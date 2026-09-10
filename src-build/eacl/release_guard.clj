(ns eacl.release-guard
  "Credential-free GitHub ref, version, and exact-check-run release guards."
  (:require [clojure.string :as string]))

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

(defn- assert-branch-head!
  [{:keys [sha branch-sha]}]
  (when (or (string/blank? sha)
            (string/blank? branch-sha)
            (not= sha branch-sha))
    (reject!
     "Release commit must still be the exact head of the selected branch."
     :eacl.release/branch-head-mismatch
     {:sha sha :branch-sha branch-sha})))

(defn ordinary-version
  [{:keys [event-name ref ref-type supplied-version] :as context}]
  (when-not (= "push" event-name)
    (reject! "Ordinary releases require a push event."
             :eacl.release/invalid-event context))
  (when-not (= "branch" ref-type)
    (reject! "EACL releases never publish from tags or pull-request refs."
             :eacl.release/invalid-ref-type context))
  (when-not (string/blank? supplied-version)
    (reject! "Ordinary release versions are derived, never supplied."
             :eacl.release/version-override context))
  (let [[_ version]
        (re-matches
         #"refs/heads/v([0-9]+\.[0-9]+\.[0-9]+(?:-SNAPSHOT)?)"
         ref)]
    (when-not version
      (reject!
       (str "Ordinary release branch must exactly match "
            "vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-SNAPSHOT.")
       :eacl.release/invalid-version-branch
       context))
    (assert-branch-head! context)
    version))

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
                             (= branch head_branch)
                             (= sha head_sha))))
              (map :check_suite_id)
              (remove nil?))
        workflow-runs))

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
   :branch-sha (System/getenv "EACL_BRANCH_SHA")
   :supplied-version (System/getenv "EACL_VERSION")})

(defn- read-listing
  [path key]
  (let [read-json (requiring-resolve 'clojure.data.json/read-str)]
    (listing (read-json (slurp path) :key-fn keyword) key)))

(defn -main
  [& [operation & arguments]]
  (case operation
    "ordinary" (println (ordinary-version (environment-context)))
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
