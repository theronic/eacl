(ns eacl.security.retention
  "Bounded physical cleanup; current key acceptance remains the trust boundary."
  (:require [eacl.cache.standard-lru :as lru]))

(defn on-retirement!
  "Claims cleanup once per observed retirement epoch. No controller read,
   listener registration, or retained secret snapshot. Older in-flight epochs
   cannot claim the same controller's newer cleanup. Failures are optional
   cache hygiene failures; they never alter authentication or request results."
  [observed snapshot cleanup]
  (when (and observed (seq (:retired-kids snapshot)))
    (let [identity (:controller-id snapshot)
          n (count (:retired-kids snapshot))
          previous @observed]
      (when (and (or (not= identity (first previous)) (> n (or (second previous) 0)))
                 (compare-and-set! observed previous [identity n]))
        (try (cleanup (:retired-kids snapshot))
             (catch #?(:clj Throwable :cljs :default) _ nil)))))
  nil)

(defn prune!
  "Drops only the exact resident selected by the predicate. A racing local
   publication or a newly accepted key's entry is never removed accidentally."
  [store discard?]
  (when store
    (doseq [[key value] (lru/entries store)]
      (when (discard? key value)
        (lru/evict-if! store key value))))
  nil)
