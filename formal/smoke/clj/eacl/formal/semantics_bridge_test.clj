(ns eacl.formal.semantics-bridge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [datascript.core :as ds]
   [eacl.adapter-certification :as certification]
   [eacl.authorization-oracle :as oracle]
   [eacl.backend.v8 :as backend]
   [eacl.contract-support :as fixtures]
   [eacl.core :as eacl]
   [eacl.datascript.core :as datascript]
   [eacl.engine.indexed :as indexed]
   [eacl.engine.v8 :as v8]
   [eacl.formal.differential-runner :as differential]
   [eacl.formal.semantics-bridge :as formal]))

(def smoke-fixture
  {:objects fixtures/smoke-objects
   :relationships fixtures/smoke-relationships
   :rules oracle/smoke-rules})

(def recursive-fixture
  {:objects fixtures/recursive-objects
   :relationships fixtures/recursive-relationships
   :rules oracle/recursive-rules})

(deftest generated-semantics-agrees-with-independent-oracle
  (doseq [[fixture-name fixture]
          [[:smoke smoke-fixture]
           [:recursive recursive-fixture]]]
    (testing (name fixture-name)
      (is (true? (formal/well-formed? fixture)))
      (is (= (oracle/authorization-set fixture)
             (formal/authorization-set fixture))))))

(deftest generated-acyclic-kernel-refines-the-independent-oracle
  (let [expected (oracle/authorization-set smoke-fixture)]
    (is (= 5 (formal/compiled-path-count smoke-fixture)))
    (doseq [subject fixtures/smoke-objects
            [[resource-type permission]] oracle/smoke-rules]
      (let [wanted
            (into
             #{}
             (for [[grant-subject grant-permission resource]
                   expected
                   :when (and (= subject grant-subject)
                              (= permission grant-permission)
                              (= resource-type (:type resource)))]
               resource))
            actual
            (set
             (formal/acyclic-forward
              smoke-fixture subject resource-type permission))]
        (is (= wanted actual)
            (pr-str
             {:direction :forward
              :subject subject
              :permission [resource-type permission]}))
        (is (= {:count (count wanted)
                :truncated? false}
               (formal/acyclic-count
                smoke-fixture
                subject
                resource-type
                permission
                -1)))
        (let [bounded
              (formal/acyclic-count
               smoke-fixture
               subject
               resource-type
               permission
               1)]
          (is (= (min 1 (count wanted))
                 (:count bounded)))
          (is (= (> (count wanted) 1)
                 (:truncated? bounded))))))
    (doseq [resource fixtures/smoke-objects
            [[resource-type permission]] oracle/smoke-rules
            :when (= resource-type (:type resource))
            subject-type [:user :platform :account :server]]
      (let [wanted
            (into
             #{}
             (for [[subject grant-permission grant-resource]
                   expected
                   :when (and (= resource grant-resource)
                              (= permission grant-permission)
                              (= subject-type (:type subject)))]
               subject))
            actual
            (set
             (formal/acyclic-reverse
              smoke-fixture resource subject-type permission))]
        (is (= wanted actual)
            (pr-str
             {:direction :reverse
              :resource resource
              :subject-type subject-type
              :permission permission}))))))

(deftest generated-direct-check-is-sound-and-complete
  (let [user-1 (fixtures/->user "direct-user-1")
        user-2 (fixtures/->user "direct-user-2")
        document-1 (eacl/spice-object
                    :document "direct-document-1")
        document-2 (eacl/spice-object
                    :document "direct-document-2")
        fixture
        {:objects [user-1 user-2 document-1 document-2]
         :relationships
         [(eacl/->Relationship
           user-1 :reader document-1)]
         :rules
         {[:document :view] [:relation :reader]}}]
    (doseq [subject [user-1 user-2]
            resource [document-1 document-2]]
      (is (= (and (= user-1 subject)
                  (= document-1 resource))
             (formal/direct-can?
             fixture subject :view resource))))))

(defn- seed-recursive-datascript-client
  ([]
   (seed-recursive-datascript-client recursive-fixture))
  ([fixture]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client fixtures/recursive-schema)
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index))
         :eacl/id id})
      (:objects fixture)))
    (eacl/create-relationships!
     client
     (:relationships fixture))
    client)))

(def recursive-shape-fixtures
  (let [user (fixtures/->user "shape-user")
        folder #(eacl/spice-object :folder (str "shape-folder-" %))
        reader
        (fn [index]
          (eacl/->Relationship user :reader (folder index)))
        editor
        (fn [index]
          (eacl/->Relationship user :editor (folder index)))
        parent
        (fn [parent-index child-index]
          (eacl/->Relationship
           (folder parent-index)
           :parent
           (folder child-index)))
        fixture
        (fn [id folder-count relationships]
          {:id id
           :subject user
           :objects
           (into
            [user]
            (map folder (range folder-count)))
           :relationships relationships
           :rules oracle/recursive-rules})]
    [(fixture
      :cyclic
      2
      [(reader 0) (parent 0 1) (parent 1 0)])
     (fixture
      :diamond
      4
      [(reader 0)
       (parent 0 1)
       (parent 0 2)
       (parent 1 3)
       (parent 2 3)])
     (fixture
      :deep
      4
      [(reader 0) (parent 0 1) (parent 1 2) (parent 2 3)])
     (fixture
      :wide
      5
      [(reader 0)
       (parent 0 1)
       (parent 0 2)
       (parent 0 3)
       (parent 0 4)])
     (fixture
      :duplicate-path
      2
      [(reader 0) (editor 0) (parent 0 1)])
     (fixture
      :empty-seed
      2
      [(parent 0 1) (parent 1 0)])]))

(deftest generated-recursive-worklist-refines-oracle-and-v8
  (let [expected (oracle/authorization-set recursive-fixture)
        client (seed-recursive-datascript-client)
        subject (fixtures/->user "recursive-user")
        resource
        (eacl/spice-object
         :folder
         (str "folder-"
              (dec fixtures/recursive-connected-folder-count)))
        wanted-resources
        (into
         #{}
         (for [[grant-subject permission grant-resource] expected
               :when (and (= subject grant-subject)
                          (= :read permission)
                          (= :folder (:type grant-resource)))]
           grant-resource))
        generated-forward
        (formal/recursive-forward
         recursive-fixture subject :folder :read)
        production-resources
        (->> (eacl/lookup-resources
              client
              {:subject subject
               :permission :read
               :resource/type :folder
               :first 100})
             :data
             set)
        wanted-subjects
        (into
         #{}
         (for [[grant-subject permission grant-resource] expected
               :when (and (= resource grant-resource)
                          (= :duplicate permission)
                          (= :user (:type grant-subject)))]
           grant-subject))
        generated-reverse
        (formal/recursive-reverse
         recursive-fixture resource :user :duplicate)
        production-subjects
        (->> (eacl/lookup-subjects
              client
              {:resource resource
               :permission :duplicate
               :subject/type :user
               :first 100})
             :data
             set)]
    (is (= :complete (:status generated-forward)))
    (is (= wanted-resources
           (set (:items generated-forward))
           production-resources))
    (is (= :complete (:status generated-reverse)))
    (is (= wanted-subjects
           (set (:items generated-reverse))
           production-subjects))
    (doseq [limit
            [{:max-derived-grants 0}
             {:max-advanced-datoms 0}
             {:max-queued-work 0}]]
      (let [outcome
            (formal/recursive-forward
             recursive-fixture
             (fixtures/->user "recursive-user")
             :folder
             :read
             limit)]
        (is (= :limit-exceeded (:status outcome)))
        (is (#{:derived-grants :advanced-datoms :queued-work}
             (:limit-kind outcome)))
        (is (not (contains? outcome :items)))))))

(deftest generated-recursive-shapes-agree-with-oracle-and-v8
  (doseq [{:keys [id subject] :as fixture}
          recursive-shape-fixtures]
    (testing (name id)
      (let [expected
            (into
             #{}
             (for [[grant-subject permission resource]
                   (oracle/authorization-set fixture)
                   :when (and (= subject grant-subject)
                              (= :read permission)
                              (= :folder (:type resource)))]
               resource))
            generated
            (formal/recursive-forward
             fixture subject :folder :read)
            production
            (->> (eacl/lookup-resources
                  (seed-recursive-datascript-client fixture)
                  {:subject subject
                   :permission :read
                   :resource/type :folder
                   :first 100})
                 :data
                 set)]
        (is (= :complete (:status generated)))
        (is (= expected
               (set (:items generated))
               production))))))

(defn- apply-window
  [values cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (if (map? cursor-or-options)
          cursor-or-options
          {:direction :asc
           :bound-eid cursor-or-options
           :inclusive-bound? false})
        direction (or direction :asc)
        within?
        (case direction
          :asc (if (some? bound-eid)
                 (if inclusive-bound?
                   #(<= bound-eid %)
                   #(< bound-eid %))
                 (constantly true))
          :desc (if (some? bound-eid)
                  (if inclusive-bound?
                    #(>= bound-eid %)
                    #(> bound-eid %))
                  (constantly true)))]
    (cond->> values
      :always sort
      :always (filter within?)
      (= :desc direction) reverse)))

(defn- pure-adapters
  [fixture]
  (let [objects (:objects fixture)
        external->internal
        (into {}
              (map-indexed
               (fn [index object]
                 [object (+ 1000 index)])
               objects))
        id->object
        (into {} (map (juxt :id identity)) objects)
        internal->object
        (into {} (map (fn [[object internal]]
                        [internal object]))
              external->internal)
        relations
        (into {}
              (map-indexed
               (fn [index relation]
                 [[(:resource-type relation)
                   (:relation-name relation)]
                  (assoc relation :relation-id (+ 2000 index))])
               (:relations fixture)))
        permissions
        (->> (:permissions fixture)
             (map-indexed
              (fn [index permission]
                (assoc permission :permission-id (+ 3000 index))))
             (group-by
              (juxt :resource-type :permission-name)))
        relation-defs
        (fn [resource-type relation-name]
          (if-let [definition
                   (get relations [resource-type relation-name])]
            [definition]
            []))
        permission-defs
        (fn [resource-type permission-name]
          (get permissions [resource-type permission-name] []))
        scan
        (fn [subject-type subject-id relation-id
             resource-type cursor-or-options]
          (let [subject (get internal->object subject-id)
                relation-name
                (:relation-name
                 (first
                  (filter
                   #(= relation-id (:relation-id %))
                   (vals relations))))]
            (apply-window
             (for [{relationship-subject :subject
                    relationship-relation :relation
                    resource :resource}
                   (:relationships fixture)
                   :when
                   (and (= subject relationship-subject)
                        (= subject-type (:type relationship-subject))
                        (= relation-name relationship-relation)
                        (= resource-type (:type resource)))]
               (get external->internal resource))
             cursor-or-options)))
        reverse-scan
        (fn [resource-type resource-id relation-id
             subject-type cursor-or-options]
          (let [resource (get internal->object resource-id)
                relation-name
                (:relation-name
                 (first
                  (filter
                   #(= relation-id (:relation-id %))
                   (vals relations))))]
            (apply-window
             (for [{subject :subject
                    relationship-relation :relation
                    relationship-resource :resource}
                   (:relationships fixture)
                   :when
                   (and (= resource relationship-resource)
                        (= resource-type (:type relationship-resource))
                        (= relation-name relationship-relation)
                        (= subject-type (:type subject)))]
               (get external->internal subject))
             cursor-or-options)))
        direct-match?
        (fn [subject-type subject-id relation-id
             resource-type resource-id]
          (let [subject (get internal->object subject-id)
                resource (get internal->object resource-id)
                relation-name
                (:relation-name
                 (first
                  (filter
                   #(= relation-id (:relation-id %))
                   (vals relations))))]
            (boolean
             (some
              #(and (= subject (:subject %))
                    (= subject-type (get-in % [:subject :type]))
                    (= relation-name (:relation %))
                    (= resource (:resource %))
                    (= resource-type
                       (get-in % [:resource :type])))
              (:relationships fixture)))))
        legacy
        {:cache-stamp (constantly 1)
         :relation-defs relation-defs
         :permission-defs permission-defs
         :permission-expression (fn [& _] nil)
         :subject->resources scan
         :resource->subjects reverse-scan
         :direct-match? direct-match?}
        operations
        {:snapshot-id (constantly {:source :memory :revision 1})
         :basis-kind (constantly :ordinary)
         :native-revision
         (constantly {:revision 1 :exact-locator 1})
         :order-hint (constantly 1)
         :exact-locator (constantly 1)
         :object-id->internal
         (fn [object-id]
           (some-> (get id->object object-id)
                   external->internal))
         :internal-id->object
         (fn [internal-id]
           (:id (get internal->object internal-id)))
         :relation-defs relation-defs
         :permission-defs permission-defs
         :permission-expression (fn [& _] nil)
         :subject->resources scan
         :resource->subjects reverse-scan
         :direct-match? direct-match?
         :relation-populated? (fn [& _] false)
         :all-permission-nodes
         (constantly (:permission-nodes fixture))
         :proof-frame
         (fn [relation-ids]
           {:schema-stamp 1
            :relation-stamps
            (mapv (fn [relation-id] [relation-id 1])
                  (sort relation-ids))})}
        adapter
        (backend/make-adapter
         {:id :formal-memory
          :capabilities {:cache-proofs #{:ordered-generations}}
          :operations operations})]
    {:v8 adapter
     :indexed legacy
     :external->internal external->internal
     :internal->object internal->object}))

(deftest generated-kernel-v8-and-indexed-agree-on-coherent-acyclic-fixture
  (doseq [fixture
          (certification/coherent-fixtures [820084 820085])]
    (let [{v8-adapter :v8
           indexed-adapter :indexed
           :keys [external->internal internal->object]}
          (pure-adapters fixture)
          semantic-fixture
          (select-keys
           fixture [:objects :relationships :rules])
          expected (oracle/authorization-set semantic-fixture)
          resources
          (filter #(= :document (:type %)) (:objects fixture))
          subjects
          (filter #(= :user (:type %)) (:objects fixture))]
      (doseq [subject subjects
              resource resources]
        (let [wanted
              (contains?
               expected [subject :view resource])
              generated
              (formal/direct-can?
               {:objects (:objects fixture)
                :relationships (:relationships fixture)
                :rules
                {[:document :view] [:relation :reader]}}
               subject :view resource)
              expected-direct
              (boolean
               (some
                #(and (= subject (:subject %))
                      (= :reader (:relation %))
                      (= resource (:resource %)))
                (:relationships fixture)))
              indexed-subject
              (assoc subject :id
                     (get external->internal subject))
              indexed-resource
              (assoc resource :id
                     (get external->internal resource))]
          (is (= :passed
                 (:status
                  (differential/compare-values!
                   {:seed (:seed fixture)
                    :case-id
                    [:can? (:id subject) (:id resource)]
                    :values
                    [[:formal-semantics wanted]
                     [:legacy-v8
                      (v8/can?
                       v8-adapter subject :view resource)]
                     [:legacy-indexed
                      (indexed/can?
                       indexed-adapter
                       indexed/calc-permission-paths
                       indexed-subject
                       :view
                       indexed-resource)]]}))))
          (is (= expected-direct generated))))
      (doseq [subject subjects]
        (let [wanted
              (into
               #{}
               (for [[grant-subject permission resource] expected
                     :when (and (= subject grant-subject)
                                (= :view permission))]
                 resource))
              generated
              (set
               (formal/acyclic-forward
                semantic-fixture subject :document :view))
              v8-result
              (->> (v8/lookup-resources
                    v8-adapter
                    {:subject subject
                     :permission :view
                     :resource/type :document
                     :first 100})
                   :data
                   (map
                    #(get internal->object (:id %)))
                   set)
              indexed-result
              (->> (indexed/lookup
                    indexed-adapter
                    indexed/forward-direction
                    indexed/calc-permission-paths
                    {:subject
                     (assoc
                      subject :id
                      (get external->internal subject))
                     :permission :view
                     :resource/type :document
                     :limit 100})
                   :data
                   (map
                    #(get internal->object (:id %)))
                   set)]
          (is (= :passed
                 (:status
                  (differential/compare-values!
                   {:seed (:seed fixture)
                    :case-id [:lookup-resources (:id subject)]
                    :values
                    [[:formal-semantics wanted]
                     [:verified-generated-java generated]
                     [:legacy-v8 v8-result]
                     [:legacy-indexed indexed-result]]})))))))))
