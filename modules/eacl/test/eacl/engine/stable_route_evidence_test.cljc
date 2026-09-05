(ns eacl.engine.stable-route-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-test :as completions]
            [eacl.authorization.qualification :as qualification]
            [eacl.engine.stable-route :as route]
            [eacl.relationships.edge :as edge]))

(def root [:doc :view])
(def target [:group :member])
(defn direct [node relation]
  {:rule :relation :resource-type (first node) :subject-type :user
   :node node :relation-eid relation})
(defn arrow [node via target-node]
  {:rule :arrow-permission :resource-type (first node) :node node
   :via-relation-eid via :intermediate-type (first target-node)
   :target-node target-node})
(defn sealed [rules] {:root root :indexes {:reverse-rules rules}})

(defn run
  "Physical fixture contains [subject relation resource qualifier] rows.
   The independent qualification injection keeps this test about traversal."
  [plan rows leaves & [extra]]
  (let [calls (atom [])
        reads (atom [])
        fetch (fn [{:keys [operation subject-eid resource-eid relation-eid
                          bound-eid limit include-qualifier?] :as command}]
                (swap! calls conj command)
                (->> rows
                     (keep (fn [[s r o q]]
                             (when (and (= r relation-eid)
                                        (= (if (= operation :subject->resources) s o)
                                           (if (= operation :subject->resources) subject-eid resource-eid)))
                               (let [eid (if (= operation :subject->resources) o s)]
                                 (when (or (nil? bound-eid) (> eid bound-eid))
                                   (if include-qualifier? (edge/pack eid q) eid))))))
                     (sort-by edge/endpoint) (take limit) vec))
        answer (with-redefs [qualification/qualify
                             (fn [_ relation value]
                               (swap! reads conj [relation value])
                               (if-let [qid (edge/qualifier-id value)]
                                 (get leaves qid)
                                 (some? value)))]
                 (route/check-eids
                  (merge {:plan plan :fetch-fn fetch :subject-type :user
                          :subject-eid 1 :resource-eid 100
                          :qualification ::injected :physical-chunk-size 1}
                         extra)))]
    {:answer answer :commands @calls :qualification-reads @reads}))

(deftest direct-union-retains-residuals-deadlines-and-failures
  (let [plan (sealed {root [(direct root 10) (direct root 11)]})
        rows [[1 10 100 101] [1 11 100 102]]
        x (evidence/with-certificate completions/x 110 true)
        y (evidence/with-certificate completions/y 120 true)
        answer (:answer (run plan rows {101 x 102 y}))]
    (is (= #{1 2 3} (completions/completions answer [completions/x completions/y])))
    (is (= 110 (evidence/valid-until answer)))
    (is (= ["x" "y"] (evidence/missing-fields answer)))
    (is (evidence/fault? (:answer (run plan rows {101 (evidence/fault :test/failure :invalid)
                                                               102 true}))))
    (is (true? (:answer (run plan rows {101 true 102 (evidence/fault :test/failure :invalid)}))))))

(deftest bidirectional-arrows-continue-past-conditional-and-expired-candidates
  (doseq [rule [(arrow root 20 target)
               {:rule :arrow-relation :resource-type :doc :via-relation-eid 20
                :intermediate-type :group :target-relation-eid 30 :target-subject-type :user}]]
    (let [plan (sealed {root [rule] target [(direct target 30)]})
          rows [[2 20 100 201] [3 20 100 202] [1 30 2 301] [1 30 3 302]]
          result (run plan rows {201 completions/x 202 completions/y 301 true 302 true})]
      (is (= #{1 2 3} (completions/completions (:answer result) [completions/x completions/y])))
      (is (every? :include-qualifier? (:commands result)))
      (is (true? (:answer (run plan rows {201 completions/x 202 true 301 true 302 true}))))
      (let [expired (run plan rows {201 false 202 false
                                   301 (evidence/fault :test/failure :invalid)
                                   302 (evidence/fault :test/failure :invalid)})]
        (is (false? (:answer expired)))
        (is (not-any? #(= 30 (first %)) (:qualification-reads expired)))))))

(deftest recursive-prefixes-join-alternate-paths-and-stop-on-cycles
  (let [plan (sealed {root [(arrow root 20 root) (direct root 10)]})
        rows [[2 20 100 201] [3 20 100 202] [4 20 2 nil] [4 20 3 nil]
              [2 20 4 nil] [1 10 4 nil]]
        x (evidence/with-certificate completions/x 110 true)
        y (evidence/with-certificate completions/y 120 true)
        result (run plan rows {201 x 202 y})]
    (is (= #{1 2 3} (completions/completions (:answer result) [completions/x completions/y])))
    (is (= 110 (evidence/valid-until (:answer result))))
    (is (evidence/complete? (:answer result)))
    (is (< (count (:commands result)) 50))))
