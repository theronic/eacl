(ns eacl.relationships.safe-retraction-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(deftest support-and-target-contract-test
  (doseq [mode safe/supported-modes]
    (is (= mode
           (:mode (safe/support-descriptor
                   {:backend :test :mode mode :reason :test-mode})))))
  (doseq [target [0 42 [:eacl/id "account-1"] [:some/ident "abc"]]]
    (is (= [[safe/function-ident target]]
           (safe/target-invocation target))))
  (doseq [target [-1 nil [] [:eacl/id] [:eacl/id nil] [:a 1 :b]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 (safe/validate-target! target)))))

(deftest component-closure-is-complete-cycle-safe-and-bounded-test
  (let [reads (atom [])
        children {1 [2 3] 2 [3 4] 3 [1] 4 []}]
    (is (= [1 2 3 4]
           (safe/component-closure
            1
            (fn [eid]
              (swap! reads conj eid)
              (get children eid [])))))
    (is (= #{1 2 3 4} (set @reads)))
    (is (= 4 (count @reads))
        "every closure entity is read exactly once")))

(deftest local-half-planning-is-exact-test
  (let [target 10
        forward [[:account 20 :server 30]
                 [:account 21 :account target]
                 [:account 20 :server 30]]
        reverse [[:account 22 :user 40]
                 [:account 21 :account target]]
        plan (safe/plan-local-halves target forward reverse)]
    (is (= 5 (:local-half-count plan)))
    (is (= [20 21 22] (:relation-ids plan)))
    (is (= #{[:db/retract 30 storage/reverse-attribute
              [:server 20 :account target]]
             [:db/retract 40 storage/forward-attribute
              [:user 22 :account target]]}
           (set (:peer-retractions plan))))
    (is (= [[:db/add 20 :eacl/relation-version :db/current-tx]
            [:db/add 21 :eacl/relation-version :db/current-tx]
            [:db/add 22 :eacl/relation-version :db/current-tx]]
           (safe/relation-stamps (:relation-ids plan))))))

(deftest combined-plans-deduplicate-peer-work-and-relation-stamps-test
  (let [op [:db/retract 30 storage/reverse-attribute [:server 20 :account 10]]
        combined
        (safe/combine-plans
         [{:peer-retractions [op]
           :relation-ids [20]
           :local-half-count 1}
          {:peer-retractions [op]
           :relation-ids [20 21]
           :local-half-count 2}])]
    (is (= [op] (:peer-retractions combined)))
    (is (= [20 21] (:relation-ids combined)))
    (is (= 3 (:local-half-count combined)))))

(deftest malformed-halves-fail-before-emitting-cleanup-test
  (doseq [[forwards reverses]
          [[[[:account 2 :server]] []]
           [[] [[:account "relation" :user 4]]]
           [[[:account 2 :server -1]] []]]]
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo
                    :cljs cljs.core.ExceptionInfo)
                 (safe/plan-local-halves 1 forwards reverses)))))

(deftest expansion-size-is-linear-in-local-degree-test
  (doseq [degree [0 1 10 100 1000]]
    (let [target 1
          relation-count 7
          forward
          (mapv (fn [index]
                  [:user (+ 2 (mod index relation-count))
                   :account (+ 1000 index)])
                (range degree))
          plan (safe/plan-local-halves target forward [])]
      (is (= degree (:local-half-count plan)))
      (is (= degree (count (:peer-retractions plan))))
      (is (= (min degree relation-count)
             (count (:relation-ids plan)))))))
