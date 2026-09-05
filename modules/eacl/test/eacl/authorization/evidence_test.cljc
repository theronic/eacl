(ns eacl.authorization.evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as e]
            [eacl.caveats.values :as values]))

(def x (e/conditional ["c" :x] ["x"]))
(def y (e/conditional ["c" :y] ["y"]))
(def z (e/conditional ["c" :z] ["z"]))

(defn error-data [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) error (ex-data error))))

(defn evaluate-node
  "Test-only Boolean completion interpreter, independent of BDD application."
  [node assignment]
  (if (boolean? node) node
      (evaluate-node (nth node (if (get assignment (ffirst node)) 2 1)) assignment)))

(defn completions [evidence atoms]
  (set (for [world (range (bit-shift-left 1 (count atoms)))
             :let [assignment (into {} (map-indexed (fn [i atom] [(ffirst (e/value atom)) (bit-test world i)]) atoms))]
             :when (evaluate-node (e/value evidence) assignment)] world)))

(deftest conditional-algebra-is-canonical-and-correlated
  (is (true? (e/combine :union x (e/combine :exclusion true x))))
  (is (false? (e/combine :intersection x (e/combine :exclusion true x))))
  (doseq [op [:union :intersection :arrow]]
    (is (= x (e/combine op x x)))
    (is (= (e/combine op x y) (e/combine op y x))))
  (is (= (e/combine :union x (e/combine :union y z))
         (e/combine :union (e/combine :union z x) y)))
  (is (= x (e/combine :union x (e/combine :intersection x y))))
  (is (= ["x" "y"] (e/missing-fields (e/combine :exclusion x y))))
  (is (= [] (e/missing-fields (e/combine :union x (e/combine :exclusion true x))))))

(deftest temporal-certificate-uses-decisive-evidence
  (let [grant (e/with-certificate true 100 true)
        other (e/with-certificate y 110 true)
        ban (e/combine :exclusion true grant)]
    (is (= :no-permission (e/permissionship ban)))
    (is (= 100 (e/valid-until ban)))
    (is (e/reusable? ban 90 99))
    (is (not (e/reusable? ban 90 100)))
    (is (not (e/reusable? ban 90 89)))
    (is (true? (e/combine :union true other)))
    (is (= 100 (e/valid-until (e/combine :intersection grant other))))
    (is (false? (e/combine :intersection false other)))
    (is (not (e/complete? (e/combine :intersection (e/with-certificate x 100 false) other))))
    (is (true? (e/complete? (e/combine :union true (e/with-certificate x 100 false)))))))

(deftest authoritative-faults-never-become-boolean-absence
  (let [fault (e/fault :eacl.qualifier/invalid :missing-qualifier)]
    (doseq [op [:union :intersection :exclusion :arrow] other [true false x]]
      (is (e/fault? (e/combine op fault other)))
      (is (e/fault? (e/combine op other fault))))
    (is (not (e/has? fault)))
    (is (not (e/reusable? fault 0 0)))
    (is (= :evaluation-failure (e/permissionship fault)))))

(deftest canonical-bounded-wire-round-trips
  (doseq [evidence [true false x y
                    (e/combine :exclusion x y)
                    (e/with-certificate x 100 true)
                    (e/with-certificate false nil false)
                    (e/fault :eacl.qualifier/invalid :missing-qualifier)]]
    (let [payload (e/encode evidence)]
      (is (= evidence (e/decode payload)))
      (is (= payload (e/encode (e/decode payload))))))
  (let [wire (fn [node] (values/encode-bounded [:eacl.authorization/evidence 1 node nil true]
                                               {:maximum-size 65536 :maximum-depth 80 :maximum-entries 16384}))
        atom (first (e/value x))
        invalid-nodes [nil [] [1 false true] [atom false false]
                       [atom [atom false true] true]
                       [:fault []] [:fault [["private exception" :reason]]]]]
    (doseq [node invalid-nodes]
      (is (some? (:eacl/error (error-data #(e/decode (wire node))))))
      (is (some? (:eacl/error (error-data #(e/encode (e/->Evidence node nil true))))))))
  (is (= :certificate (:reason (error-data #(e/encode (e/->Evidence true 1.5 true))))))
  (is (= :certificate (:reason (error-data #(e/with-certificate x 1.5 true)))))
  (is (= :missing-fields (:reason (error-data #(e/conditional ["c"] []))))))

(deftest residual-work-and-depth-have-hard-bounds
  (with-redefs [e/limits (assoc e/limits :work 1)]
    (is (= :work-limit (:reason (error-data #(e/combine :union x y))))))
  (with-redefs [e/limits (assoc e/limits :nodes 1)]
    (is (= :node-limit (:reason (error-data #(e/combine :union x false))))))
  (with-redefs [e/limits (assoc e/limits :depth 0)]
    (is (= :depth-limit (:reason (error-data #(e/combine :intersection x y)))))))
