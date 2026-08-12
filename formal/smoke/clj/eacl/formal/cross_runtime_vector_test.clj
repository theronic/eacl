(ns eacl.formal.cross-runtime-vector-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing]]
            [eacl.formal.java-round-trip :as round-trip]
            [eacl.formal.page-window-bridge :as page]
            [eacl.formal.semantics-bridge :as semantics]))

(defn- vectors
  []
  (edn/read-string
   (slurp "formal/cross-runtime/vectors.edn")))

(defn- plain-object
  [value]
  (select-keys value [:type :id]))

(defn- plain-grants
  [grants]
  (into
   #{}
   (map
    (fn [[subject permission resource]]
      [(plain-object subject) permission (plain-object resource)]))
   grants))

(defn- continuation-input
  [input]
  (merge
   {:authenticated? true
    :scope-matches? true
    :expired? false
    :source "source"
    :cursor-source "source"
    :current-proof "proof"
    :cursor-proof "proof"
    :cursor-graph 7}
   input))

(deftest generated-java-cross-runtime-vectors-test
  (let [{:keys [graph pages continuations round-trips]}
        (vectors)
        {:keys [fixture subject resource permission expected]} graph]
    (testing "portable graph"
      (is (= (:grants expected)
             (plain-grants
              (semantics/authorization-set fixture))))
      (is (= (:forward expected)
             (mapv :id
                   (semantics/acyclic-forward
                    fixture subject :document permission))))
      (is (= (:reverse expected)
             (mapv :id
                   (semantics/acyclic-reverse
                    fixture resource :user permission)))))
    (testing "page windows"
      (doseq [{:keys [values request expected]} pages]
        (is (= expected
               (select-keys
                (page/paginate values request)
                (keys expected)))
            (pr-str request))))
    (testing "continuation decisions"
      (doseq [{:keys [input expected]} continuations]
        (is (= expected
               (page/continuation-decision
                (continuation-input input)))
            (pr-str input))))
    (testing "typed round-trip results"
      (doseq [{:keys [tag values limit expected]} round-trips]
        (is (= expected
               (select-keys
                (round-trip/round-trip tag values limit)
                (keys expected)))
            (pr-str [tag values limit]))))))
