(ns eacl.formal.qualified.discovery-model-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.set :as set]
            [eacl.formal.qualified.discovery-model :as discovery]))

(def universe #{0 1 2 3})
(def a #{0 1})
(def b #{0 2})
(def weights [universe #{} a b #{2 3} #{1 3}])

(defn graph [mask style]
  (into {}
        (for [from (range 3)]
          [from (vec (for [to (range 3) :when (bit-test mask (+ (* from 3) to))]
                       [to (nth weights (mod (+ style from (* 2 to)) (count weights)))]))])))

(deftest weighted-discovery-is-complete-bounded-and-resumable
  (doseq [mask (range 512) style (range 6)
          seeds [[[0 a] [1 b]] [[0 universe]] [[0 a] [1 #{2 3}]]]]
    (let [graph (graph mask style)
          expected (discovery/denotation graph seeds universe)
          initial (discovery/initial seeds)
          full (discovery/run graph initial Long/MAX_VALUE)]
      (is (= expected (:values full)))
      (is (= (set (keys expected)) (set (:emitted full))))
      (is (<= (:changes full) (* 3 (count universe))))
      (doseq [target [1 2]]
        (let [prefix (discovery/run graph initial target)
              resumed (discovery/run graph prefix Long/MAX_VALUE)]
          (is (= (:emitted full) (:emitted resumed)))
          (is (= (:values full) (:values resumed))))))))

(deftest changed-prefixes-must-revisit-and-conjoin
  (let [graph {0 [[2 universe]] 1 [[2 universe]] 2 [[3 b]]}
        seeds [[0 a] [1 #{2 3}]]
        expected (discovery/denotation graph seeds universe)
        run #(discovery/run graph (discovery/initial seeds) Long/MAX_VALUE)
        admit discovery/admit]
    (is (= expected (:values (run))))
    (with-redefs [discovery/admit (fn [state node worlds]
                                   (if (contains? (:values state) node) state
                                       (admit state node worlds)))]
      (is (not= expected (:values (run)))))
    (with-redefs [set/intersection (fn [prefix _] prefix)]
      (is (not= expected (:values (run)))))))
