(ns eacl.datomic.speculative-admission-test
  "Public provenance and same-basis cache-poisoning regressions for eacl/with."
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.causal-token :as causal-token]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.engine.v8 :as engine]))

(def ^:private schema
  "definition user {}
   definition document {
     relation reader: user
     relation banned: user
     permission view = reader - banned
   }
   definition report {
     relation viewer: user
     permission view = viewer
   }")

(def ^:private alice (eacl/spice-object :user "alice"))
(def ^:private doc (eacl/spice-object :document "doc-1"))
(def ^:private report (eacl/spice-object :report "report-1"))

(def ^:private schema-without-ban
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }
   definition report {
     relation viewer: user
     permission view = viewer
   }")

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn- view?
  [target]
  (binding [engine/*operator-routing-enabled?* true]
    (eacl/can? target
               {:subject alice :permission :view :resource doc})))

(defn- seed!
  [client conn]
  (binding [orchestration/*operator-expression-writes-enabled?* true]
    (eacl/write-schema! client schema))
  @(d/transact conn [{:eacl/id "alice"}
                     {:eacl/id "doc-1"}
                     {:eacl/id "report-1"}])
  (eacl/create-relationships!
   client
   [(eacl/->Relationship alice :reader doc)
    (eacl/->Relationship alice :banned doc)
    (eacl/->Relationship alice :viewer report)]))

(deftest caller-native-database-values-have-no-public-constructor-test
  (is (nil? (ns-resolve 'eacl.datomic.core 'snapshot)))
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [db (d/db conn)
          values
          [db
           (d/filter db (fn [_ _] true))
           (d/history db)
           (d/since db 0)
           (:db-after (d/with db [{:db/id "datomic.tx"
                                   :db/doc "speculative"}]))]]
      (doseq [value values]
        (let [data
              (try
                (eacl/snapshot value)
                nil
                (catch clojure.lang.ExceptionInfo error
                  (ex-data error)))]
          (is (= :eacl/unsupported-capability (:type data)))
          (is (= :snapshot (:capability data)))
          (is (= :non-eacl (:target data))))))))

(deftest same-t-same-tx-instant-different-content-cannot-poison-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      ;; Leave a second document relationship after the speculative removal so
      ;; the first page emits a cursor whose provenance can be challenged by
      ;; the real same-t collision below.
      @(d/transact conn [{:eacl/id "doc-2"}])
      (eacl/create-relationships!
       client
       [(eacl/->Relationship
         alice :reader (eacl/spice-object :document "doc-2"))])
      (is (false? (view? client)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [shared-instant (java.util.Date.)
              delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)
              prospective
              (eacl/with
               base
               (into [{:db/id "datomic.tx"
                       :db/txInstant shared-instant
                       :db/doc "speculative ban removal"}]
                     delete-ban))]
          (try
            (let [speculative-db (datomic/db prospective)
                  collision-t (d/basis-t speculative-db)
                  speculative-page
                  (eacl/read-relationships
                   prospective {:resource/type :document :first 1})
                  speculative-cursor
                  (get-in speculative-page [:page-info :end-cursor])]
              (is (= :speculative (:kind (eacl/basis prospective))))
              (is (string? speculative-cursor))
              (is (map?
                   (eacl/read-relationships
                    prospective
                    {:resource/type :document
                     :first 1
                     :after speculative-cursor})))
              (is (true? (view? prospective))
                  "affected dependencies evaluate against db-after")
              (is (true? (view? prospective))
                  "a repeated speculative miss remains correct")
              (let [committed
                    (:db-after
                     @(d/transact
                       conn
                       [{:db/id "datomic.tx"
                         :db/txInstant shared-instant
                         :db/doc "different committed content"}]))]
                (is (= collision-t (d/basis-t committed)))
                (is (= shared-instant
                       (:db/txInstant
                        (d/entity speculative-db (d/t->tx collision-t)))
                       (:db/txInstant
                        (d/entity committed (d/t->tx collision-t)))))
                (is (not=
                     (:db/doc
                      (d/entity speculative-db (d/t->tx collision-t)))
                     (:db/doc
                      (d/entity committed (d/t->tx collision-t)))))
                (let [cursor-error
                      (error-data
                       #(eacl/read-relationships
                         client
                         {:resource/type :document
                          :first 1
                          :after speculative-cursor}))]
                  (is (= :eacl.pagination/invalid-cursor
                         (:type cursor-error)))
                  (is (= :speculative-provenance
                         (:reason cursor-error)))
                  (is (not (nil? cursor-error))
                      "a speculative cursor cannot cross to a colliding commit"))))
            (finally
              (eacl/release! prospective)))))
      (is (false? (view? client))
          "the committed exact-basis answer is not the speculative grant"))))

(deftest eacl-with-is-composable-and-schema-storage-is-guarded-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)]
          (eacl/with-snapshot [s1 (eacl/with base delete-ban)]
            (is (true? (view? s1)))
            (let [restore-ban
                  (eacl/tx-relationship s1 :touch alice :banned doc)]
              (eacl/with-snapshot [s2 (eacl/with s1 restore-ban)]
                (is (false? (view? s2)))
                (is (= :speculative (:kind (eacl/basis s2)))))))
          (let [data
                (try
                  (eacl/with
                   base
                   [[:db/add [:eacl/id "schema-string"]
                     :eacl/schema-string "definition user {}"]])
                  nil
                  (catch clojure.lang.ExceptionInfo error
                    (ex-data error)))]
            (is (= :eacl.speculative/schema-mutation (:type data)))
            (is (= :with-schema (:use data)))))))))

(deftest speculative-read-through-reuses-only-disjoint-committed-proof-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})
          report-demand
          {:subject alice :permission :view :resource report}]
      (seed! client conn)
      (is (true? (eacl/can? client report-demand)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)]
          (eacl/with-snapshot [prospective (eacl/with base delete-ban)]
            (let [before (datomic/cache-stats client)
                  decision (eacl/check-permission prospective report-demand)
                  after (datomic/cache-stats client)]
              (is (true? (:allowed? decision)))
              (is (true? (:cached? decision))
                  "the disjoint proof reads through the committed managed tier")
              (is (= (inc (:managed-hits before))
                     (:managed-hits after)))
              (is (= (:puts before) (:puts after))
                  "speculative read-through never promotes or publishes"))))))))

(deftest with-schema-shares-planning-and-retain-inert-is-bounded-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [default-error
              (error-data #(eacl/with-schema base schema-without-ban))]
          (is (= :eacl.schema/relation-in-use (:type default-error))))
        (eacl/with-snapshot
          [prospective
           (with-redefs
             [datomic-schema/count-relationships-using-relation
              (fn [& _]
                (throw
                 (ex-info "retain-inert enumerated relationship tuples"
                          {:type :test/unbounded-orphan-count})))]
             (eacl/with-schema
              base schema-without-ban {:orphan-policy :retain-inert}))]
          (is (true? (view? prospective))
              "prospective permission schema is used for authorization")
          (is (= [{:type
                   :eacl.speculative/retained-orphan-relationships
                   :relation [:relation :document :banned :user]
                   :present? true}]
                 (eacl/speculative-diagnostics prospective)))
          (is (= #{:reader}
                 (into #{}
                       (map :relation)
                       (:data
                        (eacl/read-relationships
                         prospective {:resource/type :document}))))
              "retained tuples are physically present but semantically inert")
          (eacl/with-snapshot [restored (eacl/with-schema prospective schema)]
            (is (= :speculative (:kind (eacl/basis restored))))
            (is (= (eacl/speculative-diagnostics prospective)
                   (eacl/speculative-diagnostics restored))
                "diagnostics and cumulative effects never subtract")
            (is (false?
                 (:cached?
                  (eacl/check-permission
                   restored
                   {:subject alice :permission :view :resource doc})))
                "restoration never makes the committed root proof reusable"))))
      (is (false? (view? client)) "the committed client remains unchanged")
      (is (= :eacl.schema/invalid-orphan-policy
             (:type
              (error-data
               #(eacl/write-schema!
                 client {:schema schema-without-ban
                         :orphan-policy :retain-inert})))))
      (is (false? (view? client))))))

(deftest speculative-schema-change-reuses-only-disjoint-schema-proof-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})
          report-demand {:subject alice :permission :view :resource report}]
      (seed! client conn)
      (is (true? (eacl/can? client report-demand)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (eacl/with-snapshot
          [prospective
           (eacl/with-schema
            base schema-without-ban {:orphan-policy :retain-inert})]
          (let [before (datomic/cache-stats client)
                decision (eacl/check-permission prospective report-demand)
                after (datomic/cache-stats client)]
            (is (true? (:allowed? decision)))
            (is (true? (:cached? decision)))
            (is (= (inc (:managed-hits before)) (:managed-hits after)))
            (is (= (:puts before) (:puts after)))))))))

(deftest transaction-function-expanded-datoms-drive-effects-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      @(d/transact
        conn
        [{:db/ident :eacl.test/emit-tx-data
          :db/fn
          (d/function
           {:lang "clojure"
            :params '[db tx-data]
            :code 'tx-data})}])
      (is (false? (view? client)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)]
          (eacl/with-snapshot
            [prospective
             (eacl/with base [[:eacl.test/emit-tx-data delete-ban]])]
            (let [before (datomic/cache-stats client)
                  decision
                  (eacl/check-permission
                   prospective
                   {:subject alice :permission :view :resource doc})
                  after (datomic/cache-stats client)]
              (is (true? (:allowed? decision)))
              (is (false? (:cached? decision))
                  "the emitted relationship effect blocks the root proof")
              (is (= (:managed-hits before) (:managed-hits after)))
              (is (= (:puts before) (:puts after))))))))))

(deftest unclassified-application-datom-disables-all-read-through-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})
          report-demand {:subject alice :permission :view :resource report}]
      (seed! client conn)
      (is (true? (eacl/can? client report-demand)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (eacl/with-snapshot
          [prospective
           (eacl/with
            base
            [[:db/add [:eacl/id "doc-1"] :db/doc "prospective app data"]])]
          (let [before (datomic/cache-stats client)
                decision (eacl/check-permission prospective report-demand)
                after (datomic/cache-stats client)]
            (is (true? (:allowed? decision)))
            (is (false? (:cached? decision)))
            (is (= (:managed-hits before) (:managed-hits after)))
            (is (= (:puts before) (:puts after)))))))))

(deftest speculative-snapshots-are-immutable-lifecycle-values-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      (eacl/with-snapshot [base (eacl/snapshot client)]
        ;; Tokens are minted per call from the wall clock. Pin it so the
        ;; comparison exercises the committed root identity rather than the
        ;; second boundary between the two mints.
        (with-redefs [causal-token/now-seconds
                      (constantly (causal-token/now-seconds))]
          (let [root-token (eacl/basis-token base)
                delete-ban
                (eacl/tx-relationship base :delete alice :banned doc)
                prospective (eacl/with client delete-ban)]
            (try
              (is (= :speculative (:kind (eacl/basis prospective))))
              (is (= root-token (eacl/basis-token prospective))
                  "speculation preserves its authenticated committed root")
              (doseq [invoke
                      [#(eacl/write-relationships! prospective [])
                       #(eacl/write-schema! prospective schema)]]
                (let [data (error-data invoke)]
                  (is (= :eacl/unsupported-capability (:type data)))
                  (is (= :write (:capability data)))))
              (is (false? (eacl/released? prospective)))
              (finally
                (eacl/release! prospective)))
            (is (true? (eacl/released? prospective)))))))))

(deftest sibling-speculation-never-shares-computed-answers-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client conn)
      (is (false? (view? client)))
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)
              delete-reader
              (eacl/tx-relationship base :delete alice :reader doc)
              ready (promise)
              run
              (fn [tx-data]
                (future
                  @ready
                  (let [snapshot (eacl/with client tx-data)]
                    (try
                      {:decision (view? snapshot)
                       :basis (:kind (eacl/basis snapshot))}
                      (finally
                        (eacl/release! snapshot))))))
              left (run delete-ban)
              right (run delete-reader)]
          (deliver ready true)
          (is (= #{{:decision true :basis :speculative}
                   {:decision false :basis :speculative}}
                 #{@left @right}))))
      (is (false? (view? client))))))

(deftest every-public-speculative-read-has-zero-cache-publication-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})
          demand {:subject alice :permission :view :resource doc}]
      (seed! client conn)
      (eacl/with-snapshot [base (eacl/snapshot client)]
        (let [delete-ban
              (eacl/tx-relationship base :delete alice :banned doc)]
          (eacl/with-snapshot [prospective (eacl/with base delete-ban)]
            (let [before (datomic/cache-stats client)]
              (is (map? (eacl/check-permission prospective demand)))
              (is (= 2
                     (count
                      (eacl/check-permissions
                       prospective {:checks [demand demand]}))))
              (is (map? (eacl/read-schema prospective)))
              (is (map?
                   (eacl/read-relationships
                    prospective {:resource/type :document :first 100})))
              (is (map?
                   (eacl/lookup-resources
                    prospective
                    {:subject alice
                     :permission :view
                     :resource/type :document
                     :first 100})))
              (is (map?
                   (eacl/lookup-subjects
                    prospective
                    {:resource doc
                     :permission :view
                     :subject/type :user
                     :first 100})))
              (is (map?
                   (eacl/count-resources
                    prospective
                    {:subject alice
                     :permission :view
                     :resource/type :document})))
              (is (map?
                   (eacl/count-subjects
                    prospective
                    {:resource doc
                     :permission :view
                     :subject/type :user})))
              (is (map?
                   (eacl/expand-permission-tree
                    prospective {:resource doc :permission :view})))
              (let [after (datomic/cache-stats client)]
                (is (= (:puts before) (:puts after)))
                (is (= (:exact-size before) (:exact-size after)))
                (is (= (:managed-size before) (:managed-size after)))))))))))
