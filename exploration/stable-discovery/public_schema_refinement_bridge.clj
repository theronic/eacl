(ns eacl.exploration.public-schema-refinement-bridge
  "Independent generated oracle for the public EACL schema boundary.

  Expected values are semantic relation and permission tuples written directly
  in this file.  The oracle does not inspect parser ASTs and does not use model
  constructors or production ID helpers.  Parser output is normalized to those
  tuples, then the public reference validator is required to accept it."
  (:require [clojure.string :as str]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser])
  (:import [java.util Collections Random]))

(def root-arms
  [{:expression "reader"
    :semantic [:folder :read :self :relation :reader]}
   {:expression "base_read"
    :semantic [:folder :read :self :permission :base_read]}
   {:expression "parent->read"
    :semantic [:folder :read :parent :permission :read]}
   {:expression "team->member"
    :semantic [:folder :read :team :relation :member]}
   {:expression "team->participant"
    :semantic [:folder :read :team :permission :participant]}])

(def expected-relations
  #{[:folder :reader :user]
    [:folder :reader :service]
    [:folder :parent :folder]
    [:folder :team :team]
    [:team :member :user]
    [:team :member :service]})

(def fixed-permissions
  #{[:folder :base_read :self :relation :reader]
    [:team :participant :self :relation :member]})

(def expected-definitions
  #{"user" "service" "team" "folder"})

(defn- shuffled
  [^Random rng values]
  (let [copy (java.util.ArrayList. values)]
    (Collections/shuffle copy rng)
    (vec copy)))

(defn- definition
  [name lines]
  (str "definition " name " {\n"
       (when (seq lines)
         (str "  " (str/join "\n  " lines) "\n"))
       "}"))

(defn- selected-root-arms
  [mask]
  (into []
        (keep-indexed
         (fn [index arm]
           (when (bit-test mask index) arm)))
        root-arms))

(defn- schema-text
  [^Random rng mask {:keys [duplicate-arm? any-arrow? parens? comments?]}]
  (let [selected (selected-root-arms mask)
        expressions
        (cond-> (mapv :expression selected)
          duplicate-arm? (conj (:expression (first selected))))
        expressions
        (mapv (fn [expression]
                (cond
                  (and any-arrow? (= expression "team->participant"))
                  "team.any(participant)"

                  :else expression))
              expressions)
        expression
        (str/join " + " expressions)
        expression
        (if (and parens? (> (count expressions) 1))
          (str "(" expression ")")
          expression)
        folder-lines
        (shuffled rng
                  ["relation reader: user | service"
                   "relation parent: folder"
                   "relation team: team"
                   "permission base_read = reader"
                   (str "permission read = " expression)])
        team-lines
        (shuffled rng
                  ["relation member: user | service"
                   "permission participant = member"])
        definitions
        (shuffled rng
                  [(definition "user" [])
                   (definition "service" [])
                   (definition "team" team-lines)
                   (definition "folder" folder-lines)])
        separator (if comments?
                    "\n\n// generated ordering boundary\n"
                    "\n\n")]
    (str (str/join separator definitions) "\n")))

(defn- canonical-relation
  [relation]
  [(:eacl.relation/resource-type relation)
   (:eacl.relation/relation-name relation)
   (:eacl.relation/subject-type relation)])

(defn- canonical-permission
  [permission]
  [(:eacl.permission/resource-type permission)
   (:eacl.permission/permission-name permission)
   (:eacl.permission/source-relation-name permission)
   (:eacl.permission/target-type permission)
   (:eacl.permission/target-name permission)])

(defn- expected-relation-id
  [[resource-type relation-name subject-type]]
  (str "eacl.relation:"
       resource-type ":" relation-name ":" subject-type))

(defn- expected-permission-id
  [[resource-type permission-name source target-type target-name]]
  (str "eacl:permission:"
       resource-type ":" permission-name ":" source ":"
       target-type ":" target-name))

(defn- expected-permissions
  [mask]
  (into fixed-permissions
        (map :semantic)
        (selected-root-arms mask)))

(defn- normalize-public-schema
  [text]
  (let [schema (parser/->eacl-schema (parser/parse-schema text))]
    (model/validate-schema-references schema)
    (let [relations (:relations schema)
          permissions (:permissions schema)
          canonical-relations (mapv canonical-relation relations)
          canonical-permissions (mapv canonical-permission permissions)]
      {:definitions (set (:definitions schema))
       :relations (set canonical-relations)
       :permissions (set canonical-permissions)
       :identity-exact?
       (and
        (every? true?
                (map (fn [relation semantic]
                       (= (:eacl/id relation)
                          (expected-relation-id semantic)))
                     relations canonical-relations))
        (every? true?
                (map (fn [permission semantic]
                       (= (:eacl/id permission)
                          (expected-permission-id semantic)))
                     permissions canonical-permissions)))
       :persisted-relation-count (count (set relations))
       :persisted-permission-count (count (set permissions))
     :raw-relation-count (count (:relations schema))
       :raw-permission-count (count (:permissions schema))})))

(defn- expected-schema
  [mask]
  {:definitions expected-definitions
   :relations expected-relations
   :permissions (expected-permissions mask)})

(defn- semantic-exact?
  [expected actual]
  (and
   (= expected (select-keys actual [:definitions :relations :permissions]))
   (:identity-exact? actual)
   (= (count (:relations expected))
      (:persisted-relation-count actual))
   (= (count (:permissions expected))
      (:persisted-permission-count actual))))

(defn- error-data
  [text]
  (try
    (normalize-public-schema text)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(def invalid-schemas
  {:malformed
   "definition user { relation member user\n}\n"

   :intersection
   "definition user {}\ndefinition doc {\n relation reader: user\n permission read = reader & reader\n}\n"

   :exclusion
   "definition user {}\ndefinition doc {\n relation reader: user\n permission read = reader - reader\n}\n"

   :multi-level-arrow
   "definition user {}\ndefinition team {\n relation member: user\n}\ndefinition folder {\n relation parent: folder\n permission read = parent->parent->read\n}\n"

   :all-arrow
   "definition user {}\ndefinition team {\n relation member: user\n}\ndefinition folder {\n relation team: team\n permission read = team.all(member)\n}\n"

   :nil
   "definition doc {\n permission read = nil\n}\n"

   :self
   "definition doc {\n permission read = self\n}\n"

   :namespaced-type
   "definition docs/user {}\n"

   :wildcard
   "definition user {}\ndefinition doc {\n relation reader: user:*\n}\n"

   :subject-relation
   "definition user {}\ndefinition team {\n relation member: user\n}\ndefinition doc {\n relation reader: team#member\n}\n"

   :caveat
   "definition user {}\ndefinition doc {\n relation reader: user with active\n}\n"

   :duplicate-definition
   "definition user {}\ndefinition user {}\n"

   :duplicate-relation
   "definition user {}\ndefinition doc {\n relation reader: user\n relation reader: user\n}\n"

   :duplicate-permission
   "definition user {}\ndefinition doc {\n relation reader: user\n permission read = reader\n permission read = reader\n}\n"

   :name-collision
   "definition user {}\ndefinition doc {\n relation reader: user\n permission reader = reader\n}\n"

   :unknown-direct-target
   "definition doc {\n permission read = missing\n}\n"

   :unknown-arrow-base
   "definition doc {\n permission read = missing->read\n}\n"

   :missing-arrow-target-on-one-type
   "definition principal {}\ndefinition user {\n relation leaf: principal\n}\ndefinition service {}\ndefinition folder {\n relation actor: user | service\n permission view = actor->leaf\n}\n"

   :mixed-arrow-target
   "definition principal {}\ndefinition user {\n relation leaf: principal\n}\ndefinition service {\n relation member: principal\n permission leaf = member\n}\ndefinition folder {\n relation actor: user | service\n permission view = actor->leaf\n}\n"})

(defn- mutation-controls
  [expected]
  (let [one-permission (first (:permissions expected))
        dropped (update expected :permissions disj one-permission)
        changed-arrow
        (update expected :permissions
                (fn [permissions]
                  (let [arrow (first (filter #(not= :self (nth % 2)) permissions))]
                    (if arrow
                      (conj (disj permissions arrow)
                            (assoc arrow 3
                                   (if (= :relation (nth arrow 3))
                                     :permission
                                     :relation)))
                      permissions))))]
    {:drop-killed? (not= expected dropped)
     :wrong-arrow-kind-killed? (not= expected changed-arrow)}))

(defn run-bridge!
  ([] (run-bridge! 48117 4))
  ([seed permutations-per-mask]
   (let [rng (Random. (long seed))
         variants [{:duplicate-arm? false
                    :any-arrow? false
                    :parens? false
                    :comments? false}
                   {:duplicate-arm? true
                    :any-arrow? false
                    :parens? true
                    :comments? false}
                   {:duplicate-arm? false
                    :any-arrow? true
                    :parens? false
                    :comments? true}
                   {:duplicate-arm? true
                    :any-arrow? true
                    :parens? true
                    :comments? true}]
         cases
         (vec
          (for [mask (range 1 (bit-shift-left 1 (count root-arms)))
                permutation (range permutations-per-mask)]
            (let [variant (nth variants (mod permutation (count variants)))
                  text (schema-text rng mask variant)
                  actual (normalize-public-schema text)
                  expected (expected-schema mask)]
              {:mask mask
               :permutation permutation
               :variant variant
               :text text
                  :actual actual
                  :expected expected
               :duplicate-collapses?
               (= (:raw-permission-count actual)
                  (+ (count (:permissions expected))
                     (if (:duplicate-arm? variant) 1 0)))
               :semantic-exact? (semantic-exact? expected actual)})))
         failures (filterv (complement :semantic-exact?) cases)
         duplicate-failures (filterv (complement :duplicate-collapses?) cases)
         invalid-results
         (into {}
               (map (fn [[case text]] [case (error-data text)]))
               invalid-schemas)
         invalid-survivors
         (into {}
               (filter (comp nil? val))
               invalid-results)
         richest (expected-schema 31)
         controls (mutation-controls richest)]
     (when (or (seq failures)
               (seq duplicate-failures)
               (seq invalid-survivors)
               (not (every? true? (vals controls))))
       (throw
        (ex-info
         "Public schema refinement bridge failed."
         {:seed seed
          :failures (mapv #(select-keys % [:mask :permutation :variant
                                            :actual :expected :text])
                          (take 3 failures))
          :duplicate-failures
          (mapv #(select-keys % [:mask :permutation :variant
                                 :actual :expected :text])
                (take 3 duplicate-failures))
          :invalid-survivors invalid-survivors
          :controls controls})))
     {:seed seed
      :case-count (count cases)
      :masks 31
      :permutations-per-mask permutations-per-mask
      :invalid-case-count (count invalid-results)
      :all-invalid-rejected? true
      :semantic-tuple-comparisons
      (reduce +
              (map (fn [{:keys [expected]}]
                     (+ (count (:relations expected))
                        (count (:permissions expected))))
                   cases))
      :mutation-controls controls})))
