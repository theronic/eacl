(ns eacl.exploration.source-refinement-bridge
  "Independent exploration oracle for the reusable EACL schema compiler seam.

  This does not call production permission-path, rule, or index helpers to
  construct its expected values. It reads only normalized backend schema facts,
  derives reachable typed rules independently, then compares private compiler
  output as a black box. It is not production code."
  (:require [clojure.set :as set]
            [eacl.backend.v8 :as backend]
            [eacl.engine.v8 :as engine])
  (:import [java.util Random]))

(def ^:private compile-rules
  (deref (ns-resolve 'eacl.engine.v8 'compile-recursive-rules)))

(def ^:private compile-source-plan
  (deref (ns-resolve 'eacl.engine.v8 'certification-source-plan)))

(def ^:private production-forward-consumers
  (deref (ns-resolve 'eacl.engine.v8 'forward-consumers)))

(def ^:private production-forward-seeds
  (deref (ns-resolve 'eacl.engine.v8 'forward-seeds-by-subject-type)))

(def ^:private production-rules-by-node
  (deref (ns-resolve 'eacl.engine.v8 'rules-by-node)))

(defn- operation-map
  []
  (into {}
        (map (fn [operation]
               [operation (fn [& _] [])]))
        backend/required-snapshot-operations))

(defn- schema-adapter
  [{:keys [relations permissions relation-order permission-order]}]
  (backend/make-adapter
   {:id :source-refinement-exploration
    :capabilities
    {:consistency #{:fully-consistent}
     :snapshots #{:current}
     :cursor #{:forward :reverse}
     :transactions #{}
     :cache-proofs #{:ordered-generations :snapshot-bound}
     :runtime #{:clj}}
    :operations
    (merge
     (operation-map)
     {:snapshot-id
      (constantly {:database-id :source-refinement :basis-t 1})
      :source-scope
      (constantly {:source-id :source-refinement :branch nil})
      :proof-frame
      (fn [relation-ids]
        {:schema-stamp 1
         :relation-stamps
         (mapv (fn [relation-id] [relation-id 1]) relation-ids)})
      :permission-defs
      (fn [resource-type permission-name]
        (let [values (get permissions [resource-type permission-name] [])]
          (permission-order values)))
      :relation-defs
      (fn [resource-type relation-name]
        (let [values (get relations [resource-type relation-name] [])]
          (relation-order values)))
      :all-permission-nodes
      (fn [] (set (keys permissions)))})}))

(defn- relation-def
  [id resource-type relation-name subject-type]
  {:relation-id id
   :resource-type resource-type
   :relation-name relation-name
   :subject-type subject-type})

(defn- permission-def
  [id resource-type permission-name source target-type target-name]
  {:permission-id id
   :resource-type resource-type
   :permission-name permission-name
   :source-relation-name source
   :target-type target-type
   :target-name target-name})

(def base-relations
  {[:folder :reader]
   [(relation-def 1 :folder :reader :user)
    (relation-def 2 :folder :reader :service)]
   [:folder :parent]
   [(relation-def 3 :folder :parent :folder)]
   [:folder :team]
   [(relation-def 4 :folder :team :team)]
   [:team :member]
   [(relation-def 5 :team :member :user)
    (relation-def 6 :team :member :service)]})

(def fixed-permissions
  {[:folder :base-read]
   [(permission-def 100 :folder :base-read
                    :self :relation :reader)]
   [:team :participant]
   [(permission-def 101 :team :participant
                    :self :relation :member)]})

(def root-arms
  [(permission-def 200 :folder :read :self :relation :reader)
   (permission-def 201 :folder :read :self :permission :base-read)
   (permission-def 202 :folder :read :parent :permission :read)
   (permission-def 203 :folder :read :team :relation :member)
   (permission-def 204 :folder :read :team :permission :participant)])

(defn- fixture
  [mask relation-order permission-order]
  {:relations base-relations
   :permissions
   (assoc fixed-permissions
          [:folder :read]
          (into []
                (keep-indexed
                 (fn [index arm]
                   (when (bit-test mask index) arm)))
                root-arms))
   :relation-order relation-order
   :permission-order permission-order})

(defn- relation-defs
  [schema resource-type relation-name]
  (get-in schema [:relations [resource-type relation-name]] []))

(defn- permission-defs
  [schema [resource-type permission-name]]
  (get-in schema [:permissions [resource-type permission-name]] []))

(defn- dependency-nodes
  [schema node definition]
  (let [resource-type (first node)
        source (:source-relation-name definition)]
    (if (not= :permission (:target-type definition))
      []
      (if (= :self source)
        [[resource-type (:target-name definition)]]
        (mapv
         (fn [{:keys [subject-type]}]
           [subject-type (:target-name definition)])
         (relation-defs schema resource-type source))))))

(defn- reachable-nodes
  [schema root]
  (loop [known #{root}]
    (let [expanded
          (reduce
           (fn [nodes node]
             (reduce
              into nodes
              (map #(dependency-nodes schema node %)
                   (permission-defs schema node))))
           known
           known)]
      (if (= known expanded)
        known
        (recur expanded)))))

(defn- rules-for-definition
  [schema [resource-type permission :as node] definition]
  (let [source (:source-relation-name definition)
        target-type (:target-type definition)
        target-name (:target-name definition)
        head-base {:node node
                   :resource-type resource-type
                   :permission permission}]
    (cond
      (and (= :self source) (= :relation target-type))
      (mapv
       (fn [{:keys [relation-id subject-type]}]
         (merge head-base
                {:rule :relation
                 :relation-eid relation-id
                 :subject-type subject-type}))
       (relation-defs schema resource-type target-name))

      (and (= :self source) (= :permission target-type))
      [(merge head-base
              {:rule :self-permission
               :target-node [resource-type target-name]})]

      (= :permission target-type)
      (mapv
       (fn [{:keys [relation-id subject-type]}]
         (merge head-base
                {:rule :arrow-permission
                 :via-relation-eid relation-id
                 :intermediate-type subject-type
                 :target-node [subject-type target-name]}))
       (relation-defs schema resource-type source))

      (= :relation target-type)
      (vec
       (mapcat
        (fn [{via-id :relation-id intermediate :subject-type}]
          (map
           (fn [{target-id :relation-id
                 target-subject :subject-type}]
             (merge head-base
                    {:rule :arrow-relation
                     :via-relation-eid via-id
                     :intermediate-type intermediate
                     :target-relation-eid target-id
                     :target-subject-type target-subject}))
           (relation-defs schema intermediate target-name)))
        (relation-defs schema resource-type source)))

      :else
      (throw
       (ex-info "Independent oracle encountered an unsupported definition."
                {:node node :definition definition})))))

(defn- oracle-rules
  [schema root]
  (->> (reachable-nodes schema root)
       (sort-by pr-str)
       (mapcat
        (fn [node]
          (mapcat #(rules-for-definition schema node %)
                  (permission-defs schema node))))
       distinct
       vec))

(defn- semantic-rule
  [rule]
  (dissoc rule :id))

(defn- group-exact
  [rules key-fn include?]
  (->> rules
       (filter include?)
       (group-by key-fn)
       (into {}
             (map (fn [[key values]] [key (set values)])))))

(defn- oracle-consumers
  [rules]
  (group-exact
   rules
   :target-node
   #(contains? #{:self-permission :arrow-permission} (:rule %))))

(defn- oracle-seeds
  [rules]
  (group-exact
   rules
   #(case (:rule %)
      :relation (:subject-type %)
      :arrow-relation (:target-subject-type %))
   #(contains? #{:relation :arrow-relation} (:rule %))))

(defn- oracle-by-node
  [rules]
  (->> rules
       (group-by :node)
       (into {} (map (fn [[node values]] [node (set values)])))))

(defn- normalized-production-index
  [index]
  (into {}
        (map (fn [[key rules]]
               [key (set (map semantic-rule rules))]))
        index))

(defn- compile-case
  [schema root]
  (let [adapter (schema-adapter schema)
        expected (oracle-rules schema root)
        production (mapv semantic-rule (compile-rules adapter root))
        source-plan (compile-source-plan adapter root)
        production-rules (compile-rules adapter root)]
    {:expected expected
     :production production
     :source-plan source-plan
     :semantic-exact?
     (and (= (set expected) (set production))
          (= (count expected) (count production)))
     :consumer-index-exact?
     (= (oracle-consumers expected)
        (normalized-production-index
         (production-forward-consumers production-rules)))
     :seed-index-exact?
     (= (oracle-seeds expected)
        (normalized-production-index
         (production-forward-seeds production-rules)))
     :node-index-exact?
     (= (oracle-by-node expected)
        (normalized-production-index
         (production-rules-by-node production-rules)))}))

(defn- permuted-order
  [^Random rng]
  (fn [values]
    (->> values
         (map (fn [value] [(.nextLong rng) value]))
         (sort-by first)
         (mapv second))))

(defn- mutation-controls
  [expected]
  (let [drop-mutant (pop expected)
        duplicate-mutant (conj expected (first expected))
        arrow-index
        (first
         (keep-indexed
          (fn [index rule]
            (when (and (= :arrow-permission (:rule rule))
                       (not= (:node rule) (:target-node rule)))
              index))
          expected))
        reversed-arrow-mutant
        (if (some? arrow-index)
          (update expected arrow-index
                  (fn [rule]
                    (assoc rule :target-node (:node rule))))
          expected)]
    {:drop-killed? (not= (set expected) (set drop-mutant))
     :duplicate-killed? (not= (count expected) (count duplicate-mutant))
     :reversed-arrow-killed?
     (or (nil? arrow-index)
         (not= expected reversed-arrow-mutant))}))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn run-bridge!
  ([] (run-bridge! 9173 8))
  ([seed permutations-per-mask]
   (let [rng (Random. (long seed))
         cases
         (vec
          (for [mask (range 1 (bit-shift-left 1 (count root-arms)))
                permutation (range permutations-per-mask)]
            (let [schema
                  (fixture mask
                           (permuted-order rng)
                           (permuted-order rng))
                  result (compile-case schema [:folder :read])]
              (assoc result :mask mask :permutation permutation))))
         failures
         (filterv
          #(not (and (:semantic-exact? %)
                     (:consumer-index-exact? %)
                     (:seed-index-exact? %)
                     (:node-index-exact? %)))
          cases)
         richest
         (first (filter #(= 31 (:mask %)) cases))
         controls (mutation-controls (:expected richest))
         missing-relation
         (-> (fixture 1 identity identity)
             (update :relations dissoc [:folder :reader]))
         missing-permission
         (-> (fixture 2 identity identity)
             (update :permissions dissoc [:folder :base-read]))
         missing-relation-error
         (error-data
          #(compile-source-plan
            (schema-adapter missing-relation) [:folder :read]))
         missing-permission-error
         (error-data
          #(compile-source-plan
            (schema-adapter missing-permission) [:folder :read]))]
     (when (or (seq failures)
               (not (every? true? (vals controls)))
               (nil? missing-relation-error)
               (nil? missing-permission-error))
       (throw
        (ex-info
         "Independent schema/compiler refinement bridge failed."
         {:seed seed
          :case-count (count cases)
          :failures (mapv #(select-keys % [:mask :permutation
                                            :expected :production
                                            :semantic-exact?
                                            :consumer-index-exact?
                                            :seed-index-exact?
                                            :node-index-exact?])
                          (take 3 failures))
          :controls controls
          :missing-relation-error missing-relation-error
          :missing-permission-error missing-permission-error})))
     {:seed seed
      :case-count (count cases)
      :masks 31
      :permutations-per-mask permutations-per-mask
      :semantic-rule-comparisons
      (reduce + (map (comp count :expected) cases))
      :direction-index-comparisons (* 3 (count cases))
      :mutation-controls controls
      :missing-relation-rejected? (some? missing-relation-error)
      :missing-permission-rejected? (some? missing-permission-error)})))
