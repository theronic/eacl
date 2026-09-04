(ns eacl.datascript.migrations.v7-to-v9-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
            [eacl.datascript.schema :as schema]
            [eacl.datascript.storage :as admission]
            [eacl.datascript.migrations.v7-to-v9 :as migration]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.migration-contract :as contract]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade :as upgrade]
            [eacl.relationships.upgrade-test :refer [error-data]]))

(def source-schema
  (-> schema/datascript-schema
      (dissoc storage/forward-attribute storage/reverse-attribute)
      (assoc legacy/forward-attribute {:db/cardinality :db.cardinality/many :db/index true}
             legacy/reverse-attribute {:db/cardinality :db.cardinality/many :db/index true})))

(defn source-conn []
  (let [conn (d/create-conn source-schema)]
    (d/transact! conn [{:db/id 100 :eacl/id "schema-string" :eacl/storage-version 7}
                       {:db/id 10 :eacl/id "viewer" :eacl.relation/resource-type :document
                        :eacl.relation/subject-type :user :eacl.relation/relation-name :viewer}
                       {:db/id 20 :eacl/id "document"}
                       {:db/id 1 :eacl/id "alice"} {:db/id 2 :eacl/id "bob"}
                       [:db/add 1 legacy/forward-attribute [:user 10 :document 20]]
                       [:db/add 2 legacy/forward-attribute [:user 10 :document 20]]
                       [:db/add 20 legacy/reverse-attribute [:document 10 :user 1]]
                       [:db/add 20 legacy/reverse-attribute [:document 10 :user 2]]])
    conn))

(deftest migration-resumes-at-every-committed-phase-test
  (doseq [stop [:preflight :converting :verifying :cleaning :complete]]
    (let [conn (source-conn)
          initial (d/db conn)
          phases (atom [])]
      (is (= :eacl/storage-version (:type (error-data #(api/make-client conn {})))))
      (is (= :interrupted
             (:reason (error-data
                       #(migration/migrate! conn {:quiesced? true :batch-size 1
                                                  :on-progress (fn [report]
                                                                 (swap! phases conj (:state report))
                                                                 (when (and (= stop (:state report))
                                                                            (or (not= stop :converting)
                                                                                (= 1 (:converted report))))
                                                                   (throw (ex-info "Test interruption" {:reason :interrupted}))))})))))
      (when-not (= :complete stop)
        (is (= :eacl/storage-version (:type (error-data #(api/make-client conn {}))))))
      (let [report (migration/migrate! conn {:quiesced? true :batch-size 1})
            after (d/db conn)
            revision (:max-tx after)]
        (is (= :complete (:state report)))
        (is (= 2 (:source-count report)))
        (is (empty? (d/datoms after :aevt legacy/forward-attribute)))
        (is (= 2 (count (d/datoms after :aevt storage/forward-attribute))))
        (is (= 2 (count (d/datoms after :aevt storage/reverse-attribute))))
        (is (= 2 (count (d/datoms initial :aevt legacy/forward-attribute))))
        (is (admission/assert-compatible! after))
        (is (:already-complete? (migration/migrate! conn {:quiesced? true})))
        (is (= revision (:max-tx (d/db conn))))))))

(deftest corrupt-source-and-concurrent-head-never-complete-test
  (let [conn (source-conn)]
    (d/transact! conn [[:db/retract 20 legacy/reverse-attribute [:document 10 :user 1]]])
    (is (= :pair-mismatch (:reason (error-data #(migration/migrate! conn {:quiesced? true}))))))
  (let [conn (source-conn)]
    (is (= :concurrent-write
           (:reason (error-data
                     #(migration/migrate! conn {:quiesced? true :batch-size 1
                                                :on-progress (fn [_] (d/transact! conn [{:eacl/id "interfering"}]))})))))
    (is (= 7 (:version (admission/evidence (d/db conn))))))
  (is (= :invalid-options (:reason (error-data #(migration/migrate! (source-conn) {}))))))

(deftest preflight-rejects-native-corruption-test
  (doseq [[reason corrupt!]
          [[:pair-mismatch #(d/transact! % [[:db.fn/retractEntity 1]])]
           [:invalid-relation #(d/transact! % [[:db/retract 10 :eacl.relation/subject-type :user]])]
           [:invalid-relation #(d/transact! % [[:db/add 10 :eacl.relation/resource-type :account]])]
           [:wrong-source-version #(d/transact! % [[:db/add 100 :eacl/storage-version 8]])]
           [:incompatible-source-schema #(d/reset-schema! % (assoc-in (:schema @%) [legacy/forward-attribute :db/index] false))]
           [:v6-prerequisite #(d/transact! % [{:eacl.relationship/relation-name :viewer}])]]]
    (let [conn (source-conn)]
      (corrupt! conn)
      (is (= reason (:reason (error-data #(migration/migrate! conn {:quiesced? true})))) (str reason))
      (is (not= 9 (:version (admission/evidence @conn)))))))

(deftest schema-change-without-transaction-is-fenced-test
  (let [conn (source-conn)]
    (is (= :concurrent-schema-change
           (:reason (error-data
                     #(migration/migrate!
                       conn {:quiesced? true
                             :on-progress (fn [_]
                                            (d/reset-schema! conn
                                                             (assoc (:schema @conn) :foreign/attribute {})))})))))
    (is (= 7 (:version (admission/evidence @conn))))))

(deftest complete-target-admission-rejects-mixed-and-wrong-stores-test
  (doseq [[reason corrupt!]
          [[:legacy-data #(d/transact! % [[:db/add 1 legacy/forward-attribute [:user 10 :document 20]]])]
           [:incomplete-storage #(d/transact! % [[:db/add 100 :eacl/storage-version 7]])]
           [:incomplete-storage #(d/transact! % [[:db/retract 100 upgrade/state-attribute
                                                (get (d/entity @% 100) upgrade/state-attribute)]])]
           [:incompatible-schema #(d/reset-schema! % (assoc-in (:schema @%) [storage/forward-attribute :db/index] false))]]]
    (let [conn (source-conn)]
      (migration/migrate! conn {:quiesced? true})
      (is (admission/assert-compatible! @conn))
      (corrupt! conn)
      (is (= reason (:reason (error-data #(api/make-client conn {}))))))))

(defn open-source []
  (let [conn (d/create-conn source-schema)]
    {:write-schema! #(schema/write-schema! conn %)
     :client! #(api/make-client conn {})
     :snapshot #(d/db conn) :entid d/entid
     :transact! #(d/transact! conn %)
     :migrate! #(migration/migrate! conn %)
     :close! (fn [])}))

(deftest migrated-public-contract-test (contract/exercise-public! open-source))

(deftest native-startup-admission-test (contract/exercise-admission! open-source))
