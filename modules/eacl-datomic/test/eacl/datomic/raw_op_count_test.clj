(ns eacl.datomic.raw-op-count-test
  "Datomic raw-facade logical-work gates over populated recursion.

  The raw impl API is the 0tx consumer surface: bare immutable db values,
  no client caches. Envelopes are the ratcheted numbers recorded in
  formal/baselines/recursive-op-count-envelopes.edn. Every measurement is
  taken from an observer that production writes on this path; the
  self-checks below fail when one of them goes quiet. Per-push, no
  wall-clock assertions."
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
            [eacl.formal.production-kernel :as production]
            [eacl.request.counters :as request-counters]
            [eacl.subproblem-cache :as subproblem]
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
  "Runs one raw request with every observer bound.

  The raw Datomic facade rebinds engine/*recursive-traversal-stats* to
  impl.indexed's dynamic (with-shared-engine), so raw callers observe
  through the impl-level var. :scans is :advanced-datoms, the adapter
  commands the stable engine issued; :crossings sums every generated-kernel
  invocation; :plan-seals is the request ledger's :seals. Denotation key
  builds have no counter of their own, so they are counted by binding a
  counting key constructor."
  [f]
  (let [crossings (atom {}) backend-ops (atom {}) traversal (atom {})
        shape (atom {}) key-builds (atom 0)
        ledger (request-counters/make-ledger)]
    (binding [verified/*kernel-crossing-stats* crossings
              backend/*backend-op-stats* backend-ops
              impl.indexed/*recursive-traversal-stats* traversal
              engine/*request-shape-stats* shape
              request-counters/*ledger* ledger
              subproblem/*exact-denotation-key-fn*
              (fn [_]
                (swap! key-builds inc)
                nil)]
      (f))
    {:proof-frame (get @backend-ops :proof-frame 0)
     :plan-seals (:seals (request-counters/snapshot ledger))
     :key-builds @key-builds
     :path-calcs (get @shape :permission-path-calcs 0)
     :crossings (reduce + (vals @crossings))
     :scans (get @traversal :advanced-datoms 0)
     :derived-grants (get @traversal :derived-grants 0)}))

(defn- assert-crossing-law!
  [render-kind {:keys [crossings scans]}]
  (let [{default-batch-size :batch-size
         page-batch-size :page-batch-size
         :keys [constant fuel]}
        (:crossing-law envelopes)
        batch-size (if (= :page render-kind)
                     page-batch-size
                     default-batch-size)
        batches (quot (+ scans (dec batch-size)) batch-size)
        fuel-yields (quot scans fuel)]
    (is (<= crossings
            (+ (* 2 batches) constant fuel-yields))
        (str (name render-kind)
             " crossings <= 2*ceil(scans/batch)+recorded constant"))))

(deftest raw-lookup-op-count-test
  (let [e (:raw-lookup-first-50 envelopes)
        {:keys [db user-1-eid]} @state
        m (measured
           #(dimpl/lookup-resources
             db
             {:subject {:type :user :id user-1-eid}
              :permission :view :resource/type :account :first 50}))]
    (testing "recursion active and every observer live (suite self-check)"
      (is (pos? (:scans m)) (pr-str m))
      (is (pos? (:derived-grants m)) (pr-str m))
      (is (pos? (:plan-seals m)) (pr-str m))
      (is (pos? (:path-calcs m)) (pr-str m))
      (is (pos? (:crossings m)) (pr-str m)))
    (testing "ordered-generation frames per raw list request"
      (is (<= (:proof-frame m) (:maximum-proof-frame-reads e)) (pr-str m)))
    (testing "plan seals per raw request (ledger :seals)"
      (is (<= (:plan-seals m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "denotation cache-key work against a nil store"
      (is (<= (:key-builds m) (:maximum-denotation-key-builds e)) (pr-str m)))
    (testing "permission path calculations (:permission-path-calcs)"
      (is (<= (:path-calcs m) (:maximum-permission-path-calcs e)) (pr-str m)))
    (testing "early-stop scan envelope (:advanced-datoms)"
      (is (<= (:scans m) (:maximum-backend-scans e)) (pr-str m)))
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
        (is (pos? (:scans m))
            (str label " point check reads the backend " (pr-str m)))
        (is (<= (:plan-seals m) (:maximum-plan-compiles e))
            (str label " plan seals " (pr-str m)))
        (is (zero? (:key-builds m))
            (str label " raw can? builds no denotation keys " (pr-str m)))
        (is (<= (:scans m) (:maximum-backend-scans e))
            (str label " bounded reverse point check " (pr-str m)))
        (assert-crossing-law! :order-independent m)))
    (testing "the key-build counter is live: the same check keys its reuse lookups once a store is bound"
      (is (pos? (:key-builds
                 (binding [subproblem/*store*
                           (subproblem/store {:denotation-max-entries 16
                                              :answer-max-entries 2})]
                   (measured
                    #(dimpl/can? db {:type :user :id user-1-eid} :view
                                 {:type :account :id deep-child-eid})))))))))

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
    (testing "scan count linear in fixture size (:advanced-datoms)"
      (is (pos? (:scans m)) (pr-str m))
      (is (<= (:scans m)
              (+ accounts (:maximum-backend-scans-slack e)))
          (pr-str m)))
    (assert-crossing-law! :order-independent m)))

(deftest interned-empty-response-immutability-test
  ;; 4.2 pin: the interned empty scan-response payload must stay empty
  ;; after the generated validator has been handed it many times —
  ;; generated code mutates only freshly constructed wrappers (the
  ;; collection shims' contract). A fresh interned instance shows that
  ;; these responses, and nothing earlier in the JVM, are what realized it.
  (let [interned (delay (#'production/dafny-sequence []))
        exchange {:command {:request-scope 31
                            :request-id 7
                            :projection {:kind :subject->resources
                                         :subject-type "user"
                                         :subject-eid 1
                                         :relation-eid 2
                                         :resource-type "document"
                                         :bound-eid 10}
                            :chunk-size 3}
                  :response {:request-scope 31
                             :request-id 7
                             :values []
                             :terminal? true
                             :fetched-values 0}}]
    (with-redefs [production/empty-values-sequence interned]
      (is (every? #(= {:status :accepted
                       :values []
                       :terminal? true
                       :fetched-values 0}
                      %)
                  (repeatedly
                   1000
                   #(verified/decide production/default-selection
                                     :indexed-scan-response
                                     exchange)))))
    (is (realized? interned)
        "an empty scan response is handed to the validator as the interned payload")
    (is (zero? (.cardinalityInt ^dafny.DafnySequence @interned))
        "interned empty DafnySequence mutated by generated code")))
