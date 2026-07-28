(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.migrations.v6-to-v7 :as migrations]
            [eacl.spicedb.consistency :as consistency]
            [malli.core :as m]))

(defn cursor->token
  "Serializes an internal cursor map to an opaque string token.
  An expiry timestamp (:t, epoch seconds) is embedded only when the client is
  configured with :cursor-ttl-seconds; by default tokens do not expire —
  slow batch pagination must never silently restart (audit §7)."
  ([cursor] (cursor->token cursor nil))
  ([cursor {:keys [cursor-ttl-seconds]}]
   (when cursor
     (let [cursor' (if cursor-ttl-seconds
                     (assoc cursor :t (+ (quot (System/currentTimeMillis) 1000)
                                         cursor-ttl-seconds))
                     cursor)]
       (str "eacl1_"
         (.encodeToString
          (java.util.Base64/getEncoder)
          (.getBytes (pr-str cursor') "UTF-8")))))))

(defn token->cursor
  "Deserializes an opaque cursor token.

  Contract:
  - nil means \"first page\" and returns nil;
  - raw cursor maps pass through (backward compatibility);
  - any other non-nil input that fails to decode throws
    ex-info {:type :eacl/invalid-cursor :reason :undecodable};
  - a token carrying an expiry (:t) throws {:reason :expired} when expired and
    the client is configured with :cursor-ttl-seconds. Tokens without :t never
    expire. A bad cursor must fail loudly — decoding to nil silently restarted
    pagination from the first page (audit §7)."
  ([token-or-cursor] (token->cursor token-or-cursor nil))
  ([token-or-cursor {:keys [cursor-ttl-seconds]}]
   (cond
     (nil? token-or-cursor) nil
     (map? token-or-cursor) token-or-cursor

     (and (string? token-or-cursor)
          (.startsWith ^String token-or-cursor "eacl1_"))
     (let [cursor (try
                    (edn/read-string
                     (String. (.decode (java.util.Base64/getDecoder)
                                (.getBytes (subs token-or-cursor 6) "UTF-8"))
                       "UTF-8"))
                    (catch Exception e
                      (throw (ex-info "Invalid cursor token: cannot be decoded."
                               {:type :eacl/invalid-cursor
                                :reason :undecodable}
                               e))))]
       (when-not (map? cursor)
         (throw (ex-info "Invalid cursor token: does not decode to a cursor map."
                  {:type :eacl/invalid-cursor
                   :reason :undecodable})))
       (let [now (quot (System/currentTimeMillis) 1000)]
         (if (and cursor-ttl-seconds (:t cursor) (> now (:t cursor)))
           (throw (ex-info "Invalid cursor token: expired."
                    {:type :eacl/invalid-cursor
                     :reason :expired
                     :expired-at (:t cursor)}))
           (dissoc cursor :t))))

     :else
     (throw (ex-info "Invalid cursor token: unrecognized format."
              {:type :eacl/invalid-cursor
               :reason :undecodable
               :token token-or-cursor})))))

(defn default-internal-cursor->spice
  [db {:keys [entid->object-id]} cursor]
  (when cursor
    (cond
      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(entid->object-id db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (entid->object-id db v)]))
                           p))))

      (= 3 (:v cursor))
      cursor

      :else
      (cond
        (:resource cursor) (S/transform [:resource :id] #(entid->object-id db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(entid->object-id db %) cursor)))))

(defn default-spice-cursor->internal
  [db {:keys [object-id->entid]} cursor]
  (when cursor
    (cond
      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(object-id->entid db %))
        (:p cursor) (update :p
                       (fn [p]
                         (into {}
                           (map (fn [[k v]] [k (object-id->entid db v)]))
                           p))))

      (= 3 (:v cursor))
      cursor

      :else
      (cond
        (:resource cursor) (S/transform [:resource :id] #(object-id->entid db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(object-id->entid db %) cursor)))))

(defn object->spice
  [db {:keys [entid->object-id]} object]
  (update object :id #(entid->object-id db %)))

(defn relationship->spice
  [db opts {:keys [subject relation resource]}]
  (map->Relationship
   {:subject (object->spice db opts subject)
    :relation relation
    :resource (object->spice db opts resource)}))

(defn spiceomic-read-relationships
  [db
   {:keys [object-id->entid] :as opts}
   filters]
  (let [subject-id   (:subject/id filters)
        resource-id  (:resource/id filters)
        subject-eid  (when (some? subject-id) (object-id->entid db subject-id))
        resource-eid (when (some? resource-id) (object-id->entid db resource-id))]
    (if (or (and (some? subject-id) (nil? subject-eid))
            (and (some? resource-id) (nil? resource-eid)))
      ;; A filter names an object that does not exist: nothing can match.
      ;; A supplied-but-unresolvable ID must not be conflated with an absent
      ;; filter — that conflation degraded this query to a global scan.
      []
      (let [filters' (cond-> filters
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid))]
        (->> (impl/read-relationships db filters')
          (map #(relationship->spice db opts %)))))))

(defn- resolve-existing-object
  "Resolves an external spice object to its internal eid, verifying the entity
  actually exists. Existence is checked via datom presence because d/entid
  passes unallocated numeric eids through unchanged. Throws :eacl/unknown-object
  when the object cannot be resolved to an existing entity."
  [db object-id->entid {:keys [type id] :as obj}]
  (let [eid (when (some? id) (object-id->entid db id))]
    (if (and eid (seq (d/datoms db :eavt eid)))
      (assoc obj :id eid)
      (throw (ex-info (str "Unknown object: " (pr-str type) " with id " (pr-str id) " does not exist.")
               {:type :eacl/unknown-object
                :object {:type type :id id}})))))

(defn spice-relationship->internal
  "Resolves both relationship endpoints to existing internal eids.
  Throws :eacl/unknown-object for either endpoint rather than letting nils or
  ghost ids reach tx-data (raw :db.error/not-an-entity) or silently no-op."
  [db {:keys [object-id->entid]} {:keys [subject relation resource]}]
  {:subject (resolve-existing-object db object-id->entid subject)
   :relation relation
   :resource (resolve-existing-object db object-id->entid resource)})

(defn spiceomic-write-relationships!
  [conn opts updates]
  (let [db (d/db conn)
        tx-data (->> updates
                  (S/transform [S/ALL :relationship]
                    #(spice-relationship->internal db opts %))
                  (mapcat #(impl/tx-update-relationship db %))
                  (remove nil?))
        {:keys [db-after]} @(d/transact conn tx-data)
        basis (d/basis-t db-after)]
    {:zed/token (str basis)}))

(defn spiceomic-can?
  [db {:keys [object->entid]} subject permission resource consistency max-depth]
  (when-not (= consistency/fully-consistent consistency)
    (throw (ex-info "EACL only supports consistency/fully-consistent at this time."
             {:type :eacl/unsupported-consistency
              :consistency consistency})))
  (let [subject-type (:type subject)
        subject-eid  (object->entid db subject)
        resource-type (:type resource)
        resource-eid  (object->entid db resource)]
    (if-not (and subject-eid resource-eid)
      false
      (impl/can? db {:subject (spice-object subject-type subject-eid)
                     :permission permission
                     :resource (spice-object resource-type resource-eid)
                     :max-depth max-depth}))))

(defn spiceomic-lookup-resources
  [db
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->ident
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (log/debug 'spiceomic-lookup-resources 'query query)
  (let [internal-subject (spice-object->internal db subject)]
    (if (nil? (:id internal-subject))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      {:data [] :cursor nil}
      (->> query
        (S/setval [:subject] internal-subject)
        (S/transform [:cursor]
          (fn [token-or-cursor]
            (some->> (token->cursor token-or-cursor opts)
              (spice-cursor->internal db opts))))
        (impl/lookup-resources db)
        (S/transform [:data S/ALL]
          (fn [{:keys [type id]}]
            (spice-object type (entid->object-id db id))))
        (S/transform [:cursor]
          (fn [internal-cursor]
            (some-> (internal-cursor->spice db opts internal-cursor)
              (cursor->token opts))))))))

(defn spiceomic-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [subject-ent (spice-object->internal db subject)]
    (if (nil? (:id subject-ent))
      ;; Unknown subjects match nothing (SpiceDB-consistent; can? is false).
      {:count 0 :limit (:limit query -1) :cursor nil}
      (->> query
        (S/setval [:subject] subject-ent)
        (S/transform [:cursor]
          (fn [token-or-cursor]
            (some->> (token->cursor token-or-cursor opts)
              (spice-cursor->internal db opts))))
        (impl/count-resources db)
        (S/transform [:cursor]
          (fn [internal-cursor]
            (some-> (internal-cursor->spice db opts internal-cursor)
              (cursor->token opts))))))))

(defn spiceomic-lookup-subjects
  [db
   {:as opts
    :keys [entid->object-id
           spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [internal-resource (spice-object->internal db (:resource query))]
    (if (nil? (:id internal-resource))
      ;; Unknown resources match nothing (SpiceDB-consistent).
      {:data [] :cursor nil}
      (->> query
        (S/setval [:resource] internal-resource)
        (S/transform [:cursor]
          (fn [token-or-cursor]
            (some->> (token->cursor token-or-cursor opts)
              (spice-cursor->internal db opts))))
        (impl/lookup-subjects db)
        (S/transform [:data S/ALL]
          (fn [{:keys [type id]}]
            (spice-object type (entid->object-id db id))))
        (S/transform [:cursor]
          (fn [internal-cursor]
            (some-> (internal-cursor->spice db opts internal-cursor)
              (cursor->token opts))))))))

(defrecord Spiceomic [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency/fully-consistent nil))

  (can? [_ subject permission resource consistency]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency nil))

  (can? [_ {:keys [subject permission resource consistency max-depth]}]
    (spiceomic-can? (d/db conn) opts subject permission resource
      (or consistency consistency/fully-consistent)
      max-depth))

  (read-schema [_]
    (schema/read-schema (d/db conn)))

  (write-schema! [_ schema-string]
    (schema/write-schema! conn schema-string))

  (read-relationships [_ filters]
    (spiceomic-read-relationships (d/db conn) opts filters))

  (write-relationships! [_ updates]
    (spiceomic-write-relationships! conn opts updates))

  (write-relationship! [_ operation subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate operation (->Relationship subject relation resource))]))

  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate operation (->Relationship subject relation resource))]))

  (create-relationships! [_ relationships]
    (spiceomic-write-relationships! conn opts
      (for [rel relationships]
        (->RelationshipUpdate :create rel))))

  (create-relationship! [_ relationship]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :create relationship)]))

  (create-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :create (->Relationship subject relation resource))]))

  (delete-relationships! [_ relationships]
    (spiceomic-write-relationships! conn opts
      (for [rel relationships]
        (->RelationshipUpdate :delete rel))))

  (delete-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :delete (->Relationship subject relation resource))]))

  (delete-relationship! [_ {:keys [subject relation resource]}]
    (spiceomic-write-relationships! conn opts
      [(->RelationshipUpdate :delete (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (spiceomic-lookup-resources (d/db conn) opts query))

  (count-resources [_ query]
    (spiceomic-count-resources (d/db conn) opts query))

  (lookup-subjects [_ query]
    (spiceomic-lookup-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
             {:type :eacl/not-implemented
              :method 'expand-permission-tree}))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->ident
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds
    :auto-migrate-v6})

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config — a silently ignored key
  means silently wrong ID coercion, audit §5):
  - :entid->object-id  (fn [db eid] external-id) — canonical, as documented in the README.
  - :entity->object-id (fn [entity] external-id) — deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :cursor-ttl-seconds — optional cursor token expiry; default nil (tokens never expire).
  - :internal-cursor->spice / :spice-cursor->internal — advanced cursor coercion overrides.
  - :auto-migrate-v6 — opt-in automatic v6->v7 storage migration at startup.
    Construction fails with {:type :eacl/storage-version} when the database
    holds unmigrated v6 relationship entities (v7 would silently answer false/
    empty against them). Pass true (default options) or an eacl.migrations.v6-to-v7/migrate!
    options map, e.g. {:schema \"definition user {} ...\"} — see docs/migration-v6-to-v7.md."
  [conn
   {:as   config-opts
    :keys [entid->object-id
           entity->object-id
           object-id->ident
           internal-cursor->spice
           spice-cursor->internal
           cursor-ttl-seconds
           auto-migrate-v6]
    :or   {object-id->ident       (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
    (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                         ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
             {:type :eacl/invalid-config
              :unknown-keys (vec unknown-keys)
              :known-keys known-client-opt-keys})))
  (when (and entid->object-id entity->object-id)
    (throw (ex-info "EACL Config Error: supply only one of :entid->object-id (canonical) or :entity->object-id (deprecated alias)."
             {:type :eacl/invalid-config
              :conflicting-keys [:entid->object-id :entity->object-id]})))
  (when-not (fn? object-id->ident)
    (throw (ex-info "EACL Config Error: object-id->ident must be a fn that coerces a Spice Object ID to a Datomic ident resolvable by d/entid."
             {:type :eacl/invalid-config
              :key :object-id->ident})))
  ;; Refuse to run v7 code against unmigrated v6 relationship data — it would
  ;; silently answer every check with false/empty. Throws :eacl/storage-version
  ;; unless the DB is v7/fresh/stamped, or :auto-migrate-v6 opts into migration.
  (migrations/assert-storage-compatible! conn {:auto-migrate-v6 auto-migrate-v6})
  (let [entid->object-id (or entid->object-id
                             (when entity->object-id
                               (fn [db eid] (entity->object-id (d/entity db eid))))
                             (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid (fn [db object-id]
                           (d/entid db (object-id->ident object-id)))
        opts             {:object-id->ident object-id->ident
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(when (some? %) (object-id->entid db %))))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal
                          :cursor-ttl-seconds cursor-ttl-seconds}]
    (->Spiceomic conn opts)))
