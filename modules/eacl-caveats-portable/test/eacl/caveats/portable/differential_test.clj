(ns eacl.caveats.portable.differential-test
  "Differential certification against the JVM module, the certified reference.
   Complete contexts compare with cel-parser; incomplete ones with the JVM
   module's own portable path, including its admission and resource checks."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as caveat-evaluator]
            [eacl.caveats.plan :as plan]
            [eacl.caveats.portable :as portable]
            [eacl.caveats.jvm :as jvm]))

(def parameters
  {"b" :bool "c" :bool "i" :int "j" :int "s" :string "u" :string "t" :timestamp "v" :timestamp
   "bs" [:list :bool] "is" [:list :int] "ss" [:list :string] "ts" [:list :timestamp]
   "mb" [:map :string :bool] "mi" [:map :string :int] "ms" [:map :string :string]
   "mt" [:map :string :timestamp]})

(def ^:private scalar-types [:bool :int :string :timestamp])

(defn- names-of [type]
  (sort (keep (fn [[name t]] (when (= t type) name)) parameters)))

;; Small pools make equality, membership, prefixes and map keys collide often;
;; the open generators reach the rest of each admitted domain.
(def ^:private key-pool ["" "a" "b" "on" "za" "a😀b"])
(def ^:private string-pool (into key-pool ["ab" "ba" "😀" "é" "\u0000" "line\nbreak" "\"q\"" "\\" "/"]))
(def ^:private int-pool [-9007199254740991 -2 -1 0 1 2 3 9007199254740991])
(def ^:private instant-pool [-62135596800000 -1 0 1 2 253402300799999])

(def ^:private gen-int
  (gen/one-of [(gen/elements int-pool)
               (gen/large-integer* {:min -9007199254740991 :max 9007199254740991})]))
(def ^:private gen-string (gen/one-of [(gen/elements string-pool) gen/string]))
(def ^:private gen-timestamp
  (gen/fmap #(vector :timestamp %)
            (gen/one-of [(gen/elements instant-pool)
                         (gen/large-integer* {:min -62135596800000 :max 253402300799999})])))

(defn- gen-value [type]
  (case type
    :bool gen/boolean
    :int gen-int
    :string gen-string
    :timestamp gen-timestamp
    (if (= :list (first type))
      (gen/vector (gen-value (second type)) 0 5)
      (gen/map (gen/one-of [(gen/elements key-pool) gen/string-alphanumeric])
               (gen-value (nth type 2)) {:max-elements 4}))))

(defn- gen-leaf [type]
  (gen/one-of
   (cond-> [(gen/fmap #(vector :param %) (gen/elements (names-of type)))]
     (= :bool type) (conj (gen/fmap #(vector :literal :bool %) gen/boolean))
     (= :int type) (conj (gen/fmap #(vector :literal :int %) gen-int))
     ;; Short literals keep deep plans inside the 8192-byte source bound.
     (= :string type) (conj (gen/fmap #(vector :literal :string %)
                                      (gen/one-of [(gen/elements string-pool)
                                                   (gen/resize 12 gen/string)]))))))

(declare gen-expression)

(defn- gen-binary [ops type depth]
  (gen/tuple (gen/elements ops) (gen-expression type depth) (gen-expression type depth)))

(defn- gen-index [type depth]
  ;; m[key] and m.key both select a string key; a missing key is a fault.
  (gen/fmap (fn [[m key]] [:index [:param m] key])
            (gen/tuple (gen/elements (names-of [:map :string type]))
                       (gen/one-of [(gen/fmap #(vector :literal :string %) (gen/elements key-pool))
                                    (gen-expression :string depth)]))))

(defn gen-expression
  "Well-typed plans over every profile 1 operator and parameter type."
  [type depth]
  (if (zero? depth)
    (gen-leaf type)
    (let [depth (dec depth)]
      (case type
        :bool
        (gen/frequency
         [[1 (gen-leaf :bool)]
          [3 (gen/fmap #(vector :not %) (gen-expression :bool depth))]
          [10 (gen-binary [:and :or] :bool depth)]
          [2 (gen/bind (gen/elements scalar-types) #(gen-binary [:eq :ne] % depth))]
          [2 (gen/bind (gen/elements [:int :timestamp]) #(gen-binary [:lt :le :gt :ge] % depth))]
          [2 (gen/bind (gen/elements scalar-types)
                       (fn [t] (gen/fmap (fn [[item list]] [:in item [:param list]])
                                         (gen/tuple (gen-expression t depth)
                                                    (gen/elements (names-of [:list t]))))))]
          [1 (gen/fmap (fn [[key m]] [:in key [:param m]])
                       (gen/tuple (gen-expression :string depth)
                                  (gen/elements (mapcat #(names-of [:map :string %]) scalar-types))))]
          [2 (gen-binary [:contains :starts-with :ends-with] :string depth)]
          [2 (gen-index :bool depth)]])
        (gen/frequency [[3 (gen-leaf type)] [1 (gen-index type depth)]])))))

(def ^:private operators {:and "&&" :or "||" :eq "==" :ne "!=" :lt "<" :le "<=" :gt ">" :ge ">=" :in "in"})
(def ^:private method-names {:contains "contains" :starts-with "startsWith" :ends-with "endsWith"})

(defn- string-literal [s]
  (str "\"" (apply str (map (fn [c]
                              (cond (= c \") "\\\""
                                    (= c \\) "\\\\"
                                    (< (int c) 32) (format "\\u%04x" (int c))
                                    :else c))
                            s))
       "\""))

(defn- member? [key]
  (and (re-matches #"[A-Za-z_][A-Za-z0-9_]*" key) (not (#{"true" "false" "in"} key))))

(defn source
  "Renders a plan as fully grouped profile 1 source."
  [[op a b]]
  (case op
    :literal (if (= :string a) (string-literal b) (str b))
    :param a
    :not (str "!(" (source a) ")")
    :index (if (and (= :literal (first b)) (member? (nth b 2)))
             (str "(" (source a) ")." (nth b 2))
             (str "(" (source a) ")[" (source b) "]"))
    (if-let [method (method-names op)]
      (str "(" (source a) ")." method "(" (source b) ")")
      (str "(" (source a) " " (operators op) " " (source b) ")"))))

(def ^:private gen-complete
  (apply gen/hash-map (mapcat (fn [[name type]] [name (gen-value type)]) parameters)))

(def ^:private gen-names (gen/fmap set (gen/vector (gen/elements (keys parameters)))))

(def ^:private gen-inputs
  "Complete, incomplete and bound-over-request inputs, with some wrong types.
   Bound values come from an independent context, so overlaps differ."
  (gen/bind
   (gen/tuple (gen/frequency (mapv (fn [[weight mode]] [weight (gen/return mode)])
                                   [[3 :complete] [3 :split] [3 :partial]
                                    [3 :bound-over-request] [1 :wrong-type]]))
              gen-complete gen-complete gen-names gen-names
              (gen/elements (keys parameters)))
   (fn [[mode request bound dropped overridden wrong]]
     (gen/return
      [mode
       (case mode
         :complete request
         :split (apply dissoc request dropped)
         :partial (apply dissoc request dropped)
         :bound-over-request request
         :wrong-type (assoc request wrong (if (= :string (get parameters wrong)) 7 "wrong")))
       (case mode
         :complete nil
         :split (select-keys bound (into dropped overridden))
         :partial (select-keys bound overridden)
         :bound-over-request (select-keys bound overridden)
         :wrong-type {})]))))

(def ^:private portable-engine (portable/evaluator))
(def ^:private jvm-engine (jvm/evaluator))

(defn- outcomes [entity request bound]
  [(caveat-evaluator/evaluate jvm-engine entity request bound)
   (caveat-evaluator/evaluate portable-engine entity request bound)])

(deftest generated-definitions-and-contexts-match-the-jvm-evaluator
  (let [seen (atom {})
        property
        (prop/for-all [expression (gen/sized #(gen-expression :bool (min 5 (quot % 8))))
                       [mode request bound] gen-inputs]
          (let [text (source expression)
                ;; The renderer must reproduce the generated plan exactly.
                parsed (:plan (plan/compile-plan text parameters))
                [expected actual] (outcomes (definition/entity "generated" parameters text) request bound)]
            (swap! seen update [mode (:outcome expected) (:reason expected)] (fnil inc 0))
            (and (= expression parsed) (= expected actual))))
        result (tc/quick-check 4000 property :seed 20260927 :max-size 72)]
    (is (:pass? result) (pr-str (select-keys result [:seed :fail :shrunk])))
    (testing "the generator reaches every outcome and fault from complete contexts"
      (let [complete (set (keep (fn [[[mode outcome reason] _]]
                                  (when (#{:complete :bound-over-request} mode) [outcome reason]))
                                @seen))]
        (is (every? complete [[:true nil] [:false nil] [:error :missing-map-key]]) (pr-str complete))
        (is (some (fn [[[_ outcome] _]] (= :conditional outcome)) @seen))
        (is (some (fn [[[_ _ reason] _]] (= :context-type reason)) @seen))))))

(deftest finite-four-valued-logic-matches-the-jvm-evaluator
  (let [parameters {"a" :bool "b" :bool "m" [:map :string :bool] "k" :string}
        atoms [[:param "a"] [:param "b"] [:literal :bool false] [:literal :bool true]
               [:index [:param "m"] [:literal :string "on"]] [:index [:param "m"] [:param "k"]]]
        expressions (concat atoms
                            (map #(vector :not %) atoms)
                            (for [op [:and :or] x atoms y atoms] [op x y])
                            (for [op [:and :or] x atoms y atoms] [op [:not x] [:or y [:not x]]]))
        contexts (for [a [nil false true] b [nil false true] m [nil {} {"on" false} {"on" true}]
                       k [nil "on" "off"]]
                   (cond-> {} (some? a) (assoc "a" a) (some? b) (assoc "b" b)
                           (some? m) (assoc "m" m) (some? k) (assoc "k" k)))]
    (doseq [expression expressions
            :let [entity (definition/entity "finite" parameters (source expression))]
            context contexts
            bound [nil {} {"a" true} {"m" {"on" true}}]]
      (let [[expected actual] (outcomes entity context bound)]
        (is (= expected actual) (pr-str [expression context bound]))))))

(deftest finite-comparisons-match-the-jvm-evaluator
  (doseq [[type values] [[:int int-pool] [:timestamp (map #(vector :timestamp %) instant-pool)]]
          source ["a < b" "a <= b" "a > b" "a >= b" "a == b" "a != b" "a in xs" "!(a in xs)"]
          :let [parameters {"a" type "b" type "xs" [:list type]}
                entity (definition/entity "ordered" parameters source)]
          a values b values]
    (let [[expected actual] (outcomes entity {"a" a "b" b "xs" [b]} nil)]
      (is (= expected actual) (pr-str [source a b])))))

(deftest resource-limits-and-definition-faults-match-the-jvm-evaluator
  (let [text (apply str (repeat 2048 "x"))
        parameters {"a" :string "b" :string "xs" [:list :string] "flag" :bool}
        entity (definition/entity "bounded" parameters "true || a.contains(b) || b in xs")]
    (doseq [[request bound] [[{"a" text "b" (subs text 0 1024)} nil]
                             [{"a" text} {"b" (subs text 0 1024)}]
                             [{"xs" (vec (repeat 129 "x"))} nil]
                             [{"a" (apply str (repeat 4097 "x"))} nil]
                             [{"a" "ok"} {"xs" (vec (repeat 129 "x"))}]
                             [{"a" "ok" "b" "o" "xs" [] "flag" true} {}]]]
      (let [[expected actual] (outcomes entity request bound)]
        (is (= expected actual) (pr-str [(count (get request "a")) bound]))))
    (doseq [changed [(assoc entity :db/id 7)
                     (assoc entity :unexpected true)
                     (dissoc entity :eacl.caveat/parameters-payload)
                     (assoc entity :eacl.caveat/parameters-payload "[:eacl.caveat/parameters 1 []]")
                     (assoc entity :eacl.caveat/parameters-payload "malformed")
                     (assoc entity :eacl.caveat/profile-version "unknown")
                     (assoc entity :eacl.caveat/name "not a name")
                     (assoc entity :eacl.caveat/expression-source "flag && missing")
                     (assoc entity :eacl.caveat/expression-source "a")
                     nil {}]]
      (let [[expected actual] (outcomes changed {"flag" true} nil)]
        (is (= expected actual) (pr-str changed))))))

(deftest host-values-match-the-jvm-reference
  ;; Complete contexts are evaluated by cel-parser, which reads a Boolean
  ;; object's value and matches exact string keys. The JVM module's partial
  ;; path treats such values as host truthiness and comparator matches; this
  ;; evaluator rebuilds canonical values in both paths instead.
  (let [false-object (Boolean. false)
        folded (sorted-map-by #(compare (str/lower-case %1) (str/lower-case %2)) "A" true)
        parameters {"a" :bool "m" [:map :string :bool] "k" :string "i" :int "t" :timestamp}
        complete {"a" false-object "m" folded "k" "a" "i" 1N "t" [:timestamp 1N]}]
    (doseq [source ["a" "!(a)" "a || m[k]" "m[k] && false" "k in m" "i == 1 && t == t" "a == false"]
            :let [entity (definition/entity "host" parameters source)
                  [expected actual] (outcomes entity complete nil)]]
      (is (= expected actual) source))))

(deftest identity-and-process-default
  (is (= (:profile-fingerprint jvm/capability) (:profile-fingerprint portable/capability)))
  (is (= (:profile jvm/capability) (:profile portable/capability)))
  (is (not= (:fingerprint jvm/capability) (:fingerprint portable/capability)))
  ;; Whatever loaded first, the JVM module is the default once it is loaded.
  (is (= jvm/capability (caveat-evaluator/descriptor (caveat-evaluator/default-evaluator)))))
