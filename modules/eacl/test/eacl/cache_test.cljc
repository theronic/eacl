(ns eacl.cache-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]))

(defn- adapter
  [proofs]
  (backend/make-adapter
   {:id :cache-test
    :capabilities
    {:consistency #{:fully-consistent}
     :snapshots #{:current}
     :cursor #{:forward}
     :transactions #{}
     :cache-proofs #{:schema :relations :snapshot-bound}
     :runtime #{:clj}}
    :operations
    (merge
     (into {}
           (map (fn [operation]
                  [operation (fn [& _] nil)]))
           backend/required-snapshot-operations)
     {:snapshot-id #(select-keys @proofs [:basis])
      :schema-proof
      (fn
        ([] (:schema @proofs))
        ([{:keys [permission-nodes]}]
         (select-keys (:schema @proofs) permission-nodes)))
      :relation-proof
      (fn [relation-ids]
        (select-keys (:relations @proofs) relation-ids))})}))

(defrecord ThrowingStore []
  cache/CacheStore
  (lookup [_ _] (throw (ex-info "unavailable" {})))
  (store! [_ _ _] (throw (ex-info "unavailable" {})))
  (evict! [_ _] (throw (ex-info "unavailable" {})))
  (clear! [_] (throw (ex-info "unavailable" {})))
  (stats [_] (throw (ex-info "unavailable" {}))))

(deftest exact-proof-validation-test
  (let [proofs (atom {:basis 1
                      :schema {:document :schema-1
                               :unrelated :schema-a}
                      :relations {10 :relation-1
                                  20 :unrelated-1}})
        snapshot (adapter proofs)
        store (cache/local-store)
        calls (atom 0)
        compute #(do (swap! calls inc) {:answer true})
        resolve #(cache/resolve!
                  snapshot store :key :can?
                  {:permission-nodes #{:document}}
                  [10]
                  (fn [value] (= #{:answer} (set (keys value))))
                  compute)]
    (testing "matching proofs reuse the value"
      (is (false? (:cached? (resolve))))
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "unrelated relation changes retain the entry"
      (swap! proofs assoc-in [:relations 20] :unrelated-2)
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "unrelated schema changes retain the entry"
      (swap! proofs assoc-in [:schema :unrelated] :schema-b)
      (is (true? (:cached? (resolve))))
      (is (= 1 @calls)))

    (testing "relevant relation and schema changes invalidate"
      (swap! proofs assoc-in [:relations 10] :relation-2)
      (is (false? (:cached? (resolve))))
      (swap! proofs assoc-in [:schema :document] :schema-2)
      (is (false? (:cached? (resolve))))
      (is (= 3 @calls)))))

(deftest corrupt-and-unavailable-store-fail-closed-test
  (let [proofs (atom {:basis 1
                      :schema {:document :schema}
                      :relations {10 :relation}})
        snapshot (adapter proofs)
        corrupt-store (cache/local-store)
        _ (cache/store! corrupt-store :key {:answer :unvalidated})
        computed (atom 0)
        compute #(do (swap! computed inc) false)]
    (testing "a malformed value is a miss"
      (let [answer
            (cache/resolve!
             snapshot corrupt-store :key :can?
             {:permission-nodes #{:document}}
             [10] boolean? compute)]
        (is (false? (:value answer)))
        (is (false? (:cached? answer)))))

    (testing "provider read and write failures fall back to computation"
      (let [answer
            (cache/resolve!
             snapshot (->ThrowingStore)
             :key :can?
             {:permission-nodes #{:document}}
             [10] boolean? compute)]
        (is (false? (:value answer)))
        (is (false? (:cached? answer)))))

    (is (= 2 @computed))))
