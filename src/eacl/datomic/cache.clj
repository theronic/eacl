(ns eacl.datomic.cache
  "Ephemeral, bounded cache primitives for EACL's Datomic client.

  Cache availability is never part of an authorization answer. Store failures,
  eviction and disabled caches all fall back to the ordinary indexed/traversal
  implementation."
  (:import [java.util Iterator LinkedHashMap Map$Entry]
           [java.util.concurrent.locks ReentrantReadWriteLock Lock]))

(def cache-entry-version 1)

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

(defprotocol RelationshipCoordinator
  (generation
    [coordinator]
    [coordinator dependency-keys]
    "Returns the mutation clock, or the generation token for dependencies.")
  (with-read [coordinator f]
    "Runs `f` with an immutable coordinator snapshot inside the read barrier.")
  (with-mutation [coordinator f]
    "Runs `f` exclusively. `f` returns [value changed-dependency-keys]."))

(def ^:private default-store-config
  {:max-weight (* 16 1024 1024)
   :max-entry-weight (* 4 1024 1024)
   :max-entries 1024})

(defn- now-ms []
  (System/currentTimeMillis))

(defn- expired?
  [now {:keys [expires-at]}]
  (<= expires-at now))

(defn- remove-entry!
  [^LinkedHashMap entries state k reason]
  (when-let [{:keys [weight]} (.remove entries k)]
    (swap! state
           (fn [s]
             (-> s
                 (update :weight - weight)
                 (update reason (fnil inc 0)))))
    true))

(defn- expire-entries!
  [^LinkedHashMap entries state now]
  (let [^Iterator iterator (.iterator (.entrySet entries))]
    (loop []
      (when (.hasNext iterator)
        (let [^Map$Entry map-entry (.next iterator)
              {:keys [weight] :as entry} (.getValue map-entry)]
          (when (expired? now entry)
            (.remove iterator)
            (swap! state
                   (fn [s]
                     (-> s
                         (update :weight - weight)
                         (update :expirations (fnil inc 0))))))
          (recur))))))

(defn- evict-to-capacity!
  [^LinkedHashMap entries state {:keys [max-weight max-entries]}]
  (loop []
    (when (or (> (.size entries) max-entries)
              (> (:weight @state) max-weight))
      (let [^Iterator iterator (.iterator (.entrySet entries))]
        (when (.hasNext iterator)
          (let [^Map$Entry map-entry (.next iterator)
                {:keys [weight]} (.getValue map-entry)]
            (.remove iterator)
            (swap! state
                   (fn [s]
                     (-> s
                         (update :weight - weight)
                         (update :evictions (fnil inc 0)))))
            (recur)))))))

(defrecord LocalStore [^LinkedHashMap entries state config]
  CacheStore
  (lookup [_ k]
    (locking entries
      (let [now (now-ms)]
        (expire-entries! entries state now)
        (if-let [{:keys [value] :as entry} (.get entries k)]
          (if (expired? now entry)
            (do
              (remove-entry! entries state k :expirations)
              (swap! state update :misses (fnil inc 0))
              nil)
            (do
              (swap! state update :hits (fnil inc 0))
              value))
          (do
            (swap! state update :misses (fnil inc 0))
            nil)))))

  (store! [_ k value weight ttl-ms]
    (let [{:keys [max-entry-weight]} config]
      (if (or (nil? value)
              (not (integer? weight))
              (not (pos? weight))
              (> weight max-entry-weight)
              (not (integer? ttl-ms))
              (not (pos? ttl-ms)))
        (do
          (swap! state update :rejections (fnil inc 0))
          false)
        (locking entries
          (let [now (now-ms)]
            (expire-entries! entries state now)
            (remove-entry! entries state k :replacements)
            (.put entries k {:value value
                             :weight weight
                             :expires-at (+ now ttl-ms)})
            (swap! state
                   (fn [s]
                     (-> s
                         (update :weight + weight)
                         (update :puts (fnil inc 0)))))
            (evict-to-capacity! entries state config)
            (boolean (.containsKey entries k)))))))

  (evict! [_ k]
    (locking entries
      (boolean (remove-entry! entries state k :manual-evictions))))

  (clear! [_]
    (locking entries
      (.clear entries)
      (swap! state assoc :weight 0)
      nil))

  (stats [_]
    (locking entries
      (assoc @state
             :entries (.size entries)
             :max-weight (:max-weight config)
             :max-entry-weight (:max-entry-weight config)
             :max-entries (:max-entries config)))))

(defn local-store
  "Creates a bounded access-ordered local cache.

  Weight is an admission unit supplied by EACL's entry type; current built-in
  entries use conservative retained-work estimates rather than JVM object
  instrumentation."
  ([]
   (local-store {}))
  ([config]
   (let [{:keys [max-weight max-entry-weight max-entries] :as config'}
         (merge default-store-config config)]
     (when-not (and (integer? max-weight)
                    (pos? max-weight)
                    (integer? max-entry-weight)
                    (pos? max-entry-weight)
                    (<= max-entry-weight max-weight)
                    (integer? max-entries)
                    (pos? max-entries))
       (throw (ex-info "Invalid EACL cache capacity."
                       {:type :eacl/invalid-config
                        :cache config'})))
     (->LocalStore (LinkedHashMap. 16 0.75 true)
                   (atom {:weight 0
                          :hits 0
                          :misses 0
                          :puts 0
                          :evictions 0
                          :expirations 0
                          :rejections 0})
                   config'))))

(defrecord LocalRelationshipCoordinator [^ReentrantReadWriteLock lock generation-state]
  RelationshipCoordinator
  (generation [_]
    (:clock @generation-state))

  (generation [_ dependency-keys]
    (let [{:keys [dependencies uncertain]} @generation-state
          ordered-keys (if (vector? dependency-keys)
                         dependency-keys
                         (sort dependency-keys))]
      (into [[:uncertain uncertain]]
            (map (fn [dependency-key]
                   [dependency-key (get dependencies dependency-key 0)]))
            ordered-keys)))

  (with-read [_ f]
    (let [^Lock read-lock (.readLock lock)]
      (.lock read-lock)
      (try
        (f @generation-state)
        (finally
          (.unlock read-lock)))))

  (with-mutation [_ f]
    (let [^Lock write-lock (.writeLock lock)]
      (.lock write-lock)
      (try
        (try
          (let [[value changed-dependency-keys] (f)]
            (when (seq changed-dependency-keys)
              (swap! generation-state
                     (fn [{:keys [clock] :as state}]
                       (let [next-clock (inc clock)]
                         (-> state
                             (assoc :clock next-clock)
                             (update :dependencies
                                     (fn [dependencies]
                                       (reduce #(assoc %1 %2 next-clock)
                                               dependencies
                                               changed-dependency-keys))))))))
            value)
          (catch Exception e
            ;; A helper failure could occur after its Datomic transaction
            ;; committed but before its changed relation ids were returned.
            ;; Fail closed by making every prior live result unreachable.
            (swap! generation-state update :uncertain unchecked-inc)
            (throw e)))
        (finally
          (.unlock write-lock))))))

(defn local-coordinator []
  (->LocalRelationshipCoordinator (ReentrantReadWriteLock.)
                                  (atom {:clock 0
                                         :uncertain 0
                                         :dependencies {}})))

(defn local-context
  "Creates an explicit local cache context.

  Pass the same context to every EACL client that reads or writes relationships
  in one live-cache coherence scope."
  ([]
   (local-context {}))
  ([store-config]
   {:store (local-store store-config)
    :coordinator (local-coordinator)}))

(defn entry
  [cache-key kind value]
  {:eacl.cache/version cache-entry-version
   :eacl.cache/key cache-key
   :eacl.cache/kind kind
   :eacl.cache/value value})

(defn entry-value
  "Returns a matching wrapped value or nil. Cache providers are trusted for
  values stored under a valid wrapper; mismatched versions/keys/kinds miss."
  [cached cache-key kind valid-value?]
  (when (and (map? cached)
             (= cache-entry-version (:eacl.cache/version cached))
             (= cache-key (:eacl.cache/key cached))
             (= kind (:eacl.cache/kind cached))
             (valid-value? (:eacl.cache/value cached)))
    (:eacl.cache/value cached)))

(defn safe-lookup
  "Cache lookup whose failure is always a miss."
  [store k]
  (when store
    (try
      (lookup store k)
      (catch Exception _
        nil))))

(defn safe-store!
  "Best-effort cache publication. Failures never affect authorization output."
  [store k value weight ttl-ms]
  (boolean
   (when store
     (try
       (store! store k value weight ttl-ms)
       (catch Exception _
         false)))))

(defn safe-entry-value
  [store cache-key kind valid-value?]
  (when store
    (try
      (some-> (lookup store cache-key)
              (entry-value cache-key kind valid-value?))
      (catch Exception _
        nil))))

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
  [store cache-key kind value weight ttl-ms]
  (try
    (safe-store! store
                 cache-key
                 (entry cache-key kind value)
                 (+ weight (estimated-key-weight cache-key))
                 ttl-ms)
    (catch Exception _
      false)))
