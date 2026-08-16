(ns eacl.datascript.recursive-op-count-test
  "Deterministic logical-work gates over populated recursion.

  Every assertion names the counter it reads and checks it against the
  ratcheted envelope recorded in
  formal/verification/recursive-op-count-envelopes.edn. These are
  per-push tests (no wall-clock assertions; see the benchmark suites for
  latency gates). The fixture self-check guarantees the recursive engine
  is actually exercised — an empty-recursion fixture fails the suite."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as dsb]
            [eacl.datascript.core :as dsc]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.test-support.repo :as repo]
            [eacl.verified-kernel :as verified]))

(def ^:private envelopes
  (-> (repo/file "formal" "verification" "recursive-op-count-envelopes.edn")
      slurp
      edn/read-string
      :work-envelopes))

(def ^:private config {:shape :star :accounts 2000})

(defonce ^:private state (atom nil))

(defn- seed-config!
  ([fixture-config]
   (seed-config! fixture-config {}))
  ([fixture-config client-opts]
   (let [conn (dsc/create-conn)
         client (dsc/make-client conn client-opts)]
     (eacl/write-schema! client (rf/schema-for fixture-config))
     (ds/transact! conn (vec (rf/object-transactions fixture-config)))
     (doseq [batch (rf/relationship-batches fixture-config)]
       (eacl/create-relationships! client (vec batch)))
     (let [db (ds/db conn)
           eid (fn [ext-id] (:e (first (ds/datoms db :avet :eacl/id ext-id))))]
       {:conn conn
        :client client
        :db db
        :eid eid
        :user-1-eid (eid "user-1")
        :stranger-eid (eid "stranger")
        :deep-child-eid (eid (rf/account-id 1500))}))))

(defn- seed-star! []
  (seed-config! config))

(use-fixtures :once
  (fn [run]
    (reset! state (seed-star!))
    (run)))

(defn- measured
  "Runs f with all observer counters bound; returns its result and counters."
  [f]
  (let [kx (atom {}) bops (atom {}) rts (atom {}) shape (atom {})
        work (atom {})]
    (let [result (binding [verified/*kernel-crossing-stats* kx
                           backend/*backend-op-stats* bops
                           engine/*backend-work-stats* work
                           engine/*recursive-traversal-stats* rts
                           engine/*request-shape-stats* shape]
                   (f))]
      {:result result
       :proof-frame (get @bops :proof-frame 0)
       :plan-compiles (get @rts :compiled-recursive-plans 0)
       :path-calcs (get @shape :permission-path-calcs 0)
       :key-builds (get @shape :denotation-key-builds 0)
       :dep-calcs (get @shape :denotation-dependency-calcs 0)
       :backend-operations (get @work :executed-backend-operations 0)
       :drive (get @kx :indexed-traversal-drive 0)
       :resume (get @kx :indexed-traversal-resume 0)
       :stream-fills (get @rts :stream-fills 0)
       :advanced (get @rts :advanced-stream-datoms 0)
       :derived-grants (get @rts :derived-grants 0)
       :continuation-hits (get @rts :continuation-hits 0)})))

(def ^:private deterministic-work-keys
  [:drive :resume :stream-fills :advanced :derived-grants :continuation-hits])

(defn- operation-count-run
  [{:keys [config] :as operation-case}]
  (let [{:keys [db eid]} (seed-config! config)
        {:keys [subject permission resource]} (rf/operation-count-query operation-case)
        adapter (dsb/snapshot-adapter db {})]
    (measured
     #(engine/can? adapter
                   (update subject :id eid)
                   permission
                   (update resource :id eid)))))

(deftest deterministic-operation-count-fixtures-test
  (testing "shallow, deep, negative, cyclic, diamond, mutual, and broad-union cases"
    (doseq [{:keys [case expected-allowed?] :as operation-case}
            rf/operation-count-cases]
      (let [first-run (operation-count-run operation-case)
            second-run (operation-count-run operation-case)]
        (testing (name case)
          (is (= expected-allowed? (:result first-run)) (pr-str first-run))
          (is (= expected-allowed? (:result second-run)) (pr-str second-run))
          (is (= (select-keys first-run deterministic-work-keys)
                 (select-keys second-run deterministic-work-keys))
              (str "logical work must be reproducible: "
                   (pr-str {:first first-run :second second-run})))
          (is (pos? (:stream-fills first-run))
              (str "fixture must exercise recursive traversal: "
                   (pr-str first-run))))))))

(defn- traced
  [f]
  (let [events (atom [])]
    (try
      {:result (binding [engine/*execution-trace* events]
                 (f))
       :events @events}
      (catch clojure.lang.ExceptionInfo error
        {:error (ex-data error)
         :events @events}))))

(defn- point-case-trace
  [operation-case cache? evaluation]
  (let [
        {:keys [client]} (seed-config! (:config operation-case))]
    (traced
     #(eacl/can?
       client
       (assoc (rf/operation-count-query operation-case)
              :cache? cache?
              :evaluation evaluation)))))

(defn- point-trace
  ([cache?]
   (point-trace cache? :demand))
  ([cache? evaluation]
   (point-case-trace
    (second rf/operation-count-cases)
    cache?
    evaluation)))

(defn- normalized-semantic-events
  [events]
  (mapv
   (fn [{:keys [event direction command response stopping-reason
                resource-limit-outcome]}]
     (cond-> {:event event :direction direction}
       command
       (assoc :command (select-keys command [:projection :chunk-size]))

       response
       (assoc :response
              (select-keys response [:values :terminal? :fetched-values]))

       stopping-reason
       (assoc :stopping-reason stopping-reason
              :resource-limit-outcome resource-limit-outcome)))
   (remove #(= :evaluation-start (:event %)) events)))

(deftest cold-cache-and-bypass-trace-shape-test
  (doseq [[label cache? expected-direction]
          [[:cold-cache true :reverse]
           [:cache-bypass false :reverse]]]
    (testing (name label)
      (let [{:keys [result events]} (point-trace cache?)
            starts (filter #(= :evaluation-start (:event %)) events)
            commands (filter #(= :generated-command (:event %)) events)
            responses (filter #(= :adapter-response (:event %)) events)
            stops (filter #(= :evaluation-stop (:event %)) events)]
        (is (true? result) (pr-str {:result result :events events}))
        (is (= [expected-direction] (mapv :direction starts)) (pr-str events))
        (is (= (count commands) (count responses)) (pr-str events))
        (is (pos? (count commands)) (pr-str events))
        (is (every? map? (map :command commands)) (pr-str commands))
        (is (every? map? (map :response responses)) (pr-str responses))
        (is (every? #(= (:fetched-values %)
                        (get-in % [:response :fetched-values]))
                    responses)
            (pr-str responses))
        (is (= [{:status :within-limits}]
               (mapv :resource-limit-outcome stops))
            (pr-str stops))
        (is (= [:target-derived]
               (mapv :stopping-reason stops))
            (pr-str stops))))))

(deftest cold-cache-miss-equals-bypass-semantic-trace-test
  (doseq [operation-case
          [(first rf/operation-count-cases)
           (second rf/operation-count-cases)
           (nth rf/operation-count-cases 2)]]
    (let [cold-cache (point-case-trace operation-case true :demand)
          bypass (point-case-trace operation-case false :demand)]
      (is (= (:result bypass) (:result cold-cache)) (:case operation-case))
      (is (= (normalized-semantic-events (:events bypass))
             (normalized-semantic-events (:events cold-cache)))
          (pr-str {:case (:case operation-case)
                   :bypass (:events bypass)
                   :cold-cache (:events cold-cache)})))))

(deftest explicit-complete-denotation-point-mode-test
  (let [demand (point-trace false :demand)
        complete (point-trace false :complete-denotation)
        complete-start
        (first (filter #(= :evaluation-start (:event %)) (:events complete)))]
    (is (= true (:result demand) (:result complete)))
    (is (= :reverse
           (:direction
            (first (filter #(= :evaluation-start (:event %))
                           (:events demand))))))
    (is (= :forward (:direction complete-start)))
    (is (= :graph-exhausted
           (:stopping-reason
            (last (filter #(= :evaluation-stop (:event %))
                          (:events complete))))))))

(defn- count-trace
  [cache? evaluation count-limit]
  (let [fixture-config {:shape :chain :accounts 20}
        {:keys [client]} (seed-config! fixture-config)]
    (traced
     #(eacl/count-resources
       client
       (cond->
        (assoc (rf/count-query fixture-config rf/user-1)
               :cache? cache?
               :evaluation evaluation)
         (some? count-limit) (assoc :count-limit count-limit))))))

(deftest bounded-count-cache-parity-and-stopping-test
  (let [bypass (count-trace false :demand 3)
        cold-cache (count-trace true :demand 3)]
    (is (= {:count 3 :limit 3 :truncated? true}
           (select-keys (:result bypass) [:count :limit :truncated?])))
    (is (= (select-keys (:result bypass) [:count :limit :truncated?])
           (select-keys (:result cold-cache) [:count :limit :truncated?])))
    (is (= (normalized-semantic-events (:events bypass))
           (normalized-semantic-events (:events cold-cache)))
        (pr-str {:bypass (:events bypass)
                 :cold-cache (:events cold-cache)}))
    (is (= :demand-sentinel
           (:stopping-reason
            (last (filter #(= :evaluation-stop (:event %))
                          (:events bypass))))))))

(deftest exact-and-explicit-complete-count-exhaustion-test
  (let [exact (count-trace false :demand nil)
        complete-bounded (count-trace false :complete-denotation 3)]
    (is (= {:count 20 :limit -1}
           (select-keys (:result exact) [:count :limit])))
    (is (= {:count 3 :limit 3 :truncated? true}
           (select-keys (:result complete-bounded)
                        [:count :limit :truncated?])))
    (doseq [trace [exact complete-bounded]]
      (is (= :graph-exhausted
             (:stopping-reason
              (last (filter #(= :evaluation-stop (:event %))
                            (:events trace)))))))))

(deftest exact-generated-command-response-reuse-test
  (let [operation-case (second rf/operation-count-cases)
        {:keys [client]}
        (seed-config!
         (:config operation-case)
         {:cache {:admit-on-repeat? true}})
        query (assoc (rf/operation-count-query operation-case) :cache? true)
        _ (eacl/can? client query)
        events (atom [])
        second-run
        (measured
         #(binding [engine/*execution-trace* events]
            (eacl/can? client query)))
        stats (orchestration/cache-stats client)]
    (is (true? (:result second-run)))
    (is (pos? (:stream-fills second-run))
        "second-sighting answer admission still runs the evaluator")
    (is (zero? (:backend-operations second-run)) (pr-str second-run))
    (is (pos? (get-in stats [:subproblems :avoided-backend-operations]))
        (pr-str stats))
    (is (pos? (count (filter #(= :generated-command (:event %)) @events))))))

(deftest resource-limit-trace-test
  (let [operation-case (second rf/operation-count-cases)
        run
        (fn [cache?]
          (let [{:keys [client]}
                (seed-config!
                 (:config operation-case)
                 {:recursive-traversal-limits
                  {:max-derived-grants 1
                   :max-advanced-datoms 1
                   :max-queued-work 1}})]
            (traced
             #(eacl/can?
               client
               (assoc (rf/operation-count-query operation-case)
                      :cache? cache?)))))
        bypass (run false)
        cold-cache (run true)]
    (doseq [{:keys [error events]} [bypass cold-cache]]
      (let [stop (last (filter #(= :evaluation-stop (:event %)) events))]
        (is (= :eacl.recursive-traversal/limit-exceeded (:eacl/error error))
            (pr-str {:error error :events events}))
        (is (= :resource-limit-exceeded (:stopping-reason stop)) (pr-str stop))
        (is (= :exceeded (get-in stop [:resource-limit-outcome :status]))
            (pr-str stop))))
    (is (= (:error bypass) (:error cold-cache)))
    (is (= (normalized-semantic-events (:events bypass))
           (normalized-semantic-events (:events cold-cache))))))

(deftest invalid-evaluation-precedes-consistency-and-cache-access-test
  (let [{:keys [client]} (seed-config! {:shape :chain :accounts 3})
        before (orchestration/cache-stats client)
        error
        (try
          (eacl/can?
           client
           {:subject rf/user-1
            :permission :view
            :resource (rf/object :account (rf/account-id 2))
            :evaluation :speculative
            :consistency :not-a-consistency-mode})
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))
        after (orchestration/cache-stats client)]
    (is (= :eacl.execution/invalid-contract (:eacl/error error)) (pr-str error))
    (is (= :evaluation (:key error)) (pr-str error))
    (is (= before after) "invalid evaluation must not touch cache state")))

(defn- raw-adapter []
  (dsb/snapshot-adapter (:db @state) {}))

(defn- assert-crossing-law!
  "Ordered scan-wave crossing law for the populated star fixture."
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
    (testing "ordered-generation frames per raw list request"
      (is (<= (:proof-frame m) (:maximum-proof-frame-reads e)) (pr-str m)))
    (testing "recursive plan compiles per raw request (:compiled-recursive-plans)"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "denotation cache-key work against a nil store"
      (is (<= (:key-builds m) (:maximum-denotation-key-builds e)) (pr-str m))
      (is (<= (:dep-calcs m) (:maximum-denotation-dependency-calcs e)) (pr-str m)))
    (testing "cold permission-path walks (:permission-path-calcs)"
      (is (<= (:path-calcs m) (:maximum-permission-path-calcs e)) (pr-str m)))
    (testing "streaming early-stop scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! :page m)))

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
        (is (<= (:proof-frame m) (:maximum-proof-frame-reads e))
            (str label " :proof-frame " (pr-str m)))
        (is (<= (:plan-compiles m) (:maximum-plan-compiles e))
            (str label " :compiled-recursive-plans " (pr-str m)))
        (is (zero? (:key-builds m))
            (str label " raw can? builds no denotation keys " (pr-str m)))
        (is (<= (:stream-fills m) (:maximum-backend-scans e))
            (str label " bounded reverse point check " (pr-str m)))
        (assert-crossing-law! :order-independent m)))))

(deftest client-lookup-op-count-test
  (let [e (:client-lookup-first-50 envelopes)
        m (measured
           #(eacl/lookup-resources (:client @state)
                                   (rf/resource-query config rf/user-1)))]
    (testing "ordered-generation frames per client list request"
      (is (<= (:proof-frame m) (:maximum-proof-frame-reads e)) (pr-str m)))
    (testing "plan compiles amortized by the client schema cache"
      (is (<= (:plan-compiles m) (:maximum-plan-compiles e)) (pr-str m)))
    (testing "scan envelope (:stream-fills)"
      (is (<= (:stream-fills m) (:maximum-backend-scans e)) (pr-str m)))
    (assert-crossing-law! :page m)))

(deftest client-can-and-bounded-count-remain-demand-bounded-test
  ;; Fresh client so this test owns its cache lifecycle.
  (let [{:keys [client]} (seed-star!)
        e (:client-can envelopes)
        can-m (measured
               #(eacl/can? client rf/user-1 :view
                           (rf/object :account (rf/account-id 1500))))
        count-m (measured
                 #(eacl/count-resources
                   client
                   (assoc (rf/count-query config rf/user-1)
                          :count-limit 1000)))]
    (testing "cache-enabled recursive point checks remain target-anchored"
      (is (<= (:proof-frame can-m) (:maximum-proof-frame-reads e)) (pr-str can-m))
      (is (<= (:drive can-m) (:maximum-kernel-drives e)) (pr-str can-m))
      (is (< (:backend-operations can-m) 16) (pr-str can-m))
      (assert-crossing-law! :order-independent can-m))
    (testing "bounded count performs only its own L+1 demand"
      (is (= {:count 1000 :limit 1000 :truncated? true}
             (select-keys (:result count-m) [:count :limit :truncated?])))
      (is (<= (:backend-operations count-m) 1024) (pr-str count-m))
      (is (pos? (:backend-operations count-m))
          "a point check must not silently precompute the count denotation"))))

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
    (assert-crossing-law! :order-independent m)))

(deftest demand-page-continuation-test
  (let [{:keys [client]} (seed-star!)
        e (:client-lookup-first-50 envelopes)
        first-m (measured
                 #(eacl/lookup-resources
                   client (rf/resource-query config rf/user-1 50)))
        page-1 (:result first-m)
        page-2-m (measured
           #(eacl/lookup-resources client
                                   (assoc (rf/resource-query config rf/user-1 50)
                                          :after (get-in page-1
                                                         [:page-info
                                                          :end-cursor]))))
        page-2 (:result page-2-m)
        ids (mapv :id (concat (:data page-1) (:data page-2)))
        backward-m
        (measured
         #(eacl/lookup-resources
           client
           {:subject rf/user-1
            :permission :view
            :resource/type :account
            :last 10
            :before (get-in page-2 [:page-info :start-cursor])}))]
    (testing "each page performs only N+1 demand"
      (is (<= (:backend-operations first-m)
              (:maximum-backend-scans e)) (pr-str first-m))
      (is (<= (:backend-operations page-2-m)
              (:maximum-later-page-scans e)) (pr-str page-2-m)))
    (testing "retained continuation preserves logical order without overlap"
      (is (= (mapv rf/account-id (range 100)) ids))
      (is (pos? (get-in (dsc/cache-stats client)
                        [:continuations :hits]))))
    (testing "last-before replays only the authenticated prefix with an N window"
      (is (= (mapv rf/account-id (range 40 50))
             (mapv :id (get-in backward-m [:result :data]))))
      (is (<= (:backend-operations backward-m) 64)
          (pr-str backward-m)))))

(deftest cache-disabled-page-replay-is-bounded-and-equivalent-test
  (let [{:keys [client]} (seed-star!)
        query (assoc (rf/resource-query config rf/user-1 25) :cache? false)
        page-1 (eacl/lookup-resources client query)
        page-2-m
        (measured
         #(eacl/lookup-resources
           client
           (assoc query :after (get-in page-1 [:page-info :end-cursor]))))
        page-2 (:result page-2-m)]
    (is (= (mapv rf/account-id (range 50))
           (mapv :id (concat (:data page-1) (:data page-2)))))
    (is (<= (:backend-operations page-2-m) 52)
        "replay plus the requested page consumes only authenticated prefix + N+1")))

(deftest recursive-logical-order-is-chunk-and-page-size-invariant-test
  (let [{:keys [db user-1-eid]} (seed-star!)
        adapter (dsb/snapshot-adapter db {})
        stream-chunk-var (ns-resolve 'eacl.engine.v8 '*stream-chunk-size*)
        query {:subject {:type :user :id user-1-eid}
               :permission :view
               :resource/type :account}
        first-40
        (fn [chunk-size]
          (with-bindings {stream-chunk-var chunk-size}
            (mapv :id (:data (engine/lookup-resources
                              adapter (assoc query :first 40))))))
        page-1 (engine/lookup-resources adapter (assoc query :first 13))
        page-2 (engine/lookup-resources
                adapter
                (assoc query
                       :first 27
                       :after (get-in page-1 [:page-info :end-cursor])))]
    (is (= (first-40 1) (first-40 64)))
    (is (= (first-40 64)
           (mapv :id (concat (:data page-1) (:data page-2)))))))

(deftest recursive-bare-last-requires-explicit-completion-test
  (let [{:keys [client]} (seed-star!)
        query {:subject rf/user-1
               :permission :view
               :resource/type :account
               :last 10}
        data
        (try
          (eacl/lookup-resources client query)
          nil
          (catch clojure.lang.ExceptionInfo error
            (ex-data error)))
        complete
        (eacl/lookup-resources
         client (assoc query :evaluation :complete-denotation))]
    (is (= :eacl.pagination/complete-evaluation-required
           (:eacl/error data)))
    (is (= (mapv rf/account-id (range 1990 2000))
           (mapv :id (:data complete))))))

(deftest deadline-is-checked-after-blocking-adapter-and-render-boundaries-test
  (let [{:keys [db user-1-eid client]} (seed-star!)
        adapter (dsb/snapshot-adapter db {})
        clock (atom 0)
        contract
        (binding [execution/*monotonic-nanos* #(deref clock)]
          (execution/normalize
           {:execution-timeout-ms 10}
           :lookup-resources
           {:first 5}))
        query {:subject {:type :user :id user-1-eid}
               :permission :view
               :resource/type :account
               :first 5}
        adapter-error
        (binding [execution/*contract* contract
                  execution/*monotonic-nanos* #(deref clock)
                  backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (and (= :after phase)
                               (= :subject->resources operation))
                      (reset! clock 10000000)))]
          (try
            (engine/lookup-resources adapter query)
            nil
            (catch clojure.lang.ExceptionInfo error
              (ex-data error))))]
    (is (= :eacl.execution/deadline-exceeded
           (:eacl/error adapter-error)))
    (is (= :adapter-response (:stage adapter-error)))
    (reset! clock 0)
    (let [render-error
          (binding [execution/*monotonic-nanos* #(deref clock)
                    backend/*invoke-observer*
                    (fn [{:keys [phase operation]}]
                      (when (and (= :after phase)
                                 (= :internal-id->object operation))
                        (reset! clock 10000000)))]
            (try
              (eacl/lookup-resources
               client
               {:subject rf/user-1
                :permission :view
                :resource/type :account
                :first 1
                :timeout-ms 10
                :cache? false})
              nil
              (catch clojure.lang.ExceptionInfo error
                (ex-data error))))]
      (is (= :eacl.execution/deadline-exceeded
             (:eacl/error render-error)))
      (is (= :rendered-identity (:stage render-error))))))

(deftest cancellation-is-checked-after-a-running-adapter-command-test
  (let [{:keys [db user-1-eid]} (seed-star!)
        adapter (dsb/snapshot-adapter db {})
        token (eacl/cancellation-token)
        contract
        (execution/normalize
         {:execution-timeout-ms 1000}
         :lookup-resources
         {:first 5 :cancellation-token token})
        query {:subject {:type :user :id user-1-eid}
               :permission :view
               :resource/type :account
               :first 5}
        data
        (binding [execution/*contract* contract
                  backend/*invoke-observer*
                  (fn [{:keys [phase operation]}]
                    (when (and (= :after phase)
                               (= :subject->resources operation))
                      (eacl/cancel! token)))]
          (try
            (engine/lookup-resources adapter query)
            nil
            (catch clojure.lang.ExceptionInfo error
              (ex-data error))))]
    (is (= :eacl.execution/cancelled (:eacl/error data)))
    (is (= :adapter-response (:stage data)))
    (is (not (contains? data :cancellation-token)))))
