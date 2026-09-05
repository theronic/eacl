(ns eacl.authorization.temporal-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.temporal :as temporal]
            [eacl.cache :as cache]))

(def basis-key
  {:key-version 2 :backend :test :adapter-fingerprint :temporal-test :identity-contract :identity-v1
   :basis-identity {:backend :test :source-id :source :branch nil :source-lifecycle :one
                    :basis-kind :ordinary :revision 1 :exact-locator 1
                    :backend-snapshot-id {:basis 1}}})
(def semantic-key
  {:operation :can? :query {:public [:user :view :doc]}
   :qualification [1 :basis :context :evaluator]
   :temporal-answer-format temporal/point-format})

(defn resolve! [store time proof options]
  (cache/resolve-basis! store (merge {:exact-basis-key basis-key :evaluation-time-ms time} options)
                        semantic-key #(temporal/point-answer time proof)))

(deftest certificate-boundaries-and-completeness-are-independent-of-retention
  (doseq [value [true false (evidence/conditional [:test] ["field"])]
          start [98 99 100] end [101 nil] complete? [true false]
          time [97 98 99 100 101 102] exact? [true false]]
    (let [proof (evidence/with-certificate value end complete?)
          answer (temporal/point-answer start proof)]
      (is (temporal/point-answer-valid? answer))
      (is (= (and (<= start time) (or (nil? end) (< time end))
                  (or complete? (and exact? (= start time))))
             (temporal/reusable? answer time exact?)))))
  (let [answer (temporal/point-answer 99 (evidence/with-certificate true 100 true))]
    (doseq [bad [(assoc answer :kind :conditional-permission)
                 (assoc answer :valid-until-ms 200)
                 (assoc answer :complete? false)
                 (assoc answer :start-ms 100)
                 (assoc answer :extra true)
                 (assoc answer :value (evidence/encode (evidence/fault :test/fault :invalid)))]]
      (is (false? (temporal/point-answer-valid? bad))))))

(deftest resident-expiration-is-a-miss-and-later-publication-replaces-the-interval
  (let [store (cache/basis-cache {:max-entries 8})
        grant (evidence/with-certificate true 100 true)]
    (is (false? (:cached? (resolve! store 99 grant {}))))
    (is (:cached? (resolve! store 99 grant {})))
    (let [resident (cache/export-basis-snapshot store {:max-entries 8})
          expired (resolve! store 100 false {:populate-cache? false})]
      (is (false? (:cached? expired)))
      (is (= :no-permission (get-in expired [:value :kind])))
      (is (= resident (cache/export-basis-snapshot store {:max-entries 8}))
          "a rejected interval can remain physically resident without changing authorization"))
    (is (false? (:cached? (resolve! store 101 false {}))))
    (is (:cached? (resolve! store 102 false {})))
    (is (false? (:cached? (resolve! store 99 grant {}))) "older pinned computation cannot reuse a future interval")
    (is (:cached? (resolve! store 102 false {})) "older publication did not displace the newer interval")))

(deftest expired-bans-and-incomplete-certificates-never-reuse-a-stale-decision
  (let [store (cache/basis-cache {:max-entries 8})
        denial (evidence/with-certificate false 100 true)]
    (is (= :no-permission (get-in (resolve! store 99 denial {}) [:value :kind])))
    (is (:cached? (resolve! store 99 denial {})))
    (let [allowed (resolve! store 100 true {})]
      (is (false? (:cached? allowed)))
      (is (= :has-permission (get-in allowed [:value :kind])))))
  (let [store (cache/basis-cache {:max-entries 8})
        unknown (evidence/with-certificate true nil false)]
    (resolve! store 99 unknown {})
    (is (:cached? (resolve! store 99 unknown {})))
    (is (false? (:cached? (resolve! store 100 unknown {}))))
    (is (false? (:cached? (resolve! store 101 unknown {}))))))
