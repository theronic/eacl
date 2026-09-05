(ns eacl.authorization.evidence-index-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [clojure.set :as set]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-index :as index]
            [eacl.authorization.evidence-test :as fixtures]))

(defn denotation [op values]
  (reduce (case op :union set/union :intersection set/intersection)
          (if (= op :union) #{} #{0 1 2 3})
          (map #(fixtures/completions % [fixtures/x fixtures/y]) values)))

(deftest incremental-joins-refine-independent-completion-sets
  (doseq [op [:union :intersection] width (range 36)]
    (let [values (mapv #(nth [false fixtures/x fixtures/y true] (mod % 4)) (range width))
          built (index/build op values (fn []))]
      (is (= (denotation op values) (fixtures/completions (index/value built) [fixtures/x fixtures/y])))
      (loop [slot 0 values values tree built]
        (when (< slot width)
          (let [replacement (nth [fixtures/y false true fixtures/x] (mod slot 4))
                values (assoc values slot replacement)
                tree (index/replace-slot tree slot replacement (fn []))]
            (is (= (denotation op values) (fixtures/completions (index/value tree) [fixtures/x fixtures/y])))
            (recur (inc slot) values tree)))))))

(deftest updates-retain-hidden-leaves-and-charge-only-an-ancestor-path
  (let [work (atom 0) step! #(swap! work inc)
        built (index/build :union (into [true] (repeat 63 false)) step!)
        hidden (index/replace-slot built 63 fixtures/x step!)]
    (is (true? (index/value hidden)))
    (reset! work 0)
    (let [revealed (index/replace-slot hidden 0 false step!)]
      (is (= fixtures/x (index/value revealed)))
      (is (<= @work 6)))
    (reset! work 0)
    (is (identical? hidden (index/replace-slot hidden 63 fixtures/x step!)))
    (is (zero? @work))))

(deftest faults-and-certificates-survive-incremental-joins
  (let [fault (evidence/fault :eacl.qualifier/invalid :missing-qualifier)
        grant (evidence/with-certificate true 100 true)
        tree (index/build :intersection [grant fixtures/x true] (fn []))
        result (index/value tree)]
    (is (= :conditional-permission (evidence/permissionship result)))
    (is (= 100 (evidence/valid-until result)))
    (is (evidence/fault? (index/value (index/replace-slot tree 2 fault (fn [])))))
    (is (not (evidence/complete?
              (index/value (index/replace-slot tree 2 (evidence/with-certificate true nil false) (fn []))))))))
