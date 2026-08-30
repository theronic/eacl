(ns eacl.continuation
  "Adapter-neutral, client-private continuation storage.

  Public cursors authenticate query and snapshot lineage. Opaque traversal
  state stays in this bounded in-process store and is addressed by that
  authenticated lineage. Cache loss is always a performance miss: callers can
  deterministically replay the public boundary."
  (:require [eacl.backend.v8 :as backend]
            [eacl.cache.key :as cache-key]
            [eacl.cache.standard-lru :as lru]))

(def ^:private context-version 3)
(def ^:private default-max-entries 1024)

(defrecord BoundedContinuationStore
    [storage metrics max-entries telemetry-enabled?])

(defn store?
  [value]
  (instance? BoundedContinuationStore value))

(defn make-store
  ([]
   (make-store {}))
  ([options]
   (when-not (map? options)
     (throw
      (ex-info
       "Continuation store options must be a map."
       {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
        :options options})))
   (let [unknown-options
         (seq (remove #{:max-entries :telemetry?} (keys options)))
         max-entries (get options :max-entries default-max-entries)
         telemetry? (get options :telemetry? true)]
     (when unknown-options
       (throw
        (ex-info
         "Unknown continuation store options."
         {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
          :unknown-options (set unknown-options)})))
     (when-not (boolean? telemetry?)
       (throw
        (ex-info
         "Continuation store :telemetry? must be boolean."
         {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
          :telemetry? telemetry?})))
     (->BoundedContinuationStore
      (lru/store max-entries)
      (atom {:hits 0
             :misses 0
             :puts 0
             :publications 0
             :replacements 0
             :evictions 0
             :errors 0
             ;; Diagnostics only; standard LRU remains the retention
             ;; authority. Keeping this counter avoids an O(capacity) scan on
             ;; every publication merely to report sequential evictions.
             :resident-estimate 0
             :miss-reasons {}
             :by-kind {}})
      max-entries
      telemetry?))))

(defn- inc-metric
  [metrics kind metric]
  (-> metrics
      (update metric (fnil inc 0))
      (update-in [:by-kind kind metric] (fnil inc 0))))

(defn- metric!
  [store kind metric]
  (when (:telemetry-enabled? store)
    (swap! (:metrics store) inc-metric kind metric)))

(defn- miss!
  [store kind reason]
  (when (:telemetry-enabled? store)
    (swap!
     (:metrics store)
     (fn [metrics]
       (-> metrics
           (update :misses (fnil inc 0))
           (update-in [:miss-reasons reason] (fnil inc 0))
           (update-in [:by-kind kind :misses] (fnil inc 0))
           (update-in [:by-kind kind :miss-reasons reason]
                      (fnil inc 0)))))))

(defn- storage-key
  [kind key]
  (cache-key/domain-key :continuation [kind key]))

(defn- resident
  [store kind key]
  (lru/peek-entry (:storage store) (storage-key kind key)))

(defn- lookup!
  "Peeks without changing recency. Absence is counted immediately; a present
  entry becomes an LRU hit only after stable-page validates its authenticated
  ordinal and boundary."
  [store kind key]
  (try
    (let [{:keys [found? value]} (resident store kind key)]
      (if found?
        value
        (do
          (miss! store kind :absent)
          nil)))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      nil)))

(defn- checkpoint-hit!
  [store kind key expected-value]
  (try
    (when (lru/hit-if-value!
           (:storage store) (storage-key kind key) expected-value)
      (metric! store kind :hits)
      true)
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      false)))

(defn- checkpoint-miss!
  [store kind reason]
  (miss! store kind reason))

(defn- record-put!
  [store kind replaced? published?]
  (when (:telemetry-enabled? store)
    (swap!
     (:metrics store)
     (fn [metrics]
       (let [metrics (inc-metric metrics kind :puts)]
         (if-not published?
           metrics
           (let [metrics (inc-metric metrics kind :publications)]
             (if replaced?
               (inc-metric metrics kind :replacements)
               (if (>= (:resident-estimate metrics) (:max-entries store))
                 (inc-metric metrics kind :evictions)
                 (update metrics :resident-estimate inc)))))))))
  published?)

(defn- checkpoint-progress
  "Returns the total boundary progress used by latest-only publication.

  Ordinal is primary because it names the delivered semantic boundary;
  transitions break ties for the same boundary when traversal has advanced
  farther without emitting another value. This is deliberately a shallow
  ingress check: the stable-page engine constructs the opaque reducer state."
  [checkpoint]
  (let [ordinal (:ordinal checkpoint)
        transitions (get-in checkpoint [:state :transitions])]
    (when-not (and (map? checkpoint)
                   (contains? checkpoint :boundary)
                   (vector? (:pending checkpoint))
                   (map? (:state checkpoint))
                   (integer? ordinal)
                   (not (neg? ordinal))
                   (integer? transitions)
                   (not (neg? transitions)))
      (throw
       (ex-info
        "Continuation publication requires a completed checkpoint."
        {:type :eacl/internal-continuation-contract
         :eacl/error :eacl/internal-continuation-contract})))
    [ordinal transitions]))

(defn- progress-newer?
  [[candidate-ordinal candidate-transitions]
   [resident-ordinal resident-transitions]]
  (or (> candidate-ordinal resident-ordinal)
      (and (= candidate-ordinal resident-ordinal)
           (> candidate-transitions resident-transitions))))

(defn- put-latest-checkpoint!
  "Publishes only progress newer than the value currently resident at key.

  Progress comparison runs outside the standard-LRU CAS. The CAS receives an
  immutable expected value, so concurrent older and newer publishers retry
  until the resident mapping is absent, replaced by the candidate, or already
  at least as advanced."
  [store kind key checkpoint]
  (try
    (let [storage (:storage store)
          key (storage-key kind key)
          candidate-progress (checkpoint-progress checkpoint)]
      (loop []
        (let [{:keys [found? value]} (lru/peek-entry storage key)]
          (if found?
            (let [resident-progress (checkpoint-progress value)]
              (if (progress-newer? candidate-progress resident-progress)
                (if (lru/replace-if! storage key value checkpoint)
                  (record-put! store kind true true)
                  (recur))
                (record-put! store kind false false)))
            (if (lru/put-if-absent! storage key checkpoint)
              (record-put! store kind false true)
              (recur))))))
    (catch #?(:clj Exception :cljs :default) _
      (metric! store kind :errors)
      false)))

(defn stats
  [store]
  (assoc (dissoc @(:metrics store) :resident-estimate)
         :entries (lru/entry-count (:storage store))
         :max-entries (:max-entries store)
         :telemetry-enabled? (:telemetry-enabled? store)))

(defn validate-context!
  [context]
  (when-not
   (and
    (map? context)
    (false? (:required? context))
    (true? (:opaque-values? context))
    (every?
     #(fn? (get context %))
     [:get :hit! :miss! :put!]))
    (throw
     (ex-info
      "Continuation context does not satisfy the adapter-neutral contract."
      {:type :eacl/internal-continuation-contract :eacl/error :eacl/internal-continuation-contract
       :context-keys (set (keys context))})))
  context)

(defn private-context
  "Builds engine callbacks scoped to one client, selected snapshot, and query.

  The store itself supplies client isolation. One full collision-checked scope
  value contains every cross-request semantic input, while the final edge/page
  key identifies the resumable frontier within that lineage."
  ([store adapter operation query-identity]
   (private-context store adapter operation query-identity {}))
  ([store adapter operation query-identity
    {:keys [request-lineage request-proof-frame populate-cache?]
     :or {populate-cache? true}}]
   (when store
     (let [basis-identity (:basis-identity request-proof-frame)
           derived-lineage
           (when basis-identity
             {:source-scope
              (select-keys basis-identity [:backend :source-id :branch])
              :source-lifecycle (:source-lifecycle basis-identity)})
           _
           (when (and request-lineage derived-lineage
                      (not= request-lineage derived-lineage))
             (throw
              (ex-info
               "Continuation lineage differs from its request proof frame."
               {:type :eacl/internal-continuation-contract
                :eacl/error :eacl/internal-continuation-contract
                :request-lineage request-lineage
                :derived-lineage derived-lineage})))
           lineage (or request-lineage derived-lineage)
           scope
           (when lineage
             ;; Keep the full collision-checked identity in the ordinary key.
             ;; The store is cleared during explicit lifecycle rotation, but
             ;; a late publisher is also isolated by this lineage.
             [context-version
              (backend/backend-id adapter)
              lineage
              (backend/fingerprint adapter)
              (backend/identity-contract adapter)
              operation
              query-identity])
          key-for (fn [key] [scope key])]
       (when scope
         (validate-context!
          {:required? false
           :opaque-values? true
           :get
           #(lookup! store :recursive-continuation
                     (key-for %))
           :hit!
           (fn [edge expected-value]
             (checkpoint-hit!
              store :recursive-continuation
              (key-for edge) expected-value))
           :miss!
           (fn [reason]
             (checkpoint-miss!
              store :recursive-continuation reason))
           :put!
           (fn [edge value]
             (and populate-cache?
                  (put-latest-checkpoint!
                   store :recursive-continuation
                   (key-for edge)
                   value)))}))))))
