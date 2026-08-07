(ns eacl.datomic.raw-op-count-test
  "Datomic raw-facade logical-work gates over populated recursion.

  The raw impl API is the 0tx consumer surface: bare immutable db values,
  no client caches. Envelopes are the ratcheted numbers recorded in
  formal/verification/recursive-op-count-envelopes.edn (V1/V2 current
  truth: two schema proofs and two plan compiles per raw list request;
  zero proofs per raw point check). Per-push, no wall-clock assertions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.core :as eacl]
            [eacl.datomic.core :as dc]
            [eacl.datomic.impl :as dimpl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as dschema]
            [eacl.engine.v8 :as engine]
            [eacl.verified-kernel :as verified]))

(def ^:private envelopes
  (-> (io/file "formal/verification/recursive-op-count-envelopes.edn")
      slurp
      edn/read-string
      :work-envelopes))

(def ^:private config {:shape :star :accounts 2000})

(defonce ^:private state (atom nil))

(defn- seed-star! []
  (let [uri (str "datomic:mem://recursive-op-count-" (java.util.UUID/randomUUID))
        _ (assert (d/create-database uri))
        conn (d/connect uri)]
    @(d/transact conn dschema/v7-schema)
    (let [client (dc/make-client
                  conn
                  {:entid->object-id
                   (fn [snapshot internal-id]
                     (:eacl/id (d/entity snapshot internal-id)))})]
      (eacl/write-schema! client (rf/schema-for config))
      @(d/transact conn (vec (rf/object-transactions config)))
      (doseq [batch (rf/relationship-batches config)]
        (eacl/create-relationships! client (vec batch)))
      (let [db (d/db conn)
            eid (fn [ext-id] (d/entid db [:eacl/id ext-id]))]
        {:uri uri
         :conn conn
         :db db
         :user-1-eid (eid "user-1")
         :stranger-eid (eid "stranger")
         :deep-child-eid (eid (rf/account-id 1500))}))))

(use-fixtures :once
  (fn [run]
    (reset! state (seed-star!))
    (try
      (run)
      (finally
        (d/release (:conn @state))
        (d/delete-database (:uri @state))))))

(defn- measured
  "The raw Datomic facade rebinds engine/*recursive-traversal-stats* to
  impl.indexed's dynamic (with-shared-engine), so raw callers observe
  through the impl-level var."
  [f]
  (let [kx (atom {}) bops (atom {}) rts (atom {})]
    (binding [verified/*kernel-crossing-stats* kx
              backend/*backend-op-stats* bops
              impl.indexed/*recursive-traversal-stats* rts]
      (f))
    {:schema-proof (get @bops :schema-proof 0)
     :schema-proof-computations (get @bops :schema-proof-computations 0)
     :plan-compiles (get @rts :compiled-recursive-plans 0)
     :key-builds (get @rts :denotation-key-builds 0)
     :dep-calcs (get @rts :denotation-dependency-calcs 0)
     :drive (get @kx :indexed-traversal-drive 0)
     :resume (get @kx :indexed-traversal-resume 0)
     :stream-fills (get @rts :stream-fills 0)
     :advanced (get @rts :advanced-stream-datoms 0)
     :derived-grants (get @rts :derived-grants 0)}))

(defn- assert-crossing-law!
  [{:keys [drive resume stream-fills advanced]}]
  (let [fuel (get-in envelopes [:crossing-law :fuel])]
    (is (= resume stream-fills)
        "every NeedScan resumes exactly once (:indexed-traversal-resume = :stream-fills)")
    (is (<= drive (+ stream-fills 1 (quot advanced fuel)))
        ":indexed-traversal-drive bounded by scans + 1 + fuel-yield allowance")))

(deftest raw-lookup-op-count-test
  (let [e (:raw-lookup-first-50 envelopes)
        {:keys [db user-1-eid]} @state
        m (measured
           #(dimpl/lookup-resources
             db
             {:subject {:type :user :id user-1-eid}
              :permission :view :resource/type :account :first 50}))]
    (testing "recursion active (suite self-check)"
      (is (pos? (:stream-fills m)) (pr-str m))
      (is (pos? (:derived-grants m)) (pr-str m)))
    (testing "schema proofs per raw list request (:schema-proof)"
      (is (<= (:schema-proof m) (:maximum-schema-proof-reads e)) (pr-str m)))
    (testing "memoized proof computations per raw list request"
      (is (<= (:schema-proof-computations m)
              (:maximum-schema-proof-computations e))
          (pr-str m)))
    (testing "recursive plan compiles per raw request (:compiled-recursive-plans)"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "denotation cache-key work against a nil store"
      (is (<= (:key-builds m) (:maximum-denotation-key-builds e)) (pr-str m))
      (is (<= (:dep-calcs m) (:maximum-denotation-dependency-calcs e)) (pr-str m)))
    (testing "streaming early-stop scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! m)))

(deftest raw-can-op-count-test
  (let [e (:raw-can envelopes)
        {:keys [db user-1-eid stranger-eid deep-child-eid]} @state
        pos (measured
             #(dimpl/can? db {:type :user :id user-1-eid} :view
                          {:type :account :id deep-child-eid}))
        neg (measured
             #(dimpl/can? db {:type :user :id stranger-eid} :view
                          {:type :account :id deep-child-eid}))]
    (doseq [[label m] [[:positive pos] [:negative neg]]]
      (testing (str label " raw point check")
        (is (<= (:schema-proof m) (:maximum-schema-proof-reads e))
            (str label " :schema-proof " (pr-str m)))
        (is (<= (:plan-compiles m) (:maximum-plan-compiles e))
            (str label " :compiled-recursive-plans " (pr-str m)))
        (is (zero? (:key-builds m))
            (str label " raw can? builds no denotation keys " (pr-str m)))
        (is (<= (:stream-fills m) (:maximum-backend-scans e))
            (str label " bounded reverse point check " (pr-str m)))
        (assert-crossing-law! m)))))

(deftest raw-count-linearity-test
  (let [e (:count-full envelopes)
        {:keys [db user-1-eid]} @state
        m (measured
           #(dimpl/count-resources
             db
             {:subject {:type :user :id user-1-eid}
              :permission :view :resource/type :account}))
        accounts (:accounts config)]
    (testing "derived grants linear in fixture size (:derived-grants)"
      (is (<= (:derived-grants m)
              (* (:maximum-derived-grants-factor e) accounts))
          (pr-str m)))
    (testing "scan count linear in fixture size (:stream-fills)"
      (is (<= (:stream-fills m)
              (+ accounts (:maximum-backend-scans-slack e)))
          (pr-str m)))
    (assert-crossing-law! m)))
