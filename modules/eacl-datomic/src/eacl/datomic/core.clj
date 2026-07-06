(ns eacl.datomic.core
  "Reifies eacl.core/IAuthorization for Datomic-backed EACL in eacl.datomic.impl."
  (:require [clojure.tools.logging :as log]
            [com.rpl.specter :as S]
            [datomic.api :as d]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.spicedb.consistency :as consistency]
            [malli.core :as m]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(defn default-internal-cursor->spice
  [db {:keys [entid->object-id]} cursor]
  (when cursor
    (cond
      (= 3 (:v cursor))
      (cond-> cursor
        (:subject cursor) (update :subject #(entid->object-id db %))
        (:resource cursor) (update :resource #(entid->object-id db %)))

      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(entid->object-id db %))
        (:p cursor) (update :p
                            (fn [p]
                              (into {}
                                    (map (fn [[k v]] [k (entid->object-id db v)]))
                                    p))))
      :else
      (cond
        (:resource cursor) (S/transform [:resource :id] #(entid->object-id db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(entid->object-id db %) cursor)))))

(defn default-spice-cursor->internal
  [db {:keys [object-id->entid]} cursor]
  (when cursor
    (cond
      (= 3 (:v cursor))
      (cond-> cursor
        (:subject cursor) (update :subject #(object-id->entid db %))
        (:resource cursor) (update :resource #(object-id->entid db %)))

      (= 2 (:v cursor))
      (cond-> cursor
        (:e cursor) (update :e #(object-id->entid db %))
        (:p cursor) (update :p
                            (fn [p]
                              (into {}
                                    (map (fn [[k v]] [k (object-id->entid db v)]))
                                    p))))
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
   {:as opts
    :keys [object-id->entid
           internal-cursor->spice
           spice-cursor->internal]}
   filters]
  (let [subject-id   (:subject/id filters)
        resource-id  (:resource/id filters)
        subject-eid  (when subject-id (object-id->entid db subject-id))
        resource-eid (when resource-id (object-id->entid db resource-id))
        missing-id?  (or (and subject-id (nil? subject-eid))
                         (and resource-id (nil? resource-eid)))]
    (if missing-id?
      {:data [] :cursor nil}
      (let [filters' (cond-> filters
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid))
            filters'' (S/transform [:cursor]
                                   (fn [token-or-cursor]
                                     (some->> (token->cursor token-or-cursor opts)
                                              (spice-cursor->internal db opts)))
                                   filters')
            result    (impl/read-relationships db filters'')]
        (-> result
            ((fn [page]
               (S/transform [:data S/ALL]
                            #(relationship->spice db opts %)
                            page)))
            ((fn [page]
               (S/transform [:cursor]
                            (fn [internal-cursor]
                              (some-> (internal-cursor->spice db opts internal-cursor)
                                      (cursor->token opts)))
                            page))))))))

(defn- resolve-existing-object
  "Resolves an external spice object to its internal eid, verifying the entity
  actually exists (datom presence - d/entid passes unallocated numeric eids
  through unchanged). Throws :eacl/unknown-object otherwise (audit 11)."
  [db object-id->entid {:keys [type id] :as obj}]
  (let [eid (when (some? id) (object-id->entid db id))]
    (if (and eid (seq (d/datoms db :eavt eid)))
      (assoc obj :id eid)
      (throw (ex-info (str "Unknown object: " (pr-str type) " with id " (pr-str id) " does not exist.")
                      {:type :eacl/unknown-object
                       :object {:type type :id id}})))))

(defn spice-relationship->internal
  "Resolves both relationship endpoints to existing internal eids.
  Throws :eacl/unknown-object rather than letting nils or ghost ids reach
  tx-data (raw :db.error/not-an-entity) or silently no-op."
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

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn spiceomic-can?
  [db {:keys [object->entid]} subject permission resource consistency]
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
      (impl/can? db
                 (spice-object subject-type subject-eid)
                 permission
                 (spice-object resource-type resource-eid)))))

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
  (->> query
       (S/transform [:resource] #(spice-object->internal db %))
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
                              (cursor->token opts))))))

(defn spiceomic-count-subjects
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (->> query
       (S/transform [:resource] #(spice-object->internal db %))
       (S/transform [:cursor]
                    (fn [token-or-cursor]
                      (some->> (token->cursor token-or-cursor opts)
                               (spice-cursor->internal db opts))))
       (impl/count-subjects db)
       (S/transform [:cursor]
                    (fn [internal-cursor]
                      (some-> (internal-cursor->spice db opts internal-cursor)
                              (cursor->token opts))))))

(defrecord Spiceomic [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency/fully-consistent))

  (can? [_ subject permission resource consistency]
    (spiceomic-can? (d/db conn) opts subject permission resource consistency))

  (can? [_ {:keys [subject permission resource consistency]}]
    (spiceomic-can? (d/db conn) opts subject permission resource
                    (or consistency consistency/fully-consistent)))

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
                                    [(->RelationshipUpdate operation
                                                           (->Relationship subject relation resource))]))

  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate operation
                                                           (->Relationship subject relation resource))]))

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
                                    (for [rel (relationship-seq relationships)]
                                      (->RelationshipUpdate :delete rel))))

  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate :delete
                                                           (->Relationship subject relation resource))]))

  (delete-relationship! [_ subject relation resource]
    (spiceomic-write-relationships! conn opts
                                    [(->RelationshipUpdate :delete
                                                           (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (spiceomic-lookup-resources (d/db conn) opts query))

  (count-resources [_ query]
    (spiceomic-count-resources (d/db conn) opts query))

  (lookup-subjects [_ query]
    (spiceomic-lookup-subjects (d/db conn) opts query))

  (count-subjects [_ query]
    (spiceomic-count-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
                    {:type :eacl/not-implemented
                     :method (quote expand-permission-tree)}))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->ident
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds})

(defn make-client
  "Builds an IAuthorization client over a Datomic conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical, as documented in the README.
  - :entity->object-id (fn [entity] external-id) - deprecated alias; do not combine with the above.
  - :object-id->ident  (fn [external-id] ident-resolvable-by-d-entid). Default: [:eacl/id id].
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides."
  [conn
   {:as   config-opts
    :keys [entid->object-id
           entity->object-id
           object-id->ident
           internal-cursor->spice
           spice-cursor->internal
           cursor-ttl-seconds]
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
  (let [entid->object-id (or entid->object-id
                             (when entity->object-id
                               (fn [db eid] (entity->object-id (d/entity db eid))))
                             (fn [db eid] (:eacl/id (d/entity db eid))))
        object-id->entid (fn [db object-id]
                           (d/entid db (object-id->ident object-id)))
        opts             {:object-id->ident object-id->ident
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :cursor-ttl-seconds cursor-ttl-seconds
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(when (some? %) (object-id->entid db %))))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->Spiceomic conn opts)))
