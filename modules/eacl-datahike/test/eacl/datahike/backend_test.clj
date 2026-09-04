(ns eacl.datahike.backend-test
  "Datahike-specific tests for the paths the shared contract does not reach.

   `assert-seeded-contracts!` goes through `make-client`, which serves relation
   definitions from a prebuilt schema catalog. So the contract suite never
   exercises the schema tuple-prefix path or the relation-in-use range directly.

   Every test runs in both attribute representations. Datahike reports `:a` as
   a keyword by default and as a numeric ref under `:attribute-refs?`, and an
   adapter that assumes only one representation can silently deny access."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]))

(def ^:private modes
  {"attributes as keywords"          nil
   "attributes as numeric refs"      {:attribute-refs? true}})

(defn- seeded-conn
  "The contract's schema, objects and relationships, so these tests describe the
   same world the contract does."
  [config]
  (let [conn   (datahike/create-conn nil config)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (d/transact conn
                (vec (map-indexed (fn [idx {:keys [id]}]
                                    {:db/id (- (inc idx)) :eacl/id id})
                                  contract/smoke-objects)))
    (eacl/create-relationships! client contract/smoke-relationships)
    [conn client]))

(deftest seek-positions-at-the-prefix-not-the-head-of-the-attribute
  (doseq [[label config] modes]
    (testing label
      (let [[conn _] (seeded-conn config)
            db       (d/db conn)
            defs     (impl/relation-datoms db :server :account)]
        (is (= 1 (count defs))
            "the exact [resource-type relation-name] prefix, and nothing else")
        (is (= [[:server :account :account]] (mapv :v defs)))
        ;; The head of this attribute sorts BEFORE the wanted prefix
        ;; ([:account :owner :user] < [:server :account :account]), so an
        ;; unpadded seek bound is ignored, the scan starts at the head, and the
        ;; prefix take-while stops immediately with zero results.
        (is (not= [:server :account]
                  (vec (take 2 (:v (first (ddb/avet-datoms db schema/relation-key-attr))))))
            "precondition: the wanted relation is not the first datom of the attribute")))))

(deftest a-relation-in-use-cannot-be-dropped-from-the-schema
  ;; The guard counts the Datomic-layout forward tuple range. Under-counting
  ;; would let a schema write orphan live relationships.
  (doseq [[label config] modes]
    (testing label
      (let [[conn client] (seeded-conn config)
            db            (d/db conn)]
        (is (= 1 (schema/count-relationships-using-relation
                  db
                  {:eacl.relation/resource-type :account
                   :eacl.relation/relation-name :owner
                   :eacl.relation/subject-type  :user}))
            "user-1 owns account-1")
        (is (= 2 (schema/count-relationships-using-relation
                  db
                  {:eacl.relation/resource-type :server
                   :eacl.relation/relation-name :account
                   :eacl.relation/subject-type  :account}))
            "account-1 holds both servers")
        (is (= 0 (schema/count-relationships-using-relation
                  db
                  {:eacl.relation/resource-type :account
                   :eacl.relation/relation-name :owner
                   :eacl.relation/subject-type  :account}))
            "a relation that exists in neither the schema nor the data")
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo #"because it is used by 1 relationships"
             (eacl/write-schema!
              client
              "definition user {}
               definition platform {
                 relation super_admin: user
               }
               definition account {
                 relation platform: platform
                 permission admin = platform->super_admin
                 permission view = admin
               }
               definition server {
                 relation account: account
                 permission view = account->view
                 permission reboot = account->admin
               }")))))))

(deftest retain-inert-presence-detects-reverse-only-ghost-test
  (doseq [[label config] modes]
    (testing label
      (let [[conn _] (seeded-conn config)
            db (d/db conn)
            user-eid (ddb/entid db [:eacl/id "user-1"])
            account-eid (ddb/entid db [:eacl/id "account-1"])
            relation-id (model/->relation-id :account :owner :user)
            relation-eid (ddb/entid db [:eacl/id relation-id])
            relation {:eacl/id relation-id
                      :eacl.relation/resource-type :account
                      :eacl.relation/relation-name :owner
                      :eacl.relation/subject-type :user}
            forward-value [:user relation-eid :account account-eid nil]]
        (d/transact
         conn
         [[:db/retract user-eid
           relationship-storage/forward-attribute forward-value]])
        (is (empty?
             (ddb/eavt-datoms
              (d/db conn) user-eid
              relationship-storage/forward-attribute forward-value)))
        (is (seq
             (ddb/eavt-datoms
              (d/db conn) account-eid
              relationship-storage/reverse-attribute
              [:account relation-eid :user user-eid nil])))
        (is (true?
             (schema/relationship-present-for-relation?
              (d/db conn) relation))
            "retain-inert diagnostics must detect either surviving tuple half")))))

(deftest a-schema-change-invalidates-cached-permission-paths
  ;; A schema write replaces the adapter's derived-schema generation. The
  ;; authorization result must not reuse permission paths compiled from the
  ;; previous generation in either attribute representation.
  (doseq [[label config] modes]
    (testing label
      (let [[_ client] (seeded-conn config)
            user-1     (eacl/spice-object :user "user-1")
            server-1   (eacl/spice-object :server "server-1")]
        (is (true? (eacl/can? client user-1 :reboot server-1))
            "user-1 reboots as the account owner, and the path is now cached")
        ;; admin no longer follows from ownership, only from platform super-admin.
        (eacl/write-schema!
         client
         "definition user {}
          definition platform {
            relation super_admin: user
          }
          definition account {
            relation platform: platform
            relation owner: user
            permission admin = platform->super_admin
            permission view = admin
          }
          definition server {
            relation account: account
            permission view = account->view
            permission reboot = account->admin
          }")
        (is (false? (eacl/can? client user-1 :reboot server-1))
            "the owner path is gone from the schema, so the cached answer must not stand")
        (is (true? (eacl/can? client (eacl/spice-object :user "super-user") :reboot server-1))
            "and the surviving path still resolves")))))
