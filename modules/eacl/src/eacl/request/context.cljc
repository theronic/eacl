(ns eacl.request.context
  "Opaque synchronous execution context for one selected authorization basis.

  The context owns snapshot cleanup and request-invariant state. It is never a
  portable value and must not escape its constructing thread or request."
  (:require [eacl.authorization.context :as caveat-context]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as counters]))

(def context-version 1)

(def ^:private context-input-keys
  #{:runtime
    :adapter
    :selected-snapshot
    :basis-identity
    :contract
    :caveat-context
    :derived-registry
    :counter-ledger
    :proof-diagnostic-fn
    :maximum-proof-relation-count})

(def ^:private memo-kinds
  #{:prepared-roots :dependency-proofs :cursor-proofs :decisions})

(def ^:private semantic-identity-keys
  (vec source/semantic-identity-keys))
(def ^:private speculative-identity-keys
  (conj semantic-identity-keys :speculative-id))

(defn- collect-unknown-context-key
  [unknown key _]
  (if (contains? context-input-keys key)
    unknown
    (conj (or unknown []) key)))

(defn- retain-map-containing-key
  [value key]
  (when (and value (contains? value key))
    value))

(defn- closed-key-shape?
  [value required-keys]
  (and (map? value)
       (= (count value) (count required-keys))
       (identical?
        value
        (reduce retain-map-containing-key value required-keys))))

(deftype ^:private RequestContext [state])

(defn context?
  [value]
  (instance? RequestContext value))

(defn- invalid-context!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl.request/invalid-context
            :eacl/error :eacl.request/invalid-context}
           data))))

(defn- state-of
  [context]
  (if (context? context)
    (.-state ^RequestContext context)
    (invalid-context! "Value is not an EACL request context."
                      {:value context})))

(defn- record!
  [ledger counter]
  ;; Public orchestration already binds the request's ledger. Avoid creating a
  ;; nested dynamic binding for the normal path while retaining the standalone
  ;; `make-context` contract for an explicitly supplied, unbound ledger.
  (if (identical? ledger counters/*ledger*)
    (counters/add! counter)
    (counters/call-with-ledger ledger #(counters/add! counter))))

(defn- context-closed!
  []
  (throw
   (ex-info
    "The request context has already been closed."
    {:type :eacl.request/context-closed
     :eacl/error :eacl.request/context-closed})))

(defn- context-close-in-progress!
  []
  (throw
   (ex-info
    "Request context cleanup is already in progress."
    {:type :eacl.request/context-close-in-progress
     :eacl/error :eacl.request/context-close-in-progress})))

(defn- context-thread-violation!
  [operation]
  (throw
   (ex-info
    "The request context escaped its owning thread."
    {:type :eacl.request/context-thread-violation
     :eacl/error :eacl.request/context-thread-violation
     :operation operation})))

#?(:clj
   (defn- require-owner-thread!
     [state operation]
     (when-not (identical? (:owner-thread state) (Thread/currentThread))
       (context-thread-violation! operation)))
   :cljs
   (defn- require-owner-thread!
     [_state _operation]
     nil))

(defn assert-open!
  "Fails when `context` is closed, closing, or used off its owner thread."
  [context]
  (let [state (state-of context)]
    (case @(:close-state state)
      :open nil
      :closing (context-close-in-progress!)
      :closed (context-closed!))
    (require-owner-thread! state :context-access)
    context))

(defn ^:no-doc active-state
  "Returns the already validated state for one synchronous internal request.

  The state may only be retained by the owning call stack.  Context closure is
  owner-thread confined, so one successful validation is sufficient until
  that stack returns; internal consumers must not publish or retain it."
  [context]
  (state-of (assert-open! context)))

(defn closed?
  [context]
  (= :closed @(:close-state (state-of context))))

(defn- validate-basis-identity!
  [basis-identity]
  (when-not
   (or (closed-key-shape? basis-identity semantic-identity-keys)
       (and (closed-key-shape? basis-identity speculative-identity-keys)
            (string? (:speculative-id basis-identity))
            (seq (:speculative-id basis-identity))))
    (invalid-context!
     "Request context basis identity must be the closed semantic identity."
     {:basis-identity basis-identity
      :expected-keys #{source/semantic-identity-keys
                       (set speculative-identity-keys)}})))

(defn- lineage-for-validated-basis
  [basis-identity]
  {:source-scope
   {:backend (:backend basis-identity)
    :source-id (:source-id basis-identity)
    :branch (:branch basis-identity)}
   :source-lifecycle (:source-lifecycle basis-identity)})

(defn lineage-for-basis
  "Returns the one history witness used by every cross-basis artifact."
  [basis-identity]
  (validate-basis-identity! basis-identity)
  (lineage-for-validated-basis basis-identity))

(defn- release-after-construction-failure!
  [selected ledger error]
  (when selected
    (try
      (when (source/release! selected)
        (when ledger
          (record! ledger :releases)))
      (catch #?(:clj Throwable :cljs :default) release-error
        (throw
         (ex-info
          "Request context construction and snapshot cleanup both failed."
          {:type :eacl/snapshot-release-failed
           :eacl/error :eacl/snapshot-release-failed
           :context-error (ex-data error)}
          release-error)))))
  (throw error))

(defn make-context
  "Constructs the opaque context for one already selected immutable adapter.

  Ownership of `:selected-snapshot`, when present, transfers immediately. A
  construction failure releases it before propagating. Schema generation,
  proof evidence, and the derived generation stay lazy until execution needs
  them; they share one schema-generation resolution."
  [{:keys [runtime adapter selected-snapshot basis-identity contract
           derived-registry counter-ledger proof-diagnostic-fn
           maximum-proof-relation-count caveat-context]
    :as input}]
  (let [selected
        (when (and (map? input)
                   (source/selected-basis? selected-snapshot))
          selected-snapshot)
        initial-ledger
        (when (map? input)
          (or counter-ledger
              (:request-counter-ledger runtime)
              counters/*ledger*))]
    (try
      (when-not (map? input)
        (invalid-context! "Request context input must be a map."
                          {:value input}))
      (when-let [unknown (reduce-kv collect-unknown-context-key nil input)]
        (invalid-context!
         "Request context input contains unknown fields."
         {:unknown-keys unknown
          :known-keys context-input-keys}))
      (when-not (map? runtime)
        (invalid-context! "Request context runtime must be a map."
                          {:runtime runtime}))
      (when-not (backend/adapter? adapter)
        (invalid-context! "Request context requires a validated adapter."
                          {:adapter adapter}))
      (when (and (some? selected-snapshot)
                 (not (source/selected-basis?
                       selected-snapshot)))
        (invalid-context!
         "Request context selected snapshot is invalid."
         {:selected-snapshot selected-snapshot}))
      (when-not (map? contract)
        (invalid-context! "Request context requires one execution contract."
                          {:contract contract}))
      (when (and (some? caveat-context) (not (caveat-context/prepared? caveat-context)))
        (invalid-context! "Request Caveat context must be prepared before selecting a basis." {}))
      (let [selected-adapter
            (when selected
              (source/adapter selected))
            _
            (when (and selected-adapter
                       (not (identical? adapter selected-adapter)))
              (invalid-context!
               "Request context adapter differs from its selected snapshot."
               {:backend (backend/backend-id adapter)}))
            selected-identity
            (when selected
              (source/semantic-identity selected))
            basis-identity (or basis-identity selected-identity)
            _ (validate-basis-identity! basis-identity)
            _
            (when (and selected-identity
                       (not= selected-identity basis-identity))
              (invalid-context!
               "Request context basis identity differs from acquisition."
               {:basis-identity basis-identity
                :selected-basis-identity selected-identity}))
            lineage (lineage-for-validated-basis basis-identity)
            registry
            (or derived-registry
                (:derived-schema-caches runtime)
                (derived-schema/store))
            ledger
            (or counter-ledger
                (:request-counter-ledger runtime)
                counters/*ledger*
                (counters/make-ledger))
            schema-generation-delay
            (delay
              (record! ledger :generation-reads)
              (backend/invoke adapter :schema-generation))
            proof-options
            (cond->
             {:schema-generation-fn
              #(force schema-generation-delay)
              :basis-identity basis-identity}
              proof-diagnostic-fn
              (assoc :diagnostic-fn proof-diagnostic-fn)
              maximum-proof-relation-count
              (assoc :maximum-relation-count
                     maximum-proof-relation-count))
            proof-frame-delay
            (delay (proof-frame/request-frame adapter proof-options))
            derived-delay
            (delay
              (engine/schema-cache-for!
               registry adapter basis-identity
               (force schema-generation-delay)))
            context
            (RequestContext.
             {:version context-version
              :runtime runtime
              :adapter adapter
              :selected-snapshot selected
              :ownership (if selected
                           (source/ownership selected)
                           :borrowed)
              :basis-identity basis-identity
              :lineage lineage
              :schema-generation-delay schema-generation-delay
              :contract contract
              :caveat-context caveat-context
              :proof-frame-delay proof-frame-delay
              :derived-delay derived-delay
              :memos-delay (delay (atom {}))
              :counter-ledger ledger
              :owner-thread #?(:clj (Thread/currentThread) :cljs nil)
              :close-state (atom :open)})]
        (record! ledger :context-constructions)
        context)
      (catch #?(:clj Throwable :cljs :default) error
        (release-after-construction-failure!
         selected initial-ledger error)))))

(defn runtime
  [context]
  (:runtime (state-of (assert-open! context))))

(defn adapter
  [context]
  (:adapter (state-of (assert-open! context))))

(defn ownership
  [context]
  (:ownership (state-of (assert-open! context))))

(defn basis-identity
  [context]
  (:basis-identity (state-of (assert-open! context))))

(defn lineage
  [context]
  (:lineage (state-of (assert-open! context))))

(defn contract
  [context]
  (:contract (state-of (assert-open! context))))

(defn schema-generation
  [context]
  (force (:schema-generation-delay
          (state-of (assert-open! context)))))

(defn proof-frame
  [context]
  (force (:proof-frame-delay (state-of (assert-open! context)))))

(defn derived
  [context]
  (force (:derived-delay (state-of (assert-open! context)))))

(defn counter-ledger
  [context]
  (:counter-ledger (state-of (assert-open! context))))

(defn- memoized-state!
  [state memo-kind key build]
  (let [memos (force (:memos-delay state))
        memo-key [memo-kind key]
        current @memos]
    (if (contains? current memo-key)
      (get current memo-key)
      (let [value (build)]
        (swap! memos
               #(if (contains? % memo-key)
                  %
                  (assoc % memo-key value)))
        (get @memos memo-key)))))

(defn ^:no-doc memoized-active-state!
  "Internal fast path after `active-state` validated ownership and lifecycle."
  [state memo-kind key build]
  (memoized-state! state memo-kind key build))

(defn memoized!
  "Returns the one request-local value for `memo-kind` and `key`."
  [context memo-kind key build]
  (when-not (contains? memo-kinds memo-kind)
    (invalid-context!
     "Unknown request-context memo kind."
     {:memo-kind memo-kind :known-memo-kinds memo-kinds}))
  (when-not (fn? build)
    (invalid-context! "Request-context memo builder must be a function."
                      {:memo-kind memo-kind :key key}))
  (memoized-state! (active-state context) memo-kind key build))

(defn call-with-context
  "Runs synchronous `f` with the context's contract and counter ledger bound."
  [context f]
  (when-not (fn? f)
    (invalid-context! "Request context callback must be a function." {}))
  (let [state (state-of (assert-open! context))
        ledger (:counter-ledger state)
        contract (:contract state)]
    (if (identical? ledger counters/*ledger*)
      (binding [execution/*contract* contract]
        (f context))
      (counters/call-with-ledger
       ledger
       #(binding [execution/*contract* contract]
          (f context))))))

(defn close!
  "Releases owned snapshot state once.

  Returns true for the successful cleanup call and false after closure. A
  failed provider release restores retryability."
  [context]
  (let [state (state-of context)]
    (require-owner-thread! state :context-close)
    (let [close-state (:close-state state)]
      (loop []
        (case @close-state
          :closed false
          :closing (context-close-in-progress!)
          :open
          (if (compare-and-set! close-state :open :closing)
            (try
              (when-let [selected (:selected-snapshot state)]
                (when (source/release! selected)
                  (record! (:counter-ledger state) :releases)))
              (reset! close-state :closed)
              true
              (catch #?(:clj Throwable :cljs :default) error
                (reset! close-state :open)
                (throw error)))
            (recur)))))))
