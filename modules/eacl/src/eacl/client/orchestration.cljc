(ns eacl.client.orchestration
  "One shared client orchestration over the v8 backend SPI
  (backend-unification, D-7).

  The nine public operations, snapshot-context assembly, cursor plumbing,
  cache wiring, filter validation, and integrity reporting live here once,
  parameterized by a backend `api` map. Backend modules contribute only
  genuinely backend-specific code through that map:

    :backend-id                 keyword, e.g. :datascript
    :db                         (fn [conn] db) - current immutable value
    :entid                      (fn [db lookup-ref-or-eid] eid-or-nil)
    :default-entid->object-id   (fn [db eid] external-id-or-nil)
    :snapshot-adapter           (fn [db opts] v8-adapter)
    :native-source-id           optional (fn [conn] stable source identity)
    :relationship-retraction-count (fn [db tx-data] n)
    :schema  {:read-schema    (fn [db] ...)
              :write-schema!  (fn [conn schema-string opts] ...)}
    :transact!                  (fn [conn native-tx] tx-report)
    :impl    {:validate-relationship-operation! (fn [operation])
              :relationship-relation-id         (fn [db relationship])
              :tx-update-relationship           (fn [db update])
              :tx-delete-object                 (fn [db object-eid])
              :affected-relation-ids            (fn [tx-data])
              :read-relationships               (fn [db query kernel])}
    :extra-client-opt-keys      set of documented per-backend extension
                                option keys accepted by make-client"
  (:require [com.rpl.specter :as S]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.continuation :as continuation]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        IDetailedAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate]]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.permission-tree :as permission-tree]
            [eacl.proof-frame :as proof-frame]
            #?(:clj
               [eacl.formal.production-kernel :as production-kernel]
               :cljs
               [eacl.formal.production-kernel-cljs :as production-kernel])
            [eacl.relay :as relay]
            [eacl.relationships.filters :as relationship-filters]
            [eacl.relationships.mutations :as relationship-mutations]
            [eacl.schema.errors :as schema-errors]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.spicedb.consistency :as consistency]))

(defn- ensure-execution-contract
  [opts operation request]
  (cond-> opts
    (nil? (:cache-lifecycle opts))
    (assoc :cache-lifecycle
           (cache/capture-current-lifecycle
            (:current-cache-store opts)))

    (nil? (:execution-contract opts))
    (assoc :execution-contract
           (execution/normalize opts operation request))))

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

(defn- selected-context
  [api db opts consistency-value]
  (let [contract (:execution-contract opts)
        _ (execution/check! contract :consistency-selection)
        descriptor (consistency/descriptor consistency-value)
        source-adapter ((:snapshot-adapter api) db opts)
        _ (when (and (= :datascript (backend/backend-id source-adapter))
                     (= :at-exact-snapshot (:mode descriptor)))
            (throw
             (ex-info
              "DataScript is current-basis-only and does not retain exact historical snapshots."
              {:type :eacl/unsupported-capability
               :eacl/error :eacl/unsupported-capability
               :backend :datascript
               :capability :consistency
               :requested :at-exact-snapshot
               :supported (:consistency (backend/capabilities source-adapter))
               :migration
               {:use :at-least-as-fresh
                :alternative
                "Evaluate an application-owned immutable DataScript DB value directly."}})))
        selection-options
        {:format-options (:format-options opts)
         :decision-kernel (:decision-kernel opts)
         :issue-token? false
         :timeout-ms
         (if contract
           (min (:consistency-sync-timeout-ms opts)
                (execution/remaining-millis contract))
           (:consistency-sync-timeout-ms opts))}
        selection
        (if (= :minimize-latency (:mode descriptor))
          (consistency-v3/captured-current-selection
           source-adapter consistency-value selection-options)
          (consistency-v3/select
           source-adapter
           consistency-value
           selection-options))
        adapter (:adapter selection)]
    (execution/check! contract :consistency-selected)
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

(defn- new-request-proof-frame
  [adapter opts]
  (proof-frame/request-frame
   adapter
   {:diagnostic-fn
    (fn [diagnostic]
      (cache/record-proof-unavailable!
       (:current-cache-store opts)
       diagnostic))}))

(defn- cursor-options
  "Request options for relay cursor handling.

  One shared derived-schema-cache delay serves the engine evaluation, the
  cursor scope's schema stamp, and the cursor dependency closure, so the
  dependency-scoped cursor contexts add no schema-generation reads beyond the
  request's own resolution. All three delays are forced only when a cursor
  is actually minted or resumed."
  [adapter opts selection resource-type permission]
  (let [contract (:execution-contract opts)
        request-proof-frame
        (or (:request-proof-frame opts)
            (new-request-proof-frame adapter opts))
        schema-cache
        (delay
          (binding [engine/*proof-frame* request-proof-frame]
            (engine/schema-cache-for!
             (:derived-schema-caches opts)
             adapter)))]
    (assoc opts
           :request-proof-frame request-proof-frame
           :cursor-consistency-mode
           (get-in selection [:descriptor :mode])
           :cursor-request-token (:request-token selection)
           :cursor-freshness-floor
           (when (= :at-least-as-fresh
                    (get-in selection [:descriptor :mode]))
             (:request-token selection))
           :timeout-ms
           (if contract
             (min (:consistency-sync-timeout-ms opts)
                  (execution/remaining-millis contract))
             (:consistency-sync-timeout-ms opts))
           :request-schema-cache schema-cache
           :cursor-schema-stamp
           {:adapter adapter
            :stamp (delay (:schema-version @schema-cache))}
           :cursor-dependency-relation-ids
           ;; Cursor reuse uses the same compiled dependency closure as the
           ;; request proof frame. If proof is unavailable, the cursor remains
           ;; bound to exact immutable snapshot identity. Datomic/Datahike may
           ;; select that exact snapshot on resume; current-only DataScript
           ;; fails closed after a relevant basis change.
           (when (and resource-type permission)
             (delay
               (try
                 (binding [engine/*schema-cache* @schema-cache]
                   (engine/permission-relationship-eids
                    adapter resource-type permission))
                 (catch #?(:clj Exception :cljs :default) _
                   nil)))))))

(defn- page-context
  [opts selection operation query resource-type permission]
  (let [adapter (:adapter selection)
        current-opts
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
     :query (:query prepared)}))

(defn- cached-engine-result
  [adapter opts operation query resource-type permission
   valid-value? compute]
  (let [contract (:execution-contract opts)
        request-proof-frame
        (let [candidate (:request-proof-frame opts)]
          (if (and candidate
                   (identical? adapter (:adapter candidate)))
            candidate
            (new-request-proof-frame adapter opts)))
        schema-cache
        (or (:request-schema-cache opts)
            (delay
              (binding [engine/*proof-frame* request-proof-frame]
                (engine/schema-cache-for!
                 (:derived-schema-caches opts)
                 adapter))))
        evaluate
        #(do
           (execution/check! contract :schema-plan)
           (let [value
                 (binding [engine/*schema-cache* @schema-cache
                           engine/*proof-frame* request-proof-frame
                           engine/*recursive-traversal-limits*
                           (:recursive-traversal-limits opts)
                           engine/*evaluation-mode*
                           (:evaluation contract)
                           execution/*contract* contract
                           subproblem/*decision-kernel*
                           (:decision-kernel opts)]
                   (compute))]
             (execution/check! contract :semantic-evaluation)
             value))
        cacheable?
        (and (:current-cache-store opts)
             (:completed-cache? opts)
             (or (nil? contract)
                 (execution/cache-stage-available? contract)))]
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
              (binding [engine/*schema-cache* @schema-cache
                        engine/*proof-frame* request-proof-frame]
                (permission-dependencies
                 adapter resource-type permission)))
            complete-proof
            (delay
              (proof-frame/resolve!
               request-proof-frame
               (:relation-ids @dependencies)))
            db (:db (backend/state adapter))
            semantic-key
            {:operation operation
             :query query
             :evaluation (:evaluation contract)
             :demand (:demand contract)
             :engine-version engine/engine-version
             ;; The public order ABI is part of an answer's identity: a page
             ;; cached under one order must never be served under another.
             :order-abi engine/stable-order-abi
             :source-lifecycle
             (proof-frame/source-lifecycle request-proof-frame)
             :adapter-fingerprint (:adapter-fingerprint opts)
             :recursive-traversal-limits
             (:recursive-traversal-limits opts)}]
        (execution/check! contract :cache-lookup)
        (let [answer
              (binding
                  [subproblem/*publication-attempt-limit*
                   (get-in contract
                           [:cache-attempt :maximum-atomic-attempts]
                           4)]
                (cache/resolve-current!
                 (:current-cache-store opts)
                 {:snapshot db
          :cache-lifecycle (:cache-lifecycle opts)
          :snapshot-order (:max-tx db)
          :same-snapshot? identical?
          :cache-basis (backend/invoke adapter :snapshot-id)
          :decision-kernel (:decision-kernel opts)
          :managed-key-fn
          (when (:managed-cache-enabled? opts)
            #(proof-frame/descriptor @complete-proof))
          :managed-subproblem-key-fn
          (when (:managed-cache-enabled? opts)
            (fn [dependency]
              (proof-frame/subset-descriptor
               @complete-proof dependency)))
                :managed-subproblem-scope
                (consistency-v3/source-scope adapter)}
               semantic-key
               operation
                 valid-value?
                 evaluate))]
          (execution/check! contract :cache-publication)
          answer)))))

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
    :consistency :cache? :timeout-ms :cancellation-token]))

(defn- stale-cursor-anchor!
  [operation]
  (throw
   (ex-info
    "The cursor's query anchor no longer exists on the selected snapshot."
    {:type :eacl.pagination/stale-cursor
     :eacl/error :eacl.pagination/stale-cursor
     :operation operation
     :reason :query-anchor-identity-changed})))

(defn- cursor-request?
  [query]
  (or (some? (:after query))
      (some? (:before query))))

(defn- continuation-context
  [adapter opts operation query]
  (when-not (false? (:continuation-cache-request? opts))
    (continuation/private-context
     (:continuation-cache-store opts)
     adapter
     operation
     {:query (continuation-query-identity query)
      :evaluation (get-in opts [:execution-contract :evaluation])
      :recursive-traversal-limits
      (:recursive-traversal-limits opts)}
     {:request-proof-frame (:request-proof-frame opts)})))

(defn read-relationships
  [api db
   {:as opts
   :keys [object-id->entid]}
   filters]
  ;; The unified filter contract validates the complete public query before
  ;; any snapshot selection or cursor work (backend-unification 9.1).
  (relationship-filters/validate! filters)
  (let [opts (ensure-execution-contract opts :read-relationships filters)
        {selection :selection}
        (selected-context api db opts (:consistency filters))
        {adapter :adapter page-db :db cursor-opts :opts
         page-query :query}
        (page-context
         opts selection :read-relationships filters nil nil)
        _ (schema-errors/validate-relationship-read!
           ((get-in api [:schema :read-schema]) page-db)
           filters)
        base-filters
        (apply dissoc filters
               [:first :last :after :before :consistency :cache?
                :timeout-ms :cancellation-token])
        subject-id (:subject/id base-filters)
        resource-id (:resource/id base-filters)
        subject-eid (when subject-id
                      (object-id->entid page-db subject-id))
        resource-eid (when resource-id
                       (object-id->entid page-db resource-id))
        internal-query
        (-> page-query
            (dissoc :consistency :timeout-ms :cancellation-token)
            (cond->
              subject-id (assoc :subject/id subject-eid)
              resource-id (assoc :resource/id resource-eid)))]
    (if (or (and subject-id (nil? subject-eid))
            (and resource-id (nil? resource-eid)))
      (if (cursor-request? filters)
        (stale-cursor-anchor! :read-relationships)
        (assoc relay/empty-page :cached? false :cache-basis nil))
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
          ((get-in api [:impl :read-relationships])
           page-db internal-query (:decision-kernel cursor-opts)))
         :cached? false
         :cache-basis nil))))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal object-id->lookup-ref]}
   {:keys [subject relation resource]}]
  (let [internalize
        (fn [object]
          (assoc (spice-object->internal db object)
                 :eacl.relationship/identity-guard
                 (object-id->lookup-ref (:id object))))]
    {:subject (internalize subject)
     :relation relation
     :resource (internalize resource)}))

(defn- response-token
  [api db opts]
  (let [adapter ((:snapshot-adapter api) db opts)]
    (causal-token/issue
     (:format-options opts)
     (merge
      (consistency-v3/source-scope adapter)
      (consistency-v3/native-revision adapter)))))

(defn- write-response
  [api db opts]
  (if-let [token (response-token api db opts)]
    {:zed/token token}
    {}))

(defn- relationship-commit-preconditions-first
  "Moves relationship transaction functions ahead of the mutations they
  validate while retaining the leading schema-fence CAS and the relative
  order of every mutation.

  DataScript and direct-writer Datahike use transaction functions to assert
  that every planned `:create` was absent at the transaction's linearization
  point. Running all of those assertions before any add/retract makes one
  batch observe the same calculation snapshot as the Datomic writer. Without
  this staging, `[:touch relationship]` followed by `[:create relationship]`
  made the create observe the touch inside the same transaction and fail only
  on the portable backends."
  [tx-data]
  (if-let [[schema-fence & operations] (seq tx-data)]
    (let [transaction-function? #(and (vector? %)
                                      (= :db.fn/call (first %)))]
      (into [schema-fence]
            (concat (filter transaction-function? operations)
                    (remove transaction-function? operations))))
    []))

(defn write-relationships!
  [api conn opts updates]
  (let [validate-operation!
        (get-in api [:impl :validate-relationship-operation!])
        tx-update-relationship
        (get-in api [:impl :tx-update-relationship])
        updates (vec updates)
        _ (doseq [{:keys [operation]} updates]
            (validate-operation! operation))
        db      ((:db api) conn)
        schema  ((get-in api [:schema :read-schema]) db)
        _ (doseq [{:keys [relationship]} updates]
            (schema-errors/validate-relationship-write!
             schema :write-relationships
             {:resource-type (:type (:resource relationship))
              :subject-type (:type (:subject relationship))
              :relation (:relation relationship)}))
        internal-updates
        (S/transform [S/ALL :relationship]
                     #(spice-relationship->internal db opts %)
                     updates)
        _ (relationship-mutations/validate-batch! internal-updates)
        tx-data (->> internal-updates
                     (mapcat #(tx-update-relationship db %))
                     (remove nil?)
                     distinct
                     vec
                     relationship-commit-preconditions-first)]
    (if (seq tx-data)
      (let [report
            ((:transact! api)
             conn
             {:tx-data tx-data})]
        (write-response api (:db-after report) opts))
      (write-response api ((:db api) conn) opts))))

(defn delete-object!
  "Removes every relationship that references `object`, without retracting the
  object entity itself."
  [api conn {:keys [object->entid] :as opts} object]
  (let [db ((:db api) conn)
        object-eid
        (or (try
              (object->entid db object)
              (catch #?(:clj Exception :cljs :default) _
                nil))
            (when (number? (:id object))
              (:id object)))
        tx-data ((get-in api [:impl :tx-delete-object]) db object-eid)]
    (if (seq tx-data)
      (let [report
            ((:transact! api)
             conn
             {:tx-data tx-data})]
        (assoc (write-response api (:db-after report) opts)
               :retracted-datoms
               ((:relationship-retraction-count api)
                (:db-after report) (:tx-data report))))
      (assoc (write-response api ((:db api) conn) opts)
             :retracted-datoms 0))))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn check-permission
  [api db {:keys [spice-object->internal] :as opts}
   subject permission resource consistency]
  (let [request
        (merge {:subject subject
                :permission permission
                :resource resource
                :consistency consistency}
               (:execution-request opts))
        opts (ensure-execution-contract
              opts (or (:request-operation opts) :can?) request)
        {selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context api db opts consistency)
        opts (assoc opts :completed-cache? completed-cache?)
        _ (schema-errors/validate-permission-request!
           ((get-in api [:schema :read-schema]) selected-db)
           (or (:request-operation opts) :can?)
           {:resource-type (:type resource)
            :subject-type (:type subject)
            :permission permission})
        internal-subject (spice-object->internal selected-db subject)
        internal-resource (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      {:allowed? false
       :cached? false
       :cache-basis nil
       :evaluation (get-in opts [:execution-contract :evaluation])}
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
         :cache-basis (:cache-basis answer)
         :evaluation (get-in opts [:execution-contract :evaluation])}))))

(defn can?
  [api db opts subject permission resource consistency]
  (:allowed?
   (check-permission
    api db opts subject permission resource consistency)))

(defn lookup-resources
  [api db
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [opts (ensure-execution-contract opts :lookup-resources query)
        {selection :selection}
        (selected-context api db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts
         page-query :query}
        (page-context
         opts selection :lookup-resources query
         (:resource/type query) (:permission query))
        _ (schema-errors/validate-permission-request!
           ((get-in api [:schema :read-schema]) selected-db)
           :lookup-resources
           {:resource-type (:resource/type query)
            :subject-type (:type subject)
            :permission (:permission query)})
        internal-subject (spice-object->internal selected-db subject)]
    (if (nil? (:id internal-subject))
      (if (cursor-request? query)
        (stale-cursor-anchor! :lookup-resources)
        (assoc relay/empty-page :cached? false :cache-basis nil))
      (or
       (relay/lookup-visited-page
        adapter cursor-opts :lookup-resources query)
       (let [internal-query
             (-> page-query
                 (dissoc :consistency :evaluation :timeout-ms
                         :cancellation-token)
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

(defn count-resources
  [api db
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [opts (ensure-execution-contract opts :count-resources query)
        {selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context api db opts (:consistency query))
        opts (assoc opts :completed-cache? completed-cache?)
        _ (schema-errors/validate-permission-request!
           ((get-in api [:schema :read-schema]) selected-db)
           :count-resources
           {:resource-type (:resource/type query)
            :subject-type (:type subject)
            :permission (:permission query)})
        internal-subject (spice-object->internal selected-db subject)]
    (if-not (:id internal-subject)
      (assoc
       (cond-> {:count 0 :limit (or (:count-limit query) -1)}
         (contains? query :count-limit) (assoc :truncated? false))
       :cached? false :cache-basis nil)
      (let [internal-query
            (-> query
                (assoc :subject internal-subject)
                (dissoc :consistency :cache? :evaluation :timeout-ms
                        :cancellation-token))
            answer
            (cached-engine-result
             adapter opts :count-resources
             {:public (dissoc query :consistency :cache?
                              :cancellation-token)
              :internal internal-query}
             (:resource/type internal-query)
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-resources adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn lookup-subjects
  [api db
   {:as opts :keys [spice-object->internal]}
   query]
  (let [opts (ensure-execution-contract opts :lookup-subjects query)
        {selection :selection}
        (selected-context api db opts (:consistency query))
        {adapter :adapter selected-db :db cursor-opts :opts
         page-query :query}
        (page-context
         opts selection :lookup-subjects query
         (:type (:resource query)) (:permission query))
        _ (schema-errors/validate-permission-request!
           ((get-in api [:schema :read-schema]) selected-db)
           :lookup-subjects
           {:resource-type (:type (:resource query))
            :subject-type (:subject/type query)
            :permission (:permission query)})
        internal-resource
        (spice-object->internal selected-db (:resource query))]
    (when (contains? query :subject/relation)
      (throw (ex-info ":subject/relation is not supported by lookup-subjects."
                      {:eacl/error :eacl.pagination/unsupported-filter
                       :filter :subject/relation})))
    (if-not (:id internal-resource)
      (if (cursor-request? query)
        (stale-cursor-anchor! :lookup-subjects)
        (assoc relay/empty-page :cached? false :cache-basis nil))
      (or
       (relay/lookup-visited-page
        adapter cursor-opts :lookup-subjects query)
       (let [internal-query
             (-> page-query
                 (dissoc :consistency :evaluation :timeout-ms
                         :cancellation-token)
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

(defn count-subjects
  [api db
   {:as opts :keys [spice-object->internal]}
   query]
  (let [opts (ensure-execution-contract opts :count-subjects query)
        {selected-db :db adapter :adapter
         completed-cache? :completed-cache?}
        (selected-context api db opts (:consistency query))
        opts (assoc opts :completed-cache? completed-cache?)
        _ (schema-errors/validate-permission-request!
           ((get-in api [:schema :read-schema]) selected-db)
           :count-subjects
           {:resource-type (:type (:resource query))
            :subject-type (:subject/type query)
            :permission (:permission query)})
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
                (dissoc :consistency :cache? :evaluation :timeout-ms
                        :cancellation-token))
            answer
            (cached-engine-result
             adapter opts :count-subjects
             {:public (dissoc query :consistency :cache?
                              :cancellation-token)
              :internal internal-query}
             (:type (:resource internal-query))
             (:permission internal-query)
             #(and (map? %) (integer? (:count %)))
             #(engine/count-subjects adapter internal-query))]
        (with-cache-info (:value answer) answer)))))

(defn expand-permission-tree
  [api db opts query]
  (permission-tree/validate-request! query)
  (let [opts (ensure-execution-contract
              opts :expand-permission-tree query)
        contract (:execution-contract opts)
        {:keys [adapter db]}
        (selected-context api db opts (:consistency query))
        _ (schema-errors/validate-expansion-request!
           ((get-in api [:schema :read-schema]) db)
           :expand-permission-tree
           (:type (:resource query))
           (:permission query))
        tree (permission-tree/expand
              adapter
              {:limits (:permission-tree-limits opts)
               :execution-contract contract}
              (:resource query)
              (:permission query))]
    (execution/check! contract :permission-tree-token-issuance)
    (let [token
          (permission-tree/selected-adapter-token adapter opts)]
      (execution/check! contract :permission-tree-token-issued)
      {:expanded-at token
       :tree-root tree})))

(defn- request-cache-enabled?
  [cache-option]
  (cache/validate-request-cache-option! cache-option)
  (not (false? cache-option)))

(defrecord ClientAuthorization [conn opts api]
  IAuthorization
  (can? [_ subject permission resource]
    (can? api ((:db api) conn) (assoc opts
                                     :request-operation :can?
                                     :completed-cache-request? true)
          subject permission resource consistency/minimize-latency))
  (can? [_ subject permission resource consistency]
    (can? api ((:db api) conn) (assoc opts
                                     :request-operation :can?
                                     :completed-cache-request? true)
          subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency]
            cache? :cache? :as demand}]
    (can? api ((:db api) conn)
          (assoc opts
                 :request-operation :can?
                 :execution-request demand
                 :completed-cache-request?
                 (request-cache-enabled? cache?))
          subject permission resource
          consistency))

  (read-schema [_]
    ((get-in api [:schema :read-schema]) ((:db api) conn)))
  (write-schema! [_ schema-string]
    (let [result
          ((get-in api [:schema :write-schema!])
           conn schema-string
           (select-keys opts [:token-ttl-seconds]))]
      (when-not (:eacl.schema/no-op? result)
        (reset! (:derived-schema-caches opts) {})
        (when-let [store (:current-cache-store opts)]
          (cache/expire-current! store)))
      (merge result
             (write-response api (:eacl.schema/db-after result) opts))))

  (read-relationships [_ filters]
    (read-relationships
     api
     ((:db api) conn)
     (assoc opts
            :completed-cache-request?
            (request-cache-enabled? (:cache? filters)))
     (dissoc filters :cache?)))
  (write-relationships! [_ updates]
    (write-relationships! api conn opts updates))
  (write-relationship! [_ operation subject relation resource]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate operation
                                                 (->Relationship subject relation resource))]))
  (write-relationship! [_ {:keys [operation subject relation resource]}]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate operation
                                                 (->Relationship subject relation resource))]))
  (create-relationships! [_ relationships]
    (write-relationships! api conn opts
                          (for [rel relationships]
                            (->RelationshipUpdate :create rel))))
  (create-relationship! [_ relationship]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate :create relationship)]))
  (create-relationship! [_ subject relation resource]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate :create (->Relationship subject relation resource))]))
  (delete-relationships! [_ relationships]
    (write-relationships! api conn opts
                          (for [rel (relationship-seq relationships)]
                            (->RelationshipUpdate :delete rel))))
  (delete-object! [_ object]
    (delete-object! api conn opts object))
  (delete-relationship! [_ {:keys [subject relation resource]}]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate :delete
                                                 (->Relationship subject relation resource))]))
  (delete-relationship! [_ subject relation resource]
    (write-relationships! api conn opts
                          [(->RelationshipUpdate :delete
                                                 (->Relationship subject relation resource))]))

  (lookup-resources [_ query]
    (let [cache-enabled?
          (request-cache-enabled? (:cache? query))]
      (lookup-resources
       api
       ((:db api) conn)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-resources [_ query]
    (count-resources
     api
     ((:db api) conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (lookup-subjects [_ query]
    (let [cache-enabled?
          (request-cache-enabled? (:cache? query))]
      (lookup-subjects
       api
       ((:db api) conn)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-subjects [_ query]
    (count-subjects
     api
     ((:db api) conn)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))

  (expand-permission-tree [_ query]
    (expand-permission-tree
     api ((:db api) conn) opts query))

  IDetailedAuthorization
  (-check-permission
    [_ {:keys [subject permission resource consistency]
        cache? :cache? :as demand}]
    (check-permission
     api
     ((:db api) conn)
     (assoc opts
            :request-operation :check-permission
            :execution-request demand
            :completed-cache-request?
            (request-cache-enabled? cache?))
     subject permission resource
     (or consistency consistency/minimize-latency))))

(defn client?
  "True when `client` is a shared-orchestration client for `backend-id`."
  [client backend-id]
  (and (instance? ClientAuthorization client)
       (= backend-id (get-in client [:api :backend-id]))))

(defn expire-cache!
  "Rotates the complete local cache/token lifecycle for one EACL client.

  The optional second argument is the coordinated lifecycle identity to use
  across processes after a restore. Without it, a fresh process-local UUID is
  installed. In-flight requests retain their captured old lifecycle."
  ([client]
   (expire-cache! client (str (random-uuid))))
  ([client source-lifecycle]
   (causal-token/validate-source-lifecycle! source-lifecycle)
   (when-let [state (get-in client [:opts :source-lifecycle-state])]
     (reset! state source-lifecycle))
   (when-let [store (get-in client [:opts :current-cache-store])]
     (cache/expire-current! store))
   (some-> (get-in client [:opts :derived-schema-caches]) (reset! {}))
   (cursor/clear-codec-cache!
    (get-in client [:opts :cursor-codec-cache]))
   (relay/clear-page-navigation-cache!
    (get-in client [:opts :page-navigation-cache]))
   (some->
    (get-in client [:opts :continuation-cache-store])
    continuation/clear!)
   nil))

(defn cache-stats
  "Returns private completed-cache counters for one EACL client."
  [client]
  (let [current-store
        (get-in client [:opts :current-cache-store])
        continuation-store
        (get-in client [:opts :continuation-cache-store])]
    (cond->
     (if current-store
       (cache/current-cache-stats current-store)
       {:disabled? true})
      continuation-store
      (assoc :continuations
             (continuation/stats continuation-store)))))

(def base-client-opt-keys
  "The uniform make-client option surface shared by every backend
  (backend-unification, D-7). Per-backend extensions are declared via the
  api's :extra-client-opt-keys and documented on the backend's make-client."
  #{:entid->object-id
    :object-id->lookup-ref
    :internal-cursor->spice
    :spice-cursor->internal
    :cursor-ttl-seconds
    :cache
    :recursive-traversal-limits
    :permission-tree-limits
    :security-key
    :security-keyring
    :security-kid
    :token-ttl-seconds
    :source-lifecycle
    :adapter-fingerprint
    :adapter-deterministic?
    :consistency-sync-timeout-ms
    :execution-timeout-ms
    :cache-attempt})

(defn make-client
  "Builds the shared IAuthorization client over one backend api map.

  Validates the uniform option surface, assembles the client-private caches,
  and returns a ClientAuthorization. Managed reuse is automatic when the
  selected immutable snapshot supplies a complete native-generation proof.
  Backend make-client wrappers supply `api` and document their
  :extra-client-opt-keys."
  [api conn
   {:as   config-opts
    :keys [entid->object-id
           object-id->lookup-ref
           internal-cursor->spice
           spice-cursor->internal
           cursor-ttl-seconds
           cache
           recursive-traversal-limits
           permission-tree-limits
           security-key
           security-keyring
           security-kid
           token-ttl-seconds
           source-lifecycle
           adapter-fingerprint
           adapter-deterministic?
           consistency-sync-timeout-ms
           execution-timeout-ms
           cache-attempt
           ]
    :or   {object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (let [known-client-opt-keys
        (into base-client-opt-keys (:extra-client-opt-keys api))]
    (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
      (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                           ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
                      {:type :eacl/invalid-config
                       :unknown-keys (vec unknown-keys)
                       :known-keys known-client-opt-keys}))))
  (when (and security-key security-keyring)
    (throw (ex-info "EACL Config Error: supply only one of :security-key or :security-keyring."
                    {:type :eacl/invalid-config
                     :conflicting-keys [:security-key :security-keyring]})))
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
  (when source-lifecycle
    (try
      (causal-token/validate-source-lifecycle! source-lifecycle)
      (catch #?(:clj Exception :cljs :default) error
        (throw
         (ex-info
          "EACL Config Error: :source-lifecycle must be bounded portable canonical data."
          {:type :eacl/invalid-config
           :key :source-lifecycle
           :value source-lifecycle}
          error)))))
  (when (and consistency-sync-timeout-ms
             (not (and (integer? consistency-sync-timeout-ms)
                       (pos? consistency-sync-timeout-ms))))
    (throw (ex-info "EACL Config Error: :consistency-sync-timeout-ms must be positive."
                    {:type :eacl/invalid-config
                     :key :consistency-sync-timeout-ms
                     :value consistency-sync-timeout-ms})))
  (when (and execution-timeout-ms
             (not (and (integer? execution-timeout-ms)
                       (pos? execution-timeout-ms)
                       (<= execution-timeout-ms
                           execution/maximum-execution-timeout-ms))))
    (throw
     (ex-info
      "EACL Config Error: :execution-timeout-ms must be a positive integer within the supported range."
      {:type :eacl/invalid-config
       :key :execution-timeout-ms
       :value execution-timeout-ms
       :maximum-timeout-ms execution/maximum-execution-timeout-ms})))
  (let [source-lifecycle (or source-lifecycle (str (random-uuid)))
        source-lifecycle-state (atom source-lifecycle)
        codec-instance-id (str (random-uuid))
        native-source-id
        (if-let [native-source-id-fn (:native-source-id api)]
          (native-source-id-fn conn)
          (str (random-uuid)))
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
                            causal-token/default-token-ttl-seconds)}
        object-id->entid (fn [db object-id]
                           ((:entid api) db (object-id->lookup-ref object-id)))
        custom-codec?
        (boolean
         (or entid->object-id
             (contains? config-opts :object-id->lookup-ref)))
        managed-cache-eligible?
        (or (not custom-codec?)
            (and (some? adapter-fingerprint)
                 (true? adapter-deterministic?)))
        current-cache-store
        (cache/current-cache-for-option cache)
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
                             (:default-entid->object-id api))
        opts             {:object-id->lookup-ref object-id->lookup-ref
                          :conn conn
                          :derived-schema-caches (atom {})
                          :adapter-fingerprint
                          (or adapter-fingerprint
                              {:backend (:backend-id api)
                               :adapter-version backend/adapter-version
                               :recursive-traversal-limits
                               recursive-traversal-limits
                               :codec
                               (if custom-codec?
                                 [:custom-unfingerprinted codec-instance-id]
                                 :eacl-id-immutable-v1)})
                          :adapter-deterministic?
                          (if custom-codec?
                            (true? adapter-deterministic?)
                            true)
                          :entid->object-id entid->object-id
                          :object-id->entid object-id->entid
                          :cursor-ttl-seconds cursor-ttl-seconds
                          :format-options format-options
                          :source-lifecycle source-lifecycle
                          :source-lifecycle-state source-lifecycle-state
                          :native-source-id native-source-id
                          :decision-kernel production-kernel/default-selection
                          :consistency-sync-timeout-ms
                          (or consistency-sync-timeout-ms 30000)
                          :execution-timeout-ms
                          (or execution-timeout-ms
                              execution/default-execution-timeout-ms)
                          :cache-attempt
                          (execution/normalize-cache-attempt cache-attempt)
                          :token-ttl-seconds
                          (or token-ttl-seconds
                              causal-token/default-token-ttl-seconds)
                          :current-cache-store
                          current-cache-store
                          :continuation-cache-store
                          (when current-cache-store
                            (continuation/make-store
                             {:max-entries
                              (if (and (map? cache)
                                       (integer? (:max-entries cache)))
                                (:max-entries cache)
                                2048)}))
                          :cursor-codec-cache cursor-codec-cache
                          :page-navigation-cache
                          page-navigation-cache
                          :managed-cache-enabled? managed-cache-eligible?
                          :recursive-traversal-limits
                          (engine/normalize-recursive-traversal-limits
                           recursive-traversal-limits)
                          :permission-tree-limits
                          (permission-tree/normalize-limits
                           permission-tree-limits)
                          :object->entid (fn [db {:keys [id]}]
                                           (object-id->entid db id))
                          :internal-object->spice (fn [db {:keys [type id]}]
                                                    (spice-object type (entid->object-id db id)))
                          :spice-object->internal (fn [db obj]
                                                    (update obj :id #(object-id->entid db %)))
                          :internal-cursor->spice internal-cursor->spice
                          :spice-cursor->internal spice-cursor->internal}]
    (->ClientAuthorization conn opts api)))
