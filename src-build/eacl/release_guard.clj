(ns eacl.release-guard
  "Credential-free GitHub ref, version, and exact-check-run release guards."
  (:require [clojure.string :as string]
            [eacl.build.config :as config]))

(def required-checks
  #{"generated-runtime"
    "test"
    "isolated-modules (eacl)"
    "isolated-modules (eacl-caveats-jvm)"
    "isolated-modules (eacl-datomic)"
    "isolated-modules (eacl-datahike)"
    "isolated-modules (eacl-datascript)"
    "dafny-and-generated-boundaries"
    "temporal-models"
    "parity-corpus-and-mutations"})

(defn- reject!
  [message type data]
  (throw (ex-info message (assoc data :type type))))

(defn tagged-release
  [{:keys [event-name ref ref-type supplied-version] :as context}]
  (when-not (contains? #{"push" "workflow_dispatch"} event-name)
    (reject! "Releases require a tag push or manual dispatch on a tag."
             :eacl.release/invalid-event context))
  (when-not (= "tag" ref-type)
    (reject! "EACL publication requires an explicit version tag."
             :eacl.release/invalid-ref-type context))
  (when-not (string/blank? supplied-version)
    (reject! "Release versions are derived from tags, never supplied."
             :eacl.release/version-override context))
  (let [[_ base suffix]
        (re-matches #"refs/tags/v([0-9]+\.[0-9]+\.[0-9]+)(.*)" (str ref))
        snapshot? (boolean
                   (re-matches #"-SNAPSHOT-[0-9]{4}-[0-9]{2}-[0-9]{2}(?:T[0-9]{6}Z)?"
                               (or suffix "")))
        version (str base (if snapshot? "-SNAPSHOT" suffix))]
    (when-not (and base (config/valid-version? version)
                   (or snapshot? (not= suffix "-SNAPSHOT")))
      (reject!
       "Use vMAJOR.MINOR.PATCH, vMAJOR.MINOR.PATCH-RC-YYYY-MM-DD, or a dated vMAJOR.MINOR.PATCH-SNAPSHOT-YYYY-MM-DD[THHMMSSZ] tag."
       :eacl.release/invalid-version-tag
       context))
    {:version version :branch (str "v" base "-SNAPSHOT")}))

(defn assert-tag-provenance!
  [{:keys [sha tag-sha source-tree branch-tree ancestor?] :as context}]
  (when-not (and (not (string/blank? sha)) (= sha tag-sha)
                 (not (string/blank? source-tree)) (= source-tree branch-tree)
                 (= true ancestor?))
    (reject! "The unchanged tag must be merged into the release branch and match its current Git tree."
             :eacl.release/tag-provenance-mismatch context))
  true)

(defn branch-name
  "Branch name of a refs/heads/* ref. Tags and pull-request refs are rejected
  because required checks are correlated with branch push runs only."
  [ref]
  (let [[_ branch] (re-matches #"refs/heads/(.+)" (str ref))]
    (when (string/blank? branch)
      (reject! "Release checks are scoped to a branch ref."
               :eacl.release/invalid-ref-type
               {:ref ref}))
    branch))

(defn release-check-suites
  "Check-suite ids of the push-event workflow runs for exactly this branch and
  commit. GitHub attaches every check run to its commit, so one SHA also
  carries pull-request runs (which verify a synthetic merge commit rather than
  the pushed tree) and the runs of any other branch pushed to the same commit.
  Only the release branch's own push runs verify the release commit as pushed,
  and only their check suites are admitted as release evidence."
  [{:keys [sha branch]} workflow-runs]
  (into #{}
        (comp (filter (fn [{:keys [event head_branch head_sha]}]
                        (and (= "push" event)
                             (or (nil? branch) (= branch head_branch))
                             (= sha head_sha))))
              (map :check_suite_id)
              (remove nil?))
        workflow-runs))

(def required-workflows
  #{".github/workflows/test.yml" ".github/workflows/formal.yml"})

(defn verified-workflow-runs
  "Select the latest push run of each trusted workflow at the tagged SHA.
  A newer failed or pending attempt must never be hidden by older green CI."
  [sha workflow-runs]
  (->> workflow-runs
       (filter #(and (= "push" (:event %)) (= sha (:head_sha %))
                     (contains? required-workflows (:path %))))
       (group-by :path)
       vals
       (mapv #(apply max-key :id %))))

(declare evaluate-checks)

(defn evaluate-tag-checks
  [sha workflow-runs check-runs]
  (let [runs (verified-workflow-runs sha workflow-runs)]
    (when-not (= required-workflows (set (map :path runs)))
      (reject! "Run Tests and Formal verification on the source branch before tagging."
               :eacl.release/missing-workflows {:sha sha}))
    (doseq [run runs]
      (when-not (and (= "completed" (:status run)) (= "success" (:conclusion run)))
        (reject! "The latest source CI must be completed and green before publication."
                 :eacl.release/workflow-not-green
                 (select-keys run [:id :path :status :conclusion :html_url]))))
    (evaluate-checks {:sha sha} runs check-runs true)))

(defn evaluate-checks
  "Return :ready or :pending; reject ambiguity, wrong SHA, or bad conclusions.
  Only check runs that belong to the release branch's push-event workflow runs
  for this commit are judged; results the same commit carries from other refs
  or events are ignored rather than treated as ambiguous duplicates."
  [{:keys [sha] :as context} workflow-runs check-runs final?]
  (let [suites (release-check-suites context workflow-runs)
        relevant
        (filter
         (fn [{:keys [name check_suite]}]
           (and (contains? required-checks name)
                (contains? suites (:id check_suite))))
         check-runs)
        by-name (group-by :name relevant)
        duplicates
        (vec
         (sort
          (keep (fn [[name runs]]
                  (when (> (count runs) 1) name))
                by-name)))
        missing
        (vec (sort (remove (set (keys by-name)) required-checks)))]
    (when (seq duplicates)
      (reject! "Required checks have duplicate ambiguous results."
               :eacl.release/duplicate-checks
               {:checks duplicates}))
    (doseq [{:keys [name head_sha status conclusion]} relevant]
      (when-not (= sha head_sha)
        (reject! "A required check belongs to a different source commit."
                 :eacl.release/check-sha-mismatch
                 {:check name :expected sha :actual head_sha}))
      (when (and (= "completed" status)
                 (not= "success" conclusion))
        (reject! "A required release check did not succeed."
                 :eacl.release/check-failed
                 {:check name :status status :conclusion conclusion})))
    (let [pending
          (into
           missing
           (comp
            (remove #(= "completed" (:status %)))
            (map :name))
           relevant)]
      (cond
        (empty? pending) :ready
        final?
        (reject! "Required checks were absent or pending past the deadline."
                 :eacl.release/check-deadline
                 {:checks (vec (sort pending))})
        :else :pending))))

(defn listing
  "Items of one GitHub listing payload. A page that does not hold every item
  GitHub counted is rejected: the gate must never judge a partial view."
  [{:keys [total_count] :as payload} key]
  (let [items (vec (get payload key))]
    (when (and (integer? total_count)
               (not= total_count (count items)))
      (reject! "GitHub listing was truncated to one page."
               :eacl.release/truncated-listing
               {:key key :total-count total_count :returned (count items)}))
    items))

(defn- environment-context
  []
  {:event-name (System/getenv "GITHUB_EVENT_NAME")
   :ref (System/getenv "GITHUB_REF")
   :ref-type (System/getenv "GITHUB_REF_TYPE")
   :sha (System/getenv "GITHUB_SHA")
   :supplied-version (System/getenv "EACL_VERSION")})

(defn- read-listing
  [path key]
  (let [read-json (requiring-resolve 'clojure.data.json/read-str)]
    (listing (read-json (slurp path) :key-fn keyword) key)))

(defn -main
  [& [operation & arguments]]
  (case operation
    "tag" (let [{:keys [version branch]} (tagged-release (environment-context))]
            (println (str "version=" version))
            (println (str "branch=" branch)))
    "provenance"
    (assert-tag-provenance!
     {:sha (System/getenv "GITHUB_SHA")
      :tag-sha (System/getenv "EACL_TAG_SHA")
      :source-tree (System/getenv "EACL_SOURCE_TREE")
      :branch-tree (System/getenv "EACL_BRANCH_TREE")
      :ancestor? (= "true" (System/getenv "EACL_ANCESTOR"))})
    "tag-checks"
    (let [[workflow-runs-path check-runs-path] arguments]
      (println
       (name (evaluate-tag-checks
              (System/getenv "GITHUB_SHA")
              (read-listing workflow-runs-path :workflow_runs)
              (read-listing check-runs-path :check_runs)))))
    "checks"
    (let [[workflow-runs-path check-runs-path final-argument] arguments
          {:keys [sha ref]} (environment-context)]
      (println
       (name
        (evaluate-checks
         {:sha sha :branch (branch-name ref)}
         (read-listing workflow-runs-path :workflow_runs)
         (read-listing check-runs-path :check_runs)
         (= "true" final-argument)))))
    (reject! "Unknown EACL release-guard operation."
             :eacl.release/unknown-guard-operation
             {:operation operation})))
