(ns eacl.caveats.definition-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.caveats.definition :as definition]
            [eacl.spicedb.parser :as parser]
            [eacl.schema.expression-resolver :as resolver]))

(def source
  "caveat in_region(region string, accepted list<string>) {\n region in accepted\n}\ndefinition user {}\ndefinition doc {\n relation viewer: user\n permission view = viewer\n}")

(deftest top-level-typed-caveat-declarations
  (let [schema (resolver/validate-schema source)
        entity (first (:caveats schema))
        decoded (definition/decode-entity entity)]
    (is (= 1 (count (:caveats schema))))
    (is (= "in_region" (:name decoded)))
    (is (= [["accepted" [:list :string]] ["region" :string]] (:parameters decoded)))
    (is (= [:in [:param "region"] [:param "accepted"]] (:plan decoded)))
    (is (= entity (definition/entity (:name decoded) (:parameters decoded) (:source decoded))))))

(defn- reason [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:reason (ex-data e)))))

(deftest definitions-record-the-lowest-admitting-profile
  (let [parameters {"inventory" [:list :string] "sensitive" [:list :string]}
        plain (definition/entity "plain" parameters "\"passport\" in inventory")
        macro (definition/entity "nothing_sensitive" parameters "!inventory.exists(item, item in sensitive)")]
    (is (= "eacl-cel/1" (:eacl.caveat/profile-version plain))
        "a definition without comprehensions keeps its profile 1 content")
    (is (= "eacl-cel/2" (:eacl.caveat/profile-version macro)))
    (is (= "eacl-cel/2" (:profile (definition/decode-entity macro))))
    (is (= [:not [:exists [:param "inventory"] "item" [:in [:var "item"] [:param "sensitive"]]]]
           (:plan (definition/decode-entity macro))))
    (doseq [changed [(assoc macro :eacl.caveat/profile-version "eacl-cel/1")
                     (assoc plain :eacl.caveat/profile-version "eacl-cel/2")]]
      (is (= :definition-shape (reason #(definition/decode-entity changed)))
          "a recorded profile other than the lowest admitting one is not canonical"))
    (is (= :unsupported-profile
           (reason #(definition/decode-header (assoc plain :eacl.caveat/profile-version "eacl-cel/3")))))))

(defn error-type [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:type (ex-data e)))))

(deftest caveat-schema-rejections
  (doseq [source ["caveat c(a bool, a int) { a }\ndefinition user {}"
                  "caveat c(a string) { a }\ndefinition user {}"
                  "caveat c(a bool) { missing }\ndefinition user {}"
                  "caveat c(a list<list<int>>) { true }\ndefinition user {}"
                  "caveat c(a bool) { a }\ncaveat c(a bool) { a }\ndefinition user {}"]]
    (is (keyword? (error-type #(resolver/validate-schema source)))))
  (is (= :eacl.schema/unsupported-feature
         (error-type #(resolver/validate-schema
                       "caveat c(a bool) { a }\ndefinition user {}\ndefinition doc {\n relation viewer: user with c\n}")))))

(deftest braces-in-caveat-literals-and-comments
  (let [schema (resolver/validate-schema
                "caveat c(a string) {\n // a } comment\n a == \"}\"\n}\ndefinition user {}")]
    (is (= [:eq [:param "a"] [:literal :string "}"]]
           (:plan (definition/decode-entity (first (:caveats schema))))))))

(defn staged [branches]
  (-> (str "caveat c(a bool) { a }\ndefinition user {}\ndefinition doc {\n relation viewer: " branches "\n}")
      parser/parse-schema parser/transform-schema parser/staged-relation-entities))

(deftest required-and-optional-staged-branches
  (is (false? (:eacl.relation/allows-unqualified? (first (staged "user with c")))))
  (is (true? (:eacl.relation/allows-unqualified? (first (staged "user | user with c")))))
  (is (= 1 (count (staged "user | user with c"))))
  (is (= [[:eacl.caveat/name "c"]] (:eacl.relation/caveats (first (staged "user with c")))))
  (is (= :eacl.schema/duplicate-relation-branch (error-type #(staged "user with c | user with c"))))
  (is (= :eacl.schema/invalid-caveat-reference (error-type #(staged "user with missing")))))

(deftest over-long-member-is-rejected-at-schema-admission
  ;; Admitting this Caveat left every check through `viewer` failing with
  ;; :eacl.authorization/evaluation-failure [:eacl.caveat/evaluation :resource-limit].
  ;; SpiceDB accepts the expression, so EACL's profile limit makes it an
  ;; unsupported feature rather than an invalid Caveat.
  (let [member (apply str (repeat 4097 "a"))
        schema (str "caveat longfield(m map<bool>) {\n m." member " == true\n}\n"
                    "definition user {}\ndefinition doc {\n relation viewer: user with longfield\n"
                    " permission view = viewer\n}")
        data (try (resolver/validate-schema schema nil {:allow-caveats? true}) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
                    (ex-data e)))]
    (is (= :eacl.schema/unsupported-feature (:type data)))
    (is (= [{:type :caveat-profile :caveat "longfield" :reason :profile
             :profile-reason :resource-limit :offset 2}]
           (mapv #(select-keys % [:type :caveat :reason :profile-reason :offset]) (:issues data))))))

(defn- caveat-outcome [parameters body]
  (let [schema (str "caveat c(" parameters ") " body "\ndefinition user {}")]
    (try {:source (:eacl.caveat/expression-source
                   (first (:caveats (resolver/validate-schema schema nil {:allow-caveats? true}))))}
         (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
           (select-keys (ex-data e) [:type :reason :source-span])))))

(deftest caveat-source-is-the-spicedb-cel-expression
  ;; The stored source is the CEL text SpiceDB compiles: the body from its
  ;; first token to its last. Comments and whitespace around it are not part
  ;; of it, whatever they contain; a comment inside it is, and CEL accepts
  ;; `//` there but not `/* */`. A leading block comment used to be consumed
  ;; as schema whitespace only when it contained `}` (EACL-FORMAL-093).
  (doseq [[body source] [["{x == 1}" "x == 1"]
                         ["{  // note }\n  x == 1 }" "x == 1"]
                         ["{ /* } */ x == 1 }" "x == 1"]
                         ["{ /* c */ x == 1 }" "x == 1"]
                         ["{ /* a */ /* } */ x == 1 }" "x == 1"]
                         ["{ x == 1 /* } */ }" "x == 1"]
                         ["{ x == 1 /* c */ }" "x == 1"]
                         ["{\n x == 1\n /* c */\n}" "x == 1\n"]
                         ["{\n x == // inline }\n 1\n}" "x == // inline }\n 1\n"]]]
    (is (= {:source source} (caveat-outcome "x int" body)) body))
  (is (= {:source "s == \"/* } */\""}
         (caveat-outcome "s string" "{\n s == \"/* } */\" // } */\n}")))
  (testing "a block comment inside the expression is invalid CEL"
    ;; A line ending after `1` ends the expression's last token, so the
    ;; comment before it is inside the expression too.
    (doseq [[body expression] [["{ x == /* c */ 1 }" "x == /* c */ 1"]
                               ["{\n x == 1 /* c */\n}" "x == 1 /* c */\n"]]
            :let [schema-start (+ (count "caveat c(x int) ") (.indexOf body expression))]]
      (is (= {:type :eacl.caveat/invalid :reason :syntax-error
              :source-span [schema-start (+ schema-start (count expression))]}
             (caveat-outcome "x int" body))
          body)))
  (testing "a comment before the body is schema whitespace"
    (is (= {:source "x == 1"}
           (caveat-outcome "x int" "/* } */ { x == 1 }"))))
  (is (= {:type :eacl.caveat/invalid :reason :syntax-error :source-span [20 24]}
         (caveat-outcome "x int" "{   x == }"))
      "the source span covers the expression's tokens"))
