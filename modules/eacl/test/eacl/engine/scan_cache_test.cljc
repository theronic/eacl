(ns eacl.engine.scan-cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.cache.standard-lru :as lru]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.request.counters :as request-counters]))

(def ^:private descriptor
  {:operation :subject->resources
   :subject-type :user :subject-eid 7
   :relation-eid 17 :resource-type :doc})

(deftest descriptor-key-omits-bound-and-limit-test
  (is (= (scan-cache/descriptor-key descriptor)
         (scan-cache/descriptor-key (assoc descriptor :bound-eid 5 :limit 3))))
  (is (not= (scan-cache/descriptor-key descriptor)
            (scan-cache/descriptor-key (assoc descriptor :direction :desc))))
  (is (nil? (scan-cache/descriptor-key {:operation :unknown}))))

(deftest serve-reproduces-the-adapter-reply-test
  (let [entry {:prefix [1 3 5 7 9] :exhausted? false}
        complete {:prefix [1 3 5 7 9] :exhausted? true}]
    (testing "full hit from the start and after a bound"
      (is (= [1 3 5] (scan-cache/serve entry nil 3 :asc)))
      (is (= [5 7 9] (scan-cache/serve entry 3 3 :asc)))
      (is (= [5 7 9] (scan-cache/serve entry 4 3 :asc)) "a bound between values")
      (is (= [] (scan-cache/serve complete 9 3 :asc)) "exhausted beyond the last value"))
    (testing "short non-exhausted prefix misses"
      (is (nil? (scan-cache/serve entry 7 3 :asc)))
      (is (nil? (scan-cache/serve entry 9 1 :asc))))
    (testing "exhausted short hit"
      (is (= [7 9] (scan-cache/serve complete 5 64 :asc))))
    (testing "descending scans"
      (let [descending {:prefix [9 7 5 3 1] :exhausted? true}]
        (is (= [9 7] (scan-cache/serve descending nil 2 :desc)))
        (is (= [5 3 1] (scan-cache/serve descending 7 64 :desc)))
        (is (= [3 1] (scan-cache/serve descending 4 64 :desc)))))))

(deftest extend-entry-keeps-prefixes-from-the-scan-start-test
  (testing "first chunk from the start"
    (is (= {:prefix [1 2 3] :exhausted? false}
           (scan-cache/extend-entry nil nil [1 2 3] 3 :asc 512)))
    (is (= {:prefix [1 2] :exhausted? true}
           (scan-cache/extend-entry nil nil [1 2] 3 :asc 512))))
  (testing "a fragment without its start is not retained"
    (is (nil? (scan-cache/extend-entry nil 4 [5 6 7] 3 :asc 512)))
    (is (nil? (scan-cache/extend-entry {:prefix [1 2 3] :exhausted? false}
                                       9 [10 11] 3 :asc 512))))
  (testing "contiguous extension from the last value and from inside"
    (let [entry {:prefix [1 2 3] :exhausted? false}]
      (is (= {:prefix [1 2 3 4 5 6] :exhausted? false}
             (scan-cache/extend-entry entry 3 [4 5 6] 3 :asc 512)))
      (is (= {:prefix [1 2 3 4] :exhausted? true}
             (scan-cache/extend-entry entry 3 [4] 3 :asc 512)))
      (is (= {:prefix [1 2 3 4 5] :exhausted? false}
             (scan-cache/extend-entry entry 2 [3 4 5] 3 :asc 512))
          "a reply overlapping the prefix extends it")
      (is (identical? entry (scan-cache/extend-entry entry 1 [2 3] 3 :asc 512))
          "a reply the prefix already covers changes nothing")))
  (testing "the per-entry cap retains the existing prefix"
    (let [entry {:prefix [1 2 3] :exhausted? false}]
      (is (nil? (scan-cache/extend-entry entry 3 [4 5 6] 3 :asc 4)))
      (is (nil? (scan-cache/extend-entry nil nil [1 2 3 4 5] 5 :asc 4))))))

(defn- counting-inner
  "An adapter over one fixed ascending sequence per descriptor key,
  honoring bound and limit exactly and counting commands."
  [sequences commands]
  (fn [{:keys [bound-eid limit] :as descriptor}]
    (swap! commands conj (select-keys descriptor [:subject-eid :bound-eid :limit]))
    (let [values (get sequences (:subject-eid descriptor) [])
          beyond (if (nil? bound-eid) values (filterv #(> % bound-eid) values))]
      (if limit (vec (take limit beyond)) (vec beyond)))))

(defn- with-ledger
  [f]
  (request-counters/call-with-ledger (request-counters/make-ledger) f))

(deftest memo-serves-repeated-commands-inside-one-request-test
  (with-ledger
    (fn []
      (let [commands (atom [])
            inner (counting-inner {7 [1 2 3 4 5 6 7]} commands)
            fetch (scan-cache/caching-fetch-fn inner {:memo (scan-cache/memo)})
            d (assoc descriptor :limit 3)]
        (is (= [1 2 3] (fetch d)))
        (is (= [1 2 3] (fetch d)) "the identical command is served")
        (is (= [1 2] (fetch (assoc d :limit 2))) "a smaller limit is served")
        (is (= [4 5 6] (fetch (assoc d :bound-eid 3))) "a contiguous miss extends")
        (is (= [2 3 4 5 6] (fetch (assoc d :bound-eid 1 :limit 5))))
        (is (= [7] (fetch (assoc d :bound-eid 6))) "the exhausting reply")
        (is (= [] (fetch (assoc d :bound-eid 7))) "served from the exhausted prefix")
        (is (= 3 (count @commands)) "three adapter commands for seven fetches")
        (is (= [nil 3 6] (map :bound-eid @commands)))
        (let [counts (request-counters/snapshot request-counters/*ledger*)]
          (is (= 4 (:scan-memo-hits counts)))
          (is (= 3 (:scan-misses counts))))))))

(deftest memo-disabled-seam-forwards-every-command-test
  (with-ledger
    (fn []
      (let [commands (atom [])
            inner (counting-inner {7 [1 2 3]} commands)
            fetch (scan-cache/caching-fetch-fn inner {:memo (scan-cache/memo)})
            d (assoc descriptor :limit 3)]
        (binding [scan-cache/*memo-disabled?* true]
          (is (= [1 2 3] (fetch d)))
          (is (= [1 2 3] (fetch d))))
        (is (= 2 (count @commands)))))))

(deftest memo-bound-stops-retaining-new-descriptors-test
  (with-ledger
    (fn []
      (let [commands (atom [])
            sequences (into {} (for [i (range 10)] [i [i]]))
            inner (counting-inner sequences commands)
            fetch (scan-cache/caching-fetch-fn inner {:memo (scan-cache/memo 3)})
            d (fn [i] (assoc descriptor :subject-eid i :limit 4))]
        (doseq [i (range 10)] (fetch (d i)))
        (doseq [i (range 10)] (fetch (d i)))
        (is (= 17 (count @commands))
            "three memoized descriptors are served; seven are refetched")))))

(deftest shared-tier-serves-across-requests-under-one-scope-test
  (let [tier (scan-cache/tier {:max-entries 16 :max-prefix 8})
        commands (atom [])
        inner (counting-inner {7 [1 2 3 4 5]} commands)
        run (fn [scope]
              (with-ledger
                (fn []
                  (let [fetch (scan-cache/caching-fetch-fn
                               inner {:memo (scan-cache/memo)
                                      :tier tier
                                      :scope-fn (fn [relation-eid]
                                                  (when scope
                                                    [scope relation-eid]))})]
                    [(fetch (assoc descriptor :limit 3))
                     (fetch (assoc descriptor :bound-eid 3 :limit 3))]))))]
    (is (= [[1 2 3] [4 5]] (run :generation-1)))
    (is (= 2 (count @commands)))
    (is (= [[1 2 3] [4 5]] (run :generation-1)) "served from the shared tier")
    (is (= 2 (count @commands)) "no command for the second request")
    (is (= [[1 2 3] [4 5]] (run :generation-2)) "another scope misses")
    (is (= 4 (count @commands)))
    (is (= [[1 2 3] [4 5]] (run nil)) "an unavailable scope bypasses the tier")
    (is (= 6 (count @commands)))
    (let [stats (scan-cache/stats tier)]
      (is (= 1 (:hits stats))
          "the second request's first fetch hit the tier; its memo served the next")
      (is (= 2 (:entry-count stats)))
      (is (pos? (:scope-unavailable stats))))))

(deftest shared-tier-keeps-the-longer-prefix-under-concurrent-extension-test
  (let [tier (scan-cache/tier {:max-entries 16 :max-prefix 8})
        key [[:scope 17] (scan-cache/descriptor-key descriptor)]
        store (:store tier)
        short {:prefix [1 2 3] :exhausted? false}
        long {:prefix [1 2 3 4 5 6] :exhausted? false}]
    (is (true? (lru/put-if-absent! store key short)))
    (is (true? (lru/replace-if! store key short long)))
    (is (false? (lru/replace-if! store key short {:prefix [1 2 3 4] :exhausted? false}))
        "a stale expected entry never overwrites the longer prefix")
    (is (= long (:value (lru/lookup! store key))))))

(deftest unknown-operations-and-unbounded-limits-bypass-the-cache-test
  (with-ledger
    (fn []
      (let [commands (atom [])
            inner (counting-inner {7 [1 2 3]} commands)
            fetch (scan-cache/caching-fetch-fn inner {:memo (scan-cache/memo)})]
        (fetch (assoc descriptor :limit nil))
        (fetch (assoc descriptor :limit nil))
        (is (= 2 (count @commands)) "no limit means no reuse")))))
