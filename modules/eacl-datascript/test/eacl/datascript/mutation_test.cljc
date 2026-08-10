(ns eacl.datascript.mutation-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.causal-token :as causal-token]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.mutation :as journal]
            [eacl.datascript.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.storage :as storage]
            [eacl.schema.model :as model]))

(def test-schema
  "definition user {}
   definition folder {
     relation owner: user
     relation viewer: user
     permission view = owner + viewer
   }")

(def security-key "01234567890123456789012345678901")

(def schema-with-owner
  "definition user {}
   definition folder { relation owner: user }")

(def schema-without-owner
  "definition user {}
   definition folder {}")

(defn- relation-eid [db relation-name]
  (ds/entid db [:eacl/id (model/->relation-id :folder relation-name :user)]))

(defn- stamp [db relation-name]
  (first (ds/datoms db :eavt (relation-eid db relation-name)
                    :eacl/relation-version)))

(defn- internal-relationship
  [relationship]
  (let [lookup-object #(assoc % :id [:eacl/id (:id %)])]
    (-> relationship
        (update :subject lookup-object)
        (update :resource lookup-object))))

(defn- schema-transaction?
  [tx-data]
  (some #(and (vector? %)
              (= :db.fn/cas (first %))
              (= :eacl/schema-write-fence (nth % 2 nil)))
        tx-data))

(deftest ordinary-writes-publish-native-generations-without-journal-test
  (let [conn (schema/create-conn)
        client (datascript/make-client
                conn {:coherence-authority :managed
                      :security-key security-key})
        schema-result (eacl/write-schema! client test-schema)
        payload (causal-token/token-data
                 (get-in client [:opts :format-options])
                 (:zed/token schema-result))]
    (testing "schema write initializes physical relation generations"
      (is (= (:max-tx (ds/db conn)) (:revision payload)))
      (is (every? some? [(stamp (ds/db conn) :owner)
                         (stamp (ds/db conn) :viewer)]))
      (is (nil? (journal/graph-state (ds/db conn))))
      (is (not (contains? (:schema (ds/db conn)) :eacl.mutation/id))))
    (ds/transact! conn [{:eacl/id "user-1"}
                        {:eacl/id "folder-1"}
                        {:eacl/id "folder-2"}])
    (eacl/create-relationships!
     client
     [(eacl/->Relationship (eacl/spice-object :user "user-1") :owner
                           (eacl/spice-object :folder "folder-1"))
      (eacl/->Relationship (eacl/spice-object :user "user-1") :viewer
                           (eacl/spice-object :folder "folder-2"))])
    (let [db (ds/db conn)
          owner (stamp db :owner)
          viewer (stamp db :viewer)]
      (testing "one batch advances every distinct affected relation"
        (is (= (:tx owner) (:tx viewer) (:max-tx db)))
        (is (= (:v owner) (:v viewer))))
      (eacl/delete-object! client (eacl/spice-object :user "user-1"))
      (let [after (ds/db conn)]
        (is (= (:max-tx after)
               (:tx (stamp after :owner))
               (:tx (stamp after :viewer))))
        (is (not= (:tx owner) (:tx (stamp after :owner))))
        (is (nil? (journal/graph-state after)))))))

(deftest legacy-retry-journal-requires-explicit-schema-test
  (let [plain (schema/create-conn)
        error (try (journal/ensure-migrated! plain) nil
                   (catch #?(:clj clojure.lang.ExceptionInfo
                             :cljs cljs.core.ExceptionInfo) error
                     (ex-data error)))
        conn (schema/create-conn journal/mutation-schema)
        _ (journal/ensure-migrated! conn)
        mutation-id (mutation/new-id)
        options {:mutation-id mutation-id
                 :kind :custom
                 :canonical-data {:operation :optional-audit}
                 :tx-data []}
        report (journal/transact! conn options)
        recovered (journal/transact! conn options)]
    (is (= :eacl.mutation/schema-not-installed (:type error)))
    (is (not (:idempotent-recovery? report)))
    (is (true? (:idempotent-recovery? recovered)))
    (is (journal/contains-anchor? (ds/db conn) mutation-id))))

(deftest preparation-initializes-only-missing-generations-idempotently-test
  (let [conn (schema/create-conn)
        client (datascript/make-client conn {:security-key security-key})
        _ (eacl/write-schema! client test-schema)
        db (ds/db conn)
        schema-eid (ds/entid db [:eacl/id "schema-string"])
        schema-stamp (first (ds/datoms db :eavt schema-eid
                                       :eacl/schema-generation))
        owner-stamp (stamp db :owner)]
    (ds/transact! conn [[:db/retract schema-eid :eacl/schema-generation
                         (:v schema-stamp)]
                        [:db/retract (relation-eid db :owner)
                         :eacl/relation-version (:v owner-stamp)]])
    (let [prepared (datascript/prepare-cache-coherence! conn)
          repeated (datascript/prepare-cache-coherence! conn)]
      (is (true? (:prepared? prepared)))
      (is (true? (:changed? prepared)))
      (is (true? (:schema-generation-initialized? prepared)))
      (is (= 1 (:relation-generations-initialized prepared)))
      (is (empty? (:missing-after prepared)))
      (is (false? (:changed? repeated)))
      (is (zero? (:relation-generations-initialized repeated))))))

#?(:clj
   (deftest concurrent-schema-replacements-do-not-merge-test
     (let [conn (schema/create-conn)
           _ (schema/write-schema! conn "definition user {}")
           schema-left
           "definition user {}
            definition folder { relation left: user }"
           schema-right
           "definition user {}
            definition folder { relation right: user }"
           ready (java.util.concurrent.CountDownLatch. 2)
           transact ds/transact!]
       (with-redefs [ds/transact!
                     (fn [connection tx-data]
                       (when (schema-transaction? tx-data)
                         (.countDown ready)
                         (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
                       (transact connection tx-data))]
         (let [write
               (fn [text]
                 (future
                   (try
                     (schema/write-schema! conn text)
                     :ok
                     (catch clojure.lang.ExceptionInfo error
                       (:type (ex-data error))))))
               results (mapv deref [(write schema-left) (write schema-right)])
               relation-names
               (into #{}
                     (map :eacl.relation/relation-name)
                     (:relations (schema/read-schema (ds/db conn))))]
           (is (= #{:ok :eacl.schema/concurrent-write} (set results)))
           (is (contains? #{#{:left} #{:right}} relation-names)
               "the committed schema is one complete replacement, never a union"))))))

#?(:clj
   (deftest relationship-committed-after-preflight-aborts-schema-removal-test
     (let [conn (schema/create-conn)
           _ (schema/write-schema! conn schema-with-owner)
           _ (ds/transact! conn [{:eacl/id "user"} {:eacl/id "folder"}])
           relationship
           (eacl/->Relationship (eacl/spice-object :user "user") :owner
                                (eacl/spice-object :folder "folder"))
           planned
           (impl/tx-update-relationship
            (ds/db conn)
            {:operation :create
             :relationship (internal-relationship relationship)})
           transact ds/transact!
           inject? (atom true)
           error
           (with-redefs [ds/transact!
                         (fn [connection tx-data]
                           (when (and (schema-transaction? tx-data)
                                      (compare-and-set! inject? true false))
                             (transact connection planned))
                           (transact connection tx-data))]
             (try
               (schema/write-schema! conn schema-without-owner)
               nil
               (catch clojure.lang.ExceptionInfo error error)))]
       (is (= :eacl.schema/concurrent-write (:type (ex-data error))))
       (is (some? (impl/find-one-relationship-id
                   (ds/db conn) (internal-relationship relationship)))))))

(deftest relation-removed-first-aborts-stale-planned-relationship-test
  (let [conn (schema/create-conn)
        _ (schema/write-schema! conn schema-with-owner)
        _ (ds/transact! conn [{:eacl/id "user"} {:eacl/id "folder"}])
        relationship
        (eacl/->Relationship (eacl/spice-object :user "user") :owner
                             (eacl/spice-object :folder "folder"))
        db (ds/db conn)
        removed-relation-eid (relation-eid db :owner)
        planned
        (impl/tx-update-relationship
         db
         {:operation :create
          :relationship (internal-relationship relationship)})
        _ (schema/write-schema! conn schema-without-owner)
        error (try (ds/transact! conn planned) nil
                   (catch #?(:clj Exception :cljs :default) error error))]
    (is (some? error))
    (is (empty? (ds/datoms (ds/db conn) :eavt removed-relation-eid))
        "the stale relation stamp must not recreate an identity-less entity")))

(deftest reverse-only-ghost-blocks-relation-removal-test
  (let [conn (schema/create-conn)
        _ (schema/write-schema! conn schema-with-owner)
        _ (ds/transact! conn [{:eacl/id "user"} {:eacl/id "folder"}])
        db (ds/db conn)
        user-eid (ds/entid db [:eacl/id "user"])
        folder-eid (ds/entid db [:eacl/id "folder"])
        owner-eid (relation-eid db :owner)
        reverse-value [:folder owner-eid :user user-eid]
        _ (ds/transact! conn [[:db/add folder-eid
                               storage/reverse-attribute reverse-value]])
        error (try (schema/write-schema! conn schema-without-owner) nil
                   (catch #?(:clj clojure.lang.ExceptionInfo
                             :cljs cljs.core.ExceptionInfo) error error))]
    (is (= :eacl.schema/relation-in-use (:type (ex-data error))))
    (is (= 1 (:count (ex-data error))))))
