(ns eacl.datascript.storage-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.db :as ddb]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.integrity :as integrity]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.model :as model]))

(def relationship-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(def operator-storage-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base & reader - banned
   }")

(def replacement-storage-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = reader - banned
   }")

(def no-permission-storage-schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
   }")

(def invalid-negative-cycle-schema
  "definition user {}
   definition document {
     relation reader: user
     permission a = reader - b
     permission b = a
   }")

(defn- exception-data [thunk]
  (try
    (thunk)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) error
      (ex-data error))))

(defn- expression-storage-projection [db]
  {:schema (schema/read-schema db)
   :permissions
   (->> (schema/read-permissions db)
        (map #(select-keys % expression-persistence/expression-attributes))
        (sort-by :eacl/id)
        vec)})

(deftest permission-storage-is-expression-only-and-replacement-is-atomic-test
  (let [conn (datascript/create-conn)
        _ (schema/write-schema! conn operator-storage-schema)
        before-db (ds/db conn)
        before (schema/read-permissions before-db)
        view-id (expression-persistence/->expression-id :document :view)
        view-eid (ds/entid before-db [:eacl/id view-id])
        before-payload (:eacl.permission/expression-payload
                        (first (filter #(= view-id (:eacl/id %)) before)))]
    (is (= 2 (count before)))
    (is (every? #(not-any? (fn [attribute]
                             (contains? % attribute))
                           expression-persistence/legacy-flat-attributes)
                before))
    (is (empty?
         (filter #(contains? expression-persistence/legacy-flat-attributes
                             (:a %))
                 (ds/datoms before-db :eavt))))
    (schema/write-schema! conn replacement-storage-schema)
    (let [after-db (ds/db conn)
          after (schema/read-permissions after-db)
          view (first (filter #(= view-id (:eacl/id %)) after))]
      (is (= view-eid (ds/entid after-db [:eacl/id view-id])))
      (is (= 2 (count after)))
      (is (not= before-payload
                (:eacl.permission/expression-payload view)))
      (is (= :exclusion
             (:op (:root (expression-persistence/decode-entity view))))))
    (let [stable-db (ds/db conn)
          stable-schema (schema/read-schema stable-db)
          stable-generation (schema/current-schema-generation stable-db)
          data (exception-data
                #(schema/write-schema! conn invalid-negative-cycle-schema))
          after-failure (ds/db conn)]
      (is (= :eacl.schema/unstratified-exclusion (:type data)))
      (is (= stable-generation
             (schema/current-schema-generation after-failure)))
      (is (= stable-schema (schema/read-schema after-failure))))
    (let [exported (ds/serializable before-db)
          restored (ds/from-serializable exported)]
      (is (= (expression-storage-projection before-db)
             (expression-storage-projection restored))
          "DataScript export/import backup preserves expression rows"))
    (schema/write-schema! conn no-permission-storage-schema)
    (is (empty? (schema/read-permissions (ds/db conn))))
    (is (= 2 (count (schema/read-permissions before-db))))))

(deftest permission-storage-fails-closed-on-flat-mixed-and-duplicate-rows-test
  (testing "flat-only"
    (let [conn (datascript/create-conn)]
      (ds/transact!
       conn
       [{:eacl/id "flat"
         :eacl.permission/resource-type :document
         :eacl.permission/permission-name :view
         :eacl.permission/source-relation-name :self
         :eacl.permission/target-type :relation
         :eacl.permission/target-name :reader}])
      (is (= :flat-only-representation
             (:reason (exception-data #(schema/read-schema (ds/db conn))))))))
  (testing "mixed"
    (let [conn (datascript/create-conn)
          _ (schema/write-schema! conn relationship-schema)
          permission (first (schema/read-permissions (ds/db conn)))]
      (ds/transact!
       conn
       [[:db/add [:eacl/id (:eacl/id permission)]
         :eacl.permission/target-type :relation]])
      (is (= :mixed-flat-and-expression
             (:reason (exception-data #(schema/read-schema (ds/db conn))))))))
  (testing "duplicate"
    (let [conn (datascript/create-conn)
          _ (schema/write-schema! conn relationship-schema)
          permission (first (schema/read-permissions (ds/db conn)))]
      (ds/transact! conn [(assoc permission :eacl/id "duplicate")])
      (is (= :duplicate-expression
             (:reason (exception-data #(schema/read-schema (ds/db conn)))))))))

(defn- seeded
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        user (eacl/spice-object :user "user")
        account (eacl/spice-object :account "account")
        relationship (eacl/->Relationship user :owner account)]
    (eacl/write-schema! client relationship-schema)
    (ds/transact! conn [{:eacl/id "user"}
                        {:eacl/id "account"}])
    (eacl/create-relationship! client relationship)
    {:conn conn
     :client client
     :user user
     :account account
     :relationship relationship}))

(defn- relationship-state
  [db]
  (let [user-eid (ddb/entid db [:eacl/id "user"])
        account-eid (ddb/entid db [:eacl/id "account"])
        relation-eid
        (ddb/entid
         db
         [:eacl/id
          (model/->relation-id :account :owner :user)])]
    {:user-eid user-eid
     :account-eid account-eid
     :relation-eid relation-eid
     :forward
     (vec
      (ddb/eavt-datoms
       db user-eid relationship-storage/forward-attribute))
     :reverse
     (vec
      (ddb/eavt-datoms
       db account-eid relationship-storage/reverse-attribute))}))

(deftest endpoint-attributes-are-two-indexed-ordinary-values-test
  (let [forward-definition
        (get schema/datascript-schema relationship-storage/forward-attribute)
        reverse-definition
        (get schema/datascript-schema relationship-storage/reverse-attribute)
        removed-attributes
        [:eacl.relationship/subject
         :eacl.relationship/relation
         :eacl.relationship/resource
         :eacl.relationship/subject-type
         :eacl.relationship/resource-type
         :eacl.relationship/full-key
         :eacl.v7.relationship/subject-type+subject+relation+resource-type+resource
         :eacl.v7.relationship/resource-type+resource+relation+subject-type+subject
         :eacl.v7.relationship/subject-type+relation+resource-type+resource+subject
         :eacl.v7.relationship/resource-type+relation+subject-type+subject+resource]]
    (is (= {:db/cardinality :db.cardinality/many
            :db/index true}
           forward-definition))
    (is (= forward-definition reverse-definition))
    (is (not (contains? forward-definition :db/valueType)))
    (is (not (contains? forward-definition :db/tupleAttrs)))
    (is (every? #(not (contains? schema/datascript-schema %))
                removed-attributes))))

(deftest derived-expression-metrics-are-not-schema-attributes-test
  (is (every? #(not (contains? schema/datascript-schema %))
              expression-persistence/retired-expression-attributes)))

(deftest full-arity-index-boundaries-test
  (let [conn (schema/create-conn)
        _ (ds/transact! conn [{:eacl/id "subject-a"}
                              {:eacl/id "subject-b"}
                              {:eacl/id "resource-a"}
                              {:eacl/id "resource-b"}])
        initial (ds/db conn)
        subject-a (ddb/entid initial [:eacl/id "subject-a"])
        subject-b (ddb/entid initial [:eacl/id "subject-b"])
        resource-a (ddb/entid initial [:eacl/id "resource-a"])
        resource-b (ddb/entid initial [:eacl/id "resource-b"])
        relation-eid 100
        prefix [:user relation-eid :document]
        values [(endpoint-pair/forward-value
                 :user relation-eid :document resource-a)
                (endpoint-pair/forward-value
                 :user relation-eid :document resource-b)]
        _ (ds/transact!
           conn
           (concat
            (map (fn [value]
                   [:db/add subject-a
                    relationship-storage/forward-attribute value])
                 values)
            [[:db/add subject-a relationship-storage/forward-attribute
              [:user (inc relation-eid) :document resource-a]]
             [:db/add subject-a relationship-storage/forward-attribute
              [:user relation-eid :folder resource-a]]
             [:db/add subject-b relationship-storage/forward-attribute
              [:user relation-eid :document resource-a]]
             [:db/add resource-a relationship-storage/reverse-attribute
              [:document relation-eid :user subject-a]]]))
        db (ds/db conn)
        eavt-values
        (fn [direction cursor]
          (mapv :v
                (ddb/eavt-endpoint-prefix
                 db subject-a relationship-storage/forward-attribute
                 prefix cursor direction)))
        avet-values
        (fn [direction cursor]
          (mapv :v
                (ddb/avet-endpoint-prefix
                 db relationship-storage/forward-attribute
                 prefix cursor direction)))]
    (testing "equal-length vectors traverse in component order"
      (is (= values (eavt-values :asc nil)))
      (is (= (vec (reverse values)) (eavt-values :desc nil)))
      (is (= [(first values) (first values) (second values)]
             (avet-values :asc nil)))
      (is (= [(second values) (first values) (first values)]
             (avet-values :desc nil))))
    (testing "cursor bounds are inclusive at the primitive layer"
      (is (= [(second values)]
             (eavt-values :asc resource-b)))
      (is (= [(first values)]
             (eavt-values :desc resource-a))))
    (testing "missing and adjacent prefixes terminate exactly"
      (is (empty?
           (ddb/eavt-endpoint-prefix
            db subject-a relationship-storage/forward-attribute
            [:user 99 :document])))
      (is (empty?
           (ddb/eavt-endpoint-prefix
            db subject-a relationship-storage/reverse-attribute prefix))))
    (testing "EAVT isolates the endpoint while AVET intentionally spans it"
      (is (= 2 (count (eavt-values :asc nil))))
      (is (= 3 (count (avet-values :asc nil)))))))

(deftest relationships-cost-two-datoms-and-pairs-repair-test
  (let [{:keys [conn client relationship]} (seeded)
        db (ds/db conn)
        {:keys [user-eid account-eid relation-eid forward reverse]}
        (relationship-state db)]
    (is (= [[user-eid
             [[:user relation-eid :account account-eid]]]]
           [[(:e (first forward)) (mapv :v forward)]]))
    (is (= [[account-eid
             [[:account relation-eid :user user-eid]]]]
           [[(:e (first reverse)) (mapv :v reverse)]]))
    (is (= 2 (+ (count forward) (count reverse))))
    (is (= 1
           (schema/count-relationships-using-relation
            db
            {:eacl.relation/resource-type :account
             :eacl.relation/relation-name :owner
             :eacl.relation/subject-type :user})))
    (is (= :eacl/relationship-conflict
           (:type
            (try
              (eacl/create-relationship! client relationship)
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo) error
                (ex-data error))))))
    (is (= :eacl/unsupported-operation
           (:type
            (try
              (eacl/write-relationship!
               client
               {:operation :merge
                :subject (:subject relationship)
                :relation (:relation relationship)
                :resource (:resource relationship)})
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo) error
                (ex-data error))))))
    (is (= :eacl/unknown-object
           (:type
            (try
              (eacl/create-relationship!
               client
               (eacl/->Relationship
                (:subject relationship)
                (:relation relationship)
                (eacl/spice-object :account "missing")))
              nil
              (catch #?(:clj clojure.lang.ExceptionInfo
                        :cljs cljs.core.ExceptionInfo) error
                (ex-data error))))))

    (ds/transact!
     conn
     [[:db/retract
       account-eid relationship-storage/reverse-attribute
       (:v (first reverse))]])
    (is (= {:valid? false
            :dangling-count 1
            :by-half {:forward 1 :reverse 0}
            :sample []}
           (integrity/dangling-relationship-report
            (ds/db conn) {:sample-size 0})))

    (eacl/write-relationship!
     client
     {:operation :touch
      :subject (:subject relationship)
      :relation (:relation relationship)
      :resource (:resource relationship)})
    (is (:valid?
         (integrity/dangling-relationship-report (ds/db conn))))
    (eacl/write-relationships!
     client
     [(eacl/->RelationshipUpdate :touch relationship)
      (eacl/->RelationshipUpdate :touch relationship)])
    (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
      (is (= 1 (count forward)))
      (is (= 1 (count reverse))))

    (let [{:keys [forward]} (relationship-state (ds/db conn))]
      (ds/transact!
       conn
       [[:db/retract
         user-eid relationship-storage/forward-attribute
         (:v (first forward))]]))
    (eacl/delete-relationship! client relationship)
    (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
      (is (empty? forward))
      (is (empty? reverse)))))

(deftest retain-inert-presence-detects-reverse-only-ghost-test
  (let [{:keys [conn]} (seeded)
        db (ds/db conn)
        {:keys [user-eid forward reverse]} (relationship-state db)
        relation
        (first
         (filter #(= :owner (:eacl.relation/relation-name %))
                 (schema/read-relations db)))]
    (is (= 1 (count forward)))
    (is (= 1 (count reverse)))
    (ds/transact!
     conn
     [[:db/retract user-eid relationship-storage/forward-attribute
       (:v (first forward))]])
    (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
      (is (empty? forward))
      (is (= 1 (count reverse)))
      (is (true?
           (schema/relationship-present-for-relation?
            (ds/db conn) relation))
          "retain-inert diagnostics must detect either surviving tuple half"))))

(defn- lookup-ref-relationship
  "The internal relationship shape the shared client hands to the impl:
  endpoint ids as lookup refs, which also serve as commit-time identity
  guards."
  [relationship]
  (let [lookup-object #(assoc % :id [:eacl/id (:id %)])]
    (-> relationship
        (update :subject lookup-object)
        (update :resource lookup-object))))

(defn- error-type
  [thunk]
  (try
    (thunk)
    :ok
    (catch #?(:clj clojure.lang.ExceptionInfo
              :cljs cljs.core.ExceptionInfo) error
      (:type (ex-data error)))))

(deftest concurrent-creates-are-serialized-at-commit-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})
        user (eacl/spice-object :user "user")
        account (eacl/spice-object :account "account")
        relationship (eacl/->Relationship user :owner account)]
    (eacl/write-schema! client relationship-schema)
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "account"}])
    (testing "two :create plans made against the same pre-write value"
      (let [db (ds/db conn)
            internal (lookup-ref-relationship relationship)
            plan (fn []
                   (impl/tx-update-relationship
                    db {:operation :create :relationship internal}))
            first-plan (plan)
            second-plan (plan)]
        (is (some #(= :db.fn/call (first %)) first-plan)
            "the create is decided by a transaction function at commit time")
        (is (= :ok (error-type #(ds/transact! conn first-plan))))
        (is (= :eacl/relationship-conflict
               (error-type #(ds/transact! conn second-plan)))
            "the loser observes the winner inside the transaction")
        (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
          (is (= 1 (count forward)))
          (is (= 1 (count reverse))))
        (is (true? (eacl/can? client user :admin account)))))
    (testing "the public write path keeps its contract"
      (is (= :eacl/relationship-conflict
             (error-type #(eacl/create-relationship! client relationship))))
      (is (= :ok
             (error-type
              #(eacl/write-relationships!
                client [{:operation :touch :relationship relationship}])))
          ":touch stays idempotent")
      (is (= 1 (count (:data (eacl/read-relationships
                              client {:resource/type :account}))))))))

(deftest repeated-and-conflicting-relationship-batch-test
  (doseq [operations
          (for [left [:create :touch :delete]
                right [:create :touch :delete]
                :when (not= left right)]
            [left right])]
    (testing (pr-str operations)
      (let [conn (datascript/create-conn)
            client (datascript/make-client conn {})
            user (eacl/spice-object :user "user")
            account (eacl/spice-object :account "account")
            relationship (eacl/->Relationship user :owner account)]
        (eacl/write-schema! client relationship-schema)
        (ds/transact! conn [{:eacl/id "user"} {:eacl/id "account"}])
        (is (= :eacl/invalid-relationship-update-batch
               (error-type
                #(eacl/write-relationships!
                  client
                  (mapv (fn [operation]
                          (eacl/->RelationshipUpdate
                           operation relationship))
                        operations)))))
        (let [{:keys [forward reverse]}
              (relationship-state (ds/db conn))]
          (is (empty? forward))
          (is (empty? reverse)))
        (is (= :ok
               (error-type
                #(eacl/write-relationships!
                  client
                  [(eacl/->RelationshipUpdate :create relationship)
                   (eacl/->RelationshipUpdate :create relationship)]))))
        (let [{:keys [forward reverse]}
              (relationship-state (ds/db conn))]
          (is (= 1 (count forward)))
          (is (= 1 (count reverse))))
        (is (= :ok
               (error-type
                #(eacl/write-relationships!
                  client
                  [(eacl/->RelationshipUpdate :touch relationship)
                   (eacl/->RelationshipUpdate :touch relationship)]))))
        (let [{:keys [forward reverse]}
              (relationship-state (ds/db conn))]
          (is (= 1 (count forward)))
          (is (= 1 (count reverse))))
        (is (= :ok
               (error-type
                #(eacl/write-relationships!
                  client
                  [(eacl/->RelationshipUpdate :delete relationship)
                   (eacl/->RelationshipUpdate :delete relationship)]))))
        (let [{:keys [forward reverse]}
              (relationship-state (ds/db conn))]
          (is (empty? forward))
          (is (empty? reverse)))))))

(deftest delete-object-and-ghost-cleanup-test
  (let [{:keys [conn client account]} (seeded)
        {:keys [account-eid]} (relationship-state (ds/db conn))]
    (is (= 2 (:retracted-datoms
              (eacl/delete-object! client account))))
    (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
      (is (empty? forward))
      (is (empty? reverse)))
    (is (ddb/entity-exists? (ds/db conn) account-eid)))

  (let [{:keys [conn client account]} (seeded)
        {:keys [account-eid]} (relationship-state (ds/db conn))]
    (ds/transact! conn [[:db/retractEntity account-eid]])
    (is (= {:valid? false
            :dangling-count 1
            :by-half {:forward 1 :reverse 0}
            :sample []}
           (integrity/dangling-relationship-report
            (ds/db conn) {:sample-size 0})))
    (is (= 1
           (:retracted-datoms
            (eacl/delete-object!
             client (assoc account :id account-eid)))))
    (is (:valid?
         (integrity/dangling-relationship-report (ds/db conn))))))
