(ns eacl.datomic.cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.cache :as shared-cache]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.cache-store-contract :as contract]))

(deftest local-store-provider-contract-test
  (let [now (atom 0)]
    (contract/assert-provider-contract!
     {:clock #(deref now)
      :advance-clock! #(swap! now + %)
      :store-factory
      (fn [config]
        (cache/local-store
         (merge {:max-weight 100000
                 :max-entry-weight 10000
                 :max-entries 100}
                config)))})))

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
  (let [now (atom 100)
        store (cache/local-store {:max-weight 10
                                  :max-entry-weight 10
                                  :max-entries 2
                                  :clock #(deref now)})]
    (is (cache/store! store :short :value 1 1))
    (swap! now + 2)
    (is (nil? (cache/lookup store :short)))
    (is (pos? (:expirations (cache/stats store))))
    (is (cache/store! store :long :value 1 10000))
    (cache/clear! store)
    (is (zero? (:entries (cache/stats store))))))

(deftest local-store-expiry-is-key-local-test
  (let [now (atom 100)
        store (cache/local-store {:max-weight 100
                                  :max-entry-weight 100
                                  :max-entries 10
                                  :clock #(deref now)})]
    (is (cache/store! store :expired :old 1 1))
    (is (cache/store! store :live :current 1 10000))
    (swap! now + 2)
    (is (= :current (cache/lookup store :live)))
    (is (= 2 (:entries (cache/stats store)))
        "a hot lookup does not scan and remove unrelated keys")
    (is (zero? (:expirations (cache/stats store))))
    (is (nil? (cache/lookup store :expired)))
    (is (= 1 (:entries (cache/stats store))))
    (is (= 1 (:expirations (cache/stats store))))))

(deftest wrapped-entry-admission-includes-retained-key-shape-test
  (let [store (cache/local-store {:max-weight 512
                                  :max-entry-weight 256
                                  :max-entries 10})
        large-key [:result (apply str (repeat 200 "x"))]]
    (is (false? (cache/safe-store-entry!
                 store large-key :count {:count 1 :limit -1} 1 10000)))
    (is (zero? (:entries (cache/stats store))))))

(deftest cache-entry-wrapper-rejects-mismatches-test
  (let [store (cache/local-store)
        key [:result :db :query]
        value {:count 7 :limit -1}
        wrong-key-store
        (reify cache/CacheStore
          (lookup [_ _] (cache/entry key :count value))
          (store! [_ _ _ _ _] true)
          (evict! [_ _] false)
          (clear! [_] nil)
          (stats [_] {}))]
    (is (cache/safe-store-entry! store key :count value 1 10000))
    (is (= value
           (cache/safe-entry-value store key :count map?)))
    (is (nil? (cache/safe-entry-value wrong-key-store
                                      [:result :other-db :query]
                                      :count
                                      map?)))
    (is (nil? (cache/safe-entry-value store :missing :count map?)))
    (is (pos? (get-in (cache/stats store)
                      [:by-kind :count :misses])))
    (is (nil? (cache/safe-entry-value
               store
               key
               :count
               (fn [_] (throw (ex-info "invalid cached bytes" {}))))))
    (is (nil? (cache/safe-entry-value store key :lookup-page map?)))
    (is (nil? (cache/entry-value
               (assoc (cache/entry key :count value)
                      :eacl.cache/version (inc cache/cache-entry-version))
               key
               :count
               map?)))))

(deftest safe-cache-operations-fall-back-test
  (let [broken (reify cache/CacheStore
                 (lookup [_ _] (throw (ex-info "down" {})))
                 (store! [_ _ _ _ _] (throw (ex-info "down" {})))
                 (evict! [_ _] nil)
                 (clear! [_] nil)
                 (stats [_] {}))]
    (is (nil? (cache/safe-lookup broken :key)))
    (is (false? (cache/safe-store! broken :key :value 1 1000)))))

(deftest provider-and-observability-failures-are-contained-test
  (let [hostile
        (reify
          cache/CacheStore
          (lookup [_ _] (throw (ex-info "lookup down" {})))
          (store! [_ _ _ _ _] (throw (ex-info "store down" {})))
          (evict! [_ _] (throw (ex-info "evict down" {})))
          (clear! [_] (throw (ex-info "clear down" {})))
          (stats [_] (throw (ex-info "metrics down" {})))

          cache/CacheProvider
          (capabilities [_] (throw (ex-info "capabilities down" {})))
          (clear-namespace! [_ _]
            (throw (ex-info "namespace clear down" {})))
          (record-provider-error! [_ _ _]
            (throw (ex-info "provider telemetry down" {}))))]
    (is (nil? (cache/safe-lookup hostile :key)))
    (is (nil? (cache/safe-entry-value hostile :key :can? boolean?)))
    (is (false? (cache/safe-store! hostile :key :value 1 1000)))
    (is (false? (cache/safe-evict! hostile :key)))
    (is (= #{} (cache/safe-capabilities hostile)))
    (is (false?
         (cache/safe-store-entry!
          hostile :key :can? true 1 1000)))
    (is (nil?
         (cache/safe-record-provider-error!
          hostile :lookup :can?)))))

(deftest local-store-is-kind-aware-and-frequency-admitted-test
  (let [store (cache/local-store {:max-weight 10000
                                  :max-entry-weight 4000
                                  :max-entries 20
                                  :kind-max-weight {:can? 1200}
                                  :two-hit-kinds #{:can?}})]
    (is (false? (cache/safe-store-entry!
                 store [:can 1] :can? false 100 10000))
        "a one-off permission key is not admitted")
    (is (cache/safe-store-entry!
         store [:can 1] :can? false 100 10000)
        "the second observation is admitted")
    (is (false? (cache/safe-store-entry!
                 store [:can 2] :can? true 1100 10000))
        "the class share remains bounded")
    (is (= 1 (get-in (cache/stats store) [:entries-by-kind :can?])))
    (is (pos? (get-in (cache/stats store) [:by-kind :can? :rejections])))))

(deftest authenticated-store-preserves-logical-kind-test
  (let [provider
        (cache/local-store
         {:max-weight 100000
          :max-entry-weight 10000
          :max-entries 20
          :kind-max-weight {:can? 8000}
          :two-hit-kinds #{:can?}})
        store (cache/authenticated-store provider :test nil)
        key {:kind :can?
             :semantic-key [:permission-check "alice" :view "document-1"]}]
    (is (false? (shared-cache/store! store key "signed-envelope"))
        "the first sighting must obey :can? two-hit admission")
    (is (true? (shared-cache/store! store key "signed-envelope"))
        "the second sighting is admitted")
    (is (= "signed-envelope" (shared-cache/lookup store key)))
    (is (= 1 (get-in (cache/stats provider)
                     [:entries-by-kind :can?])))))

(deftest namespaced-clear-never-clears-other-consumers-test
  (let [store (cache/local-store)]
    (is (cache/safe-store-entry!
         store :consumer-a :a :count {:count 1 :limit -1} 10 10000))
    (is (cache/safe-store-entry!
         store :consumer-b :b :count {:count 2 :limit -1} 10 10000))
    (is (= 1 (cache/clear-namespace! store :consumer-a)))
    (is (nil? (cache/lookup store :a)))
    (is (some? (cache/lookup store :b)))))

(deftest portable-provider-rejects-process-local-values-test
  (let [values (atom {})
        provider
        (reify
          cache/CacheStore
          (lookup [_ k] (get @values k))
          (store! [_ k value _weight _ttl]
            (swap! values assoc k value)
            true)
          (evict! [_ k] (boolean (get (swap! values dissoc k) k)))
          (clear! [_] (reset! values {}))
          (stats [_] {:entries (count @values)})

          cache/CacheProvider
          (capabilities [_] #{:portable-values :ttl})
          (clear-namespace! [_ namespace]
            (swap! values
                   (fn [entries]
                     (into {}
                           (remove (fn [[_ value]]
                                     (= namespace
                                        (:eacl.cache/namespace value))))
                           entries))))
          (record-provider-error! [_ _ _] nil))]
    (is (cache/safe-store-entry!
         provider :portable :count {:count 1 :limit -1} 10 10000))
    (is (false? (cache/safe-store-entry!
                 provider :opaque :recursive-continuation
                 {:resume (fn [])} 10 10000)))
    (is (false? (cache/safe-store-entry!
                 provider :lazy :lookup-page
                 {:data (map identity [1 2])} 10 10000)))))

(deftest provider-errors-are-observable-test
  (let [store (cache/local-store)]
    (cache/record-provider-error! store :serialize :can?)
    (is (= 1 (:provider-errors (cache/stats store))))
    (is (= 1 (get-in (cache/stats store)
                     [:provider-errors-by-operation :serialize])))
    (is (= 1 (get-in (cache/stats store)
                     [:by-kind :can? :provider-errors])))))
