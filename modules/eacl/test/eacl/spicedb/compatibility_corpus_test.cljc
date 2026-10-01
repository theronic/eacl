(ns eacl.spicedb.compatibility-corpus-test
  "Checks EACL's schema admission against SpiceDB v1.56.0's verdict on every
  schema of the compatibility corpus (fixtures/spicedb-1.56-corpus.edn,
  docs/spicedb-schema-compatibility.md).

  - SpiceDB accepts: EACL accepts, or rejects with
    `:eacl.schema/unsupported-feature`.
  - SpiceDB rejects: EACL rejects. Unsupported-feature is reserved for valid
    SpiceDB: only `allowed-unsupported-rejections`, caveat bodies that use CEL
    EACL can neither evaluate nor type-check, may report it.
  - Both accept: every relation and permission EACL reads matches SpiceDB's
    ReadSchema text, so precedence, grouping, arrows and subject types agree."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            #?(:clj [clojure.java.io :as io])
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]
            [eacl.spicedb.syntax :as syntax]
            [eacl.spicedb.validation :as validation]))

(def corpus-resource "eacl/spicedb/fixtures/spicedb-1.56-corpus.edn")

(defn- read-corpus []
  (edn/read-string
   #?(:clj (slurp (io/resource corpus-resource))
      ;; Node runs from the repository root or a module directory.
      :cljs (let [fs (js/require "fs")
                  path (some #(when (.existsSync fs %) %)
                             (map #(str % corpus-resource)
                                  ["modules/eacl/test/" "test/" "../eacl/test/"]))]
              (.readFileSync fs path "utf8")))))

(def corpus (delay (read-corpus)))

(def allowed-unsupported-rejections
  "SpiceDB rejects these bodies for calling an undeclared function or method;
   EACL's checker does not model CEL calls, so it fails closed as unsupported."
  #{"cel/unknown-function" "cel/unknown-method"})

(defn- eacl-outcome [schema]
  (try
    (resolver/validate-schema schema nil {:allow-caveats? true})
    {:outcome :accept}
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo) e
      {:outcome :reject :type (:type (ex-data e)) :issues (:issues (ex-data e)) :errors (:errors (ex-data e))
       :message (ex-message e)})))

;; ---------------------------------------------------------------- canonical text

(defn- emit-rewrite [ir]
  (let [all-union? (fn all-union? [node]
                     (and (= :union (:op node))
                          (every? #(or (not (:children %)) (all-union? %)) (:children node))))
        emit-child (fn [child]
                     (if (:children child)
                       (if (all-union? child)
                         (emit-rewrite child)
                         (str "(" (emit-rewrite child) ")"))
                       (case (:op child)
                         :nil "nil"
                         :self "self"
                         :computed (:name child)
                         :arrow (if-let [f (:function child)]
                                  (str (:tupleset child) "." f "(" (:target child) ")")
                                  (str (:tupleset child) "->" (:target child))))))]
    (if (:children ir)
      (str/join (case (:op ir) :union " + " :intersection " & " :exclusion " - ")
                (map emit-child (:children ir)))
      (emit-child ir))))

(defn- source-ast->ir
  "EACL's source AST in SpiceDB's rewrite shape (unions and intersections
   flattened, as SpiceDB's translator flattens them)."
  [node]
  (let [flat (fn [op]
               {:op op
                :children (vec (mapcat #(let [c (source-ast->ir %)]
                                          (if (= op (:op c)) (:children c) [c]))
                                       (:children node)))})]
    (case (:op node)
      :identifier {:op :computed :name (:name node)}
      :self {:op :self}
      :arrow {:op :arrow :tupleset (:base node) :target (:target node)
              :function (when (= :any (:syntax node)) "any")}
      :union (flat :union)
      :intersection (flat :intersection)
      :exclusion {:op :exclusion :children [(source-ast->ir (:left node))
                                            (source-ast->ir (:right node))]})))

(defn- canonical-members
  "{definition #{line}} for relation and permission lines of SpiceDB's
   ReadSchema text."
  [text]
  (loop [[line & more] (str/split-lines text) current nil out {}]
    (if (nil? line)
      out
      (let [line (str/trim line)]
        (cond
          (str/starts-with? line "definition ")
          (let [name (second (re-find #"^definition (\S+)" line))]
            (recur more name (update out name (fnil identity #{}))))
          (= "}" line) (recur more nil out)
          (and current (or (str/starts-with? line "relation ") (str/starts-with? line "permission ")))
          (recur more current (update out current conj line))
          :else (recur more current out))))))

(defn- relation-line [[_ [_ name-node] types]]
  (str "relation " (second name-node) ": "
       (str/join " | "
                 (for [ref (rest types)
                       :let [[_ path & more] ref
                             modifier (some #(when (= :relation-modifier (first %)) %) more)
                             caveat (some #(when (= :caveat-ref (first %)) (second (second %))) more)
                             expiration? (some #(= [:expiration] %) more)]]
                   (str (str/join "/" (map second (rest path)))
                        (when modifier
                          (let [[kind ident] (second modifier)]
                            (if (= :wildcard kind) ":*" (str "#" (second ident)))))
                        (when (or caveat expiration?)
                          (str " with " caveat (when (and caveat expiration?) " and ")
                               (when expiration? "expiration"))))))))

(defn- eacl-members
  "{definition #{line}}: relations from the validated tree, permissions from
   the source AST EACL resolves."
  [schema]
  (let [tree (parser/parse-schema schema)
        {validated :tree} (validation/validate schema tree)
        transformed (parser/transform-schema tree)
        relations (into {}
                        (for [[_ path body] (rest validated)
                              :when (= :type-path (first path))]
                          [(str/join "/" (map second (rest path)))
                           (set (map relation-line (filter #(= :relation (first %)) (rest body))))]))]
    (merge-with into relations
                (into {}
                      (for [[definition {:keys [permissions]}] (:definitions transformed)]
                        [definition
                         (set (for [{:keys [name expression]} permissions]
                                (str "permission " name " = "
                                     (emit-rewrite (source-ast->ir
                                                    (parser/permission-expression->source-ast expression))))))])))))

;; ---------------------------------------------------------------- tests

(deftest corpus-is-well-formed-test
  (let [{:keys [entries spicedb counts]} @corpus]
    (is (= "v1.56.0" (:version spicedb)))
    (is (<= 2000 (count entries)))
    (is (= (:entries counts) (count entries)))
    (is (= (count entries) (count (set (map :id entries)))))
    (is (= (count entries) (count (set (map :schema entries)))))
    (is (every? #{:accept :reject} (map :spicedb entries)))))

(deftest spicedb-verdicts-test
  (let [results (for [{:keys [id spicedb schema] :as entry} (:entries @corpus)]
                  (assoc entry :eacl (eacl-outcome schema)))
        false-rejects (filter #(and (= :accept (:spicedb %))
                                    (= :reject (get-in % [:eacl :outcome]))
                                    (not= :eacl.schema/unsupported-feature (get-in % [:eacl :type])))
                              results)
        false-accepts (filter #(and (= :reject (:spicedb %))
                                    (= :accept (get-in % [:eacl :outcome])))
                              results)
        unsupported-rejections (filter #(and (= :reject (:spicedb %))
                                             (= :eacl.schema/unsupported-feature (get-in % [:eacl :type])))
                                       results)]
    (testing "a schema SpiceDB accepts is accepted, or rejected only as an unsupported feature"
      (is (empty? (map (juxt :id (comp :type :eacl) (comp :message :eacl)) false-rejects))))
    (testing "a schema SpiceDB rejects is rejected"
      (is (empty? (map :id false-accepts))))
    (testing "unsupported-feature names features of valid SpiceDB schemas"
      (is (= allowed-unsupported-rejections (set (map :id unsupported-rejections))))
      (is (empty? (remove #(= #{:caveat-profile} (set (map :type (get-in % [:eacl :issues]))))
                          unsupported-rejections))))
    (testing "rejections are typed EACL errors"
      (is (empty? (->> results
                       (filter #(= :reject (get-in % [:eacl :outcome])))
                       (remove #(some-> (get-in % [:eacl :type]) namespace (str/starts-with? "eacl")))
                       (map (juxt :id (comp :type :eacl)))))))))

(deftest spicedb-canonical-schema-test
  (doseq [{:keys [id schema canonical]} (:entries @corpus)
          :when (and canonical (= :accept (:outcome (eacl-outcome schema))))]
    (let [expected (canonical-members canonical)
          actual (eacl-members schema)]
      (is (= expected (select-keys actual (keys expected))) id))))

(defn- corpus-outcome [id]
  (eacl-outcome (:schema (first (filter #(= id (:id %)) (:entries @corpus))))))

(deftest semicolon-terminators-test
  ;; EACL-RS-001 (EACL-FORMAL-095): `;` terminates statements, as in SpiceDB.
  (is (= :accept (:outcome (corpus-outcome "eacl-rs/001-semicolons"))))
  (is (= :accept (:outcome (corpus-outcome "terminators/semicolons-one-line"))))
  (is (= :accept (:outcome (corpus-outcome "terminators/double-semicolon-inside"))))
  (is (= :eacl.schema/parse-error (:type (corpus-outcome "terminators/top-double-semicolon")))))

(deftest continuation-lines-test
  ;; EACL-RS-002 (EACL-FORMAL-096): an expression continues on the next line
  ;; after a binary operator, and only there.
  (is (= :accept (:outcome (corpus-outcome "eacl-rs/002-continuation"))))
  (is (= :accept (:outcome (corpus-outcome "terminators/pipe-end-of-line"))))
  (is (= :eacl.schema/parse-error (:type (corpus-outcome "terminators/plus-next-line"))))
  (is (= :eacl.schema/parse-error (:type (corpus-outcome "terminators/brace-next-line")))))

(deftest glued-keywords-test
  ;; EACL-RS-003 (EACL-FORMAL-097): a keyword glued to a name is one identifier.
  (doseq [id ["eacl-rs/003-glued-relation" "eacl-rs/003-glued-permission"
              "eacl-rs/003-glued-definition" "eacl-rs/003-glued-caveat"
              "eacl-rs/003-glued-with"]]
    (is (= :eacl.schema/parse-error (:type (corpus-outcome id))) id))
  (is (= :eacl.schema/expression-resolution-failed
         (:type (corpus-outcome "eacl-rs/003-glued-nil")))))

(deftest spicedb-errors-precede-unsupported-features-test
  ;; A subject relation is unsupported, but this schema is invalid SpiceDB first.
  (let [{:keys [outcome type errors]}
        (eacl-outcome "definition user {}
definition group {
 relation member: user
}
definition doc {
 relation viewer: group#member
 permission view = viewer + nothing
}")]
    (is (= :reject outcome))
    (is (= :eacl.schema/expression-resolution-failed type))
    (is (= [:missing-reference] (map :type errors))))
  (is (= :eacl.schema/unsupported-feature
         (:type (eacl-outcome "definition user {}
definition group {
 relation member: user
}
definition doc {
 relation viewer: group#member
 permission view = viewer
}"))))
  ;; Wildcards are served: SpiceDB's errors still come first.
  (let [{:keys [outcome type errors]}
        (eacl-outcome "definition user {}
definition doc {
 relation viewer: user:*
 permission view = viewer + nothing
}")]
    (is (= :reject outcome))
    (is (= :eacl.schema/expression-resolution-failed type))
    (is (= [:missing-reference] (map :type errors))))
  (is (= :accept
         (:outcome (eacl-outcome "definition user {}
definition doc {
 relation viewer: user:* | user:* with enabled
 permission view = viewer
}
caveat enabled(flag bool) { flag }")))))

(deftest spicedb-defect-reproductions-are-rejected-test
  ;; SpiceDB accepts some of these through a cache keyed by relation name only;
  ;; EACL applies the transitive-wildcard rule per definition and relation.
  (doseq [{:keys [id schema]} (:spicedb-defects @corpus)]
    (let [{:keys [outcome type errors]} (eacl-outcome schema)]
      (is (= :reject outcome) id)
      (is (= :eacl.schema/expression-resolution-failed type) id)
      (is (= #{:transitive-wildcard} (set (map :type errors))) id))))

(deftest parse-tree-shape-test
  (is (= [:schema
          [:definition [:type-path [:identifier "user"]] [:definition-body]]
          [:definition [:type-path [:identifier "doc"]]
           [:definition-body
            [:relation [:relation-name [:identifier "viewer"]]
             [:relation-type-expr [:relation-type-ref [:type-path [:identifier "user"]]]]]
            [:permission [:identifier "view"]
             [:permission-expr
              [:exclusion-expr
               [:intersect-expr
                [:union-expr
                 [:arrow-expr [:simple-arrow-expr [:base-expr [:identifier "viewer"]]]]]]]]]]]]
         (syntax/parse "definition user {}\ndefinition doc { relation viewer: user; permission view = viewer; }"))))
