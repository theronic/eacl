(ns eacl.formal.qualified.production-mutations
  "Mutation controls at actual implementation seams, mapped to independent
   conformance tests. Every control first checks its unmodified gate."
  (:require [clojure.test :as t :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.clock :as clock]
            [eacl.authorization.evidence-test :as evidence-test]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as qualification-test]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.engine.scan-cache-test :as scan-test]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route-native-evidence-test :as native-test]
            [eacl.datascript.evaluation-clock-test :as clock-test]
            [eacl.client.orchestration :as orchestration]
            [eacl.formal.qualified.recursive-bridge :as recursive-bridge]
            [eacl.formal.qualified.seekable-bridge :as seekable-bridge]
            [eacl.operator.seekable :as seekable]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.seekable-evidence-test :as seekable-test]
            [eacl.operator.recursive :as recursive]
            [eacl.operator.vector-evaluator :as vector]
            [eacl.operator.vector-evaluator-test :as vector-test]))

(defn failures [gate]
  (let [events (atom [])]
    (with-redefs [t/report (fn [event] (when (#{:fail :error} (:type event)) (swap! events conj event)))]
      (try (gate)
           (catch Throwable error (swap! events conj {:type :error :actual error}))))
    (count @events)))

(defn mutation-cases []
  (let [qualify qualification/qualify identity qualification/exact-reuse-identity
        fetch reducer/adapter-fetch-fn descriptor scan-cache/descriptor-key
        snapshot-opts @#'orchestration/snapshot-opts
        head-evidence @#'seekable/head-evidence
        count-categories @#'lookup/count-categories
        enqueue @#'recursive/enqueue-evidence!]
    {:qualifier-reference-ignored
     {:gate #'qualification-test/exclusive-expiry-precedes-program-work
      :redefs {#'qualification/qualify (fn [_ _ value] (some? value))}}
     :expiry-boundary-retains-permission
     {:gate #'qualification-test/exclusive-expiry-precedes-program-work
      :redefs {#'qualification/qualify (fn [request relation value]
                                       (qualify (update request :time dec) relation value))}}
     :authoritative-failure-becomes-plain
     {:gate #'qualification-test/authoritative-errors-survive-expiry-and-exclusion
      :redefs {#'qualification/qualify (fn [& args]
                                       (let [result (apply qualify args)]
                                         (if (evidence/fault? result) true result)))}}
     :conditional-becomes-truthy
     {:gate #'native-test/native-compact-scans-and-memos-preserve-qualified-point-evidence
      :redefs {#'evidence/has? (fn [value] (boolean (evidence/value value)))}}
     :fault-becomes-absence
     {:gate #'qualification-test/authoritative-errors-survive-expiry-and-exclusion
      :redefs {#'evidence/fault (fn [_ _] false)}}
     :latest-expiry-replaces-earliest
     {:gate #'evidence-test/temporal-certificate-uses-decisive-evidence
      :redefs {#'evidence/meet (fn [a b] (if (and a b) (max a b) (or a b)))}}
     :expired-evidence-reused
     {:gate #'evidence-test/temporal-certificate-uses-decisive-evidence
      :redefs {#'evidence/reusable? (fn [value start time]
                                    (and (evidence/complete? value) (not (evidence/fault? value)) (<= start time)))}}
     :scan-shape-omitted-from-cache
     {:gate #'scan-test/compact-and-ordinary-responses-never-share-memo-or-resident-prefixes-test
      :redefs {#'scan-cache/descriptor-key (fn [d] (descriptor (dissoc d :include-qualifier?)))}}
     :compact-flag-dropped-before-native-scan
     {:gate #'native-test/native-compact-scans-and-memos-preserve-qualified-point-evidence
      :redefs {#'reducer/adapter-fetch-fn (fn [adapter]
                                         (let [inner (fetch adapter)]
                                           (fn [d] (inner (dissoc d :include-qualifier?)))))}}
     :context-omitted-from-exact-point-scope
     {:gate #'vector-test/qualified-vectors-retain-alignment-and-exact-cache-scope
      :redefs {#'qualification/exact-reuse-identity (fn [request] (assoc (identity request) 3 nil))}}
     :time-omitted-from-exact-point-scope
     {:gate #'vector-test/qualified-vectors-retain-alignment-and-exact-cache-scope
      :redefs {#'qualification/exact-reuse-identity (fn [request] (assoc (identity request) 2 nil))}}
     :evidence-witness-validation-bypassed
     {:gate #'vector-test/exact-evidence-witnesses-avoid-rechecking-proven-nodes
      :redefs {#'vector/validate-evidence-witnesses! (fn [& _] nil)}}
     :cached-grant-hides-encountered-witness-fault
     {:gate #'vector-test/exact-evidence-witnesses-avoid-rechecking-proven-nodes
      :redefs {#'vector/demanded-witness-fault (constantly nil)}}
     :raw-clock-regresses
     {:gate #'clock-test/client-samples-once-and-snapshots-pin-time
      :redefs {#'clock/clock clojure.core/identity}}
     :snapshot-resamples-time
     {:gate #'clock-test/client-samples-once-and-snapshots-pin-time
      :redefs {#'orchestration/snapshot-opts (fn [runtime basis]
                                            (dissoc (snapshot-opts runtime basis) :evaluation-time-ms))}}
     :conditional-seekable-head-becomes-definite
     {:gate #'seekable-test/direct-specializations-carry-exact-qualified-evidence
      :redefs {#'seekable/head-evidence (fn [cursor]
                                        (let [value (head-evidence cursor)]
                                          (evidence/with-certificate true (evidence/valid-until value)
                                                                     (evidence/complete? value))))}}
     :seekable-emission-loses-expiry-certificate
     {:gate #'seekable-bridge/direct-page-algebra-and-exhaustive-temporal-certificates
      :redefs {#'seekable/head-evidence (fn [cursor]
                                        (evidence/with-certificate (head-evidence cursor) nil true))}}
     :definite-lookup-includes-conditional-results
     {:gate #'seekable-test/lookup-and-count-project-exact-generator-evidence-without-rechecking
      :redefs {#'lookup/result-policy (constantly :detailed)}}
     :count-categories-include-lookahead-sentinel
     {:gate #'seekable-test/detailed-count-cap-excludes-the-sentinel-from-category-counts
      :redefs {#'lookup/count-categories (fn [categories entries remaining]
                                         (count-categories categories entries
                                                           (when remaining (inc remaining))))}}
     :recursive-membership-stops-before-certificate-convergence
     {:gate #'recursive-bridge/qualified-positive-scc-refinement-and-temporal-stability
      :redefs {#'recursive/enqueue-evidence!
               (fn [state head value limits counters]
                 (if (= (evidence/value value) (evidence/value (get (:facts state) head false)))
                   state
                   (enqueue state head value limits counters)))}}}))

(deftest production-mutations-are-killed-by-conformance-gates
  (let [cases (mutation-cases)]
    (is (= 20 (count cases)))
    (doseq [[id {:keys [gate redefs]}] (sort-by key cases)]
      (is (zero? (failures gate)) (str id " unmodified gate must pass"))
      (is (pos? (with-redefs-fn redefs #(failures gate))) (str id " must be detected")))))
