(ns eacl.migrations.v6-to-v7-test
  "End-to-end tests for the EACL v6 -> v7 storage migration.

  Each test constructs a genuine v6-model database — relationship entities
  written via the :eacl.relationship/* attributes exactly as v6 EACL stored
  them — migrates it with eacl.migrations.v6-to-v7, and verifies the result
  through the public v7 API (can?, lookup-resources, read-relationships)."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.core :as eacl.datomic]
            [eacl.datomic.impl.base :as base]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.migrations.v6-to-v7 :as mig]
            [eacl.relationships.storage :as relationship-storage]))

(def ->user (partial spice-object :user))
(def ->account (partial spice-object :account))
(def ->product (partial spice-object :product))

(def v7-only-attr-idents
  "Attributes in v7-schema that did not exist in a v6 database."
  #{:eacl/schema-version
    :eacl/storage-version
    relationship-storage/forward-attribute
    relationship-storage/reverse-attribute})

(def v6-schema
  "A faithful v6 database schema: the shared attribute definitions (identical
  in v6 & v7) plus the v6 relationship-entity attributes."
  (vec (concat (remove #(contains? v7-only-attr-idents (:db/ident %)) schema/v7-schema)
               mig/v6-relationship-schema)))

(def schema-str
  "definition user {}

   definition account {
     relation owner: user
     permission admin = owner
     permission update = admin
   }

   definition product {
     relation account: account
     permission edit = account->admin
   }")

(def relations+permissions
  "Programmatic equivalent of schema-str, as v6 users transacted it."
  [(base/Relation :account :owner :user)
   (base/Permission :account :admin {:relation :owner})
   (base/Permission :account :update {:permission :admin})
   (base/Relation :product :account :account)
   (base/Permission :product :edit {:arrow :account :permission :admin})])

(def entity-ids ["user-1" "user-2" "account-1" "product-1" "product-2"])

(defn v6-relationship-entity
  "A v6-era relationship entity map, as v6 write-relationships! stored them."
  [db subject-type subject-id relation-name resource-type resource-id]
  {:eacl.relationship/subject-type  subject-type
   :eacl.relationship/subject       (d/entid db [:eacl/id subject-id])
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/resource-type resource-type
   :eacl.relationship/resource      (d/entid db [:eacl/id resource-id])})

(defn populate-v6!
  "Standard v6 dataset: user-1 owns account-1; account-1 is the :account for
  product-1. user-2 & product-2 exist but are unrelated."
  [conn]
  @(d/transact conn relations+permissions)
  @(d/transact conn (mapv (fn [id] {:eacl/id id}) entity-ids))
  (let [db (d/db conn)]
    @(d/transact conn
       [(v6-relationship-entity db :user "user-1" :owner :account "account-1")
        (v6-relationship-entity db :account "account-1" :account :product "product-1")])))

(defn assert-expected-permissions!
  "The permission matrix implied by the standard dataset, checked through the
  public v7 API. Exercises direct, self and arrow permissions."
  [acl]
  (is (true?  (eacl/can? acl (->user "user-1") :update (->account "account-1"))))
  (is (false? (eacl/can? acl (->user "user-2") :update (->account "account-1"))))
  (is (true?  (eacl/can? acl (->user "user-1") :edit (->product "product-1"))))
  (is (false? (eacl/can? acl (->user "user-1") :edit (->product "product-2"))))
  (is (false? (eacl/can? acl (->user "user-2") :edit (->product "product-1")))))

(deftest migrate!-end-to-end-test
  (with-mem-conn [conn v6-schema]
    (populate-v6! conn)
    (is (= :v6 (mig/detect-storage-version (d/db conn))))

    (testing "make-client refuses to start against unmigrated v6 data"
      (let [ex (try (eacl.datomic/make-client conn {}) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex))
        (is (= :eacl/storage-version (:type (ex-data ex))))
        (is (= :v6 (:detected (ex-data ex))))))

    (testing "migrate! with a re-asserted schema string"
      (let [report (mig/migrate! conn {:schema schema-str})]
        (is (= 7 (:storage-version report)))
        (is (= 2 (:relationships-backfilled report)))
        (is (= 0 (:normalized-schema-entity-ids report)))
        (is (= 2 (:v6-entities-retracted report)))
        (is (true? (get-in report [:verify :complete?])))
        (testing "re-asserting an unchanged schema is a zero-delta no-op"
          (is (empty? (get-in report [:schema-deltas :relations :additions])))
          (is (empty? (get-in report [:schema-deltas :relations :retractions])))
          (is (empty? (get-in report [:schema-deltas :permissions :additions])))
          (is (empty? (get-in report [:schema-deltas :permissions :retractions]))))))

    (testing "migration converges to tuple-only storage and stamps it"
      (is (= :v7 (mig/detect-storage-version (d/db conn))))
      (is (= 7 (mig/stamped-storage-version (d/db conn)))))

    (testing "make-client now starts and tuple reads are correct"
      (let [acl (eacl.datomic/make-client conn {})]
        (assert-expected-permissions! acl)
        (is (= ["product-1"]
               (mapv :id (:data (eacl/lookup-resources acl {:subject       (->user "user-1")
                                                            :permission    :edit
                                                            :resource/type :product
                                                            :first         10})))))
        (is (= 1 (count (:data (eacl/read-relationships acl {:resource/type :account})))))))))

(deftest migrate!-idempotency-test
  (with-mem-conn [conn v6-schema]
    (populate-v6! conn)
    (mig/migrate! conn {:schema schema-str})
    (let [count-forward-tuples
          (fn []
            (count
             (seq
              (d/datoms
               (d/db conn)
               :aevt
               relationship-storage/forward-attribute))))
          tuples-after-first   (count-forward-tuples)
          second-report        (mig/migrate! conn {:schema schema-str})]
      (is (= 2 tuples-after-first))
      (is (= tuples-after-first (count-forward-tuples)) "re-running migrate! adds no datoms")
      (is (true? (get-in second-report [:verify :complete?]))))))

(deftest migrate!-removed-rollback-option-is-rejected-test
  (with-mem-conn [conn v6-schema]
    (populate-v6! conn)
    (let [error
          (try
            (mig/migrate!
             conn
             {:schema schema-str :retract-v6-entities? true})
            nil
            (catch clojure.lang.ExceptionInfo exception
              (ex-data exception)))]
      (is (= :eacl/invalid-config (:type error)))
      (is (= [:retract-v6-entities?] (:unknown-keys error))))))

(deftest make-client-auto-migrate-test
  (testing "{:auto-migrate-v6 {:schema ...}} migrates during construction"
    (with-mem-conn [conn v6-schema]
      (populate-v6! conn)
      (let [acl (eacl.datomic/make-client conn {:auto-migrate-v6 {:schema schema-str}})]
        (is (= 7 (mig/stamped-storage-version (d/db conn))))
        (assert-expected-permissions! acl))))
  (testing "{:auto-migrate-v6 true} uses default options: stored schema entities carry over"
    (with-mem-conn [conn v6-schema]
      (populate-v6! conn)
      (let [acl (eacl.datomic/make-client conn {:auto-migrate-v6 true})]
        (assert-expected-permissions! acl)))))

(deftest ancient-schema-entities-test
  (with-mem-conn [conn v6-schema]
    ;; Ancient installs transacted Relation/Permission maps before the
    ;; :eacl/id convention existed. write-schema! can only manage (and clean
    ;; up) schema entities it can address by :eacl/id, so migrate! normalizes
    ;; them first.
    @(d/transact conn (mapv #(dissoc % :eacl/id) relations+permissions))
    @(d/transact conn (mapv (fn [id] {:eacl/id id}) entity-ids))
    (let [db (d/db conn)]
      @(d/transact conn
         [(v6-relationship-entity db :user "user-1" :owner :account "account-1")
          (v6-relationship-entity db :account "account-1" :account :product "product-1")]))
    (let [report (mig/migrate! conn {:schema schema-str})]
      (is (= 5 (:normalized-schema-entity-ids report)))
      (testing "normalization lets write-schema! adopt the entities instead of duplicating them"
        (is (empty? (get-in report [:schema-deltas :relations :additions])))
        (is (empty? (get-in report [:schema-deltas :relations :retractions])))
        (is (= 2 (count (seq (d/datoms (d/db conn) :aevt :eacl.relation/relation-name))))))
      (assert-expected-permissions! (eacl.datomic/make-client conn {})))))

(deftest missing-relation-aborts-test
  (with-mem-conn [conn v6-schema]
    (populate-v6! conn)
    ;; a v6 relationship whose relation triple has no Relation schema entity:
    (let [db (d/db conn)]
      @(d/transact conn [(v6-relationship-entity db :user "user-2" :ghost :product "product-2")]))
    (let [ex (try (mig/migrate! conn {:schema schema-str}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :eacl.migration/missing-relation (:type (ex-data ex))))
      (is (= :ghost (:relation-name (ex-data ex)))))
    (testing "aborted migration is additive: no stamp, startup still refuses"
      (is (nil? (mig/stamped-storage-version (d/db conn))))
      (is (thrown? clojure.lang.ExceptionInfo (eacl.datomic/make-client conn {}))))))

(deftest fresh-v7-database-test
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-str)
    @(d/transact conn (mapv (fn [id] {:eacl/id id}) entity-ids))
    (testing "fresh v7 installs need no stamp and no opt-in"
      (let [acl (eacl.datomic/make-client conn {})]
        (eacl/create-relationships! acl
          [(eacl/->Relationship (->user "user-1") :owner (->account "account-1"))
           (eacl/->Relationship (->account "account-1") :account (->product "product-1"))])
        (assert-expected-permissions! acl)
        (is (= :v7 (mig/detect-storage-version (d/db conn))))))
    (testing "migrate! on an already-v7 database is a harmless no-op"
      (let [report (mig/migrate! conn {})]
        (is (= 0 (:relationships-backfilled report)))
        (is (true? (get-in report [:verify :complete?])))))))

(deftest empty-v6-database-test
  (with-mem-conn [conn v6-schema]
    (is (= :none (mig/detect-storage-version (d/db conn))))
    (is (some? (eacl.datomic/make-client conn {}))
        "no relationship data at all — nothing to migrate, startup proceeds")))

(deftest migrate!-rejects-unknown-options-test
  (with-mem-conn [conn v6-schema]
    (let [ex (try (mig/migrate! conn {:shcema schema-str}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :eacl/invalid-config (:type (ex-data ex)))))))

(deftest future-storage-version-refused-test
  ;; A stamp from a future storage model means this build predates the data's
  ;; migration; running anyway would silently answer false/empty.
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [{:eacl/id "schema-string"
                        :eacl/storage-version (inc mig/storage-version)}])
    (let [ex (try (mig/assert-storage-compatible! conn {}) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :eacl/storage-version (:type (ex-data ex))))
      (is (= (inc mig/storage-version) (:stamped-version (ex-data ex)))))))
