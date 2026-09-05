(ns eacl.formal.qualified.discovery-bridge
  "Production first-discovery results against independent per-world closure."
  (:require [clojure.test :refer [deftest is]]
            [eacl.formal.qualified.discovery-model :as model]
            [eacl.formal.qualified.discovery-model-test :as contract]
            [eacl.engine.stable-reducer-evidence-test :as fixture]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-test :as residual]
            [eacl.authorization.qualification :as qualification]
            [eacl.engine.stable-reducer :as reducer]))

(def nodes [100 200 300])
(def universe #{0 1 2 3})
(defn atom-value [worlds]
  (reduce #(evidence/combine :union %1 %2) false
          (for [world worlds]
            (evidence/combine :intersection
                              (if (bit-test world 0) residual/x (evidence/combine :exclusion true residual/x))
                              (if (bit-test world 1) residual/y (evidence/combine :exclusion true residual/y))))))

(deftest generated-discovery-refines-completion-set-reachability
  (doseq [mask (range 512) style (range 6)]
    (let [graph (contract/graph mask style)
          seeds [[0 #{1 3}] [1 #{2 3}]]
          expected (model/denotation graph seeds universe)
          edges (for [[from targets] graph [to worlds] targets]
                  [(nodes from) 20 (nodes to) (+ 200 (* from 3) to) worlds])
          rows (into [[1 10 100 101] [1 10 200 102]] (map #(subvec % 0 4)) edges)
          leaves (into {101 residual/x 102 residual/y}
                       (map (fn [[_ _ _ q worlds]] [q (atom-value worlds)])) edges)
          env (fixture/environment rows leaves {})
          forward (fixture/run env)
          wide (fixture/run (assoc-in env [:options :physical-chunk-size] 4))]
      (is (= (set (map nodes (keys expected))) (set (:results forward))))
      (is (= (:results forward) (:results wide)))
      (doseq [eid (:results forward)]
        (is (= (get expected (.indexOf nodes eid))
               (residual/completions (fixture/value-at forward eid) [residual/x residual/y]))))
      (doseq [node (range 3)]
        (let [reverse (fixture/run (fixture/environment rows leaves {:direction :reverse :resource-eid (nodes node)}))]
          (is (= (if (seq (get expected node)) [1] []) (:results reverse)))
          (when (seq (:results reverse))
            (is (= (get expected node)
                   (residual/completions (fixture/value-at reverse 1) [residual/x residual/y]))))))
      (let [prefix (fixture/run (assoc-in env [:options :target] 1))
            resumed (with-redefs [qualification/qualify (:qualify env)]
                      (reducer/resume (:options env) (reducer/history-free prefix)))]
        (is (= (:results forward) (into (:results prefix) (:results resumed))))))))
