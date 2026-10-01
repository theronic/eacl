(ns eacl.datascript.qualified-write-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.write-contention-contract :as contention]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.partial-scan-contract :as partial-scan]
            [eacl.caveats.permission-tree-contract :as permission-tree]
            [eacl.caveats.deletion-contract :as deletion]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.core :as eacl]
            [eacl.client.orchestration :as orchestration]
            [eacl.relationships.storage :as storage]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datascript.core :as api]
            [eacl.datascript.caveat-schema-test :as schema-races]
            [eacl.datascript.schema :as schema]
            [eacl.datascript.storage :as admission]
            [eacl.datascript.qualifiers :as qualifiers]))

(deftest qualified-batches-publish-atomically
  (let [conn (schema/create-conn {:app/flag {}})]
    (batch/check! {:write-schema! #(schema/write-schema! conn %)
                   :writer #(qualifiers/writer conn) :entid ds/entid :strategy :prepared})))

(deftest public-qualified-writes-preserve-identity-and-commit-atomically
  (let [conn (schema/create-conn {:app/flag {}})
        now (atom 99)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (public/check! {:client client :writer #(qualifiers/writer conn) :entid ds/entid :now now})))

(deftest schema-alternatives-preserve-relation-identities-and-retained-data
  (let [conn (schema/create-conn)
        client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (allowance/check! {:client client :writer #(qualifiers/writer conn)
                       :read-schema schema/read-schema :interleave! schema-races/interleave! :entid ds/entid})))

(deftest externally-created-connection-supports-qualified-preparation-and-snapshot-publication
  (let [conn (ds/create-conn (schema/merge-schema {:app/flag {}}))
        _ (admission/bootstrap! conn)
        now (atom 99)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (is (nil? (:eacl.datascript/source-id (ds/entity (ds/db conn) [:eacl/id "datascript-metadata"]))))
    (public/check! {:client client :writer #(qualifiers/writer conn) :entid ds/entid :now now})))

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (let [conn (schema/create-conn {:app/flag {}})
        now (atom 99)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (inspection/check! {:client client :writer #(qualifiers/writer conn) :entid ds/entid :now now})))

(deftest partial-relationship-walks-over-qualified-rows-are-total-and-terminate
  (let [conn (schema/create-conn)
        now (atom 1000)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (partial-scan/check! {:client client :writer #(qualifiers/writer conn) :now now})))

(deftest permission-trees-list-qualified-relationships-without-evaluating-them
  (let [conn (schema/create-conn)
        now (atom 1000)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (permission-tree/check! {:client client :writer #(qualifiers/writer conn) :now now})))

(deftest eacl-rs-005-expansions-list-expiring-and-caveated-relationships
  ;; eacl-rust BUGS.md EACL-RS-005 and PR206-F1: each expansion failed with
  ;; :eacl.permission-tree/adapter-contract-violation, also after the grant
  ;; expired, while the plain control expanded.
  (binding [orchestration/*qualified-authorization-enabled?* true]
    (doseq [{:keys [schema qualifier]}
            [{:schema "definition user {}\ndefinition doc {\n  relation viewer: user\n  permission view = viewer\n}\n"
              :qualifier {:valid-until-ms 5000}}
             {:schema "caveat c1(flag bool) {\n  flag\n}\ndefinition user {}\ndefinition doc {\n  relation viewer: user | user with c1\n  permission view = viewer\n}\n"
              :qualifier {:caveat "c1"}}
             {:schema "definition user {}\ndefinition doc {\n  relation viewer: user\n  permission view = viewer\n}\n"
              :qualifier {}}]]
      (let [conn (schema/create-conn)
            now (atom 1000)
            client (api/make-client conn {:clock #(deref now)
                                          :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
            a (eacl/spice-object :user "a")
            b (eacl/spice-object :user "b")
            d (eacl/spice-object :doc "d")
            viewers {:expanded-object d :expanded-relation :viewer
                     :leaf {:subjects [(merge a qualifier) b]}}
            view {:expanded-object d :expanded-relation :view
                  :intermediate {:operation :union :children [viewers]}}
            expand #(:tree-root (eacl/expand-permission-tree client {:resource d :permission %}))]
        (eacl/write-schema! client schema)
        (ds/transact! conn [{:eacl/id "a"} {:eacl/id "b"} {:eacl/id "d"}])
        (eacl/write-relationships!
         client [{:operation :create :relationship (merge (eacl/->Relationship a :viewer d) qualifier)}
                 {:operation :create :relationship (eacl/->Relationship b :viewer d)}])
        (is (= viewers (expand :viewer)) (pr-str qualifier))
        (is (= view (expand :view)) (pr-str qualifier))
        (reset! now 9000)
        (is (= view (expand :view)) "an expired grant stays listed with its deadline")
        (is (true? (eacl/can? client b :view d)))))))

(deftest qualified-object-deletion-is-atomic-and-bounded
  (let [conn (schema/create-conn)
        client (api/make-client conn {:clock (constantly 200)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (deletion/check! {:client client :writer #(qualifiers/writer conn)})))

(deftest qualified-object-deletion-cleans-surviving-peers-and-unblocks-schema-removal
  (let [conn (schema/create-conn)
        client (api/make-client conn {:clock (constantly 200)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        subject (eacl/spice-object :user "deleted/u")
        resource (eacl/spice-object :doc "deleted/doc")
        replacement "definition user {}\ndefinition doc {\n relation member: user\n permission direct = member\n}"]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (eacl/write-schema! client {:schema (cache-trace/schema "flag")})
      (ds/transact! conn [{:eacl/id "deleted/u"} {:eacl/id "deleted/doc"}])
      (eacl/create-relationship! client (assoc (eacl/->Relationship subject :member resource)
                                               :caveat "enabled" :valid-until-ms 100))
      (let [before (ds/db conn)
            sid (ds/entid before [:eacl/id "deleted/u"])
            qid (:e (first (ds/datoms before :aevt :eacl.relationship-qualifier/format-version)))
            failure (cache-trace/outcome #(eacl/write-schema! client {:schema replacement}))]
        (is (contains? #{:eacl.schema/caveat-in-use :eacl.schema/relationship-qualifier-in-use}
                       (get-in failure [:fault :type])))
        (is (identical? before (ds/db conn)))
        (ds/transact! conn [[:db/retractEntity sid]])
        (is (= 1 (count (ds/datoms (ds/db conn) :aevt storage/reverse-attribute))))
        (eacl/delete-object-by-eid! client sid)
        (is (empty? (ds/datoms (ds/db conn) :aevt storage/reverse-attribute)))
        (is (empty? (ds/datoms (ds/db conn) :eavt qid)))
        (is (not (contains? (cache-trace/outcome #(eacl/write-schema! client {:schema replacement})) :fault)))))))

(deftest a-v8-0-0-caveat-source-is-rewritten-once
  ;; EACL v8.0.0 stored a Caveat's whole body; EACL now stores the CEL
  ;; expression SpiceDB reads from it. The first write of the unchanged schema
  ;; rewrites the stored source in place, and the next one is a no-op.
  (let [conn (schema/create-conn)
        client (api/make-client conn {:clock (constantly 50)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
        text "caveat enabled(flag bool) {\n  flag\n}\ndefinition user {}\ndefinition doc {\n relation member: user | user with enabled\n permission view = member\n}"
        subject (eacl/spice-object :user "upgrade/u")
        resource (eacl/spice-object :doc "upgrade/doc")
        caveat-eid #(ds/entid (ds/db conn) [:eacl.caveat/name "enabled"])
        source #(:eacl.caveat/expression-source (ds/entity (ds/db conn) (caveat-eid)))
        permissionship #(:permissionship (eacl/check-permission
                                          client {:subject subject :permission :view :resource resource
                                                  :caveat-context {"flag" true} :cache? false}))]
    (binding [orchestration/*qualified-authorization-enabled?* true]
      (eacl/write-schema! client text)
      (is (= "flag\n" (source)))
      (ds/transact! conn [{:eacl/id "upgrade/u"} {:eacl/id "upgrade/doc"}])
      (eacl/create-relationship! client (assoc (eacl/->Relationship subject :member resource)
                                               :caveat "enabled"))
      (let [eid (caveat-eid)]
        (ds/transact! conn [[:db/add eid :eacl.caveat/expression-source "\n  flag\n"]])
        (is (= :has-permission (permissionship)) "a v8.0.0 source evaluates")
        (is (false? (:eacl.schema/no-op? (eacl/write-schema! client text))))
        (is (= "flag\n" (source)))
        (is (= eid (caveat-eid)) "the Caveat keeps its identity")
        (is (= :has-permission (permissionship)))
        (is (true? (:eacl.schema/no-op? (eacl/write-schema! client text))))))))

(deftest qualified-native-cas-contention-replans-from-a-new-basis
  (let [conn (schema/create-conn)
        client (api/make-client conn {})]
    (contention/check! client #(qualifiers/writer conn))
    (contention/terminal-validation-check! client)))
