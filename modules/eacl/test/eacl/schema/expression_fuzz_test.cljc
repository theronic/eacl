(ns eacl.schema.expression-fuzz-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [clojure.string :as str]
            [eacl.operator-engine.oracle :as oracle]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]))

(defn- random-int! [state bound]
  (let [next-value (mod (+ (* @state 1664525) 1013904223) 4294967296)]
    (reset! state next-value)
    (mod next-value bound)))

(def relation-names [:a :b :c :d])

(defn- random-oracle-expression
  [state depth]
  (if (or (zero? depth) (zero? (random-int! state 4)))
    [:relation (nth relation-names (random-int! state (count relation-names)))]
    (case (random-int! state 3)
      0 (into [:union]
              (repeatedly (+ 2 (random-int! state 2))
                          #(random-oracle-expression state (dec depth))))
      1 (into [:intersection]
              (repeatedly (+ 2 (random-int! state 2))
                          #(random-oracle-expression state (dec depth))))
      2 [:exclusion
         (random-oracle-expression state (dec depth))
         (random-oracle-expression state (dec depth))])))

(defn- source-text [node]
  (case (first node)
    :relation (name (second node))
    :union (str "(" (str/join " + " (map source-text (rest node))) ")")
    :intersection
    (str "(" (str/join " & " (map source-text (rest node))) ")")
    :exclusion
    (str "(" (source-text (second node)) " - "
         (source-text (nth node 2)) ")")))

(defn- oracle-node [node]
  (case (:op node)
    :relation [:relation (:name node)]
    :permission [:permission (:name node)]
    :arrow [:arrow (:relation node)
            (get-in node [:partitions 0 :target-name])]
    :union (into [:union] (map oracle-node (:children node)))
    :intersection (into [:intersection] (map oracle-node (:children node)))
    :exclusion [:exclusion
                (oracle-node (:left node))
                (oracle-node (:right node))]))

(def test-resource [:document "d1"])
(def test-subjects [[:user "u0"] [:user "u1"] [:user "u2"]])

(def oracle-snapshot
  {:objects (set (conj test-subjects test-resource))
   :relationships
   #{{:resource test-resource :relation :a :subject [:user "u0"]}
     {:resource test-resource :relation :a :subject [:user "u1"]}
     {:resource test-resource :relation :b :subject [:user "u1"]}
     {:resource test-resource :relation :b :subject [:user "u2"]}
     {:resource test-resource :relation :c :subject [:user "u0"]}
     {:resource test-resource :relation :c :subject [:user "u2"]}}
   :permissions {}})

(defn- schema-text [source]
  (str "definition user {}\n"
       "definition document {\n"
       "relation a: user\n"
       "relation b: user\n"
       "relation c: user\n"
       "relation d: user\n"
       "permission view = " source "\n"
       "}"))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest parser-resolver-codec-denotation-fuzz-test
  (let [state (atom 8675309)
        mismatch
        (first
          (keep
            (fn [case-id]
              (let [expected (random-oracle-expression state 4)
                    source (source-text expected)
                    resolved
                    (first
                      (:expressions
                        (resolver/resolve-parse-tree
                          (parser/parse-schema (schema-text source)))))
                    actual (oracle-node (:root resolved))
                    expected-set
                    (oracle/acyclic-expression-denotation
                      oracle-snapshot expected test-resource)
                    actual-set
                    (oracle/acyclic-expression-denotation
                      oracle-snapshot actual test-resource)
                    encoded (expression/encode resolved)
                    decoded (expression/decode encoded)
                    dag (limits/normalized-dag resolved)
                    decoded-dag (limits/normalized-dag decoded)]
                (when-not (and (= expected-set actual-set)
                               (= resolved decoded)
                               (= encoded (expression/encode decoded))
                               (= dag decoded-dag))
                  {:case-id case-id
                   :source source
                   :expected expected
                   :actual actual
                   :expected-set expected-set
                   :actual-set actual-set})))
            (range 100)))]
    (is (nil? mismatch) (pr-str mismatch))))

(deftest canonical-codec-corruption-fuzz-test
  (let [state (atom 314159)
        accepted-corruption
        (first
          (keep
            (fn [case-id]
              (let [source (source-text (random-oracle-expression state 3))
                    resolved
                    (first
                      (:expressions
                        (resolver/resolve-parse-tree
                          (parser/parse-schema (schema-text source)))))
                    encoded (expression/encode resolved)
                    corruptions [(str encoded " ")
                                 (subs encoded 0 (dec (count encoded)))]
                    accepted (first (filter #(nil? (error-data
                                                     (fn []
                                                       (expression/decode %))))
                                            corruptions))]
                (when accepted
                  {:case-id case-id :source source :accepted accepted})))
            (range 100)))]
    (is (nil? accepted-corruption) (pr-str accepted-corruption))))
