(ns eacl.schema.expression-resolver-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.schema.expression-resolver :as resolver]
            [eacl.spicedb.parser :as parser]))

(defn- error-data [schema]
  (try
    (resolver/resolve-parse-tree (parser/parse-schema schema))
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(def resolved-schema
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
     relation reader: user
     relation banned: user
     relation parent: team | group
     permission edit = reader
     permission view = edit & parent->active - banned
   }")

(deftest complete-schema-resolution-test
  (let [{:keys [definitions expressions]}
        (resolver/resolve-parse-tree (parser/parse-schema resolved-schema))
        view (some #(when (= :view (:permission-name %)) %) expressions)]
    (is (= [:document :group :team :user] definitions))
    (is (= 4 (count expressions)))
    (is (= :exclusion (get-in view [:root :op])))
    (is (= :permission (get-in view [:root :left :children 0 :op])))
    (is (= :edit (get-in view [:root :left :children 0 :name])))
    (is (= [:group :team]
           (mapv :subject-type
                 (get-in view [:root :left :children 1 :partitions]))))
    (is (= [:permission :permission]
           (mapv :target-kind
                 (get-in view [:root :left :children 1 :partitions]))))))

(deftest resolution-is-declaration-order-independent-test
  (let [reordered
        "definition user {}
         definition team {
           permission active = member
           relation member: user
         }
         definition group {
           permission active = member
           relation member: user
         }
         definition document {
           permission edit = reader
           relation parent: group | team
           relation banned: user
           relation reader: user
           permission view = edit & parent->active - banned
         }"
        left (:expressions
              (resolver/resolve-parse-tree
                (parser/parse-schema resolved-schema)))
        right (:expressions
               (resolver/resolve-parse-tree (parser/parse-schema reordered)))]
    (is (= left right))))

(deftest positive-recursive-references-remain-finite-test
  (let [schema
        "definition user {}
         definition document {
           relation seed: user
           relation editor: user
           permission aaa = seed + bbb
           permission bbb = aaa & editor
         }"
        {:keys [expressions]}
        (resolver/resolve-parse-tree (parser/parse-schema schema))]
    (is (= [:aaa :bbb] (mapv :permission-name expressions)))
    (is (= :permission
           (get-in (second expressions) [:root :children 0 :op])))
    (is (= :aaa
           (get-in (second expressions) [:root :children 0 :name])))))

(deftest deterministic-missing-and-type-invalid-errors-test
  ;; Every reference SpiceDB rejects is reported together. SpiceDB does not
  ;; check an arrow's target, so `parent->target` adds no issue of its own.
  (let [schema
        "definition user {}
         definition document {
           relation parent: missing_type
           permission base = missing
           permission perm = base->target + parent->target
         }"
        {:keys [type errors]} (error-data schema)]
    (is (= :eacl.schema/expression-resolution-failed type))
    (is (= [:type-invalid-reference
            :missing-reference
            :type-invalid-reference]
           (mapv :type errors)))
    (is (= [[:relation :parent :subject-type :missing_type] [:root] [:root :child 0]]
           (mapv :path errors)))
    (is (= errors (vec (sort-by (juxt (comp str :resource-type)
                                      (comp str :permission-name)
                                      (comp pr-str :path)
                                      (comp str :type))
                                errors))))))

(deftest mixed-arrow-target-kind-is-ambiguous-test
  (let [schema
        "definition user {}
         definition group {
           relation access: user
         }
         definition team {
           relation member: user
           permission access = member
         }
         definition document {
           relation parent: group | team
           permission view = parent->access
         }"
        ;; SpiceDB accepts this arrow; EACL needs one target kind.
        {:keys [type issues]} (error-data schema)
        ambiguous (some #(when (and (= :ambiguous-reference (:reason %))
                                    (= :access (:name %))) %)
                        issues)]
    (is (= :eacl.schema/unsupported-feature type))
    (is (= :arrow-target (:type ambiguous)))
    (is (= [:permission :relation] (:kinds ambiguous)))))

(deftest arrow-target-missing-on-a-subject-type-is-unsupported-test
  ;; SpiceDB accepts an arrow whose target some subject type lacks (that type
  ;; contributes nothing); EACL resolves a target on every subject type.
  (let [{:keys [type issues]}
        (error-data "definition user {}
                     definition group {
                       relation member: user
                     }
                     definition team {}
                     definition document {
                       relation parent: group | team
                       permission view = parent->member
                     }")]
    (is (= :eacl.schema/unsupported-feature type))
    (is (= [[:arrow-target :missing-reference :team]]
           (mapv (juxt :type :reason :subject-type) issues)))))

(deftest resolution-enforces-source-and-normalized-limits-test
  (let [parse-tree (parser/parse-schema resolved-schema)
        {:keys [expressions expression-metadata]}
        (resolver/resolve-parse-tree parse-tree)
        bounded
        (resolver/resolve-parse-tree parse-tree
          {:maximum-source-nodes 5
           :maximum-source-depth 3
           :maximum-direct-fan-in 2
           :maximum-normalized-nodes 5
           :maximum-child-slots 7
           :maximum-words 8
           :maximum-checkpoint-weight 4096})
        exact
        (into {}
              (map (fn [resolved metadata]
                     [[(:resource-type resolved) (:permission-name resolved)]
                      {:source (:source-metrics metadata)
                       :normalized (:normalized-metrics metadata)}])
                   expressions expression-metadata))
        view (get exact [:document :view])]
    (is (map? view))
    (is (= 5 (get-in view [:source :node-count])))
    (is (= 5 (get-in view [:normalized :node-count])))
    (is (= exact
           (into {}
                 (map (fn [resolved metadata]
                        [[(:resource-type resolved) (:permission-name resolved)]
                         {:source (:source-metrics metadata)
                          :normalized (:normalized-metrics metadata)}])
                      (:expressions bounded)
                      (:expression-metadata bounded)))))
    (is (= :node-count
           (:dimension
             (try
               (resolver/resolve-parse-tree parse-tree
                 {:maximum-source-nodes 4})
               nil
               (catch #?(:clj Exception :cljs :default) error
                 (ex-data error))))))))

(deftest declaration-errors-follow-source-order-test
  ;; The first failing top-level declaration in source order determines the
  ;; error. Declarations used to be built a 32-item chunk at a time before
  ;; that chunk's duplicate checks ran, and every Caveat before any
  ;; definition, so moving the same declarations changed the error.
  (let [padding (fn [n] (apply str (map #(str "definition pad" % " {}\n") (range n))))
        valid "caveat c(x int) { x == 1 }\n"
        invalid "caveat d(x int) { x == }\n"
        duplicate-type "definition doc {}\ndefinition doc {}\n"
        inner-duplicate "definition other {\n relation xyz: pad0\n relation xyz: pad0\n}\n"
        error-type
        (fn [schema]
          (try (resolver/validate-schema schema nil {:allow-caveats? true}) :accepted
               (catch #?(:clj Exception :cljs :default) error
                 (:type (ex-data error)))))]
    (doseq [n [1 2 28 29 30 31 32 33 60 61 62 63]
            [declarations expected]
            [[[valid valid invalid] :eacl.schema/duplicate-caveat]
             [[invalid valid valid] :eacl.caveat/invalid]
             [[duplicate-type inner-duplicate] :eacl.schema/duplicate-definition]
             [[inner-duplicate duplicate-type] :eacl.schema/duplicate-relation]
             [[duplicate-type invalid] :eacl.schema/duplicate-definition]
             [[invalid duplicate-type] :eacl.caveat/invalid]]]
      (testing (str n " preceding definitions")
        (is (= expected (error-type (apply str (padding n) declarations))))))))
