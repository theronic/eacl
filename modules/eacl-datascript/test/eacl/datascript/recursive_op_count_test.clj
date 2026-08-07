(ns eacl.datascript.recursive-op-count-test
  "Deterministic logical-work gates over populated recursion.

  Every assertion names the counter it reads and checks it against the
  ratcheted envelope recorded in
  formal/verification/recursive-op-count-envelopes.edn. These are
  per-push tests (no wall-clock assertions; see the benchmark suites for
  latency gates). The fixture self-check guarantees the recursive engine
  is actually exercised — an empty-recursion fixture fails the suite."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as dsb]
            [eacl.datascript.core :as dsc]
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
  (let [conn (dsc/create-conn)
        client (dsc/make-client conn {})]
    (eacl/write-schema! client (rf/schema-for config))
    (ds/transact! conn (vec (rf/object-transactions config)))
    (doseq [batch (rf/relationship-batches config)]
      (eacl/create-relationships! client (vec batch)))
    (let [db (ds/db conn)
          eid (fn [ext-id] (:e (first (ds/datoms db :avet :eacl/id ext-id))))]
      {:conn conn
       :client client
       :db db
       :user-1-eid (eid "user-1")
       :stranger-eid (eid "stranger")
       :deep-child-eid (eid (rf/account-id 1500))})))

(use-fixtures :once
  (fn [run]
    (reset! state (seed-star!))
    (run)))

(defn- measured
  "Runs f with all three observer counters bound; returns the counters."
  [f]
  (let [kx (atom {}) bops (atom {}) rts (atom {})]
    (binding [verified/*kernel-crossing-stats* kx
              backend/*backend-op-stats* bops
              engine/*recursive-traversal-stats* rts]
      (f))
    {:schema-proof (get @bops :schema-proof 0)
     :plan-compiles (get @rts :compiled-recursive-plans 0)
     :path-calcs (get @rts :permission-path-calcs 0)
     :key-builds (get @rts :denotation-key-builds 0)
     :dep-calcs (get @rts :denotation-dependency-calcs 0)
     :drive (get @kx :indexed-traversal-drive 0)
     :resume (get @kx :indexed-traversal-resume 0)
     :stream-fills (get @rts :stream-fills 0)
     :advanced (get @rts :advanced-stream-datoms 0)
     :derived-grants (get @rts :derived-grants 0)
     :continuation-hits (get @rts :continuation-hits 0)}))

(defn- raw-adapter []
  (dsb/snapshot-adapter (:db @state) {}))

(defn- assert-crossing-law!
  "resume == scans; drive <= scans + 1 + fuel-yield allowance."
  [{:keys [drive resume stream-fills advanced]}]
  (let [fuel (get-in envelopes [:crossing-law :fuel])]
    (is (= resume stream-fills)
        "every NeedScan resumes exactly once (:indexed-traversal-resume = :stream-fills)")
    (is (<= drive (+ stream-fills 1 (quot advanced fuel)))
        ":indexed-traversal-drive bounded by scans + 1 + fuel-yield allowance")))

(deftest recursion-actually-exercised-test
  (testing "the fixture drives the genuinely recursive engine (suite self-check)"
    (let [m (measured
             #(engine/lookup-resources
               (raw-adapter)
               {:subject {:type :user :id (:user-1-eid @state)}
                :permission :view :resource/type :account :first 50}))]
      (is (pos? (:stream-fills m)) ":stream-fills nonzero — recursion active")
      (is (pos? (:derived-grants m)) ":derived-grants nonzero — recursion active"))))

(deftest raw-lookup-op-count-test
  (let [e (:raw-lookup-first-50 envelopes)
        m (measured
           #(engine/lookup-resources
             (raw-adapter)
             {:subject {:type :user :id (:user-1-eid @state)}
              :permission :view :resource/type :account :first 50}))]
    (testing "schema proofs per raw list request (:schema-proof)"
      (is (<= (:schema-proof m) (:maximum-schema-proof-reads e)) (pr-str m)))
    (testing "recursive plan compiles per raw request (:compiled-recursive-plans)"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "denotation cache-key work against a nil store"
      (is (<= (:key-builds m) (:maximum-denotation-key-builds e)) (pr-str m))
      (is (<= (:dep-calcs m) (:maximum-denotation-dependency-calcs e)) (pr-str m)))
    (testing "cold permission-path walks (:permission-path-calcs)"
      (is (<= (:path-calcs m) (:maximum-permission-path-calcs e)) (pr-str m)))
    (testing "streaming early-stop scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! m)))

(deftest raw-can-op-count-test
  (let [e (:raw-can envelopes)
        adapter (raw-adapter)
        pos (measured
             #(engine/can? adapter
                           {:type :user :id (:user-1-eid @state)} :view
                           {:type :account :id (:deep-child-eid @state)}))
        neg (measured
             #(engine/can? adapter
                           {:type :user :id (:stranger-eid @state)} :view
                           {:type :account :id (:deep-child-eid @state)}))]
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

(deftest client-lookup-op-count-test
  (let [e (:client-lookup-first-50 envelopes)
        m (measured
           #(eacl/lookup-resources (:client @state)
                                   (rf/resource-query config rf/user-1)))]
    (testing "schema proofs per client list request (:schema-proof)"
      (is (<= (:schema-proof m) (:maximum-schema-proof-reads e)) (pr-str m)))
    (testing "plan compiles amortized by the client schema cache"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! m)))

(deftest client-can-and-count-reuse-test
  ;; Fresh client so this test owns its cache lifecycle.
  (let [{:keys [client]} (seed-star!)
        e (:client-can envelopes)
        can-m (measured
               #(eacl/can? client rf/user-1 :view
                           (rf/object :account (rf/account-id 1500))))
        count-m (measured
                 #(eacl/count-resources client (rf/count-query config rf/user-1)))]
    (testing "cache-enabled recursive point check (documented full-denotation cost)"
      (is (<= (:schema-proof can-m) (:maximum-schema-proof-reads e)) (pr-str can-m))
      (is (<= (:drive can-m) (:maximum-kernel-drives e)) (pr-str can-m))
      (assert-crossing-law! can-m))
    (testing "a published compatible denotation satisfies count with zero indexed crossings"
      (is (zero? (:drive count-m)) (pr-str count-m))
      (is (zero? (:resume count-m)) (pr-str count-m))
      (is (zero? (:stream-fills count-m)) (pr-str count-m)))))

(deftest count-linearity-test
  (let [e (:count-full envelopes)
        m (measured
           #(engine/count-resources
             (raw-adapter)
             {:subject {:type :user :id (:user-1-eid @state)}
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

(deftest continuation-resumption-test
  (let [{:keys [client]} (seed-star!)
        page-1 (eacl/lookup-resources client (rf/resource-query config rf/user-1 50))
        cursor (get-in page-1 [:page-info :end-cursor])
        m (measured
           #(eacl/lookup-resources client
                                   (assoc (rf/resource-query config rf/user-1 50)
                                          :after cursor)))]
    (testing "second page resumes server-side continuation state (:continuation-hits)"
      (is (= 1 (:continuation-hits m)) (pr-str m)))))
