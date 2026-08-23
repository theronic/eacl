(ns eacl.engine.schema-generation-reuse-test
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.v8 :as engine]))

(defn- unstamped-request-cache
  []
  {:schema-version nil
   :request-local? true
   :sealed-plans (atom {})})

(deftest unstamped-values-seal-each-root-once-per-request-test
  (let [stable-plan @#'engine/stable-plan
        seals (atom 0)]
    (with-redefs [sealed-plan/seal-plan
                  (fn [_db _root]
                    (swap! seals inc)
                    (Object.))]
      (testing "one request-local floor returns one plan instance"
        (binding [engine/*schema-cache* (unstamped-request-cache)]
          (let [first-plan (stable-plan :unstamped [:document :view])
                second-plan (stable-plan :unstamped [:document :view])]
            (is (identical? first-plan second-plan))
            (is (= 1 @seals)))))
      (testing "another request gets an isolated floor and seals once again"
        (binding [engine/*schema-cache* (unstamped-request-cache)]
          (let [next-plan (stable-plan :unstamped [:document :view])]
            (is (some? next-plan))
            (is (= 2 @seals))))))))
