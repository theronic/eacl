(ns eacl.characterization-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.test-support.repo :as repo]))

(def ^:private fixture-path
  (repo/file "formal" "characterization" "v1" "eacl-engine.edn"))

(def ^:private artifact-size-policy-path
  (repo/file "formal" "policy" "generated-artifact-size.edn"))

(defn- load-fixture []
  (edn/read-string (slurp fixture-path)))

(defn- available-evidence [evidence]
  (when (repo/evidence-namespace-available? evidence)
    (requiring-resolve evidence)))

(defn- resource-projection [grants subject permission resource-type]
  (->> grants
       (keep (fn [[candidate-subject candidate-permission resource]]
               (when (and (= subject candidate-subject)
                          (= permission candidate-permission)
                          (= resource-type (:type resource)))
                 resource)))
       (sort-by :id)
       vec))

(deftest formal-cljs-smoke-preserves-persistent-nrepl-executors-test
  (let [runner (slurp (repo/file "formal" "smoke" "cljs" "run"))
        workflow (slurp (repo/file ".github" "workflows" "formal.yml"))]
    (is (str/includes? runner "cljs.build.api"))
    (is (not (str/includes? runner "(cljs/-main")))
    (is (str/includes? runner "@(future :eacl.formal/agent-executor-alive)"))
    (is (str/includes? workflow "cljs.build.api"))
    (is (not (str/includes? workflow "(cljs/-main")))))

(deftest github-actions-provisions-temporal-tools-and-isolates-module-tests-test
  (let [formal-workflow (slurp (repo/file ".github" "workflows" "formal.yml"))
        test-workflow (slurp (repo/file ".github" "workflows" "test.yml"))
        ^String temporal-job
        (-> formal-workflow
            (str/split #"\n  temporal-models:\n" 2)
            second
            (str/split #"\n  parity-corpus-and-mutations:\n" 2)
            first)]
    (testing "temporal mutation controls install their declared runtime"
      (is (str/includes? temporal-job
                         "uses: DeLaGuardo/setup-clojure@13.6.1"))
      (is (str/includes? temporal-job "bb: '1.12.213'"))
      (is (< (.indexOf temporal-job "Set up Babashka")
             (.indexOf temporal-job "bin/formal apalache-mutation-control"))))
    (testing "isolated modules exclude repository-global formal gates"
      (is (str/includes? test-workflow "(?!eacl[.]formal[.]).*-test$")))))

(deftest advanced-cljs-build-and-extern-surface-are-wired-test
  (let [workflow (slurp (repo/file ".github" "workflows" "formal.yml"))
        deps-cljs (slurp (repo/file "formal" "smoke" "cljs" "deps.cljs"))
        externs
        (slurp
         (repo/file "formal" "smoke" "cljs" "eacl" "formal"
                    "generated_runtime.ext.js"))]
    (is (str/includes?
         workflow
         "Run advanced-optimized CLJS production and differential suites"))
    (is (<= 6 (count (re-seq #":optimizations :advanced" workflow))))
    (is (<= 6 (count (re-seq #":warnings-as-errors true" workflow))))
    (doseq [main ['eacl.formal.cljs-test-runner
                  'eacl.datascript.cljs-test-runner
                  'eacl.formal.empty-bundle-entry
                  'eacl.formal.portable-kernel-bundle-entry
                  'eacl.datascript.production-bundle-entry
                  'eacl.formal.indexed-traversal-benchmark]]
      (is (str/includes? workflow (str ":main '" main)) (str main)))
    (is (str/includes? workflow "node target/formal/cljs-advanced.js"))
    (is (str/includes? workflow
                       "node target/formal/datascript-cljs-advanced.js"))
    (is (str/includes? workflow "eacl.formal.cljs-production-gate-test"))
    (is (str/includes? deps-cljs "eacl/formal/generated_runtime.ext.js"))
    (doseq [property ["EaclFormal" "create_RelationBinding"
                      "dtor_items" "is_PlanCertified"]]
      (is (str/includes? externs (str "Object.prototype." property ";"))
          property))))

(deftest formal-ci-isolates-and-stops-performance-nrepls-test
  (let [workflow (slurp (repo/file ".github" "workflows" "formal.yml"))
        index #(.indexOf workflow %)
        ordered-stages
        ["Start heap-bounded generated-boundary nREPL"
         "Gate portable CLJS indexed traversal scaling and ceiling"
         "Restart heap-bounded nREPL for runtime performance suites"
         "eacl.formal.verified-authority-suite/run-heavy!"
         "eacl.formal.verified-authority-suite/run-nonbenchmark!"
         "Restart heap-bounded nREPL for generated resource gates"
         "Gate routing-certificate logical work and JVM allocation"
         "Gate generated consistency-boundary overhead"
         "Restart heap-bounded nREPL for recorded-baseline gates"
         "Gate populated recursion against recorded v7 baselines"
         "Gate Explorer enumeration against recorded v7 baselines"
         "Stop CI nREPL"]]
    (is (= 4 (count (re-seq #"JAVA_TOOL_OPTIONS='-Xms128m -Xmx1024m'"
                            workflow))))
    (is (str/includes? workflow "echo \"$!\" > target/formal/ci-nrepl.pid"))
    (is (str/includes? workflow "if: always()"))
    (is (str/includes? workflow
                       "kill -KILL \"$nrepl_pid\" 2>/dev/null || true"))
    (is (every? (comp not neg? index) ordered-stages))
    (is (apply < (map index ordered-stages)))))

(defn- source-definitions [path]
  (into #{}
        (map (comp symbol second))
        (re-seq #"\(defn-?\s+([^\s\[\(\)]+)"
                (slurp (repo/file path)))))

(deftest generated-conversion-boundary-is-complete-and-cross-runtime-test
  (let [contract
        (load-file (str (repo/file "formal" "assurance_contract.clj")))
        {:keys [converter-categories runtime-sources boundary-invariants]}
        (first
         (filter #(= :generated-conversion-boundary (:operation %))
                 (:operation-contracts contract)))
        required-categories
        #{:schema-ir :relationships :queries :adapter-callbacks
          :cache-and-cursors :results :typed-errors}
        required-converters (into #{} cat (vals converter-categories))
        runtime-definitions
        (into {}
              (map (fn [[runtime source]]
                     [runtime (source-definitions source)]))
              runtime-sources)]
    (is (= required-categories (set (keys converter-categories))))
    (is (= #{:clj-java :cljs-javascript} (set (keys runtime-sources))))
    (is (every? symbol? required-converters))
    (doseq [[runtime definitions] runtime-definitions]
      (is (every? definitions required-converters)
          (str runtime " is missing required converters: "
               (pr-str (sort (remove definitions required-converters))))))
    (is (= #{:input-validation :result-validation
             :unknown-field-rejection :safe-integer-validation
             :bounded-collection-validation
             :complete-portable-error-comparison}
           (set boundary-invariants)))))

(deftest generated-java-boundary-is-reflection-free-test
  (when
   (try
     (Class/forName "IndexedTraversal.ForwardStep")
     true
     (catch ClassNotFoundException _ false))
    (let [source
          (slurp
           (repo/file "modules" "eacl" "src" "eacl" "formal"
                      "production_kernel.clj"))
          audit-namespace
          (symbol (str "eacl.formal.production-kernel-reflection-audit-"
                       (gensym)))
          isolated-source
          (str/replace-first source
                             "(ns eacl.formal.production-kernel"
                             (str "(ns " audit-namespace))
          warnings (java.io.StringWriter.)]
      (try
        (binding [*warn-on-reflection* true *err* warnings]
          (clojure.lang.Compiler/load
           (clojure.lang.LineNumberingPushbackReader.
            (java.io.StringReader. isolated-source))
           "eacl/formal/production_kernel.clj"
           (str audit-namespace)))
        (finally (remove-ns audit-namespace)))
      (let [reflection-warning-lines
            (mapv (comp parse-long second)
                  (re-seq #"production_kernel\.clj:(\d+):"
                          (str warnings)))]
        (is (empty? reflection-warning-lines)
            (str "Generated Java boundary contains reflective calls at "
                 "source lines " reflection-warning-lines))))))

(deftest versioned-characterization-fixture-replays-test
  (let [{:keys [fixture-format fixture-version semantics-version oracle-seed
                authorization-scenarios public-operation-scenarios]}
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
      (let [{:keys [expected-grants expected-resource-lookup
                    expected-resource-count]}
            (first (filter #(= :recursive-scc (:id %))
                           authorization-scenarios))
            subject {:type :user :id "u1"}
            resources
            (resource-projection expected-grants subject :read :folder)]
        (is (= expected-resource-lookup resources))
        (is (= expected-resource-count (count resources)))))
    (testing "public cache, cursor, lookup/count, and typed errors have evidence"
      (is (= #{:lookup-and-count :cursor-continuation :authenticated-cache
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
                (str "unresolvable characterization evidence: " evidence))))))))

(deftest quantitative-performance-gates-are-well-formed-test
  (let [{:keys [schema-version measurement gate-runtime artifacts]}
        (edn/read-string (slurp artifact-size-policy-path))
        workflow (slurp (repo/file ".github" "workflows" "formal.yml"))
        required-commands
        ["bin/formal browser-bundle"
         "bin/formal artifact-size"
         "bin/formal source-closure"
         "bin/formal manifest"]]
    (is (= 1 schema-version))
    (is (= :uncompressed-byte-length measurement))
    (is (= {:tool :babashka :version "1.12.213"} gate-runtime))
    (is (seq artifacts))
    (doseq [[artifact {:keys [path baseline-bytes maximum-bytes]}] artifacts]
      (is (keyword? artifact))
      (is (str/starts-with? path "target/formal/"))
      (is (pos-int? baseline-bytes))
      (is (<= baseline-bytes maximum-bytes)))
    (is (every? #(str/includes? workflow %) required-commands))
    (is (< (.indexOf workflow "bin/formal browser-bundle")
           (.indexOf workflow "bin/formal artifact-size")))
    (is (< (.indexOf workflow "bin/formal source-closure")
           (.lastIndexOf workflow "bin/formal manifest")))))
