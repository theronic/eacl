(ns eacl.engine.stable-route-native-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.engine.sealed-plan :as plan]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(deftest native-compact-scans-and-memos-preserve-qualified-point-evidence
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn
                          "caveat enabled(flag bool) { flag }
                          definition user {}
                          definition doc {
                            relation reader: user
                            relation parent: doc
                            permission direct = reader
                            permission via = parent->reader
                            permission inherited = parent->direct
                            permission view = reader + parent->view
                          }")
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "root"} {:eacl/id "leaf"}])
    (let [eid #(ds/entid (ds/db conn) [:eacl/id %])
          user (eid "user") root (eid "root") leaf (eid "leaf")
          relation (fn [name type]
                     (ds/entid (ds/db conn) [:eacl.relation/resource-type+relation-name+subject-type [:doc name type]]))
          reader (relation :reader :user) parent (relation :parent :doc)
          caveat (ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn [(hash-map :db/id reader :eacl.relation/caveats [caveat]
                                    :eacl.relation/allows-unqualified? true)])
      (staged/write! writer :create [:user user reader :doc leaf] {:caveat caveat})
      (staged/write! writer :create [:doc leaf parent :doc root] {:valid-until-ms 100})
      (staged/write! writer :create [:doc root parent :doc leaf] nil)
      (let [db (ds/db conn) adapter (backend/basis-adapter db {})
            fetch (scan-cache/caching-fetch-fn (reducer/adapter-fetch-fn adapter)
                                               {:memo (scan-cache/memo)})
            run (fn [permission resource time context]
                  (route/check-eids {:adapter adapter :fetch-fn fetch
                                     :plan (plan/seal-plan adapter [:doc permission])
                                     :subject-type :user :subject-eid user :resource-eid resource
                                     :physical-chunk-size 1
                                     :qualification (fixtures/qualified-request db time context)}))]
        (doseq [permission [:via :inherited :view] context [{} {"flag" true} {"flag" false}]]
          (let [before (run permission root 99 context)
                after (run permission root 100 context)]
            (is (= (case (get context "flag" :missing)
                     :missing :conditional-permission true :has-permission false :no-permission)
                   (evidence/permissionship before)))
            (is (= (when-not (= false (get context "flag")) 100) (evidence/valid-until before)))
            (is (false? after))))
        (is (= :conditional-permission (evidence/permissionship (run :direct leaf 100 {}))))
        (let [qid (edge/qualifier-id (impl/direct-edge db :user user reader :doc leaf))]
          (ds/transact! conn [[:db.fn/retractEntity qid]])
          (let [damaged (ds/db conn) adapter (backend/basis-adapter damaged {})
                check (fn [permission resource time]
                        (route/check-eids {:adapter adapter
                                           :plan (plan/seal-plan adapter [:doc permission])
                                           :subject-type :user :subject-eid user :resource-eid resource
                                           :qualification (fixtures/qualified-request damaged time {})}))]
            (is (evidence/fault? (check :direct leaf 100)))
            (is (false? (check :via root 100)))
            (is (false? (check :view root 100)))))))))
