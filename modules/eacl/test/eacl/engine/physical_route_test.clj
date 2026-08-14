(ns eacl.engine.physical-route-test
  "Section 7/8 gates: three-outcome classification with atomic
  partial-output discard (the remaining 3.4 failure-integration control),
  retry under the original absolute deadline, service-edge admission and
  the replay ledger, cooperative cancellation through the page path,
  topology capability validation, anchored point checks and exhaustion
  counts against the frozen baselines, and the binding local perf gate."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.baseline.capture :as capture]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.engine.physical :as physical]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-page :as page]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]
            [eacl.execution :as execution]))

(defn- seeded
  [fixture-key]
  (let [fixture ((get capture/fixtures fixture-key))
        {:keys [conn]} (capture/seed-client! fixture)
        db (ds/db conn)
        adapter (datascript-backend/snapshot-adapter
                 db
                 {:object-id->entid
                  (fn [snapshot object-id]
                    (ds/entid snapshot [:eacl/id object-id]))
                  :entid->object-id
                  (fn [snapshot internal-id]
                    (:eacl/id (ds/entity snapshot internal-id)))
                  :conn conn
                  :source-lifecycle (str "physical-route-" (name fixture-key))})]
    {:fixture fixture :db db :adapter adapter
     :plan (sealed-plan/seal-plan adapter [(:resource-type fixture)
                                           (:permission fixture)])}))

(defn- error-key [thunk]
  (try (thunk) nil
       (catch clojure.lang.ExceptionInfo e (:eacl/error (ex-data e)))))

(deftest classification-and-atomic-discard-test
  (let [env (seeded :folder-chain)
        base-fetch (reducer/adapter-fetch-fn (:adapter env))
        run (fn [fetch-fn]
              (mapv #(:eacl/id (ds/entity (:db env) %))
                    (:results (reducer/run-forward
                               {:fetch-fn fetch-fn
                                :plan (:plan env)
                                :subject-type :user
                                :subject-eid (ds/entid (:db env)
                                                       [:eacl/id "alice"])
                                :target 1000}))))
        reference (run (physical/classified-fetch-fn base-fetch))]
    (testing "an unclassified adapter exception becomes a typed retryable failure"
      (let [boom (physical/classified-fetch-fn
                  (fn [_] (throw (RuntimeException. "socket reset"))))
            error (try (run boom) nil
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= :eacl.scan/failure (:eacl/error error)))
        (is (= :retryable (:classification error)))
        (is (= "java.lang.RuntimeException" (:cause-class error)))))
    (testing "mutation control: partial output is discarded atomically"
      ;; A lazy scan that yields two values then dies mid-stream: the
      ;; classification boundary realizes inside the try, so nothing
      ;; partial ever reaches the reducer, and the retried request
      ;; produces the exact reference sequence — no skips, no duplicates.
      (let [calls (atom 0)
            flaky (physical/classified-fetch-fn
                   (fn [descriptor]
                     (if (= 1 (swap! calls inc))
                       (concat [999999 999998]
                               (lazy-seq
                                (throw (RuntimeException. "mid-stream"))))
                       (base-fetch descriptor))))
            first-attempt (error-key #(run flaky))
            second-attempt (run flaky)]
        (is (= :eacl.scan/failure first-attempt))
        (is (= reference second-attempt)
            "no partial value from the failed attempt was integrated")))))

(deftest retry-preserves-the-semantic-read-test
  (let [env (seeded :folder-chain)
        base-fetch (reducer/adapter-fetch-fn (:adapter env))
        subject-eid (ds/entid (:db env) [:eacl/id "alice"])
        run (fn [fetch-fn]
              (:results (reducer/run-forward
                         {:fetch-fn fetch-fn :plan (:plan env)
                          :subject-type :user :subject-eid subject-eid
                          :target 1000})))]
    (testing "retryable failures retry the same descriptor and succeed"
      (let [failures (atom 2)
            attempts (atom 0)
            descriptors (atom [])
            flaky (fn [descriptor]
                    (swap! descriptors conj (dissoc descriptor :limit))
                    (if (pos? @failures)
                      (do (swap! failures dec)
                          (throw (RuntimeException. "transient")))
                      (base-fetch descriptor)))
            wrapped (physical/retrying-fetch-fn
                     flaky {:max-attempts 3 :attempts attempts})
            results (run wrapped)]
        (is (seq results))
        (is (= 20 (count results)))
        (testing "retries reuse the exact descriptor"
          (is (= 1 (count (distinct (take 3 @descriptors))))))
        (testing "attempts are counted separately from logical commands"
          (is (> @attempts (count (distinct @descriptors)))))))
    (testing "terminal classifications never retry"
      (let [calls (atom 0)
            terminal (fn [_]
                       (swap! calls inc)
                       (throw (ex-info "corrupt node"
                                       {:classification :terminal})))
            wrapped (physical/retrying-fetch-fn terminal {:max-attempts 5})]
        (is (= :eacl.scan/failure (error-key #(run wrapped))))
        (is (= 1 @calls))))
    (testing "an expired original deadline stops retrying immediately"
      (let [calls (atom 0)
            flaky (fn [_]
                    (swap! calls inc)
                    (throw (RuntimeException. "transient")))
            wrapped (physical/retrying-fetch-fn
                     flaky {:max-attempts 5
                            :deadline-nanos (- (execution/now-nanos)
                                               1000000)})]
        (is (= :eacl.scan/failure (error-key #(run wrapped))))
        (is (= 1 @calls))))))

(deftest service-admission-test
  (testing "the enumeration bulkhead holds slots for the full duration"
    (let [admission (physical/make-service-admission {:max-concurrent 1})]
      (is (= :done
             (physical/with-admission
              admission
              (fn []
                (is (= :eacl.service/admission-rejected
                       (error-key
                        #(physical/with-admission admission
                           (constantly :second)))))
                :done))))
      (is (= :again (physical/with-admission admission
                      (constantly :again)))
          "the slot is released after the work returns")))
  (testing "the replay ledger bounds per-key concurrent replays"
    (let [admission (physical/make-service-admission
                     {:max-replays-per-key 1})]
      (is (= :done
             (physical/with-replay-admission
              admission "k"
              (fn []
                (is (= :eacl.service/replay-rejected
                       (error-key
                        #(physical/with-replay-admission admission "k"
                           (constantly :nested)))))
                (is (= :other (physical/with-replay-admission
                               admission "other" (constantly :other))))
                :done)))))))

(deftest cancellation-through-the-page-path-test
  (let [env (seeded :explorer-acyclic)
        token (doto (execution/cancellation-token) (execution/cancel!))
        context (execution/normalize {} :lookup-resources
                                     {:cancellation-token token})
        options {:adapter (:adapter env) :plan (:plan env)
                 :direction :forward
                 :anchor [:user "super-user"] :subject-type :user
                 :page-size 5
                 :security-key "physical-route-test-key-0123456789ab"
                 :cut-point! (physical/execution-cut-point context)}]
    (testing "a cancelled request stops at the next cut point, unpublished"
      (is (= :eacl.execution/cancelled (error-key #(page/page options)))))
    (testing "the same request without the signal still publishes"
      (is (= 5 (count (:data (page/page (dissoc options :cut-point!)))))))))

(deftest topology-capabilities-test
  (testing "unknown keys are rejected and defaults are conservative"
    (is (= :eacl.topology/invalid-capabilities
           (error-key #(physical/topology-capabilities {:warp-speed? true}))))
    (is (= :eacl.topology/invalid-capabilities
           (error-key #(physical/topology-capabilities
                        {:deployment-width 4}))))
    (let [capabilities (physical/topology-capabilities {})]
      (is (= 1 (:deployment-width capabilities)))
      (is (false? (:immutable-basis? capabilities)))
      (is (not (physical/stable-discovery-qualified? capabilities)))))
  (testing "a fully certified local topology qualifies at width one"
    (is (physical/stable-discovery-qualified?
         (physical/topology-capabilities
          {:immutable-basis? true :strict-scan-order? true
           :unique-scan-values? true :replayable-scans? true
           :strict-progress? true :atomic-response? true
           :failure-classification? true
           :semantic-concurrent-read-safe? true})))))

(deftest anchored-point-check-test
  (doseq [fixture-key [:explorer-acyclic :folder-chain :mutual-mixed
                       :cyclic-data :broad-union]]
    (testing (str fixture-key)
      (let [env (seeded fixture-key)
            snapshot (capture/read-snapshot fixture-key)]
        (doseq [[principal-key samples] (:points snapshot)
                [sample-key {:keys [resource can?]}] samples]
          (let [[_ resource-id] (string/split resource #":" 2)
                principal (get-in (:fixture env)
                                  [:principals principal-key])]
            (is (= can?
                   (route/check {:adapter (:adapter env) :plan (:plan env)
                                 :subject-type :user
                                 :subject-id (:id principal)
                                 :resource-id resource-id}))
                (str fixture-key " " principal-key " " sample-key))))))))

(deftest exhaustion-count-test
  (doseq [fixture-key [:explorer-acyclic :group-star :broad-union]]
    (testing (str fixture-key)
      (let [env (seeded fixture-key)
            snapshot (capture/read-snapshot fixture-key)]
        (doseq [[principal-key principal] (:principals (:fixture env))]
          (let [frozen (get-in snapshot [:forward principal-key :count])]
            (is (= (:count frozen)
                   (:count (route/count-resources
                            {:adapter (:adapter env) :plan (:plan env)
                             :subject-type :user
                             :subject-id (:id principal)})))
                (str fixture-key " " principal-key))))
        (testing "count-limit truncates explicitly"
          (let [principal (val (first (:principals (:fixture env))))
                full (:count (route/count-resources
                              {:adapter (:adapter env) :plan (:plan env)
                               :subject-type :user
                               :subject-id (:id principal)}))]
            (when (> full 2)
              (is (= {:count 2 :limit 2 :truncated? true}
                     (route/count-resources
                      {:adapter (:adapter env) :plan (:plan env)
                       :subject-type :user
                       :subject-id (:id principal)
                       :count-limit 2}))))))))))

(deftest local-perf-gate-test
  ;; Task 8.5, CLJ half: the binding local budgets against the frozen
  ;; current-engine perf baseline. Warm medians compare compute against
  ;; compute-or-cache: the absolute 0.25 ms grace covers the difference
  ;; between the new engine's full recompute and the legacy engine's
  ;; cache-assisted repeats; allocation is bounded against the legacy
  ;; public-engine full-compute envelope.
  (let [frozen (read-string (slurp "exploration/baselines/perf-clj-datascript.edn"))
        legacy-warm-ms (get-in frozen [:acyclic-2k-servers
                                       :super-user-first-page-20 :median-ms])
        env (seeded :explorer-acyclic)
        subject-eid (ds/entid (:db env) [:eacl/id "super-user"])
        run #(reducer/run-forward {:adapter (:adapter env) :plan (:plan env)
                                   :subject-type :user
                                   :subject-eid subject-eid :target 21})
        _ (dotimes [_ 20] (run))
        bean (java.lang.management.ManagementFactory/getThreadMXBean)
        thread-id (.getId (Thread/currentThread))
        samples (vec (sort (for [_ (range 40)]
                             (let [t0 (System/nanoTime)]
                               (run)
                               (- (System/nanoTime) t0)))))
        median-ms (/ (nth samples 20) 1e6)
        alloc-before (.getThreadAllocatedBytes
                      ^com.sun.management.ThreadMXBean bean thread-id)
        _ (run)
        allocated (- (.getThreadAllocatedBytes
                      ^com.sun.management.ThreadMXBean bean thread-id)
                     alloc-before)]
    (testing "median latency within legacy warm + 0.25 ms absolute grace"
      (is (<= median-ms (+ legacy-warm-ms 0.25))
          (str "median " median-ms " ms vs legacy warm " legacy-warm-ms)))
    (testing "allocation within the legacy full-compute envelope"
      ;; legacy public-engine first pages allocated 1.6-7.3 MB
      ;; (BENCHMARK_PROTOCOL); the gate binds 1.5x the lower envelope edge
      (is (<= allocated (* 1.5 1600000))
          (str "allocated " allocated " bytes")))
    (testing "no linear scaling with unvisited branches"
      ;; the dense first page must not touch every server: commands stay
      ;; far below the 36-server denotation x 4 permission paths
      (let [finished (run)]
        (is (< (:commands finished) 40))))))
