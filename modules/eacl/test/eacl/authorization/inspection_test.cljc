(ns eacl.authorization.inspection-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.caveats.plan :as plan]
            [eacl.relationships.inspection :as inspection]))

(deftest stored-inspection-never-evaluates-or-compiles-a-caveat
  (doseq [time [99 100 101]]
    (let [calls (atom 0)
          reads (atom {})
          request (fixtures/request {:time time :calls calls :reads reads :evaluator nil})]
      (with-redefs [plan/compile-plan (fn [& _] (throw (ex-info "Unexpected compilation" {})))]
        (is (= {:caveat "enabled" :valid-until-ms 100}
               (qualification/inspect request 1 3)))
        (is (= {:caveat "enabled" :caveat-context {"flag" true} :valid-until-ms 100}
               (qualification/inspect request 1 4)))
        (is (= {:valid-until-ms 100} (qualification/inspect request 1 5)))
        (is (= {} (qualification/inspect request nil nil)))
        (is (= (if (< time 100) :active :expired)
               (if (inspection/active? time (qualification/inspect request 1 3)) :active :expired))))
      (is (zero? @calls))
      (is (= {1 1, 2 1, 3 1, 4 1, 5 1} @reads)))))

(deftest expiry-inspection-is-independent-of-caveat-outcomes
  (doseq [context [{} {"flag" false} {"flag" true}]
          time [99 100 101]]
    (is (= (< time 100)
           (inspection/active? time {:caveat "enabled" :caveat-context context :valid-until-ms 100}))))
  (is (inspection/active? 100 {}))
  (is (inspection/active? 100 {:caveat "enabled"})))
