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
    :snapshot-adapter           (fn [db opts] v8-adapter), for raw DB helpers
    :snapshot-provider          (fn [conn opts] long-lived provider)
    :db-native-revision         (fn [db] committed native revision map)
    :native-source-id           optional (fn [conn] stable source identity)
    :prepared-native-source-id-key optional private config key populated by
                                  a backend bootstrap wrapper
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
            [eacl.authorization.batch :as batch]
            [eacl.authorization.filters :as authorization-filters]
            [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.continuation :as continuation]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorization
                                        IBatchedAuthorization
                                        IDetailedAuthorization
                                        ISnapshotAuthorization
                                        spice-object
                                        ->Relationship
                                        ->RelationshipUpdate]]
            [eacl.engine.physical :as physical]
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
            [eacl.request.context :as request-context]
            [eacl.request.counters :as request-counters]
            [eacl.schema.errors :as schema-errors]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.spicedb.consistency :as consistency]))

(declare request-cache-enabled? validate-permission-root!)

(defn- ensure-execution-contract
  [opts operation request]
  (let [opts
        (cond-> opts
          (nil? (:cache-lifecycle opts))
          (assoc :cache-lifecycle
                 (cache/capture-current-lifecycle
                  (:current-cache-store opts))))]
    (assoc opts :execution-contract
           (if-let [contract (:execution-contract opts)]
             (execution/refine contract opts operation request)
             (execution/normalize opts operation request)))))

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

(defn- adapter-semantic-identity
  [adapter]
  (let [scope (consistency-v3/source-scope adapter)
        revision (consistency-v3/native-revision adapter)
        snapshot-id (backend/invoke adapter :snapshot-id)]
    {:backend (backend/backend-id adapter)
     :source-id (:source-id scope)
     :branch (:branch scope)
     :source-lifecycle (:source-lifecycle scope)
     :revision (:revision revision)
     :exact-locator (:exact-locator revision)
     :backend-snapshot-id snapshot-id}))

(defn- release-selected-after-error!
  "Closes ownership that was transferred into orchestration before propagating
  a context-construction failure. A failed cleanup is classified explicitly
  and retains the original failure as data."
  [selected error]
  (when selected
    (try
      (snapshot-provider/release! selected)
      (catch #?(:clj Throwable :cljs :default) release-error
        (throw
         (ex-info
          "Request context construction failed and snapshot cleanup also failed."
          {:type :eacl/snapshot-release-failed
           :eacl/error :eacl/snapshot-release-failed
           :context-error (ex-data error)}
          release-error)))))
  (throw error))

(defn- select-request-basis
  [api source opts consistency-value]
  (let [contract (:execution-contract opts)
        _ (execution/check! contract :consistency-selection)
        descriptor (consistency/descriptor consistency-value)
        provider? (snapshot-provider/provider? source)
        source-adapter (when-not provider?
                         ((:snapshot-adapter api) source opts))
        _ (when (and (not provider?)
                     (= :datascript (backend/backend-id source-adapter))
                     (= :at-exact-snapshot (:mode descriptor)))
            (throw
             (ex-info
              "DataScript is current-basis-only and does not retain exact historical snapshots."
              {:type :eacl.consistency/exact-snapshot-unavailable
               :eacl/error :eacl.consistency/exact-snapshot-unavailable
               :reason :exact-snapshot-unavailable
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
         :selection-check!
         (when contract
           (fn [phase]
             (execution/check! contract phase)))
         :timeout-ms
         (if contract
           (min (:consistency-sync-timeout-ms opts)
                (execution/remaining-millis contract))
           (:consistency-sync-timeout-ms opts))}
        selection
        (if provider?
          (consistency-v3/select
           source consistency-value selection-options)
          (if (= :minimize-latency (:mode descriptor))
            (consistency-v3/captured-current-selection
             source-adapter consistency-value selection-options)
            (consistency-v3/select
             source-adapter consistency-value selection-options)))
        adapter (:adapter selection)
        selected (:selected-snapshot selection)
        _ (when selected
            (request-counters/add! :acquisitions))]
    (try
      (execution/check! contract :consistency-selected)
      (let [semantic-identity
            (if selected
              (snapshot-provider/semantic-identity selected)
              (adapter-semantic-identity adapter))
            snapshot-exact?
            (= :at-exact-snapshot
               (get-in selection [:descriptor :mode]))
            completed-cache?
            (and (:completed-cache-request? opts)
                 (or (not snapshot-exact?)
                     (backend/deterministic? adapter)))]
        {:adapter adapter
         :db (:db (backend/state adapter))
         :selection selection
         :selected-snapshot selected
         :semantic-identity semantic-identity
         :snapshot-exact? snapshot-exact?
         :completed-cache? completed-cache?})
      (catch #?(:clj Throwable :cljs :default) error
        (release-selected-after-error! selected error)))))

(defn- selected-context
  [api source opts consistency-value]
  (let [{:keys [adapter selected-snapshot semantic-identity selection
                snapshot-exact? completed-cache?]}
        (select-request-basis api source opts consistency-value)
        runtime
        (assoc opts
               ::selection selection
               ::snapshot-exact? snapshot-exact?
               ::completed-cache? completed-cache?)]
    (request-context/make-context
     {:runtime runtime
      :adapter adapter
      :selected-snapshot selected-snapshot
      :basis-identity semantic-identity
      :contract (:execution-contract opts)
      :derived-registry (:derived-schema-caches opts)
      :counter-ledger (:request-counter-ledger opts)
      :proof-diagnostic-fn
      (fn [diagnostic]
        (cache/record-proof-unavailable!
         (:current-cache-store opts)
         diagnostic))})))

(defn- with-selected-basis
  [api source opts consistency-value f]
  (let [basis (select-request-basis api source opts consistency-value)]
    (try
      (f basis)
      (finally
        (when-let [selected (:selected-snapshot basis)]
          (snapshot-provider/release! selected))))))

(defn- with-selected-context
  [api source opts consistency-value f]
  (if-let [active-context (::request-context opts)]
    (request-context/call-with-context
     active-context
     (fn [context]
       (binding [execution/*contract* (:execution-contract opts)]
         (f context))))
    (let [ledger (or (:request-counter-ledger opts)
                     request-counters/*ledger*
                     (request-counters/make-ledger))
          opts (assoc opts :request-counter-ledger ledger)]
      (request-counters/call-with-ledger
       ledger
       (fn []
         (request-counters/add! :public-entries)
         (request-counters/add! :contract-normalizations)
         (let [context (selected-context api source opts consistency-value)]
           (try
             (request-context/call-with-context context f)
             (finally
               (request-context/close! context)))))))))

(defn- context-runtime
  [context]
  (request-context/runtime context))

(defn- context-selection
  [context]
  (::selection (context-runtime context)))

(defn- context-db
  [context]
  (:db (backend/state (request-context/adapter context))))

(defn- selected-cache-options
  [opts context]
  (let [snapshot-exact? (::snapshot-exact? (context-runtime context))]
    (assoc opts
           :completed-cache?
           (and (:completed-cache-request? opts)
                (or (not snapshot-exact?)
                    (backend/deterministic?
                     (request-context/adapter context))))
           :snapshot-exact? snapshot-exact?
           :snapshot-semantic-identity
           (request-context/basis-identity context)
           :request-proof-frame (request-context/proof-frame context)
           :request-schema-cache (delay (request-context/derived context)))))

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

(defn- relationship-filter-relation-eids
  [adapter {:keys [resource-type relation subject-type]}]
  (when (and (keyword? resource-type) (keyword? relation))
    (->> (engine/relation-datoms adapter resource-type relation)
         (filter #(or (nil? subject-type)
                      (= subject-type (nth (:v %) 2))))
         (map :e)
         distinct
         sort
         vec)))

(defn- cursor-options
  "Request options for relay cursor handling.

  One shared derived-schema-cache delay serves the engine evaluation, the
  cursor scope's schema stamp, and the cursor dependency closure, so the
  dependency-scoped cursor contexts add no schema-generation reads beyond the
  request's own resolution. All three delays are forced only when a cursor
  is actually minted or resumed."
  [request-context adapter opts selection resource-type permission
   relationship-dependency]
  (let [contract (:execution-contract opts)
        candidate-proof-frame (:request-proof-frame opts)
        reuse-request-context?
        (identical? adapter (request-context/adapter request-context))
        request-proof-frame
        (if reuse-request-context?
          (request-context/proof-frame request-context)
          (if (and candidate-proof-frame
                   (identical? adapter (:adapter candidate-proof-frame)))
            candidate-proof-frame
            (new-request-proof-frame adapter opts)))
        schema-cache
        (if reuse-request-context?
          (delay (request-context/derived request-context))
          (or (:request-schema-cache opts)
              (delay
                (binding [engine/*proof-frame* request-proof-frame]
                  (engine/schema-cache-for!
                   (:derived-schema-caches opts)
                   adapter)))))]
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
           (when (or (and resource-type permission)
                     relationship-dependency)
             (delay
               (try
                 (binding [engine/*schema-cache* @schema-cache]
                   (let [permission-ids
                         (when (and resource-type permission)
                           (engine/permission-relationship-eids
                            adapter resource-type permission))
                         relationship-ids
                         (when relationship-dependency
                           (relationship-filter-relation-eids
                            adapter relationship-dependency))]
                     ;; A broad relationship scan cannot name a bounded
                     ;; relation dependency set through the current SPI. It
                     ;; therefore falls back to the existing exact-snapshot
                     ;; cursor proof instead of publishing a partial proof.
                     (when (or (nil? relationship-dependency)
                               (some? relationship-ids))
                       (->> (concat permission-ids relationship-ids)
                            distinct
                            sort
                            vec))))
                 (catch #?(:clj Exception :cljs :default) _
                   nil)))))))

(defn- page-context
  [request-context opts operation query resource-type permission
   relationship-dependency]
  (let [;; Low-level raw-DB entry points may receive a client's opts map. They
        ;; must remain bound to that caller-owned DB and must not reach through
        ;; the client's live provider during cursor recovery.
        selection (context-selection request-context)
        opts (if (:selected-snapshot selection)
               opts
               (dissoc opts :snapshot-provider))
        adapter (request-context/adapter request-context)
        current-opts
        (cursor-options
         request-context adapter opts selection resource-type permission
         relationship-dependency)
        prepared
        (relay/prepare-page-query
         adapter current-opts operation query)
        page-adapter
        (:adapter prepared)
        page-selected-snapshot (:selected-snapshot prepared)]
    (try
      (let [initial-semantic-identity
            (if-let [selected (:selected-snapshot selection)]
              (snapshot-provider/semantic-identity selected)
              (adapter-semantic-identity adapter))
            page-semantic-identity
            (if page-selected-snapshot
              (snapshot-provider/semantic-identity page-selected-snapshot)
              (adapter-semantic-identity page-adapter))
            snapshot-exact?
            (or (= :at-exact-snapshot
                   (get-in selection [:descriptor :mode]))
                (not= initial-semantic-identity page-semantic-identity))
            page-opts
            (assoc
             (cursor-options
              request-context page-adapter opts selection
              resource-type permission relationship-dependency)
             :snapshot-semantic-identity page-semantic-identity
             :snapshot-exact? snapshot-exact?
             :completed-cache?
             (and
              (:completed-cache-request? opts)
              ;; Historical completed-answer reuse additionally requires a stable
              ;; adapter/identity contract. Cursor authentication and exact
              ;; selection have already happened before this decision.
              (or (not snapshot-exact?)
                  (backend/deterministic? page-adapter))))]
        {:adapter page-adapter
         :request-context request-context
         :selected-snapshot page-selected-snapshot
         :snapshot-semantic-identity page-semantic-identity
         :db (:db (backend/state page-adapter))
         :opts page-opts
         :query (:query prepared)})
      (catch #?(:clj Throwable :cljs :default) error
        (release-selected-after-error! page-selected-snapshot error)))))

(defn- with-page-context
  [request-context opts operation query resource-type permission
   relationship-dependency f]
  (let [page
        (page-context
         request-context opts operation query resource-type permission
         relationship-dependency)]
    (try
      (f page)
      (finally
        (when-let [selected (:selected-snapshot page)]
          (snapshot-provider/release! selected))))))

(defn- cached-engine-result
  [request-context adapter opts operation query resource-type permission
   valid-value? compute]
  (let [contract (:execution-contract opts)
        context-adapter (request-context/adapter request-context)
        request-proof-frame
        (if (identical? adapter context-adapter)
          (request-context/proof-frame request-context)
          (let [candidate (:request-proof-frame opts)]
            (if (and candidate
                     (identical? adapter (:adapter candidate)))
              candidate
              (new-request-proof-frame adapter opts))))
        schema-cache
        (if (identical? adapter context-adapter)
          (delay (request-context/derived request-context))
          (or (:request-schema-cache opts)
              (delay
                (binding [engine/*proof-frame* request-proof-frame]
                  (engine/schema-cache-for!
                   (:derived-schema-caches opts)
                   adapter)))))
        evaluate
        #(do
           (execution/check! contract :schema-plan)
           (let [value
                 (binding [engine/*schema-cache* @schema-cache
                           engine/*proof-frame* request-proof-frame
                           engine/*recursive-traversal-limits*
                           (:recursive-traversal-limits opts)
                           engine/*service-admission*
                           (:service-admission opts)
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
      (let [_ (request-counters/add! :cache-key-builds)
            dependencies
            (delay
              (binding [engine/*schema-cache* @schema-cache
                        engine/*proof-frame* request-proof-frame]
                (let [permission-deps
                      (when (and resource-type permission)
                        (permission-dependencies
                         adapter resource-type permission))
                      page-deps
                      (when-let [dependency-ids
                                 (:cursor-dependency-relation-ids opts)]
                        @dependency-ids)
                      relation-ids
                      (->> (concat (:relation-ids permission-deps)
                                   page-deps)
                           distinct
                           sort
                           vec)]
                  {:relation-ids relation-ids
                   :schema-scope
                   (assoc (or (:schema-scope permission-deps) {})
                          :relation-ids relation-ids)})))
            complete-proof
            (delay
              (proof-frame/resolve!
               request-proof-frame
               (:relation-ids @dependencies)))
            semantic-snapshot (:snapshot-semantic-identity opts)
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
             (:recursive-traversal-limits opts)
             :permission-tree-limits
             (:permission-tree-limits opts)}]
        (execution/check! contract :cache-lookup)
        (let [answer
              (binding
               [subproblem/*publication-attempt-limit*
                (get-in contract
                        [:cache-attempt :maximum-atomic-attempts]
                        4)]
                (if (:snapshot-exact? opts)
                  (cache/resolve-exact!
                   (:current-cache-store opts)
                   {:snapshot-exact-key (cache/snapshot-exact-key adapter)
                    :cache-lifecycle (:cache-lifecycle opts)
                    :cache-basis (backend/invoke adapter :snapshot-id)
                    :decision-kernel (:decision-kernel opts)}
                   semantic-key operation valid-value? evaluate)
                  (cache/resolve-current!
                   (:current-cache-store opts)
                   {:snapshot semantic-snapshot
                    :cache-lifecycle (:cache-lifecycle opts)
                    :snapshot-order (:revision semantic-snapshot)
                    :same-snapshot? =
                    :snapshot-exact-key (cache/snapshot-exact-key adapter)
                    :cache-basis (backend/invoke adapter :snapshot-id)
                    :decision-kernel (:decision-kernel opts)
                    :managed-key-fn
                    (when (and (:managed-cache-enabled? opts)
                               resource-type permission)
                      #(proof-frame/descriptor @complete-proof))
                    :managed-subproblem-key-fn
                    (when (and (:managed-cache-enabled? opts)
                               resource-type permission)
                      (fn [dependency]
                        (proof-frame/subset-descriptor
                         @complete-proof dependency)))
                    :managed-subproblem-scope
                    (consistency-v3/source-scope adapter)}
                   semantic-key operation valid-value? evaluate)))]
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

(defn- request-schema
  "The parsed public schema visible in `db`, for validating one request.

  Inside a cached computation the engine binds the client's proof-keyed
  schema generation (`engine/*schema-cache*`); its `:parsed-schema` slot is
  read once per generation, so validation costs no schema enumeration and
  cache hits never read the schema at all (validation runs on the miss
  path — no entry can exist for a request that failed validation, and every
  tier keys by an identity that fixes the schema generation). An unstamped
  database (no schema generation) or an unbound generation reads the schema
  directly."
  [api db]
  (let [cache engine/*schema-cache*
        slot (:parsed-schema cache)
        catalog-slot (:validation-catalog cache)
        read-schema (get-in api [:schema :read-schema])]
    (if (and slot
             (or (some? (:schema-version cache))
                 (true? (:request-local? cache))))
      (let [schema
            (engine/memoized-derived! slot #(read-schema db))
            names
            (if catalog-slot
              (engine/memoized-derived!
               catalog-slot #(schema-errors/catalog schema))
              (schema-errors/catalog schema))]
        (schema-errors/with-catalog schema names))
      (read-schema db))))

(defn- authorization-scan-page
  [api opts request-context adapter selected-db cursor-opts filters
   internal-query validate!]
  (let [{:keys [subject permission on]} (:authorization filters)
        internal-subject ((:spice-object->internal opts) selected-db subject)
        endpoint-type (get filters (case on
                                     :subject :subject/type
                                     :resource :resource/type))
        schema-cache (:request-schema-cache cursor-opts)
        request-proof-frame (:request-proof-frame cursor-opts)
        contract (:execution-contract opts)
        limits (:aggregate-limits contract)
        candidate-window (:candidate-window limits)
        ledger (request-context/counter-ledger request-context)
        ledger-before (request-counters/snapshot ledger)
        work-stats (atom {})
        work-before @work-stats
        counters
        (fn [output-units]
          (batch/aggregate-counters
           work-before @work-stats
           ledger-before (request-counters/snapshot ledger)
           output-units))
        accept?
        (fn [relationship]
          (execution/check! contract :authorization-candidate (counters 0))
          (let [endpoint (get relationship on)
                allowed?
                (if-not (:id internal-subject)
                  false
                  (request-context/memoized!
                   request-context
                   :decisions
                   [:authorization-scan
                    (batch/demand-key
                     {:subject subject
                      :permission permission
                      :resource endpoint})]
                   #(binding [engine/*schema-cache* @schema-cache
                              engine/*proof-frame*
                              request-proof-frame
                              engine/*recursive-traversal-limits*
                              (:recursive-traversal-limits opts)
                              engine/*service-admission*
                              (:service-admission opts)
                              engine/*evaluation-mode* (:evaluation contract)
                              execution/*contract* contract
                              subproblem/*decision-kernel*
                              (:decision-kernel opts)]
                      (engine/can?
                       adapter internal-subject permission endpoint))))]
            (batch/check-aggregate-limits! limits (counters 0) nil)
            allowed?))]
    ;; Root/schema validation is selected-snapshot work but precedes the first
    ;; physical relationship candidate.
    (binding [engine/*schema-cache* @schema-cache
              engine/*proof-frame* request-proof-frame]
      (validate!)
      (validate-permission-root!
       api request-context selected-db opts subject permission
       {:type endpoint-type :id ::authorization-endpoint-type}))
    (let [internal-page
          (binding [engine/*aggregate-work-stats* work-stats]
            ((get-in api [:impl :read-relationships])
             selected-db internal-query (:decision-kernel cursor-opts)
             {:candidate-window candidate-window
              :accept? accept?}))]
      (batch/check-aggregate-limits!
       limits (counters (count (:data internal-page))) nil)
      internal-page)))

(defn read-relationships
  [api source {:as opts :keys [object-id->entid]} filters]
  ;; The unified filter contract validates the complete public query before
  ;; any snapshot selection or cursor work (backend-unification 9.1).
  (relationship-filters/validate! filters)
  (authorization-filters/validate-scan-authorization! filters)
  (let [opts (ensure-execution-contract opts :read-relationships filters)
        authorization (:authorization filters)
        authorization-resource-type
        (when authorization
          (get filters (case (:on authorization)
                         :subject :subject/type
                         :resource :resource/type)))
        authorization-permission (:permission authorization)]
    (with-selected-context
      api source opts (:consistency filters)
      (fn [request-context]
        (with-page-context
          request-context opts :read-relationships filters
          authorization-resource-type authorization-permission
          (when authorization
            {:resource-type (:resource/type filters)
             :relation (:resource/relation filters)
             :subject-type (:subject/type filters)})
          (fn [{request-context :request-context
                adapter :adapter page-db :db cursor-opts :opts
                page-query :query}]
            (let [;; Schema validation runs on the miss path (inside the bound
                ;; schema generation) and on the unknown-object short-circuit.
                  validate!
                  (fn []
                    (schema-errors/validate-authorized-relationship-read!
                     (request-schema api page-db)
                     filters))
                  base-filters
                  (apply dissoc filters
                         [:first :last :after :before :consistency :cache?
                          :evaluation :timeout-ms :cancellation-token
                          :aggregate-limits :authorization])
                  subject-id (:subject/id base-filters)
                  resource-id (:resource/id base-filters)
                  subject-eid
                  (when subject-id
                    (object-id->entid page-db subject-id))
                  resource-eid
                  (when resource-id
                    (object-id->entid page-db resource-id))
                  internal-query
                  (-> page-query
                      (dissoc :consistency :cache? :evaluation :timeout-ms
                              :cancellation-token :aggregate-limits
                              :authorization)
                      (cond->
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid)))]
              (if (or (and subject-id (nil? subject-eid))
                      (and resource-id (nil? resource-eid)))
                (do
                  (validate!)
                  (if (cursor-request? filters)
                    (stale-cursor-anchor! :read-relationships)
                    (cond->
                     (assoc relay/empty-page
                            :cached? false :cache-basis nil)
                      (:authorization filters)
                      (assoc-in [:page-info :bounded?] false))))
                (if (:authorization filters)
                  (or
                   (relay/lookup-visited-page
                    adapter cursor-opts :read-relationships filters)
                   (let [answer
                         (cached-engine-result
                          request-context adapter cursor-opts
                          :read-relationships
                          (cache/lookup-page-query-identity
                           filters internal-query)
                          authorization-resource-type
                          authorization-permission
                          #(and (map? %)
                                (vector? (:data %))
                                (map? (:page-info %)))
                          #(authorization-scan-page
                            api opts request-context adapter page-db
                            cursor-opts filters internal-query validate!))
                         page
                         (with-cache-info
                           (relay/externalize-relationship-page
                            adapter cursor-opts :read-relationships filters
                            (:value answer))
                           answer)]
                     (relay/remember-visited-page!
                      adapter cursor-opts :read-relationships filters
                      page)))
                  (or
                   (relay/lookup-visited-page
                    adapter cursor-opts :read-relationships filters)
                   (let [answer
                         (cached-engine-result
                          request-context adapter cursor-opts
                          :read-relationships
                          (cache/lookup-page-query-identity
                           filters internal-query)
                          nil nil
                          #(and (map? %)
                                (vector? (:data %))
                                (map? (:page-info %)))
                          #(do
                             (validate!)
                             ((get-in api [:impl :read-relationships])
                              page-db internal-query
                              (:decision-kernel cursor-opts))))
                         page
                         (with-cache-info
                           (relay/externalize-relationship-page
                            adapter cursor-opts :read-relationships filters
                            (:value answer))
                           answer)]
                     (relay/remember-visited-page!
                      adapter cursor-opts :read-relationships filters
                      page))))))))))))

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

(defn- response-token-for-revision
  [api native-revision opts]
  (let [provider (:snapshot-provider opts)]
    (if (snapshot-provider/provider? provider)
      (causal-token/issue
       (:format-options opts)
       (merge
        {:backend (snapshot-provider/backend-id provider)
         :source-lifecycle
         (snapshot-provider/source-lifecycle provider)}
        (snapshot-provider/source-scope provider)
        native-revision))
      nil)))

(defn- response-token
  [api db opts]
  (if-let [native-revision-fn (:db-native-revision api)]
    (response-token-for-revision api (native-revision-fn db) opts)
    (let [adapter ((:snapshot-adapter api) db opts)]
      (causal-token/issue
       (:format-options opts)
       (merge
        (consistency-v3/source-scope adapter)
        (consistency-v3/native-revision adapter))))))

(defn- write-response
  [api db opts]
  (if-let [token (response-token api db opts)]
    {:zed/token token}
    {}))

(defn- write-response-for-revision
  [api native-revision opts]
  (if-let [token
           (response-token-for-revision api native-revision opts)]
    {:zed/token token}
    {}))

(defn- committed-write-response
  "Acknowledges one committed backend revision only after any backend-specific
  monotonic durability hook has completed."
  [api db opts]
  (if-let [native-revision-fn (:db-native-revision api)]
    (let [native-revision (native-revision-fn db)]
      (when-let [after-commit! (:after-commit! api)]
        (after-commit! native-revision opts))
      (write-response-for-revision api native-revision opts))
    (write-response api db opts)))

(defn- with-write-planning-context
  [api conn opts f]
  (if-let [provider (:snapshot-provider opts)]
    (with-selected-basis
      api provider opts consistency/minimize-latency
      (fn [{:keys [db selection]}]
        {:plan (f db)
         :native-revision (:native-revision selection)}))
    (let [db ((:db api) conn)
          adapter ((:snapshot-adapter api) db opts)]
      {:plan (f db)
       :native-revision (consistency-v3/native-revision adapter)
       :raw-db db})))

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
        {tx-data :plan planning-revision :native-revision raw-db :raw-db}
        (with-write-planning-context
          api conn opts
          (fn [db]
            (let [schema ((get-in api [:schema :read-schema]) db)
                  _ (doseq [{:keys [relationship]} updates]
                      (schema-errors/validate-relationship-write!
                       schema :write-relationships
                       {:resource-type
                        (:type (:resource relationship))
                        :subject-type
                        (:type (:subject relationship))
                        :relation (:relation relationship)}))
                  internal-updates
                  (S/transform
                   [S/ALL :relationship]
                   #(spice-relationship->internal db opts %)
                   updates)
                  _ (relationship-mutations/validate-batch!
                     internal-updates)]
              (->> internal-updates
                   (mapcat #(tx-update-relationship db %))
                   (remove nil?)
                   distinct
                   vec
                   relationship-commit-preconditions-first))))]
    (if (seq tx-data)
      (let [report
            ((:transact! api)
             conn
             {:tx-data tx-data})]
        (committed-write-response api (:db-after report) opts))
      (if raw-db
        (write-response api raw-db opts)
        (write-response-for-revision api planning-revision opts)))))

(defn delete-object!
  "Removes every relationship that references `object`, without retracting the
  object entity itself."
  [api conn {:keys [object->entid] :as opts} object]
  (let [{tx-data :plan planning-revision :native-revision raw-db :raw-db}
        (with-write-planning-context
          api conn opts
          (fn [db]
            (let [object-eid
                  (or (try
                        (object->entid db object)
                        (catch #?(:clj Exception :cljs :default) _
                          nil))
                      (when (number? (:id object))
                        (:id object)))]
              ((get-in api [:impl :tx-delete-object]) db object-eid))))]
    (if (seq tx-data)
      (let [report
            ((:transact! api)
             conn
             {:tx-data tx-data})]
        (assoc (committed-write-response api (:db-after report) opts)
               :retracted-datoms
               ((:relationship-retraction-count api)
                (:db-after report) (:tx-data report))))
      (assoc (if raw-db
               (write-response api raw-db opts)
               (write-response-for-revision
                api planning-revision opts))
             :retracted-datoms 0))))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn- validate-permission-root!
  [api request-context selected-db opts subject permission resource]
  (request-context/memoized!
   request-context
   :prepared-roots
   [(:type resource) permission (:type subject)]
   #(do
      (schema-errors/validate-permission-request!
       (request-schema api selected-db)
       (or (:request-operation opts) :can?)
       {:resource-type (:type resource)
        :subject-type (:type subject)
        :permission permission})
      true)))

(defn- check-permission-in-context
  [api {:keys [spice-object->internal] :as opts} request-context
   subject permission resource]
  (let [selected-db (context-db request-context)
        adapter (request-context/adapter request-context)
        opts (selected-cache-options opts request-context)
        validate!
        #(validate-permission-root!
          api request-context selected-db opts subject permission resource)
        internal-subject
        (spice-object->internal selected-db subject)
        internal-resource
        (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      (do
        (validate!)
        {:allowed? false
         :cached? false
         :cache-basis nil
         :evaluation
         (get-in opts [:execution-contract :evaluation])})
      (let [answer
            (cached-engine-result
             request-context adapter opts :can?
             {:public [subject permission resource]
              :internal
              [internal-subject permission internal-resource]}
             (:type internal-resource)
             permission
             boolean?
             #(do
                (validate!)
                (engine/can?
                 adapter internal-subject permission internal-resource)))]
        {:allowed? (:value answer)
         :cached? (:cached? answer)
         :cache-basis (:cache-basis answer)
         :evaluation
         (get-in opts [:execution-contract :evaluation])}))))

(defn check-permission
  [api source opts subject permission resource consistency]
  (let [request
        (merge {:subject subject
                :permission permission
                :resource resource
                :consistency consistency}
               (:execution-request opts))
        opts (ensure-execution-contract
              opts (or (:request-operation opts) :can?) request)]
    (with-selected-context
      api source opts consistency
      #(check-permission-in-context
        api opts % subject permission resource))))

(defn- batch-counters
  [work-before work-stats ledger-before ledger output-units]
  (batch/aggregate-counters
   work-before @work-stats
   ledger-before (request-counters/snapshot ledger)
   output-units))

(defn check-permissions
  [api source opts request]
  (let [request (batch/validate-request! request (:aggregate-limits opts))
        checks (:checks request)]
    (if (empty? checks)
      (do
        ;; Validate every request-wide control without capturing cache state or
        ;; acquiring a snapshot.
        (execution/normalize opts :check-permissions request)
        [])
      (let [opts
            (-> opts
                (assoc :request-operation :check-permissions
                       :execution-request request
                       :completed-cache-request?
                       (request-cache-enabled? (:cache? request)))
                (ensure-execution-contract :check-permissions request))]
        (batch/call-with-demand-error
         0 batch/empty-aggregate-counters
         (fn []
           (with-selected-context
             api source opts (:consistency request)
             (fn [request-context]
               (let [batch-contract (:execution-contract opts)
                     scalar-contract (batch/scalar-contract batch-contract)
                     scalar-opts
                     (assoc opts :execution-contract scalar-contract)
                     ledger (request-context/counter-ledger request-context)
                     ledger-before (request-counters/snapshot ledger)
                     work-stats (atom {})
                     work-before @work-stats
                     counters-fn
                     #(batch-counters
                       work-before work-stats ledger-before ledger %)
                     limits (:aggregate-limits request)]
                 (binding [engine/*aggregate-work-stats* work-stats]
                   (loop [index 0
                          output (transient [])]
                     (if (= index (count checks))
                       (let [counters (counters-fn index)]
                         (try
                           (execution/check!
                            batch-contract :batch-complete counters)
                           (catch #?(:clj Throwable :cljs :default) error
                             (batch/throw-demand-error!
                              error (dec index) counters)))
                         (persistent! output))
                       (let [demand (nth checks index)
                             before-counters (counters-fn index)
                             decision
                             (try
                               (execution/check!
                                batch-contract
                                :batch-demand-schedule
                                before-counters)
                               (let [{:keys [subject permission resource]}
                                     demand]
                                 (request-context/memoized!
                                  request-context
                                  :decisions
                                  (batch/demand-key demand)
                                  #(check-permission-in-context
                                    api scalar-opts request-context
                                    subject permission resource)))
                               (catch #?(:clj Throwable :cljs :default) error
                                 (batch/throw-demand-error!
                                  error index (counters-fn index))))
                             next-count (inc index)
                             counters (counters-fn next-count)]
                         (try
                           (batch/check-aggregate-limits!
                            limits counters index)
                           (catch #?(:clj Throwable :cljs :default) error
                             (batch/throw-demand-error!
                              error index counters)))
                         (recur next-count (conj! output decision)))))))))))))))

(defn can?
  [api source opts subject permission resource consistency]
  (:allowed?
   (check-permission
    api source opts subject permission resource consistency)))

(defn- required-direct-relation-id
  [adapter resource-type relation subject-type]
  (or
   (some
    (fn [{:keys [e v]}]
      (when (= subject-type (nth v 2)) e))
    (engine/relation-datoms adapter resource-type relation))
   (throw
    (ex-info
     "Validated direct relationship definition is absent from the selected adapter."
     {:type :eacl/backend-integrity-error
      :eacl/error :eacl/backend-integrity-error
      :resource-type resource-type
      :relation relation
      :subject-type subject-type}))))

(defn- relationship-filtered-lookup-page
  [api opts request-context adapter selected-db cursor-opts operation query
   internal-query validate!]
  (let [contract (:execution-contract opts)
        limits (:aggregate-limits contract)
        candidate-window (:candidate-window limits)
        schema-cache (:request-schema-cache cursor-opts)
        request-proof-frame (:request-proof-frame cursor-opts)
        ledger (request-context/counter-ledger request-context)
        ledger-before (request-counters/snapshot ledger)
        work-stats (atom {})
        work-before @work-stats
        counters
        (fn [output-units]
          (batch/aggregate-counters
           work-before @work-stats
           ledger-before (request-counters/snapshot ledger)
           output-units))
        clause
        (case operation
          :lookup-resources (:resource/relationship query)
          :lookup-subjects (:subject/relationship query))
        public-anchor
        (case operation
          :lookup-resources (:subject clause)
          :lookup-subjects (:resource clause))
        internal-anchor ((:spice-object->internal opts)
                         selected-db public-anchor)]
    (binding [engine/*schema-cache* @schema-cache
              engine/*proof-frame* request-proof-frame]
      (validate!))
    (let [relation (:relation clause)
          result-type
          (case operation
            :lookup-resources (:resource/type query)
            :lookup-subjects (:subject/type query))
          relation-resource-type
          (case operation
            :lookup-resources result-type
            :lookup-subjects (:type public-anchor))
          relation-subject-type
          (case operation
            :lookup-resources (:type public-anchor)
            :lookup-subjects result-type)
          relation-id
          (binding [engine/*schema-cache* @schema-cache
                    engine/*proof-frame* request-proof-frame]
            (required-direct-relation-id
             adapter relation-resource-type relation relation-subject-type))
          accept?
          (fn [candidate]
            (execution/check!
             contract :authorization-probe (counters 0))
            (request-counters/add! :probes)
            (let [matches?
                  (if-not (:id internal-anchor)
                    false
                    (case operation
                      :lookup-resources
                      (backend/invoke
                       adapter :direct-match?
                       (:type internal-anchor) (:id internal-anchor)
                       relation-id (:type candidate) (:id candidate))

                      :lookup-subjects
                      (backend/invoke
                       adapter :direct-match?
                       (:type candidate) (:id candidate)
                       relation-id (:type internal-anchor)
                       (:id internal-anchor))))]
              (execution/check!
               contract :authorization-probe-complete (counters 0))
              (batch/check-aggregate-limits! limits (counters 0) nil)
              matches?))
          engine-options
          {:continuation-cache-fn
           (fn []
             (continuation-context
              adapter cursor-opts operation query))
           :candidate-filter
           {:candidate-window candidate-window
            :accept? accept?}}
          internal-page
          (binding [engine/*schema-cache* @schema-cache
                    engine/*proof-frame* request-proof-frame
                    engine/*recursive-traversal-limits*
                    (:recursive-traversal-limits opts)
                    engine/*service-admission* (:service-admission opts)
                    engine/*evaluation-mode* (:evaluation contract)
                    engine/*aggregate-work-stats* work-stats
                    execution/*contract* contract
                    subproblem/*decision-kernel* (:decision-kernel opts)]
            ((case operation
               :lookup-resources engine/lookup-resources
               :lookup-subjects engine/lookup-subjects)
             adapter internal-query engine-options))
          _ (batch/check-aggregate-limits!
             limits (counters (count (:data internal-page))) nil)]
      internal-page)))

(defn lookup-resources
  [api source
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (authorization-filters/validate-lookup! :lookup-resources query)
  (let [opts (ensure-execution-contract opts :lookup-resources query)]
    (with-selected-context
      api source opts (:consistency query)
      (fn [request-context]
        (with-page-context
          request-context opts :lookup-resources query
          (:resource/type query) (:permission query)
          (when-let [{:keys [relation subject]}
                     (:resource/relationship query)]
            {:resource-type (:resource/type query)
             :relation relation
             :subject-type (:type subject)})
          (fn [{request-context :request-context
                adapter :adapter selected-db :db cursor-opts :opts
                page-query :query}]
            (let [validate!
                  (fn []
                    (let [schema (request-schema api selected-db)]
                      (schema-errors/validate-permission-request!
                       schema
                       :lookup-resources
                       {:resource-type (:resource/type query)
                        :subject-type (:type subject)
                        :permission (:permission query)})
                      (schema-errors/validate-lookup-relationship!
                       schema :lookup-resources query)))
                  internal-subject
                  (spice-object->internal selected-db subject)]
              (if (nil? (:id internal-subject))
                (do
                  (validate!)
                  (if (cursor-request? query)
                    (stale-cursor-anchor! :lookup-resources)
                    (assoc relay/empty-page
                           :cached? false :cache-basis nil)))
                (let [internal-query
                      (-> page-query
                          (dissoc :consistency :cache? :evaluation :timeout-ms
                                  :cancellation-token :aggregate-limits
                                  :resource/relationship)
                          (assoc :subject internal-subject))]
                  (if (:resource/relationship query)
                    (or
                     (relay/lookup-visited-page
                      adapter cursor-opts :lookup-resources query)
                     (let [answer
                           (cached-engine-result
                            request-context adapter cursor-opts
                            :lookup-resources
                            (cache/lookup-page-query-identity
                             query internal-query)
                            (:resource/type internal-query)
                            (:permission internal-query)
                            #(and (map? %)
                                  (vector? (:data %))
                                  (map? (:page-info %)))
                            #(relationship-filtered-lookup-page
                              api opts request-context adapter selected-db
                              cursor-opts :lookup-resources query
                              internal-query validate!))
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
                        adapter cursor-opts :lookup-resources query page)))
                    (or
                     (relay/lookup-visited-page
                      adapter cursor-opts :lookup-resources query)
                     (let [answer
                           (cached-engine-result
                            request-context adapter cursor-opts
                            :lookup-resources
                            (cache/lookup-page-query-identity
                             query internal-query)
                            (:resource/type internal-query)
                            (:permission internal-query)
                            #(and (map? %)
                                  (vector? (:data %))
                                  (map? (:page-info %)))
                            #(do
                               (validate!)
                               (engine/lookup-resources
                                adapter
                                internal-query
                                {:continuation-cache-fn
                                 (fn []
                                   (continuation-context
                                    adapter cursor-opts
                                    :lookup-resources query))})))
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
                        adapter cursor-opts :lookup-resources query
                        page)))))))))))))

(defn count-resources
  [api source
   {:as opts :keys [spice-object->internal]}
   {:as query :keys [subject]}]
  (let [opts (ensure-execution-contract opts :count-resources query)]
    (with-selected-context
      api source opts (:consistency query)
      (fn [request-context]
        (let [selected-db (context-db request-context)
              adapter (request-context/adapter request-context)
              opts (selected-cache-options opts request-context)
              validate!
              (fn []
                (schema-errors/validate-permission-request!
                 (request-schema api selected-db)
                 :count-resources
                 {:resource-type (:resource/type query)
                  :subject-type (:type subject)
                  :permission (:permission query)}))
              internal-subject
              (spice-object->internal selected-db subject)]
          (if-not (:id internal-subject)
            (do
              (validate!)
              (assoc
               (cond-> {:count 0 :limit (or (:count-limit query) -1)}
                 (contains? query :count-limit)
                 (assoc :truncated? false))
               :cached? false :cache-basis nil))
            (let [internal-query
                  (-> query
                      (assoc :subject internal-subject)
                      (dissoc :consistency :cache? :evaluation :timeout-ms
                              :cancellation-token))
                  answer
                  (cached-engine-result
                   request-context adapter opts :count-resources
                   {:public (dissoc query :consistency :cache?
                                    :cancellation-token)
                    :internal internal-query}
                   (:resource/type internal-query)
                   (:permission internal-query)
                   #(and (map? %) (integer? (:count %)))
                   #(do
                      (validate!)
                      (engine/count-resources adapter internal-query)))]
              (with-cache-info (:value answer) answer))))))))

(defn lookup-subjects
  [api source
   {:as opts :keys [spice-object->internal]}
   query]
  (authorization-filters/validate-lookup! :lookup-subjects query)
  (when (contains? query :subject/relation)
    (throw (ex-info ":subject/relation is not supported by lookup-subjects."
                    {:eacl/error :eacl.pagination/unsupported-filter
                     :filter :subject/relation})))
  (let [opts (ensure-execution-contract opts :lookup-subjects query)]
    (with-selected-context
      api source opts (:consistency query)
      (fn [request-context]
        (with-page-context
          request-context opts :lookup-subjects query
          (:type (:resource query)) (:permission query)
          (when-let [{:keys [relation resource]}
                     (:subject/relationship query)]
            {:resource-type (:type resource)
             :relation relation
             :subject-type (:subject/type query)})
          (fn [{request-context :request-context
                adapter :adapter selected-db :db cursor-opts :opts
                page-query :query}]
            (let [validate!
                  (fn []
                    (let [schema (request-schema api selected-db)]
                      (schema-errors/validate-permission-request!
                       schema
                       :lookup-subjects
                       {:resource-type (:type (:resource query))
                        :subject-type (:subject/type query)
                        :permission (:permission query)})
                      (schema-errors/validate-lookup-relationship!
                       schema :lookup-subjects query)))
                  internal-resource
                  (spice-object->internal selected-db (:resource query))]
              (if-not (:id internal-resource)
                (do
                  (validate!)
                  (if (cursor-request? query)
                    (stale-cursor-anchor! :lookup-subjects)
                    (assoc relay/empty-page
                           :cached? false :cache-basis nil)))
                (let [internal-query
                      (-> page-query
                          (dissoc :consistency :cache? :evaluation :timeout-ms
                                  :cancellation-token :aggregate-limits
                                  :subject/relationship)
                          (assoc :resource internal-resource))]
                  (if (:subject/relationship query)
                    (or
                     (relay/lookup-visited-page
                      adapter cursor-opts :lookup-subjects query)
                     (let [answer
                           (cached-engine-result
                            request-context adapter cursor-opts
                            :lookup-subjects
                            (cache/lookup-page-query-identity
                             query internal-query)
                            (:type (:resource internal-query))
                            (:permission internal-query)
                            #(and (map? %)
                                  (vector? (:data %))
                                  (map? (:page-info %)))
                            #(relationship-filtered-lookup-page
                              api opts request-context adapter selected-db
                              cursor-opts :lookup-subjects query
                              internal-query validate!))
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
                        adapter cursor-opts :lookup-subjects query page)))
                    (or
                     (relay/lookup-visited-page
                      adapter cursor-opts :lookup-subjects query)
                     (let [answer
                           (cached-engine-result
                            request-context adapter cursor-opts
                            :lookup-subjects
                            (cache/lookup-page-query-identity
                             query internal-query)
                            (:type (:resource internal-query))
                            (:permission internal-query)
                            #(and (map? %)
                                  (vector? (:data %))
                                  (map? (:page-info %)))
                            #(do
                               (validate!)
                               (engine/lookup-subjects
                                adapter
                                internal-query
                                {:continuation-cache-fn
                                 (fn []
                                   (continuation-context
                                    adapter cursor-opts
                                    :lookup-subjects query))})))
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
                        adapter cursor-opts :lookup-subjects query
                        page)))))))))))))

(defn count-subjects
  [api source
   {:as opts :keys [spice-object->internal]}
   query]
  (let [opts (ensure-execution-contract opts :count-subjects query)]
    (with-selected-context
      api source opts (:consistency query)
      (fn [request-context]
        (let [selected-db (context-db request-context)
              adapter (request-context/adapter request-context)
              opts (selected-cache-options opts request-context)
              validate!
              (fn []
                (schema-errors/validate-permission-request!
                 (request-schema api selected-db)
                 :count-subjects
                 {:resource-type (:type (:resource query))
                  :subject-type (:subject/type query)
                  :permission (:permission query)}))
              internal-resource
              (spice-object->internal selected-db (:resource query))]
          (if-not (:id internal-resource)
            (do
              (validate!)
              (assoc
               (cond-> {:count 0 :limit (or (:count-limit query) -1)}
                 (contains? query :count-limit)
                 (assoc :truncated? false))
               :cached? false :cache-basis nil))
            (let [internal-query
                  (-> query
                      (assoc :resource internal-resource)
                      (dissoc :consistency :cache? :evaluation :timeout-ms
                              :cancellation-token))
                  answer
                  (cached-engine-result
                   request-context adapter opts :count-subjects
                   {:public (dissoc query :consistency :cache?
                                    :cancellation-token)
                    :internal internal-query}
                   (:type (:resource internal-query))
                   (:permission internal-query)
                   #(and (map? %) (integer? (:count %)))
                   #(do
                      (validate!)
                      (engine/count-subjects adapter internal-query)))]
              (with-cache-info (:value answer) answer))))))))

(defn expand-permission-tree
  [api source opts query]
  (permission-tree/validate-request! query)
  (let [opts (ensure-execution-contract
              opts :expand-permission-tree query)
        contract (:execution-contract opts)]
    (with-selected-context
      api source opts (:consistency query)
      (fn [request-context]
        (let [adapter (request-context/adapter request-context)
              db (context-db request-context)
              opts (selected-cache-options opts request-context)
              validate!
              (fn []
                (schema-errors/validate-expansion-request!
                 (request-schema api db)
                 :expand-permission-tree
                 (:type (:resource query))
                 (:permission query)))
              answer
              (cached-engine-result
               request-context adapter opts :expand-permission-tree
               (dissoc query :consistency :cache? :timeout-ms
                       :cancellation-token)
               (:type (:resource query))
               (:permission query)
               map?
               #(do
                  (validate!)
                  (permission-tree/expand
                   adapter
                   {:limits (:permission-tree-limits opts)
                    :execution-contract contract}
                   (:resource query)
                   (:permission query))))
              tree (:value answer)]
          (execution/check! contract :permission-tree-token-issuance)
          (let [token
                (permission-tree/selected-adapter-token adapter opts)]
            (execution/check! contract :permission-tree-token-issued)
            {:expanded-at token
             :tree-root tree}))))))

(defn- request-cache-enabled?
  [cache-option]
  (cache/validate-request-cache-option! cache-option)
  (not (false? cache-option)))

(defn- fixed-snapshot-provider
  [adapter]
  (snapshot-provider/borrowed-adapter-provider
   {:static-adapter adapter
    :topology {:snapshot-values :fixed-borrowed-view}
    :source-scope-fn #(backend/invoke adapter :source-scope)
    :source-lifecycle-fn #(backend/invoke adapter :source-lifecycle)
    :acquire-current! (constantly adapter)
    :acquire-authoritative! (fn [_timeout-ms] adapter)
    :acquire-at-least! (fn [_token-data _timeout-ms] adapter)
    :acquire-exact! (fn [_token-data _timeout-ms] adapter)}))

(defn- snapshot-view-closed!
  []
  (throw
   (ex-info
    "The snapshot authorization view escaped its synchronous scope."
    {:type :eacl/snapshot-view-closed
     :eacl/error :eacl/snapshot-view-closed})))

(defn- snapshot-view-thread-violation!
  []
  (throw
   (ex-info
    "The snapshot authorization view escaped its owning thread."
    {:type :eacl/snapshot-view-thread-violation
     :eacl/error :eacl/snapshot-view-thread-violation})))

(defn- snapshot-view-write!
  []
  (throw
   (ex-info
    "A composed snapshot view is read-only."
    {:type :eacl/read-only-snapshot-view
     :eacl/error :eacl/read-only-snapshot-view})))

(defn- require-snapshot-view!
  [open? owner-thread]
  (when-not @open?
    (snapshot-view-closed!))
  #?(:clj
     (when-not (identical? owner-thread (Thread/currentThread))
       (snapshot-view-thread-violation!))
     :cljs nil)
  nil)

(defn- fixed-snapshot-demand
  [demand]
  (when (some? (:consistency demand))
    (throw
     (ex-info
      "Consistency is fixed by the enclosing with-snapshot selection."
      {:type :eacl/snapshot-view-consistency-fixed
       :eacl/error :eacl/snapshot-view-consistency-fixed
       :requested (:consistency demand)})))
  (dissoc demand :consistency))

(declare ->ClientAuthorization)

(defrecord SnapshotAuthorization [delegate open? owner-thread]
  IAuthorization
  (can? [_ subject permission resource]
    (require-snapshot-view! open? owner-thread)
    (eacl/can? delegate subject permission resource))
  (can? [_ subject permission resource consistency-value]
    (require-snapshot-view! open? owner-thread)
    (fixed-snapshot-demand {:consistency consistency-value})
    (eacl/can? delegate subject permission resource))
  (can? [_ demand]
    (require-snapshot-view! open? owner-thread)
    (eacl/can? delegate (fixed-snapshot-demand demand)))

  (read-schema [_]
    (require-snapshot-view! open? owner-thread)
    (eacl/read-schema delegate))
  (write-schema! [_ _schema]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))

  (read-relationships [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/read-relationships delegate (fixed-snapshot-demand query)))
  (write-relationships! [_ _updates]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (write-relationship! [_ _operation _subject _relation _resource]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (write-relationship! [_ _demand]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (create-relationships! [_ _relationships]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (create-relationship! [_ _subject _relation _resource]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (create-relationship! [_ _relationship]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (delete-relationships! [_ _relationships]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (delete-object! [_ _object]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (delete-relationship! [_ _subject _relation _resource]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))
  (delete-relationship! [_ _relationship]
    (require-snapshot-view! open? owner-thread)
    (snapshot-view-write!))

  (lookup-resources [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/lookup-resources delegate (fixed-snapshot-demand query)))
  (count-resources [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/count-resources delegate (fixed-snapshot-demand query)))
  (lookup-subjects [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/lookup-subjects delegate (fixed-snapshot-demand query)))
  (count-subjects [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/count-subjects delegate (fixed-snapshot-demand query)))
  (expand-permission-tree [_ query]
    (require-snapshot-view! open? owner-thread)
    (eacl/expand-permission-tree delegate (fixed-snapshot-demand query)))

  IDetailedAuthorization
  (-check-permission [_ demand]
    (require-snapshot-view! open? owner-thread)
    (eacl/check-permission delegate (fixed-snapshot-demand demand)))

  IBatchedAuthorization
  (-check-permissions [_ request]
    (require-snapshot-view! open? owner-thread)
    (eacl/check-permissions delegate (fixed-snapshot-demand request))))

(defn- read-current-schema
  [api source opts]
  (let [opts (ensure-execution-contract opts :read-schema {})]
    (with-selected-context
      api source opts consistency/minimize-latency
      (fn [request-context]
        ((get-in api [:schema :read-schema])
         (context-db request-context))))))

(defrecord ClientAuthorization [conn opts api]
  IAuthorization
  (can? [_ subject permission resource]
    (can? api (:snapshot-provider opts) (assoc opts
                                               :request-operation :can?
                                               :completed-cache-request? true)
          subject permission resource consistency/minimize-latency))
  (can? [_ subject permission resource consistency]
    (can? api (:snapshot-provider opts) (assoc opts
                                               :request-operation :can?
                                               :completed-cache-request? true)
          subject permission resource consistency))
  (can? [_ {:keys [subject permission resource consistency]
            cache? :cache? :as demand}]
    (can? api (:snapshot-provider opts)
          (assoc opts
                 :request-operation :can?
                 :execution-request demand
                 :completed-cache-request?
                 (request-cache-enabled? cache?))
          subject permission resource
          consistency))

  (read-schema [_]
    (read-current-schema api (:snapshot-provider opts) opts))
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
             (if (:eacl.schema/no-op? result)
               (write-response api (:eacl.schema/db-after result) opts)
               (committed-write-response
                api (:eacl.schema/db-after result) opts)))))

  (read-relationships [_ filters]
    (read-relationships
     api
     (:snapshot-provider opts)
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
       (:snapshot-provider opts)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-resources [_ query]
    (count-resources
     api
     (:snapshot-provider opts)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))
  (lookup-subjects [_ query]
    (let [cache-enabled?
          (request-cache-enabled? (:cache? query))]
      (lookup-subjects
       api
       (:snapshot-provider opts)
       (assoc opts
              :completed-cache-request? cache-enabled?
              :continuation-cache-request? cache-enabled?)
       (dissoc query :cache?))))
  (count-subjects [_ query]
    (count-subjects
     api
     (:snapshot-provider opts)
     (assoc opts :completed-cache-request?
            (request-cache-enabled? (:cache? query)))
     (dissoc query :cache?)))

  (expand-permission-tree [_ query]
    (expand-permission-tree
     api
     (:snapshot-provider opts)
     (assoc opts :completed-cache-request? true)
     query))

  IDetailedAuthorization
  (-check-permission
    [_ {:keys [subject permission resource consistency]
        cache? :cache? :as demand}]
    (check-permission
     api
     (:snapshot-provider opts)
     (assoc opts
            :request-operation :check-permission
            :execution-request demand
            :completed-cache-request?
            (request-cache-enabled? cache?))
     subject permission resource
     (or consistency consistency/minimize-latency)))

  IBatchedAuthorization
  (-check-permissions [_ request]
    (check-permissions api (:snapshot-provider opts) opts request))

  ISnapshotAuthorization
  (-with-snapshot
    [_ consistency-value request-options f]
    (let [opts
          (ensure-execution-contract
           opts :with-snapshot request-options)]
      (with-selected-context
        api (:snapshot-provider opts) opts consistency-value
        (fn [request-context]
          (let [adapter (request-context/adapter request-context)
                open? (atom true)
                fixed-provider (fixed-snapshot-provider adapter)
                request-proof-frame
                (request-context/proof-frame request-context)
                request-schema-cache
                (delay (request-context/derived request-context))
                delegate
                (->ClientAuthorization
                 conn
                 (assoc opts
                        :snapshot-provider fixed-provider
                        ::request-context request-context
                        :request-proof-frame request-proof-frame
                        :request-schema-cache request-schema-cache)
                 api)
                view
                (->SnapshotAuthorization
                 delegate open?
                 #?(:clj (Thread/currentThread) :cljs nil))]
            (try
              (f view)
              (finally
                (reset! open? false)))))))))

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
    :aggregate-limits
    :cache-attempt
    :service-admission})

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
           aggregate-limits
           cache-attempt
           service-admission]
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
        prepared-native-source-id-key
        (:prepared-native-source-id-key api)
        native-source-id
        (if (and prepared-native-source-id-key
                 (contains? config-opts prepared-native-source-id-key))
          (get config-opts prepared-native-source-id-key)
          (if-let [native-source-id-fn (:native-source-id api)]
            (native-source-id-fn conn)
            (str (random-uuid))))
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
        base-opts
        (merge
         (select-keys config-opts
                      (:extra-client-opt-keys api))
         {:object-id->lookup-ref object-id->lookup-ref
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
         :aggregate-limits
         (batch/normalize-client-limits aggregate-limits)
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
         :spice-cursor->internal spice-cursor->internal
                          ;; The service-edge bulkhead and replay ledger
                          ;; (bounded-physical-execution): nil leaves the
                          ;; routed engine unguarded, a map installs it.
         :service-admission
         (some-> (physical/normalize-service-admission
                  service-admission)
                 physical/make-service-admission)})
        provider-constructor (:snapshot-provider api)
        _ (when-not (fn? provider-constructor)
            (throw
             (ex-info
              "Backend api must provide a snapshot-provider constructor."
              {:type :eacl/invalid-backend-api
               :eacl/error :eacl/invalid-backend-api
               :backend (:backend-id api)
               :missing :snapshot-provider})))
        provider (provider-constructor conn base-opts)
        opts (assoc base-opts :snapshot-provider provider)
        ;; Stable-engine qualification uses only long-lived provider metadata;
        ;; client construction does not retain or own a request snapshot.
        _ (physical/require-qualified-provider-topology! provider)]
    (->ClientAuthorization conn opts api)))
