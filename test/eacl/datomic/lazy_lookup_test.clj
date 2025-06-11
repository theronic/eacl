(ns eacl.datomic.lazy-lookup-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            [eacl.datomic.impl-indexed :as lazy-impl]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->account ->server]]))

(comment
  (with-mem-conn [conn schema/v4-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [user1-eid (d/entid (d/db conn) [:entity/id "user-1"])
          tuple-val [user1-eid :owner :account nil]]
      (into [] (d/index-range (d/db conn) :eacl.relationship/subject+relation-name+resource-type+resource tuple-val nil)))))

(deftest lazy-lookup-tests

  (with-mem-conn [conn schema/v4-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:entity/id "super-user"])
          paths          (lazy-impl/get-permission-paths db :server :view)]
      (prn 'super-user-eid super-user-eid)
      (prn 'paths paths)


      (let [user1-eid (d/entid db [:entity/id "user-1"])]
        (is (= #{"account-1"}
               (->> (lazy-impl/lazy-direct-permission-resources db user1-eid :owner :account nil)
                    (map #(d/entity (d/db conn) %))
                    (map :entity/id)
                    (set)))))

      (let [user2-eid (d/entid db [:entity/id "user-2"])]
        (is (= #{"account-2"}
               (->> (lazy-impl/lazy-direct-permission-resources db user2-eid :owner :account nil)
                    (map #(d/entity (d/db conn) %))
                    (map :entity/id)
                    (set)))))

      (is (= #{(->account "account-1")
               (->account "account-2")}
             (set (:data (lazy-impl/lookup-resources db {:subject       (->user "super-user")
                                                         :permission    :view
                                                         :resource/type :account
                                                         :cursor        nil})))))

      (is (= #{"account1-server1"
               "account1-server2"
               "account2-server1"}
             (->> (for [[steps direct-relation] paths
                        :when (seq steps)]
                    (->> (lazy-impl/lazy-arrow-permission-resources db super-user-eid steps direct-relation 'not-used nil)
                         (map #(d/entity (d/db conn) %))
                         (map :entity/id)))
                  (apply concat)
                  (set))))

      (is (= #{"account1-server1"
               "account1-server2"
               "account2-server1"}
             (->>
               (for [[steps direct-relation] paths
                     :when (seq steps)]
                 (->> (lazy-impl/lazy-arrow-permission-resources db super-user-eid steps direct-relation 'not-used nil)
                      (map #(d/entity (d/db conn) %))
                      (map :entity/id)))
               (apply concat)
               (set))))

      (is (= #{(->server "account1-server1")
               (->server "account1-server2")
               (->server "account2-server1")}
             (set (:data (lazy-impl/lookup-resources db {:subject       (->user "super-user")
                                                         :permission    :view
                                                         :resource/type :server
                                                         :cursor        nil})))))

      (is (= #{(->server "account1-server1")
               (->server "account1-server2")}
             (set (:data (lazy-impl/lookup-resources db {:subject       (->user "user-1")
                                                         :permission    :view
                                                         :resource/type :server
                                                         :cursor        nil})))))

      (is (= #{(->server "account2-server1")}
             (set (:data (lazy-impl/lookup-resources db {:subject       (->user "user-2")
                                                         :permission    :view
                                                         :resource/type :server
                                                         :cursor        nil}))))))))
