(ns eacl.formal.mutation-control-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.formal.executed-mutation-controls :as executed]
            [eacl.test-support.repo :as repo]))

(def registry-path
  (repo/file "formal" "mutations" "registry.edn"))

(defn- registry
  []
  (edn/read-string (slurp registry-path)))

(def executed-control-source
  (repo/file "modules" "eacl" "test" "eacl" "formal"
             "executed_mutation_controls.cljc"))

(defn- read-forms
  [file]
  (with-open [reader (java.io.PushbackReader.
                      (clojure.java.io/reader file))]
    (loop [forms []]
      (let [form (read {:eof ::eof
                        :read-cond :allow
                        :features #{:clj}}
                       reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(defn- detector-source-forms
  []
  (into
   {}
   (keep
    (fn [form]
      (when (and (seq? form)
                 (contains? '#{defn defn-} (first form)))
        [(name (second form)) form])))
   (read-forms executed-control-source)))

(def allowed-mechanisms
  #{:executed-production :executed-generated :executed-model :source-text})

(def required-executable-bug-classes
  "Normative examples from the assurance spec. These ids may not be made to
  disappear from the score by moving them into a retirement bucket."
  #{:wrong-arrow-direction
    :premature-cycle-cut
    :missing-de-duplication
    :wrong-frontier
    :incomplete-dependency
    :numeric-ancestry
    :cursor-scope
    :cache-fail-open
    :continuation-race})

(defn- executed-entry-problems
  [forms {:keys [id target-symbol detector control]}]
  (cond-> []
    (not= :clojure (:kind control))
    (conj {:id id :problem :executed-control-is-not-clojure})

    (not (and (string? target-symbol) (not (str/blank? target-symbol))))
    (conj {:id id :problem :missing-target-symbol})

    (not (and (string? detector) (not (str/blank? detector))))
    (conj {:id id :problem :missing-detector})

    (nil? (get forms detector))
    (conj {:id id :problem :missing-detector-source})

    (and (get forms detector)
         (not (str/includes? (pr-str (get forms detector)) target-symbol)))
    (conj {:id id
           :problem :missing-target-reference
           :detector detector
           :target-symbol target-symbol})

    (not (seq (:runtimes control)))
    (conj {:id id :problem :missing-runtime-scope})))

(defn- source-entry-problems
  [{:keys [id structural-fact source-assertions]}]
  (cond-> []
    (not (keyword? structural-fact))
    (conj {:id id :problem :source-text-is-not-structural})

    (not (seq source-assertions))
    (conj {:id id :problem :source-text-has-no-recorded-pattern})

    (some
     (fn [{:keys [file required forbidden]}]
       (or (not (and (string? file) (not (str/blank? file))))
           (not (vector? required))
           (not (vector? forbidden))
           (empty? (concat required forbidden))
           (not-every? #(and (string? %) (not (str/blank? %)))
                       (concat required forbidden))))
     source-assertions)
    (conj {:id id :problem :invalid-source-text-assertion})))

(defn- source-entry-killed?
  [{:keys [source-assertions]}]
  (every?
   (fn [{:keys [file required forbidden]}]
     (let [target (repo/file file)
           source (when (.isFile target) (slurp target))]
       (and source
            (every? #(str/includes? source %) required)
            (not-any? #(str/includes? source %) forbidden))))
   source-assertions))

(deftest registry-mechanisms-name-and-execute-their-targets-test
  (let [{:keys [required-score historical-mutant-count
                historical-clojure-mutant-count
                historical-model-mutant-count mutants
                retired-mutant-groups]}
        (registry)
        forms (detector-source-forms)
        active-ids (mapv :id mutants)
        retired-ids (mapv identity (mapcat :ids retired-mutant-groups))
        production (filterv #(= :executed-production (:mechanism %)) mutants)
        source-text (filterv #(= :source-text (:mechanism %)) mutants)
        models (filterv #(= :executed-model (:mechanism %)) mutants)]
    (testing "the historical registry was classified without silent deletion"
      (is (= historical-mutant-count
             (+ (count active-ids) (count retired-ids))))
      (is (= historical-mutant-count
             (+ historical-clojure-mutant-count
                historical-model-mutant-count)))
      (is (= historical-clojure-mutant-count
             (+ (count production) (count source-text)
                (count retired-ids))))
      (is (= historical-model-mutant-count (count models)))
      (is (= historical-mutant-count
             (count (set (concat active-ids retired-ids)))))
      (is (= (count active-ids) (count (set active-ids))))
      (is (= (count retired-ids) (count (set retired-ids))))
      (is (every? keyword? (map :reason retired-mutant-groups)))
      (is (every? (comp seq :disposition) retired-mutant-groups)))
    (testing "every active entry uses one closed execution mechanism"
      (is (every? allowed-mechanisms (map :mechanism mutants)))
      (is (every? (comp keyword? :mutation) mutants))
      (is (every? (set active-ids) required-executable-bug-classes)
          "normative mutation examples must remain executable and scored")
      (is (empty? (filter (set retired-ids)
                          required-executable-bug-classes))
          "normative mutation examples may not be retired")
      (is (empty? (mapcat #(executed-entry-problems forms %) production)))
      (is (empty? (mapcat source-entry-problems source-text))))
    (testing "executed production controls mutate the named Var"
      (is (= (set (map :id production)) (set (keys executed/controls))))
      (doseq [{:keys [id]} production]
        (is (true? ((get executed/controls id)))
            (str "surviving production mutant: " id))))
    (testing "source-text controls are structural and record exact patterns"
      (doseq [{:keys [id] :as mutant} source-text]
        (is (true? (source-entry-killed? mutant))
            (str "surviving structural mutant: " id))))
    (testing "model controls name executable Apalache inputs"
      (doseq [{:keys [control]} models]
        (is (= :apalache (:kind control)))
        (is (string? (:model control)))
        (is (string? (:config control)))
        (is (pos-int? (:length control)))
        (is (.isFile (repo/file (:model control))))
        (is (.isFile (repo/file (:config control))))))
    (let [clojure-mutants
          (filterv #(= :clojure (get-in % [:control :kind])) mutants)
          killed
          (+ (count production)
             (count (filter source-entry-killed? source-text)))
          score (/ killed (count clojure-mutants))]
      (is (= killed (count clojure-mutants)))
      (is (<= required-score score))
      (is (= 1 score)))))

(deftest registry-rejects-unbound-executed-and-nonstructural-source-controls-test
  (let [forms (detector-source-forms)
        executed-problems
        (executed-entry-problems
         forms
         {:id :negative-executed-control
          :target-symbol "missing.production/decision"
          :detector "wrong-frontier-killed?"
          :control {:kind :clojure :runtimes [:clj]}})
        source-problems
        (source-entry-problems
         {:id :negative-source-control
          :structural-fact nil
          :source-assertions []})]
    (is (some #(= :missing-target-reference (:problem %)) executed-problems))
    (is (some #(= :source-text-is-not-structural (:problem %)) source-problems))
    (is (some #(= :source-text-has-no-recorded-pattern (:problem %))
              source-problems))))

(deftest manifest-validator-rejects-corrupted-mutation-count-test
  (let [candidate
        (-> (edn/read-string
             (slurp (repo/file "formal" "verification" "manifest.edn")))
            (update-in [:mutation-control :registered] inc))
        temp-file
        (java.nio.file.Files/createTempFile
         "eacl-corrupt-manifest-"
         ".edn"
         (make-array java.nio.file.attribute.FileAttribute 0))
        dafny-report-file
        (java.nio.file.Files/createTempFile
         "eacl-test-dafny-verification-"
         ".json"
         (make-array java.nio.file.attribute.FileAttribute 0))]
    (try
      (spit (.toFile temp-file) (pr-str candidate))
      ;; Count-drift rejection does not depend on a completed Dafny build.
      ;; Give the validator a parseable, hermetic report so this negative test
      ;; executes identically in the ordinary, parity, and formal jobs.
      (spit (.toFile dafny-report-file) "{}")
      (let [builder
            (ProcessBuilder.
             [(.getCanonicalPath
               (repo/file "bin" "validate-verification-manifest"))])
            environment (.environment builder)
            _ (.put environment
                    "EACL_REPO_ROOT"
                    (.getCanonicalPath (repo/file ".")))
            _ (.put environment
                    "EACL_VERIFICATION_MANIFEST"
                    (str temp-file))
            _ (.put environment
                    "EACL_DAFNY_VERIFICATION_REPORT"
                    (str dafny-report-file))
            _ (.redirectErrorStream builder true)
            process (.start builder)
            output (slurp (.getInputStream process))
            exit (.waitFor process)]
        (is (= 2 exit)
            "invalid evidence must not masquerade as expected assurance withholding")
        (is (str/includes? output ":mutation-control/registered") output))
      (finally
        (java.nio.file.Files/deleteIfExists temp-file)
        (java.nio.file.Files/deleteIfExists dafny-report-file)))))

(deftest ledger-matches-registry-test
  (let [{:keys [mutants retired-mutant-groups]} (registry)
        clojure-mutants
        (filterv #(= :clojure (get-in % [:control :kind])) mutants)
        apalache-mutants
        (filterv #(= :apalache (get-in % [:control :kind])) mutants)
        retired (mapcat :ids retired-mutant-groups)
        ledger (edn/read-string
                (slurp (repo/file "formal" "verification"
                                  "mutation-control.edn")))]
    (testing "totals"
      (is (= (count mutants) (:mutants ledger)))
      (is (= (:mutants ledger) (:killed ledger)))
      (is (zero? (:survived ledger)))
      (is (= (count retired) (:retired-invalid-controls ledger))))
    (testing "per-control-kind counts"
      (is (= (count clojure-mutants)
             (get-in ledger [:controls :clojure :mutants])))
      (is (= (count apalache-mutants)
             (get-in ledger [:controls :apalache :mutants]))))))
