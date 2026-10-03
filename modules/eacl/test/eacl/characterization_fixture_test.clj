(ns eacl.characterization-fixture-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.test-support.repo :as repo]))

(def ^:private fixture-path
  (repo/file "formal" "characterization" "v1" "eacl-engine.edn"))

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
  (let [contract
        (load-file (str (repo/file "formal" "assurance_contract.clj")))
        {:keys [converter-categories runtime-sources boundary-invariants]}
        (first
         (filter #(= :generated-conversion-boundary (:operation %))
                 (:operation-contracts contract)))
        required-categories
        #{:schema-ir :relationships :queries :adapter-callbacks
          :cache-and-cursors :results :typed-errors}
        required-converters
        (into #{} cat (vals converter-categories))
        runtime-definitions
        (into
         {}
         (map
          (fn [[runtime source]]
            [runtime (source-definitions source)]))
         runtime-sources)]
    (is (= required-categories (set (keys converter-categories))))
    (is (= #{:clj-java :cljs-javascript}
           (set (keys runtime-sources))))
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
           (set boundary-invariants)))))

(deftest generated-java-boundary-is-reflection-free-test
  (let [classes-present?
        (try
          (Class/forName "IndexedTraversal.ForwardStep")
          true
          (catch ClassNotFoundException _
            false))]
    ;; In CI the generated classes are a build prerequisite: their absence
    ;; must fail this gate loudly, never let it pass with zero assertions.
    (when (System/getenv "CI")
      (is classes-present?
          "generated kernel classes are absent on a CI classpath"))
    (when classes-present?
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
             reflection-warning-lines)))))))

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
