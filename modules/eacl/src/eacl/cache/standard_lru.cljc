(ns eacl.cache.standard-lru
  "Private, cross-runtime storage over bounded local caches.

  This namespace owns retention only. Callers validate supported ingress and
  perform any request-dependent eligibility checks outside it. In particular,
  no function here accepts a loader or callback that cache concurrency could
  repeat. JVM storage uses Caffeine's concurrent frequency/recency policy;
  ClojureScript storage uses cljs-cache's immutable LRU policy."
  (:require [eacl.exact-integer :as exact-integer]
            #?(:cljs [cljs.cache :as cache]))
  #?(:clj
     (:import [com.github.benmanes.caffeine.cache Cache Caffeine Policy]
              [java.util Map$Entry]
              [java.util.concurrent ConcurrentMap])))

(defrecord StandardLruStore [state max-entries])

#?(:clj
   (do
     ;; Caffeine reserves nil for absence. Boxing every value both preserves
     ;; nil/false and gives conditional operations an identity-equality token.
     (deftype CacheValue [value])

     (def ^:private nil-key (Object.))))

(defn store?
  [value]
  (instance? StandardLruStore value))

(defn- valid-capacity?
  [value]
  #?(:clj
     (and (integer? value)
          (pos? value)
          (<= value exact-integer/maximum))
     :cljs
     (and (number? value)
          (js/Number.isSafeInteger value)
          (pos? value))))

(defn- invalid-capacity!
  [value]
  (throw
   (ex-info
    "Cache :max-entries must be a positive cross-runtime safe integer."
    {:type :eacl/invalid-config
     :eacl/error :eacl/invalid-config
     :option :max-entries
     :value value
     :maximum exact-integer/maximum})))

(defn- empty-lru
  [max-entries]
  #?(:clj
     (-> (Caffeine/newBuilder)
         (.maximumSize (long max-entries))
         (.build))
     :cljs
     ;; Never seed library policy state from caller-controlled or restored
     ;; maps. Restore is a sequence of ordinary absent-key publications.
     (cache/lru-cache-factory {} :threshold max-entries)))

#?(:clj
   (do
     (defn- storage-key
       [key]
       (if (nil? key) nil-key key))

     (defn- public-key
       [key]
       (if (identical? nil-key key) nil key))

     (defn- boxed-value
       [value]
       (CacheValue. value))

     (defn- public-value
       [^CacheValue boxed]
       (.-value boxed))

     (defn- quiet-box
       [^Cache storage key]
       (.getIfPresentQuietly ^Policy (.policy storage) (storage-key key)))

     (defn- storage-map
       ^ConcurrentMap [^Cache storage]
       (.asMap storage))))

(defn store
  "Creates an empty bounded local cache with a positive safe-integer capacity."
  [max-entries]
  (when-not (valid-capacity? max-entries)
    (invalid-capacity! max-entries))
  (->StandardLruStore #?(:clj (empty-lru max-entries)
                         :cljs (atom (empty-lru max-entries)))
                      max-entries))

(defn lookup!
  "Returns {:found? true :value value} or explicit absence.

  Retrieval records policy usage and holds the immutable value even if another
  operation immediately evicts its key. Any request-dependent eligibility
  check happens after this function returns."
  [store key]
  #?(:clj
     (let [boxed (.getIfPresent ^Cache (:state store) (storage-key key))]
       (if (nil? boxed)
         {:found? false :value nil}
         {:found? true :value (public-value boxed)}))
     :cljs
     (loop []
       (let [current @(:state store)]
         (if-not (cache/has? current key)
           {:found? false
            :value nil}
           (let [held-value (cache/lookup current key)
                 next (cache/hit current key)]
             (if (compare-and-set! (:state store) current next)
               {:found? true
                :value held-value}
               (recur))))))))

(defn peek-entry
  "Reads one resident mapping without changing retention policy state.

  This supports publication comparison and off-CAS semantic eligibility.
  Actual retrieval calls `lookup!` or follows with `hit-if-value!` so only an
  accepted resident mapping refreshes recency."
  [store key]
  #?(:clj
     (let [boxed (quiet-box (:state store) key)]
       (if (nil? boxed)
         {:found? false :value nil}
         {:found? true :value (public-value boxed)}))
     :cljs
     (let [current @(:state store)]
       (if (cache/has? current key)
         {:found? true
          :value (cache/lookup current key)}
         {:found? false
          :value nil}))))

(defn hit-if-value!
  "Touches one mapping only while its immutable value is `expected-value`.

  Eligibility is decided by the semantic caller before this operation. The
  expected value is data, not a callback, so retries perform only membership,
  identity comparison, and a conditional cache update."
  [store key expected-value]
  #?(:clj
     (let [^Cache storage (:state store)
           key (storage-key key)
           ^ConcurrentMap mappings (storage-map storage)]
       (loop []
         (let [boxed (.getIfPresentQuietly ^Policy (.policy storage) key)]
           (cond
             (nil? boxed) false
             (not (identical? expected-value (public-value boxed))) false
             (.replace mappings key boxed boxed) true
             :else (recur)))))
     :cljs
     (loop []
       (let [current @(:state store)]
         (if-not (cache/has? current key)
           false
           (let [resident-value (cache/lookup current key)]
             (if-not (identical? expected-value resident-value)
               false
               (if (compare-and-set! (:state store) current
                                     (cache/hit current key))
                 true
                 (recur)))))))))

(defn put-if-absent!
  "Publishes an already-computed value unless key is already resident.

  Returns true only for the successful insertion. A concurrent same-key
  publication wins without being overwritten. Computation and validation are
  always outside cache atomic scopes."
  [store key completed-value]
  #?(:clj
     (let [^Cache storage (:state store)
           key (storage-key key)]
       ;; The quiet probe preserves the common existing-key operation as a
       ;; non-use. A publisher racing this probe may touch the winner through
       ;; ConcurrentMap.putIfAbsent; that affects retention only, never values.
       (if (some? (.getIfPresentQuietly ^Policy (.policy storage) key))
         false
         (nil? (.putIfAbsent (storage-map storage)
                             key
                             (boxed-value completed-value)))))
     :cljs
     (loop []
       (let [current @(:state store)]
         (if (cache/has? current key)
           false
           (let [next (cache/miss current key completed-value)]
             (if (compare-and-set! (:state store) current next)
               true
               (recur))))))))

(defn replace-if!
  "Atomically replaces one expected resident mapping.

  The expected-value comparison is data, not a callback. A successful
  replacement records fresh policy usage. Returns false when the key is absent
  or its immutable value has changed."
  [store key expected-value replacement-value]
  #?(:clj
     (let [^Cache storage (:state store)
           key (storage-key key)
           ^ConcurrentMap mappings (storage-map storage)]
       (loop []
         (let [boxed (.getIfPresentQuietly ^Policy (.policy storage) key)]
           (cond
             (nil? boxed) false
             (not (identical? expected-value (public-value boxed))) false
             (.replace mappings key boxed (boxed-value replacement-value)) true
             :else (recur)))))
     :cljs
     (loop []
       (let [current @(:state store)]
         (if-not (cache/has? current key)
           false
           (let [resident-value (cache/lookup current key)]
             (if-not (identical? expected-value resident-value)
               false
               (let [next (cache/miss (cache/evict current key)
                                      key
                                      replacement-value)]
                 (if (compare-and-set! (:state store) current next)
                   true
                   (recur))))))))))

(defn evict!
  "Evicts key if resident, returning whether this call removed a mapping."
  [store key]
  #?(:clj
     (some? (.remove (storage-map (:state store)) (storage-key key)))
     :cljs
     (loop []
       (let [current @(:state store)]
         (if-not (cache/has? current key)
           false
           (let [next (cache/evict current key)]
             (if (compare-and-set! (:state store) current next)
               true
               (recur))))))))

(defn clear!
  "Removes all mappings from this local cache instance."
  [store]
  #?(:clj (.invalidateAll ^Cache (:state store))
     :cljs (reset! (:state store) (empty-lru (:max-entries store))))
  nil)

(defn entries
  "Returns a portable immutable snapshot of resident [key value] pairs.

  Iteration order is deliberately not a retention or serialization contract.
  Concurrent JVM iteration is weakly consistent, as documented by Caffeine."
  [store]
  #?(:clj
     (let [^Cache storage (:state store)]
       (.cleanUp storage)
       (mapv (fn [^Map$Entry entry]
               [(public-key (.getKey entry))
                (public-value (.getValue entry))])
             (.entrySet (storage-map storage))))
     :cljs
     (into [] @(:state store))))

(defn entry-count
  "Returns the settled resident entry-count estimate."
  [store]
  #?(:clj
     (let [^Cache storage (:state store)]
       (.cleanUp storage)
       (.estimatedSize storage))
     :cljs
     (count @(:state store))))
