(ns eacl.datomic.raw-op-count-test
  "Datomic raw-facade logical-work gates over populated recursion.

  The raw impl API is the 0tx consumer surface: bare immutable db values,
  no client caches. Envelopes are the ratcheted numbers recorded in
  formal/baselines/recursive-op-count-envelopes.edn (V1/V2 current
  truth: two schema proofs and two plan compiles per raw list request;
  zero proofs per raw point check). Per-push, no wall-clock assertions."
  (:require [clojure.edn :as edn]
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
            [eacl.test-support.repo :as repo]
            [eacl.verified-kernel :as verified]))

(def ^:private envelopes
  (-> (repo/file "formal" "baselines" "recursive-op-count-envelopes.edn")
      slurp
      edn/read-string
      :work-envelopes))

(def ^:private config {:shape :star :accounts 2000})

(defonce ^:private state (atom nil))

(defn- seed-star! []
  (let [uri (str "datomic:mem://recursive-op-count-" (java.util.UUID/randomUUID))
        _ (assert (d/create-database uri))
        conn (d/connect uri)]
    (dschema/install! conn)
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
  (let [kx (atom {}) bops (atom {}) rts (atom {}) shape (atom {})]
    (binding [verified/*kernel-crossing-stats* kx
              backend/*backend-op-stats* bops
              impl.indexed/*recursive-traversal-stats* rts
              engine/*request-shape-stats* shape]
      (f))
    {:proof-frame (get @bops :proof-frame 0)
     :plan-compiles (get @rts :compiled-recursive-plans 0)
     :key-builds (get @shape :denotation-key-builds 0)
     :dep-calcs (get @shape :denotation-dependency-calcs 0)
     :drive (get @kx :indexed-traversal-drive 0)
     :resume (get @kx :indexed-traversal-resume 0)
     :stream-fills (get @rts :stream-fills 0)
     :advanced (get @rts :advanced-datoms 0)
     :derived-grants (get @rts :derived-grants 0)}))

(defn- assert-crossing-law!
  [render-kind {:keys [drive resume stream-fills advanced]}]
  (let [{default-batch-size :batch-size
         page-batch-size :page-batch-size
         :keys [constant fuel]}
        (:crossing-law envelopes)
        batch-size (if (= :page render-kind)
                     page-batch-size
                     default-batch-size)
        batches (quot (+ stream-fills (dec batch-size)) batch-size)
        fuel-yields (quot advanced fuel)]
    (is (<= resume stream-fills)
        "one ordered response wave resumes one or more backend scans")
    (is (<= drive (+ resume 1 fuel-yields))
        ":indexed-traversal-drive bounded by response waves + completion + fuel yields")
    (is (<= (+ drive resume)
            (+ (* 2 batches) constant fuel-yields))
        (str (name render-kind)
             " crossings <= 2*ceil(streams/batch)+recorded constant"))))

(deftest raw-lookup-op-count-test
  (let [e (:raw-lookup-first-50 envelopes)
        {:keys [db user-1-eid]} @state
        m (measured
           #(dimpl/lookup-resources
             db
             {:subject {:type :user :id user-1-eid}
              :permission :view :resource/type :account :first 50}))]
    (testing "recursion active (suite self-check)"
      ;; :stream-fills belonged to the retired streaming engine; the stable
      ;; engine's physical work shows up as :advanced-datoms (commands).
      (is (pos? (:advanced m)) (pr-str m))
      (is (pos? (:derived-grants m)) (pr-str m)))
    (testing "ordered-generation frames per raw list request"
      (is (<= (:proof-frame m) (:maximum-proof-frame-reads e)) (pr-str m)))
    (testing "recursive plan compiles per raw request (:compiled-recursive-plans)"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "denotation cache-key work against a nil store"
      (is (<= (:key-builds m) (:maximum-denotation-key-builds e)) (pr-str m))
      (is (<= (:dep-calcs m) (:maximum-denotation-dependency-calcs e)) (pr-str m)))
    (testing "streaming early-stop scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! :page m)))

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
        (is (<= (:proof-frame m) (:maximum-proof-frame-reads e))
            (str label " :proof-frame " (pr-str m)))
        (is (<= (:plan-compiles m) (:maximum-plan-compiles e))
            (str label " :compiled-recursive-plans " (pr-str m)))
        (is (zero? (:key-builds m))
            (str label " raw can? builds no denotation keys " (pr-str m)))
        (is (<= (:stream-fills m) (:maximum-backend-scans e))
            (str label " bounded reverse point check " (pr-str m)))
        (assert-crossing-law! :order-independent m)))))

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
      ;; The count exhausts root + N accounts; admissions stay linear with
      ;; the same factor over the result count, not the account count.
      (is (<= (:derived-grants m)
              (* (:maximum-derived-grants-factor e) (inc accounts)))
          (pr-str m)))
    (testing "scan count linear in fixture size (:stream-fills)"
      (is (<= (:stream-fills m)
              (+ accounts (:maximum-backend-scans-slack e)))
          (pr-str m)))
    (assert-crossing-law! :order-independent m)))

(deftest interned-empty-response-immutability-test
  ;; 4.2 pin: the interned empty scan-response payload must stay empty
  ;; after traversal storms — generated code mutates only freshly
  ;; constructed wrappers (the collection shims' contract). The suite's
  ;; other tests have already driven thousands of empty responses
  ;; through the interned instance by the time this runs.
  (let [{:keys [db user-1-eid]} @state]
    (dimpl/count-resources
     db {:subject {:type :user :id user-1-eid}
         :permission :view :resource/type :account})
    (is (zero? (.cardinalityInt
                ^dafny.DafnySequence
                @@(resolve 'eacl.formal.production-kernel/empty-values-sequence)))
        "interned empty DafnySequence mutated by generated code")))
