(ns eacl.datahike.core
  (:require [com.rpl.specter :as S]
            [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate
                                        map->Relationship]]
            [eacl.cursor :as cursor]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.relay :as relationship-relay]
            [eacl.spicedb.consistency :as consistency]))

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

(defn- snapshot-adapter
  [db opts]
  (datahike-backend/snapshot-adapter db opts))

(defn- require-consistency!
  [adapter value]
  (backend/require-consistency! adapter value))

(defn- permission-dependencies
  [adapter resource-type permission]
  (let [relation-ids
        (engine/permission-relationship-eids
         adapter resource-type permission)]
    {:relation-ids relation-ids
     :schema-scope
     {:permission-nodes
      (engine/permission-schema-nodes
       adapter resource-type permission)
      :relation-ids relation-ids}}))

(defn- cached-engine-result
  [adapter opts operation query resource-type permission
   valid-value? compute]
  (let [{:keys [schema-scope relation-ids]}
        (permission-dependencies adapter resource-type permission)]
    (binding [engine/*recursive-traversal-limits*
              (:recursive-traversal-limits opts)]
      (cache/resolve!
       adapter
       (:cache-store opts)
       [operation query]
       operation
       schema-scope
       relation-ids
       valid-value?
       compute))))

(defn- with-cache-info
  [value {:keys [cached? cache-basis]}]
  (if (map? value)
    (assoc value :cached? cached? :cache-basis cache-basis)
    value))

(defn datahike-read-relationships
  [db
   {:as opts
    :keys [object-id->entid
           internal-cursor->spice
           spice-cursor->internal]}
   filters]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter (:consistency filters))
        base-filters (apply dissoc filters
                            [:first :last :after :before :consistency])
        _ (relationship-filters/validate! base-filters)
        subject-id   (:subject/id base-filters)
        resource-id  (:resource/id base-filters)
        subject-eid  (when subject-id (object-id->entid db subject-id))
        resource-eid (when resource-id (object-id->entid db resource-id))
        missing-id?  (or (and subject-id (nil? subject-eid))
                         (and resource-id (nil? resource-eid)))]
    (if missing-id?
      relay/empty-page
      (let [filters' (cond-> base-filters
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid))
            internal-relationships
            (loop [cursor nil
                   result []]
              (let [page
                    (impl/read-relationships
                     db
                     (cond-> (assoc filters' :limit 10000)
                       cursor (assoc :cursor cursor)))
                    result' (into result (:data page))
                    next-cursor (:cursor page)]
                (if (and next-cursor
                         (not= cursor next-cursor)
                         (seq (:data page)))
                  (recur next-cursor result')
                  result')))
            public-relationships
            (->> internal-relationships
                 (sort-by
                  (fn [{:keys [subject relation resource]}]
                    [(:type subject) (:id subject)
                     relation
                     (:type resource) (:id resource)]))
                 (map #(relationship->spice db opts %))
                 vec)]
        (relationship-relay/paginate
         opts
         :read-relationships
         filters
         (backend/invoke adapter :snapshot-id)
         public-relationships)))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal]} {:keys [subject relation resource]}]
  {:subject (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn datahike-write-relationships!
  [conn opts updates]
  (let [db       (d/db conn)
        tx-stamp (:tx-stamp opts)
        tx-data  (->> updates
                      (S/transform [S/ALL :relationship]
                                   #(spice-relationship->internal db opts %))
                      (mapcat #(impl/tx-update-relationship db %))
                      (remove nil?)
                      (vec))]
    (when (seq tx-data)
      (d/transact conn tx-data))
    {:zed/token (str @tx-stamp)}))

(defn datahike-delete-object!
  "Removes every relationship that references `object`, without retracting the
  object entity itself."
  [conn {:keys [object->entid tx-stamp]} object]
  (let [db (d/db conn)]
    (when-let [object-eid (object->entid db object)]
      (let [relationship-eids
            (d/q '[:find [?relationship ...]
                   :in $ ?object
                   :where
                   (or [?relationship :eacl.relationship/subject ?object]
                       [?relationship :eacl.relationship/resource ?object])]
                 db object-eid)]
        (when (seq relationship-eids)
          (d/transact conn
                      (mapv (fn [relationship-eid]
                              [:db/retractEntity relationship-eid])
                            relationship-eids))))))
  {:zed/token (str @tx-stamp)})

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn datahike-can?
  [db {:keys [spice-object->internal] :as opts}
   subject permission resource consistency]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter consistency)
        internal-subject (spice-object->internal db subject)
        internal-resource (spice-object->internal db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      false
      (:value
       (cached-engine-result
        adapter opts :can?
        [internal-subject permission internal-resource]
        (:type internal-resource)
        permission
        boolean?
        #(engine/can?
          adapter internal-subject permission internal-resource))))))

(defn datahike-lookup-resources
  [db
   {:as opts
    :keys [spice-object->internal
           entid->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter (:consistency query))
        internal-subject (spice-object->internal db subject)]
    (if (nil? (:id internal-subject))
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (dissoc :consistency)
                (#(relay/internalize-page-query
                   adapter opts :lookup-resources %))
                (assoc :subject internal-subject))
            answer
            (cached-engine-result
             adapter opts :lookup-resources internal-query
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (vector? (:data %))
                   (map? (:page-info %)))
             #(engine/lookup-resources adapter internal-query))]
        (with-cache-info
          (relay/externalize-page
           adapter opts :lookup-resources query (:value answer))
          answer)))))

(defn datahike-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter (:consistency query))
        internal-subject (spice-object->internal db subject)]
    (if-not (:id internal-subject)
      (assoc
       (cond-> {:count 0 :limit (or (:count-limit query) -1)}
         (contains? query :count-limit) (assoc :truncated? false))
       :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (assoc :subject internal-subject)
                (dissoc :consistency))
            answer
            (cached-engine-result
             adapter opts :count-resources internal-query
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-resources adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn datahike-lookup-subjects
  [db
   {:as opts
    :keys [entid->object-id
           spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter (:consistency query))
        internal-resource (spice-object->internal db (:resource query))]
    (when (contains? query :subject/relation)
      (throw (ex-info ":subject/relation is not supported by lookup-subjects."
                      {:eacl/error :eacl.pagination/unsupported-filter
                       :filter :subject/relation})))
    (if-not (:id internal-resource)
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (dissoc :consistency)
                (#(relay/internalize-page-query
                   adapter opts :lookup-subjects %))
                (assoc :resource internal-resource))
            answer
            (cached-engine-result
             adapter opts :lookup-subjects internal-query
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (vector? (:data %))
                   (map? (:page-info %)))
             #(engine/lookup-subjects adapter internal-query))]
        (with-cache-info
          (relay/externalize-page
           adapter opts :lookup-subjects query (:value answer))
          answer)))))

(defn datahike-count-subjects
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [adapter (snapshot-adapter db opts)
        _ (require-consistency! adapter (:consistency query))
        internal-resource (spice-object->internal db (:resource query))]
    (if-not (:id internal-resource)
      (assoc
       (cond-> {:count 0 :limit (or (:count-limit query) -1)}
         (contains? query :count-limit) (assoc :truncated? false))
       :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (assoc :resource internal-resource)
                (dissoc :consistency))
            answer
            (cached-engine-result
             adapter opts :count-subjects internal-query
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-subjects adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defrecord DatahikeAuthorization [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (datahike-can? (d/db conn) opts subject permission resource consistency/fully-consistent))
  (can? [_ subject permission resource consistency]
    (datahike-can? (d/db conn) opts subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency]}]
    (datahike-can? (d/db conn) opts subject permission resource
                   (or consistency consistency/fully-consistent)))

  (read-schema [_]
    (schema/read-schema (d/db conn)))
  (write-schema! [_ schema-string]
    (schema/write-schema! conn schema-string))

  (read-relationships [_ filters]
    (datahike-read-relationships (d/db conn) opts filters))
  (write-relationships! [_ updates]
    (datahike-write-relationships! conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate operation
                                                          (->Relationship subject relation resource))]))
  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate operation
                                                          (->Relationship subject relation resource))]))
  (create-relationships! [_ relationships]
    (datahike-write-relationships! conn opts
                                   (for [rel relationships]
                                     (->RelationshipUpdate :create rel))))
  (create-relationship! [_ relationship]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :create relationship)]))
  (create-relationship! [_ subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :create (->Relationship subject relation resource))]))
  (delete-relationships! [_ relationships]
    (datahike-write-relationships! conn opts
                                   (for [rel (relationship-seq relationships)]
                                     (->RelationshipUpdate :delete rel))))
  (delete-object! [_ object]
    (datahike-delete-object! conn opts object))
  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (datahike-lookup-resources (d/db conn) opts query))
  (count-resources [_ query]
    (datahike-count-resources (d/db conn) opts query))
  (lookup-subjects [_ query]
    (datahike-lookup-subjects (d/db conn) opts query))
  (count-subjects [_ query]
    (datahike-count-subjects (d/db conn) opts query))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
                    {:type :eacl/not-implemented
                     :method (quote expand-permission-tree)}))))

(defonce runtime-state-registry (atom {}))

(defn- schema-transaction?
  "Whether `tx-report` touched EACL's schema, and so invalidated the permission
   paths and the schema catalog.

   The ident resolution is load-bearing, not defensive: under
   `:attribute-refs? true` a datom's `:a` is a numeric ref, so comparing it
   against a set of keywords matches nothing and the derived caches are never
   invalidated — a schema change would keep answering from stale permission
   paths. `:db-after` is used because a retraction's attribute must still
   resolve."
  [tx-report]
  (let [db (:db-after tx-report)]
    (boolean
     (some (fn [{:keys [a]}]
             (contains? schema/schema-change-attrs (ddb/attr-ident db a)))
           (:tx-data tx-report)))))

(defn- reset-schema-derived-state!
  [state]
  (swap! (:schema-stamp state) inc)
  (reset! (:permission-paths-cache state) {})
  (reset! (:schema-catalog state) nil))

(defn ensure-runtime-state!
  [conn]
  (if-let [state (get @runtime-state-registry conn)]
    state
    (let [state {:conn-id (random-uuid)
                 :tx-stamp (atom 0)
                 :schema-stamp (atom 0)
                 :permission-paths-cache (atom {})
                 :schema-catalog (atom nil)
                 :listener-key (keyword (str "eacl-stamp-" (random-uuid)))}]
      (d/listen conn
                (:listener-key state)
                (fn [tx-report]
                  (swap! (:tx-stamp state) inc)
                  (when (schema-transaction? tx-report)
                    (reset-schema-derived-state! state))))
      (get (swap! runtime-state-registry
                  #(if (contains? % conn) % (assoc % conn state)))
           conn))))

(defn- ensure-schema-catalog!
  [state db]
  (let [schema-stamp @(:schema-stamp state)
        cached       @(:schema-catalog state)]
    (if (= schema-stamp (:schema-stamp cached))
      (:catalog cached)
      (let [catalog (impl/build-schema-catalog db)]
        (-> (swap! (:schema-catalog state)
                   (fn [entry]
                     (if (= schema-stamp (:schema-stamp entry))
                       entry
                       {:schema-stamp schema-stamp
                        :catalog      catalog})))
            :catalog)))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->lookup-ref
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds
    :cache
    :recursive-traversal-limits})

(defn make-client
  "Builds an IAuthorization client over a datahike conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :entity->object-id (fn [entity] external-id) - deprecated alias; do not combine.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cursor-ttl-seconds - optional cursor token expiry; default nil (tokens never expire).
  - :internal-cursor->spice / :spice-cursor->internal - advanced cursor coercion overrides."
  [conn
   {:as   config-opts
    :keys [entid->object-id
           entity->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal
           cursor-ttl-seconds
           cache
           recursive-traversal-limits]
    :or   {object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
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
  (let [runtime-state    (ensure-runtime-state! conn)
        object-id->entid (fn [db object-id]
                           (ddb/entid db (object-id->lookup-ref object-id)))
        entid->object-id (or entid->object-id
                             (when entity->object-id
                               (fn [db eid] (entity->object-id (d/entity db eid))))
                             (fn [db eid] (:eacl/id (d/entity db eid))))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :cache-stamp (fn []
                                         [(:conn-id runtime-state)
                                          @(:schema-stamp runtime-state)])
                          :tx-stamp (:tx-stamp runtime-state)
                          :permission-paths-cache (:permission-paths-cache runtime-state)
                          :schema-catalog (fn [db]
                                            (ensure-schema-catalog! runtime-state db))
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :cursor-ttl-seconds cursor-ttl-seconds
                          :cache-store (eacl.cache/cache-store cache)
                          :recursive-traversal-limits
                          (engine/normalize-recursive-traversal-limits
                           recursive-traversal-limits)
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(object-id->entid db %)))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->DatahikeAuthorization conn opts)))

(defn create-conn
  "A datahike connection carrying EACL's schema. See
  `eacl.datahike.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema))
  ([extra-schema config] (schema/create-conn extra-schema config)))
