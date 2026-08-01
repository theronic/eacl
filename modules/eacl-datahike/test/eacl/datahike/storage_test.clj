(ns eacl.datahike.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.integrity :as integrity]
            [eacl.datahike.schema :as schema]
            [eacl.schema.model :as model]))

(def ^:private relationship-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(def ^:private modes
  {"attributes as keywords" false
   "attributes as numeric refs" true})

(defn- seeded
  [attribute-refs?]
  (let [conn (datahike/create-conn
              nil
              {:attribute-refs? attribute-refs?})
        client (datahike/make-client conn {})
        user (eacl/spice-object :user "user")
        account (eacl/spice-object :account "account")
        relationship (eacl/->Relationship user :owner account)]
    (eacl/write-schema! client relationship-schema)
    (d/transact conn [{:eacl/id "user"}
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

(deftest relationships-use-two-datomic-compatible-tuple-datoms-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [{:keys [conn client user]} (seeded attribute-refs?)]
        (try
          (let [db (d/db conn)
                {:keys [user-eid account-eid relation-eid
                        forward reverse]}
                (relationship-state db)]
            (is (= [[user-eid
                     [[:user relation-eid :account account-eid]]]]
                   [[(:e (first forward))
                     (mapv :v forward)]]))
            (is (= [[account-eid
                     [[:account relation-eid :user user-eid]]]]
                   [[(:e (first reverse))
                     (mapv :v reverse)]]))
            (is (= 2 (+ (count forward) (count reverse)))
                "one relationship costs exactly two relationship datoms")
            (is (nil? (ddb/entid db :eacl.relationship/full-key)))
            (is (nil?
                 (ddb/entid
                  db
                  :eacl.v7.relationship/subject-type+subject+relation+resource-type+resource)))
            (is (nil?
                 (ddb/entid
                  db
                  :eacl.v7.relationship/resource-type+resource+relation+subject-type+subject)))

            (d/transact conn [{:eacl/id "account-2"}])
            (eacl/create-relationship!
             client
             (eacl/->Relationship
              user
              :owner
              (eacl/spice-object :account "account-2")))
            (let [db-after (d/db conn)
                  account-2-eid
                  (ddb/entid db-after [:eacl/id "account-2"])]
              (is (= 2
                     (count
                      (ddb/eavt-datoms
                       db-after
                       user-eid
                       schema/forward-relationship-attr)))
                  "cardinality-many retains both resources on the subject")
              (is (= 1
                     (count
                      (ddb/eavt-datoms
                       db-after
                       account-2-eid
                       schema/reverse-relationship-attr))))))
          (finally
            (d/release conn)))))))

(deftest half-pairs-are-repairable-and-detectable-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [{:keys [conn client relationship]} (seeded attribute-refs?)]
        (try
          (let [{:keys [account-eid reverse]}
                (relationship-state (d/db conn))]
            (d/transact
             conn
             [[:db/retract
               account-eid
               schema/reverse-relationship-attr
               (vec (:v (first reverse)))]])
            (is (= {:valid? false
                    :dangling-count 1
                    :by-half {:forward 1 :reverse 0}
                    :sample []}
                   (integrity/dangling-relationship-report
                    (d/db conn) {:sample-size 0})))

            (eacl/write-relationship!
             client
             {:operation :touch
              :subject (:subject relationship)
              :relation (:relation relationship)
              :resource (:resource relationship)})
            (is (:valid?
                 (integrity/dangling-relationship-report
                  (d/db conn))))

            (let [{:keys [user-eid forward]}
                  (relationship-state (d/db conn))]
              (d/transact
               conn
               [[:db/retract
                 user-eid
                 schema/forward-relationship-attr
                 (vec (:v (first forward)))]])
              (eacl/delete-relationship! client relationship)
              (let [{:keys [forward reverse]}
                    (relationship-state (d/db conn))]
                (is (empty? forward))
                (is (empty? reverse)))))
          (finally
            (d/release conn)))))))

(deftest delete-object-removes-both-halves-before-entity-retraction-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [{:keys [conn client account]} (seeded attribute-refs?)]
        (try
          (let [{:keys [account-eid]} (relationship-state (d/db conn))]
            (is (= 2
                   (:retracted-datoms
                    (eacl/delete-object! client account))))
            (let [{:keys [forward reverse]}
                  (relationship-state (d/db conn))]
              (is (empty? forward))
              (is (empty? reverse)))
            (is (ddb/entity-exists? (d/db conn) account-eid)
                "delete-object! leaves the endpoint entity intact"))
          (finally
            (d/release conn)))))))

(deftest delete-object-repairs-a-ghost-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [{:keys [conn client account]} (seeded attribute-refs?)]
        (try
          (let [{:keys [account-eid]} (relationship-state (d/db conn))]
            (d/transact conn [[:db/retractEntity account-eid]])
            (is (= {:valid? false
                    :dangling-count 1
                    :by-half {:forward 1 :reverse 0}
                    :sample []}
                   (integrity/dangling-relationship-report
                    (d/db conn) {:sample-size 0})))

            (is (= 1
                   (:retracted-datoms
                    (eacl/delete-object!
                     client
                     (assoc account :id account-eid)))))
            (is (:valid?
                 (integrity/dangling-relationship-report
                  (d/db conn))))
            (is (false?
                 (eacl/can?
                  client
                  (eacl/spice-object :user "user")
                  :admin
                  (eacl/spice-object :account account-eid)))))
          (finally
            (d/release conn)))))))
