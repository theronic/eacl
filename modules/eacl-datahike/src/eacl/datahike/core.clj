(ns eacl.datahike.core
  (:require [com.rpl.specter :as S]
            [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.continuation :as continuation]
            [eacl.core :as eacl :refer [IAuthorization
                                        IDetailedAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate]]
            [eacl.cursor :as cursor]
            [eacl.datahike.backend :as datahike-backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.mutation :as journal]
            [eacl.datahike.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.formal.production-kernel :as production-kernel]
            [eacl.mutation :as mutation]
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
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

(defn- snapshot-adapter
  [db opts]
  (datahike-backend/snapshot-adapter db opts))

(defn- selected-context
  [db opts consistency-value]
  (let [descriptor (consistency/descriptor consistency-value)
        source-adapter (snapshot-adapter db opts)
        selection-options
        {:format-options (:format-options opts)
         :coherence-authority (:coherence-authority opts)
         :decision-kernel (:decision-kernel opts)
         :issue-token? false
         :timeout-ms (:consistency-sync-timeout-ms opts)}
        selection
        (if (= :minimize-latency (:mode descriptor))
          (consistency-v3/captured-current-selection
           source-adapter consistency-value selection-options)
          (consistency-v3/select
           source-adapter
           consistency-value
           selection-options))
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
  "Request options for relay cursor handling.

  One shared derived-schema-cache delay serves the engine evaluation, the
  cursor scope's schema stamp, and the cursor dependency closure, so the
  dependency-scoped cursor contexts add no schema-proof reads beyond the
  request's own resolution. All three delays are forced only when a cursor
  is actually minted or resumed."
  [adapter opts selection resource-type permission]
  (let [schema-cache
        (delay
          (engine/schema-cache-for!
           (:derived-schema-caches opts)
           adapter))]
    (assoc opts
           :cursor-consistency-mode
           (get-in selection [:descriptor :mode])
           :timeout-ms (:consistency-sync-timeout-ms opts)
           :request-schema-cache schema-cache
           :cursor-schema-stamp
           {:adapter adapter
            :stamp (delay (:schema-version @schema-cache))}
           :cursor-dependency-relation-ids
           (when (and resource-type permission)
             (delay
               (try
                 (binding [engine/*schema-cache* @schema-cache]
                   (engine/permission-relationship-eids
                    adapter resource-type permission))
                 (catch Exception _
                   nil)))))))

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
  (when-let [datom (first (ddb/eavt-datoms db entity attribute))]
    [(:tx datom) (:v datom)]))

(defn- managed-cache-descriptor
  [db relation-ids]
  (let [relation-ids (vec (distinct relation-ids))]
    (when (seq relation-ids)
      (let [schema-eid
            (ddb/entid db [:eacl/id mutation/schema-entity-id])
            schema-stamp
            (when schema-eid
              (datom-proof
               db schema-eid mutation/schema-mutation-id-attr))
            relation-stamps
            (d/q
             '[:find ?relation ?tx ?mutation
               :in $ [?relation ...]
               :where
               [?relation :eacl.relation/mutation-id ?mutation ?tx]]
             db relation-ids)]
        (when (and (subproblem/proof-stamp? schema-stamp)
                   (= (count relation-ids)
                      (count relation-stamps))
                   (= (set relation-ids)
                      (set (map first relation-stamps)))
                   (every?
                    (fn [[_ tx mutation-id]]
                      (subproblem/proof-stamp? [tx mutation-id]))
                    relation-stamps))
          {:schema-stamp schema-stamp
           :dependency-stamp
           (mapv vec (sort-by first relation-stamps))})))))

(defn- cached-engine-result
  [adapter opts operation query resource-type permission
   valid-value? compute]
  (let [schema-cache
        (or (:request-schema-cache opts)
            (delay
              (engine/schema-cache-for!
               (:derived-schema-caches opts)
               adapter)))
        evaluate
        #(binding [engine/*schema-cache* @schema-cache
                   engine/*recursive-traversal-limits*
                   (:recursive-traversal-limits opts)
                   subproblem/*decision-kernel*
                   (:decision-kernel opts)]
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
          :decision-kernel (:decision-kernel opts)
          :managed-descriptor-key-fn
          (when (:managed-cache-enabled? opts)
            #(vec (sort (distinct (:relation-ids @dependencies)))))
          :managed-key-fn
          (when (:managed-cache-enabled? opts)
            #(managed-cache-descriptor
              db (:relation-ids @dependencies)))
          :managed-subproblem-key-fn
          (when (:managed-cache-enabled? opts)
            (fn [dependency]
              (managed-cache-descriptor
               db
               (if (vector? dependency)
                 dependency
                 [dependency]))))
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

(defn- continuation-query-identity
  [query]
  (apply
   dissoc
   query
   [:first :last :after :before
    :consistency :cache?]))

(defn- continuation-context
  [adapter opts operation query]
  (when-not (false? (:continuation-cache-request? opts))
    (continuation/private-context
     (:continuation-cache-store opts)
     adapter
     operation
     (continuation-query-identity query))))

(defn datahike-read-relationships
  [db
   {:as opts
   :keys [object-id->entid]}
   filters]
  ;; The unified filter contract validates the complete public query before
  ;; any snapshot selection or cursor work (backend-unification 9.1).
  (relationship-filters/validate! filters)
  (let [{source-adapter :adapter selection :selection}
        (selected-context db opts (:consistency filters))
        {adapter :adapter page-db :db cursor-opts :opts
         page-query :query}
        (page-context
         source-adapter opts selection :read-relationships filters nil nil)
        base-filters (apply dissoc filters
                            [:first :last :after :before :consistency :cache?])
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
      (assoc relay/empty-page :cached? false :cache-basis nil)
      (or
       (relay/lookup-visited-page
        adapter cursor-opts :read-relationships filters)
       (relay/remember-visited-page!
        adapter
        cursor-opts
        :read-relationships
        filters
        (assoc
         (relay/externalize-relationship-page
          adapter
          cursor-opts
          :read-relationships
          filters
          (impl/read-relationships
           page-db internal-query (:decision-kernel cursor-opts)))
         :cached? false
         :cache-basis nil))))))

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
        {:backend :datahike}
        (backend/invoke adapter :source-scope)
        (backend/invoke adapter :graph-head))))))

(defn- write-response
  [db opts]
  (if-let [token (response-token db opts)]
    {:zed/token token}
    {}))

(defn datahike-write-relationships!
  [conn opts updates]
  (let [updates (vec updates)
        _ (doseq [{:keys [operation]} updates]
            (impl/validate-relationship-operation! operation))
        db (d/db conn)
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
        tx-data
        (->> internal-updates
             (mapcat #(impl/tx-update-relationship db %))
             (remove nil?)
             vec)]
    (if (seq tx-data)
      (let [report
            (journal/transact!
             conn
             {:mutation-id (mutation/new-id)
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
        (write-response (d/db conn) opts)))))

(defn- relationship-retraction-count
  [db tx-data]
  (let [attr-reprs
        (into
         #{}
         (map #(ddb/attr-repr db %))
         relationship-storage/attributes)]
    (count
     (filter
      (fn [{:keys [a added]}]
        (and (false? added)
             (contains? attr-reprs a)))
      tx-data))))

(defn datahike-delete-object!
  "Removes both halves of every relationship touching `object`, without
   retracting the object entity itself."
  [conn {:keys [object->entid] :as opts} object]
  (let [db (d/db conn)
        object-eid
        (or (try
              (object->entid db object)
              (catch Exception _
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
               (relationship-retraction-count
                (:db-after report)
                (:tx-data report))))
      (do
        (journal/ensure-migrated! conn)
        (assoc (write-response (d/db conn) opts)
               :retracted-datoms 0)))))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn datahike-check-permission
  [db {:keys [spice-object->internal] :as opts}
   subject permission resource consistency]
  (let [{selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context db opts consistency)
        opts (assoc opts :completed-cache? completed-cache?)
        internal-subject (spice-object->internal selected-db subject)
        internal-resource (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      {:allowed? false
       :cached? false
       :cache-basis nil}
      (let [answer
            (cached-engine-result
             adapter opts :can?
             {:public [subject permission resource]
              :internal
              [internal-subject permission internal-resource]}
             (:type internal-resource)
             permission
             boolean?
             #(engine/can?
               adapter internal-subject permission internal-resource))]
        {:allowed? (:value answer)
         :cached? (:cached? answer)
         :cache-basis (:cache-basis answer)}))))

(defn datahike-can?
  [db opts subject permission resource consistency]
  (:allowed?
   (datahike-check-permission
    db opts subject permission resource consistency)))

(defn datahike-lookup-resources
  [db
   {:as opts :keys [spice-object->internal]}
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
              (cache/lookup-page-query-identity query internal-query)
              (:resource/type internal-query)
              (:permission internal-query)
              #(and (map? %) (vector? (:data %))
                    (map? (:page-info %)))
              #(engine/lookup-resources
                adapter
                internal-query
                {:continuation-cache
                 (continuation-context
                  adapter cursor-opts :lookup-resources query)}))
             page
             (with-cache-info
               (binding [subproblem/*store*
                         (:subproblem-store answer)
                         subproblem/*decision-kernel*
                         (:decision-kernel cursor-opts)]
                 (relay/externalize-page
                  adapter cursor-opts :lookup-resources query
                  (:value answer)))
               answer)]
         (relay/remember-visited-page!
          adapter cursor-opts :lookup-resources query page))))))

(defn datahike-count-resources
  [db
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [{selected-db :db adapter :adapter
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
                (dissoc :consistency :cache?))
            answer
            (cached-engine-result
             adapter opts :count-resources
             {:public (dissoc query :consistency :cache?)
              :internal internal-query}
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-resources adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn datahike-lookup-subjects
  [db
   {:as opts :keys [spice-object->internal]}
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
              (cache/lookup-page-query-identity query internal-query)
              (:type (:resource internal-query))
              (:permission internal-query)
              #(and (map? %) (vector? (:data %))
                    (map? (:page-info %)))
              #(engine/lookup-subjects
                adapter
                internal-query
                {:continuation-cache
                 (continuation-context
                  adapter cursor-opts :lookup-subjects query)}))
             page
             (with-cache-info
               (binding [subproblem/*store*
                         (:subproblem-store answer)
                         subproblem/*decision-kernel*
                         (:decision-kernel cursor-opts)]
                 (relay/externalize-page
                  adapter cursor-opts :lookup-subjects query
                  (:value answer)))
               answer)]
         (relay/remember-visited-page!
          adapter cursor-opts :lookup-subjects query page))))))

(defn datahike-count-subjects
  [db
   {:as opts :keys [spice-object->internal]}
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
                (dissoc :consistency :cache?))
            answer
            (cached-engine-result
             adapter opts :count-subjects
             {:public (dissoc query :consistency :cache?)
              :internal internal-query}
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-subjects adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn- request-cache-enabled?
  [cache-option]
  (cache/validate-request-cache-option! cache-option)
  (not (false? cache-option)))

(defrecord DatahikeAuthorization [conn opts]
  IAuthorization
  (can? [_ subject permission resource]
    (datahike-can? (d/db conn) (assoc opts :completed-cache-request? true)
                   subject permission resource consistency/minimize-latency))
  (can? [_ subject permission resource consistency]
    (datahike-can? (d/db conn) (assoc opts :completed-cache-request? true)
                   subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency] cache? :cache?}]
    (datahike-can? (d/db conn)
                   (assoc opts :completed-cache-request?
                          (request-cache-enabled? cache?))
                   subject permission resource
                   consistency))

  (read-schema [_]
    (schema/read-schema (d/db conn)))
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
    (datahike-read-relationships
     (d/db conn)
     (assoc opts
            :completed-cache-request?
            (request-cache-enabled? (:cache? filters)))
     (dissoc filters :cache?)))
  (write-relationships! [_ updates]
    (datahike-write-relationships! conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate operation
                                                          (->Relationship subject relation resource))]))
  (write-relationship! [_ {:keys [operation subject relation resource]}]
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
  (delete-relationship! [_ {:keys [subject relation resource]}]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (datahike-write-relationships! conn opts
                                   [(->RelationshipUpdate :delete
                                                          (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (let [cache-enabled?
          (request-cache-enabled? (:cache? query))]
      (datahike-lookup-resources
       (d/db conn)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-resources [_ query]
    (datahike-count-resources
     (d/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (lookup-subjects [_ query]
    (let [cache-enabled?
          (request-cache-enabled? (:cache? query))]
      (datahike-lookup-subjects
       (d/db conn)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-subjects [_ query]
    (datahike-count-subjects
     (d/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))

  (expand-permission-tree [_ _]
    (throw (ex-info "expand-permission-tree is not implemented yet."
                    {:type :eacl/not-implemented
                     :method (quote expand-permission-tree)})))

  IDetailedAuthorization
  (-check-permission
    [_ {:keys [subject permission resource consistency] cache? :cache?}]
    (datahike-check-permission
     (d/db conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? cache?))
     subject permission resource
     (or consistency consistency/fully-consistent))))

(defn expire-cache!
  "Expires every completed answer owned by one Datahike EACL client."
  [client]
  (when-not (instance? DatahikeAuthorization client)
    (throw (ex-info "expire-cache! requires a Datahike EACL client."
                    {:type :eacl/invalid-client})))
  (when-let [store (get-in client [:opts :current-cache-store])]
    (cache/expire-current! store))
  (cursor/clear-codec-cache!
   (get-in client [:opts :cursor-codec-cache]))
  (relay/clear-page-navigation-cache!
   (get-in client [:opts :page-navigation-cache]))
  (some->
   (get-in client [:opts :continuation-cache-store])
   continuation/clear!)
  nil)

(defn cache-stats
  "Returns private completed-cache counters for one Datahike EACL client."
  [client]
  (when-not (instance? DatahikeAuthorization client)
    (throw (ex-info "cache-stats requires a Datahike EACL client."
                    {:type :eacl/invalid-client})))
  (let [completed
        (if-let [store (get-in client [:opts :current-cache-store])]
          (cache/current-cache-stats store)
          {:disabled? true})
        continuation-store
        (get-in client [:opts :continuation-cache-store])]
    (cond->
     completed
      continuation-store
      (assoc :continuations
             (continuation/stats continuation-store)))))

(def ^:private known-client-opt-keys
  #{:entid->object-id
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
    :consistency-sync-timeout-ms})

(defn make-client
  "Builds an IAuthorization client over a datahike conn.

  Options (unknown keys throw :eacl/invalid-config - a silently ignored key
  means silently wrong ID coercion, audit 5):
  - :entid->object-id  (fn [db eid] external-id) - canonical.
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
           consistency-sync-timeout-ms]
    :or   {object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
    (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                         ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
                    {:type :eacl/invalid-config
                     :unknown-keys (vec unknown-keys)
                     :known-keys known-client-opt-keys})))
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
      (catch Exception error
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
          (do
            (secure/warn-defaulted-token-key!)
            {:default secure/default-root-key}))
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
                           (ddb/entid db (object-id->lookup-ref object-id)))
        custom-codec?
        (boolean
         (or entid->object-id
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
        continuation-cache-store
        (when current-cache-store
          (continuation/make-store
           {:max-entries
            (if (and (map? cache)
                     (integer? (:max-entries cache)))
              (:max-entries cache)
              2048)}))
        entid->object-id (or entid->object-id
                             (fn [db eid] (:eacl/id (d/entity db eid))))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :conn conn
                          :derived-schema-caches (atom {})
                          :adapter-fingerprint
                          (or adapter-fingerprint
                              {:backend :datahike
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
                          :decision-kernel production-kernel/default-selection
                          :coherence-authority coherence-authority
                          :proof-mode proof-mode
                          :consistency-sync-timeout-ms
                          (or consistency-sync-timeout-ms 30000)
                          :token-ttl-seconds
                          (or token-ttl-seconds
                              mutation/default-token-ttl-seconds)
                          :retention-grace-seconds
                          (or retention-grace-seconds
                              mutation/default-retention-grace-seconds)
                          :current-cache-store
                          current-cache-store
                          :cursor-codec-cache cursor-codec-cache
                          :page-navigation-cache
                          page-navigation-cache
                          :continuation-cache-store
                          continuation-cache-store
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
    (->DatahikeAuthorization conn opts)))

(defn create-conn
  "A datahike connection carrying EACL's schema. See
  `eacl.datahike.schema/create-conn` for the config options."
  ([] (schema/create-conn))
  ([extra-schema] (schema/create-conn extra-schema))
  ([extra-schema config] (schema/create-conn extra-schema config)))
