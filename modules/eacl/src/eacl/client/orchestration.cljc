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
    :basis-adapter              (fn [db closed-config] v8 basis adapter)
    :basis-adapter-config-keys  closed set of conversion/configuration keys
    :source                     (fn [conn opts] long-lived basis source)
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
  (:require [clojure.set :as set]
            [com.rpl.specter :as S]
            [eacl.authorization.batch :as batch]
            [eacl.authorization.filters :as authorization-filters]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.backend.writer :as backend-writer]
            [eacl.cache :as cache]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.cache-identity :as cache-identity]
            [eacl.causal-token :as causal-token]
            [eacl.consistency :as consistency-v3]
            [eacl.continuation :as continuation]
            [eacl.cursor :as cursor]
            [eacl.core :as eacl :refer [IAuthorizationReader
                                        IAuthorizationWriter
                                        IBatchedAuthorization
                                        ISnapshotSource
                                        IAuthorizationSnapshot
                                        ISpeculativeAuthorization
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
            [eacl.relationships.storage :as relationship-storage]
            [eacl.request.context :as request-context]
            [eacl.request.counters :as request-counters]
            [eacl.schema.errors :as schema-errors]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.secure-format :as secure]
            [eacl.subproblem-cache :as subproblem]
            [eacl.spicedb.parser :as schema-parser]
            [eacl.spicedb.consistency :as consistency]))

(declare request-cache-controls
         validate-permission-root!
         speculative-with-snapshot
         speculative-with-schema-snapshot
         snapshot-tx-relationship
         attach-runtime-cache-lifecycle)

(def ^:dynamic *operator-expression-writes-enabled?*
  "Public schema-write gate for intersection or exclusion expressions.
  Union-only schemas never consult this value. Dynamic binding remains
  available for release-gate regression tests."
  true)

(def completed-cache-value-abi
  "Narrow ABI for values stored in the completed answer cache. Version 2
  retires every pre-compatibility scalar/page entry: old values miss and are
  recomputed without a durable-data migration or a value-shape exception."
  2)

(defn- operator-expression-node?
  "Recognizes an actual intersection/exclusion operation in an Instaparse
  tree. The grammar emits one-child wrapper nodes with these tags for every
  permission expression, so the tag alone is deliberately insufficient."
  [parse-tree]
  (loop [pending [parse-tree]]
    (if-let [node (peek pending)]
      (let [pending (pop pending)]
        (if (vector? node)
          (if (and (contains? #{:intersect-expr :exclusion-expr}
                              (first node))
                   (< 2 (count node)))
            true
            (recur (into pending (filter vector?) (next node))))
          (recur pending)))
      false)))

(defn- require-operator-expression-writes-enabled!
  [schema-string]
  (when (and (not *operator-expression-writes-enabled?*)
             (operator-expression-node?
              (schema-parser/parse-schema schema-string)))
    (throw
     (ex-info
      "Public operator-expression schema writes are disabled by the pre-release gate."
      {:type :eacl.schema/operator-expression-writes-disabled
       :eacl/error :eacl.schema/operator-expression-writes-disabled}))))

(defn- ensure-execution-contract
  [opts operation request]
  (let [opts
        (cond-> opts
          (nil? (:cache-lifecycle opts))
          (assoc :cache-lifecycle
                 (cache/capture-cache-lifecycle
                  (:basis-cache-store opts))))]
    (assoc opts :execution-contract
           (if-let [contract (:execution-contract opts)]
             (execution/refine contract opts operation request)
             (execution/normalize opts operation request)))))

(defn- refresh-runtime-lifecycle-options
  [opts]
  (if-let [state (:runtime-lifecycle-state opts)]
    (attach-runtime-cache-lifecycle opts @state)
    opts))

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
  [adapter scope lifecycle]
  (let [scope (select-keys scope [:source-id :branch])
        revision (consistency-v3/native-revision adapter)
        snapshot-id (backend/invoke adapter :snapshot-id)]
    {:backend (backend/backend-id adapter)
     :source-id (:source-id scope)
     :branch (:branch scope)
     :source-lifecycle lifecycle
     :basis-kind (backend/basis-kind adapter)
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
      (source/release! selected)
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
  [_api source opts consistency-value]
  (loop [opts (refresh-runtime-lifecycle-options opts)]
    (let [contract (:execution-contract opts)
          _ (execution/check! contract :consistency-selection)
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
          (consistency-v3/select
           source consistency-value selection-options)
          adapter (:adapter selection)
          selected (:selected-snapshot selection)
          outcome
          (try
            (execution/check! contract :consistency-selected)
            (let [semantic-identity (source/semantic-identity selected)
                  captured-lifecycle (:runtime-cache-lifecycle opts)
                  current-lifecycle
                  (some-> (:runtime-lifecycle-state opts) deref)
                  source-replaced?
                  (and current-lifecycle
                       (not
                        (identical?
                         (:source-incarnation captured-lifecycle)
                         (:source-incarnation current-lifecycle))))]
              (if (or source-replaced?
                      (not= (:source-lifecycle opts)
                            (:source-lifecycle semantic-identity)))
                {:retry? true}
                (let [;; Cache class follows the immutable value, not the
                      ;; route used to select it. An ordinary value loaded by
                      ;; exact locator may use proof-backed lifting; an as-of
                      ;; value is exact-basis only.
                      historical-basis?
                      (= :as-of (:basis-kind semantic-identity))
                      completed-cache?
                      (:completed-cache-request? opts)]
                  {:adapter adapter
                   :db (:db (backend/state adapter))
                   :selection selection
                   :selected-snapshot selected
                   :semantic-identity semantic-identity
                   :execution-constraints
                   (source/execution-constraints source)
                   :historical-basis? historical-basis?
                   :maximum-snapshot-retention-ms
                   (:maximum-snapshot-retention-ms opts)
                   :completed-cache? completed-cache?
                   :runtime-options opts})))
            (catch #?(:clj Throwable :cljs :default) error
              (release-selected-after-error! selected error)))]
      (if (:retry? outcome)
        (do
          ;; Expiry won the interval between the request's cache capture and
          ;; the source's semantic-identity capture. Release this candidate
          ;; and retry with one newly captured outer lifecycle; never pair an
          ;; L0 cache child with an L1 source identity.
          (source/release! selected)
          (recur (refresh-runtime-lifecycle-options opts)))
        outcome))))

(defn- selected-context
  [api source opts consistency-value]
  (let [{:keys [adapter selected-snapshot semantic-identity selection
                historical-basis? completed-cache? runtime-options]}
        (select-request-basis api source opts consistency-value)
        runtime
        (assoc runtime-options
               ::selection selection
               ::historical-basis? historical-basis?
               ::completed-cache? completed-cache?)]
    (request-context/make-context
     {:runtime runtime
      :adapter adapter
      :selected-snapshot selected-snapshot
      :basis-identity semantic-identity
      :contract (:execution-contract runtime)
      :derived-registry (:derived-schema-caches runtime)
      :counter-ledger (:request-counter-ledger runtime)
      :proof-diagnostic-fn
      (when (:completed-cache-request? runtime)
        (fn [diagnostic]
          (cache/record-proof-diagnostic!
           (:basis-cache-store runtime)
           diagnostic)))})))

(defn- with-selected-basis
  [api source opts consistency-value f]
  (let [basis (select-request-basis api source opts consistency-value)]
    (try
      (f basis)
      (finally
        (when-let [selected (:selected-snapshot basis)]
          (source/release! selected))))))

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
         (let [retained-basis (::retained-basis opts)
               context
               (if retained-basis
                 (let [selected (:selected-snapshot retained-basis)
                       _ (when selected
                           (source/assert-open! selected))
                       adapter (:adapter retained-basis)
                       identity (:identity retained-basis)
                       runtime
                       (assoc opts
                              ::selection (:selection retained-basis)
                              ::historical-basis?
                              (:historical-basis? retained-basis)
                              ::completed-cache?
                              (:completed-cache-request? opts))]
                   ;; A per-read context borrows the retained basis. Ownership
                   ;; remains with the public Snapshot until eacl/release!.
                   (request-context/make-context
                    {:runtime runtime
                     :adapter adapter
                     :selected-snapshot nil
                     :basis-identity identity
                     :contract (:execution-contract opts)
                     :derived-registry (:derived-schema-caches opts)
                     :counter-ledger ledger
                     :proof-diagnostic-fn
                     (when (:completed-cache-request? opts)
                       (fn [diagnostic]
                         (cache/record-proof-diagnostic!
                          (:basis-cache-store opts)
                          diagnostic)))}))
                 (selected-context api source opts consistency-value))]
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
  (let [context-state (request-context/active-state context)
        runtime (:runtime context-state)
        historical-basis? (::historical-basis? runtime)]
    (assoc runtime
           ::request-context-state context-state
           ;; Nested operations (notably one scalar decision inside a batch)
           ;; refine the selected request's contract without selecting a new
           ;; basis. Preserve that operation-local semantic demand instead of
           ;; silently restoring the outer batch demand from context runtime.
           :execution-contract (:execution-contract opts)
           :completed-cache?
           (:completed-cache-request? opts)
           :historical-basis? historical-basis?
           :snapshot-semantic-identity
           (:basis-identity context-state)
           :request-lineage (:lineage context-state)
           :request-proof-frame-delay
           (:proof-frame-delay context-state)
           :request-schema-cache (:derived-delay context-state))))

(defn- request-proof-frame
  [opts]
  (or (:request-proof-frame opts)
      (some-> (:request-proof-frame-delay opts) force)))

(defn- call-with-request-schema-cache
  "Runs selected-snapshot schema work against the request's proof-keyed
  derived generation.  This is schema decoding reuse, not authorization
  answer caching, and therefore remains active when `:cache? false` bypasses
  all result and subproblem caches."
  [opts f]
  (if-let [schema-cache (:request-schema-cache opts)]
    (binding [engine/*schema-cache* @schema-cache
              expression-persistence/*structural-cache*
              (:expression-metrics @schema-cache)
              expression-persistence/*expression-limits*
              (:expression-limits opts)
              engine/*proof-frame* (request-proof-frame opts)]
      (f))
    (f)))

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

(defn- detached-schema-state
  "Builds basis-bound lazy schema/proof state for an adapter that is not the
  request context's original adapter (notably exact cursor reconstruction)."
  [adapter opts basis-identity]
  (let [schema-generation
        (delay (backend/invoke adapter :schema-generation))
        request-proof-frame
        (proof-frame/request-frame
         adapter
         {:basis-identity basis-identity
          :schema-generation-fn #(force schema-generation)
          :diagnostic-fn
          (fn [diagnostic]
            (cache/record-proof-diagnostic!
             (:basis-cache-store opts)
             diagnostic))})]
    {:proof-frame request-proof-frame
     :schema-cache
     (delay
       (binding [engine/*proof-frame* request-proof-frame]
         (engine/schema-cache-for!
          (:derived-schema-caches opts)
          adapter basis-identity
          (force schema-generation))))}))

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

  The request context's proof frame serves engine evaluation, answer reuse,
  checkpoint lookup, and cursor continuation, so one canonical dependency
  closure is read at most once from the selected adapter. Derived schema state
  and dependency discovery remain lazy until a cursor is minted or resumed."
  [request-context adapter opts selection resource-type permission
   relationship-dependency]
  (let [contract (:execution-contract opts)
        basis-identity
        (or (:snapshot-semantic-identity opts)
            (request-context/basis-identity request-context))
        candidate-proof-frame (:request-proof-frame opts)
        candidate-schema-cache (:request-schema-cache opts)
        reuse-request-context?
        (identical? adapter (request-context/adapter request-context))
        complete-candidate-state?
        (and candidate-schema-cache
             candidate-proof-frame
             (identical? adapter (:adapter candidate-proof-frame)))
        detached-state
        (when (and (not reuse-request-context?)
                   (not complete-candidate-state?))
          (detached-schema-state adapter opts basis-identity))
        request-proof-frame
        (if reuse-request-context?
          (request-context/proof-frame request-context)
          (if complete-candidate-state?
            candidate-proof-frame
            (:proof-frame detached-state)))
        schema-cache
        (if reuse-request-context?
          (delay (request-context/derived request-context))
          (if complete-candidate-state?
            candidate-schema-cache
            (:schema-cache detached-state)))
        dependency-relation-ids
        (fn [candidate-adapter candidate-basis-identity]
          (let [same-basis?
                (and (identical? candidate-adapter adapter)
                     (= candidate-basis-identity basis-identity))
                candidate-state
                (when-not same-basis?
                  (detached-schema-state
                   candidate-adapter opts candidate-basis-identity))
                candidate-schema-cache
                (if same-basis?
                  schema-cache
                  (:schema-cache candidate-state))]
            (try
              (binding [engine/*schema-cache* @candidate-schema-cache
                        expression-persistence/*expression-limits*
                        (:expression-limits opts)]
                (let [permission-ids
                      (when (and resource-type permission)
                        (engine/permission-relationship-eids
                         candidate-adapter resource-type permission))
                      relationship-ids
                      (when relationship-dependency
                        (relationship-filter-relation-eids
                         candidate-adapter relationship-dependency))]
                  ;; A broad relationship scan cannot name a bounded relation
                  ;; dependency set through the current SPI. It therefore
                  ;; falls back to exact immutable basis identity.
                  (when (or (nil? relationship-dependency)
                            (some? relationship-ids))
                    (->> (concat permission-ids relationship-ids)
                         distinct
                         sort
                         vec))))
              (catch #?(:clj Exception :cljs :default) _
                nil))))]
    (assoc opts
           :snapshot-semantic-identity
           basis-identity
           :request-lineage
           (request-context/lineage-for-basis basis-identity)
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
           :cursor-dependency-relation-ids-fn
           ;; Cursor reuse uses the same compiled dependency closure as the
           ;; request proof frame. If proof is unavailable, the cursor remains
           ;; bound to exact immutable snapshot identity. Datomic/Datahike may
           ;; select that exact snapshot on resume; current-only DataScript
           ;; fails closed after a relevant basis change.
           (when (and (:proof-equivalent-cursors? opts)
                      (or (and resource-type permission)
                          relationship-dependency))
             dependency-relation-ids))))

(defn- page-context
  [request-context opts operation query resource-type permission
   relationship-dependency]
  (let [opts (selected-cache-options opts request-context)
        ;; Low-level raw-DB entry points may receive a client's opts map. They
        ;; must remain bound to that caller-owned DB and must not reach through
        ;; the client's live source during cursor recovery.
        selection (context-selection request-context)
        opts (if (:selected-snapshot selection)
               opts
               (dissoc opts :source))
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
        page-selected-snapshot (:selected-snapshot prepared)
        continuation-context (:continuation-context prepared)]
    (try
      (let [page-semantic-identity
            (if page-selected-snapshot
              (source/semantic-identity page-selected-snapshot)
              (if (identical? page-adapter adapter)
                (request-context/basis-identity request-context)
                (throw
                 (ex-info
                  "Cursor recovery changed basis without transferring ownership."
                  {:type :eacl/backend-contract-violation
                   :eacl/error :eacl/backend-contract-violation
                   :operation :cursor-recovery}))))
            historical-basis?
            (= :as-of (:basis-kind page-semantic-identity))
            accepted-cursor-frame
            (when (and page-selected-snapshot
                       (proof-frame/descriptor?
                        (:frame continuation-context)))
              (:frame continuation-context))
            page-opts
            (assoc
             (cursor-options
              request-context page-adapter
              (assoc opts
                     :snapshot-semantic-identity page-semantic-identity)
              selection
              resource-type permission relationship-dependency)
             :snapshot-semantic-identity page-semantic-identity
             :cursor-dependency-context continuation-context
             :accepted-cursor-frame accepted-cursor-frame
             :historical-basis? historical-basis?
             :completed-cache?
             (:completed-cache-request? opts))]
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
          (source/release! selected))))))

(defn- cached-engine-result
  [request-context adapter opts operation query resource-type permission
   compute]
  (let [contract (:execution-contract opts)
        managed-reuse?
        (and (:managed-cache-enabled? opts)
             (not (:historical-basis? opts)))
        context-state (or (::request-context-state opts)
                          (request-context/active-state request-context))
        context-adapter (:adapter context-state)
        context-adapter? (identical? adapter context-adapter)
        candidate-proof-frame (request-proof-frame opts)
        candidate-schema-cache (:request-schema-cache opts)
        complete-candidate-state?
        (and candidate-schema-cache
             candidate-proof-frame
             (identical? adapter (:adapter candidate-proof-frame)))
        detached-state
        (when (and (not context-adapter?)
                   (not complete-candidate-state?))
          (detached-schema-state
           adapter opts (:snapshot-semantic-identity opts)))
        request-proof-frame-delay
        (delay
          (if context-adapter?
            (force (:proof-frame-delay context-state))
            (if complete-candidate-state?
              candidate-proof-frame
              (:proof-frame detached-state))))
        schema-cache
        (if context-adapter?
          (:derived-delay context-state)
          (if complete-candidate-state?
            candidate-schema-cache
            (:schema-cache detached-state)))
        request-relation-ids
        (delay
          (if-let [resolve-dependency-ids
                   (:cursor-dependency-relation-ids-fn opts)]
            (resolve-dependency-ids
             adapter (:snapshot-semantic-identity opts))
            (some-> (:cursor-dependency-relation-ids opts) force)))
        request-frame-descriptor
        (delay
          (or (:accepted-cursor-frame opts)
              (when-let [relation-ids @request-relation-ids]
                (proof-frame/descriptor
                 (proof-frame/resolve!
                  @request-proof-frame-delay relation-ids)))))
        evaluate
        #(do
           (execution/check! contract :schema-plan)
           (let [value
                 (binding [engine/*schema-cache* @schema-cache
                           expression-persistence/*structural-cache*
                           (:expression-metrics @schema-cache)
                           expression-persistence/*expression-limits*
                           (:expression-limits opts)
                           engine/*proof-frame* @request-proof-frame-delay
                           engine/*request-lineage*
                           (:request-lineage opts)
                           engine/*request-frame*
                           request-frame-descriptor
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
        (and (:basis-cache-store opts)
             (:completed-cache? opts)
             (or (nil? contract)
                 (execution/cache-stage-available? contract)))]
    (if-not cacheable?
      (do
        (cache/record-current-bypass!
         (:basis-cache-store opts))
        {:value
         (binding [subproblem/*store* nil
                   subproblem/*exact-denotation-key-fn* nil
                   subproblem/*populate?* false]
           (evaluate))
         :cached? false
         :cache-tier nil
         :cache-basis nil})
      (let [_ (request-counters/add! :cache-key-builds)
            dependencies
            (delay
              (binding [engine/*schema-cache* @schema-cache
                        expression-persistence/*expression-limits*
                        (:expression-limits opts)
                        engine/*proof-frame* @request-proof-frame-delay]
                (let [permission-deps
                      (when (and resource-type permission)
                        (permission-dependencies
                         adapter resource-type permission))
                      page-deps
                      @request-relation-ids
                      relation-ids
                      (->> (concat (:relation-ids permission-deps)
                                   page-deps)
                           distinct
                           sort
                           vec)
                      speculative (:speculative-context opts)
                      relation-coordinate-fn
                      (:relation-coordinate-fn speculative)
                      selected-db (:db (backend/state adapter))
                      relationship-components
                      (when speculative
                        (when (fn? relation-coordinate-fn)
                          (into #{}
                                (keep #(relation-coordinate-fn selected-db %))
                                relation-ids)))
                      permission-components
                      (when speculative
                        (into #{}
                              (map (fn [[definition permission-name]]
                                     [:permission definition permission-name]))
                              (get-in permission-deps
                                      [:schema-scope :permission-nodes])))]
                  {:relation-ids relation-ids
                   :schema-scope
                   (assoc (or (:schema-scope permission-deps) {})
                          :relation-ids relation-ids)
                   :relationship-components relationship-components
                   :schema-components
                   (when speculative
                     (set/union relationship-components
                                permission-components))})))
            complete-proof
            (delay
              (proof-frame/resolve!
               @request-proof-frame-delay
               (:relation-ids @dependencies)))
            semantic-snapshot (:snapshot-semantic-identity opts)
            exact-basis-key (cache/exact-basis-key adapter semantic-snapshot)
            speculative (:speculative-context opts)
            speculative-effects (:effects speculative)
            speculative-disjoint?
            (delay
              (and speculative
                   (:complete? speculative-effects)
                   (set? (:relationship-components @dependencies))
                   (set? (:schema-components @dependencies))
                   (empty?
                    (set/intersection
                     (:relationship-components @dependencies)
                     (:relationships speculative-effects)))
                   (empty?
                    (set/intersection
                     (:schema-components @dependencies)
                     (:schema-components speculative-effects)))
                   (empty? (:other speculative-effects))))
            committed-proof-frame
            (delay
              (when @speculative-disjoint?
                (proof-frame/request-frame
                 adapter
                 {:basis-identity semantic-snapshot
                  :schema-generation-fn
                  (constantly (:root-schema-generation speculative))
                  :diagnostic-fn
                  (fn [diagnostic]
                    (cache/record-proof-diagnostic!
                     (:basis-cache-store opts) diagnostic))})))
            committed-proof
            (delay
              (when-let [frame @committed-proof-frame]
                (proof-frame/resolve!
                 frame (:relation-ids @dependencies))))
            semantic-key
            {:operation operation
             :query query
             :evaluation (:evaluation contract)
             :demand (:demand contract)
             ;; Aggregate limits can affect returned page boundaries (for
             ;; example candidate-window exhaustion), not merely work cost.
             ;; Cross-client portable restore must therefore not alias clients
             ;; whose normalized defaults differ.
             :aggregate-limits (:aggregate-limits contract)
             :engine-version engine/engine-version
             ;; The public order ABI is part of an answer's identity: a page
             ;; cached under one order must never be served under another.
             :order-abi engine/stable-order-abi
             :compiler-plan-compatibility
             engine/compiler-plan-compatibility
             :cache-value-abi completed-cache-value-abi
             :adapter-fingerprint (:adapter-fingerprint opts)
             :identity-contract (:identity-contract opts)
             :recursive-traversal-limits
             (:recursive-traversal-limits opts)
             :expression-limits
             (:expression-limits opts)
             :permission-tree-limits
             (:permission-tree-limits opts)}]
        (execution/check! contract :cache-lookup)
        (let [answer
              (if speculative
                  (cache/resolve-managed-read-only!
                   (:basis-cache-store opts)
                   {:cache-lifecycle (:cache-lifecycle opts)
                    :snapshot-order (:revision semantic-snapshot)
                    :managed-source
                    (cache/managed-source-identity
                     (:request-lineage opts)
                     (:adapter-fingerprint opts)
                     (:identity-contract opts))
                    :managed-key-fn
                    (when (and @speculative-disjoint?
                               managed-reuse?
                               resource-type permission)
                      #(proof-frame/descriptor @committed-proof))}
                   semantic-key evaluate)
                  (cache/resolve-basis!
                   (:basis-cache-store opts)
                   {:cache-lifecycle (:cache-lifecycle opts)
                    :exact-basis-key exact-basis-key
                    :populate-cache?
                    (:populate-cache-request? opts true)
                    :managed-key-fn
                    (when (and managed-reuse?
                               resource-type permission)
                      #(proof-frame/descriptor @complete-proof))}
                   semantic-key evaluate))]
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
    :consistency :cache? :populate-cache?
    :timeout-ms :cancellation-token]))

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
     {:request-proof-frame (request-proof-frame opts)
      :request-lineage (:request-lineage opts)
      :populate-cache? (:populate-cache-request? opts true)})))

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
                              expression-persistence/*expression-limits*
                              (:expression-limits opts)
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
      expression-persistence/*expression-limits*
      (:expression-limits opts)
      engine/*proof-frame* request-proof-frame]
      (validate!))
    (let [internal-page
          (binding [engine/*aggregate-work-stats* work-stats
                    relationship-filters/*validated-request?* true]
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
  (when-not relationship-filters/*validated-request?*
    (relationship-filters/validate! filters))
  (when-not authorization-filters/*validated-request?*
    (authorization-filters/validate-scan-authorization! filters))
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
                          :populate-cache?
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
                      (dissoc :consistency :cache? :populate-cache?
                              :evaluation :timeout-ms
                              :cancellation-token :aggregate-limits
                              :authorization)
                      (cond->
                       subject-id (assoc :subject/id subject-eid)
                       resource-id (assoc :resource/id resource-eid)))]
              (if (or (and subject-id (nil? subject-eid))
                      (and resource-id (nil? resource-eid)))
                (do
                  (call-with-request-schema-cache cursor-opts validate!)
                  (if (cursor-request? filters)
                    (stale-cursor-anchor! :read-relationships)
                    (cond->
                     (assoc relay/empty-page
                            :cached? false :cache-basis nil)
                      (:authorization filters)
                      (assoc-in [:page-info :bounded?] false))))
                (if (:authorization filters)
                  (let [answer
                        (cached-engine-result
                         request-context adapter cursor-opts
                         :read-relationships
                         (cache/lookup-page-query-identity
                          filters internal-query)
                         authorization-resource-type
                         authorization-permission
                         #(authorization-scan-page
                           api opts request-context adapter page-db
                           cursor-opts filters internal-query validate!))]
                    (with-cache-info
                      (relay/externalize-relationship-page
                       adapter cursor-opts :read-relationships filters
                       (:value answer))
                      answer))
                  (let [answer
                        (cached-engine-result
                         request-context adapter cursor-opts
                         :read-relationships
                         (cache/lookup-page-query-identity
                          filters internal-query)
                         nil nil
                         #(do
                            (validate!)
                            (binding
                             [relationship-filters/*validated-request?* true]
                              ((get-in api [:impl :read-relationships])
                               page-db internal-query
                               (:decision-kernel cursor-opts)))))]
                    (with-cache-info
                      (relay/externalize-relationship-page
                       adapter cursor-opts :read-relationships filters
                       (:value answer))
                      answer)))))))))))

(defn spice-relationship->internal
  [db {:keys [spice-object->internal object-id->lookup-ref]}
   {:keys [subject relation resource]}]
  (let [internalize
        (fn [object]
          (assoc (spice-object->internal db object)
                 :eacl.relationship/identity-guard
                 (object-id->lookup-ref (:id object))
                 ;; Resolution is intentionally performed against the selected
                 ;; basis, so an absent endpoint becomes nil in the internal
                 ;; object. Retain the bounded public identity solely for a
                 ;; precise, backend-independent typed error; it never enters
                 ;; transaction data or a cache key.
                 :eacl.relationship/public-object
                 (select-keys object [:type :id])))]
    {:subject (internalize subject)
     :relation relation
     :resource (internalize resource)}))

(defn- response-token-for-revision
  [api native-revision opts]
  (let [basis-source (:source opts)]
    (if (source/source? basis-source)
      (causal-token/issue
       (:format-options opts)
       (merge
        {:backend (source/backend-id basis-source)
         :source-lifecycle
         (source/source-lifecycle basis-source)}
        (source/source-scope basis-source)
        native-revision))
      nil)))

(defn- response-token
  [api db opts]
  (response-token-for-revision
   api ((:db-native-revision api) db) opts))

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

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (:data relationships)
    relationships))

(defn- validate-permission-root!
  [api request-context selected-db opts subject permission resource]
  (let [build
        #(do
           (schema-errors/validate-permission-request!
            (request-schema api selected-db)
            (or (:request-operation opts) :can?)
            {:resource-type (:type resource)
             :subject-type (:type subject)
             :permission permission})
           true)]
    (if-let [context-state (::request-context-state opts)]
      (request-context/memoized-active-state!
       context-state :prepared-roots
       [(:type resource) permission (:type subject)] build)
      (request-context/memoized!
       request-context :prepared-roots
       [(:type resource) permission (:type subject)] build))))

(defn- check-permission-in-context
  [api {:keys [spice-object->internal] :as opts} request-context
   subject permission resource]
  (let [opts (selected-cache-options opts request-context)
        context-state (::request-context-state opts)
        adapter (:adapter context-state)
        selected-db (:db (backend/state adapter))
        validate!
        #(validate-permission-root!
          api request-context selected-db opts subject permission resource)
        internal-subject
        (spice-object->internal selected-db subject)
        internal-resource
        (spice-object->internal selected-db resource)]
    (if-not (and (:id internal-subject) (:id internal-resource))
      (do
        (call-with-request-schema-cache opts validate!)
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
        cache-controls (request-cache-controls request)
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
                       (:cache-enabled? cache-controls)
                       :populate-cache-request?
                       (:populate-cache? cache-controls))
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
              expression-persistence/*expression-limits*
              (:expression-limits opts)
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
                    expression-persistence/*expression-limits*
                    (:expression-limits opts)
                    engine/*proof-frame* request-proof-frame]
            (required-direct-relation-id
             adapter relation-resource-type relation relation-subject-type))
          direct-match! (backend/direct-match-invoker adapter)
          accept?
          (fn [candidate]
            (execution/check!
             contract :authorization-probe (counters 0))
            (request-counters/add-probes!)
            (let [matches?
                  (if-not (:id internal-anchor)
                    false
                    (case operation
                      :lookup-resources
                      (direct-match!
                       (:type internal-anchor) (:id internal-anchor)
                       relation-id (:type candidate) (:id candidate))

                      :lookup-subjects
                      (direct-match!
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
                    expression-persistence/*expression-limits*
                    (:expression-limits opts)
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
  (when-not authorization-filters/*validated-request?*
    (authorization-filters/validate-lookup! :lookup-resources query))
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
                  (call-with-request-schema-cache cursor-opts validate!)
                  (if (cursor-request? query)
                    (stale-cursor-anchor! :lookup-resources)
                    (assoc relay/empty-page
                           :cached? false :cache-basis nil)))
                (let [internal-query
                      (-> page-query
                          (dissoc :consistency :cache? :populate-cache?
                                  :evaluation :timeout-ms
                                  :cancellation-token :aggregate-limits
                                  :resource/relationship)
                          (assoc :subject internal-subject))]
                  (if (:resource/relationship query)
                    (let [answer
                          (cached-engine-result
                           request-context adapter cursor-opts
                           :lookup-resources
                           (cache/lookup-page-query-identity
                            query internal-query)
                           (:resource/type internal-query)
                           (:permission internal-query)
                           #(relationship-filtered-lookup-page
                             api opts request-context adapter selected-db
                             cursor-opts :lookup-resources query
                             internal-query validate!))]
                      (with-cache-info
                        (binding [subproblem/*decision-kernel*
                                  (:decision-kernel cursor-opts)]
                          (relay/externalize-page
                           adapter cursor-opts :lookup-resources query
                           (:value answer)))
                        answer))
                    (let [answer
                          (cached-engine-result
                           request-context adapter cursor-opts
                           :lookup-resources
                           (cache/lookup-page-query-identity
                            query internal-query)
                           (:resource/type internal-query)
                           (:permission internal-query)
                           #(do
                              (validate!)
                              (engine/lookup-resources
                               adapter
                               internal-query
                               {:continuation-cache-fn
                                (fn []
                                  (continuation-context
                                   adapter cursor-opts
                                   :lookup-resources query))})))]
                      (with-cache-info
                        (binding [subproblem/*decision-kernel*
                                  (:decision-kernel cursor-opts)]
                          (relay/externalize-page
                           adapter cursor-opts :lookup-resources query
                           (:value answer)))
                        answer))))))))))))

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
              (call-with-request-schema-cache opts validate!)
              (assoc
               (cond-> {:count 0 :limit (or (:count-limit query) -1)}
                 (contains? query :count-limit)
                 (assoc :truncated? false))
               :cached? false :cache-basis nil))
            (let [internal-query
                  (-> query
                      (assoc :subject internal-subject)
                      (dissoc :consistency :cache? :populate-cache?
                              :evaluation :timeout-ms
                              :cancellation-token))
                  answer
                  (cached-engine-result
                   request-context adapter opts :count-resources
                   {:public (-> (cache-identity/successful-result-query query)
                                (dissoc :consistency))
                   :internal internal-query}
                   (:resource/type internal-query)
                   (:permission internal-query)
                   #(do
                      (validate!)
                      (engine/count-resources adapter internal-query)))]
              (with-cache-info (:value answer) answer))))))))

(defn lookup-subjects
  [api source
   {:as opts :keys [spice-object->internal]}
   query]
  (when-not authorization-filters/*validated-request?*
    (authorization-filters/validate-lookup! :lookup-subjects query))
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
                  (call-with-request-schema-cache cursor-opts validate!)
                  (if (cursor-request? query)
                    (stale-cursor-anchor! :lookup-subjects)
                    (assoc relay/empty-page
                           :cached? false :cache-basis nil)))
                (let [internal-query
                      (-> page-query
                          (dissoc :consistency :cache? :populate-cache?
                                  :evaluation :timeout-ms
                                  :cancellation-token :aggregate-limits
                                  :subject/relationship)
                          (assoc :resource internal-resource))]
                  (if (:subject/relationship query)
                    (let [answer
                          (cached-engine-result
                           request-context adapter cursor-opts
                           :lookup-subjects
                           (cache/lookup-page-query-identity
                            query internal-query)
                           (:type (:resource internal-query))
                           (:permission internal-query)
                           #(relationship-filtered-lookup-page
                             api opts request-context adapter selected-db
                             cursor-opts :lookup-subjects query
                             internal-query validate!))]
                      (with-cache-info
                        (binding [subproblem/*decision-kernel*
                                  (:decision-kernel cursor-opts)]
                          (relay/externalize-page
                           adapter cursor-opts :lookup-subjects query
                           (:value answer)))
                        answer))
                    (let [answer
                          (cached-engine-result
                           request-context adapter cursor-opts
                           :lookup-subjects
                           (cache/lookup-page-query-identity
                            query internal-query)
                           (:type (:resource internal-query))
                           (:permission internal-query)
                           #(do
                              (validate!)
                              (engine/lookup-subjects
                               adapter
                               internal-query
                               {:continuation-cache-fn
                                (fn []
                                  (continuation-context
                                   adapter cursor-opts
                                   :lookup-subjects query))})))]
                      (with-cache-info
                        (binding [subproblem/*decision-kernel*
                                  (:decision-kernel cursor-opts)]
                          (relay/externalize-page
                           adapter cursor-opts :lookup-subjects query
                           (:value answer)))
                        answer))))))))))))

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
              (call-with-request-schema-cache opts validate!)
              (assoc
               (cond-> {:count 0 :limit (or (:count-limit query) -1)}
                 (contains? query :count-limit)
                 (assoc :truncated? false))
               :cached? false :cache-basis nil))
            (let [internal-query
                  (-> query
                      (assoc :resource internal-resource)
                      (dissoc :consistency :cache? :populate-cache?
                              :evaluation :timeout-ms
                              :cancellation-token))
                  answer
                  (cached-engine-result
                   request-context adapter opts :count-subjects
                   {:public (-> (cache-identity/successful-result-query query)
                                (dissoc :consistency))
                   :internal internal-query}
                   (:type (:resource internal-query))
                   (:permission internal-query)
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
               (dissoc query :consistency :cache? :populate-cache? :timeout-ms
                       :cancellation-token)
               (:type (:resource query))
               (:permission query)
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
                (permission-tree/selected-basis-token opts)]
            (execution/check! contract :permission-tree-token-issued)
            {:expanded-at token
             :tree-root tree}))))))

(defn- request-cache-controls
  [request]
  (let [cache-option
        (cache/validate-request-cache-option! (:cache? request))
        populate-option
        (cache/validate-request-populate-option!
         (:populate-cache? request))]
    {:cache-enabled? (not (false? cache-option))
     :populate-cache? (not (false? populate-option))}))

#?(:clj (ns-unmap *ns* 'Runtime))
(defrecord Runtime [])
(defrecord RuntimeCacheLifecycle
           [token source-incarnation source-lifecycle basis-cache-store
            continuation-cache-store cursor-codec-cache
            cursor-construction-cache derived-schema-caches
            content-revision])
(defrecord Basis [adapter selected-snapshot identity selection basis-kind
                  historical-basis? execution-constraints release-state
                  owner-thread acquired-at-ms maximum-retention-ms
                  source-incarnation speculative])

(def ^:private runtime-lifecycle-option-keys
  #{:source-lifecycle :basis-cache-store :continuation-cache-store
    :cursor-codec-cache :cursor-construction-cache :derived-schema-caches})

(defn- runtime-lifecycle-options
  [lifecycle]
  (select-keys lifecycle runtime-lifecycle-option-keys))

(defn- cache-entry-capacity
  [cache-option]
  (if (and (map? cache-option)
           (integer? (:max-entries cache-option)))
    (:max-entries cache-option)
    1024))

(defn- runtime-content-change-fn
  [runtime-lifecycle-state lifecycle-token]
  (fn []
    (loop []
      (let [current @runtime-lifecycle-state]
        (when (identical? lifecycle-token (:token current))
          (let [next (update current :content-revision inc)]
            (when-not (compare-and-set! runtime-lifecycle-state current next)
              (recur))))))))

(defn- fresh-authorization-children
  [{:keys [cache-option proof-contract-reporter runtime-lifecycle-state]}
   lifecycle-token]
  (let [basis-cache-store
        (cache/basis-cache-for-option
         cache-option
         {:proof-contract-reporter proof-contract-reporter
          :content-change-fn
          (runtime-content-change-fn
           runtime-lifecycle-state lifecycle-token)})]
    {:basis-cache-store basis-cache-store
     :continuation-cache-store
     (when basis-cache-store
       (continuation/make-store
        {:max-entries (cache-entry-capacity cache-option)
         :telemetry? (:telemetry-enabled? basis-cache-store)}))}))

(defn- fresh-runtime-cache-lifecycle
  [{:keys [cache-option derived-schema-store-factory]
    :as config}
   source-lifecycle content-revision]
  (let [token (atom nil)
        {:keys [basis-cache-store continuation-cache-store]}
        (fresh-authorization-children config token)
        capacity (cache-entry-capacity cache-option)
        cursor-codec-cache
        (when basis-cache-store
          (cursor/codec-cache {:max-entries capacity}))
        cursor-construction-cache
        (or cursor-codec-cache
            (cursor/codec-cache {:max-entries capacity}))]
    (->RuntimeCacheLifecycle
     token
     (atom nil)
     source-lifecycle
     basis-cache-store
     continuation-cache-store
     cursor-codec-cache
     cursor-construction-cache
     (derived-schema-store-factory)
     content-revision)))

(defn- narrow-runtime-cache-lifecycle
  [config current content-revision]
  (let [token (atom nil)
        {:keys [basis-cache-store continuation-cache-store]}
        (fresh-authorization-children config token)
        previous-basis-store (:basis-cache-store current)
        basis-cache-store
        (if (and basis-cache-store previous-basis-store)
          ;; A proof contract violation is sticky for the source lifecycle,
          ;; not merely for one answer-store generation. Share the atoms rather
          ;; than copying their values so an old in-flight request that reports
          ;; after this rotation still disables the installed store.
          (assoc basis-cache-store
                 :managed-lifting-disabled?
                 (:managed-lifting-disabled? previous-basis-store)
                 :reported-contract-violations
                 (:reported-contract-violations previous-basis-store))
          basis-cache-store)]
    (->RuntimeCacheLifecycle
     token
     (:source-incarnation current)
     (:source-lifecycle current)
     basis-cache-store
     continuation-cache-store
     (:cursor-codec-cache current)
     (:cursor-construction-cache current)
     (:derived-schema-caches current)
     content-revision)))

(defn- lifecycle-content-revision
  [lifecycle]
  (:content-revision lifecycle))

(def ^:private cumulative-cache-counter-keys
  #{:hits :misses :puts :exact-hits :managed-hits :bypasses
    :expirations :restores :stamp-failures :retention-ineligible-pages
    :proof-unavailable :proof-contract-violations})

(def ^:private cumulative-cache-map-counter-keys
  #{:proof-unavailable-reasons :proof-contract-violation-reasons})

(defn- merge-cache-counters
  [current previous]
  (let [with-scalars
        (reduce
         (fn [result key]
           (update result key (fnil + 0) (get previous key 0)))
         current
         cumulative-cache-counter-keys)]
    (reduce
     (fn [result key]
       (update result key
               #(merge-with + (or % {}) (get previous key {}))))
     with-scalars
     cumulative-cache-map-counter-keys)))

(defn- accumulate-detached-cache-counters!
  [runtime lifecycle]
  (when-let [store (:basis-cache-store lifecycle)]
    (when (:telemetry-enabled? store)
      (swap! (::runtime-cache-lifecycle-metrics runtime)
             #(merge-cache-counters % (cache/basis-cache-stats store)))))
  nil)

(defn- record-runtime-cache-expiration!
  [runtime lifecycle]
  (when (some-> (:basis-cache-store lifecycle)
                :telemetry-enabled?)
    (swap! (::runtime-cache-lifecycle-metrics runtime)
           update :expirations (fnil inc 0)))
  nil)

(defn- attach-runtime-cache-lifecycle
  [options lifecycle]
  (-> (apply dissoc options runtime-lifecycle-option-keys)
      (merge (runtime-lifecycle-options lifecycle))
      (assoc :runtime-cache-lifecycle lifecycle
             :cache-lifecycle
             (cache/capture-cache-lifecycle
              (:basis-cache-store lifecycle)))))

(def ^:private runtime-option-keys
  #{:adapter-fingerprint :adapter-deterministic? :aggregate-limits
    :continuation-cache-store :basis-cache-store
    :cursor-codec-cache :cursor-construction-cache :decision-kernel
    :derived-schema-caches :expression-limits
    :entid->object-id :object-id->entid
    :object-id->lookup-ref :object->entid :internal-object->spice
    :spice-object->internal :internal-cursor->spice
    :spice-cursor->internal :format-options :cursor-ttl-seconds
    :token-ttl-seconds :managed-cache-enabled?
    :proof-equivalent-cursors? :identity-contract
    :proof-contract-reporter
    :recursive-traversal-limits :permission-tree-limits
    :execution-timeout-ms :consistency-sync-timeout-ms
    :service-admission :source-lifecycle :runtime-lifecycle-state
    :maximum-snapshot-retention-ms})

(defn- reader-api
  [api]
  {:backend-id (:backend-id api)
   :basis-adapter (:basis-adapter api)
   :basis-adapter-config-keys (:basis-adapter-config-keys api)
   :native-with (:native-with api)
   :normalize-report-datom (:normalize-report-datom api)
   :transaction-datom? (:transaction-datom? api)
   :schema-storage-datom? (:schema-storage-datom? api)
   :relation-version-attribute (:relation-version-attribute api)
   :prepare-relationship-tx (:prepare-relationship-tx api)
   :schema {:read-schema (get-in api [:schema :read-schema])
            :generation (get-in api [:schema :generation])
            :plan-replacement (get-in api [:schema :plan-replacement])}
   :impl {:validate-relationship-operation!
          (get-in api [:impl :validate-relationship-operation!])
          :relationship-relation-id
          (get-in api [:impl :relationship-relation-id])
          :relation-coordinate (get-in api [:impl :relation-coordinate])
          :tx-update-relationship
          (get-in api [:impl :tx-update-relationship])
          :affected-relation-ids
          (get-in api [:impl :affected-relation-ids])
          :read-relationships (get-in api [:impl :read-relationships])}})

(defn- runtime-options
  [runtime]
  (let [options (into {} runtime)
        lifecycle
        (or (::captured-runtime-cache-lifecycle options)
            (some-> (:runtime-lifecycle-state options) deref))]
    (if lifecycle
      (attach-runtime-cache-lifecycle options lifecycle)
      options)))

(defn- typed-capability-error!
  [capability target]
  (throw
   (ex-info
    "Authorization target does not support this capability."
    {:type :eacl/unsupported-capability
     :eacl/error :eacl/unsupported-capability
     :capability capability
     :target target})))

(defn- monotonic-millis
  []
  #?(:clj (quot (System/nanoTime) 1000000)
     :cljs (.now js/Date)))

(declare release-basis!)

(defn- enforce-basis-retention!
  [basis]
  (when-let [maximum (:maximum-retention-ms basis)]
    (let [age (- (monotonic-millis) (:acquired-at-ms basis))]
      (when (>= age maximum)
        ;; This runs only after the ordinary open/thread checks below.  An
        ;; owned native value is therefore released on its acquiring thread;
        ;; a direct borrowed value only closes the EACL wrapper.
        (release-basis! basis)
        (throw
         (ex-info
          "Authorization snapshot exceeded its configured retention bound."
          {:type :eacl/snapshot-retention-exceeded
           :eacl/error :eacl/snapshot-retention-exceeded
           :backend (get-in basis [:identity :backend])
           :maximum-retention-ms maximum
           :age-ms age}))))))

(defn- basis-open!
  [basis]
  (if-let [selected (:selected-snapshot basis)]
    (do
      (source/assert-open! selected)
      (enforce-basis-retention! basis))
    (do
      #?(:clj
         (when (and (= :acquiring-thread
                       (get-in basis
                               [:execution-constraints :snapshot-thread]))
                    (not (identical? (:owner-thread basis)
                                     (Thread/currentThread))))
           (throw
            (ex-info
             "Authorization snapshot escaped its acquiring thread."
             {:type :eacl/snapshot-thread-violation
              :eacl/error :eacl/snapshot-thread-violation
              :backend (get-in basis [:identity :backend])
              :phase :snapshot-access
              :constraint :acquiring-thread}))))
      (when (= :released @(:release-state basis))
        (throw
         (ex-info
          "Authorization snapshot has already been released."
          {:type :eacl/snapshot-released
           :eacl/error :eacl/snapshot-released
           :backend (get-in basis [:identity :backend])})))
      (enforce-basis-retention! basis)))
  basis)

(defn- basis-released?
  [basis]
  (if-let [selected (:selected-snapshot basis)]
    (source/released? selected)
    (= :released @(:release-state basis))))

(defn- release-basis!
  [basis]
  (if-let [selected (:selected-snapshot basis)]
    (let [released? (source/release! selected)]
      (when released?
        (request-counters/add! :releases))
      released?)
    (do
      #?(:clj
         (when (and (= :acquiring-thread
                       (get-in basis
                               [:execution-constraints :release-thread]))
                    (not (identical? (:owner-thread basis)
                                     (Thread/currentThread))))
           (throw
            (ex-info
             "Authorization snapshot release escaped its acquiring thread."
             {:type :eacl/snapshot-thread-violation
              :eacl/error :eacl/snapshot-thread-violation
              :backend (get-in basis [:identity :backend])
              :phase :snapshot-release
              :constraint :acquiring-thread}))))
      (let [released? (compare-and-set! (:release-state basis)
                                        :open :released)]
        (when released?
          (request-counters/add! :releases))
        released?))))

(defn- public-basis
  [basis]
  (basis-open! basis)
  (assoc
   (select-keys (:identity basis)
                [:backend :source-id :branch :source-lifecycle
                 :revision :exact-locator])
   :kind (:basis-kind basis)))

(defn- basis-token*
  [runtime basis]
  (basis-open! basis)
  (consistency-v3/selected-basis-token
   (or (get-in basis [:speculative :committed-root])
       (:identity basis))
   (runtime-options runtime)))

(defn- basis-token-data
  [runtime basis token source]
  (let [identity (:identity basis)
        expected-scope
        (select-keys identity
                     [:backend :source-id :source-lifecycle :branch])]
    (try
      (causal-token/token-data
       (:format-options (runtime-options runtime)) expected-scope token)
      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
             error
        (if (= :scope-mismatch (:reason (ex-data error)))
          (throw
           (ex-info
            "Consistency token names another authorization basis."
            {:type :eacl.consistency/basis-conflict
             :eacl/error :eacl.consistency/basis-conflict
             :source source}
            error))
          (throw error))))))

(defn- assert-snapshot-consistency!
  [runtime basis request]
  (basis-open! basis)
  (let [{:keys [mode token]}
        (consistency/descriptor (:consistency request))
        identity (:identity basis)]
    (case mode
      :minimize-latency nil

      :fully-consistent
      (when-not (::transient-acl-selection? (runtime-options runtime))
        (throw
         (ex-info
          "Fully consistent reads require selection through an acl."
          {:type :eacl.consistency/selection-required
           :eacl/error :eacl.consistency/selection-required
           :capability :snapshot-selection
           :target :snapshot})))

      :at-least-as-fresh
      (let [requested (basis-token-data runtime basis token :token)
            actual (:revision identity)]
        (when (< actual (:revision requested))
          (throw
           (ex-info
            "The retained snapshot is behind the requested freshness floor."
            {:type :eacl.consistency/freshness-unavailable
             :eacl/error :eacl.consistency/freshness-unavailable
             :reason :snapshot-behind
             :requested-revision (:revision requested)
             :actual-revision actual}))))

      :at-exact-snapshot
      (let [requested (basis-token-data runtime basis token :token)]
        (when-not (= (select-keys requested [:revision :exact-locator])
                     (select-keys identity [:revision :exact-locator]))
          (throw
           (ex-info
            "Consistency token names another authorization basis."
            {:type :eacl.consistency/basis-conflict
             :eacl/error :eacl.consistency/basis-conflict
             :source :token}))))))
  request)

(defn- snapshot-opts
  [runtime basis]
  (let [opts (runtime-options runtime)
        current-runtime-lifecycle (:runtime-cache-lifecycle opts)
        current-lifecycle (:source-lifecycle current-runtime-lifecycle)
        retained-lifecycle (get-in basis [:identity :source-lifecycle])
        retired?
        (or (not= current-lifecycle retained-lifecycle)
            (not (identical? (:source-incarnation basis)
                             (:source-incarnation
                              current-runtime-lifecycle))))]
    (cond-> (assoc opts
                   ::retained-basis basis
                   :authorization-target-kind
                   (if (::transient-acl-selection? opts)
                     :acl
                     :snapshot))
      (:speculative basis)
      ;; Prospective state may read an already committed, proof-carrying
      ;; managed entry after disjointness validation, but no result or derived
      ;; artifact from the prospective value may outlive this operation.
      (assoc :speculative-context (:speculative basis)
             :populate-cache-request? false
             :continuation-cache-request? false
             :continuation-cache-store nil
             :cursor-codec-cache nil
             :cursor-construction-cache nil
             :derived-schema-caches (derived-schema/store))

      retired?
      ;; Lifecycle rotation makes every old registry generation unreachable
      ;; to the live Acl. A retained immutable snapshot remains evaluable, but
      ;; it must not repopulate the cleared runtime under its old lineage.
      (assoc :basis-cache-store nil
             :cache-lifecycle nil
             :continuation-cache-store nil
             :cursor-codec-cache nil
             :cursor-construction-cache nil
             :derived-schema-caches (derived-schema/store)
             :managed-cache-enabled? false
             ::retired-basis? true))))

(defn- read-current-schema
  [api source opts request]
  (let [opts (ensure-execution-contract opts :read-schema request)]
    (with-selected-context
      api source opts (:consistency request)
      (fn [request-context]
        (execution/check! (:execution-contract opts) :schema-read)
        (let [selected-opts (selected-cache-options opts request-context)
              schema
              (call-with-request-schema-cache
               selected-opts
               #((get-in api [:schema :read-schema])
                 (context-db request-context)))]
          (execution/check! (:execution-contract opts)
                            :schema-read-complete)
          schema)))))

(defn- make-basis
  [{:keys [adapter selected-snapshot semantic-identity selection
           historical-basis? execution-constraints
           maximum-snapshot-retention-ms runtime-options
           source-incarnation speculative]}]
  (->Basis adapter selected-snapshot semantic-identity selection
           (if speculative :speculative (:basis-kind semantic-identity))
           historical-basis?
           (or execution-constraints
               source/default-execution-constraints)
           (atom :open)
           #?(:clj (Thread/currentThread) :cljs nil)
           (monotonic-millis)
           maximum-snapshot-retention-ms
           (or source-incarnation
               (get-in runtime-options
                       [:runtime-cache-lifecycle :source-incarnation]))
           speculative))

(defn- snapshot-populate-cache?
  [basis requested?]
  (and (nil? (:speculative basis)) requested?))

(defrecord Snapshot [runtime basis api]
  IAuthorizationReader
  (-check-permission [_ {:keys [subject permission resource consistency]
                         :as request}]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (check-permission
       api nil
       (assoc (snapshot-opts runtime basis)
              :request-operation :check-permission
              :execution-request request
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?))
       subject permission resource
       (or consistency consistency/minimize-latency))))
  (-read-schema [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (read-current-schema api nil (snapshot-opts runtime basis) request))
  (-read-relationships [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (read-relationships
       api nil
       (assoc (snapshot-opts runtime basis)
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?))
       (dissoc request :cache? :populate-cache?))))
  (-lookup-resources [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (lookup-resources
       api nil
       (assoc (snapshot-opts runtime basis)
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?)
              :continuation-cache-request? cache-enabled?)
       (dissoc request :cache? :populate-cache?))))
  (-lookup-subjects [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (lookup-subjects
       api nil
       (assoc (snapshot-opts runtime basis)
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?)
              :continuation-cache-request? cache-enabled?)
       (dissoc request :cache? :populate-cache?))))
  (-count-resources [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (count-resources
       api nil
       (assoc (snapshot-opts runtime basis)
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?))
       (dissoc request :cache? :populate-cache?))))
  (-count-subjects [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (count-subjects
       api nil
       (assoc (snapshot-opts runtime basis)
              :completed-cache-request? cache-enabled?
              :populate-cache-request?
              (snapshot-populate-cache? basis populate-cache?))
       (dissoc request :cache? :populate-cache?))))
  (-expand-permission-tree [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (let [{:keys [cache-enabled? populate-cache?]}
          (request-cache-controls request)]
      (expand-permission-tree
       api nil (assoc (snapshot-opts runtime basis)
                      :completed-cache-request? cache-enabled?
                      :populate-cache-request?
                      (snapshot-populate-cache? basis populate-cache?))
       (dissoc request :cache? :populate-cache?))))

  IBatchedAuthorization
  (-check-permissions [_ request]
    (assert-snapshot-consistency! runtime basis request)
    (check-permissions api nil (snapshot-opts runtime basis) request))

  ISpeculativeAuthorization
  (-with [this tx-data]
    (speculative-with-snapshot this tx-data))
  (-with-schema [this schema options]
    (speculative-with-schema-snapshot this schema options))
  (-tx-relationship [this update]
    (snapshot-tx-relationship this update))
  (-speculative-diagnostics [_]
    (basis-open! basis)
    (if-let [speculative (:speculative basis)]
      (:diagnostics speculative)
      (typed-capability-error! :speculative-diagnostics :ordinary-snapshot)))

  IAuthorizationSnapshot
  (-basis [_] (public-basis basis))
  (-basis-token [_] (basis-token* runtime basis))
  (-release! [_] (release-basis! basis))
  (-released? [_] (basis-released? basis)))

(defn- writable!
  [writer]
  (or writer (typed-capability-error! :write :acl)))

(defn- make-writer-role
  [api conn source options runtime]
  (backend-writer/make-writer
   {:id (:backend-id api)
    :state {:conn conn :source source :options options
            :runtime runtime :api api}
    :max-attempts (or (:writer-max-attempts api) 1)
    :max-transaction-size
    (or (:writer-max-transaction-size api)
        backend/maximum-exact-integer)
    :operations
    {:transact! (:transact! api)
     :write-schema! (get-in api [:schema :write-schema!])
     :schema-generation (get-in api [:schema :generation])
     :plan-relationship-update
     (get-in api [:impl :tx-update-relationship])
     :plan-delete-object
     (or (get-in api [:impl :tx-delete-object-stream])
         (get-in api [:impl :tx-delete-object]))
     :prepare-relationship-tx
     (or (:prepare-relationship-tx api)
         (fn [_db tx-data]
           (relationship-commit-preconditions-first tx-data)))
     :relation-id (get-in api [:impl :relationship-relation-id])
     :affected-relations (get-in api [:impl :affected-relation-ids])
     :retraction-count (:relationship-retraction-count api)
     :contention? (or (:writer-contention? api) (constantly false))}}))

(defn- current-writer-options
  [writer]
  (let [{:keys [runtime options]} (backend-writer/state writer)]
    (if runtime
      (merge options (runtime-options runtime))
      options)))

(defn- relationship-contention!
  [writer attempts cause]
  (throw
   (ex-info
    "EACL mutation could not obtain a stable schema/relation generation."
    {:type :eacl/relationship-contention
     :eacl/error :eacl/relationship-contention
     :backend (backend-writer/backend-id writer)
     :attempts attempts}
    cause)))

(defn- transaction-size-exceeded!
  [writer actual]
  (throw
   (ex-info
    "Planned authorization transaction exceeds the writer's declared limit."
    {:type :eacl/transaction-size-exceeded
     :eacl/error :eacl/transaction-size-exceeded
     :backend (backend-writer/backend-id writer)
     :actual actual
     :maximum (backend-writer/max-transaction-size writer)})))

(defn- call-with-writer-basis
  [writer f]
  (let [{:keys [source api]} (backend-writer/state writer)
        options (current-writer-options writer)]
    (with-selected-basis
      api source options consistency/minimize-latency f)))

(defn- submit-writer-tx!
  [writer tx-data]
  (let [{:keys [conn]} (backend-writer/state writer)]
    (request-counters/add! :writer-submissions)
    ((backend-writer/operation writer :transact!)
     conn {:tx-data (vec tx-data)})))

(defn- writer-write-relationships!
  [writer updates]
  (let [{:keys [api]} (backend-writer/state writer)
        options (current-writer-options writer)
        validate-operation!
        (get-in api [:impl :validate-relationship-operation!])
        plan-update
        (backend-writer/operation writer :plan-relationship-update)
        prepare
        (backend-writer/operation writer :prepare-relationship-tx)
        contention?
        (backend-writer/operation writer :contention?)
        updates (vec updates)]
    ;; Shape and operation validation precede acquisition. A malformed batch
    ;; cannot observe source state or enter a retry loop.
    (doseq [{:keys [operation]} updates]
      (validate-operation! operation))
    (loop [attempt 1]
      (let [outcome
            (try
              {:value
               (let [{:keys [tx-data no-op-response]}
                     (call-with-writer-basis
                      writer
                      (fn [{:keys [db selection]}]
                        (let [schema
                              ((get-in api [:schema :read-schema]) db)
                              _
                              (doseq [{:keys [relationship]} updates]
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
                               #(spice-relationship->internal db options %)
                               updates)
                              _
                              (relationship-mutations/validate-batch!
                               internal-updates)
                              raw-tx
                              (->> internal-updates
                                   (mapcat #(plan-update db %))
                                   (remove nil?)
                                   distinct
                                   vec)
                              tx-data
                              (when (seq raw-tx)
                                (vec (prepare db raw-tx)))]
                          (if (seq tx-data)
                            {:tx-data tx-data}
                            {:no-op-response
                             (write-response-for-revision
                              api (:native-revision selection)
                              options)}))))]
                 ;; An owned planning basis (notably Datalevin's read
                 ;; transaction) is released before the writer can block on a
                 ;; commit. The fully realized plan is the only value crossing
                 ;; this boundary.
                 (if-not tx-data
                   no-op-response
                   ;; Relationship batches retain their documented atomic
                   ;; semantics. The declared transaction-size bound governs
                   ;; the only operation the shared contract permits to split:
                   ;; delete-object!. Silently partitioning relationship
                   ;; updates would make create/touch/delete batch validation
                   ;; race across commits.
                   (let [report (submit-writer-tx! writer tx-data)]
                     (committed-write-response
                      api (:db-after report) options))))}
              (catch #?(:clj Throwable :cljs :default) error
                {:error error}))]
        (if-let [error (:error outcome)]
          (if (contention? error)
            (if (< attempt (backend-writer/max-attempts writer))
              (recur (inc attempt))
              (relationship-contention! writer attempt error))
            (throw error))
          (:value outcome))))))

(defn- largest-fitting-prepared-batch
  "Returns a non-empty final transaction no larger than the writer limit.

  `raw-ops` may be a lazy native scan. At most limit+1 raw operations are
  realized, and preparation is binary-searched because schema/relation guards
  can make the final transaction larger than its raw slice."
  [writer db raw-ops]
  (let [limit (backend-writer/max-transaction-size writer)
        prepare (backend-writer/operation writer :prepare-relationship-tx)
        sampled (if (counted? raw-ops)
                  (let [raw-count (count raw-ops)]
                    (vec (if (<= raw-count limit)
                           raw-ops
                           (take (inc limit) raw-ops))))
                  (vec (take (inc limit) raw-ops)))
        more-raw? (> (count sampled) limit)
        candidates (if more-raw? (subvec sampled 0 limit) sampled)]
    (when (seq candidates)
      (let [prepared-prefix
            (fn [n]
              (vec (prepare db (subvec candidates 0 n))))
            all-prepared (prepared-prefix (count candidates))]
        (if (<= (count all-prepared) limit)
          {:tx-data all-prepared
           :raw-count (count candidates)
           :basis-exhausted? (not more-raw?)}
          (loop [low 1
                 high (dec (count candidates))
                 best nil]
            (if (> low high)
              (if best
                (assoc best :basis-exhausted? false)
                (transaction-size-exceeded!
                 writer (count (prepared-prefix 1))))
              (let [mid (quot (+ low high) 2)
                    prepared (prepared-prefix mid)]
                (if (<= (count prepared) limit)
                  (recur (inc mid) high
                         {:tx-data prepared :raw-count mid})
                  (recur low (dec mid) best))))))))))

(defn- writer-delete-object!
  "Removes every relationship touching object in final-transaction-bounded
  batches. Each contention retry reacquires and replans from a fresh basis."
  [writer object]
  (let [{:keys [api]} (backend-writer/state writer)
        options (current-writer-options writer)
        plan-delete
        (backend-writer/operation writer :plan-delete-object)
        contention?
        (backend-writer/operation writer :contention?)
        retraction-count
        (backend-writer/operation writer :retraction-count)]
    (loop [retracted 0
           last-response nil]
      (let [batch-outcome
            (loop [attempt 1]
              (let [outcome
                    (try
                      {:value
                       (let [planned
                             (call-with-writer-basis
                              writer
                              (fn [{:keys [db selection]}]
                                (let [object-eid
                                      (or
                                       (try
                                         ((:object->entid options) db object)
                                         (catch #?(:clj Throwable
                                                   :cljs :default) _
                                           nil))
                                       (when (number? (:id object))
                                         (:id object)))
                                      fitted
                                      (largest-fitting-prepared-batch
                                       writer db
                                       (plan-delete db object-eid))]
                                  (if-not fitted
                                    {:done? true
                                     :response
                                     (or last-response
                                         (write-response-for-revision
                                          api (:native-revision selection)
                                          options))}
                                    fitted))))]
                         (if-not (:tx-data planned)
                           planned
                           (let [report
                                 (submit-writer-tx!
                                  writer (:tx-data planned))
                                 db-after (:db-after report)]
                             {:done? (:basis-exhausted? planned)
                              :response
                              (committed-write-response
                               api db-after options)
                              :retracted
                              (retraction-count
                               db-after (:tx-data report))})))}
                      (catch #?(:clj Throwable :cljs :default) error
                        {:error error}))]
                (if-let [error (:error outcome)]
                  (if (contention? error)
                    (if (< attempt (backend-writer/max-attempts writer))
                      (recur (inc attempt))
                      (relationship-contention! writer attempt error))
                    (throw error))
                  (:value outcome))))
            retracted (+ retracted (or (:retracted batch-outcome) 0))
            response (:response batch-outcome)]
        (if (:done? batch-outcome)
          (assoc response :retracted-datoms retracted)
          (recur retracted response))))))

(defn- write-schema-through!
  [writer {:keys [schema] :as request}]
  (when (= :retain-inert (:orphan-policy request))
    (throw
     (ex-info
      ":orphan-policy :retain-inert is available only through eacl/with-schema."
      {:type :eacl.schema/invalid-orphan-policy
       :eacl/error :eacl.schema/invalid-orphan-policy
       :orphan-policy :retain-inert
       :operation :write-schema!})))
  (let [schema-string schema]
  (require-operator-expression-writes-enabled! schema-string)
  (let [{:keys [conn api]} (backend-writer/state writer)
        options (current-writer-options writer)
        write-schema! (backend-writer/operation writer :write-schema!)
        contention? (backend-writer/operation writer :contention?)
        result
        (loop [attempt 1]
          (let [outcome
                (try
                  (let [expected-generation
                        (call-with-writer-basis
                         writer
                         (fn [{:keys [db]}]
                           ((backend-writer/operation
                             writer :schema-generation)
                            db)))]
                    {:value
                    (write-schema!
                      conn schema-string
                      (merge
                       (select-keys options
                                    [:token-ttl-seconds :expression-limits])
                       (select-keys request
                                    [:allow-empty-schema? :orphan-policy]))
                      expected-generation)})
                  (catch #?(:clj Throwable :cljs :default) error
                    {:error error}))]
            (if-let [error (:error outcome)]
              (if (contention? error)
                (if (< attempt (backend-writer/max-attempts writer))
                  (recur (inc attempt))
                  (relationship-contention! writer attempt error))
                (throw error))
              (:value outcome))))]
    (when-not (:eacl.schema/no-op? result)
      ;; Exact keys already include the new immutable basis and managed keys
      ;; include the certified schema generation. Retaining bounded historical
      ;; entries is safe and avoids an obsolete whole-cache flush.
      (request-counters/add! :writer-submissions))
    (merge result
           (if (:eacl.schema/no-op? result)
             (write-response api (:eacl.schema/db-after result) options)
             (committed-write-response
              api (:eacl.schema/db-after result) options))))))

(defn- speculative-capability!
  [api capability]
  (throw
   (ex-info
    "Backend does not support this speculative operation."
    {:type :eacl/unsupported-capability
     :eacl/error :eacl/unsupported-capability
     :backend (:backend-id api)
     :capability capability})))

(defn- relation-coordinate
  [api db relation-id]
  (when-let [coordinate-fn (get-in api [:impl :relation-coordinate])]
    (coordinate-fn db relation-id)))

(defn- empty-speculative-effects
  []
  {:complete? true
   :relationships #{}
   :schema-components #{}
   :other #{}})

(defn- union-speculative-effects
  [parent child]
  {:complete? (and (:complete? parent) (:complete? child))
   :relationships
   (set/union (:relationships parent) (:relationships child))
   :schema-components
   (set/union (:schema-components parent) (:schema-components child))
   :other (set/union (:other parent) (:other child))})

(defn- emitted-effects
  [api {:keys [db-before db-after tx-data]}]
  (let [normalize (:normalize-report-datom api)
        schema-storage? (:schema-storage-datom? api)
        transaction-datom? (:transaction-datom? api)
        relation-version-attribute (:relation-version-attribute api)]
    (when-not (and (fn? normalize)
                   (fn? schema-storage?)
                   (fn? transaction-datom?))
      (speculative-capability! api :native-with-effect-certificate))
    (reduce
     (fn [effects raw-datom]
       (let [{:keys [e a v added] :as datom}
             (normalize db-before db-after raw-datom)]
         (cond
           (schema-storage? db-before db-after datom)
           (throw
            (ex-info
             "Generic eacl/with cannot mutate EACL permission-schema storage; use eacl/with-schema."
             {:type :eacl.speculative/schema-mutation
              :eacl/error :eacl.speculative/schema-mutation
              :attribute a
              :operation :with
              :use :with-schema}))

           (transaction-datom? datom)
           effects

           (contains? relationship-storage/attributes a)
           (let [relation-id (when (and (vector? v) (< 1 (count v)))
                               (nth v 1))
                 coordinate
                 (or (when (false? added)
                       (relation-coordinate api db-before relation-id))
                     (relation-coordinate api db-after relation-id)
                     (relation-coordinate api db-before relation-id))]
             (if coordinate
               (update effects :relationships conj coordinate)
               (-> effects
                   (assoc :complete? false)
                   (update :other conj :unknown-relationship-coordinate))))

           (= relation-version-attribute a)
           (if-let [coordinate
                    (or (when (false? added)
                          (relation-coordinate api db-before e))
                        (relation-coordinate api db-after e)
                        (relation-coordinate api db-before e))]
             (update effects :relationships conj coordinate)
             (-> effects
                 (assoc :complete? false)
                 (update :other conj :unknown-relation-version-target)))

           :else
           ;; Application identity, existence, ordering, and externalization
           ;; are deliberately not guessed from arbitrary attributes.
           (-> effects
               (assoc :complete? false)
               (update :other conj :unclassified-application-datom)))))
     (empty-speculative-effects)
     tx-data)))

(defn- validate-native-with-report!
  [api report]
  (when-not (and (map? report)
                 (some? (:db-before report))
                 (some? (:db-after report))
                 (seqable? (:tx-data report)))
    (throw
     (ex-info
      "Backend native-with returned an incomplete transaction report."
      {:type :eacl/backend-contract-violation
       :eacl/error :eacl/backend-contract-violation
       :backend (:backend-id api)
       :operation :native-with
       :required #{:db-before :db-after :tx-data}})))
  (update report :tx-data vec))

(defn- speculative-snapshot-from-report
  [snapshot report child-effects child-diagnostics]
  (let [{:keys [runtime basis api]} snapshot
        db-before (:db (backend/state (:adapter basis)))
        _
        (when-not (identical? db-before (:db-before report))
          (throw
           (ex-info
            "Backend native-with changed the selected db-before identity."
            {:type :eacl/backend-contract-violation
             :eacl/error :eacl/backend-contract-violation
             :backend (:backend-id api)
             :operation :native-with
             :obligation :selected-db-before})))
        parent-speculative (:speculative basis)
        parent-effects
        (or (:effects parent-speculative) (empty-speculative-effects))
        effects (union-speculative-effects parent-effects child-effects)
        options (runtime-options runtime)
        adapter
        ((:basis-adapter api)
         (:db-after report)
         (select-keys options (:basis-adapter-config-keys api)))
        parent-identity (:identity basis)
        committed-root
        (or (:committed-root parent-speculative) parent-identity)
        root-schema-generation
        (if parent-speculative
          (:root-schema-generation parent-speculative)
          (backend/invoke (:adapter basis) :schema-generation))
        speculative-id (str (random-uuid))
        identity
        (assoc
         (adapter-semantic-identity
          adapter
          (select-keys parent-identity [:source-id :branch])
          (:source-lifecycle parent-identity))
         :speculative-id speculative-id)
        speculative
        {:id speculative-id
         :committed-root committed-root
         :root-schema-generation root-schema-generation
         :relation-coordinate-fn
         (get-in api [:impl :relation-coordinate])
         :effects effects
         :diagnostics
         (into (vec (:diagnostics parent-speculative)) child-diagnostics)}]
    (when-not (and (map? committed-root)
                   (or (nil? root-schema-generation)
                       (integer? root-schema-generation))
                   (fn? (:relation-coordinate-fn speculative))
                   (map? effects)
                   (vector? (:diagnostics speculative)))
      (throw
       (ex-info
        "Speculative snapshot provenance is incomplete."
        {:type :eacl/backend-contract-violation
         :eacl/error :eacl/backend-contract-violation
         :backend (:backend-id api)
         :operation :speculative-snapshot-construction})))
    (->Snapshot
     runtime
     (make-basis
      {:adapter adapter
       :selected-snapshot nil
       :semantic-identity identity
       :selection {:descriptor {:mode :minimize-latency}
                   :speculative? true}
       :execution-constraints (:execution-constraints basis)
       :maximum-snapshot-retention-ms (:maximum-retention-ms basis)
       :historical-basis? false
       :source-incarnation (:source-incarnation basis)
       :speculative speculative})
     api)))

(defn speculative-with-snapshot
  [snapshot tx-data]
  (let [{:keys [basis api]} snapshot
        _ (basis-open! basis)
        native-with (:native-with api)]
    (when-not (fn? native-with)
      (speculative-capability! api :native-with))
    (let [db-before (:db (backend/state (:adapter basis)))
          report
          (validate-native-with-report!
           api (native-with db-before (vec tx-data)))
          child-effects (emitted-effects api report)]
      (speculative-snapshot-from-report
       snapshot report child-effects []))))

(defn speculative-with-schema-snapshot
  [snapshot schema options]
  (let [{:keys [runtime basis api]} snapshot
        _ (basis-open! basis)
        native-with (:native-with api)
        plan-replacement (get-in api [:schema :plan-replacement])]
    (when-not (fn? native-with)
      (speculative-capability! api :native-with))
    (when-not (ifn? plan-replacement)
      (speculative-capability! api :with-schema))
    (let [db-before (:db (backend/state (:adapter basis)))
          plan
          (plan-replacement
           db-before schema
           (merge
            (select-keys (runtime-options runtime) [:expression-limits])
            (or options {})))
          tx-data (:speculative-tx-data plan)
          _
          (when-not (and (map? plan)
                         (sequential? tx-data)
                         (set? (:changed-schema-components plan))
                         (set? (:affected-relationships plan))
                         (set? (:removed-relations plan))
                         (vector? (:diagnostics plan))
                         (boolean? (:no-op? plan)))
            (throw
             (ex-info
              "Backend schema planner returned an incomplete speculative plan."
              {:type :eacl/backend-contract-violation
               :eacl/error :eacl/backend-contract-violation
               :backend (:backend-id api)
               :operation :plan-schema-replacement})))
          report
          (validate-native-with-report!
           api (native-with db-before (vec tx-data)))
          effects
          {:complete? true
           :relationships (:affected-relationships plan)
           :schema-components (:changed-schema-components plan)
           :other #{}}]
      (speculative-snapshot-from-report
       snapshot report effects (:diagnostics plan)))))

(defn snapshot-tx-relationship
  [snapshot update]
  (let [{:keys [basis api]} snapshot
        _ (basis-open! basis)
        db (:db (backend/state (:adapter basis)))
        {:keys [operation relationship]}
        (if (and (map? update) (contains? update :relationship))
          update
          {:operation (:operation update)
           :relationship
           (->Relationship (:subject update)
                           (:relation update)
                           (:resource update))})
        validate-operation!
        (get-in api [:impl :validate-relationship-operation!])
        plan-update (get-in api [:impl :tx-update-relationship])
        prepare (or (:prepare-relationship-tx api)
                    (fn [_db tx]
                      (relationship-commit-preconditions-first tx)))]
    (when-not (and (ifn? validate-operation!)
                   (ifn? plan-update))
      (speculative-capability! api :tx-relationship))
    (validate-operation! operation)
    (let [schema ((get-in api [:schema :read-schema]) db)
          _
          (schema-errors/validate-relationship-write!
           schema :write-relationships
           {:resource-type (:type (:resource relationship))
            :subject-type (:type (:subject relationship))
            :relation (:relation relationship)})
          internal-relationship
          (spice-relationship->internal
           db (runtime-options (:runtime snapshot)) relationship)
          internal-update
          (->RelationshipUpdate operation internal-relationship)
          _ (relationship-mutations/validate-batch! [internal-update])
          raw (some-> (plan-update db internal-update) vec)]
      (if (seq raw)
        (vec (prepare db raw))
        []))))

(defn- call-with-transient-snapshot
  "Selects one request basis, delegates to the ordinary Snapshot reader, and
  releases in `finally`. The transient runtime carries only the already
  normalized request contract; it still contains no source or writer."
  [runtime source api operation request f]
  (let [opts (ensure-execution-contract
              (runtime-options runtime) operation request)
        selected (select-request-basis
                  api source opts (:consistency request))
        selected-options (:runtime-options selected)
        transient-runtime
        (map->Runtime
         (-> selected-options
             ;; A retained snapshot owns its immutable captured lifecycle. It
             ;; must not retain a live path to later client cache/source
             ;; incarnations through the mutable outer state atom.
             (dissoc :runtime-lifecycle-state)
             (assoc ::transient-acl-selection? true
                    :execution-contract (:execution-contract opts)
                    ::captured-runtime-cache-lifecycle
                    (:runtime-cache-lifecycle selected-options))))
        snapshot (->Snapshot transient-runtime
                             (make-basis selected)
                             (reader-api api))]
    (try
      (binding [relay/*acl-cursor-recovery-source* source]
        (f snapshot))
      (finally
        (eacl/release! snapshot)))))

(defrecord Acl [runtime source writer api]
  IAuthorizationReader
  (-check-permission [_ request]
    (call-with-transient-snapshot
     runtime source api :check-permission request
     #(eacl/-check-permission % request)))
  (-read-schema [_ request]
    (call-with-transient-snapshot
     runtime source api :read-schema request
     #(eacl/-read-schema % request)))
  (-read-relationships [_ request]
    (relationship-filters/validate! request)
    (authorization-filters/validate-scan-authorization! request)
    (binding [relationship-filters/*validated-request?* true
              authorization-filters/*validated-request?* true]
      (call-with-transient-snapshot
       runtime source api :read-relationships request
       #(eacl/-read-relationships % request))))
  (-lookup-resources [_ request]
    (authorization-filters/validate-lookup! :lookup-resources request)
    (binding [authorization-filters/*validated-request?* true]
      (call-with-transient-snapshot
       runtime source api :lookup-resources request
       #(eacl/-lookup-resources % request))))
  (-lookup-subjects [_ request]
    (authorization-filters/validate-lookup! :lookup-subjects request)
    (binding [authorization-filters/*validated-request?* true]
      (call-with-transient-snapshot
       runtime source api :lookup-subjects request
       #(eacl/-lookup-subjects % request))))
  (-count-resources [_ request]
    (call-with-transient-snapshot
     runtime source api :count-resources request
     #(eacl/-count-resources % request)))
  (-count-subjects [_ request]
    (call-with-transient-snapshot
     runtime source api :count-subjects request
     #(eacl/-count-subjects % request)))
  (-expand-permission-tree [_ request]
    (permission-tree/validate-request! request)
    (call-with-transient-snapshot
     runtime source api :expand-permission-tree request
     #(eacl/-expand-permission-tree % request)))

  IBatchedAuthorization
  (-check-permissions [_ request]
    (let [request
          (batch/validate-request!
           request (:aggregate-limits (runtime-options runtime)))
          _ (request-cache-controls request)]
      (if (empty? (:checks request))
        (do
          (execution/normalize
           (runtime-options runtime) :check-permissions request)
          [])
        (batch/call-with-demand-error
         0 batch/empty-aggregate-counters
         #(call-with-transient-snapshot
           runtime source api :check-permissions request
           (fn [snapshot]
             (eacl/-check-permissions snapshot request)))))))

  ISnapshotSource
  (-snapshot [_ consistency-value options]
    (let [opts
          (ensure-execution-contract
           (merge (runtime-options runtime) options)
           :snapshot
           {:consistency consistency-value})]
      (->Snapshot
       runtime
       (make-basis
       (select-request-basis api source opts consistency-value))
       (reader-api api))))

  ISpeculativeAuthorization
  (-with [this tx-data]
    (let [parent (eacl/snapshot this)]
      (try
        (eacl/-with parent tx-data)
        (finally
          (eacl/release! parent)))))
  (-with-schema [this schema options]
    (let [parent (eacl/snapshot this)]
      (try
        (eacl/-with-schema parent schema options)
        (finally
          (eacl/release! parent)))))
  (-tx-relationship [_ _update]
    (typed-capability-error! :tx-relationship :acl))
  (-speculative-diagnostics [_]
    (typed-capability-error! :speculative-diagnostics :acl))

  IAuthorizationWriter
  (-write-schema! [_ request]
    (write-schema-through! (writable! writer) request))
  (-write-relationships! [_ {:keys [updates]}]
    (writer-write-relationships! (writable! writer) updates))
  (-delete-object! [_ {:keys [object]}]
    (writer-delete-object! (writable! writer) object)))

(defn client?
  "True when `client` is a shared-orchestration client for `backend-id`."
  [client backend-id]
  (and (instance? Acl client)
       (= backend-id (get-in client [:api :backend-id]))))

(defn- unsupported-database-value!
  [backend-id basis-kind data cause]
  (throw
   (ex-info
    "Database value is not an admissible public authorization basis."
    (merge
     {:type :eacl/unsupported-database-value
      :eacl/error :eacl/unsupported-database-value
      :backend backend-id
      :basis-kind basis-kind}
     data)
    cause)))

(defn snapshot-db
  "Returns the native immutable value held by a backend-specific snapshot."
  [snapshot backend-id]
  (when-not (and (instance? Snapshot snapshot)
                 (= backend-id (get-in snapshot [:api :backend-id])))
    (unsupported-database-value!
     backend-id :foreign-backend {:target :snapshot} nil))
  (basis-open! (:basis snapshot))
  (:db (backend/state (get-in snapshot [:basis :adapter]))))

(defn- client-options
  [client]
  (if (instance? Acl client)
    (runtime-options (:runtime client))
    {}))

(defn- runtime-cache-lifecycle-config
  [runtime]
  (::runtime-cache-lifecycle-config runtime))

(defn- rotate-runtime-cache-lifecycle!
  [runtime make-next]
  (let [state (:runtime-lifecycle-state runtime)]
    (when-not state
      (throw
       (ex-info
        "EACL client has no runtime cache lifecycle."
        {:type :eacl/invalid-client
         :eacl/error :eacl/invalid-client})))
    (loop []
      (let [current @state
            next (make-next current)]
        (if (compare-and-set! state current next)
          {:installed next :detached current}
          (recur))))))

(defn- install-restored-runtime-cache-lifecycle!
  [runtime captured candidate]
  (let [state (:runtime-lifecycle-state runtime)
        captured-source-lifecycle (:source-lifecycle captured)
        captured-source-incarnation (:source-incarnation captured)]
    (loop [current captured]
      (when-not (and (= captured-source-lifecycle
                        (:source-lifecycle current))
                     (identical? captured-source-incarnation
                                 (:source-incarnation current)))
        (throw
         (ex-info
          "Cache restore raced a source-lifecycle rotation."
          {:type :eacl/cache-restore-lifecycle-conflict
           :eacl/error :eacl/cache-restore-lifecycle-conflict
           :captured-source-lifecycle captured-source-lifecycle
           :current-source-lifecycle (:source-lifecycle current)})))
      ;; The candidate's LRUs and values were validated exactly once. A
      ;; same-lineage CAS loss may only rebase its process-local revision;
      ;; it never reconstructs or revalidates snapshot entries.
      (let [candidate
            (assoc candidate
                   :content-revision
                   (inc (lifecycle-content-revision current)))]
        (if (compare-and-set! state current candidate)
          {:installed candidate :detached current}
          (recur @state))))))

(defn expire-cache!
  "Rotates the complete local cache/token lifecycle for one EACL client.

  The optional second argument is the coordinated lifecycle identity to use
  across processes after a restore. Without it, a fresh process-local UUID is
  installed. In-flight requests retain their captured old lifecycle."
  ([client]
   (expire-cache! client (str (random-uuid))))
  ([client source-lifecycle]
   (causal-token/validate-source-lifecycle! source-lifecycle)
   (let [runtime (:runtime client)
         config (runtime-cache-lifecycle-config runtime)
         rotation
         (rotate-runtime-cache-lifecycle!
          runtime
          (fn [current]
            (fresh-runtime-cache-lifecycle
             config
             source-lifecycle
             (inc (lifecycle-content-revision current)))))]
     (accumulate-detached-cache-counters!
      runtime (:detached rotation))
     (record-runtime-cache-expiration!
      runtime (:detached rotation)))
   nil))

(defn clear-answer-cache!
  "Evicts authorization answers, exact denotations, and resumable page state.

  Unlike `expire-cache!`, this is not a source-lifecycle rotation: it retains
  the client's derived schema artifacts, sealed plans, signing
  keys, and cursor codec artifacts. It is therefore suitable for operational
  answer-cache testing and capacity management, but MUST NOT be used after a
  restore, history replacement, or unsupported authorization mutation."
  [client]
  (let [runtime (:runtime client)
        config (runtime-cache-lifecycle-config runtime)
        rotation
        (rotate-runtime-cache-lifecycle!
         runtime
         (fn [current]
           (narrow-runtime-cache-lifecycle
            config current (inc (lifecycle-content-revision current)))))]
    (accumulate-detached-cache-counters!
     runtime (:detached rotation))
    (record-runtime-cache-expiration!
     runtime (:detached rotation)))
  nil)

(defn cache-stats
  "Returns private completed-cache counters for one EACL client."
  [client]
  (let [opts (client-options client)
        basis-store (:basis-cache-store opts)
        continuation-store (:continuation-cache-store opts)
        structural-stats
        (some-> (:derived-schema-caches opts) derived-schema/stats)]
    (cond->
     (assoc (merge-cache-counters
             (if basis-store
               (cache/basis-cache-stats basis-store)
               {:disabled? true})
            (or (some-> (::runtime-cache-lifecycle-metrics
                          (:runtime client))
                         deref)
                 {}))
            :structural-metrics
            (or structural-stats {:entry-count 0 :max-entries 0}))
      continuation-store
      (assoc :continuations
             (continuation/stats continuation-store)))))

(defn- require-cache-client-options!
  [client operation]
  (when-not (instance? Acl client)
    (throw
     (ex-info (str operation " requires an EACL client.")
              {:type :eacl/invalid-client
               :eacl/error :eacl/invalid-client})))
  (client-options client))

(defn export-cache-snapshot
  "Exports a count-bounded process-neutral authorization-cache v2 value.

  The host MUST authenticate and encoded-size-bound any externally persisted
  representation. Continuations, cursors, metrics, backend snapshots, and
  process-local identity tokens are excluded."
  [client bounds]
  (let [opts (require-cache-client-options! client "export-cache-snapshot")]
    (if-let [store (:basis-cache-store opts)]
      (cache/export-basis-snapshot store bounds)
      {:type :eacl/cache-disabled
       :eacl/error :eacl/cache-disabled
       :disabled? true
       :entry-count 0})))

(defn restore-cache-snapshot!
  "Atomically restores an already authenticated and decoded cache snapshot.

  External bytes MUST be authenticated and encoded-size-bound before decoding
  because this function's first argument is a trusted immutable value. Restore
  never selects a backend basis or alters source freshness."
  [client snapshot bounds]
  (let [opts (require-cache-client-options! client "restore-cache-snapshot!")
        runtime (:runtime client)]
    (if (:basis-cache-store opts)
      (let [state (:runtime-lifecycle-state runtime)
            captured @state
            config (runtime-cache-lifecycle-config runtime)
            candidate
            (fresh-runtime-cache-lifecycle
             config
             (:source-lifecycle captured)
             (lifecycle-content-revision captured))
            result
            (cache/restore-basis-snapshot!
             (:basis-cache-store candidate) snapshot bounds)]
        (let [installation
              (install-restored-runtime-cache-lifecycle!
               runtime captured candidate)]
          (accumulate-detached-cache-counters!
           runtime (:detached installation)))
        result)
      {:type :eacl/cache-disabled
       :eacl/error :eacl/cache-disabled
       :disabled? true
       :restored? false})))

(defn cache-content-revision
  "Returns a conservative process-local dirty revision for authorization content.

  It advances for mapping publication or eviction and for explicit clear,
  expiry, or restore, but not for database writes, lookup-only metrics, or LRU
  touches. It may advance for a process-local managed-to-exact promotion that
  portable export omits. Values are not comparable across processes."
  [client]
  (let [opts (require-cache-client-options! client "cache-content-revision")
        lifecycle (:runtime-cache-lifecycle opts)]
    (if (:basis-cache-store opts)
      (lifecycle-content-revision lifecycle)
      {:type :eacl/cache-disabled
       :eacl/error :eacl/cache-disabled
       :disabled? true})))

(defn refresh-metrics!
  "Drops currently resident derived schema/plan artifacts without backend mutation.

  This is a point-in-time retention reset, not a quiescence barrier: a
  concurrent request that already captured the same validated store may
  publish an artifact immediately afterward. Returned statistics are a
  contemporaneous snapshot, and this timing never affects authorization.

  `:eager? true` immediately rereads and validates the bounded permission
  schema, repopulating structural artifacts for the current generation."
  ([client] (refresh-metrics! client {}))
  ([client {:keys [eager?]
            :or {eager? false} :as opts}]
   (when-not (instance? Acl client)
     (typed-capability-error! :metrics :non-eacl))
   (when-let [unknown
              (seq (remove #{:eager?} (keys opts)))]
     (throw (ex-info "Unknown metric refresh option."
                     {:type :eacl/invalid-config
                      :eacl/error :eacl/invalid-config
                      :unknown-keys (vec unknown)})))
   (when-not (boolean? eager?)
     (throw (ex-info "Metric refresh :eager? option must be boolean."
                     {:type :eacl/invalid-config
                      :eacl/error :eacl/invalid-config
                      :key :eager?
                      :value eager?})))
   (let [store (:derived-schema-caches (client-options client))]
     (some-> store derived-schema/clear!)
     (when eager?
       (eacl/read-schema client {}))
     {:structural-refreshed? true
      :structural-metrics
      (or (some-> store derived-schema/stats)
          {:entry-count 0 :max-entries 0})})))

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
    :proof-contract-reporter
    :recursive-traversal-limits
    :expression-limits
    :permission-tree-limits
    :security-key
    :security-keyring
    :security-kid
    :token-ttl-seconds
    :source-lifecycle
    :adapter-fingerprint
    :adapter-deterministic?
    :identity-immutable?
    :consistency-sync-timeout-ms
    :execution-timeout-ms
    :aggregate-limits
    :service-admission
    :read-only?})

(defn- valid-security-kid?
  [kid]
  (or (keyword? kid)
      (and (string? kid) (not (empty? kid)))))

(defn- normalize-security-root-keyring
  [config-opts security-key security-keyring security-kid]
  (let [key-present? (contains? config-opts :security-key)
        keyring-present? (contains? config-opts :security-keyring)
        kid-present? (contains? config-opts :security-kid)
        current-kid (if kid-present? security-kid :default)
        invalid!
        (fn [message data cause]
          (throw
           (ex-info
            message
            (merge {:type :eacl/invalid-config
                    :eacl/error :eacl/invalid-config}
                   data)
            cause)))]
    (when (and key-present? keyring-present?)
      (invalid!
       "EACL Config Error: supply only one of :security-key or :security-keyring."
       {:conflicting-keys [:security-key :security-keyring]}
       nil))
    (when (and kid-present? (not (valid-security-kid? current-kid)))
      (invalid!
       "EACL Config Error: :security-kid must be a non-empty string or keyword."
       {:key :security-kid :value current-kid}
       nil))
    (let [root-keyring
          (try
            (cond
              keyring-present?
              (do
                (when-not (and (map? security-keyring)
                               (seq security-keyring))
                  (invalid!
                   "EACL Config Error: :security-keyring must be a non-empty map."
                   {:key :security-keyring :value security-keyring}
                   nil))
                (reduce-kv
                 (fn [result kid key-material]
                   (when-not (valid-security-kid? kid)
                     (invalid!
                      "EACL Config Error: security key IDs must be non-empty strings or keywords."
                      {:key :security-keyring :security-kid kid}
                      nil))
                   (assoc result kid (secure/normalize-key key-material)))
                 {}
                 security-keyring))

              key-present?
              {current-kid (secure/normalize-key security-key)}

              :else
              (do
                (secure/warn-defaulted-token-key!)
                {:default secure/default-root-key}))
            (catch #?(:clj Exception :cljs :default) error
              (if (= :eacl/invalid-config (:type (ex-data error)))
                (throw error)
                (invalid!
                 "EACL Config Error: security key material is invalid."
                 {:key (if keyring-present? :security-keyring :security-key)
                  :format-reason (:reason (ex-data error))}
                 error))))]
      (when-not (get root-keyring current-kid)
        (invalid!
         "EACL Config Error: :security-kid is absent from :security-keyring."
         {:key :security-kid
          :value current-kid}
         nil))
      {:current-kid current-kid
       :root-keyring root-keyring})))

(defn make-client
  "Builds the shared authorization acl over one backend api map.

  Validates the uniform option surface, assembles the client-private caches,
  and returns an Acl. Managed reuse is automatic when the
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
           proof-contract-reporter
           recursive-traversal-limits
           expression-limits
           permission-tree-limits
           security-key
           security-keyring
           security-kid
           token-ttl-seconds
           source-lifecycle
           adapter-fingerprint
           adapter-deterministic?
           identity-immutable?
           consistency-sync-timeout-ms
           execution-timeout-ms
           aggregate-limits
           service-admission
           read-only?]
    :or   {object-id->lookup-ref  (fn [obj-id] [:eacl/id obj-id])
           internal-cursor->spice default-internal-cursor->spice
           spice-cursor->internal default-spice-cursor->internal}}]
  (let [known-client-opt-keys
        (into base-client-opt-keys (:extra-client-opt-keys api))]
    (when-let [unknown-keys (seq (remove known-client-opt-keys (keys config-opts)))]
      (throw (ex-info (str "EACL Config Error: unknown make-client option(s) " (pr-str (vec unknown-keys))
                           ". Known options: " (pr-str (vec (sort known-client-opt-keys))) ".")
                      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                       :unknown-keys (vec unknown-keys)
                       :known-keys known-client-opt-keys}))))
  (when (and (contains? config-opts :adapter-deterministic?)
             (not (boolean? adapter-deterministic?)))
    (throw (ex-info "EACL Config Error: :adapter-deterministic? must be boolean."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :key :adapter-deterministic?
                     :value adapter-deterministic?})))
  (when (and (contains? config-opts :identity-immutable?)
             (not (boolean? identity-immutable?)))
    (throw
     (ex-info
      "EACL Config Error: :identity-immutable? must be boolean."
      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
       :key :identity-immutable?
       :value identity-immutable?})))
  (when-not (or (nil? proof-contract-reporter)
                (fn? proof-contract-reporter))
    (throw
     (ex-info "EACL Config Error: :proof-contract-reporter must be a function."
              {:type :eacl/invalid-config
               :eacl/error :eacl/invalid-config
               :key :proof-contract-reporter
               :value proof-contract-reporter})))
  (when adapter-fingerprint
    (try
      (secure/encode-canonical adapter-fingerprint)
      (catch #?(:clj Exception :cljs :default) error
        (throw (ex-info "EACL Config Error: :adapter-fingerprint must be portable canonical data."
                        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                         :key :adapter-fingerprint}
                        error)))))
  (when (and (contains? config-opts :token-ttl-seconds)
             (not (and (integer? token-ttl-seconds)
                       (pos? token-ttl-seconds))))
    (throw (ex-info "EACL Config Error: :token-ttl-seconds must be positive."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :key :token-ttl-seconds
                     :value token-ttl-seconds})))
  (when (and (contains? config-opts :cursor-ttl-seconds)
             (not (and (integer? cursor-ttl-seconds)
                       (pos? cursor-ttl-seconds)
                       (<= cursor-ttl-seconds
                           backend/maximum-exact-integer))))
    (throw
     (ex-info
      "EACL Config Error: :cursor-ttl-seconds must be a positive portable exact integer."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :cursor-ttl-seconds
       :value cursor-ttl-seconds
       :maximum backend/maximum-exact-integer})))
  (when source-lifecycle
    (try
      (causal-token/validate-source-lifecycle! source-lifecycle)
      (catch #?(:clj Exception :cljs :default) error
        (throw
         (ex-info
          "EACL Config Error: :source-lifecycle must be bounded portable canonical data."
          {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
           :key :source-lifecycle
           :value source-lifecycle}
          error)))))
  (when (and (contains? config-opts :consistency-sync-timeout-ms)
             (not (and (integer? consistency-sync-timeout-ms)
                       (pos? consistency-sync-timeout-ms))))
    (throw (ex-info "EACL Config Error: :consistency-sync-timeout-ms must be positive."
                    {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
                     :key :consistency-sync-timeout-ms
                     :value consistency-sync-timeout-ms})))
  (when (and (contains? config-opts :execution-timeout-ms)
             (not (and (integer? execution-timeout-ms)
                       (pos? execution-timeout-ms)
                       (<= execution-timeout-ms
                           execution/maximum-execution-timeout-ms))))
    (throw
     (ex-info
      "EACL Config Error: :execution-timeout-ms must be a positive integer within the supported range."
      {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
       :key :execution-timeout-ms
       :value execution-timeout-ms
       :maximum-timeout-ms execution/maximum-execution-timeout-ms})))
  (when-not (or (nil? read-only?) (boolean? read-only?))
    (throw
     (ex-info
      "EACL Config Error: :read-only? must be boolean."
      {:type :eacl/invalid-config
       :eacl/error :eacl/invalid-config
       :key :read-only?
       :value read-only?})))
  (let [source-lifecycle (or source-lifecycle "eacl/initial")
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
        {:keys [current-kid root-keyring]}
        (normalize-security-root-keyring
         config-opts security-key security-keyring security-kid)
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
        immutable-identity-contract?
        (if (contains? config-opts :identity-immutable?)
          (true? identity-immutable?)
          (not custom-codec?))
        proof-equivalent-cursors?
        (and managed-cache-eligible? immutable-identity-contract?)
        runtime-lifecycle-state
        (atom nil)
        runtime-cache-lifecycle-config
        {:cache-option cache
         :proof-contract-reporter proof-contract-reporter
         :derived-schema-store-factory derived-schema/store
         :runtime-lifecycle-state runtime-lifecycle-state}
        initial-runtime-cache-lifecycle
        (fresh-runtime-cache-lifecycle
         runtime-cache-lifecycle-config source-lifecycle 0)
        _
        (reset! runtime-lifecycle-state initial-runtime-cache-lifecycle)
        basis-cache-store
        (:basis-cache-store initial-runtime-cache-lifecycle)
        cursor-codec-cache
        (:cursor-codec-cache initial-runtime-cache-lifecycle)
        cursor-construction-cache
        (:cursor-construction-cache initial-runtime-cache-lifecycle)
        entid->object-id (or entid->object-id
                             (:default-entid->object-id api))
        base-opts
        (merge
         (select-keys config-opts
                      (:extra-client-opt-keys api))
         {:object-id->lookup-ref object-id->lookup-ref
          :derived-schema-caches
          (:derived-schema-caches initial-runtime-cache-lifecycle)
          :adapter-fingerprint
          (or adapter-fingerprint
              {:backend (:backend-id api)
               :adapter-version backend/adapter-version
               :recursive-traversal-limits
               recursive-traversal-limits
               :codec
               (if custom-codec?
                 [:custom-unfingerprinted codec-instance-id]
                 (if immutable-identity-contract?
                   :eacl-id-immutable-v1
                   :eacl-id-current-v2))})
          :adapter-deterministic?
          (if custom-codec?
            (true? adapter-deterministic?)
            true)
          :identity-contract
          (if immutable-identity-contract?
            :selected-internal/immutable-external-injective-v3
            :selected-internal/current-external-injective-v2)
          :entid->object-id entid->object-id
          :object-id->entid object-id->entid
          :cursor-ttl-seconds cursor-ttl-seconds
          :format-options format-options
          :source-lifecycle source-lifecycle
          :runtime-lifecycle-state runtime-lifecycle-state
          :native-source-id native-source-id
          :decision-kernel production-kernel/default-selection
          :consistency-sync-timeout-ms
          (or consistency-sync-timeout-ms 30000)
          :execution-timeout-ms
          (or execution-timeout-ms
              execution/default-execution-timeout-ms)
          :aggregate-limits
          (batch/normalize-client-limits aggregate-limits)
          :token-ttl-seconds
          (or token-ttl-seconds
              causal-token/default-token-ttl-seconds)
          :basis-cache-store
          basis-cache-store
          :proof-contract-reporter proof-contract-reporter
          :continuation-cache-store
          (:continuation-cache-store initial-runtime-cache-lifecycle)
          :cursor-codec-cache cursor-codec-cache
          :cursor-construction-cache cursor-construction-cache
          :managed-cache-enabled? managed-cache-eligible?
          :proof-equivalent-cursors? proof-equivalent-cursors?
          :recursive-traversal-limits
          (engine/normalize-recursive-traversal-limits
           recursive-traversal-limits)
          :expression-limits
          (expression-policy/normalize-client-limits expression-limits)
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
        adapter-constructor (:basis-adapter api)
        adapter-config-keys (:basis-adapter-config-keys api)
        _ (when-not (and (fn? adapter-constructor)
                         (set? adapter-config-keys))
            (throw
             (ex-info
              "Backend API must declare a basis-adapter constructor and closed configuration keys."
              {:type :eacl/invalid-backend-api
               :eacl/error :eacl/invalid-backend-api
               :backend (:backend-id api)
               :missing
               (cond
                 (not (fn? adapter-constructor)) :basis-adapter
                 :else :basis-adapter-config-keys)})))
        source-constructor (:source api)
        _ (when-not (fn? source-constructor)
            (throw
             (ex-info
              "Backend API must provide a basis-source constructor."
              {:type :eacl/invalid-backend-api
               :eacl/error :eacl/invalid-backend-api
               :backend (:backend-id api)
               :missing :source})))
        source (source-constructor conn base-opts)
        ;; Stable-engine qualification uses only long-lived source metadata;
        ;; client construction does not retain or own a request snapshot.
        _ (physical/require-qualified-source-topology! source)
        runtime
        (map->Runtime
         (assoc (select-keys base-opts runtime-option-keys)
                ::runtime-cache-lifecycle-config
                runtime-cache-lifecycle-config
                ::runtime-cache-lifecycle-metrics
                (atom {:expirations 0 :restores 0})))
        writer (when-not read-only?
                 (make-writer-role
                  api conn source
                  (assoc base-opts :source source)
                  runtime))]
    (->Acl runtime source writer api)))
