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
    (with-mem-conn [conn schema/v7-schema]
      @(d/transact conn (concat fixtures/relations+permissions fixtures/entity-fixtures))
      @(d/transact conn (fixtures/relationship-fixtures (d/db conn)))
      ;@(d/transact conn [{:db/ident :my/id
      ;                    :db/doc "Your custom ID here, e.g. UUID in this case."
      ;                    :db/valueType :db.type/uuid
      ;                    :db/cardinality :db.cardinality/one
      ;                    :db/unique :db.unique/identity}])
      ; Q: do we want lookups to fail if entity does not exist?
      (testing "we can override EACL's object ID to Datomic ident resolution"
        (let [client (eacl.datomic.core/make-client conn
                                                    {                                                     ;:entity->object-id (fn [ent] (:))
                                                     ;:object-id->ident (fn [obj-id] [:my/id obj-id])})]
                                                     :object-id->ident (fn [obj-id] [:db/ident obj-id])})]

          ; todo: also test read/write-relationships, and count-resources.

          (testing "lookup-resources returns an empty page for a missing subject ident (SpiceDB-consistent, audit D9)"
            (is (= [] (:data (eacl/lookup-resources client
                                                    {:subject       (->user :missing-ident)
                                                     :permission    :view
                                                     :resource/type :server})))))

          (testing "basic can? works when passing :db/ident"
            (is (true? (eacl/can? client (->user :test/user1) :view (->server :test/server1))))
            (is (false? (eacl/can? client (->user :test/user2) :view (->server :test/server1))))
            (is (true? (eacl/can? client (->user :test/user2) :view (->server :test/server2)))))

          (is (= 2 (count (:data (eacl/lookup-resources client {:subject       (->user :test/user1)
                                                                :permission    :view
                                                                :resource/type :server})))))

          (is (= 2 (:count (eacl/count-resources client {:subject       (->user :test/user1)
                                                         :permission    :view
                                                         :resource/type :server}))))

          (is (= 2 (count (:data (eacl/lookup-subjects client {:resource     (->server :test/server1)
                                                               :permission   :view
                                                               :subject/type :user}))))))))))

(deftest page-token-ttl-is-validated-at-the-client-boundary-test
  (with-mem-conn [conn schema/v7-schema]
    (doseq [value [nil "300" 0 -1 (inc (quot Long/MAX_VALUE 1000))]]
      (let [data (try
                   (eacl.datomic.core/make-client
                    conn {:page-token-ttl-seconds value})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
        (is (= :eacl/invalid-config (:type data)) (pr-str value))
        (is (= :page-token-ttl-seconds (:key data)) (pr-str value))))))

(deftest shared-subproblem-cache-config-is-forwarded-and-validated-test
  (with-mem-conn [conn schema/v7-schema]
    (let [subproblem-config
          {:enabled? false
           :projection-max-weight 17
           :denotation-max-weight 19
           :max-inflight 2
           :managed-proof-max-atoms 3}
          client
          (eacl.datomic.core/make-client
           conn
           {:cache
            {:remember-answers true
             :subproblem-cache subproblem-config}})
          current-store
          (get-in client [:opts :current-cache-store])]
      (is (= subproblem-config
             (:subproblem-options current-store)))
      (is (= 2
             (get-in
              (eacl.cache/current-cache-stats current-store)
              [:max-subproblem-computations]))))
    (doseq [subproblem-config
            [{:enabled? :yes}
             {:projection-max-weight 0}
             {:denotation-max-weight 0}
             {:max-inflight 0}
             {:managed-proof-max-atoms 0}]]
      (is (= :eacl/invalid-config
             (try
               (eacl.datomic.core/make-client
                conn
                {:cache {:subproblem-cache subproblem-config}})
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))
          (pr-str subproblem-config)))))
