(ns eacl.schema.expression-graph-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-graph :as graph]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]))

(defn- resolve-schema [schema]
  (resolver/resolve-parse-tree (parser/parse-schema schema)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest positive-recursion-and-strict-strata-test
  (let [schema
        "definition user {}
         definition folder {
           relation direct: user
           relation parent: folder
           relation banned: user
           permission blocked = banned
           permission view = direct + parent->view & direct
           permission allowed = view - blocked
           permission elevated = direct - allowed
         }"
        {:keys [dependency-certificate]} (resolve-schema schema)
        {:keys [components strata maximum-stratum]} dependency-certificate]
    (is (= 0 (get strata [:folder :view]))
        "a positive self-recursive component is accepted")
    (is (some #{[[:folder :view]]} components))
    (is (= 0 (get strata [:folder :blocked])))
    (is (= 1 (get strata [:folder :allowed])))
    (is (= 2 (get strata [:folder :elevated])))
    (is (= 2 maximum-stratum))))

(deftest arrow-dependencies-retain-typed-partition-paths-test
  (let [schema
        "definition user {}
         definition group {
           relation member: user
           permission active = member
         }
         definition team {
           relation member: user
           permission active = member
         }
         definition document {
           relation parent: group | team
           permission view = parent->active
         }"
        edges (get-in (resolve-schema schema)
                      [:dependency-certificate :edges])
        view-edges (filterv #(= [:document :view] (:from %)) edges)]
    (is (= [[:group :active] [:team :active]] (mapv :to view-edges)))
    (is (= [:group :team]
           (mapv #(get-in % [:via :partition :subject-type]) view-edges)))
    (is (every? #(= :positive (:sign %)) view-edges))))

(deftest negative-cycle-diagnostic-is-reproducible-and-typed-test
  (let [schema
        "definition user {}
         definition folder {
           relation direct: user
           permission p = direct - q
           permission q = p
         }"
        first-result (error-data #(resolve-schema schema))
        second-result (error-data #(resolve-schema schema))]
    (is (= first-result second-result))
    (is (= :eacl.schema/unstratified-exclusion (:type first-result)))
    (is (= [:folder :p]
           (get-in first-result [:negative-edge :from])))
    (is (= [:folder :q]
           (get-in first-result [:negative-edge :to])))
    (is (= [:root :right]
           (get-in first-result [:negative-edge :path])))
    (is (= [[:folder :p] [:folder :q] [:folder :p]]
           (:cycle first-result)))
    (is (= 2 (count (:cycle-edges first-result))))))

(deftest nested-negative-path-never-cancels-test
  (let [schema
        "definition user {}
         definition folder {
           relation direct: user
           permission p = direct - (direct - q)
           permission q = p
         }"
        data (error-data #(resolve-schema schema))]
    (is (= :eacl.schema/unstratified-exclusion (:type data)))
    (is (= :negative (get-in data [:negative-edge :sign])))
    (is (= [:root :right :right]
           (get-in data [:negative-edge :path])))))

(deftest malformed-missing-dependency-fails-closed-test
  (let [expressions
        [(expression/expression
           :document :view (expression/permission :missing))]
        data (error-data #(graph/build-certificate expressions))]
    (is (= :eacl.schema/missing-expression-dependency (:type data)))
    (is (= [:document :missing] (get-in data [:edge :to])))))
