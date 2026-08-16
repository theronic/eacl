(ns eacl.causal-model-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [eacl.authorization-oracle :as oracle]
            [eacl.causal-model :as model]
            [eacl.core :as eacl]))

(def user (eacl/spice-object :user "u"))
(def other-user (eacl/spice-object :user "other"))
(def document (eacl/spice-object :document "d"))
(def other-document (eacl/spice-object :document "other"))

(def fixture
  {:objects [user other-user document other-document]
   :relationships [(eacl/->Relationship user :reader document)]
   :rules {[:document :view] [:relation :reader]}})

(def query [user :view document])

(deftest canonical-full-content-proof-test
  (let [proof (oracle/full-content-proof fixture)]
    (is (= proof
           (oracle/full-content-proof
            (update fixture :relationships reverse))))
    (is (not= proof
              (oracle/full-content-proof
               (update fixture :relationships conj
                       (eacl/->Relationship other-user :reader document)))))
    (is (not= proof
              (oracle/full-content-proof
               (assoc-in fixture [:rules [:document :view]]
                         [:union [:relation :reader]
                          [:relation :owner]]))))))

(deftest generated-history-transitions-never-use-order-equality-test
  (doseq [commands (model/generated-divergence-traces fixture)]
    (let [states (model/run-trace (model/initial-state fixture) commands)
          token-state (second states)
          divergent-state (last states)
          token-anchor (:head token-state)]
      (is (false?
           (model/contains-anchor?
            divergent-state (:head divergent-state) token-anchor))
          (pr-str commands)))))

(deftest cache-differential-property-test
  (testing "a causal predecessor with an equal proof may lift"
    (let [state-0 (model/initial-state fixture)
          state-1 (model/apply-command state-0 (model/cache-put :q :genesis true))
          state-2 (model/apply-command state-1 (model/unrelated-write))]
      (is (model/cache-result-equals-selected? state-2 :q query))
      (is (true? (:last-result
                  (model/apply-command state-2 (model/cache-read :q)))))))

  (testing "a future or sibling candidate is never returned backward"
    (let [state-0 (model/initial-state fixture)
          future (model/apply-command state-0 (model/unrelated-write))
          cached (model/apply-command
                  future (model/cache-put :q (:head future) true))
          reset (model/apply-command cached (model/reset-head :genesis))]
      (is (nil? (:last-result
                 (model/apply-command reset (model/cache-read :q)))))
      (is (model/cache-result-equals-selected? reset :q query))))

  (testing "negative results include a relation that may later grant"
    (let [denied-query [other-user :view document]
          state-0 (model/initial-state fixture)
          state-1 (model/apply-command
                   state-0 (model/cache-put :denied :genesis false))
          changed-fixture
          (update fixture :relationships conj
                  (eacl/->Relationship other-user :reader document))
          state-2 (model/apply-command
                   state-1 (model/graph-write (:relationships changed-fixture)))]
      (is (nil? (:last-result
                 (model/apply-command state-2 (model/cache-read :denied)))))
      (is (true? (:last-result
                  (model/apply-command
                   state-2 (model/read-command denied-query)))))
      (is (model/cache-result-equals-selected?
           state-2 :denied denied-query)))))

(deftest missing-mutation-anchor-regression-test
  (let [token-state (model/apply-command
                     (model/initial-state fixture)
                     (model/graph-write (:relationships fixture)))
        token (:head token-state)
        reset (model/apply-command token-state (model/reset-head :genesis))]
    (is (false? (model/contains-anchor? reset (:head reset) token)))))

(deftest cursor-differential-property-test
  (let [query {:permission :view :resource-type :document}
        state-0 (model/install-cursor
                 (model/initial-state fixture) :cursor query)
        page-1-state (model/apply-command
                      state-0 (model/cursor-page :cursor 1))
        page-1 (:last-result page-1-state)
        unrelated (model/apply-command page-1-state (model/unrelated-write))
        page-2-state (model/apply-command
                      unrelated (model/cursor-page :cursor 10))
        page-2 (:last-result page-2-state)]
    (is (model/pages-equal-proof-graph?
         page-2-state :cursor [page-1 page-2])))

  (testing "changed proof falls back to retained exact graph"
    (let [query {:permission :view :resource-type :document}
          state-0 (model/install-cursor
                   (model/initial-state fixture) :cursor query)
          page-1-state (model/apply-command
                        state-0 (model/cursor-page :cursor 1))
          changed (model/apply-command
                   page-1-state
                   (model/graph-write
                    (conj (:relationships fixture)
                          (eacl/->Relationship
                           other-user :reader other-document))))
          page-2-state (model/apply-command
                        changed (model/cursor-page :cursor 10))]
      (is (not= :snapshot-expired (:last-error page-2-state)))
      (is (model/pages-equal-proof-graph?
           page-2-state :cursor
           [(:last-result page-1-state) (:last-result page-2-state)]))))

  (testing "changed proof with expired exact graph fails"
    (let [query {:permission :view :resource-type :document}
          state-0 (model/install-cursor
                   (model/initial-state fixture) :cursor query)
          changed (model/apply-command
                   state-0
                   (model/graph-write
                    (conj (:relationships fixture)
                          (eacl/->Relationship
                           other-user :reader other-document))))
          expired (model/apply-command
                   changed (model/expire-snapshot :genesis))
          result (model/apply-command
                  expired (model/cursor-page :cursor 1))]
      (is (= :snapshot-expired (:last-error result))))))
