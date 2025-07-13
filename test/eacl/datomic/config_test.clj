(ns eacl.datomic.config-test
  "As of 2025-06-28, EACL supports configurable ID attributes."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [eacl.core :as eacl]
            [datomic.api :as d]
            [eacl.datomic.core]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server]]
            [eacl.datomic.datomic-helpers :as helpers :refer [with-mem-conn]]))

(deftest eacl-config-tests
  (testing ""
    (with-mem-conn [conn schema/v5-schema]
      @(d/transact conn fixtures/base-fixtures)
      ;@(d/transact conn [{:db/ident :my/id
      ;                    :db/doc "Your custom ID here, e.g. UUID in this case."
      ;                    :db/valueType :db.type/uuid
      ;                    :db/cardinality :db.cardinality/one
      ;                    :db/unique :db.unique/identity}])
      ; Q: do we want lookups to fail if entity does not exist?
      (testing "we can override EACL's object ID to Datomic ident resolution"
        (let [client (eacl.datomic.core/make-client conn
                                                    {
                                                     ;:entity->object-id (fn [ent] (:))
                                                     ;:object-id->ident (fn [obj-id] [:my/id obj-id])})]
                                                     :object-id->ident (fn [obj-id] [:db/ident obj-id])})]

          ; todo: also test read/write-relationships, and count-resources.

          (testing "lookup-resources throws for missing subject ident with some detail"
            (is (thrown? Throwable (eacl/lookup-resources client
                                                          {:subject       (->user :missing-ident)
                                                           :permission    :view
                                                           :resource/type :server
                                                           :limit         1000
                                                           :cursor        nil}))))

          (testing "basic can? works when passing :db/ident"
            (is (true? (eacl/can? client (->user :test/user1) :view (->server :test/server1))))
            (is (false? (eacl/can? client (->user :test/user2) :view (->server :test/server1))))
            (is (true? (eacl/can? client (->user :test/user2) :view (->server :test/server2)))))

          (is (= 2 (count (:data (eacl/lookup-resources client {:subject       (->user :test/user1)
                                                                :permission    :view
                                                                :resource/type :server})))))

          (is (= 2 (eacl/count-resources client {:subject       (->user :test/user1)
                                                 :permission    :view
                                                 :resource/type :server})))

          (is (= 2 (count (:data (eacl/lookup-subjects client {:resource     (->server :test/server1)
                                                               :permission   :view
                                                               :subject/type :user}))))))))))

