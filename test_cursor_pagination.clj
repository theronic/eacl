(ns test-cursor-pagination
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server ->account]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as impl :refer [Relation Relationship Permission]]
            [eacl.datomic.impl.indexed :as impl.indexed :refer [lookup-resources]]))

(deftest cursor-pagination-bug-reproduction
  "Reproduces the cursor pagination bug where intermediate relationships get filtered incorrectly"
  (with-mem-conn [conn schema/v6-schema]
    ;; Set up schema and entities to create the bug scenario
    @(d/transact conn
                 [;; Schema
                  (Relation :account :owner :user)
                  (Relation :server :account :account)
                  (Permission :server :view {:arrow :account :permission :admin})
                  (Permission :account :admin {:relation :owner})

       ;; Entities - order matters for entity IDs
       ;; Create servers first (lower eids)
                  {:db/id "server-1" :eacl/id "server-1"}
                  {:db/id "server-2" :eacl/id "server-2"}
                  {:db/id "server-3" :eacl/id "server-3"}
                  {:db/id "server-4" :eacl/id "server-4"}

       ;; Create accounts after servers (higher eids) 
                  {:db/id "account-low" :eacl/id "account-low"}
                  {:db/id "account-high" :eacl/id "account-high"}

       ;; Create user
                  {:db/id "user-1" :eacl/id "user-1"}

       ;; Relationships
                  (Relationship (->user "user-1") :owner (->account "account-low"))
                  (Relationship (->user "user-1") :owner (->account "account-high"))
                  (Relationship (->account "account-low") :account (->server "server-1"))
                  (Relationship (->account "account-low") :account (->server "server-2"))
                  (Relationship (->account "account-high") :account (->server "server-3"))
                  (Relationship (->account "account-high") :account (->server "server-4"))])

    (let [db (d/db conn)
          user1-eid (d/entid db [:eacl/id "user-1"])
          server2-eid (d/entid db [:eacl/id "server-2"])
          account-low-eid (d/entid db [:eacl/id "account-low"])
          account-high-eid (d/entid db [:eacl/id "account-high"])]

      (println "Entity IDs:")
      (println "  server-1:" (d/entid db [:eacl/id "server-1"]))
      (println "  server-2:" (d/entid db [:eacl/id "server-2"]))
      (println "  server-3:" (d/entid db [:eacl/id "server-3"]))
      (println "  server-4:" (d/entid db [:eacl/id "server-4"]))
      (println "  account-low:" account-low-eid)
      (println "  account-high:" account-high-eid)

      (testing "Page 1 with limit 2 should return first 2 servers"
        (let [page1 (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :server
                                          :limit 2
                                          :cursor nil})]
          (println "Page 1 results:" (count (:data page1)))
          (println "Page 1 cursor:" (:cursor page1))
          (is (= 2 (count (:data page1))))))

      (testing "Page 2 with cursor from page 1 should return remaining servers"
        (let [page1 (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :server
                                          :limit 2
                                          :cursor nil})
              page2 (lookup-resources db {:subject (->user user1-eid)
                                          :permission :view
                                          :resource/type :server
                                          :limit 2
                                          :cursor (:cursor page1)})]
          (println "Page 2 results:" (count (:data page2)))
          (println "Page 2 cursor:" (:cursor page2))
          ;; This should NOT be empty - this is the bug we're fixing
          (is (= 2 (count (:data page2))) "Page 2 should have 2 results, not be empty"))))))

;; Run the test
(cursor-pagination-bug-reproduction)