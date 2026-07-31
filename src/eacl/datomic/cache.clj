(ns eacl.datomic.cache
  "Ephemeral, bounded cache primitives for EACL's Datomic client.

  Cache availability never changes a recomputable authorization answer. Store
  failures, eviction, and disabled caches fall back to indexed/traversal work
  against the selected live or historical Datomic value."
  (:import [java.util Iterator LinkedHashMap Map$Entry]))

(def cache-entry-version 2)
(def portable-value-version 1)

(defprotocol CacheStore
  (lookup [store k]
    "Returns the cached value for `k`, or nil after a miss/expiry.")
  (store! [store k value weight ttl-ms]
    "Admits `value` when it fits. Returns true when stored, false when rejected.")
  (evict! [store k]
    "Evicts one key. Returns true when an entry existed.")
  (clear! [store]
    "Evicts every entry.")
  (stats [store]
    "Returns store counters and current capacity use."))

(defprotocol CacheProvider
  (capabilities [store]
    "Returns provider capability keywords.

    :opaque-values permits process-local continuations, :portable-values
    permits the versioned scalar/collection format, :ttl promises provider
    expiry, and :namespaced-clear permits targeted cleanup.")
  (clear-namespace! [store namespace]
    "Best-effort removal of only one EACL namespace. Never means FLUSHDB.")
  (record-provider-error! [store operation kind]
    "Records a provider failure without changing authorization behavior."))

(extend-protocol CacheProvider
  Object
  (capabilities [_]
    ;; Existing custom CacheStore implementations predate capability
    ;; negotiation. Preserve their behavior; new portable providers should
    ;; implement CacheProvider explicitly.
    #{:opaque-values :portable-values :ttl})
  (clear-namespace! [_ _] false)
  (record-provider-error! [_ _ _] nil))

(def ^:private default-store-config
  {:max-weight (* 16 1024 1024)
   :max-entry-weight (* 4 1024 1024)
   :max-entries 1024
   :kind-max-weight {}
   :two-hit-kinds #{}
   :admission-entries 4096
   :clock #(System/currentTimeMillis)})

(defn- now-ms
  [config]
  ((:clock config)))

(defn- expired?
  [now {:keys [expires-at]}]
  (<= expires-at now))

(defn- update-metric
  [state kind metric]
  (cond-> (update state metric (fnil inc 0))
    kind (update-in [:by-kind kind metric] (fnil inc 0))))

(defn- remove-entry!
  [^LinkedHashMap entries state k reason]
  (when-let [{:keys [weight kind]} (.remove entries k)]
    (swap! state
           (fn [s]
             (-> s
                 (update :weight - weight)
                 (update :entries-by-kind
                         (fn [entries-by-kind]
                           (let [n (dec (get entries-by-kind kind 1))]
                             (if (pos? n)
                               (assoc entries-by-kind kind n)
                               (dissoc entries-by-kind kind)))))
                 (update :weight-by-kind
                         (fn [weight-by-kind]
                           (let [n (- (get weight-by-kind kind 0) weight)]
                             (if (pos? n)
                               (assoc weight-by-kind kind n)
                               (dissoc weight-by-kind kind)))))
                 (update-metric kind reason))))
    true))

(defn- evict-to-capacity!
  [^LinkedHashMap entries state {:keys [max-weight max-entries]}]
  (loop []
    (when (or (> (.size entries) max-entries)
              (> (:weight @state) max-weight))
      (let [^Iterator iterator (.iterator (.entrySet entries))]
        (when (.hasNext iterator)
          (let [^Map$Entry map-entry (.next iterator)
                {:keys [weight kind]} (.getValue map-entry)]
            (.remove iterator)
            (swap! state
                   (fn [s]
                     (-> s
                         (update :weight - weight)
                         (update :entries-by-kind update kind (fnil dec 1))
                         (update :entries-by-kind
                                 #(if (pos? (get % kind 0)) % (dissoc % kind)))
                         (update :weight-by-kind update kind (fnil - 0) weight)
                         (update :weight-by-kind
                                 #(if (pos? (get % kind 0)) % (dissoc % kind)))
                         (update-metric kind :evictions))))
            (recur)))))))

(defn- entry-kind
  [value]
  (or (:eacl.cache/kind value) :untyped))

(defn- frequency-admitted?
  [^LinkedHashMap admission config k kind existing?]
  (if (or existing? (not (contains? (:two-hit-kinds config) kind)))
    true
    (let [candidate [kind (hash k)]
          sightings (inc (long (or (.get admission candidate) 0)))]
      (.put admission candidate sightings)
      (while (> (.size admission) (:admission-entries config))
        (let [^Iterator iterator (.iterator (.entrySet admission))]
          (when (.hasNext iterator)
            (.next iterator)
            (.remove iterator))))
      (>= sightings 2))))

(defn- within-kind-capacity?
  [state config kind weight existing]
  (if-let [kind-limit (get (:kind-max-weight config) kind)]
    (let [existing-weight (if (= kind (:kind existing))
                            (:weight existing)
                            0)]
      (<= (+ (- (get-in state [:weight-by-kind kind] 0)
                existing-weight)
             weight)
          kind-limit))
    true))

(defrecord LocalStore [^LinkedHashMap entries ^LinkedHashMap admission state config]
  CacheStore
  (lookup [_ k]
    ;; Only the map access and weight accounting need the monitor. The hit/miss
    ;; counters are advanced after releasing it: a swap! on the shared metrics
    ;; atom is a CAS loop plus several map allocations, and running it inside
    ;; the critical section lengthened the one lock every concurrent
    ;; authorization call contends for.
    (let [now (now-ms config)
          [hit? kind value]
          (locking entries
            (if-let [{:keys [value kind] :as entry} (.get entries k)]
              (if (expired? now entry)
                (do
                  (remove-entry! entries state k :expirations)
                  [false kind nil])
                [true kind value])
              [false :unknown nil]))]
      (swap! state update-metric kind (if hit? :hits :misses))
      value))

  (store! [_ k value weight ttl-ms]
    (let [{:keys [max-entry-weight]} config
          kind (entry-kind value)]
      (if (or (nil? value)
              (not (integer? weight))
              (not (pos? weight))
              (> weight max-entry-weight)
              (not (integer? ttl-ms))
              (not (pos? ttl-ms)))
        (do
          (swap! state update-metric kind :rejections)
          false)
        (locking entries
          (let [now (now-ms config)
                existing (.get entries k)
                existing (if (and existing (expired? now existing))
                           (do
                             (remove-entry! entries state k :expirations)
                             nil)
                           existing)]
            (if (and (frequency-admitted? admission config k kind (some? existing))
                     (within-kind-capacity? @state config kind weight existing))
              (do
                (remove-entry! entries state k :replacements)
                (.put entries k {:value value
                                 :weight weight
                                 :kind kind
                                 :expires-at (+ now ttl-ms)})
                (swap! state
                       (fn [s]
                         (-> s
                             (update :weight + weight)
                             (update-in [:entries-by-kind kind] (fnil inc 0))
                             (update-in [:weight-by-kind kind] (fnil + 0) weight)
                             (update-metric kind :puts))))
                (evict-to-capacity! entries state config)
                (boolean (.containsKey entries k)))
              (do
                (swap! state update-metric kind :rejections)
                false)))))))

  (evict! [_ k]
    (locking entries
      (boolean (remove-entry! entries state k :manual-evictions))))

  (clear! [_]
    (locking entries
      (.clear entries)
      (.clear admission)
      (swap! state assoc
             :weight 0
             :entries-by-kind {}
             :weight-by-kind {})
      nil))

  (stats [_]
    (locking entries
      (assoc @state
             :entries (.size entries)
             :max-weight (:max-weight config)
             :max-entry-weight (:max-entry-weight config)
             :max-entries (:max-entries config)
             :kind-max-weight (:kind-max-weight config))))

  CacheProvider
  (capabilities [_]
    #{:opaque-values :portable-values :ttl :namespaced-clear})

  (clear-namespace! [_ namespace]
    (locking entries
      (let [ks (->> (.iterator (.entrySet entries))
                    iterator-seq
                    (keep (fn [^Map$Entry map-entry]
                            (when (= namespace
                                     (get-in (.getValue map-entry)
                                             [:value :eacl.cache/namespace]))
                              (.getKey map-entry))))
                    vec)]
        (doseq [k ks]
          (remove-entry! entries state k :manual-evictions))
        (count ks))))

  (record-provider-error! [_ operation kind]
    (swap! state
           (fn [s]
             (-> s
                 (update-metric kind :provider-errors)
                 (update-in [:provider-errors-by-operation operation]
                            (fnil inc 0)))))
    nil))

(defn local-store
  "Creates a bounded access-ordered local cache.

  Weight is an admission unit supplied by EACL's entry type; current built-in
  entries use conservative retained-work estimates rather than JVM object
  instrumentation."
  ([]
   (local-store {}))
  ([config]
   (let [{:keys [max-weight max-entry-weight max-entries kind-max-weight
                 two-hit-kinds admission-entries clock] :as config'}
         (merge default-store-config config)]
     (when-not (and (integer? max-weight)
                    (pos? max-weight)
                    (integer? max-entry-weight)
                    (pos? max-entry-weight)
                    (<= max-entry-weight max-weight)
                    (integer? max-entries)
                    (pos? max-entries)
                    (map? kind-max-weight)
                    (every? (fn [[kind n]]
                              (and (keyword? kind)
                                   (integer? n)
                                   (pos? n)
                                   (<= n max-weight)))
                            kind-max-weight)
                    (set? two-hit-kinds)
                    (every? keyword? two-hit-kinds)
                    (integer? admission-entries)
                    (pos? admission-entries)
                    (fn? clock))
       (throw (ex-info "Invalid EACL cache capacity."
                       {:type :eacl/invalid-config
                        :cache config'})))
     (->LocalStore (LinkedHashMap. 16 0.75 true)
                   (LinkedHashMap. 16 0.75 true)
                   (atom {:weight 0
                          :entries-by-kind {}
                          :weight-by-kind {}
                          :by-kind {}
                          :hits 0
                          :misses 0
                          :puts 0
                          :evictions 0
                          :expirations 0
                          :rejections 0
                          :provider-errors 0
                          :provider-errors-by-operation {}})
                   config'))))

(defn entry
  ([cache-key kind value]
   (entry :default cache-key kind value))
  ([namespace cache-key kind value]
   {:eacl.cache/version cache-entry-version
    :eacl.cache/portable-version portable-value-version
    :eacl.cache/namespace namespace
    :eacl.cache/key cache-key
    :eacl.cache/kind kind
    :eacl.cache/value value}))

(defn entry-value
  "Returns a matching wrapped value or nil. Cache providers are trusted for
  values stored under a valid wrapper; mismatched versions/keys/kinds miss."
  [cached cache-key kind valid-value?]
  (when (and (map? cached)
             (= cache-entry-version (:eacl.cache/version cached))
             (= portable-value-version (:eacl.cache/portable-version cached))
             (= cache-key (:eacl.cache/key cached))
             (= kind (:eacl.cache/kind cached))
             (valid-value? (:eacl.cache/value cached)))
    (:eacl.cache/value cached)))

(defn safe-record-provider-error!
  "Best-effort provider telemetry. Observability cannot replace cache fallback."
  [store operation kind]
  (when store
    (try
      (record-provider-error! store operation kind)
      (catch Exception _
        nil))))

(defn safe-capabilities
  "Returns provider capabilities, or the conservative empty set on failure."
  [store]
  (if store
    (try
      (let [result (capabilities store)]
        (if (set? result) result #{}))
      (catch Exception _
        (safe-record-provider-error! store :capabilities :unknown)
        #{}))
    #{}))

(defn safe-evict!
  "Best-effort single-entry eviction."
  [store k]
  (boolean
   (when store
     (try
       (evict! store k)
       (catch Exception _
         (safe-record-provider-error! store :evict :unknown)
         false)))))

(defn safe-lookup
  "Cache lookup whose failure is always a miss."
  [store k]
  (when store
    (try
      (lookup store k)
      (catch Exception _
        (safe-record-provider-error! store :lookup :unknown)
        nil))))

(defn safe-store!
  "Best-effort cache publication. Failures never affect authorization output."
  [store k value weight ttl-ms]
  (boolean
   (when store
     (try
      (store! store k value weight ttl-ms)
      (catch Exception _
        (safe-record-provider-error! store :store (entry-kind value))
        false)))))

(defn safe-entry-value
  [store cache-key kind valid-value?]
  (when store
    (let [value
          (try
            (some-> (lookup store cache-key)
                    (entry-value cache-key kind valid-value?))
            (catch Exception _
              (safe-record-provider-error! store :lookup kind)
              nil))]
      ;; CacheStore/lookup predates typed requests, so the built-in store can
      ;; only infer a hit's kind. The typed wrapper records the requested kind
      ;; for misses without double-counting the total miss metric.
      (when (and (nil? value)
                 (instance? LocalStore store))
        (try
          (swap! (:state store)
                 update-in [:by-kind kind :misses] (fnil inc 0))
          (catch Exception _
            nil)))
      value)))

(defn portable-value?
  "True for the dependency-free cache interchange format.

  Records, lazy sequences, functions, Datomic DB values and engine objects are
  deliberately rejected."
  [value]
  (letfn [(portable? [x]
            (cond
              (or (nil? x)
                  (string? x)
                  (keyword? x)
                  (symbol? x)
                  (number? x)
                  (boolean? x)
                  (uuid? x)) true
              (record? x) false
              (map? x) (and (every? portable? (keys x))
                            (every? portable? (vals x)))
              (vector? x) (every? portable? x)
              (set? x) (every? portable? x)
              (list? x) (every? portable? x)
              :else false))]
    (portable? value)))

(defn compatible-entry?
  [store kind value]
  (let [provider-capabilities (safe-capabilities store)]
    (cond
      (contains? provider-capabilities :opaque-values) true
      (not (contains? provider-capabilities :portable-values)) false
      (= :recursive-continuation kind) false
      :else (portable-value? value))))

(defn- estimated-key-weight
  "Conservative retained-shape estimate for cache keys. This is deliberately
  allocation-free: building a serialized copy merely to size an untrusted
  request could itself create the memory spike the bound is meant to avoid."
  [cache-key]
  (letfn [(estimate [x]
            (cond
              (nil? x) 8
              (string? x) (+ 40 (* 2 (count x)))
              (keyword? x) (+ 40
                              (* 2 (count (name x)))
                              (* 2 (count (or (namespace x) ""))))
              (number? x) 24
              (boolean? x) 8
              (map? x) (reduce-kv (fn [n k v]
                                    (+ n 32 (estimate k) (estimate v)))
                                  64
                                  x)
              (set? x) (reduce (fn [n v] (+ n 24 (estimate v))) 64 x)
              (sequential? x) (reduce (fn [n v] (+ n 16 (estimate v))) 48 x)
              :else 128))]
    (estimate cache-key)))

(defn safe-store-entry!
  ([store cache-key kind value weight ttl-ms]
   (safe-store-entry! store :default cache-key kind value weight ttl-ms))
  ([store namespace cache-key kind value weight ttl-ms]
   (try
     (if (and store (compatible-entry? store kind value))
       (safe-store! store
                    cache-key
                    (entry namespace cache-key kind value)
                    ;; The key is retained both by the provider's map and by
                    ;; the self-validating entry wrapper.
                    (+ weight (* 2 (estimated-key-weight cache-key)))
                    ttl-ms)
       false)
     (catch Exception _
       (when store
         (safe-record-provider-error! store :admission kind))
       false))))
