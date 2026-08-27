(ns eacl.release-guard
  "Credential-free GitHub ref, version, and exact-check-run release guards."
  (:require [clojure.string :as string]))

(def required-checks
  #{"generated-runtime"
    "test"
    "isolated-modules (eacl)"
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

(defn evaluate-checks
  "Return :ready or :pending; reject ambiguity, wrong SHA, or bad conclusions."
  [sha check-runs final?]
  (let [relevant (filter #(contains? required-checks (:name %)) check-runs)
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

(defn- environment-context
  []
  {:event-name (System/getenv "GITHUB_EVENT_NAME")
   :ref (System/getenv "GITHUB_REF")
   :ref-type (System/getenv "GITHUB_REF_TYPE")
   :sha (System/getenv "GITHUB_SHA")
   :branch-sha (System/getenv "EACL_BRANCH_SHA")
   :supplied-version (System/getenv "EACL_VERSION")})

(defn- read-check-runs
  [path]
  (let [read-json (requiring-resolve 'clojure.data.json/read-str)]
    (:check_runs (read-json (slurp path) :key-fn keyword))))

(defn -main
  [& [operation argument final-argument]]
  (case operation
    "ordinary" (println (ordinary-version (environment-context)))
    "checks"
    (println
     (name
      (evaluate-checks
       (System/getenv "GITHUB_SHA")
       (read-check-runs argument)
       (= "true" final-argument))))
    (reject! "Unknown EACL release-guard operation."
             :eacl.release/unknown-guard-operation
             {:operation operation})))
