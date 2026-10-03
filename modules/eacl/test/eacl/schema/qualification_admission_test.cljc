(ns eacl.schema.qualification-admission-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.schema.qualification-admission :as admission]))

(def ^:private publication (admission/publication-descriptor :inline))

(def ^:private plain
  {:eacl.relation/resource-type :doc
   :eacl.relation/relation-name :viewer
   :eacl.relation/subject-type :user})

(def ^:private caveated
  (assoc plain
         :eacl.relation/caveats [[:eacl.caveat/name "ip_ok"]]
         :eacl.relation/allows-unqualified? true))

(def ^:private malformed
  (assoc plain
         :eacl.relation/caveats []
         :eacl.relation/allows-unqualified? true))

(defn- outcome
  [f]
  (try
    (f)
    :admitted
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
      (:type (ex-data error)))))

(defn- admit
  "Admits `schema` for a client that has no Caveat evaluator."
  [schema]
  (outcome #(admission/schema! schema nil publication)))

(deftest a-schema-is-admitted-by-its-own-relations-test
  (let [plain-schema {:relations [plain]}
        caveated-schema {:relations [plain caveated]}
        malformed-schema {:relations [plain malformed]}]
    (testing "asking again about the same schema gives the scanned answer"
      (dotimes [_ 3]
        (is (= :admitted (admit plain-schema)))
        (is (= :eacl.caveat/evaluator-unavailable (admit caveated-schema)))
        (is (= :eacl.schema/invalid-relation-allowance
               (admit malformed-schema)))))
    (testing "an equal Relation vector that is another value is scanned itself"
      (is (= :admitted (admit {:relations [plain]})))
      (is (= :eacl.caveat/evaluator-unavailable
             (admit {:relations [plain caveated]}))))
    (testing "more schemas than are remembered are each answered for themselves"
      (doseq [n (range 12)
              :let [relations (vec (repeat (inc n) plain))]]
        (is (= :admitted (admit {:relations relations})))
        (is (= :eacl.caveat/evaluator-unavailable
               (admit {:relations (conj relations caveated)}))))
      (is (= :admitted (admit plain-schema)))
      (is (= :eacl.caveat/evaluator-unavailable (admit caveated-schema))))
    (testing "a schema without Relations needs no evaluator"
      (is (= :admitted (admit {})))
      (is (= :admitted (admit {:relations []}))))
    (testing "an uncertified publication strategy is refused before the scan"
      (is (= :eacl/unsupported-capability
             (outcome #(admission/schema! caveated-schema nil
                                          {:strategy :other})))))))
