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

(deftest definite-certificates-retain-the-canonical-wire-format
  (doseq [value [false true]
          end [nil -62135596800000 -1 0 100 253402300799999]
          complete? [false true]]
    (let [proof (e/with-certificate value end complete?)
          expected (values/encode-bounded [:eacl.authorization/evidence e/format-version value end complete?]
                                          {:maximum-size 4194304 :maximum-entries 16384 :maximum-depth 80})]
      (is (= expected (e/encode proof)))
      (is (= proof (e/decode expected))))))

(defn- outcome [f]
  (try [:value (f)]
       (catch #?(:clj Throwable :cljs :default) error [:error (:reason (ex-data error))])))

(deftest scalar-envelopes-decode-exactly-as-the-generic-reader-does
  (let [generic #(outcome (fn [] (#'e/decode-generic %)))
        payloads (for [value [false true]
                       end [nil -62135596800000 -1 0 7 100 1790240461782 253402300799999]
                       complete? [false true]]
                   (e/encode (e/with-certificate value end complete?)))
        edits (fn [payload]
                (concat
                 (for [i (range (count payload))]
                   (str (subs payload 0 i) (subs payload (inc i))))
                 (for [i (range (inc (count payload))) c [" " "0" "1" "-" "+" "]" "e" "N" "."]]
                   (str (subs payload 0 i) c (subs payload i)))
                 (for [i (range (count payload)) c [" " "0" "9" "-" "]" "x"]]
                   (str (subs payload 0 i) c (subs payload (inc i))))
                 ["" "[:eacl.authorization/evidence 1 " "[:eacl.authorization/evidence 1 true 1 true 1]"
                  "[:eacl.authorization/evidence 1 true 253402300800000 true]"
                  "[:eacl.authorization/evidence 1 true 9223372036854775808 true]"
                  "[:eacl.authorization/evidence 1 true 1e3 true]"]))]
    (doseq [payload payloads]
      (is (= [:value (e/decode payload)] (generic payload)))
      (is (= payload (e/encode (e/decode payload))))
      (doseq [edited (edits payload)]
        (is (= (outcome #(e/decode edited)) (generic edited)) edited)))))
