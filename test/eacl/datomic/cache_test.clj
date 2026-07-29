(ns eacl.datomic.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.datomic.cache :as cache]))

(deftest local-store-is-weight-and-entry-bounded-test
  (let [store (cache/local-store {:max-weight 10
                                  :max-entry-weight 6
                                  :max-entries 2})]
    (is (cache/store! store :a :a 4 10000))
    (is (cache/store! store :b :b 4 10000))
    (is (= :a (cache/lookup store :a)) "lookup makes :a most recently used")
    (is (cache/store! store :c :c 4 10000))
    (is (nil? (cache/lookup store :b)) "least-recently-used entry is evicted")
    (is (= :a (cache/lookup store :a)))
    (is (= :c (cache/lookup store :c)))
    (is (false? (cache/store! store :oversized :x 7 10000)))
    (is (<= (:weight (cache/stats store)) 10))
    (is (<= (:entries (cache/stats store)) 2))))

(deftest local-store-expiry-and-clear-test
  (let [store (cache/local-store {:max-weight 10
                                  :max-entry-weight 10
                                  :max-entries 2})]
    (is (cache/store! store :short :value 1 1))
    (Thread/sleep 5)
    (is (nil? (cache/lookup store :short)))
    (is (pos? (:expirations (cache/stats store))))
    (is (cache/store! store :long :value 1 10000))
    (cache/clear! store)
    (is (zero? (:entries (cache/stats store))))))

(deftest relationship-coordinator-advances-only-on-change-test
  (let [coordinator (cache/local-coordinator)]
    (is (= 0 (cache/generation coordinator)))
    (is (= :unchanged
           (cache/with-mutation coordinator (fn [] [:unchanged #{}]))))
    (is (= 0 (cache/generation coordinator)))
    (is (= :changed
           (cache/with-mutation coordinator
                                (fn [] [:changed #{10 20}]))))
    (is (= 1 (cache/generation coordinator)))
    (is (= [[10 1] [20 1]]
           (cache/generation coordinator #{10 20})))
    (is (= [1 :read]
           (cache/with-read coordinator
                            (fn [snapshot]
                              [(:clock snapshot) :read]))))
    (cache/with-mutation coordinator (fn [] [:other #{30}]))
    (is (= [[10 1] [20 1]]
           (cache/generation coordinator #{10 20})))
    (is (= [[30 2]]
           (cache/generation coordinator #{30})))))

(deftest relationship-coordinator-excludes-read-write-publication-races-test
  (let [coordinator (cache/local-coordinator)
        writer-entered (promise)
        release-writer (promise)
        reader-finished (promise)
        writer (future
                 (cache/with-mutation
                  coordinator
                  (fn []
                    (deliver writer-entered true)
                    @release-writer
                    [:written #{10}])))
        _ @writer-entered
        reader (future
                 (cache/with-read
                  coordinator
                  (fn [snapshot]
                    (deliver reader-finished (:clock snapshot))
                    (:clock snapshot))))]
    (is (= ::blocked (deref reader-finished 50 ::blocked))
        "a read cannot publish against the old generation during a write")
    (deliver release-writer true)
    (is (= :written @writer))
    (is (= 1 @reader))
    (is (= 1 @reader-finished))))

(deftest process-coordinator-is-shared-by-database-id-test
  (testing "clients over one database share a mutation generation"
    (is (identical? (cache/process-coordinator "db-a")
                    (cache/process-coordinator "db-a"))))
  (testing "different databases are isolated"
    (is (not (identical? (cache/process-coordinator "db-a")
                         (cache/process-coordinator "db-b"))))))

(deftest safe-cache-operations-fall-back-test
  (let [broken (reify cache/CacheStore
                 (lookup [_ _] (throw (ex-info "down" {})))
                 (store! [_ _ _ _ _] (throw (ex-info "down" {})))
                 (evict! [_ _] nil)
                 (clear! [_] nil)
                 (stats [_] {}))]
    (is (nil? (cache/safe-lookup broken :key)))
    (is (false? (cache/safe-store! broken :key :value 1 1000)))))
