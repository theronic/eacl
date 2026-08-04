(ns eacl.adapter-certification
  "Portable evidence harness for the assumptions in SnapshotOracle.dfy.

  A passing report certifies one seeded adapter/runtime combination. It is
  executable evidence for an adapter obligation, not a proof of the backend."
  (:require [clojure.set :as set]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]))

(def certification-version "eacl.adapter-certification/v1")

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
        source (backend/invoke adapter :source-scope)
        head (backend/invoke adapter :graph-head)
        schema-proof (backend/invoke adapter :schema-proof)
        relation-ids (mapv :relation-id (vals relations))
        relation-proof
        (backend/invoke adapter :relation-proof relation-ids)
        current (backend/invoke adapter :select-current)]
    (demand (= snapshot-id (backend/invoke adapter :snapshot-id))
            "Snapshot identity changed on an immutable adapter.")
    (demand (= source (backend/invoke adapter :source-scope)
               (backend/invoke current :source-scope))
            "Current selection changed source identity.")
    (demand (= head (backend/invoke adapter :graph-head))
            "Graph head changed on an immutable adapter.")
    (demand
     (true?
      (backend/invoke
       adapter :contains-anchor? (:graph-anchor head)))
     "An adapter did not contain its own graph head."
     {:head head})
    (demand (= schema-proof (backend/invoke adapter :schema-proof))
            "Schema proof was unstable on an immutable adapter.")
    (demand (= relation-proof
               (backend/invoke
                adapter :relation-proof relation-ids))
            "Relation proof was unstable on an immutable adapter.")
    (when (backend/supports?
           adapter :consistency :at-exact-snapshot)
      (let [exact
            (backend/invoke adapter :select-exact head 1000)]
        (demand (backend/adapter? exact)
                "Advertised exact selection returned no adapter."
                {:head head})
        (demand (= source (backend/invoke exact :source-scope))
                "Exact selection changed source identity.")
        (demand
         (= (:exact-locator head)
            (backend/invoke exact :exact-locator))
         "Exact selection returned a different locator.")
        (demand
         (= (:graph-anchor head)
            (:graph-anchor
             (backend/invoke exact :graph-head)))
         "Exact selection returned a different graph anchor.")))
    {:snapshot-id snapshot-id
     :source-scope source
     :graph-head head
     :schema-proof-available? (some? schema-proof)
     :relation-proof-available? (some? relation-proof)
     :exact-selection?
     (backend/supports?
      adapter :consistency :at-exact-snapshot)}))

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
               (= backend/required-snapshot-operations declared)
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
          :select-current
          :identity-proof-and-exact-selection
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
