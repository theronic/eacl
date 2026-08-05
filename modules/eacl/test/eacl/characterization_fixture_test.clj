(ns eacl.characterization-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.subproblem-cache :as subproblem]
            [eacl.test-support.repo :as repo]))

(def ^:private fixture-path
  (repo/file "formal" "characterization" "v1" "eacl-engine.edn"))

(def ^:private performance-gates-path
  (repo/file "formal" "verification" "performance-gates.edn"))

(def ^:private conversion-boundary-path
  (repo/file "formal" "verification" "conversion-boundary.edn"))

(defn- load-fixture
  []
  (edn/read-string (slurp fixture-path)))

(defn- available-evidence
  [evidence]
  (when (repo/evidence-namespace-available? evidence)
    (requiring-resolve evidence)))

(defn- resource-projection
  [grants subject permission resource-type]
  (->> grants
       (keep (fn [[candidate-subject candidate-permission resource]]
               (when (and (= subject candidate-subject)
                          (= permission candidate-permission)
                          (= resource-type (:type resource)))
                 resource)))
       (sort-by :id)
       vec))

(deftest formal-cljs-smoke-preserves-persistent-nrepl-executors-test
  (let [runner
        (slurp (repo/file "formal" "smoke" "cljs" "run"))
        workflow
        (slurp (repo/file ".github" "workflows" "formal.yml"))]
    (is (str/includes? runner "cljs.build.api"))
    (is (not (str/includes? runner "(cljs/-main")))
    (is (str/includes?
         runner
         "@(future :eacl.formal/agent-executor-alive)"))
    (is (str/includes? workflow "cljs.build.api"))
    (is (not (str/includes? workflow "(cljs/-main")))))

(deftest formal-ci-isolates-and-stops-performance-nrepls-test
  (let [workflow
        (slurp (repo/file ".github" "workflows" "formal.yml"))
        index
        (fn [needle]
          (.indexOf workflow needle))
        ordered-stages
        ["Start heap-bounded generated-boundary nREPL"
         "Gate generated JavaScript indexed traversal scaling"
         "Restart heap-bounded nREPL for runtime performance suites"
         "eacl.formal.verified-authority-suite/run-heavy!"
         "eacl.formal.verified-authority-suite/run-nonbenchmark!"
         "Restart heap-bounded nREPL for generated resource gates"
         "Gate routing-certificate logical work and JVM allocation"
         "Gate generated consistency-boundary overhead"
         "Restart heap-bounded nREPL for cursor resource gate"
         "Gate recoverable cursor rebase work and host resources"
         "Stop CI nREPL"]]
    (is (= 4
           (count
            (re-seq
             #"JAVA_TOOL_OPTIONS='-Xms128m -Xmx1024m'"
             workflow))))
    (is (str/includes? workflow
                       "echo \"$!\" > target/formal/ci-nrepl.pid"))
    (is (str/includes? workflow "if: always()"))
    (is (str/includes?
         workflow
         "kill -KILL \"$nrepl_pid\" 2>/dev/null || true"))
    (is (every? (comp not neg? index) ordered-stages))
    (is (apply < (map index ordered-stages)))))

(defn- source-definitions
  [path]
  (into
   #{}
   (map (comp symbol second))
   (re-seq
    #"\(defn-?\s+([^\s\[\(\)]+)"
    (slurp (repo/file path)))))

(deftest generated-conversion-boundary-is-complete-and-cross-runtime-test
  (let [{:keys [status categories runtimes strict-boundary]}
        (edn/read-string (slurp conversion-boundary-path))
        required-categories
        #{:schema-ir :relationships :queries :adapter-callbacks
          :cache-and-cursors :results :typed-errors}
        required-converters
        (into #{} cat (vals categories))
        runtime-definitions
        (into
         {}
         (map
          (fn [[runtime {:keys [source]}]]
            [runtime (source-definitions source)]))
         runtimes)]
    (is (= :passed status))
    (is (= required-categories (set (keys categories))))
    (is (= #{:clj-java :cljs-javascript}
           (set (keys runtimes))))
    (is (every? symbol? required-converters))
    (doseq [[runtime definitions] runtime-definitions]
      (is (every? definitions required-converters)
          (str runtime " is missing required converters: "
               (pr-str
                (sort
                 (remove definitions required-converters))))))
    (is (= #{:input-validation :result-validation
             :unknown-field-rejection :safe-integer-validation
             :bounded-collection-validation
             :complete-portable-error-comparison}
           (set strict-boundary)))))

(deftest generated-java-boundary-is-reflection-free-test
  (when
   (try
     (Class/forName "IndexedTraversal.ForwardStep")
     true
     (catch ClassNotFoundException _
       false))
    (let [source
          (slurp
           (repo/file
            "modules" "eacl" "src" "eacl" "formal"
            "production_kernel.clj"))
          audit-namespace
          (symbol
           (str
            "eacl.formal.production-kernel-reflection-audit-"
            (gensym)))
          isolated-source
          (str/replace-first
           source
           "(ns eacl.formal.production-kernel"
           (str "(ns " audit-namespace))
          warnings (java.io.StringWriter.)]
      (try
        (binding [*warn-on-reflection* true
                  *err* warnings]
          (clojure.lang.Compiler/load
           (clojure.lang.LineNumberingPushbackReader.
            (java.io.StringReader. isolated-source))
           "eacl/formal/production_kernel.clj"
           (str audit-namespace)))
        (finally
          (remove-ns audit-namespace)))
      (let [reflection-warning-lines
            (mapv
             (comp parse-long second)
             (re-seq
              #"production_kernel\.clj:(\d+):"
              (str warnings)))]
        (is (empty? reflection-warning-lines)
            (str
             "Generated Java boundary contains reflective calls "
             "at source lines "
             reflection-warning-lines))))))

(deftest versioned-characterization-fixture-replays-test
  (let [{:keys [fixture-format
                fixture-version
                semantics-version
                oracle-seed
                authorization-scenarios
                public-operation-scenarios]}
        (load-fixture)]
    (is (= :eacl/characterization fixture-format))
    (is (= 1 fixture-version semantics-version))
    (is (= oracle/fixture-seed oracle-seed))

    (testing "authorization scenarios exactly match the independent oracle"
      (doseq [{:keys [id expected-grants] :as scenario}
              authorization-scenarios]
        (is (= expected-grants (oracle/authorization-set scenario))
            (str "characterization mismatch: " id))))

    (testing "recursive lookup and count projections are frozen"
      (let [{:keys [expected-grants
                    expected-resource-lookup
                    expected-resource-count]}
            (first (filter #(= :recursive-scc (:id %))
                           authorization-scenarios))
            subject {:type :user :id "u1"}
            resources
            (resource-projection expected-grants subject :read :folder)]
        (is (= expected-resource-lookup resources))
        (is (= expected-resource-count (count resources)))))

    (testing "public cache, cursor, lookup/count, and typed-error behavior has evidence"
      (is (= #{:lookup-and-count
               :cursor-continuation
               :authenticated-cache
               :typed-errors}
             (set (map :id public-operation-scenarios))))
      (doseq [{:keys [id expected covered-by]} public-operation-scenarios]
        (is (seq expected) (str "missing expected result: " id))
        (is (seq covered-by) (str "missing executable evidence: " id))
        (is (every? symbol? covered-by)
            (str "evidence must name test vars: " id))
        (doseq [evidence covered-by]
          (when-let [evidence-var (available-evidence evidence)]
            (is (var? evidence-var)
                (str "unresolvable characterization evidence: "
                     evidence))))))))

(deftest quantitative-performance-gates-are-well-formed-test
  (let [{:keys [lore-resource-boundary
                formal-pipeline
                routing-certificate-boundary
                consistency-selection-boundary
                generated-artifacts
                legacy-runtime
                generated-indexed-authority
                ordered-merge-source-specialization
                layered-subproblem-cache
                cross-backend-managed-proof
                authority-mode-matrix
                recoverable-cursor-rebase
                memory-and-token
                retained-live-heap-gate
                release-performance-evaluation
                final-heavy-run
                shadow-rollout]}
        (edn/read-string (slurp performance-gates-path))
        generated-artifact-config
        (edn/read-string
         (slurp
          (repo/file
           (:machine-readable-config generated-artifacts))))
        formal-workflow
        (slurp (repo/file ".github" "workflows" "formal.yml"))]
    (is (= :outdated-untrusted-diagnostic
           (:analyzer-status lore-resource-boundary)))
    (is (= :none
           (:assurance-contribution lore-resource-boundary)))
    (is (= :diagnostic-only-no-release-gate-depends-on-analyzer
           (:status lore-resource-boundary)))
    (is (not-any?
         #{:lore-resource-boundary}
         (tree-seq
          coll?
          seq
          release-performance-evaluation)))
    (is (= :host-specific-measurement
           (:wall-clock-assurance formal-pipeline)))
    (is (< 0
           (:last-observed-local-wall-seconds formal-pipeline)
           (:github-job-timeout-seconds formal-pipeline)))
    (is (pos-int?
         (:assertion-batch-time-limit-seconds formal-pipeline)))
    (is (pos-int? (:proof-effort-resource-limit formal-pipeline)))
    (is (< (get-in formal-pipeline
                   [:maximum-observed-proof-effort :resource-count])
           (:proof-effort-resource-limit formal-pipeline)))
    (testing "routing certificate keeps proof and runtime dimensions separate"
      (let [required (:required routing-certificate-boundary)
            observed (:observed routing-certificate-boundary)]
        (is (= :passed (:status routing-certificate-boundary)))
        (is (= :dafny-theorem
               (get-in routing-certificate-boundary
                       [:formal-logical-work :assurance])))
        (is (= :not-established
               (get-in routing-certificate-boundary
                       [:resource-qualification
                        :retained-live-heap])))
        (is (= :none
               (get-in routing-certificate-boundary
                       [:resource-qualification
                        :lore-analyser-contribution])))
        (is (every?
             #(<= (:p50-allocated-bytes-per-node %)
                  (:maximum-p50-allocated-bytes-per-node required))
             (:sizes observed)))
        (is (<= (:normalized-allocation-ratio observed)
                (:maximum-normalized-allocation-ratio required)))
        (is (<= (:normalized-latency-ratio observed)
                (:maximum-normalized-latency-ratio required)))
        (is (= :passed (:logical-status observed)))
        (is (= :passed (:allocation-status observed)))
        (is (= :passed (:latency-status observed)))
        (is (str/includes?
             formal-workflow
             "routing-certificate-benchmark/run-gate!"))))
    (testing "consistency boundary gates keep logic and wall time separate"
      (is (= :passed (:status consistency-selection-boundary)))
      (is (= :dafny-bounded-and-source-instrumentation-matched
             (get-in consistency-selection-boundary
                     [:resource-qualification :logical-counters])))
      (is (= :host-specific-regression-measurement
             (get-in consistency-selection-boundary
                     [:resource-qualification :wall-time])))
      (doseq [runtime [:clj-java :cljs-javascript]]
        (let [required
              (get-in consistency-selection-boundary
                      [:required runtime])
              observed
              (get-in consistency-selection-boundary
                      [:observed runtime])]
          (is (<= (:median-p95-ratio observed)
                  (:maximum-median-p95-ratio required)))
          (is (<= (:median-p95-absolute-overhead-ns observed)
                  (:maximum-median-p95-absolute-overhead-ns required)))
          (is (= :passed (:status observed))))))
    (testing "recoverable cursor rebase has generated logical and host gates"
      (let [required (:required recoverable-cursor-rebase)
            observed (:observed recoverable-cursor-rebase)
            formal-work (:formal-logical-work
                         recoverable-cursor-rebase)]
        (is (= :passed (:status recoverable-cursor-rebase)))
        (is (= :none (:whole-denotation-cap formal-work)))
        (is (= {:clj-java 4096
                :cljs-javascript 16384}
               (:maximum-adapter-input-items formal-work)))
        (is (= :dafny-exact-first-match-and-bounded-adapter-chunk-theorems
               (:assurance formal-work)))
        (doseq [runtime [:clj-java :cljs-javascript]]
          (let [runtime-required (get-in required [runtime])
                runtime-fixture (get-in observed [runtime :fixture])
                sizes (:sizes runtime-fixture)]
            (is (= :multi-chunk-current-denotation
                   (:scaling-domain runtime-fixture)))
            (is (every?
                 #(<= (:minimum-scaling-size runtime-required) %)
                 sizes))
            (is (<= (* (:minimum-scaling-span runtime-required)
                       (first sizes))
                    (last sizes)))))
        (is (<= (get-in observed
                        [:clj-java :maximum-p50-ns-per-item])
                (get-in required
                        [:clj-java :maximum-p50-ns-per-item])))
        (is (<= (get-in observed
                        [:clj-java
                         :maximum-p50-allocated-bytes-per-item])
                (get-in required
                        [:clj-java
                         :maximum-p50-allocated-bytes-per-item])))
        (is (= (get-in required
                       [:clj-java :large-recovery-size])
               (get-in observed
                       [:clj-java :large-recovery :size])))
        (is (<= (get-in observed
                        [:cljs-javascript :maximum-p50-ns-per-item])
                (get-in required
                        [:cljs-javascript :maximum-p50-ns-per-item])))
        (is (= :not-established
               (get-in recoverable-cursor-rebase
                       [:resource-qualification :retained-live-heap])))
        (is (= :none
               (get-in recoverable-cursor-rebase
                       [:resource-qualification
                        :lore-analyser-contribution])))
        (is (str/includes?
             formal-workflow
             "Restart heap-bounded nREPL for cursor resource gate"))
        (is (str/includes?
             formal-workflow
             "cursor-rebase-benchmark/run-gate!"))
        (is (str/includes?
             formal-workflow
             "cursor-rebase-benchmark.js"))))
    (is (= "bin/formal artifact-size"
           (:measurement-command generated-artifacts)))
    (is (= :after-all-generated-artifacts-are-rebuilt
           (:measurement-order generated-artifacts)))
    (is (= {:tool :babashka
            :version "1.12.213"
            :ci-installed true}
           (:gate-runtime generated-artifacts)))
    (is (str/includes? formal-workflow "bb: '1.12.213'"))
    (is (str/includes? formal-workflow "bin/formal browser-bundle"))
    (is (str/includes? formal-workflow "bin/formal artifact-size"))
    (is (< (.indexOf formal-workflow "bin/formal browser-bundle")
           (.indexOf formal-workflow "bin/formal artifact-size")))
    (is (= :reviewed-full-kernel-baseline
           (get-in generated-artifacts [:cutover-rule :status])))
    (is (.isFile
         (repo/file (:machine-readable-config generated-artifacts))))
    (is (= :uncompressed-byte-length
           (:measurement generated-artifact-config)))
    (is (= (dissoc (:gate-runtime generated-artifacts) :ci-installed)
           (:gate-runtime generated-artifact-config)))
    (is (= (get-in generated-artifacts
                   [:cutover-rule
                    :maximum-growth-over-reviewed-full-kernel])
           (:maximum-growth-over-reviewed-full-kernel
            generated-artifact-config)))
    (doseq [[_ {:keys [baseline-bytes maximum-bytes]}]
            (:artifacts generated-artifact-config)]
      (is (pos-int? baseline-bytes))
      (is (pos-int? maximum-bytes))
      (is (<= baseline-bytes maximum-bytes)))
    (is (< (get-in legacy-runtime
                   [:multipath-page :max-page-median-baseline-ms])
           (get-in legacy-runtime
                   [:multipath-page :max-page-median-max-ms])))
    (let [harness
          (get-in legacy-runtime
                  [:permission-check :steady-state-harness])]
      (is (= :EACL-FORMAL-050 (:counterexample harness)))
      (is (= 15000 (:warmup-calls harness)))
      (is (= 3 (:measurement-batches harness)))
      (is (= 5000 (:samples-per-batch harness)))
      (is (= :median-of-batch-medians (:aggregation harness)))
      (is (false? (:threshold-relaxed harness)))
      (is (= :passed
             (get-in harness
                     [:last-local-forced-authority :status])))
      (is (< (get-in harness
                     [:last-local-forced-authority
                      :median-microseconds])
             (get-in legacy-runtime
                     [:permission-check :warm-max-microseconds])))
      (is (= :failed
             (get-in harness
                     [:github-corrected-harness-before-root-hoist
                      :status])))
      (is (= :EACL-FORMAL-051
             (get-in harness
                     [:github-corrected-harness-before-root-hoist
                      :closing-counterexample]))))
    (let [classification
          (get-in legacy-runtime
                  [:permission-check :generated-root-classification])]
      (is (= :EACL-FORMAL-051 (:counterexample classification)))
      (is (= 1
             (:maximum-lookups-per-public-generated-can
              classification)))
      (is (= 2 (:before-lookups-per-call classification)))
      (is (= 1 (:after-lookups-per-call classification)))
      (is (< (get-in classification
                     [:last-local-paired :after-to-before-ratio])
             1.0))
      (is (false? (:threshold-relaxed classification)))
      (is (= :passed (:status classification))))
    (is (<= (get-in memory-and-token
                    [:cursor-token-utf8-bytes
                     :multipath-500-results-page-50])
            (get-in memory-and-token
                    [:cursor-token-utf8-bytes :regression-max])))
    (is (= {:payload-canonical-passes-per-encode 1
            :payload-canonical-passes-per-decode 1
            :authentication-passes-per-encode 1
            :authentication-passes-per-decode 1
            :public-relationship-continuation-decodes-per-request 1
            :framing-growth :linear}
           (:cursor-codec-work memory-and-token)))
    (is (zero? (:unexplained-differences-max shadow-rollout)))
    (is (zero? (:false-grants-max shadow-rollout)))
    (is (= [:ci :canary :ramp :pre-authority]
           (mapv :name (:stages shadow-rollout))))
    (is (every? #(<= 0.0 (:sample-rate %) 1.0)
                (:stages shadow-rollout)))
    (is (every? #(pos-int? (:minimum-compared-operations %))
                (:stages shadow-rollout)))

    (testing "each resource dimension is evaluated independently"
      (let [dimensions (:dimensions release-performance-evaluation)
            required (:required-dimensions release-performance-evaluation)
            failed
            (filterv
             #(not= :passed (get-in dimensions [% :status]))
             required)]
        (is (= required (vec (distinct required))))
        (is (empty? failed))
        (is (true?
             (get-in dimensions
                     [:retained-live-heap :release-blocking])))
        (is (true?
             (:all-required-passed? release-performance-evaluation)))
        (is (= :passed
               (:release-cutover release-performance-evaluation)))))

    (testing "retained heap uses an observed positive full-GC signal"
      (let [required (:required retained-live-heap-gate)
            observed (:observed retained-live-heap-gate)]
        (is (= :passed (:status retained-live-heap-gate)))
        (is (= "formal/verification/retained-live-heap.edn"
               (:report retained-live-heap-gate)))
        (is (true? (:positive-signal? observed)))
        (is (true? (:same-results? observed)))
        (is (<= (:minimum-positive-retained-signal-bytes required)
                (:minimum-retained-delta-bytes observed)))
        (is (<= (:maximum-generated-to-legacy-ratio observed)
                (:maximum-generated-to-legacy-ratio required)))
        (is (true? (:observed-full-gc-between-every-snapshot required)))
        (is (true? (:explicit-baseline-keepalive required)))))

    (testing "configured logical weight is checked without calling it heap"
      (let [store (subproblem/store)
            expected
            (:default-subproblem-cache memory-and-token)]
        (is (= {:projection (:projection-max-weight expected)
                :denotation (:denotation-max-weight expected)}
               (:budgets store)))
        (is (= (:max-inflight expected)
               (:max-inflight store)))
        (is (= (:managed-proof-max-atoms expected)
               (:managed-proof-max-atoms store)))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :entry-weight :status])))))

    (testing "proof-operation thresholds are evaluated from like dimensions"
      (let [maximum
            (get-in cross-backend-managed-proof
                    [:required :maximum-large-to-small-p50-ratio])]
        (is (true?
             (get-in cross-backend-managed-proof
                     [:required :unchanged-target-proof])))
        (doseq [[_ {:keys [p50-ratio]}]
                (:observed cross-backend-managed-proof)]
          (is (<= p50-ratio maximum)))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :proof-operations :status])))))

    (testing "throughput and latency gates use measured wall-time evidence"
      (let [page-median
            (get-in final-heavy-run
                    [:multipath-page :max-page-median-ms])
            pages-per-second (/ 1000.0 page-median)
            minimum-throughput
            (get-in legacy-runtime
                    [:multipath-page
                     :minimum-threshold-throughput-pages-per-second])]
        (is (<= page-median
                (get-in legacy-runtime
                        [:multipath-page
                         :max-page-median-max-ms])))
        (is (>= pages-per-second minimum-throughput))
        (is (true?
             (get-in final-heavy-run
                     [:recursive-4000 :thresholds-met])))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :throughput :status])))))

    (testing "public authority modes gate like resource dimensions separately"
      (let [required (:required authority-mode-matrix)]
        (is (= #{:direct :acyclic :recursive :cursor :cache-hot}
               (set (keys (:observed authority-mode-matrix)))))
        (is (= :passed (:status authority-mode-matrix)))
        (doseq [[_ observation] (:observed authority-mode-matrix)]
          (is (or
               (<= (:median-p95-latency-ratio observation)
                   (:maximum-median-p95-latency-ratio required))
               (<= (:median-p95-absolute-overhead-ns observation)
                   (:maximum-median-p95-absolute-overhead-ns required))))
          (is (or
               (<= (:median-p95-allocation-ratio observation)
                   (:maximum-median-p95-allocation-ratio required))
               (<= (:median-p95-allocation-overhead-bytes observation)
                   (:maximum-median-p95-allocation-overhead-bytes
                    required))))
          (is (or
               (<= (:median-p95-backend-operation-ratio observation)
                   (:maximum-median-p95-backend-operation-ratio required))
               (<= (:median-p95-backend-operation-overhead observation)
                   (:maximum-median-p95-backend-operation-overhead
                    required))))
          (is (= :passed (:status observation))))
        (is (zero?
             (get-in authority-mode-matrix
                     [:reflection-removal
                      :generated-java-boundary-reflection-warnings])))))

    (testing "shared-subgraph gates are recomputed rather than trusted"
      (let [required (:required layered-subproblem-cache)
            observed
            (get-in layered-subproblem-cache
                    [:observed :current-rerun])]
        (is (<= (/ (:layered-backend-operations observed)
                   (:baseline-backend-operations observed))
                (:maximum-backend-work-ratio required)))
        (is (<= (:p50-latency-ratio observed)
                (:maximum-p50-latency-ratio required)))
        (is (<= (:new-generation-proof-reads observed)
                (:maximum-new-generation-proof-reads required)))
        (is (<= (:hot-hit-regression-ratio observed)
                (:maximum-hot-hit-regression-ratio required)))
        (is (<= (:cache-disabled-regression-ratio observed)
                (:maximum-cache-disabled-regression-ratio required)))))

    (testing "verification-time and generated-byte gates fail closed"
      (is (true? (:timeout-is-failure formal-pipeline)))
      (is (< (:last-observed-local-wall-seconds formal-pipeline)
             (:github-job-timeout-seconds formal-pipeline)))
      (is (= :deterministic-for-locked-toolchain-and-seed
             (:resource-assurance formal-pipeline)))
      (is (< (get-in formal-pipeline
                     [:maximum-observed-proof-effort :resource-count])
             (:proof-effort-resource-limit formal-pipeline)))
      (doseq [[_ {:keys [baseline-bytes maximum-bytes]}]
              (:artifacts generated-artifact-config)]
        (is (<= baseline-bytes maximum-bytes)))
      (is (= :passed
             (get-in release-performance-evaluation
                     [:dimensions :verification-time :status])))
      (is (= :passed
             (get-in release-performance-evaluation
                     [:dimensions :generated-artifact-size :status]))))

    (testing "noise rules require independent trials and robust summaries"
      (let [indexed
            (get-in generated-indexed-authority
                    [:traversal-scope-binding-recheck])
            merge-gates
            (map
             ordered-merge-source-specialization
             [:page-prefix-gate
              :complete-consumption-gate])]
        (is (= 5 (count (:trials indexed))))
        (is (= :passed (get-in indexed [:summary :status])))
        (doseq [gate merge-gates]
          (is (= 5 (get-in gate [:fixture :independent-trials])))
          (is (= :passed (:status gate)))
          (is (<= (:median-p95-ratio gate)
                  (get-in gate
                          [:required
                           :maximum-median-p95-ratio]))))
        (is (= 5
               (get-in layered-subproblem-cache
                       [:observed :repeated-runs])))
        (is (= :passed
               (get-in release-performance-evaluation
                       [:dimensions :benchmark-noise :status])))))))
