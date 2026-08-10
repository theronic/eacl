(ns eacl.datomic.safe-retraction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.mutation :as journal]
            [eacl.datomic.safe-retraction :as safe-datomic]
            [eacl.datomic.schema :as schema]
            [eacl.mutation :as mutation]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(defn- relation-eid
  [db resource-type relation-name subject-type]
  (d/entid
   db
   [:eacl.relation/resource-type+relation-name+subject-type
    [resource-type relation-name subject-type]]))

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-eid (d/entid db [:eacl/id (:id subject)])
        resource-eid (d/entid db [:eacl/id (:id resource)])
        relation-eid (relation-eid db (:type resource) relation (:type subject))]
    {:subject-eid subject-eid
     :resource-eid resource-eid
     :relation-eid relation-eid
     :forward [(:type subject) relation-eid (:type resource) resource-eid]
     :reverse [(:type resource) relation-eid (:type subject) subject-eid]}))

(defn- relationship-present?
  [db relationship]
  (let [{:keys [subject-eid resource-eid forward reverse]}
        (resolve-relationship db relationship)]
    (and subject-eid
         resource-eid
         (seq (d/datoms db :eavt subject-eid storage/forward-attribute forward))
         (seq (d/datoms db :eavt resource-eid storage/reverse-attribute reverse)))))

(defn- seed-contract!
  [conn]
  (schema/write-schema! conn contract/safe-retraction-schema)
  @(d/transact
    conn
    (mapv (fn [object] {:eacl/id (:id object)})
          contract/safe-retraction-objects))
  (doseq [relationship contract/safe-retraction-relationships]
    @(d/transact conn (impl/tx-relationship (d/db conn) relationship)))
  true)

(defn- deterministic-options
  []
  {:mutation-id (mutation/new-id)
   :issued-at 1700000000})

(deftest installation-is-explicit-versioned-and-safe-test
  (testing "the default schema does not claim the opt-in ident"
    (with-mem-conn [conn schema/v7-schema]
      (is (nil? (d/entid (d/db conn) safe/function-ident)))
      (is (= :absent
             (safe-datomic/installation-state (d/db conn))))
      (is (= :named (:mode (safe-datomic/support-descriptor))))
      (is (= 64 (count safe-datomic/function-digest)))
      (is (re-find #":requires \[\]"
                   (pr-str (:db/fn safe-datomic/function-definition)))
          "the stored function has no EACL or other transactor requires")
      (is (true? (:requires-installation?
                  (safe-datomic/support-descriptor))))

      (is (= {:installed? true :state :absent}
             (select-keys (safe-datomic/install! conn)
                          [:installed? :state])))
      (is (= :current
             (safe-datomic/installation-state (d/db conn))))
      (is (= {:installed? false :state :current}
             (select-keys (safe-datomic/install! conn)
                          [:installed? :state])))))

  (testing "a recognized older EACL marker is upgraded"
    (with-mem-conn [conn schema/v7-schema]
      @(d/transact
        conn
        [{:db/ident safe/function-ident
          :db/doc (str safe/function-doc-prefix " v0")
          :db/fn (d/function {:lang "clojure"
                              :params '[db target envelope]
                              :code '(do [])})}])
      (is (= :upgradeable
             (safe-datomic/installation-state (d/db conn))))
      (is (= :upgradeable (:state (safe-datomic/install! conn))))
      (is (= safe-datomic/function-doc
             (:db/doc (d/entity (d/db conn) safe/function-ident))))))

  (testing "an unrelated occupant is never overwritten"
    (with-mem-conn [conn schema/v7-schema]
      @(d/transact
        conn
        [{:db/ident safe/function-ident
          :db/doc "Application-owned function"
          :db/fn (d/function {:lang "clojure"
                              :params '[db target envelope]
                              :code '(do [])})}])
      (let [error (try
                    (safe-datomic/install! conn)
                    nil
                    (catch Exception error error))]
        (is (= :eacl.safe-retraction/install-conflict
               (:type (ex-data error))))
        (is (= "Application-owned function"
               (:db/doc (d/entity (d/db conn) safe/function-ident))))))))

(deftest installed-function-prewarm-is-repeatable-test
  (with-mem-conn [conn schema/v7-schema]
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          target [:eacl/id "prewarm-missing"]
          envelope (safe/mutation-envelope target (deterministic-options))
          first-expansion (d/invoke db safe/function-ident db target envelope)
          warmed-expansion (d/invoke db safe/function-ident db target envelope)]
      (is (= [] first-expansion warmed-expansion)))))

(deftest named-function-retracts-both-relationship-directions-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db-before (d/db conn)
          target-eid (d/entid db-before [:eacl/id "target-account"])
          owner-relation (relation-eid db-before :account :owner :user)
          account-relation (relation-eid db-before :server :account :account)
          peer-relation (relation-eid db-before :user :peer :user)
          peer-version-before (:eacl/relation-version
                               (d/entity db-before peer-relation))
          old-head (:head-id (journal/graph-state db-before))
          options (deterministic-options)
          mutation-id (:mutation-id options)]
      @(d/transact
        conn
        (safe-datomic/retract-entity-tx-data
         [:eacl/id "target-account"] options))
      (let [db (d/db conn)]
        (is (empty? (d/datoms db :eavt target-eid)))
        (is (every?
             false?
             (map #(boolean (relationship-present? db %))
                  (take 2 contract/safe-retraction-relationships))))
        (is (every?
             true?
             (map #(boolean (relationship-present? db %))
                  (drop 2 contract/safe-retraction-relationships))))
        (is (= mutation-id (:head-id (journal/graph-state db))))
        (is (not= old-head mutation-id))
        (is (= mutation-id
               (:eacl.mutation/id
                (d/entity db [:eacl.mutation/id mutation-id]))))
        (is (= mutation-id
               (:eacl.relation/mutation-id
                (d/entity db owner-relation))))
        (is (= mutation-id
               (:eacl.relation/mutation-id
                (d/entity db account-relation))))
        (is (= peer-version-before
               (:eacl/relation-version (d/entity db peer-relation)))
            "an unrelated relation proof remains stable"))

      (testing "an unresolved lookup ref is a no-op"
        (let [basis (d/basis-t (d/db conn))]
          @(d/transact
            conn
            (safe-datomic/retract-entity-tx-data
             [:eacl/id "missing"] (deterministic-options)))
          (is (= mutation-id (:head-id (journal/graph-state (d/db conn)))))
          (is (< basis (d/basis-t (d/db conn)))
              "Datomic may record the otherwise empty transaction"))))))

(deftest direct-expansion-matches-portable-local-half-oracle-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          target-eid (d/entid db [:eacl/id "target-account"])
          forward-values (mapv :v (d/datoms db :eavt target-eid
                                            storage/forward-attribute))
          reverse-values (mapv :v (d/datoms db :eavt target-eid
                                            storage/reverse-attribute))
          expected (safe/plan-local-halves target-eid
                                           forward-values reverse-values)
          envelope (safe/mutation-envelope target-eid
                                           (deterministic-options))
          expansion (d/invoke db safe/function-ident db target-eid envelope)
          peer-ops (into []
                         (filter #(and (vector? %)
                                       (= :db/retract (first %))))
                         expansion)
          report (d/with db expansion)]
      (is (= (set (:peer-retractions expected)) (set peer-ops)))
      (is (= (* 2 (count (:relation-ids expected)))
             (count
              (filter
               #(or (and (vector? %)
                         (= :db/add (first %))
                         (= :eacl/relation-version (nth % 2 nil)))
                    (and (map? %)
                         (contains? % :eacl.relation/mutation-id)))
               expansion))))
      (is (empty? (d/datoms (:db-after report) :eavt target-eid)))
      (is (every?
           false?
           (map #(boolean (relationship-present? (:db-after report) %))
                (take 2 contract/safe-retraction-relationships))))
      (let [error
            (try
              (d/invoke db safe/function-ident db target-eid
                        (assoc envelope :fingerprint
                               "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
              nil
              (catch Exception error error))]
        (is (= :invalid-fingerprint (:reason (ex-data error))))))))

(deftest expansion-performs-exactly-two-endpoint-attribute-reads-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          target-eid (d/entid db [:eacl/id "target-account"])
          envelope (safe/mutation-envelope target-eid (deterministic-options))
          calls (atom [])
          original d/datoms]
      (with-redefs [d/datoms
                    (fn [& args]
                      (swap! calls conj args)
                      (apply original args))]
        (d/invoke db safe/function-ident db target-eid envelope))
      (is (= {storage/forward-attribute 1
              storage/reverse-attribute 1}
             (frequencies
              (keep (fn [args]
                      (let [attribute (nth args 3 nil)]
                        (when (contains? storage/attributes attribute)
                          attribute)))
                    @calls)))))))

(deftest self-relationship-and-raw-eid-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          self-relationship (nth contract/safe-retraction-relationships 3)
          eid (d/entid db [:eacl/id "self-folder"])
          envelope (safe/mutation-envelope eid (deterministic-options))
          expansion (d/invoke db safe/function-ident db eid envelope)]
      (is (relationship-present? db self-relationship))
      (is (empty? (filter #(= :db/retract (first %)) expansion))
          "ordinary retractEntity removes both local halves")
      @(d/transact conn (safe-datomic/retract-entity-tx-data
                         eid (deterministic-options)))
      (is (empty? (d/datoms (d/db conn) :eavt eid))))))

(deftest missing-target-does-not-repair-an-existing-ghost-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-contract! conn)
    (safe-datomic/install! conn)
    (let [db (d/db conn)
          relationship (nth contract/safe-retraction-relationships 2)
          {:keys [subject-eid resource-eid reverse]}
          (resolve-relationship db relationship)]
      @(d/transact conn [[:db.fn/retractEntity subject-eid]])
      (is (seq (d/datoms (d/db conn) :eavt
                         resource-eid storage/reverse-attribute reverse)))
      @(d/transact conn (safe-datomic/retract-entity-tx-data
                         subject-eid (deterministic-options)))
      (is (seq (d/datoms (d/db conn) :eavt
                         resource-eid storage/reverse-attribute reverse))
          "the missing target owns no local half from which to discover the ghost"))))

(deftest ordinary-datomic-inbound-ref-and-component-semantics-are-preserved-test
  (let [extra-schema
        [{:db/ident :test/target
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/one}
         {:db/ident :test/component
          :db/valueType :db.type/ref
          :db/cardinality :db.cardinality/one
          :db/isComponent true}]]
    (with-mem-conn [conn (into schema/v7-schema extra-schema)]
      (safe-datomic/install! conn)
      @(d/transact conn [{:eacl/id "target"}])
      @(d/transact conn [{:eacl/id "referrer"
                          :test/target [:eacl/id "target"]}
                         {:eacl/id "parent"
                          :test/component {:eacl/id "child"}}])
      (let [db (d/db conn)
            target-eid (d/entid db [:eacl/id "target"])
            child-eid (d/entid db [:eacl/id "child"])]
        @(d/transact conn (safe-datomic/retract-entity-tx-data
                           target-eid (deterministic-options)))
        (is (empty? (d/datoms (d/db conn) :eavt target-eid)))
        (is (nil? (:test/target
                   (d/entity (d/db conn) [:eacl/id "referrer"]))))

        @(d/transact conn (safe-datomic/retract-entity-tx-data
                           [:eacl/id "parent"] (deterministic-options)))
        (is (empty? (d/datoms (d/db conn) :eavt child-eid))
            "component recursion remains Datomic's built-in behavior")))))

(def ^:private cache-schema
  "definition user {}
   definition account {
     relation owner: user
     permission admin = owner
   }")

(defn- seed-cache-case!
  [conn]
  (schema/write-schema! conn cache-schema)
  @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
  (let [relationship
        (eacl/->Relationship (eacl/spice-object :user "u")
                             :owner
                             (eacl/spice-object :account "a"))]
    @(d/transact conn (impl/tx-relationship (d/db conn) relationship))
    relationship))

(deftest managed-cache-observes-safe-retraction-test
  (with-mem-conn [conn schema/v7-schema]
    (seed-cache-case! conn)
    (safe-datomic/install! conn)
    (let [client (core/make-client conn {:coherence-authority :managed})
          user (eacl/spice-object :user "u")
          account (eacl/spice-object :account "a")]
      (is (true? (eacl/can? client user :admin account)))
      (is (true? (eacl/can? client user :admin account))
          "the answer is warm before deletion")
      @(d/transact conn
                   (safe-datomic/retract-entity-tx-data
                    [:eacl/id "a"] (deterministic-options)))
      (is (false? (eacl/can? client user :admin account))
          "the atomically advanced relation proof prevents stale reuse"))))

(deftest certified-writer-serializes-with-safe-retraction-test
  (testing "writer first: deletion observes and removes the committed pair"
    (with-mem-conn [conn schema/v7-schema]
      (schema/write-schema! conn cache-schema)
      @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
      (safe-datomic/install! conn)
      (let [client (core/make-client conn {})
            relationship
            (eacl/->Relationship (eacl/spice-object :user "u")
                                 :owner
                                 (eacl/spice-object :account "a"))]
        (eacl/create-relationship! client relationship)
        @(d/transact conn
                     (safe-datomic/retract-entity-tx-data
                      [:eacl/id "a"] (deterministic-options)))
        (is (not (relationship-present? (d/db conn) relationship))))))

  (testing "deletion first: a writer calculated from the old graph head loses"
    (with-mem-conns [writer-conn delete-conn schema/v7-schema]
      (schema/write-schema! writer-conn cache-schema)
      @(d/transact writer-conn [{:eacl/id "u"} {:eacl/id "a"}])
      (safe-datomic/install! delete-conn)
      (let [stale-db (d/db writer-conn)
            relationship
            (eacl/->Relationship (eacl/spice-object :user [:eacl/id "u"])
                                 :owner
                                 (eacl/spice-object :account [:eacl/id "a"]))
            ordinary (impl/tx-update-relationship
                      stale-db {:operation :create
                                :relationship relationship})
            guarded (impl/optimistic-relationship-tx-data stale-db ordinary)
            relation-id (impl/relationship-relation-id stale-db relationship)]
        @(d/transact delete-conn
                     (safe-datomic/retract-entity-tx-data
                      [:eacl/id "a"] (deterministic-options)))
        (let [error
              (try
                (journal/transact!
                 writer-conn
                 {:mutation-id (mutation/new-id)
                  :calculation-db stale-db
                  :kind :relationships
                  :canonical-data {:operation :concurrency-test}
                  :relation-ids [relation-id]
                  :tx-data guarded})
                nil
                (catch Throwable error error))]
          (is (some? error) "the stale graph-head CAS must fail")
          (is (empty? (d/datoms (d/db writer-conn) :eavt
                                (d/entid stale-db [:eacl/id "a"])))))))))
