(ns eacl.adapter-certification
  "Portable evidence harness for the assumptions in SnapshotOracle.dfy.

  A passing report certifies one seeded adapter/runtime combination. It is
  executable evidence for an adapter obligation, not a proof of the backend."
  (:require [clojure.set :as set]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.engine.v8 :as engine]))

(def certification-version "eacl.adapter-certification/v4")

(def certification-schema
  "definition user {}

   definition group {
     relation member: user
   }

   definition document {
     relation reader: user
     relation parent: group
     permission view = reader + parent->member
   }")

(defn coherent-fixture
  "Returns a deterministic, internally coherent schema/object/relationship
  fixture. Different seeds change every external identity while preserving a
  topology with fan-in, fan-out, direct rules, an arrow, and multi-item scans."
  [seed]
  (let [suffix (str "-" seed)
        user (fn [index]
               (eacl/spice-object :user
                                  (str "user-" index suffix)))
        group (fn [index]
                (eacl/spice-object :group
                                   (str "group-" index suffix)))
        document (fn [index]
                   (eacl/spice-object :document
                                      (str "document-" index suffix)))
        users (mapv user (range 3))
        groups (mapv group (range 2))
        documents (mapv document (range 4))
        relationship
        (fn [subject relation resource]
          (eacl/->Relationship subject relation resource))]
    {:seed seed
     :schema certification-schema
     :objects (vec (concat users groups documents))
     :relations
     [{:resource-type :group
       :relation-name :member
       :subject-type :user}
      {:resource-type :document
       :relation-name :reader
       :subject-type :user}
      {:resource-type :document
       :relation-name :parent
       :subject-type :group}]
     :permissions
     [{:resource-type :document
       :permission-name :view
       :source-relation-name :self
       :target-type :relation
       :target-name :reader}
      {:resource-type :document
       :permission-name :view
       :source-relation-name :parent
       :target-type :relation
       :target-name :member}]
     :permission-nodes #{[:document :view]}
     :rules
     {[:document :view]
      [:union
       [:relation :reader]
       [:arrow :parent [:relation :member]]]}
     :relationships
     [(relationship (users 0) :member (groups 0))
      (relationship (users 1) :member (groups 0))
      (relationship (users 2) :member (groups 1))
      (relationship (users 0) :reader (documents 0))
      (relationship (users 1) :reader (documents 0))
      (relationship (users 0) :reader (documents 1))
      (relationship (users 2) :reader (documents 2))
      (relationship (users 0) :reader (documents 3))
      (relationship (groups 0) :parent (documents 2))
      (relationship (groups 0) :parent (documents 3))
      (relationship (groups 1) :parent (documents 0))]}))

(defn coherent-fixtures
  "Shared deterministic generator used by every adapter certification suite."
  [seeds]
  (mapv coherent-fixture seeds))

(defn- throwable-data
  [error]
  {:class #?(:clj (str (class error))
             :cljs (or (.-name error) "Error"))
   :message #?(:clj (ex-message error)
               :cljs (.-message error))
   :data (ex-data error)})

(defn- check
  [id operation obligation f]
  (try
    (let [evidence (f)]
      {:id id
       :operation operation
       :obligation obligation
       :passed? true
       :evidence evidence})
    (catch #?(:clj Throwable :cljs :default) error
      {:id id
       :operation operation
       :obligation obligation
       :passed? false
       :error (throwable-data error)})))

(defn- demand
  ([condition message]
   (demand condition message nil))
  ([condition message data]
   (when-not condition
     (throw
      (ex-info message
               (merge {:type :eacl/adapter-certification-failure}
                      data))))))

(defn- relation-key
  [{:keys [resource-type relation-name]}]
  [resource-type relation-name])

(defn- relationship-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) relation
   (:type resource) (:id resource)])

(defn- relation-catalog
  [adapter fixture]
  (into
   {}
   (map
    (fn [{:keys [resource-type relation-name] :as expected}]
      (let [actual
            (vec
             (backend/invoke
              adapter :relation-defs resource-type relation-name))]
        (demand (= 1 (count actual))
                "A seeded relation must have exactly one definition."
                {:expected expected :actual actual})
        (let [definition (first actual)]
          (demand
           (= expected
              (select-keys
               definition
               [:resource-type :relation-name :subject-type]))
           "Relation definition did not match the seeded schema."
           {:expected expected :actual definition})
          [(relation-key expected) definition])))
    (:relations fixture))))

(defn- internal-catalog
  [adapter objects]
  (into
   {}
   (map
    (fn [object]
      [object
       (backend/invoke adapter :object-id->internal (:id object))])
    objects)))

(defn- materialized-forward
  [fixture internals relation subject]
  (->> (:relationships fixture)
       (filter
        (fn [relationship]
          (and (= subject (:subject relationship))
               (= (:relation-name relation)
                  (:relation relationship))
               (= (:resource-type relation)
                  (get-in relationship [:resource :type])))))
       (map #(get internals (:resource %)))
       sort
       vec))

(defn- materialized-reverse
  [fixture internals relation resource]
  (->> (:relationships fixture)
       (filter
        (fn [relationship]
          (and (= resource (:resource relationship))
               (= (:relation-name relation)
                  (:relation relationship))
               (= (:subject-type relation)
                  (get-in relationship [:subject :type])))))
       (map #(get internals (:subject %)))
       sort
       vec))

(defn- expected-window
  [values direction bound inclusive?]
  (let [within?
        (case direction
          :asc (if inclusive?
                 #(<= bound %)
                 #(< bound %))
          :desc (if inclusive?
                  #(>= bound %)
                  #(> bound %)))]
    (cond->> values
      (some? bound) (filter within?)
      (= :desc direction) reverse
      :always vec)))

(defn- scan-options
  [direction bound inclusive?]
  (cond-> {:direction direction}
    (some? bound)
    (assoc :bound-eid bound
           :inclusive-bound? inclusive?)))

(defn- assert-scan-windows!
  [operation scan expected]
  (let [expected (vec expected)
        bounds (if (seq expected)
                 (into [nil] expected)
                 [nil])]
    (doseq [direction [:asc :desc]
            bound bounds
            inclusive? (if (some? bound) [false true] [false])]
      (let [options (scan-options direction bound inclusive?)
            actual (vec (scan options))
            wanted (if (some? bound)
                     (expected-window
                      expected direction bound inclusive?)
                     (cond-> expected
                       (= :desc direction) reverse))]
        (demand (= wanted actual)
                "Adapter scan disagreed with materialized fixture."
                {:operation operation
                 :options options
                 :expected wanted
                 :actual actual})
        (demand (= (count actual) (count (distinct actual)))
                "Adapter scan returned duplicate internal IDs."
                {:operation operation
                 :options options
                 :actual actual})
        (demand
         (or (< (count actual) 2)
             (apply
              (if (= :asc direction) < >)
              actual))
         "Adapter scan is not strictly ordered."
         {:operation operation
          :options options
          :actual actual})))))

(defn- certify-identity!
  [adapter fixture internals]
  (let [values (vals internals)]
    (demand (= (count values) (count (distinct values)))
            "Visible object conversion is not injective."
            {:internals values})
    (doseq [[object internal] internals]
      (demand #?(:clj (integer? internal)
                 :cljs (and (number? internal)
                            (js/Number.isSafeInteger internal)))
              "Internal object identity is not an exact integer."
              {:object object :internal internal})
      (demand (not (neg? internal))
              "Internal object identity must be nonnegative."
              {:object object :internal internal})
      (demand (= (:id object)
                 (backend/invoke
                  adapter :internal-id->object internal))
              "Visible object conversion did not round-trip."
              {:object object :internal internal}))
    {:visible-objects (count (:objects fixture))
     :distinct-internal-ids (count (distinct values))}))

(defn- certify-definitions!
  [adapter fixture relations]
  (let [actual-permissions
        (->> (:permissions fixture)
             (group-by
              (juxt :resource-type :permission-name))
             (mapcat
              (fn [[[resource-type permission-name] _]]
                (backend/invoke
                 adapter
                 :permission-defs
                 resource-type
                 permission-name)))
             (map
              #(select-keys
                %
                [:resource-type :permission-name
                 :source-relation-name :target-type :target-name]))
             set)
        expected-permissions (set (:permissions fixture))
        nodes (set (backend/invoke adapter :all-permission-nodes))]
    (demand (= expected-permissions actual-permissions)
            "Permission definitions did not cover the seeded schema exactly."
            {:expected expected-permissions
             :actual actual-permissions})
    (demand (= (:permission-nodes fixture) nodes)
            "Permission-node enumeration did not cover the schema exactly."
            {:expected (:permission-nodes fixture)
             :actual nodes})
    {:relations (count relations)
     :permission-definitions (count actual-permissions)
     :permission-nodes (count nodes)}))

(defn- certify-permission-paths!
  [adapter relations]
  (let [reader (get relations [:document :reader])
        parent (get relations [:document :parent])
        member (get relations [:group :member])
        expected
        [{:type :relation
          :name :reader
          :subject-type :user
          :relation-eid (:relation-id reader)}
         {:type :arrow
          :via :parent
          :target-type :group
          :via-relation-eid (:relation-id parent)
          :target-relation :member
          :sub-paths
          [{:type :relation
            :name :member
            :subject-type :user
            :relation-eid (:relation-id member)}]}]
        actual
        (engine/calc-permission-paths adapter :document :view)]
    (demand (= expected actual)
            "Materialized permission paths did not exactly refine the seeded schema."
            {:expected expected :actual actual})
    {:permission :view
     :paths (count actual)
     :path-types (mapv :type actual)}))

(defn- certify-scans!
  [adapter fixture relations internals]
  (doseq [relation (:relations fixture)]
    (let [definition (get relations (relation-key relation))
          relation-id (:relation-id definition)
          subjects
          (filter
           #(= (:subject-type relation) (:type %))
           (:objects fixture))
          resources
          (filter
           #(= (:resource-type relation) (:type %))
           (:objects fixture))]
      (doseq [subject subjects]
        (let [expected
              (materialized-forward
               fixture internals relation subject)]
          (assert-scan-windows!
           :subject->resources
           (fn [options]
             (backend/invoke
              adapter
              :subject->resources
              (:type subject)
              (get internals subject)
              relation-id
              (:resource-type relation)
              options))
           expected)))
      (doseq [resource resources]
        (let [expected
              (materialized-reverse
               fixture internals relation resource)]
          (assert-scan-windows!
           :resource->subjects
           (fn [options]
             (backend/invoke
              adapter
              :resource->subjects
              (:type resource)
              (get internals resource)
              relation-id
              (:subject-type relation)
              options))
           expected)))))
  {:relationships (count (:relationships fixture))
   :relations (count (:relations fixture))
   :directions 2
   :bound-modes #{:unbounded :exclusive :inclusive}})

(defn- certify-direct-match!
  [adapter fixture relations internals]
  (let [expected (set (map relationship-key (:relationships fixture)))]
    (doseq [relation (:relations fixture)
            :let [relation-id
                  (:relation-id
                   (get relations (relation-key relation)))]
            subject (:objects fixture)
            :when (= (:subject-type relation) (:type subject))
            resource (:objects fixture)
            :when (= (:resource-type relation) (:type resource))]
      (let [key [(:type subject) (:id subject)
                 (:relation-name relation)
                 (:type resource) (:id resource)]
            direct
            (boolean
             (backend/invoke
              adapter :direct-match?
              (:type subject)
              (get internals subject)
              relation-id
              (:type resource)
              (get internals resource)))
            forward
            (set
             (backend/invoke
              adapter :subject->resources
              (:type subject)
              (get internals subject)
              relation-id
              (:type resource)
              {:direction :asc}))
            reverse
            (set
             (backend/invoke
              adapter :resource->subjects
              (:type resource)
              (get internals resource)
              relation-id
              (:type subject)
              {:direction :asc}))
            in-fixture? (contains? expected key)]
        (demand (= in-fixture? direct)
                "Direct match disagreed with the materialized fixture."
                {:relationship key
                 :expected in-fixture?
                 :actual direct})
        (demand (= direct
                   (contains? forward (get internals resource))
                   (contains? reverse (get internals subject)))
                "Direct match disagreed with scan membership."
                {:relationship key
                 :direct direct
                 :forward forward
                 :reverse reverse}))))
  {:candidate-pairs
   (reduce
    +
    (map
     (fn [{:keys [resource-type subject-type]}]
       (* (count
           (filter #(= resource-type (:type %))
                   (:objects fixture)))
          (count
           (filter #(= subject-type (:type %))
                   (:objects fixture)))))
     (:relations fixture)))})
(defn- certify-snapshot!
  [adapter relations]
  (let [snapshot-id (backend/invoke adapter :snapshot-id)
        basis-kind (backend/invoke adapter :basis-kind)
        revision (backend/invoke adapter :native-revision)
        schema-generation (backend/invoke adapter :schema-generation)
        relation-ids (vec (sort (map :relation-id (vals relations))))
        ordered-generations?
        (backend/supports? adapter :cache-proofs :ordered-generations)
        proof-frame
        (when ordered-generations?
          (backend/invoke adapter :proof-frame relation-ids))]
    (demand (= snapshot-id (backend/invoke adapter :snapshot-id))
            "Snapshot identity changed on an immutable adapter.")
    (demand (keyword? basis-kind)
            "Basis kind must be a keyword."
            {:basis-kind basis-kind})
    (demand (= basis-kind (backend/invoke adapter :basis-kind))
            "Basis kind changed on an immutable adapter.")
    (demand (= revision (backend/invoke adapter :native-revision))
            "Native revision changed on an immutable adapter.")
    (demand (or (nil? schema-generation)
                (backend/schema-generation? schema-generation))
            "Certified schema generation must be an exact natural or nil."
            {:schema-generation schema-generation})
    (demand (= schema-generation
               (backend/invoke adapter :schema-generation))
            "Schema generation changed on an immutable adapter.")
    (demand (= (:revision revision) (backend/invoke adapter :order-hint))
            "Native revision disagreed with the order hint.")
    (demand (= (:exact-locator revision)
               (backend/invoke adapter :exact-locator))
            "Native revision disagreed with the exact locator.")
    (when ordered-generations?
      (demand (vector? proof-frame)
              "Ordered-generation frame must be a vector."
              {:proof-frame proof-frame})
      (demand (= relation-ids (mapv first proof-frame))
              "Ordered-generation frame was incomplete or noncanonical.")
      (demand (every? (fn [[_ generation]]
                        (backend/schema-generation? generation))
                      proof-frame)
              "Relation generations must share the exact-natural revision domain."
              {:proof-frame proof-frame :native-revision revision})
      (demand (every? (fn [[_ generation]]
                        (<= generation (:revision revision)))
                      proof-frame)
              "A relation generation exceeded the selected native revision."
              {:proof-frame proof-frame :native-revision revision})
      (demand (and (backend/schema-generation? schema-generation)
                   (<= schema-generation (:revision revision)))
              "Schema generation was absent or exceeded the selected native revision."
              {:schema-generation schema-generation
               :native-revision revision})
      (demand (= proof-frame
                 (backend/invoke adapter :proof-frame relation-ids))
              "Ordered-generation frame was unstable on an immutable adapter."))
    {:snapshot-id snapshot-id
     :basis-kind basis-kind
     :native-revision revision
     :schema-generation schema-generation
     :ordered-generation-proof? ordered-generations?}))

(defn certify-ordered-generation-transition!
  "Executes the temporal proof obligations across one supported relationship
  mutation. Bundled adapters call this with immutable values selected
  immediately before and after the committed writer operation. An adapter that
  does not advertise ordered generations returns an explicit non-claim."
  [{:keys [before-adapter after-adapter relation-ids
           affected-relation-ids]}]
  (let [claimed-before?
        (backend/supports?
         before-adapter :cache-proofs :ordered-generations)
        claimed-after?
        (backend/supports?
         after-adapter :cache-proofs :ordered-generations)]
    (if-not (or claimed-before? claimed-after?)
      {:status :not-claimed
       :backend (backend/backend-id after-adapter)}
      (do
        (demand (and claimed-before? claimed-after?)
                "Ordered-generation capability changed across one mutation.")
        (let [relation-ids (vec (sort relation-ids))
              affected (set affected-relation-ids)
              before-revision
              (:revision (backend/invoke before-adapter :native-revision))
              after-revision
              (:revision (backend/invoke after-adapter :native-revision))
              before-schema
              (backend/invoke before-adapter :schema-generation)
              after-schema
              (backend/invoke after-adapter :schema-generation)
              before-frame
              (backend/invoke before-adapter :proof-frame relation-ids)
              after-frame
              (backend/invoke after-adapter :proof-frame relation-ids)
              before-generations (into {} before-frame)
              after-generations (into {} after-frame)]
          (doseq [[label value]
                  [[:before-revision before-revision]
                   [:after-revision after-revision]
                   [:before-schema-generation before-schema]
                   [:after-schema-generation after-schema]]]
            (demand (backend/schema-generation? value)
                    "A certified generation left the native exact-natural domain."
                    {:field label :value value}))
          (demand (< before-revision after-revision)
                  "A supported relationship mutation did not advance revision."
                  {:before before-revision :after after-revision})
          (demand (= before-schema after-schema)
                  "A relationship-only mutation changed schema generation."
                  {:before before-schema :after after-schema})
          (doseq [[label frame revision]
                  [[:before before-frame before-revision]
                   [:after after-frame after-revision]]]
            (demand (and (vector? frame)
                         (= relation-ids (mapv first frame)))
                    "A temporal proof frame was incomplete or noncanonical."
                    {:phase label :frame frame :relation-ids relation-ids})
            (demand (every?
                     (fn [[_ generation]]
                       (and (backend/schema-generation? generation)
                            (<= generation revision)))
                     frame)
                    "A temporal proof generation left its domain or ceiling."
                    {:phase label :frame frame :revision revision}))
          (demand (set/subset? affected (set relation-ids))
                  "Affected relations were absent from the certified frame."
                  {:affected affected :relation-ids relation-ids})
          (doseq [relation-id relation-ids]
            (if (contains? affected relation-id)
              (demand (= after-revision
                         (get after-generations relation-id))
                      "An affected relation was not stamped at commit revision."
                      {:relation-id relation-id
                       :generation (get after-generations relation-id)
                       :revision after-revision})
              (demand (= (get before-generations relation-id)
                         (get after-generations relation-id))
                      "An unaffected relation generation changed."
                      {:relation-id relation-id
                       :before (get before-generations relation-id)
                       :after (get after-generations relation-id)})))
          {:status :certified
           :backend (backend/backend-id after-adapter)
           :before-revision before-revision
           :after-revision after-revision
           :affected-relation-ids affected})))))

(defn certify-live-source-identity!
  "Certifies the source-id rule for two separately opened live sources.

  Non-durable sources must differ even when caller configuration is reused.
  Durable reopenings of the same store must retain their persisted identity."
  [{:keys [backend durability first-scope second-scope]}]
  (doseq [[label scope] [[:first first-scope] [:second second-scope]]]
    (demand (and (map? scope)
                 (some? (:source-id scope))
                 (contains? scope :branch))
            "A live source supplied no complete source scope."
            {:backend backend :position label :scope scope}))
  (case durability
    :non-durable
    (demand (not= first-scope second-scope)
            "Two non-durable live sources reused one source identity."
            {:backend backend :first first-scope :second second-scope})

    :durable
    (demand (= first-scope second-scope)
            "A durable store identity changed across reopen."
            {:backend backend :first first-scope :second second-scope})

    (demand false "Unknown source durability profile."
            {:backend backend :durability durability}))
  {:status :certified
   :backend backend
   :durability durability
   :distinct? (not= first-scope second-scope)})

(defn certify
  "Runs the portable static-snapshot obligations and returns a machine-readable
  report. Callers should fail their test when `:passed?` is false."
  [{:keys [adapter fixture runtime]}]
  (let [relations (atom nil)
        internals (atom nil)
        checks
        [(check
          :operation-obligation-coverage
          :adapter
          :all-operations-declared
          (fn []
            (let [declared
                  (set (keys (backend/certification-obligations)))]
              (demand
               (= (into
                   (conj backend/required-snapshot-operations :proof-frame)
                   backend/optional-snapshot-operations)
                  declared)
               "Adapter obligation registry is incomplete."
               {:required backend/required-snapshot-operations
                :declared declared})
              {:operations (count declared)})))
         (check
          :relation-catalog
          :relation-defs
          :finite-complete-type-correct
          (fn []
            (let [catalog (relation-catalog adapter fixture)]
              (reset! relations catalog)
              {:relations (count catalog)})))
         (check
          :identity-round-trip
          :object-id->internal
          :injective-round-trip-exact
          (fn []
            (let [catalog
                  (internal-catalog adapter (:objects fixture))]
              (reset! internals catalog)
              (certify-identity! adapter fixture catalog))))
         (check
          :schema-enumeration
          :all-permission-nodes
          :exact-schema-coverage
         (fn []
            (certify-definitions!
             adapter fixture
             (or @relations
                 (relation-catalog adapter fixture)))))
         (check
          :permission-path-materialization
          :permission-defs
          :exact-resolved-ranked-paths
          (fn []
            (certify-permission-paths!
             adapter
             (or @relations
                 (relation-catalog adapter fixture)))))
         (check
          :forward-reverse-scans
          :subject->resources
          :finite-ordered-unique-complete-bounded
          (fn []
            (certify-scans!
             adapter
             fixture
             (or @relations
                 (relation-catalog adapter fixture))
             (or @internals
                 (internal-catalog
                  adapter (:objects fixture))))))
         (check
          :direct-match-equivalence
          :direct-match?
          :iff-scan-membership
          (fn []
            (certify-direct-match!
             adapter
             fixture
             (or @relations
                 (relation-catalog adapter fixture))
             (or @internals
                 (internal-catalog
                  adapter (:objects fixture))))))
         (check
          :immutable-snapshot
          :basis-adapter
          :immutable-value-identity-and-proof
          (fn []
            (certify-snapshot!
             adapter
             (or @relations
                 (relation-catalog adapter fixture)))))]
        failed (filterv (comp not :passed?) checks)]
    {:version certification-version
     :backend (backend/backend-id adapter)
     :runtime runtime
     :seed (:seed fixture)
     :passed? (empty? failed)
     :checks checks
     :failed-checks (mapv :id failed)
     :certified-obligations
     (if (empty? failed)
       (->> checks
            (map (juxt :operation :obligation))
            set)
       #{})}))

(defn assert-certified!
  [report]
  (demand (:passed? report)
          "Adapter certification failed."
          {:report report})
  report)
