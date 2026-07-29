(ns eacl.datomic.object-deletion-test
  "Regressions for dangling relationship halves.

  A v7 relationship is two datoms on two different entities, each naming its
  peer inside a tuple VALUE. Datomic's :db.fn/retractEntity follows
  :db.type/ref ATTRIBUTES, not ref-typed components of a heterogeneous tuple,
  so retracting a permissioned entity the ordinary Datomic way leaves the
  peer's half behind — where it keeps answering queries, and where
  write-relationships! can no longer reach it (resolving either endpoint now
  throws :eacl/unknown-object).

  eacl/delete-object! (impl/tx-delete-object) is the supported deletion path;
  impl/orphaned-relationship-halves + impl/tx-retract-orphaned-relationships
  repair databases that already contain orphans."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.integrity :as integrity]
            [eacl.datomic.schema :as schema]))

(def ^:private test-schema
  "definition user {}
   definition account { relation owner: user
                        permission admin = owner }")

(defn- seed!
  "user u owns account a."
  [conn]
  (schema/write-schema! conn test-schema)
  @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
  @(d/transact conn (impl/tx-relationship (d/db conn)
                      (Relationship (spice-object :user [:eacl/id "u"])
                                    :owner
                                    (spice-object :account [:eacl/id "a"]))))
  (let [db (d/db conn)]
    {:u (d/entid db [:eacl/id "u"])
     :a (d/entid db [:eacl/id "a"])}))

(defn- forward-count [db eid]
  (count (seq (d/datoms db :eavt eid :eacl.v7.relationship/subject-type+relation+resource-type+resource))))

(defn- reverse-count [db eid]
  (count (seq (d/datoms db :eavt eid :eacl.v7.relationship/resource-type+relation+subject-type+subject))))

(deftest retract-entity-leaves-orphans-that-delete-object-prevents-test
  (testing "deleting the RESOURCE: the subject's forward half used to keep granting"
    (with-mem-conn [conn schema/v7-schema]
      (let [{:keys [u a]} (seed! conn)]
        (is (true? (idx/can? (d/db conn) (spice-object :user u) :admin (spice-object :account a))))

        ;; the supported path: drop the relationships, then the entity
        @(d/transact conn (impl/tx-delete-object (d/db conn) a))
        @(d/transact conn [[:db.fn/retractEntity a]])

        (let [db (d/db conn)]
          (is (zero? (forward-count db u)) "the subject's half is gone too")
          (is (false? (idx/can? db (spice-object :user u) :admin (spice-object :account a))))
          (is (empty? (:data (idx/lookup-resources db {:subject       (spice-object :user u)
                                                       :permission    :admin
                                                       :resource/type :account
                                                       :first         10}))))
          (is (zero? (:count (idx/count-resources db {:subject       (spice-object :user u)
                                                      :permission    :admin
                                                      :resource/type :account}))))))))

  (testing "deleting the SUBJECT: the resource's reverse half used to keep listing it"
    (with-mem-conn [conn schema/v7-schema]
      (let [{:keys [u a]} (seed! conn)]
        @(d/transact conn (impl/tx-delete-object (d/db conn) u))
        @(d/transact conn [[:db.fn/retractEntity u]])

        (let [db (d/db conn)]
          (is (zero? (reverse-count db a)) "the resource's half is gone too")
          (is (empty? (:data (idx/lookup-subjects db {:resource     (spice-object :account a)
                                                      :permission   :admin
                                                      :subject/type :user
                                                      :first        10}))))
          (is (zero? (:count (idx/count-subjects db {:resource     (spice-object :account a)
                                                     :permission   :admin
                                                     :subject/type :user})))))))))

(deftest delete-object-through-the-client-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u a]} (seed! conn)
          acl (core/make-client conn {})]
      (is (true? (eacl/can? acl (spice-object :user "u") :admin (spice-object :account "a"))))

      (let [result (eacl/delete-object! acl (spice-object :account "a"))]
        (is (= 2 (:retracted-datoms result)) "both halves of the one relationship")
        (is (some? (:zed/token result))))

      (is (false? (eacl/can? acl (spice-object :user "u") :admin (spice-object :account "a"))))
      (is (zero? (forward-count (d/db conn) u)))
      (is (zero? (reverse-count (d/db conn) a)))

      (testing "it is idempotent"
        (is (= 0 (:retracted-datoms (eacl/delete-object! acl (spice-object :account "a"))))))

      (testing "the entities themselves are untouched — retracting them is the caller's business"
        (is (seq (d/datoms (d/db conn) :eavt a)))))))

(deftest delete-object-accepts-a-raw-eid-after-the-entity-is-gone-test
  ;; The cleanup path for an entity already retracted the bare Datomic way:
  ;; its :eacl/id no longer resolves, but the eid still identifies the orphans.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u a]} (seed! conn)
          acl (core/make-client conn {})]
      @(d/transact conn [[:db.fn/retractEntity u]])
      (is (= 1 (reverse-count (d/db conn) a)) "orphan present before repair")

      (let [result (eacl/delete-object! acl (spice-object :user u))]
        (is (pos? (:retracted-datoms result))))

      (is (zero? (reverse-count (d/db conn) a)))
      (is (empty? (:data (idx/lookup-subjects (d/db conn)
                                              {:resource     (spice-object :account a)
                                               :permission   :admin
                                               :subject/type :user
                                               :first        10})))))))

(deftest orphan-detection-and-repair-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u a]} (seed! conn)]
      (is (empty? (impl/orphaned-relationship-halves (d/db conn)))
          "a well-formed database has no orphans")

      @(d/transact conn [[:db.fn/retractEntity u]])

      (let [orphans (vec (impl/orphaned-relationship-halves (d/db conn)))]
        (is (= 1 (count orphans)))
        (is (= :reverse (:half (first orphans))))
        (is (= a (:e (first orphans))))
        (is (= u (:subject-eid (first orphans)))))

      @(d/transact conn (impl/tx-retract-orphaned-relationships (d/db conn)))

      (is (empty? (impl/orphaned-relationship-halves (d/db conn))))
      (is (zero? (reverse-count (d/db conn) a)))
      (is (empty? (:data (idx/lookup-subjects (d/db conn)
                                              {:resource     (spice-object :account a)
                                               :permission   :admin
                                               :subject/type :user
                                               :first        10})))))))

(deftest public-integrity-audit-is-explicit-and-bounded-test
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u]} (seed! conn)
          acl (core/make-client conn {})]
      (is (= :current (:status (integrity/client-schema-status acl))))

      @(d/transact conn [[:db.fn/retractEntity u]])

      (is (= {:valid? false
              :dangling-count 1
              :by-half {:forward 0 :reverse 1}
              :sample []}
             (integrity/dangling-relationship-report (d/db conn) {:sample-size 0})))

      (let [batches (integrity/repair-tx-batches (d/db conn) {:batch-size 1})]
        (is (= 1 (count batches)))
        (doseq [batch batches]
          @(d/transact conn batch)))

      (is (= {:valid? true
              :dangling-count 0
              :by-half {:forward 0 :reverse 0}
              :sample []}
             (integrity/dangling-relationship-report (d/db conn)))))))

(deftest half-written-relationships-are-repairable-test
  ;; relationship-exists? consulted only the forward index, so a surviving
  ;; reverse half read as "already there" to :touch and "nothing to do" to
  ;; :delete — permanently unrepairable through the write API.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u a]} (seed! conn)
          acl (core/make-client conn {})
          rel (Relationship (spice-object :user "u") :owner (spice-object :account "a"))]

      (testing ":delete removes both halves even when only one is present"
        ;; hand-retract the forward half only, simulating a partial write
        (let [forward (first (d/datoms (d/db conn) :eavt u
                                       :eacl.v7.relationship/subject-type+relation+resource-type+resource))]
          @(d/transact conn [[:db/retract u
                              :eacl.v7.relationship/subject-type+relation+resource-type+resource
                              (vec (:v forward))]]))
        (is (= 1 (reverse-count (d/db conn) a)) "reverse half survives")

        (eacl/delete-relationship! acl rel)
        (is (zero? (reverse-count (d/db conn) a)) "…and :delete can now clear it")))

    (let [{:keys [u a]} (seed! conn)]
      (testing ":touch re-asserts a missing half instead of reporting it present"
        (let [reverse-datom (first (d/datoms (d/db conn) :eavt a
                                             :eacl.v7.relationship/resource-type+relation+subject-type+subject))]
          @(d/transact conn [[:db/retract a
                              :eacl.v7.relationship/resource-type+relation+subject-type+subject
                              (vec (:v reverse-datom))]]))
        (is (zero? (reverse-count (d/db conn) a)))

        (eacl/write-relationship! (core/make-client conn {})
                                  {:operation :touch
                                   :subject   (spice-object :user "u")
                                   :relation  :owner
                                   :resource  (spice-object :account "a")})
        (is (= 1 (reverse-count (d/db conn) a)))
        (is (= 1 (forward-count (d/db conn) u)))))))

(deftest bare-retract-can-leave-a-detectable-ghost-test
  ;; With eid-as-external-id coercion, d/entid passes any long through
  ;; unchanged. EACL deliberately does not add entity-existence probes to every
  ;; can?; the consumer deletion contract and explicit integrity auditor own
  ;; this failure mode.
  (with-mem-conn [conn schema/v7-schema]
    (let [{:keys [u a]} (seed! conn)
          acl (core/make-client conn {:object-id->ident identity
                                      :entid->object-id (fn [_db eid] eid)})]
      (is (true? (eacl/can? acl (spice-object :user u) :admin (spice-object :account a))))

      @(d/transact conn [[:db.fn/retractEntity a]])

      (is (= 1 (forward-count (d/db conn) u)) "the orphan is still there…")
      (is (true? (eacl/can? acl (spice-object :user u) :admin (spice-object :account a)))
          "…and raw eid identity can still address the surviving tuple")
      (is (= 1 (:dangling-count
                (integrity/dangling-relationship-report (d/db conn)))))

      (doseq [batch (integrity/repair-tx-batches (d/db conn))]
        @(d/transact conn batch))
      (is (false? (eacl/can? acl (spice-object :user u) :admin (spice-object :account a)))
          "repair removes the ghost grant"))))
