(ns eacl.datascript.qualified-inspection-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.definition-test :as errors]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
            [eacl.datascript.qualified-check-test :as fixtures]))

(deftest stored-and-expiry-active-inspection-retain-metadata-without-caveat-evaluation
  (let [{:keys [client now]} (fixtures/fixture)]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (with-redefs [plan/compile-plan (fn [& _] (throw (ex-info "Inspection compiled a Caveat" {})))]
        (doseq [[time expected] [[99 #{:banned :member :parent :writer}]
                                 [100 #{:member :parent :writer}]
                                 [200 #{:parent :writer}]]]
          (reset! now time)
          (doseq [page-size [{:first 20} {:last 20}]]
            (let [query (merge {:resource/type :folder} page-size)
                  stored (eacl/read-relationships client (assoc query :relationship-state :stored))
                  active (eacl/read-relationships client (assoc query :relationship-state :expiry-active))]
              (is (= #{:banned :member :parent :writer} (set (map :relation (:data stored)))))
              (is (= expected (set (map :relation (:data active)))))
              (is (= :stored (:relationship-state stored)))
              (is (= :expiry-active (:relationship-state active)))
              (is (= time (:evaluation-time-ms active)))
              (is (= "enabled" (:caveat (first (filter #(= :member (:relation %)) (:data stored))))))
              (is (= 100 (:valid-until-ms (first (filter #(= :banned (:relation %)) (:data stored))))))
              (is (every? #(instance? eacl.core.Relationship %) (:data stored)))
              (is (not-any? #(or (contains? % :allowed?) (contains? % :permissionship)) (:data active)))
              (is (= (:data active)
                     (:data (eacl/read-relationships client (assoc query :relationship-state :expiry-active :cache? false))))))))))))

(deftest expiry-filter-keeps-the-existing-candidate-work-bound
  (let [{:keys [client now]} (fixtures/fixture)
        query {:resource/type :folder :first 1 :relationship-state :expiry-active
               :aggregate-limits {:candidate-window 1}}]
    (reset! now 100)
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (let [page (eacl/read-relationships client query)]
        (is (empty? (:data page)))
        (is (true? (get-in page [:page-info :bounded?])))
        (is (true? (get-in page [:page-info :has-next-page?])))
        (is (string? (get-in page [:page-info :end-cursor])))
        (let [next (eacl/read-relationships client (assoc query :after (get-in page [:page-info :end-cursor])))]
          (is (= [:member] (mapv :relation (:data next)))))))))

(deftest physical-inspection-does-not-require-a-caveat-evaluator
  (let [{:keys [conn now]} (fixtures/fixture)
        client (api/make-client conn {:clock #(deref now) :caveat-evaluator nil})]
    (reset! now 100)
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (is (= 4 (count (:data (eacl/read-relationships client {:resource/type :folder :first 20})))))
      (is (= 3 (count (:data (eacl/read-relationships client {:resource/type :folder :first 20
                                                              :relationship-state :expiry-active}))))))))

(deftest inspection-mode-is-validated-before-clock-or-basis-work
  (let [calls (atom 0)
        client (api/make-client (api/create-conn) {:clock #(do (swap! calls inc) 100)})]
    (doseq [mode [nil :active :authorized "stored"]]
      (is (= :eacl.filters/invalid-filter
             (errors/error-type #(eacl/read-relationships client {:resource/type :folder :relationship-state mode})))))
    (is (zero? @calls))))
