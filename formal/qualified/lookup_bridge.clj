(ns eacl.formal.qualified.lookup-bridge
  "Qualified general covers against completion-set denotations. Ascending
   and descending continuation walks must agree on each least coordinate."
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.seekable-bridge :as seekable]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.lookup-evidence-test :as traversal]
            [eacl.operator.seekable-evidence-test :as fixtures]
            [eacl.relationships.edge :as edge]))

(defn oracle [permission semantic i]
  (if (= permission :either)
    (model/compose :union (seekable/oracle :both semantic i) (seekable/oracle :allowed semantic i))
    (seekable/oracle permission semantic i)))

(defn check-case! [{:keys [docs qids]} base permission traversal width policy a b]
  (let [semantic (seekable/leaves a b 99)
        positions (zipmap docs (range))
        options (assoc base :traversal traversal :page-size width :candidate-window 2
                       :result-policy policy :direct-specializations? false)
        resolve-edge (fn [_ _ compact]
                       (if compact (get semantic (get qids (edge/qualifier-id compact))) false))
        [rows descending counted]
        (with-redefs-fn {#'qualification/qualify resolve-edge}
          #(vector (traversal/drain options)
                   (traversal/drain (assoc options :order-direction :desc))
                   (lookup/count-results options)))
        expected (into {} (for [doc docs :let [value (oracle permission semantic (positions doc))]
                                :when (if (= policy :definite)
                                        (= (model/worlds 2) (:worlds value)) (seq (:worlds value)))]
                            [doc value]))]
    (is (= expected (into {} (map (juxt :value (comp bridge/model-value :evidence))) rows)))
    (is (= (count expected) (count rows) (:count counted)))
    (is (= (mapv (juxt :value :coords (comp evidence/value :evidence)) rows)
           (mapv (juxt :value :coords (comp evidence/value :evidence)) (rseq descending))))
    (doseq [time [100 109 110]]
      (let [later (seekable/leaves a b time)]
        (is (every? (fn [row]
                      (or (not (evidence/reusable? (:evidence row) 99 time))
                          (= (bridge/model-value (:evidence row))
                             (oracle permission later (positions (:value row)))))) rows))))))

(deftest qualified-general-covers-and-continuations-refine-completion-sets
  (let [env (seekable/fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:both :allowed :either]]
      (let [base (fixtures/options env permission 99 {} :asc)]
        (doseq [traversal [:forward :reverse] width [1 2] policy [:definite :detailed]
                a inputs b inputs]
          (check-case! env base permission traversal width policy a b))))))
