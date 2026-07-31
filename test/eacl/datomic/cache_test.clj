(ns eacl.datomic.cache-test
  (:require [clojure.test :refer [deftest is testing]]
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

(deftest relationship-coordinator-advances-only-on-change-test
  (let [coordinator (cache/local-coordinator {:incarnation "test"})]
    (is (= {:incarnation "test" :uncertain 0 :revision 0}
           (cache/generation coordinator)))
    (is (= :unchanged
           (cache/with-mutation coordinator (fn [] [:unchanged nil]))))
    (is (= {:incarnation "test" :uncertain 0 :revision 0}
           (cache/generation coordinator)))
    (is (= :changed
           (cache/with-mutation coordinator
                                (fn [] [:changed {:dependency-keys #{10 20}
                                                  :basis-t 101}]))))
    (is (= {:incarnation "test" :uncertain 0 :revision 101}
           (cache/generation coordinator)))
    (is (= {:incarnation "test" :uncertain 0 :revision 101}
           (cache/generation coordinator #{10 20})))
    (is (= [101 :read]
           (cache/with-read coordinator
                            (fn [snapshot]
                              [(:observed-t snapshot) :read]))))
    (cache/with-mutation coordinator
                         (fn [] [:other {:dependency-keys #{30}
                                         :basis-t 107}]))
    (is (= {:incarnation "test" :uncertain 0 :revision 101}
           (cache/generation coordinator #{10 20})))
    (is (= {:incarnation "test" :uncertain 0 :revision 107}
           (cache/generation coordinator #{30})))))

(deftest relationship-coordinator-fails-closed-after-an-attempted-write-test
  (let [coordinator (cache/local-coordinator)
        before (cache/generation coordinator [10])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"after commit"
         (cache/with-mutation
          coordinator
          (fn []
            (cache/mutation-attempted!)
            (throw (ex-info "after commit" {}))))))
    (is (not= before (cache/generation coordinator [10]))
        "an uncertain helper outcome invalidates every prior live result")))

(deftest relationship-coordinator-keeps-results-after-a-pre-write-failure-test
  ;; A :create conflict or an unknown object id is detected before any
  ;; transaction is submitted, so the database is unchanged and every cached
  ;; result is still valid. Invalidating here held the live cache at a 0% hit
  ;; rate for any caller who could trigger an ordinary application error.
  (let [coordinator (cache/local-coordinator)
        before (cache/generation coordinator [10])]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"before commit"
         (cache/with-mutation
          coordinator
          (fn []
            (throw (ex-info "before commit" {}))))))
    (is (= before (cache/generation coordinator [10]))
        "validation that committed nothing must not invalidate live results")))

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
                    [:written {:dependency-keys #{10}
                               :basis-t 1}])))
        _ @writer-entered
        reader (future
                 (cache/with-read
                  coordinator
                  (fn [snapshot]
                    (deliver reader-finished (:observed-t snapshot))
                    (:observed-t snapshot))))]
    (is (= ::blocked (deref reader-finished 50 ::blocked))
        "a read cannot publish against the old generation during a write")
    (deliver release-writer true)
    (is (= :written @writer))
    (is (= 1 @reader))
    (is (= 1 @reader-finished))))

(deftest local-context-owns-independent-explicit-coordinators-test
  (let [context-a (cache/local-context)
        context-b (cache/local-context)]
    (testing "one context contains the store and coordinator consumers pass"
      (is (satisfies? cache/CacheStore (:store context-a)))
      (is (satisfies? cache/RelationshipCoordinator
                      (:coordinator context-a))))
    (testing "there is no implicit process-global coherence scope"
      (is (not (identical? (:coordinator context-a)
                           (:coordinator context-b)))))))

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

(deftest dependency-generation-compresses-to-safe-maximum-test
  (let [snapshot {:incarnation "inc"
                  :uncertain 3
                  :dependencies {10 100
                                 20 70
                                 30 110}}]
    (is (= {:incarnation "inc" :uncertain 3 :revision 100}
           (cache/dependency-generation snapshot [10 20])))
    (is (= {:incarnation "inc" :uncertain 3 :revision 110}
           (cache/dependency-generation
            (assoc-in snapshot [:dependencies 20] 110)
            [10 20])))))

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
