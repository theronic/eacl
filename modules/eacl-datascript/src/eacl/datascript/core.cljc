(ns eacl.datascript.core
  (:require [com.rpl.specter :as S]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate]]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.mutation :as journal]
            [eacl.datascript.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.mutation :as mutation]
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]
            [eacl.spicedb.consistency :as consistency]))

(def cursor->token cursor/cursor->token)
(def token->cursor cursor/token->cursor)

(defn- transform-frontier
  [f frontier]
  (if (= :exhausted frontier)
    frontier
    (f frontier)))

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
                                    (map (fn [[k v]]
                                           [k (transform-frontier
                                               #(entid->object-id db %)
                                               v)]))
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
                                    (map (fn [[k v]]
                                           [k (transform-frontier
                                               #(object-id->entid db %)
                                               v)]))
                                    p))))
      :else
      (cond
        (:resource cursor) (S/transform [:resource :id] #(object-id->entid db %) cursor)
        (:subject cursor) (S/transform [:subject :id] #(object-id->entid db %) cursor)))))

(defn- snapshot-adapter
  [db opts]
  (datascript-backend/snapshot-adapter db opts))

(defn- selected-context
  [db opts consistency-value]
  (let [descriptor (consistency/descriptor consistency-value)
        source-adapter (snapshot-adapter db opts)
        selection
        (if (#{:local-snapshot :minimize-latency} (:mode descriptor))
          {:adapter source-adapter
           :descriptor descriptor
           :request-token nil
           :response-token nil}
          (consistency-v3/select
           source-adapter
           consistency-value
           {:format-options (:format-options opts)
            :coherence-authority (:coherence-authority opts)
            :issue-token? false
            :timeout-ms (:consistency-sync-timeout-ms opts)}))
        adapter (:adapter selection)]
    {:adapter adapter
     :db (:db (backend/state adapter))
     :selection selection
     :completed-cache?
     (and (:completed-cache-request? opts)
          (not= :at-exact-snapshot
                (get-in selection [:descriptor :mode])))}))

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

(defn- cursor-options
  [_adapter opts selection _resource-type _permission]
  (assoc opts
         :cursor-consistency-mode
         (get-in selection [:descriptor :mode])
         :timeout-ms (:consistency-sync-timeout-ms opts)))

(defn- page-context
  [adapter opts selection operation query resource-type permission]
  (let [current-opts
        (cursor-options
         adapter opts selection resource-type permission)
        prepared
        (relay/prepare-page-query
         adapter current-opts operation query)
        page-adapter
        (:adapter prepared)
        page-opts
        (assoc
         (cursor-options
          page-adapter opts selection resource-type permission)
         :cursor-recovery (:recovery prepared)
         :completed-cache?
         (and
          (:completed-cache-request? opts)
          (not= :at-exact-snapshot
                (get-in selection [:descriptor :mode]))
          ;; Cursor authentication and snapshot selection happen before this
          ;; decision. A continuation that still resolves to the selected
          ;; current DB is therefore safe to cache; a historical continuation
          ;; selects another immutable DB and continues to bypass this cache.
          (identical?
           (:db (backend/state adapter))
           (:db (backend/state page-adapter)))))]
    {:adapter page-adapter
     :db (:db (backend/state page-adapter))
     :opts page-opts
     :query (:query prepared)
     :recovery (:recovery prepared)}))

(defn- datom-proof
  [db entity attribute]
  (when-let [datom (first (ds/datoms db :eavt entity attribute))]
    [(:tx datom) (:v datom)]))

(defn- managed-cache-descriptor
  [db relation-ids]
  (let [relation-ids (vec (distinct relation-ids))]
    (when (seq relation-ids)
      (let [schema-eid
            (ds/entid db [:eacl/id mutation/schema-entity-id])
            schema-stamp
            (when schema-eid
              (datom-proof
               db schema-eid mutation/schema-mutation-id-attr))
            relation-stamps
            (keep
             (fn [relation-id]
               (when-let [tx
                          (datom-proof
                           db relation-id
                           mutation/relation-mutation-id-attr)]
                 [relation-id tx]))
             relation-ids)]
        (when (and (subproblem/proof-stamp? schema-stamp)
                   (= (count relation-ids)
                      (count relation-stamps))
                   (every? (comp subproblem/proof-stamp? second)
                           relation-stamps))
          {:schema-stamp schema-stamp
           :dependency-stamp
           (mapv
            (fn [[relation-id [tx mutation-id]]]
              [relation-id tx mutation-id])
            (sort-by first relation-stamps))})))))

(defn- cached-engine-result
  [adapter opts operation query resource-type permission
   valid-value? compute]
  (let [schema-cache
        (delay
          (engine/schema-cache-for!
           (:derived-schema-caches opts)
           adapter))
        evaluate
        #(binding [engine/*schema-cache* @schema-cache
                   engine/*recursive-traversal-limits*
                   (:recursive-traversal-limits opts)]
           (compute))
        cacheable?
        (and (:current-cache-store opts)
             (:completed-cache? opts))]
    (if-not cacheable?
      (do
        (cache/record-current-bypass!
         (:current-cache-store opts))
        {:value (evaluate)
         :cached? false
         :cache-tier nil
         :cache-basis nil})
      (let [dependencies
            (delay
              (binding [engine/*schema-cache* @schema-cache]
                (permission-dependencies
                 adapter resource-type permission)))
            db (:db (backend/state adapter))
            semantic-key
            {:operation operation
             :query query
             :engine-version engine/engine-version
             :adapter-fingerprint (:adapter-fingerprint opts)
             :recursive-traversal-limits
             (:recursive-traversal-limits opts)}]
        (cache/resolve-current!
         (:current-cache-store opts)
         {:snapshot db
          :snapshot-order (:max-tx db)
          :same-snapshot? identical?
          :cache-basis (backend/invoke adapter :snapshot-id)
          :managed-descriptor-key-fn
          (when (:managed-cache-enabled? opts)
            #(vec (sort (distinct (:relation-ids @dependencies)))))
          :managed-key-fn
          (when (:managed-cache-enabled? opts)
            #(managed-cache-descriptor
              db (:relation-ids @dependencies)))
          :managed-subproblem-key-fn
          (when (:managed-cache-enabled? opts)
            #(managed-cache-descriptor db [%]))
          :managed-subproblem-scope
          (backend/invoke adapter :source-scope)}
         semantic-key
         operation
         valid-value?
         evaluate)))))

(defn- with-cache-info
  [value {:keys [cached? cache-basis]}]
  (if (map? value)
    (assoc value :cached? cached? :cache-basis cache-basis)
    value))

(defn datascript-read-relationships
  [db
   {:as opts
   :keys [object-id->entid]}
   filters]
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency filters))
        {adapter :adapter page-db :db cursor-opts :opts
         page-query :query}
        (page-context
         source-adapter opts selection :read-relationships filters nil nil)
        base-filters (apply dissoc filters
                            [:first :last :after :before :consistency])
        _ (relationship-filters/validate! base-filters)
        subject-id (:subject/id base-filters)
        resource-id (:resource/id base-filters)
        subject-eid (when subject-id
                      (object-id->entid page-db subject-id))
        resource-eid (when resource-id
                       (object-id->entid page-db resource-id))
        internal-query
        (-> page-query
            (dissoc :consistency)
            (cond->
              subject-id (assoc :subject/id subject-eid)
              resource-id (assoc :resource/id resource-eid)))]
    (if (or (and subject-id (nil? subject-eid))
            (and resource-id (nil? resource-eid)))
      relay/empty-page
      (relay/externalize-relationship-page
       adapter
       cursor-opts
       :read-relationships
       filters
       (impl/read-relationships
        page-db internal-query (:engine-selection cursor-opts))))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal]} {:keys [subject relation resource]}]
  {:subject (spice-object->internal db subject)
   :relation relation
   :resource (spice-object->internal db resource)})

(defn- response-token
  [db opts]
  (when (= :managed (:coherence-authority opts))
    (let [adapter (snapshot-adapter db opts)]
      (causal-token/issue
       (:format-options opts)
       (merge
        {:backend :datascript}
        (backend/invoke adapter :source-scope)
        (backend/invoke adapter :graph-head))))))

(defn- write-response
  [db opts]
  (if-let [token (response-token db opts)]
    {:zed/token token}
    {}))

(defn datascript-write-relationships!
  [conn opts updates]
  (let [updates (vec updates)
        _ (doseq [{:keys [operation]} updates]
            (impl/validate-relationship-operation! operation))
        db      (ds/db conn)
        internal-updates
        (S/transform [S/ALL :relationship]
                     #(spice-relationship->internal db opts %)
                     updates)
        relation-ids
        (->> internal-updates
             (map (comp #(impl/relationship-relation-id db %)
                        :relationship))
             distinct
             vec)
        tx-data (->> internal-updates
                     (mapcat #(impl/tx-update-relationship db %))
                     (remove nil?)
                     vec)]
    (if (seq tx-data)
      (let [mutation-id (mutation/new-id)
            report
            (journal/transact!
             conn
             {:mutation-id mutation-id
              :kind :relationships
              :canonical-data
              {:operation :write-relationships
               :updates internal-updates}
              :relation-ids relation-ids
              :token-ttl-seconds (:token-ttl-seconds opts)
              :retention-grace-seconds
              (:retention-grace-seconds opts)
              :tx-data tx-data})]
        (write-response (:db-after report) opts))
      (do
        (journal/ensure-migrated! conn)
        (write-response (ds/db conn) opts)))))

(def ^:private relationship-attrs
  #{schema/forward-relationship-attr
    schema/reverse-relationship-attr})

(defn- relationship-retraction-count
  [tx-data]
  (count
   (filter
    (fn [{:keys [a added]}]
      (and (false? added)
           (contains? relationship-attrs a)))
    tx-data)))

(defn datascript-delete-object!
  "Removes every relationship that references `object`, without retracting the
  object entity itself."
  [conn {:keys [object->entid] :as opts} object]
  (let [db (ds/db conn)
        object-eid
        (or (try
              (object->entid db object)
              (catch #?(:clj Exception :cljs :default) _
                nil))
            (when (number? (:id object))
              (:id object)))
        tx-data (impl/tx-delete-object db object-eid)]
    (if (seq tx-data)
      (let [report
            (journal/transact!
             conn
             {:mutation-id (mutation/new-id)
              :kind :object-deletion
              :canonical-data
              {:operation :delete-object
               :object object
               :tx-data tx-data}
              :relation-ids (impl/affected-relation-ids tx-data)
              :token-ttl-seconds (:token-ttl-seconds opts)
              :retention-grace-seconds
              (:retention-grace-seconds opts)
              :tx-data tx-data})]
        (assoc (write-response (:db-after report) opts)
               :retracted-datoms
               (relationship-retraction-count (:tx-data report))))
      (do
        (journal/ensure-migrated! conn)
        (assoc (write-response (ds/db conn) opts)
               :retracted-datoms 0)))))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn datascript-can?
  [db {:keys [spice-object->internal] :as opts}
   subject permission resource consistency]
  (let [{selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context db opts consistency)
        opts (assoc opts :completed-cache? completed-cache?)
        internal-subject (spice-object->internal selected-db subject)
        internal-resource (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      false
      (:value
       (cached-engine-result
        adapter opts :can?
        {:public [subject permission resource]
         :internal
         [internal-subject permission internal-resource]}
        (:type internal-resource)
        permission
        boolean?
        #(engine/can?
          adapter internal-subject permission internal-resource))))))

(defn datascript-lookup-resources
  [db
   {:as opts
   :keys [spice-object->internal
           entid->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal]}
   {:as query :keys [subject]}]
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts
         page-query :query}
        (page-context
         source-adapter opts selection :lookup-resources query
         (:resource/type query) (:permission query))
        internal-subject (spice-object->internal selected-db subject)]
    (if (nil? (:id internal-subject))
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (or
       (relay/lookup-visited-page
        adapter cursor-opts :lookup-resources query)
       (let [internal-query
             (-> page-query
                 (dissoc :consistency)
                 (assoc :subject internal-subject))
             answer
             (cached-engine-result
              adapter cursor-opts :lookup-resources
              {:public (dissoc query :consistency)
               :internal internal-query}
              (:resource/type internal-query)
              (:permission internal-query)
              #(and (map? %) (vector? (:data %))
                    (map? (:page-info %)))
              #(engine/lookup-resources adapter internal-query))
             page
             (with-cache-info
               (binding [subproblem/*store*
                         (:subproblem-store answer)]
                 (relay/externalize-page
                  adapter cursor-opts :lookup-resources query
                  (:value answer)))
               answer)]
         (relay/remember-visited-page!
          adapter cursor-opts :lookup-resources query page))))))

(defn datascript-count-resources
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   {:as query :keys [subject]}]
  (let [{selected-db :db adapter :adapter selection :selection
         completed-cache? :completed-cache?}
        (selected-context db opts (:consistency query))
        opts (assoc opts :completed-cache? completed-cache?)
        internal-subject (spice-object->internal selected-db subject)]
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
             adapter opts :count-resources
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-resources adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn datascript-lookup-subjects
  [db
   {:as opts
    :keys [entid->object-id
           spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts
         page-query :query}
        (page-context
         source-adapter opts selection :lookup-subjects query
         (:type (:resource query)) (:permission query))
        internal-resource
        (spice-object->internal selected-db (:resource query))]
    (when (contains? query :subject/relation)
      (throw (ex-info ":subject/relation is not supported by lookup-subjects."
                      {:eacl/error :eacl.pagination/unsupported-filter
                       :filter :subject/relation})))
    (if-not (:id internal-resource)
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (or
       (relay/lookup-visited-page
        adapter cursor-opts :lookup-subjects query)
       (let [internal-query
             (-> page-query
                 (dissoc :consistency)
                 (assoc :resource internal-resource))
             answer
             (cached-engine-result
              adapter cursor-opts :lookup-subjects
              {:public (dissoc query :consistency)
               :internal internal-query}
              (:type (:resource internal-query))
              (:permission internal-query)
              #(and (map? %) (vector? (:data %))
                    (map? (:page-info %)))
              #(engine/lookup-subjects adapter internal-query))
             page
             (with-cache-info
               (binding [subproblem/*store*
                         (:subproblem-store answer)]
                 (relay/externalize-page
                  adapter cursor-opts :lookup-subjects query
                  (:value answer)))
               answer)]
         (relay/remember-visited-page!
          adapter cursor-opts :lookup-subjects query page))))))

(defn datascript-count-subjects
  [db
   {:as opts
    :keys [spice-object->internal
           spice-cursor->internal
           internal-cursor->spice]}
   query]
  (let [{selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context db opts (:consistency query))
        opts (assoc opts :completed-cache? completed-cache?)
        internal-resource
        (spice-object->internal selected-db (:resource query))]
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
             adapter opts :count-subjects
             {:public (dissoc query :consistency)
              :internal internal-query}
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-subjects adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn- request-cache-enabled?
  [cache-option]
  (when-not (or (nil? cache-option) (boolean? cache-option))
    (throw (ex-info "EACL Error: per-request :cache? must be true or false."
                    {:type :eacl/invalid-request
                     :key :cache?
                     :value cache-option})))
  (not (false? cache-option)))

(defrecord DataScriptAuthorization [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (datascript-can? (ds/db conn) (assoc opts :completed-cache-request? true)
                     subject permission resource consistency/local-snapshot))
  (can? [_ subject permission resource consistency]
    (datascript-can? (ds/db conn) (assoc opts :completed-cache-request? true)
                     subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency] cache? :cache?}]
    (datascript-can? (ds/db conn)
                     (assoc opts :completed-cache-request?
                            (request-cache-enabled? cache?))
                     subject permission resource
                     (or consistency consistency/local-snapshot)))

  (read-schema [_]
    (schema/read-schema (ds/db conn)))
  (write-schema! [_ schema-string]
    (let [result
          (schema/write-schema!
           conn schema-string
           (select-keys opts
                        [:token-ttl-seconds
                         :retention-grace-seconds]))]
      (when-not (:eacl.mutation/no-op? result)
        (reset! (:derived-schema-caches opts) {})
        (when-let [store (:current-cache-store opts)]
          (cache/expire-current! store)))
      (merge result
             (write-response (:eacl.mutation/db-after result) opts))))

  (read-relationships [_ filters]
    (request-cache-enabled? (:cache? filters))
    (datascript-read-relationships (ds/db conn) opts
                                   (dissoc filters :cache?)))
  (write-relationships! [_ updates]
    (datascript-write-relationships! conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate operation
                                                            (->Relationship subject relation resource))]))
  (write-relationship! [_ {:as demand :keys [operation subject relation resource]}]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate operation
                                                            (->Relationship subject relation resource))]))
  (create-relationships! [_ relationships]
    (datascript-write-relationships! conn opts
                                     (for [rel relationships]
                                       (->RelationshipUpdate :create rel))))
  (create-relationship! [_ relationship]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate :create relationship)]))
  (create-relationship! [_ subject relation resource]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate :create (->Relationship subject relation resource))]))
  (delete-relationships! [_ relationships]
    (datascript-write-relationships! conn opts
                                     (for [rel (relationship-seq relationships)]
                                       (->RelationshipUpdate :delete rel))))
  (delete-object! [_ object]
    (datascript-delete-object! conn opts object))
  (delete-relationship! [_ {:as relationship :keys [subject relation resource]}]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate :delete
                                                            (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (datascript-write-relationships! conn opts
                                     [(->RelationshipUpdate :delete
                                                            (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (datascript-lookup-resources
     (ds/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (count-resources [_ query]
    (datascript-count-resources
     (ds/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (lookup-subjects [_ query]
    (datascript-lookup-subjects
     (ds/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (count-subjects [_ query]
    (datascript-count-subjects
     (ds/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
                    {:type :eacl/not-implemented
                     :method (quote expand-permission-tree)}))))

(defn expire-cache!
  "Expires every completed answer owned by one DataScript EACL client."
  [client]
  (when-not (instance? DataScriptAuthorization client)
    (throw (ex-info "expire-cache! requires a DataScript EACL client."
                    {:type :eacl/invalid-client})))
  (when-let [store (get-in client [:opts :current-cache-store])]
    (cache/expire-current! store))
  (cursor/clear-codec-cache!
   (get-in client [:opts :cursor-codec-cache]))
  (relay/clear-page-navigation-cache!
   (get-in client [:opts :page-navigation-cache]))
  nil)

(defn cache-stats
  "Returns private completed-cache counters for one DataScript EACL client."
  [client]
  (when-not (instance? DataScriptAuthorization client)
    (throw (ex-info "cache-stats requires a DataScript EACL client."
                    {:type :eacl/invalid-client})))
  (if-let [store (get-in client [:opts :current-cache-store])]
    (cache/current-cache-stats store)
    {:disabled? true}))

(def ^:private known-client-opt-keys
  #{:entid->object-id
    :entity->object-id
    :object-id->lookup-ref
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds
    :cache
    :recursive-traversal-limits
    :security-key
    :security-keyring
    :security-kid
    :token-ttl-seconds
    :retention-grace-seconds
    :coherence-authority
    :proof-mode
    :adapter-fingerprint
    :adapter-deterministic?
    :consistency-sync-timeout-ms
    :exact-snapshot-registry-size
    :engine-selection})

(defn make-client
  "Builds an IAuthorization client over a DataScript conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
  - :entity->object-id (fn [entity] external-id) - deprecated alias; do not combine.
  - :object-id->lookup-ref (fn [external-id] lookup-ref). Default: [:eacl/id id].
  - :cache - omitted creates a bounded client-private current-generation
    cache; eacl.cache/no-cache disables it; {:max-entries n} bounds it.
    :coherence-authority :managed enables relation-stamp reuse across
    unrelated forward transactions. Unknown authority remains exact-current.
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
           recursive-traversal-limits
           security-key
           security-keyring
           security-kid
           token-ttl-seconds
           retention-grace-seconds
           coherence-authority
           proof-mode
           adapter-fingerprint
           adapter-deterministic?
           consistency-sync-timeout-ms
           exact-snapshot-registry-size
           engine-selection]
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
  (when (and security-key security-keyring)
    (throw (ex-info "EACL Config Error: supply only one of :security-key or :security-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:security-key :security-keyring]})))
  (when-not (contains? #{nil :unknown :managed} coherence-authority)
    (throw (ex-info "EACL Config Error: :coherence-authority must be :managed or :unknown."
                    {:type :eacl/invalid-config
                     :key :coherence-authority
                     :value coherence-authority})))
  (when-not (contains? #{nil :auto :mutation :content :none} proof-mode)
    (throw (ex-info "EACL Config Error: unsupported :proof-mode."
                    {:type :eacl/invalid-config
                     :key :proof-mode
                     :value proof-mode})))
  (when (and (= :mutation proof-mode)
             (not= :managed coherence-authority))
    (throw (ex-info "EACL Config Error: mutation proof requires managed writer authority."
                    {:type :eacl/invalid-config
                     :key :proof-mode
                     :value proof-mode})))
  (when (and (contains? config-opts :adapter-deterministic?)
             (not (boolean? adapter-deterministic?)))
    (throw (ex-info "EACL Config Error: :adapter-deterministic? must be boolean."
                    {:type :eacl/invalid-config
                     :key :adapter-deterministic?
                     :value adapter-deterministic?})))
  (when adapter-fingerprint
    (try
      (secure/encode-canonical adapter-fingerprint)
      (catch #?(:clj Exception :cljs :default) error
        (throw (ex-info "EACL Config Error: :adapter-fingerprint must be portable canonical data."
                        {:type :eacl/invalid-config
                         :key :adapter-fingerprint}
                        error)))))
  (when (and token-ttl-seconds
             (not (and (integer? token-ttl-seconds)
                       (pos? token-ttl-seconds))))
    (throw (ex-info "EACL Config Error: :token-ttl-seconds must be positive."
                    {:type :eacl/invalid-config
                     :key :token-ttl-seconds
                     :value token-ttl-seconds})))
  (when (and consistency-sync-timeout-ms
             (not (and (integer? consistency-sync-timeout-ms)
                       (pos? consistency-sync-timeout-ms))))
    (throw (ex-info "EACL Config Error: :consistency-sync-timeout-ms must be positive."
                    {:type :eacl/invalid-config
                     :key :consistency-sync-timeout-ms
                     :value consistency-sync-timeout-ms})))
  (when (and exact-snapshot-registry-size
             (not (and (integer? exact-snapshot-registry-size)
                       (pos? exact-snapshot-registry-size))))
    (throw (ex-info "EACL Config Error: :exact-snapshot-registry-size must be positive."
                    {:type :eacl/invalid-config
                     :key :exact-snapshot-registry-size
                     :value exact-snapshot-registry-size})))
  (let [_ (journal/ensure-migrated! conn)
        coherence-authority (or coherence-authority :unknown)
        proof-mode (case (or proof-mode :auto)
                     :auto (if (= :managed coherence-authority)
                             :mutation
                             :content)
                     proof-mode)
        current-kid (or security-kid :default)
        root-keyring
        (cond
          security-keyring
          (into {} (map (fn [[kid key]]
                          [kid (secure/normalize-key key)]))
                security-keyring)

          security-key
          {current-kid (secure/normalize-key security-key)}

          :else
          {:default secure/default-root-key})
        _ (when-not (get root-keyring current-kid)
            (throw (ex-info "EACL Config Error: :security-kid is absent from :security-keyring."
                            {:type :eacl/invalid-config
                             :key :security-kid
                             :value current-kid})))
        format-options {:current-kid current-kid
                        :keyring root-keyring
                        :token-ttl-seconds
                        (or token-ttl-seconds
                            mutation/default-token-ttl-seconds)}
        object-id->entid (fn [db object-id]
                           (ds/entid db (object-id->lookup-ref object-id)))
        custom-codec?
        (boolean
         (or entid->object-id
             entity->object-id
             (contains? config-opts :object-id->lookup-ref)))
        cache-eligible? (or (not custom-codec?)
                            (and (some? adapter-fingerprint)
                                 (true? adapter-deterministic?)))
        current-cache-store
        (when cache-eligible?
          (eacl.cache/current-cache-for-option cache))
        cursor-codec-cache
        (when current-cache-store
          (cursor/codec-cache
           {:max-entries
            (if (and (map? cache)
                     (integer? (:max-entries cache)))
              (:max-entries cache)
              2048)}))
        page-navigation-cache
        (when current-cache-store
          (relay/page-navigation-cache
           {:max-entries
            (if (and (map? cache)
                     (integer? (:max-entries cache)))
              (:max-entries cache)
              2048)}))
        entid->object-id (or entid->object-id
                             (when entity->object-id
                               (fn [db eid] (entity->object-id (ds/entity db eid))))
                             (fn [db eid] (:eacl/id (ds/entity db eid))))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :conn conn
                          :derived-schema-caches (atom {})
                          :adapter-fingerprint
                          (or adapter-fingerprint
                              {:backend :datascript
                               :adapter-version backend/adapter-version
                               :proof-mode proof-mode
                               :recursive-traversal-limits
                               recursive-traversal-limits
                               :codec
                               (if custom-codec?
                                 :custom-unfingerprinted
                                 :eacl-id-immutable-v1)})
                          :adapter-deterministic?
                          (if custom-codec?
                            (true? adapter-deterministic?)
                            true)
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :cursor-ttl-seconds cursor-ttl-seconds
                          :format-options format-options
                          :engine-selection
                          (verified/normalize-selection engine-selection)
                          :coherence-authority coherence-authority
                          :proof-mode proof-mode
                          :consistency-sync-timeout-ms
                          (or consistency-sync-timeout-ms 30000)
                          :exact-registry
                          (when exact-snapshot-registry-size
                            (atom {:order [] :snapshots {}}))
                          :exact-registry-limit
                          exact-snapshot-registry-size
                          :token-ttl-seconds
                          (or token-ttl-seconds
                              mutation/default-token-ttl-seconds)
                          :retention-grace-seconds
                          (or retention-grace-seconds
                              mutation/default-retention-grace-seconds)
                          :cache-store
                          (if cache-eligible?
                            (eacl.cache/cache-store cache)
                            eacl.cache/no-cache)
                          :current-cache-store
                          current-cache-store
                          :cursor-codec-cache cursor-codec-cache
                          :page-navigation-cache
                          page-navigation-cache
                          :managed-cache-enabled?
                          (and cache-eligible?
                               (not custom-codec?)
                               (= :managed coherence-authority))
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
    (->DataScriptAuthorization conn opts)))

(defn create-conn
  ([] (schema/create-conn))
  ([extra-schema]
   (schema/create-conn extra-schema)))
