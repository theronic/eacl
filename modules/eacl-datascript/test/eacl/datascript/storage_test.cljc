(ns eacl.datascript.storage-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.db :as ddb]
            [eacl.datascript.integrity :as integrity]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.schema.model :as model]))

(def relationship-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

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
       db user-eid schema/forward-relationship-attr))
     :reverse
     (vec
      (ddb/eavt-datoms
       db account-eid schema/reverse-relationship-attr))}))

(deftest endpoint-attributes-are-two-indexed-ordinary-values-test
  (let [forward-definition
        (get schema/datascript-schema schema/forward-relationship-attr)
        reverse-definition
        (get schema/datascript-schema schema/reverse-relationship-attr)
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
                    schema/forward-relationship-attr value])
                 values)
            [[:db/add subject-a schema/forward-relationship-attr
              [:user (inc relation-eid) :document resource-a]]
             [:db/add subject-a schema/forward-relationship-attr
              [:user relation-eid :folder resource-a]]
             [:db/add subject-b schema/forward-relationship-attr
              [:user relation-eid :document resource-a]]
             [:db/add resource-a schema/reverse-relationship-attr
              [:document relation-eid :user subject-a]]]))
        db (ds/db conn)
        eavt-values
        (fn [direction cursor]
          (mapv :v
                (ddb/eavt-endpoint-prefix
                 db subject-a schema/forward-relationship-attr
                 prefix cursor direction)))
        avet-values
        (fn [direction cursor]
          (mapv :v
                (ddb/avet-endpoint-prefix
                 db schema/forward-relationship-attr
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
            db subject-a schema/forward-relationship-attr
            [:user 99 :document])))
      (is (empty?
           (ddb/eavt-endpoint-prefix
            db subject-a schema/reverse-relationship-attr prefix))))
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
       account-eid schema/reverse-relationship-attr
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
         user-eid schema/forward-relationship-attr
         (:v (first forward))]]))
    (eacl/delete-relationship! client relationship)
    (let [{:keys [forward reverse]} (relationship-state (ds/db conn))]
      (is (empty? forward))
      (is (empty? reverse)))))

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
