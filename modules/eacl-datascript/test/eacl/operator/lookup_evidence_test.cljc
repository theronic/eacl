(ns eacl.operator.lookup-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.backend.direct-membership :as direct]
            [eacl.engine.least-path :as least-path]
            [eacl.operator.evaluator :as scalar]
            [eacl.operator.batch-schedule :as schedule]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.plan :as plan]
            [eacl.operator.seekable-evidence-test :as fixtures]))

(defn drain [options]
  (loop [boundary nil pages 0 rows []]
    (when (> pages 32) (throw (ex-info "Qualified cover did not progress" {})))
    (let [page (lookup/lookup-page (assoc options :boundary boundary))
          rows (into rows (:emissions page))]
      (if (:has-more? page)
        (recur (:resume-coords page) (inc pages) rows)
        rows))))

(deftest general-cover-completes-conditional-nodes-before-emission
  (let [{:keys [adapter user docs] :as env} (fixtures/fixture)]
    (doseq [permission [:both :allowed :either] time [99 100]
            context [{} {"flag" true} {"flag" false}]
            policy [:definite :detailed] direction [:asc :desc] traversal [:forward :reverse]]
      (let [options (assoc (fixtures/options env permission time context direction)
                           :direct-specializations? false :page-size 1 :candidate-window 2
                           :result-policy policy :traversal traversal)
            expected (into {} (keep (fn [doc]
                                      (let [result (scalar/check-eids
                                                    {:adapter adapter :plan (:plan options)
                                                     :subject-type :user
                                                     :subject-eid (if (= :forward traversal) user doc)
                                                     :resource-eid (if (= :forward traversal) doc user)
                                                     :qualification (:qualification options)})]
                                        (when (if (= :definite policy)
                                                (evidence/has? result) (not (evidence/no? result)))
                                          [doc (evidence/value result)])))) docs)
            rows (drain options)
            comparison (map #(least-path/compare-coords (:coords %1) (:coords %2)) rows (rest rows))]
        (is (= expected (into {} (map (juxt :value (comp evidence/value :evidence))) rows)))
        (is (= (count expected) (count rows)))
        (is (every? (if (= :asc direction) neg? pos?) comparison))))))

(deftest local-exact-evidence-is-bounded-and-resolution-is-request-shared
  (let [env (fixtures/fixture)
        options (assoc (fixtures/options env :both 99 {} :asc)
                       :direct-specializations? false :page-size 1 :candidate-window 2
                       :result-policy :detailed)
        request (:qualification options) reads (atom {})
        observed (qualification/request
                  (assoc request :entity (fn [eid]
                                           (swap! reads update eid (fnil inc 0))
                                           ((:entity request) eid))))]
    (is (= 4 (count (drain (assoc options :qualification observed)))))
    (is (seq @reads))
    (is (every? #(= 1 %) (vals @reads)))
    (with-redefs [lookup/maximum-local-node-evidence 1]
      (is (= :node-evidence-limit
             (:reason (fixtures/error-data #(lookup/lookup-page options))))))
    (with-redefs [lookup/maximum-local-node-evidence 3 schedule/maximum-width 1]
      (is (= 4 (:count (lookup/count-results options)))))
    (is (= :max-values
           (:limit (fixtures/error-data
                    #(lookup/lookup-page (assoc-in options [:traversal-limits :max-values] 1))))))))

(deftest a-generated-direct-node-is-not-probed-again-by-its-parent
  (let [env (fixtures/fixture)
        options (assoc (fixtures/options env :both 99 {} :asc)
                       :direct-specializations? false :page-size 10 :result-policy :detailed)
        sealed (:plan options) root (get (plan/expression-roots sealed) [:doc :both])
        source (get-in sealed [:generators [:doc :both] root :source-node])
        source-relation (get-in sealed [:predicate-programs [:doc :both] source :descriptor :partitions 0 :relation-id])
        probes (atom []) dispatch direct/dispatch-edges]
    (with-redefs [direct/dispatch-edges (fn [adapter requests]
                                        (swap! probes into requests)
                                        (dispatch adapter requests))]
      (is (= 4 (count (:emissions (lookup/lookup-page options))))))
    (is (seq @probes))
    (is (not-any? #(= source-relation (get-in % [:descriptor :relation-eid])) @probes))))
