(ns eacl.formal.generators-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.causal-model :as causal]
            [eacl.formal.generators :as generators]))

(def required-features
  #{:alias :arrow-relation :arrow-permission :recursive-scc
    :multiple-subject-types :duplicate-semantic-path
    :disconnected :cycle :diamond :fan-in :fan-out
    :empty-relation :extreme-id})

(deftest coherent-schema-and-graph-generator-test
  (doseq [seed (range 1 26)]
    (let [fixture (generators/coherent-schema seed)
          objects (set (:objects fixture))]
      (is (= fixture (generators/coherent-schema seed))
          (str "seed " seed " is deterministic"))
      (is (= required-features (:features fixture)))
      (is (= (count (:relationships fixture))
             (count (distinct (:relationships fixture)))))
      (is (every?
           (fn [{:keys [subject resource]}]
             (and (contains? objects subject)
                  (contains? objects resource)))
           (:relationships fixture)))
      (is (set? (oracle/authorization-set fixture))
          (str "oracle convergence seed=" seed))
      (is (= 3 (count (:malformed-variants fixture))))
      (is (= (select-keys fixture [:objects :relationships])
             (select-keys (generators/coherent-graph seed)
                          [:objects :relationships]))))))

(deftest request-and-state-command-coverage-test
  (let [fixture (generators/coherent-schema 77)
        requests (generators/request-cases 77 12)
        trace (generators/state-command-trace fixture)
        operations (set (map :operation trace))]
    (is (= #{:can? :lookup-resources :lookup-subjects
             :count-resources :count-subjects}
           (set (map :operation (:operations requests)))))
    (is (= #{:asc :desc}
           (set (map #(if (contains? % :last) :desc :asc)
                     (:valid-pages requests)))))
    (is (<= 0 (:random-jump requests) 11))
    (is (= 10 (count (:invalid-pages requests))))
    (is (every?
         operations
         [:read :cache-put :cache-read :unrelated-write
          :graph-write :schema-write :clone :reset :restore :branch
          :force-head :retention-expire :cursor-mint :cursor-replay
          :exact-read :history-read :exact-unavailable
          :cache-disabled-read :cache-expire :traversal-limit
          :proof-provider-failure :compute-failure
          :concurrent-identical-reads :concurrent-read-write
          :cache-tamper :cursor-tamper]))
    (is (= #{:asc :desc}
           (into
            #{}
            (keep
             #(when (= :cursor-replay (:operation %))
                (get-in % [:arguments :direction])))
            trace)))
    (is (= #{:read-first :write-first :overlap}
           (set
            (get-in
             (first
              (filter
               #(= :concurrent-read-write (:operation %))
               trace))
             [:arguments :schedules]))))))

(deftest coherence-preserving-shrinker-test
  (let [fixture (generators/coherent-schema 91)
        graph-candidates (generators/shrink-graph fixture)
        schema-candidates (generators/shrink-schema fixture)
        executable-trace
        (vec
         (remove :harness-only?
                 (generators/state-command-trace fixture)))
        initial (causal/initial-state fixture)
        trace-candidates (generators/shrink-trace initial executable-trace)]
    (is (= (count (:relationships fixture))
           (count graph-candidates)))
    (doseq [candidate graph-candidates]
      (let [objects (set (:objects candidate))]
        (is (< (count (:relationships candidate))
               (count (:relationships fixture))))
        (is (every?
             (fn [{:keys [subject resource]}]
               (and (contains? objects subject)
                    (contains? objects resource)))
             (:relationships candidate)))
        (is (set? (oracle/authorization-set candidate)))))
    (is (seq schema-candidates))
    (doseq [candidate schema-candidates]
      (is (= (set (keys (:rules fixture)))
             (set (keys (:rules candidate)))))
      (is (set? (oracle/authorization-set candidate))))
    (is (= [{:first 4} {:first 1}]
           (generators/shrink-page {:first 8})))
    (is (= (count executable-trace) (count trace-candidates)))
    (is (every? #(< (count %) (count executable-trace))
                trace-candidates))))
