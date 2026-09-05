(ns eacl.formal.qualified.arrow-bridge
  "Completing a known arrow binding against independent completion sets.
   The existing scalar machine visits the remaining bindings exactly once."
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.engine.least-path :as least-path]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.operator.arrow-evidence-test :as fixtures]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.lookup-evidence-test :as ordered]))

(defn semantic [layout a b time]
  (let [negative-a (evidence/combine :exclusion true a)
        values (case layout
                 0 [true a true b]
                 1 [a b b negative-a]
                 2 [a negative-a true b])]
    (into {} (map (fn [key value end]
                    [key (if (< time end) (evidence/with-certificate value end true) false)])
                  [[:via 0] [:target 0] [:via 1] [:target 1]] values [100 110 120 130]))))

(defn oracle [leaves]
  (model/compose :union
                 (model/compose :arrow (bridge/model-value (get leaves [:via 0]))
                                (bridge/model-value (get leaves [:target 0])))
                 (model/compose :arrow (bridge/model-value (get leaves [:via 1]))
                                (bridge/model-value (get leaves [:target 1])))))

(deftest seeded-arrow-completion-refines-union-of-exact-bindings
  (let [{:keys [qids] :as env} (fixtures/fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:view :direct_inherited]]
      (let [base (fixtures/options env permission)]
        (doseq [layout [0 1 2] a inputs b inputs]
          (let [leaves (semantic layout a b 99)
                known (evidence/combine :arrow (get leaves [:via 0]) (get leaves [:target 0]))
                result (with-redefs-fn {#'qualification/qualify (fixtures/resolver qids leaves)}
                         #(scalar/check-eids (assoc-in base [:arrow-witness :evidence] known)))]
            (is (= (oracle leaves) (bridge/model-value result)))
            (doseq [time [100 110 120]]
              (is (or (not (evidence/reusable? result 99 time))
                      (= (oracle (semantic layout a b time)) (bridge/model-value result)))))))))))

(defn check-ordered-case! [env base traversal width policy layout a b]
  (let [leaves (semantic layout a b 99)
        options (assoc base :traversal traversal :page-size width :result-policy policy
                       :anchor-eid (if (= traversal :forward) (:user env) (:document env)))
        expected-value (oracle leaves)
        expected (if (if (= policy :definite)
                       (= (model/worlds 2) (:worlds expected-value))
                       (seq (:worlds expected-value)))
                   (if (= traversal :forward) (:documents env) (:users env)) [])
        [rows descending counted]
        (with-redefs-fn {#'qualification/qualify (fixtures/resolver (:qids env) leaves)}
          #(vector (ordered/drain options)
                   (ordered/drain (assoc options :order-direction :desc))
                   (lookup/count-results options)))]
    (is (= (zipmap expected (repeat expected-value))
           (into {} (map (juxt :value (comp bridge/model-value :evidence))) rows)))
    (is (= (count expected) (count rows) (:count counted)))
    (is (= (mapv (juxt :value :coords (comp evidence/value :evidence)) rows)
           (mapv (juxt :value :coords (comp evidence/value :evidence)) (rseq descending))))
    (doseq [time [100 110 120]]
      (is (every? (fn [row]
                    (or (not (evidence/reusable? (:evidence row) 99 time))
                        (= (oracle (semantic layout a b time)) (bridge/model-value (:evidence row))))) rows)))))

(deftest ordered-arrow-covers-refine-completion-sets-and-resume-coordinates
  (let [env (fixtures/lookup-fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:inherited :inherited_direct]]
      (let [base (fixtures/lookup-options env permission :forward :asc 1 :detailed)]
        (doseq [layout [0 1 2] traversal [:forward :reverse] width [1 2] policy [:definite :detailed]
                a inputs b inputs]
          (check-ordered-case! env base traversal width policy layout a b))))))

(deftest expired-prefix-witness-work-is-bounded-by-the-physical-shorter-side
  (let [probes (atom 0)
        ctx (least-path/make-context
             {:qualification {} :physical-chunk-size 1 :max-values 2 :max-commands 3
              :fetch-fn (fn [descriptor]
                          (if (= :large (:side descriptor))
                            [[(inc (or (:bound-eid descriptor) 0)) 10]] []))})
        stream (fn [side]
                 (#'least-path/stream
                   (fn [bound width] {:side side :relation-eid 1 :bound-eid bound :limit width}) nil))
        member? (fn [_] (swap! probes inc) true)]
    (with-redefs [qualification/qualify (constantly false)]
      (is (false? (#'least-path/isect2? ctx (stream :large) member? (stream :empty) member? nil))))
    (is (= {:commands 2 :fetched-values 1}
           (select-keys @(:counters ctx) [:commands :fetched-values])))
    (is (zero? @probes))))
