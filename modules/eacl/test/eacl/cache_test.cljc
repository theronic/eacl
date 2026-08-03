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
      :source-scope
      (fn [] {:source-id (:source @proofs) :branch nil})
      :graph-head
      (fn [] {:graph-anchor (:head @proofs)
              :order-hint (:basis @proofs)
              :exact-locator (:basis @proofs)})
      :contains-anchor?
      (fn [anchor] (contains? (:anchors @proofs) anchor))
      :order-hint (fn [] (:basis @proofs))
      :exact-locator (fn [] (:basis @proofs))
      :schema-proof
      (fn
        ([] (when-not (:proof-unavailable? @proofs)
              (:schema @proofs)))
        ([{:keys [permission-nodes]}]
         (when-not (:proof-unavailable? @proofs)
           (select-keys (:schema @proofs) permission-nodes))))
      :relation-proof
      (fn [relation-ids]
        (when-not (:proof-unavailable? @proofs)
          (select-keys (:relations @proofs) relation-ids)))})}))

(defrecord ThrowingStore []
  cache/CacheStore
  (lookup [_ _] (throw (ex-info "unavailable" {})))
  (store! [_ _ _] (throw (ex-info "unavailable" {})))
  (evict! [_ _] (throw (ex-info "unavailable" {})))
  (clear! [_] (throw (ex-info "unavailable" {})))
  (stats [_] (throw (ex-info "unavailable" {}))))

(defrecord ForgingStore [value]
  cache/CacheStore
  (lookup [_ _] value)
  (store! [_ _ _] true)
  (evict! [_ _] false)
  (clear! [_] nil)
  (stats [_] {}))

(deftest exact-proof-validation-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head-1"
                      :anchors #{"head-1"}
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
      (swap! proofs assoc
             :basis 2
             :head "head-2"
             :anchors #{"head-1" "head-2"})
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

(deftest forward-only-proof-lifting-test
  (let [proofs
        (atom {:basis 1
               :source "source"
               :head "computed"
               :anchors #{"computed"}
               :schema {:document :schema}
               :relations {10 :relation}})
        snapshot (adapter proofs)
        store (cache/local-store)
        calls (atom 0)
        resolve
        #(cache/resolve!
          snapshot store :key :can?
          {:permission-nodes #{:document}}
          [10] boolean?
          (fn [] (swap! calls inc) true))]
    (is (false? (:cached? (resolve))))
    (swap! proofs assoc
           :basis 2
           :head "descendant"
           :anchors #{"computed" "descendant"})
    (is (true? (:cached? (resolve))))
    (swap! proofs assoc
           :basis 2
           :head "sibling"
           :anchors #{"sibling"})
    (is (false? (:cached? (resolve))))
    (is (= 2 @calls))
    (is (= 1 (:causal-proof-lift (cache/stats store))))
    (is (= 1 (:future-history-rejection
              (cache/stats store))))))

(deftest corrupt-and-unavailable-store-fail-closed-test
  (let [proofs (atom {:basis 1
                      :source "source"
                      :head "head"
                      :anchors #{"head"}
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
             snapshot (->ForgingStore "eacl_ce3_forged")
             :key :can?
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

(deftest cache-scope-and-proof-availability-test
  (let [store (cache/local-store)
        first-proof
        (atom {:basis 1
               :source "first"
               :head "first-head"
               :anchors #{"first-head"}
               :schema {:document :schema}
               :relations {10 :relation}})
        second-proof
        (atom {:basis 1
               :source "second"
               :head "second-head"
               :anchors #{"second-head"}
               :schema {:document :schema}
               :relations {10 :relation}})
        calls (atom 0)
        compute #(do (swap! calls inc) true)
        resolve
        (fn [snapshot]
          (cache/resolve!
           snapshot store :same-query :can?
           {:permission-nodes #{:document}}
           [10] boolean? compute))]
    (is (false? (:cached? (resolve (adapter first-proof)))))
    (is (false? (:cached? (resolve (adapter second-proof)))))
    (is (= 2 @calls)
        "equal content in another source cannot reuse the entry")
    (swap! first-proof assoc :proof-unavailable? true)
    (is (false? (:cached? (resolve (adapter first-proof)))))
    (is (= 1 (:no-proof-bypass (cache/stats store))))))
