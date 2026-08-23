(ns eacl.request.context
  "Opaque synchronous execution context for one selected authorization basis.

  The context owns snapshot cleanup and request-invariant state. It is never a
  portable value and must not escape its constructing thread or request."
  (:require [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
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
    :derived-registry
    :counter-ledger
    :proof-diagnostic-fn
    :maximum-proof-relation-count})

(def ^:private memo-kinds
  #{:prepared-roots :dependency-proofs :cursor-proofs :decisions})

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
  (counters/call-with-ledger ledger #(counters/add! counter)))

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

(defn- require-owner-thread!
  [state operation]
  #?(:clj
     (when-not (identical? (:owner-thread state) (Thread/currentThread))
       (context-thread-violation! operation))
     :cljs nil))

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

(defn closed?
  [context]
  (= :closed @(:close-state (state-of context))))

(defn- validate-basis-identity!
  [basis-identity]
  (when-not (and (map? basis-identity)
                 (= snapshot-provider/semantic-identity-keys
                    (set (keys basis-identity))))
    (invalid-context!
     "Request context basis identity must be the closed semantic identity."
     {:basis-identity basis-identity
      :expected-keys snapshot-provider/semantic-identity-keys})))

(defn- release-after-construction-failure!
  [selected ledger error]
  (when selected
    (try
      (when (snapshot-provider/release! selected)
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
           maximum-proof-relation-count]
    :as input}]
  (let [selected
        (when (and (map? input)
                   (snapshot-provider/selected-snapshot? selected-snapshot))
          selected-snapshot)
        initial-ledger
        (when (map? input)
          (or counter-ledger
              (get-in input [:runtime :request-counter-ledger])
              counters/*ledger*))]
    (try
      (when-not (map? input)
        (invalid-context! "Request context input must be a map."
                          {:value input}))
      (when-let [unknown (seq (remove context-input-keys (keys input)))]
        (invalid-context!
         "Request context input contains unknown fields."
         {:unknown-keys (vec unknown)
          :known-keys context-input-keys}))
      (when-not (map? runtime)
        (invalid-context! "Request context runtime must be a map."
                          {:runtime runtime}))
      (when-not (backend/adapter? adapter)
        (invalid-context! "Request context requires a validated adapter."
                          {:adapter adapter}))
      (when (and (some? selected-snapshot)
                 (not (snapshot-provider/selected-snapshot?
                       selected-snapshot)))
        (invalid-context!
         "Request context selected snapshot is invalid."
         {:selected-snapshot selected-snapshot}))
      (when-not (map? contract)
        (invalid-context! "Request context requires one execution contract."
                          {:contract contract}))
      (let [selected-adapter
            (when selected
              (snapshot-provider/adapter selected))
            _
            (when (and selected-adapter
                       (not (identical? adapter selected-adapter)))
              (invalid-context!
               "Request context adapter differs from its selected snapshot."
               {:backend (backend/backend-id adapter)}))
            selected-identity
            (when selected
              (snapshot-provider/semantic-identity selected))
            basis-identity (or basis-identity selected-identity)
            _ (validate-basis-identity! basis-identity)
            _
            (when (and selected-identity
                       (not= selected-identity basis-identity))
              (invalid-context!
               "Request context basis identity differs from acquisition."
               {:basis-identity basis-identity
                :selected-basis-identity selected-identity}))
            registry
            (or derived-registry
                (:derived-schema-caches runtime)
                (atom {}))
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
              #(force schema-generation-delay)}
              proof-diagnostic-fn
              (assoc :diagnostic-fn proof-diagnostic-fn)
              maximum-proof-relation-count
              (assoc :maximum-relation-count
                     maximum-proof-relation-count))
            request-proof-frame
            (proof-frame/request-frame adapter proof-options)
            derived-delay
            (delay
              (engine/schema-cache-for!
               registry adapter (force schema-generation-delay)))
            context
            (RequestContext.
             {:version context-version
              :runtime runtime
              :adapter adapter
              :selected-snapshot selected
              :ownership (if selected
                           (snapshot-provider/ownership selected)
                           :borrowed)
              :basis-identity basis-identity
              :schema-generation-delay schema-generation-delay
              :contract contract
              :proof-frame request-proof-frame
              :derived-delay derived-delay
              :memos (zipmap memo-kinds (repeatedly #(atom {})))
              :counter-ledger ledger
              :publication-buffer (atom [])
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

(defn contract
  [context]
  (:contract (state-of (assert-open! context))))

(defn schema-generation
  [context]
  (force (:schema-generation-delay
          (state-of (assert-open! context)))))

(defn proof-frame
  [context]
  (:proof-frame (state-of (assert-open! context))))

(defn derived
  [context]
  (force (:derived-delay (state-of (assert-open! context)))))

(defn counter-ledger
  [context]
  (:counter-ledger (state-of (assert-open! context))))

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
  (let [memos (:memos (state-of (assert-open! context)))
        slot (get memos memo-kind)
        candidate (delay (build))
        selected
        (get
         (swap! slot
                #(if (contains? % key)
                   %
                   (assoc % key candidate)))
         key)]
    @selected))

(defn buffer-publication!
  "Buffers one valid artifact for publication after semantic success."
  [context publication]
  (swap! (:publication-buffer (state-of (assert-open! context)))
         conj publication)
  nil)

(defn take-publications!
  "Atomically drains and returns the buffered publication artifacts."
  [context]
  (let [buffer (:publication-buffer (state-of (assert-open! context)))]
    (loop []
      (let [current @buffer]
        (if (compare-and-set! buffer current [])
          current
          (recur))))))

(defn discard-publications!
  [context]
  (reset! (:publication-buffer (state-of (assert-open! context))) [])
  nil)

(defn call-with-context
  "Runs synchronous `f` with the context's contract and counter ledger bound."
  [context f]
  (when-not (fn? f)
    (invalid-context! "Request context callback must be a function." {}))
  (let [state (state-of (assert-open! context))]
    (counters/call-with-ledger
     (:counter-ledger state)
     #(binding [execution/*contract* (:contract state)]
        (f context)))))

(defn close!
  "Discards unpublished artifacts and releases owned snapshot state once.

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
            (do
              (reset! (:publication-buffer state) [])
              (try
                (when-let [selected (:selected-snapshot state)]
                  (when (snapshot-provider/release! selected)
                    (record! (:counter-ledger state) :releases)))
                (reset! close-state :closed)
                true
                (catch #?(:clj Throwable :cljs :default) error
                  (reset! close-state :open)
                  (throw error))))
            (recur)))))))
