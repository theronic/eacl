(ns eacl.formal.verified-authority-suite
  "Runs the public JVM suites with generated authority injected into every
  backend client through a formal-only default-kernel seam.

  The suite records generated calls by backend. A value-only pass is
  insufficient: zero calls for any backend fails the cutover gate because it
  demonstrates that the harness did not exercise generated authority."
  (:require
   [clojure.test :as test]
   [eacl.datahike.core :as datahike]
   [eacl.datascript.core :as datascript]
   [eacl.datomic.core :as datomic]
   [eacl.formal.production-kernel :as production]
   [eacl.verified-kernel :as verified]))

(def nonbenchmark-namespaces
  '[eacl.datahike.adapter-certification-test
    eacl.datahike.backend-test
    eacl.datahike.consistency-v3-test
    eacl.datahike.contract-test
    eacl.datahike.storage-test
    eacl.datascript.adapter-certification-test
    eacl.datascript.consistency-v3-test
    eacl.datascript.contract-test
    eacl.datascript.impl-test
    eacl.datascript.storage-test
    eacl.datomic.adapter-certification-test
    eacl.datomic.api-contract-test
    eacl.datomic.backend-test
    eacl.datomic.cache-differential-test
    eacl.datomic.cache-model-test
    eacl.datomic.cache-review-regressions-test
    eacl.datomic.config-test
    eacl.datomic.consistency-cache-test
    eacl.datomic.consistency-v3-test
    eacl.datomic.contract-test
    eacl.datomic.differential-test
    eacl.datomic.impl.indexed-test
    eacl.datomic.lookup-cache-test
    eacl.datomic.object-deletion-test
    eacl.datomic.parser-test
    eacl.datomic.permission-check-test
    eacl.datomic.recursive-cache-test
    eacl.datomic.schema-basis-test
    eacl.datomic.schema-test
    eacl.datomic.trusted-surface-audit-test
    eacl.datomic.v8-characterization-test
    eacl.migrations.v6-to-v7-test
    eacl.spice-test
    eacl.backend.v8-test
    eacl.cache-test
    eacl.causal-model-test
    eacl.characterization-fixture-test
    eacl.consistency-test
    eacl.engine.relationships-test
    eacl.formal.cache-strategy-adversarial-test
    eacl.formal.counterexample-replay-test
    eacl.formal.dafny-cleanup-gate-test
    eacl.formal.differential-runner-test
    eacl.formal.generators-test
    eacl.formal.mutation-control-test
    eacl.formal.public-source-closure-test
    eacl.relationships.endpoint-pair-test
    eacl.relay-test
    eacl.secure-format-test
    eacl.subproblem-cache-test
    eacl.verified-kernel-test])

(def heavy-namespaces
  '[eacl.bench.cross-backend-workload-test
    eacl.bench.managed-proof-cost-test
    eacl.bench.pagination-test
    eacl.bench.subproblem-cache-test])

(def backends
  [:datomic :datahike :datascript])

(def required-generated-authority-operations
  "The generated decision authority the stable-discovery design still
  routes through on every backend: cursor continuation and relationship
  paging. Consistency selection/validation moved to the host-native
  portable decision procedure (the generated model remains its offline
  differential oracle), and the retired traversal authorities
  (:enumeration-route, :acyclic-*, :indexed-traversal-*) left with the
  engines they governed."
  #{:cursor-continuation
    :relationship-page})

(defn- count-call!
  [calls backend operation]
  (swap! calls update-in [backend :generated-calls operation] (fnil inc 0)))

(defrecord CountingKernel [delegate backend calls]
  verified/DecisionKernel
  (-decide [_ operation input]
    (count-call! calls backend operation)
    (verified/-decide delegate operation input))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (count-call! calls backend :indexed-traversal-compile)
    (verified/-compile-indexed-plan delegate input))
  (-initialize-indexed [_ direction input]
    (count-call! calls backend :indexed-traversal-initialize)
    (verified/-initialize-indexed delegate direction input))
  (-drive-indexed [_ direction state limits fuel]
    (count-call! calls backend :indexed-traversal-drive)
    (verified/-drive-indexed delegate direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (count-call! calls backend :indexed-traversal-resume)
    (verified/-resume-indexed delegate direction state response limits))
  (-continue-indexed-page [_ direction state input]
    (count-call! calls backend :indexed-traversal-continue)
    (verified/-continue-indexed-page delegate direction state input))
  (-read-indexed-result [_ direction state]
    (count-call! calls backend :indexed-traversal-read)
    (verified/-read-indexed-result delegate direction state)))

(defn- verified-selection
  [selection backend calls]
  {:kernel
   (->CountingKernel (:kernel selection) backend calls)})

(defn- injecting-constructor
  [backend constructor calls]
  (fn [connection options]
    (swap! calls update-in [backend :injected-clients] (fnil inc 0))
    ;; Preserve any fixture-local generated wrapper and add the suite counter
    ;; around the selection visible at the actual constructor call.
    (let [selection
          (verified-selection production/default-selection backend calls)]
      (with-redefs
       [production/default-selection selection]
        (constructor connection (or options {}))))))

(defn- load-namespaces!
  [namespaces]
  (doseq [test-namespace namespaces]
    (require test-namespace :reload)))

(defn- backend-generated-call-count
  [calls backend]
  (reduce
   +
   0
   (vals (get-in calls [backend :generated-calls] {}))))

(defn- assert-cutover!
  [summary calls required-operations]
  (let [failed-tests (+ (:fail summary 0) (:error summary 0))
        missing-clients
        (filterv
         #(zero? (get-in calls [% :injected-clients] 0))
         backends)
        missing-generated-calls
        (filterv
         #(zero? (backend-generated-call-count calls %))
         backends)
        missing-required-operations
        (into
         {}
         (keep
          (fn [backend]
            (let [missing
                  (filterv
                   #(zero?
                     (get-in
                      calls
                      [backend :generated-calls %]
                      0))
                   required-operations)]
              (when (seq missing)
                [backend missing]))))
         backends)]
    (when (or (pos? failed-tests)
              (seq missing-clients)
              (seq missing-generated-calls)
              (seq missing-required-operations))
      (throw
       (ex-info
        "Verified-authority JVM cutover suite failed."
        {:summary summary
         :missing-injected-client-backends missing-clients
         :missing-generated-call-backends missing-generated-calls
         :missing-required-generated-operations
         missing-required-operations
         :calls calls}))))
  {:status :passed
   :summary summary
   :authority calls})

(defn run-suite!
  [namespaces required-operations]
  (load-namespaces! namespaces)
  (let [calls (atom {})
        datomic-constructor datomic/make-client
        datahike-constructor datahike/make-client
        datascript-constructor datascript/make-client]
    (with-redefs
     [datomic/make-client
      (injecting-constructor :datomic datomic-constructor calls)
      datahike/make-client
      (injecting-constructor :datahike datahike-constructor calls)
      datascript/make-client
      (injecting-constructor :datascript datascript-constructor calls)]
      (let [summary (apply test/run-tests namespaces)]
        (assert-cutover!
         summary
         @calls
         required-operations)))))

(defn run-nonbenchmark!
  []
  (run-suite!
   nonbenchmark-namespaces
   required-generated-authority-operations))

(defn run-heavy!
  []
  ;; Heavy suites must also prove the generated authority executed: the
  ;; empty set made the cutover assertion vacuous for benchmark runs.
  (run-suite! heavy-namespaces
              required-generated-authority-operations))
