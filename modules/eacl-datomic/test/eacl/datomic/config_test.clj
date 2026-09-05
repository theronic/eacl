(ns eacl.datomic.config-test
  "As of 2025-06-28, EACL supports configurable ID attributes."
  (:require [clojure.test :as t :refer [deftest testing is]]
            [clojure.walk]
            [eacl.core :as eacl]
            [datomic.api :as d]
            [eacl.datomic.core :as core]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server]]
            [eacl.datomic.datomic-helpers :as helpers :refer [with-mem-conn]]))

(defn- runtime-options
  [client]
  (into {} (:runtime client)))

(def ^:private test-security-key
  "0123456789abcdef0123456789abcdef")

(deftest portable-cache-api-round-trip-test
  (with-mem-conn [conn schema/v8-schema]
    (let [client (core/make-client conn {})
          bounds {:max-entries 64}
          before (core/cache-content-revision client)
          snapshot (core/export-cache-snapshot client bounds)
          restored (core/restore-cache-snapshot! client snapshot bounds)]
      (is (= :eacl.cache/basis-snapshot-v2 (:format snapshot)))
      (is (zero? (:entry-count snapshot)))
      (is (true? (:restored? restored)))
      (is (> (core/cache-content-revision client) before)))))

(deftest eacl-config-tests
  (testing ""
    (with-mem-conn [conn schema/v8-schema]
      (fixtures/install-expression-schema! conn)
      @(d/transact conn fixtures/entity-fixtures)
      @(d/transact conn (fixtures/relationship-fixtures (d/db conn)))
      ;@(d/transact conn [{:db/ident :my/id
      ;                    :db/doc "Your custom ID here, e.g. UUID in this case."
      ;                    :db/valueType :db.type/uuid
      ;                    :db/cardinality :db.cardinality/one
      ;                    :db/unique :db.unique/identity}])
      ; Q: do we want lookups to fail if entity does not exist?
      (testing "we can override EACL's object ID to Datomic ident resolution"
        (let [client
              (eacl.datomic.core/make-client
               conn
               {:object-id->lookup-ref
                (fn [obj-id] [:db/ident obj-id])})]

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

(deftest cursor-ttl-is-validated-at-the-client-boundary-test
  (with-mem-conn [conn schema/v8-schema]
    (doseq [value [nil "300" 0 -1 (inc (quot Long/MAX_VALUE 1000))]]
      (let [data (try
                   (core/make-client conn {:cursor-ttl-seconds value})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
        (is (= :eacl/invalid-config (:type data)) (pr-str value))
        (is (= :cursor-ttl-seconds (:key data)) (pr-str value))))))

(deftest cursor-expiry-is-optional-and-independent-from-authorization-cache-test
  (with-mem-conn [conn schema/v8-schema]
    (let [client (core/make-client conn {:security-key test-security-key})
          opts (runtime-options client)]
      (is (nil? (:cursor-ttl-seconds opts))
          "an omitted cursor TTL remains unconfigured"))

    (let [client (core/make-client
                  conn
                  {:security-key test-security-key
                   :cursor-ttl-seconds 60})
          opts (runtime-options client)]
      (is (= 60 (:cursor-ttl-seconds opts))
          "an explicit positive cursor TTL is retained by the shared runtime"))

    (let [client (core/make-client
                  conn
                  {:cursor-ttl-seconds 1
                   :cache {:max-entries 5000}})]
      (is (= 1 (:cursor-ttl-seconds (runtime-options client))))
      (is (some? (:basis-cache-store (runtime-options client)))
          "cursor policy does not alter an independently bounded answer cache"))

    (testing "wall-clock cache expiry is rejected"
      (let [data (try
                   (core/make-client conn {:cache {:ttl-ms 5000}})
                   nil
                   (catch clojure.lang.ExceptionInfo error
                     (ex-data error)))]
        (is (= :eacl/invalid-config (:type data)))
        (is (= [:ttl-ms] (:unknown-keys data)))))))

(deftest uniform-construction-option-family-test
  (with-mem-conn [conn schema/v8-schema]
    (let [lookup-ref (fn [object-id] [:eacl/id object-id])
          client (core/make-client
                  conn
                  {:object-id->lookup-ref lookup-ref
                   :security-keyring {:shared test-security-key}
                   :security-kid :shared
                   :cursor-ttl-seconds 123})
          opts (runtime-options client)]
      (is (identical? lookup-ref (:object-id->lookup-ref opts)))
      (is (= :shared (:active-kid (eacl/security-keyring-status (get-in opts [:format-options :keyring-controller])))))
      (is (= 123 (:cursor-ttl-seconds opts))))

    (testing "removed backend-specific cursor aliases are unknown"
      (let [data (try
                   (core/make-client
                    conn
                    {:security-key "canonical"
                     :page-token-key "legacy00000000000000000000000000"})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
        (is (= :eacl/invalid-config (:type data)))
        (is (= [:page-token-key] (:unknown-keys data)))))

    (testing "unknown-option ex-data matches the shared orchestrator"
      (let [data (try
                   (core/make-client conn {:not-an-option true})
                   nil
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
        (is (= :eacl/invalid-config (:type data)))
        (is (= [:not-an-option] (:unknown-keys data)))
        (is (contains? (:known-keys data) :security-key))
        (is (contains? (:known-keys data) :cursor-ttl-seconds))))))

(deftest flat-denotation-cache-config-is-forwarded-and-validated-test
  (with-mem-conn [conn schema/v8-schema]
    (let [client
          (eacl.datomic.core/make-client
           conn
           {:cache {:max-entries 11
                    :denotation-max-entries 19}})
          basis-store
          (:basis-cache-store (runtime-options client))]
      (is (= 19 (get-in basis-store
                        [:subproblem-options :denotation-max-entries])))
      (is (= 11 (get-in basis-store
                        [:subproblem-options :answer-max-entries]))))
    (doseq [cache-config
            [{:subproblem-cache {}}
             {:enabled? false}
             {:projection-max-entries 17}
             {:denotation-max-entries 0}
             {:projection-max-weight 17}
             {:denotation-max-weight 19}
             {:answer-max-entries 11}
             {:max-inflight 1}
             {:managed-proof-max-atoms 3}]]
      (is (= :eacl/invalid-config
             (try
               (eacl.datomic.core/make-client
                conn
                {:cache cache-config})
               nil
               (catch clojure.lang.ExceptionInfo error
                 (:type (ex-data error)))))
          (pr-str cache-config)))))

(deftest execution-config-is-strict-and-cache-attempt-is-removed-test
  (with-mem-conn [conn schema/v8-schema]
    (doseq [options [{:execution-timeout-ms 0}
                     {:execution-timeout-ms "30"}]]
      (let [data
            (try
              (core/make-client conn options)
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))]
        (is (= :eacl/invalid-config (:type data)) (pr-str options))
        (is (= :execution-timeout-ms (:key data)) (pr-str options))))
    (doseq [value [{:evaluation-reserve-ms 0} {:unknown-limit 1}]]
      (let [data
            (try
              (core/make-client conn {:cache-attempt value})
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error)))]
        (is (= :eacl/invalid-config (:type data)) (pr-str value))
        (is (= [:cache-attempt] (:unknown-keys data)) (pr-str value))))
    (let [client
          (core/make-client
           conn
           {:execution-timeout-ms 1234})]
      (is (= 1234 (:execution-timeout-ms (runtime-options client)))))))

(deftest expand-permission-tree-uses-the-client-id-codec-test
  ;; The adapter's :object-id->internal must resolve external ids through the
  ;; client's :object-id->lookup-ref, not a hardwired [:eacl/id id]. Expansion
  ;; is the one public operation that hands the adapter an external id, so a
  ;; codec whose external ids differ from :eacl/id used to yield an absent
  ;; root (no subjects anywhere) instead of the tree.
  (with-mem-conn [conn schema/v8-schema]
    (fixtures/install-expression-schema! conn)
    @(d/transact conn fixtures/entity-fixtures)
    @(d/transact conn (fixtures/relationship-fixtures (d/db conn)))
    (let [external->eacl-id {"S1" "account1-server1" "A1" "account-1" "U1" "user-1"
                             "G1" "group-1" "SU" "super-user" "P" "platform"
                             "N1" "nic-1" "L1" "lease-1" "NET1" "network-1" "V1" "vpc-1"}
          eacl-id->external (into {} (map (juxt val key)) external->eacl-id)
          codec-client (core/make-client
                        conn
                        {:object-id->lookup-ref (fn [id] [:eacl/id (get external->eacl-id id id)])
                         :entid->object-id (fn [db eid]
                                             (let [eacl-id (:eacl/id (d/entity db eid))]
                                               (get eacl-id->external eacl-id eacl-id)))})
          default-client (core/make-client conn {})
          rename-ids (fn rename [tree]
                       (clojure.walk/postwalk
                        (fn [node]
                          (if (and (map? node) (contains? node :type) (contains? node :id))
                            (update node :id #(get eacl-id->external % %))
                            node))
                        tree))
          codec-tree (:tree-root (eacl/expand-permission-tree
                                  codec-client {:resource (->server "S1") :permission :view}))
          default-tree (:tree-root (eacl/expand-permission-tree
                                    default-client {:resource (->server "account1-server1") :permission :view}))
          leaf-subjects (fn [tree]
                          (->> (tree-seq map? #(get-in % [:intermediate :children]) tree)
                               (mapcat #(get-in % [:leaf :subjects]))
                               (map :id)
                               set))]
      (testing "the codec client sees the same topology and subjects as the default client"
        (is (seq (leaf-subjects default-tree)))
        (is (= (leaf-subjects (rename-ids default-tree)) (leaf-subjects codec-tree)))
        (is (= (rename-ids default-tree) codec-tree)))
      (testing "the other operations agree with expansion under the same codec"
        (is (true? (eacl/can? codec-client (->user "U1") :view (->server "S1"))))
        (is (contains? (leaf-subjects codec-tree) "U1"))))))

(deftest service-admission-option-test
  (with-mem-conn [conn schema/v8-schema]
    (fixtures/install-expression-schema! conn)
    @(d/transact conn fixtures/entity-fixtures)
    @(d/transact conn (fixtures/relationship-fixtures (d/db conn)))
    (testing "the option is validated at construction"
      (doseq [bad [{:max-concurrent 0} {:bogus 1} :on]]
        (is (= :eacl/invalid-config
               (try (core/make-client conn {:service-admission bad}) nil
                    (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))
            (pr-str bad))))
    (testing "a configured bulkhead is installed and enumerations run through it"
      (let [client (core/make-client conn {:service-admission {:max-concurrent 8
                                                               :max-replays 4}})
            admission (:service-admission (runtime-options client))]
        (is (= 8 (:max-concurrent @admission)))
        (is (= 4 (:max-replays @admission)))
        (is (= 2 (count (:data (eacl/lookup-resources client {:subject (->user "user-1")
                                                              :permission :view
                                                              :resource/type :server})))))
        (is (true? (eacl/can? client (->user "user-1") :view (->server "account1-server1"))))
        (is (zero? (:active @admission)) "slots are released when the work returns")))
    (testing "the default client installs no bulkhead"
      (is (nil? (:service-admission
                 (runtime-options (core/make-client conn {}))))))))
