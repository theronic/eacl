(ns eacl.datomic.speculative-admission-test
  "Speculative database values must be refused by public snapshot
  constructors before they can create a basis identity or publish into any
  cache tier shared with committed bases.

  The poisoning shape under regression: a `d/with`/`db-with` value takes the
  next commit's revision, answers from uncommitted state, and publishes into
  the exact-basis cache tier under `{source-scope, revision}`. When the real
  commit lands at that revision, a live client and a head snapshot both hit
  the speculative entry, so a banned subject is authorized. The control run
  (same fixture, no speculative capture) answers `false` throughout."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datomic.api :as d]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as datahike-db]
            [eacl.datascript.core :as datascript]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.engine.v8 :as engine]
            [eacl.relationships.storage :as relationship-storage]))

(def ^:private schema
  "definition user {}
   definition document {
     relation reader: user
     relation banned: user
     permission view = reader - banned
   }")

(def ^:private alice (eacl/spice-object :user "alice"))
(def ^:private doc (eacl/spice-object :document "doc-1"))

(defn- view?
  [target]
  (binding [engine/*operator-routing-enabled?* true]
    (eacl/can? target {:subject alice :permission :view :resource doc})))

(defn- seed!
  "Writes the exclusion schema and a `reader + banned` pair for alice, so the
  committed answer for `view` is `false`."
  [client transact!]
  (binding [orchestration/*operator-expression-writes-enabled?* true]
    (eacl/write-schema! client schema))
  (transact! [{:eacl/id "alice"} {:eacl/id "doc-1"}])
  (eacl/create-relationships!
   client
   [(eacl/->Relationship alice :reader doc)
    (eacl/->Relationship alice :banned doc)]))

(defn- ban-retraction-tx
  "Builds the raw retraction of every `banned` relationship tuple, using only
  the value's own datoms. Applied speculatively, it flips alice's committed
  `false` to `true` without any commit."
  [datoms-fn q-fn db]
  (let [banned-relation
        (q-fn '[:find ?r .
                :where
                [?r :eacl.relation/resource-type :document]
                [?r :eacl.relation/relation-name :banned]]
              db)
        tuple-retractions
        (fn [attribute]
          (->> (datoms-fn db :aevt attribute)
               (filter #(= banned-relation (nth (:v %) 1)))
               (mapv (fn [datom] [:db/retract (:e datom) attribute (:v datom)]))))]
    (into (tuple-retractions relationship-storage/forward-attribute)
          (tuple-retractions relationship-storage/reverse-attribute))))

(defn- refusal-data
  "Attempts a public snapshot over `db`. On the fixed code path this returns
  the typed refusal's ex-data. If admission still succeeds (the regressed
  path), the speculative snapshot is exercised so the answer publishes into
  the shared cache exactly as the defect did, and nil is returned."
  [snapshot-fn client db]
  (let [outcome
        (try
          {:snapshot (snapshot-fn client db)}
          (catch clojure.lang.ExceptionInfo error
            {:error (ex-data error)}))]
    (when-let [speculative (:snapshot outcome)]
      (try
        (view? speculative)
        (finally
          (eacl/release! speculative))))
    (:error outcome)))

(defn- exercise-control!
  [client]
  (is (false? (view? client))
      "control: live client answers false for the banned subject")
  (let [head (eacl/snapshot client)]
    (try
      (is (false? (view? head))
          "control: head snapshot answers false for the banned subject")
      (finally
        (eacl/release! head)))))

(defn- exercise-treatment!
  "Captures a speculative value, asserts the typed refusal, then commits the
  colliding revision and asserts committed answers are unpoisoned."
  [{:keys [client speculative-db snapshot-fn advance!]}]
  (let [refusal (refusal-data snapshot-fn client speculative-db)]
    (is (= :eacl/unsupported-database-value (:type refusal))
        "speculative value is refused with the typed admission error")
    (is (= (:type refusal) (:eacl/error refusal)))
    (is (= :speculative (:basis-kind refusal))
        "refusal names basis kind :speculative"))
  (advance!)
  (is (false? (view? client))
      "live client still answers false after the colliding commit")
  (let [head (eacl/snapshot client)]
    (try
      (is (false? (view? head))
          "head snapshot still answers false after the colliding commit")
      (finally
        (eacl/release! head)))))

(deftest datomic-speculative-with-value-is-refused-and-cannot-poison-test
  (testing "control"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [client (datomic/make-client conn {})]
        (seed! client #(deref (d/transact conn %)))
        (exercise-control! client))))
  (testing "treatment"
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (with-mem-conn [conn datomic-schema/v7-schema]
      (let [client (datomic/make-client conn {})]
        (seed! client #(deref (d/transact conn %)))
        (let [db (d/db conn)
              speculative
              (:db-after
               (d/with db (ban-retraction-tx d/datoms (fn [q db] (d/q q db)) db)))]
          (is (= (d/basis-t (d/db conn)) (dec (d/basis-t speculative)))
              "the speculative basis is the next commit's revision")
          (exercise-treatment!
           {:client client
            :speculative-db speculative
            :snapshot-fn datomic/snapshot
            :advance!
            #(deref
              (d/transact conn [{:db/id "datomic.tx"
                                 :db/doc "colliding committed revision"}]))}))))))

(deftest datascript-speculative-db-with-value-is-refused-and-cannot-poison-test
  (testing "control"
    (let [conn (datascript/create-conn)
          client (datascript/make-client conn {})]
      (seed! client #(ds/transact! conn %))
      (exercise-control! client)))
  (testing "treatment"
    (let [conn (datascript/create-conn)
          client (datascript/make-client conn {})]
      (seed! client #(ds/transact! conn %))
      (let [db (ds/db conn)
            speculative
            (ds/db-with db (ban-retraction-tx ds/datoms (fn [q db] (ds/q q db)) db))]
        (is (= (inc (:max-tx db)) (:max-tx speculative))
            "the speculative basis is the next commit's revision")
        (exercise-treatment!
         {:client client
          :speculative-db speculative
          :snapshot-fn datascript/snapshot
          :advance! #(ds/transact! conn [{:eacl/id "advance-marker"}])})))))

(defn- admission-outcome
  "Returns `:admitted` when the public snapshot constructor accepts `db`, or
  the refused basis kind from the typed admission error."
  [snapshot-fn client db]
  (try
    (let [snapshot (snapshot-fn client db)]
      (eacl/release! snapshot)
      :admitted)
    (catch clojure.lang.ExceptionInfo error
      (let [data (ex-data error)]
        (is (= :eacl/unsupported-database-value (:type data)))
        (is (= (:type data) (:eacl/error data)))
        (:basis-kind data)))))

(defn- exercise-admission-matrix!
  [snapshot-fn client cases]
  (doseq [[expected db] cases]
    (testing (name expected)
      (is (= expected (admission-outcome snapshot-fn client db))))))

(deftest datomic-admission-matrix-test
  #_{:clj-kondo/ignore [:unresolved-symbol]}
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (datomic/make-client conn {})]
      (seed! client #(deref (d/transact conn %)))
      (let [db (d/db conn)]
        (exercise-admission-matrix!
         datomic/snapshot client
         [[:admitted db]
          [:admitted (d/as-of db (d/basis-t db))]
          [:admitted (d/as-of db (dec (d/basis-t db)))]
          [:filtered (d/filter db (fn [_ _] true))]
          [:history (d/history db)]
          [:since (d/since db 0)]
          [:speculative
           (:db-after (d/with db [{:db/id "datomic.tx"
                                   :db/doc "speculative"}]))]
          [:speculative
           (d/as-of
            (:db-after (d/with db [{:db/id "datomic.tx"
                                    :db/doc "speculative"}]))
            (d/basis-t db))]])))))

(deftest datahike-admission-matrix-test
  (let [conn (datahike/create-conn nil {})
        config (datahike-db/db-config (dh/db conn))
        client (datahike/make-client conn {})]
    (try
      (seed! client #(dh/transact conn {:tx-data (vec %)}))
      (let [db (dh/db conn)]
        (exercise-admission-matrix!
         datahike/snapshot client
         [[:admitted db]
          [:admitted (dh/as-of db (:max-tx db))]
          [:filtered (dh/filter db (fn [_ _] true))]
          [:history (dh/history db)]
          [:since (dh/since db (java.util.Date.))]
          [:speculative (dh/db-with db [{:eacl/id "speculative-marker"}])]
          [:speculative
           (dh/as-of (dh/db-with db [{:eacl/id "speculative-marker"}])
                     (:max-tx db))]]))
      (finally
        (dh/release conn)
        (dh/delete-database config)))))

(deftest datascript-admission-matrix-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (seed! client #(ds/transact! conn %))
    (let [db (ds/db conn)]
      (exercise-admission-matrix!
       datascript/snapshot client
       [[:admitted db]
        [:filtered (ds/filter db (fn [_ _] true))]
        [:speculative (ds/db-with db [{:eacl/id "speculative-marker"}])]]))))

(deftest datahike-speculative-db-with-value-is-refused-and-cannot-poison-test
  (let [run!
        (fn [exercise!]
          (let [conn (datahike/create-conn nil {})
                config (datahike-db/db-config (dh/db conn))
                client (datahike/make-client conn {})]
            (try
              (seed! client #(dh/transact conn {:tx-data (vec %)}))
              (exercise! conn client)
              (finally
                (dh/release conn)
                (dh/delete-database config)))))]
    (testing "control"
      (run! (fn [_conn client] (exercise-control! client))))
    (testing "treatment"
      (run!
       (fn [conn client]
         (let [db (dh/db conn)
               speculative
               (dh/db-with db (ban-retraction-tx
                               (fn [db index attribute]
                                 (dh/datoms db {:index index
                                                :components [attribute]}))
                               (fn [q db] (dh/q q db))
                               db))]
           (is (= (inc (:max-tx db)) (:max-tx speculative))
               "the speculative basis is the next commit's revision")
           (exercise-treatment!
            {:client client
             :speculative-db speculative
             :snapshot-fn datahike/snapshot
             :advance! #(dh/transact conn {:tx-data [{:eacl/id "advance-marker"}]})})))))))
