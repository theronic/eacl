(ns eacl.datascript.qualified-cache-trace-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.caveats.cache-trace-contract :as trace]
            [datascript.core :as ds]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datascript.qualified-check-test :as checks]
            [eacl.datascript.qualified-lookup-test :as lookups]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datascript.core :as api]
            [eacl.datascript.qualifiers :as qualifiers]))

(deftest qualified-cache-traces-match-uncached-authorization
  (let [conn (api/create-conn) now (atom 99)
        client (api/make-client conn {:clock #(deref now) :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (trace/check! {:client client :writer #(qualifiers/writer conn) :now now :expire-cache! api/expire-cache!})))

(deftest unknown-writer-faults-and-restored-entity-identities-never-reuse-old-answers
  (let [{:keys [conn client check]} (checks/fixture)
        check (assoc check :permission :both :caveat-context {"flag" true})
        original (ds/db conn)
        qid (ds/q '[:find ?e . :where [?e :eacl.relationship-qualifier/valid-until-ms 100]] original)
        point #(dissoc (eacl/check-permission client (merge check %)) :cached? :cache-basis :evaluation)
        operations (into [point]
                         (for [direction [:forward :reverse] policy [:definite :detailed]
                               kind [:count :lookup]
                               :let [query (lookups/count-query check direction policy {"flag" true})]]
                           (fn [options]
                             (let [query (merge query options {:evaluation :complete-denotation})]
                               (if (= :count kind)
                                 (dissoc (lookups/count! client direction query) :cached? :cache-basis)
                                 (trace/drain (if (= :forward direction) eacl/lookup-resources eacl/lookup-subjects)
                                              client query))))))]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (is (= :no-permission (:permissionship (trace/compare-operation! :healthy point))))
      (doseq [mutate! [#(ds/transact! conn [[:db/add qid :unknown/field "invalid"]])
                       #(ds/transact! conn [[:db/retractEntity qid]])]]
        (mutate!)
        (doseq [read operations]
          (let [results (mapv #(trace/outcome (fn [] (read %)))
                              [{:cache? false} {} {} {:populate-cache? false}])]
            (is (apply = results))
            (is (= :eacl.authorization/evaluation-failure (get-in (first results) [:fault :type])))))
        (is (false? (eacl/can? client check))))
      (ds/reset-conn! conn original)
      (api/expire-cache! client "qualified-restored-eid-lifecycle")
      (is (= :no-permission (:permissionship (trace/compare-operation! :restored point))))
      (ds/transact! conn [[:db/add qid qualifier/expiration-attribute 99]])
      (is (= :has-permission (:permissionship (trace/compare-operation! :unstamped-ban-expiry point)))))))
