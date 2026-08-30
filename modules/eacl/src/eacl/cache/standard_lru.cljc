(ns eacl.cache.standard-lru
  "Private, cross-runtime storage over the standard immutable LRU protocol.

  This namespace owns retention only. Callers validate supported ingress and
  perform any request-dependent eligibility checks outside it. In particular,
  no function here accepts a loader or callback that an atom retry could
  repeat."
  (:require [eacl.exact-integer :as exact-integer]
            #?(:clj [clojure.core.cache :as cache]
               :cljs [cljs.cache :as cache])))

(defrecord StandardLruStore [state max-entries])

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
  ;; Never seed library policy state from caller-controlled or restored maps.
  ;; Restore is deliberately a sequence of ordinary absent-key publications.
  (cache/lru-cache-factory {} :threshold max-entries))

(defn store
  "Creates an empty local LRU with a strict positive safe-integer capacity."
  [max-entries]
  (when-not (valid-capacity? max-entries)
    (invalid-capacity! max-entries))
  (->StandardLruStore (atom (empty-lru max-entries)) max-entries))

(defn lookup!
  "Returns {:found? true :value value} or explicit absence.

  One CAS attempt performs membership, value capture, and the LRU hit against
  the same immutable cache. The held value remains usable even if another
  operation immediately evicts its key. Any request-dependent eligibility
  check happens after this function returns."
  [store key]
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
            (recur)))))))

(defn peek-entry
  "Reads one immutable store snapshot without changing LRU recency.

  This supports publication comparison and off-CAS semantic eligibility.
  Actual retrieval calls `lookup!` or follows with `hit-if-value!` so only an
  accepted resident mapping refreshes recency."
  [store key]
  (let [current @(:state store)]
    (if (cache/has? current key)
      {:found? true
       :value (cache/lookup current key)}
      {:found? false
       :value nil})))

(defn hit-if-value!
  "Touches one mapping only while its immutable value is `expected-value`.

  Eligibility is decided by the semantic caller before this operation. The
  expected value is data, not a callback, so CAS retries perform only standard
  membership, lookup, identity, and hit transformations."
  [store key expected-value]
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
              (recur))))))))

(defn put-if-absent!
  "Publishes an already-computed value unless key is already resident.

  Returns true only for the successful insertion. A concurrent same-key
  publication wins without being overwritten or refreshed: publication
  contention is not a cache read and therefore is not LRU usage. The retry
  loop performs only pure membership and LRU-miss transformations."
  [store key completed-value]
  (loop []
    (let [current @(:state store)]
      (if (cache/has? current key)
        false
        (let [next (cache/miss current key completed-value)]
          (if (compare-and-set! (:state store) current next)
            true
            (recur)))))))

(defn replace-if!
  "Atomically replaces one expected resident mapping.

  The expected-value comparison is data, not a callback, so an atom retry can
  repeat only standard cache membership, lookup, eviction, and miss
  transformations. A successful replacement is the newest LRU entry. Returns
  false when the key is absent or its immutable value has changed."
  [store key expected-value replacement-value]
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
                (recur)))))))))

(defn evict!
  "Evicts key if resident, returning whether this call removed a mapping."
  [store key]
  (loop []
    (let [current @(:state store)]
      (if-not (cache/has? current key)
        false
        (let [next (cache/evict current key)]
          (if (compare-and-set! (:state store) current next)
            true
            (recur)))))))

(defn clear!
  "Atomically replaces the current policy value with a fresh empty LRU."
  [store]
  (reset! (:state store) (empty-lru (:max-entries store)))
  nil)

(defn entries
  "Returns a portable immutable snapshot of resident [key value] pairs.

  Iteration order is deliberately not an LRU or serialization contract."
  [store]
  (into [] (seq @(:state store))))

(defn entry-count
  "Returns the number of entries in one immutable store snapshot."
  [store]
  (reduce (fn [n _] (inc n)) 0 (seq @(:state store))))
