(ns eacl.formal.qualified.stable-route-bridge
  "Finite completion-set refinement of the existing bidirectional and DFS
   routes. The oracle exhausts a small graph; production keeps its demand,
   chunking, early exits, and shorter-side probing."
  (:require [clojure.test :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.engine.stable-route-evidence-test :as fixture]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.model-test :as contract]))

(defn active [value end time]
  (if (model/before? time end) (evidence/with-certificate value end true) false))

(defn denotation [a b c time]
  (model/compose :union
                 (model/compose :arrow (bridge/model-value (active a 100 time))
                                (bridge/model-value b))
                 (model/compose :arrow (bridge/model-value (active c 110 time))
                                (bridge/model-value b))))

(deftest bidirectional-completion-sets-and-exclusive-certificates
  (let [inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))
        rows [[2 20 100 201] [3 20 100 202] [1 30 2 301] [1 30 3 302]]]
    (doseq [rule [(fixture/arrow fixture/root 20 fixture/target)
                 {:rule :arrow-relation :resource-type :doc :via-relation-eid 20
                  :intermediate-type :group :target-relation-eid 30 :target-subject-type :user}]
            chunk [1 2]
            a inputs b inputs c inputs]
      (let [plan (fixture/sealed {fixture/root [rule] fixture/target [(fixture/direct fixture/target 30)]})
            answer (:answer (fixture/run plan rows {201 (active a 100 99) 202 (active c 110 99)
                                                    301 b 302 b}
                                         {:physical-chunk-size chunk}))]
        (is (= (denotation a b c 99) (bridge/model-value answer)))
        (is (= answer (evidence/decode (evidence/encode answer))))
        (doseq [time [100 109 110]]
          (is (or (not (evidence/reusable? answer 99 time))
                  (= (denotation a b c time) (bridge/model-value answer)))))))))

(defn graph-semantics [graph time]
  {:base {0 (bridge/production-for-worlds #{1 3}) 1 false
          2 (active (bridge/production-for-worlds #{2 3}) 100 time)}
   :via (into {} (for [target (range 3) source (range 3)]
                   [[target source]
                    (if (bit-test graph (+ (* target 3) source))
                      (active (bridge/production-for-worlds (if (= source 1) #{2 3} contract/universe))
                              (nth [nil 100 110] (mod (+ target source) 3)) time)
                      false)]))})

(defn graph-oracle [semantic]
  (:values
   (model/fixed-point contract/universe
                      (update-vals (:base semantic) bridge/model-evidence)
                      (into {} (for [target (range 3)]
                                 [target (mapv (fn [source]
                                                 [(bridge/model-evidence (get-in semantic [:via [target source]])) source])
                                               (range 3))])) 64)))

(deftest cyclic-path-prefixes-refine-positive-fixed-point
  (let [plan (fixture/sealed {fixture/root [(fixture/direct fixture/root 10)
                                           (fixture/arrow fixture/root 20 fixture/root)]})
        ids [100 2 3]
        rows (into (mapv (fn [i] [1 10 (nth ids i) (+ 1000 i)]) (range 3))
                   (for [target (range 3) source (range 3)]
                     [(nth ids source) 20 (nth ids target) (+ 2000 (* target 3) source)]))]
    (doseq [graph (range 512)]
      (let [semantic (graph-semantics graph 99)
            leaves (into (into {} (map (fn [[i value]] [(+ 1000 i) value]) (:base semantic)))
                         (map (fn [[[target source] value]] [(+ 2000 (* target 3) source) value]) (:via semantic)))
            expected (graph-oracle semantic)
            later (mapv #(graph-oracle (graph-semantics graph %)) [100 109 110])]
        (doseq [node (range 3)]
          (let [answer (:answer (fixture/run plan rows leaves {:resource-eid (nth ids node)}))]
            (is (= (:value (get expected node)) (bridge/model-value answer)))
            (doseq [[time at-time] (map vector [100 109 110] later)]
              (is (or (not (evidence/reusable? answer 99 time))
                      (= (:value (get at-time node)) (bridge/model-value answer)))))))))))
