(ns eacl.datomic.cache-store-contract
  "Reusable assertions for EACL CacheStore implementations.

  Provider authors can call `assert-provider-contract!` from their own test
  suite. The factory receives a store configuration map so TTL can be tested
  with an injected monotonic test clock."
  (:require [clojure.test :refer [is testing]]
            [eacl.datomic.cache :as cache]))

(defn assert-provider-contract!
  [{:keys [store-factory clock advance-clock!]}]
  (testing "capabilities and wrapper validation"
    (let [store (store-factory {:clock clock})
          key [:contract :wrapped]
          value {:count 7 :limit -1}]
      (is (set? (cache/capabilities store)))
      (is (cache/safe-store-entry!
           store :contract-a key :count value 64 1000))
      (is (= value
             (cache/safe-entry-value store key :count map?)))
      (is (nil? (cache/safe-entry-value
                 store key :lookup-page map?)))
      (is (nil? (cache/entry-value
                 (assoc (cache/entry :contract-a key :count value)
                        :eacl.cache/version
                        (inc cache/cache-entry-version))
                 key
                 :count
                 map?)))))

  (testing "TTL is enforced by lookup"
    (let [store (store-factory {:clock clock})
          key [:contract :ttl]]
      (is (cache/safe-store-entry!
           store :contract-a key :count {:count 1 :limit -1} 64 10))
      (advance-clock! 11)
      (is (nil? (cache/safe-entry-value store key :count map?)))))

  (testing "namespace cleanup is isolated"
    (let [store (store-factory {:clock clock})]
      (is (cache/safe-store-entry!
           store :contract-a :a :count {:count 1 :limit -1} 64 1000))
      (is (cache/safe-store-entry!
           store :contract-b :b :count {:count 2 :limit -1} 64 1000))
      (is (= 1 (cache/clear-namespace! store :contract-a)))
      (is (nil? (cache/lookup store :a)))
      (is (some? (cache/lookup store :b)))))

  (testing "concurrent independent keys remain readable"
    (let [store (store-factory {:clock clock})
          writes (doall
                  (for [n (range 32)]
                    (future
                      (cache/safe-store-entry!
                       store
                       :contract-concurrent
                       [:concurrent n]
                       :count
                       {:count n :limit -1}
                       64
                       1000))))]
      (is (every? true? (map deref writes)))
      (is (= (set (range 32))
             (into #{}
                   (keep (fn [n]
                           (some-> (cache/safe-entry-value
                                    store [:concurrent n] :count map?)
                                   :count)))
                   (range 32))))))

  (testing "provider failures are cache misses"
    (let [broken
          (reify cache/CacheStore
            (lookup [_ _] (throw (ex-info "lookup failed" {})))
            (store! [_ _ _ _ _] (throw (ex-info "store failed" {})))
            (evict! [_ _] (throw (ex-info "evict failed" {})))
            (clear! [_] (throw (ex-info "clear failed" {})))
            (stats [_] {}))]
      (is (nil? (cache/safe-entry-value
                 broken :unavailable :count map?)))
      (is (false? (cache/safe-store-entry!
                   broken :unavailable :count
                   {:count 1 :limit -1} 64 1000))))))
