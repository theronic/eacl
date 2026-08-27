(ns eacl.schema.expression-graph-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-graph :as graph]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.operator-engine.oracle :as oracle]
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

(deftest complete-candidate-validation-is-all-or-error-test
  (let [schema
        "definition user {}
         definition document {
           relation reader: user
           permission accepted = reader
           permission p = reader - q
           permission q = p
         }"
        data (error-data #(resolve-schema schema))]
    (is (= :eacl.schema/unstratified-exclusion (:type data)))
    (is (nil? (:expressions data))
        "validation never exposes the otherwise valid permission as a partial candidate")
    (is (nil? (:expression-metadata data)))
    (is (nil? (:dependency-certificate data)))))

(deftest malformed-missing-dependency-fails-closed-test
  (let [expressions
        [(expression/expression
           :document :view (expression/permission :missing))]
        data (error-data #(graph/build-certificate expressions))]
    (is (= :eacl.schema/missing-expression-dependency (:type data)))
    (is (= [:document :missing] (get-in data [:edge :to])))))

(defn- random-int!
  "Portable deterministic LCG. The intermediate product remains below 2^53."
  [state bound]
  (let [next-value (mod (+ (* @state 1664525) 1013904223) 4294967296)]
    (reset! state next-value)
    (mod next-value bound)))

(defn- graph-case
  [state]
  (let [permission-count (inc (random-int! state 15))
        names (mapv #(keyword (str "p" %)) (range permission-count))
        specifications
        (mapv (fn [_]
                (vec
                  (for [_ (range (random-int! state
                                              (inc permission-count)))]
                    [(nth names (random-int! state permission-count))
                     (if (zero? (random-int! state 4))
                       :negative
                       :positive)])))
              names)
        resolved-node
        (fn [[target sign]]
          (if (= :negative sign)
            (expression/exclusion
              (expression/relation :seed [:user])
              (expression/permission target))
            (expression/permission target)))
        oracle-node
        (fn [[target sign]]
          (if (= :negative sign)
            [:exclusion [:relation :seed] [:permission target]]
            [:permission target]))
        root
        (fn [node-fn dependencies]
          (let [nodes (mapv node-fn dependencies)]
            (cond
              (empty? nodes) ((if (= node-fn resolved-node)
                                expression/relation
                                (fn [_ _] [:relation :seed]))
                              :seed [:user])
              (= 1 (count nodes)) (first nodes)
              (= node-fn resolved-node) (expression/union nodes)
              :else (into [:union] nodes))))]
    {:resolved
     (mapv (fn [name dependencies]
             (expression/expression
               :thing name (root resolved-node dependencies)))
           names specifications)
     :oracle
     {:permissions
      (into {}
            (map (fn [name dependencies]
                   [[:thing name] (root oracle-node dependencies)])
                 names specifications))}}))

(deftest signed-graph-differential-fuzz-test
  (let [state (atom 2718281)
        mismatch
        (first
          (keep
            (fn [case-id]
              (let [{:keys [resolved oracle]} (graph-case state)
                    expected (oracle/stratify oracle)
                    actual (try
                             {:certificate (graph/build-certificate resolved)}
                             (catch #?(:clj Exception :cljs :default) error
                               {:error (ex-data error)}))
                    accepted? (contains? actual :certificate)]
                (when (or (not= (:valid? expected) accepted?)
                          (and accepted?
                               (not= (:strata expected)
                                     (get-in actual [:certificate :strata]))))
                  {:case-id case-id
                   :expected expected
                   :actual actual})))
            (range 1000)))]
    (is (nil? mismatch) (pr-str mismatch))))
