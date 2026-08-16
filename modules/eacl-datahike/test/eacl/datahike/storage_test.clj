(ns eacl.datahike.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.integrity :as integrity]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]))

(def ^:private relationship-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(def ^:private self-relationship-schema
  "definition node {
     relation peer: node
   }")

(def ^:private modes
  {"attributes as keywords" false
   "attributes as numeric refs" true})

(defn- walk-relationship-pages
  [client query]
  (loop [query query
         pages []]
    (let [page (eacl/read-relationships client query)
          pages' (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur (assoc query
                      :after (get-in page [:page-info :end-cursor]))
               pages')
        pages'))))

(defn- walk-relationship-pages-backward
  [client query]
  (loop [query query
         pages ()]
    (let [page (eacl/read-relationships client query)
          pages' (conj pages page)]
      (if (get-in page [:page-info :has-previous-page?])
        (recur (assoc query
                      :before (get-in page [:page-info :start-cursor]))
               pages')
        pages'))))

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
       db user-eid relationship-storage/forward-attribute))
     :reverse
     (vec
      (ddb/eavt-datoms
       db account-eid relationship-storage/reverse-attribute))}))

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
                       relationship-storage/forward-attribute)))
                  "cardinality-many retains both resources on the subject")
              (is (= 1
                     (count
                      (ddb/eavt-datoms
                       db-after
                       account-2-eid
                       relationship-storage/reverse-attribute))))))
          (finally
            (d/release conn)))))))

(deftest relationship-prefix-seeks-stay-within-the-requested-attribute-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [conn
            (datahike/create-conn
             nil
             {:attribute-refs? attribute-refs?
              :commit-graph? false
              :keep-history? true})
            client (datahike/make-client conn {})
            node-a (eacl/spice-object :node "node-a")
            node-b (eacl/spice-object :node "node-b")
            relationship (eacl/->Relationship node-b :peer node-a)]
        (try
          (eacl/write-schema! client self-relationship-schema)
          (d/transact conn [{:eacl/id "node-a"}
                            {:eacl/id "node-b"}])
          (eacl/create-relationship! client relationship)
          (let [snapshot (d/db conn)
                snapshot-tx (:max-tx snapshot)
                node-a-eid (ddb/entid snapshot [:eacl/id "node-a"])
                node-b-eid (ddb/entid snapshot [:eacl/id "node-b"])
                relation-eid
                (ddb/entid
                 snapshot
                 [:eacl/id
                  (model/->relation-id :node :peer :node)])
                assert-snapshot!
                (fn [db]
                  (is (= [node-a-eid]
                         (vec
                          (impl/subject->resources
                           db :node node-b-eid relation-eid :node nil))))
                  (is (empty?
                       (impl/subject->resources
                        db :node node-a-eid relation-eid :node nil))
                      "an incoming edge must not be returned as outgoing")
                  (is (= [node-b-eid]
                         (vec
                          (impl/resource->subjects
                           db :node node-a-eid relation-eid :node nil))))
                  (is (empty?
                       (impl/resource->subjects
                        db :node node-b-eid relation-eid :node nil))
                      "an outgoing edge must not be returned as incoming"))]
            (assert-snapshot! snapshot)
            (eacl/delete-relationship! client relationship)
            (assert-snapshot!
             (d/as-of (d/db conn) snapshot-tx)))
          (finally
            (d/release conn)))))))

(deftest relationship-keyset-pages-are-stable-and-duplicate-free-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [relationship-count 41
            conn
            (datahike/create-conn
             nil
             {:attribute-refs? attribute-refs?})
            client (datahike/make-client conn {})
            account (eacl/spice-object :account "account")
            users
            (mapv
             #(eacl/spice-object :user (str "user-" %))
             (range relationship-count))
            accounts
            (mapv
             #(eacl/spice-object :account (str "account-" %))
             (range relationship-count))
            anchor-user (eacl/spice-object :user "anchor-user")
            relationships
            (into
             (mapv #(eacl/->Relationship % :owner account) users)
             (map #(eacl/->Relationship anchor-user :owner %) accounts))]
        (try
          (eacl/write-schema! client relationship-schema)
          (d/transact
           conn
           (into
            [{:eacl/id "account"}
             {:eacl/id "anchor-user"}]
            (map
             (fn [{:keys [id]}] {:eacl/id id})
             (concat users accounts))))
          (eacl/create-relationships! client relationships)
          (doseq [[base-query expected-count]
                  [[{:subject/type :user :subject/id "anchor-user"}
                    relationship-count]
                   [{:resource/id "account"} relationship-count]
                   [{:subject/type :user} (* 2 relationship-count)]
                   [{:resource/type :account} (* 2 relationship-count)]]]
            (let [forward-pages
                  (walk-relationship-pages
                   client (assoc base-query :first 7))
                  backward-pages
                  (walk-relationship-pages-backward
                   client (assoc base-query :last 7))
                  forward (vec (mapcat :data forward-pages))
                  backward (vec (mapcat :data backward-pages))
                  first-page (first forward-pages)
                  last-page (last backward-pages)
                  backward-from-forward
                  (eacl/read-relationships
                   client
                   (assoc
                    base-query
                    :last 3
                    :before
                    (get-in first-page [:page-info :end-cursor])))
                  forward-from-backward
                  (eacl/read-relationships
                   client
                   (assoc
                    base-query
                    :first 3
                    :after
                    (get-in last-page [:page-info :start-cursor])))]
              (is (= expected-count (count forward)))
              (is (= expected-count (count (distinct forward))))
              (is (= forward backward))
              (is (= (->> (:data first-page) butlast (take-last 3) vec)
                     (:data backward-from-forward)))
              (is (= (->> (:data last-page) rest (take 3) vec)
                     (:data forward-from-backward)))
              (is (=
                   (mapv
                    (fn [{:keys [data page-info]}]
                      {:data data
                       :page-info
                       (select-keys
                        page-info
                        [:has-next-page?
                         :has-previous-page?])})
                    forward-pages)
                   (mapv
                    (fn [{:keys [data page-info]}]
                      {:data data
                       :page-info
                       (select-keys
                        page-info
                        [:has-next-page?
                         :has-previous-page?])})
                    (walk-relationship-pages
                     client (assoc base-query :first 7)))))))
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
               relationship-storage/reverse-attribute
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
                 relationship-storage/forward-attribute
                 (vec (:v (first forward)))]])
              (eacl/delete-relationship! client relationship)
              (let [{:keys [forward reverse]}
                    (relationship-state (d/db conn))]
                (is (empty? forward))
                (is (empty? reverse)))))
          (finally
            (d/release conn)))))))

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
    (catch clojure.lang.ExceptionInfo error
      (:type (ex-data error)))))

(deftest concurrent-creates-are-serialized-at-commit-test
  (doseq [[label attribute-refs?] modes]
    (testing label
      (let [conn (datahike/create-conn nil {:attribute-refs? attribute-refs?})
            client (datahike/make-client conn {})
            user (eacl/spice-object :user "user")
            account (eacl/spice-object :account "account")
            relationship (eacl/->Relationship user :owner account)]
        (try
          (eacl/write-schema! client relationship-schema)
          (d/transact conn [{:eacl/id "user"} {:eacl/id "account"}])
          (testing "two :create plans made against the same pre-write value"
            (let [db (d/db conn)
                  internal (lookup-ref-relationship relationship)
                  plan (fn []
                         (impl/tx-update-relationship
                          db {:operation :create :relationship internal}))
                  first-plan (plan)
                  second-plan (plan)
                  transact! #'datahike/transact-native!]
              (is (true? (ddb/direct-writer? db)))
              (is (some #(= :db.fn/call (first %)) first-plan)
                  "a direct writer decides the create at commit time")
              (is (= :ok (error-type
                          #(transact! conn {:tx-data first-plan}))))
              (let [raw (try
                          (d/transact conn second-plan)
                          nil
                          (catch Exception error error))]
                (is (some? raw)
                    "the loser observes the winner inside the transaction")
                (is (nil? (:type (ex-data raw)))
                    "Datahike reports the failing transaction function wrapped")
                (is (= :eacl/relationship-conflict
                       (:eacl/error
                        (ex-data (datahike/typed-transaction-error raw))))
                    "the typed conflict is recoverable from the cause chain"))
              (is (= :eacl/relationship-conflict
                     (error-type
                      #(transact! conn {:tx-data second-plan})))
                  "the client's transaction wrapper surfaces the typed conflict")
              (let [{:keys [forward reverse]} (relationship-state (d/db conn))]
                (is (= 1 (count forward)))
                (is (= 1 (count reverse))))
              (is (true? (eacl/can? client user :admin account)))))
          (testing "the public write path keeps its contract"
            (is (= :eacl/relationship-conflict
                   (error-type
                    #(eacl/create-relationship! client relationship))))
            (is (= :ok
                   (error-type
                    #(eacl/write-relationships!
                      client [{:operation :touch
                               :relationship relationship}])))
                ":touch stays idempotent")
            (is (= 1 (count (:data (eacl/read-relationships
                                    client {:resource/type :account}))))))
          (finally
            (d/release conn)))))))

(deftest repeated-and-conflicting-relationship-batch-test
  (doseq [[label attribute-refs?] modes
          operations
          (for [left [:create :touch :delete]
                right [:create :touch :delete]
                :when (not= left right)]
            [left right])]
    (testing (str label " " (pr-str operations))
      (let [conn (datahike/create-conn nil
                                       {:attribute-refs? attribute-refs?})
            client (datahike/make-client conn {})
            user (eacl/spice-object :user "user")
            account (eacl/spice-object :account "account")
            relationship (eacl/->Relationship user :owner account)]
        (try
          (eacl/write-schema! client relationship-schema)
          (d/transact conn [{:eacl/id "user"} {:eacl/id "account"}])
          (is (= :eacl/invalid-relationship-update-batch
                 (error-type
                  #(eacl/write-relationships!
                    client
                    (mapv (fn [operation]
                            (eacl/->RelationshipUpdate
                             operation relationship))
                          operations)))))
          (let [{:keys [forward reverse]}
                (relationship-state (d/db conn))]
            (is (empty? forward))
            (is (empty? reverse)))
          (is (= :ok
                 (error-type
                  #(eacl/write-relationships!
                    client
                    [(eacl/->RelationshipUpdate :create relationship)
                     (eacl/->RelationshipUpdate :create relationship)]))))
          (let [{:keys [forward reverse]}
                (relationship-state (d/db conn))]
            (is (= 1 (count forward)))
            (is (= 1 (count reverse))))
          (is (= :ok
                 (error-type
                  #(eacl/write-relationships!
                    client
                    [(eacl/->RelationshipUpdate :touch relationship)
                     (eacl/->RelationshipUpdate :touch relationship)]))))
          (let [{:keys [forward reverse]}
                (relationship-state (d/db conn))]
            (is (= 1 (count forward)))
            (is (= 1 (count reverse))))
          (is (= :ok
                 (error-type
                  #(eacl/write-relationships!
                    client
                    [(eacl/->RelationshipUpdate :delete relationship)
                     (eacl/->RelationshipUpdate :delete relationship)]))))
          (let [{:keys [forward reverse]}
                (relationship-state (d/db conn))]
            (is (empty? forward))
            (is (empty? reverse)))
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
