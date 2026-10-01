(ns eacl.authorization.evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as e]
            [eacl.caveats.values :as values]
            [eacl.request.counters :as counters]))

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

(def ^:private fault-a (e/fault :eacl.qualifier/invalid :missing-qualifier))
(def ^:private fault-b (e/fault :eacl.caveat/evaluation :missing-map-key))

(defn- world-value
  "Test-only completion interpreter for three-valued terminals: `true`,
  `false`, or the reasons of a fault terminal as a set."
  [node assignment]
  (cond
    (boolean? node) node
    (keyword? (first node)) (set (second node))
    :else (world-value (nth node (if (get assignment (ffirst node)) 2 1)) assignment)))

(defn- kleene
  "Independent strong-Kleene connectives on one completion. A fault is the
  set of its reasons."
  [op a b]
  (let [fault? set?
        merged (fn [x y] (cond (and (fault? x) (fault? y)) (into x y) (fault? x) x :else y))]
    (case op
      :union (cond (or (true? a) (true? b)) true (or (fault? a) (fault? b)) (merged a b) :else false)
      (:intersection :arrow) (cond (or (false? a) (false? b)) false (or (fault? a) (fault? b)) (merged a b) :else true)
      :exclusion (cond (or (false? a) (true? b)) false (or (fault? a) (fault? b)) (merged a b) :else true))))

(def ^:private assignments
  (for [xv [false true] yv [false true]]
    {(ffirst (e/value x)) xv (ffirst (e/value y)) yv}))

(defn- per-world [evidence]
  (mapv #(world-value (e/value evidence) %) assignments))

(def ^:private operands
  "Terminals, atoms, and mixed residual/fault diagrams."
  (let [base [true false fault-a fault-b x y]]
    (into base
          (for [op [:union :intersection :exclusion] a [x y] b [fault-a true false y]
                :let [v (e/combine op a b)]]
            v))))

(deftest kleene-connectives-are-pointwise-in-every-completion
  (doseq [op [:union :intersection :exclusion :arrow] a operands b operands]
    (is (= (mapv #(kleene op %1 %2) (per-world a) (per-world b))
           (per-world (e/combine op a b)))
        (pr-str [op (e/value a) (e/value b)]))))

(deftest a-definite-absorber-decides-beside-a-fault
  ;; Strong Kleene: true absorbs a fault in a union, false in an
  ;; intersection or an arrow, and an exclusion is false when its left side
  ;; is false or its right side true. A fault is never an absorber.
  (doseq [fault [fault-a (e/combine :union fault-a fault-b) (e/combine :union x fault-a)]]
    (is (true? (e/combine :union true fault)))
    (is (true? (e/combine :union fault true)))
    (doseq [op [:intersection :arrow]]
      (is (false? (e/combine op false fault)))
      (is (false? (e/combine op fault false))))
    (is (false? (e/combine :exclusion false fault)))
    (is (false? (e/combine :exclusion fault true)))
    (is (e/fault? (e/combine :union false fault)))
    (is (e/fault? (e/combine :intersection true fault)))
    (is (e/fault? (e/combine :exclusion true fault)))
    (is (e/fault? (e/combine :exclusion fault false))))
  (is (= [[:eacl.caveat/evaluation :missing-map-key] [:eacl.qualifier/invalid :missing-qualifier]]
         (e/fault-reasons (e/combine :union fault-a fault-b))
         (e/fault-reasons (e/combine :intersection fault-b fault-a))))
  ;; (c + f) & c = c: a fault absorbed in the completions where c is false.
  (is (= x (e/combine :intersection (e/combine :union x fault-a) x)))
  (is (true? (e/combine :union (e/combine :union x fault-a) (e/combine :exclusion true x))))
  (is (e/fault? (e/combine :exclusion fault-a fault-a))))

(deftest a-decisive-witness-keeps-its-certificate-beside-a-fault
  (let [grant (e/with-certificate true 100 true)
        ban (e/with-certificate true 90 true)]
    (is (= 100 (e/valid-until (e/combine :union grant fault-a))))
    (is (= 100 (e/valid-until (e/combine :union fault-a grant))))
    (is (e/complete? (e/combine :union fault-a grant)))
    (is (= 90 (e/valid-until (e/combine :exclusion fault-a ban))))
    (is (not (e/complete? (e/combine :union fault-a (e/with-certificate x 100 false)))))
    (is (not (e/reusable? (e/combine :union x fault-a) 0 0)))))

(deftest faults-classify-as-evaluation-failures
  (doseq [fault [fault-a (e/combine :union x fault-a) (e/combine :intersection y fault-b)]]
    (is (e/fault? fault))
    (is (not (e/has? fault)))
    (is (not (e/no? fault)))
    (is (= [] (e/missing-fields fault)))
    (is (not (e/reusable? fault 0 0)))
    (is (= :evaluation-failure (e/permissionship fault)))
    (is (= {:type :eacl.authorization/evaluation-failure
            :eacl/error :eacl.authorization/evaluation-failure
            :faults (e/fault-reasons fault)}
           (error-data #(e/throw-if-fault! fault)))))
  (is (= [[:eacl.qualifier/invalid :missing-qualifier]] (e/fault-reasons (e/combine :union x fault-a))))
  (doseq [definite [true false x (e/with-certificate true 10 true)]]
    (is (not (e/fault? definite)))
    (is (= definite (e/throw-if-fault! definite)))))

(defn- fold [op values] (reduce #(e/combine op %1 %2) (if (= :union op) false true) values))

(defn- permutations [xs]
  (if (empty? xs) [[]]
      (for [i (range (count xs)) rest (permutations (vec (concat (subvec xs 0 i) (subvec xs (inc i)))))]
        (into [(nth xs i)] rest))))

(deftest composition-is-independent-of-operand-order
  ;; Commutative, associative and idempotent with reasons: every evaluation
  ;; order of one union or intersection gives one value.
  (doseq [op [:union :intersection]
          values [[fault-a true x] [fault-a fault-b y] [x fault-a (e/combine :exclusion true x)]
                  [false fault-b y x] [fault-a y fault-b false]]]
    (is (apply = (map #(fold op %) (permutations values))) (pr-str [op (mapv e/value values)])))
  (doseq [op [:union :intersection] a operands b operands]
    (is (= (e/combine op a b) (e/combine op b a)))
    (is (= a (e/combine op a a)))))

(def ^:private level {false 0 true 2})
(defn- rank [v] (if (set? v) 1 (level v)))

(deftest composition-is-monotone-in-false-below-fault-below-true
  ;; Per completion, union and intersection are monotone and exclusion is
  ;; monotone left and antitone right in false < fault < true: the least
  ;; fixed point of positive recursion exists and is reached by accumulation.
  (let [terminals [false fault-a true]]
    (doseq [a terminals a' terminals b terminals b' terminals
            :when (and (<= (rank (world-value (e/value a) {})) (rank (world-value (e/value a') {})))
                       (<= (rank (world-value (e/value b) {})) (rank (world-value (e/value b') {}))))]
      (doseq [op [:union :intersection]]
        (is (<= (rank (world-value (e/value (e/combine op a b)) {}))
                (rank (world-value (e/value (e/combine op a' b')) {})))))
      (is (<= (rank (world-value (e/value (e/combine :exclusion a b')) {}))
              (rank (world-value (e/value (e/combine :exclusion a' b)) {})))))))

(deftest an-absorbed-fault-is-metered
  (let [ledger (counters/make-ledger)]
    (counters/call-with-ledger
     ledger
     (fn []
       (e/combine :union fault-a true)
       (e/combine :intersection false fault-b)
       (e/combine :union x fault-a)
       (e/combine :union true true)))
    (is (= 2 (:masked-faults (counters/snapshot ledger))))))

(deftest canonical-bounded-wire-round-trips
  (doseq [evidence [true false x y
                    (e/combine :exclusion x y)
                    (e/with-certificate x 100 true)
                    (e/with-certificate false nil false)
                    (e/fault :eacl.qualifier/invalid :missing-qualifier)
                    (e/combine :union x (e/fault :eacl.qualifier/invalid :missing-qualifier))
                    (e/combine :intersection (e/with-certificate y 100 true)
                               (e/combine :union (e/fault :eacl.caveat/evaluation :a)
                                          (e/fault :eacl.caveat/evaluation :b)))]]
    (let [payload (e/encode evidence)]
      (is (= evidence (e/decode payload)))
      (is (= payload (e/encode (e/decode payload))))))
  (let [wire (fn [node] (values/encode-bounded [:eacl.authorization/evidence 1 node nil true]
                                               {:maximum-size 65536 :maximum-depth 80 :maximum-entries 16384}))
        atom (first (e/value x))
        invalid-nodes [nil [] [1 false true] [atom false false]
                       [atom [atom false true] true]
                       [:fault []] [:fault [["private exception" :reason]]]
                       [atom [:fault []] true]
                       [atom [:fault [[:b :r] [:a :r]]] true]
                       [atom [:fault [[:a :r]]] [:fault [[:a :r]]]]]]
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
