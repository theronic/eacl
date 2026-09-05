(ns eacl.datascript.qualified-write-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.deletion-contract :as deletion]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.core :as eacl]
            [eacl.client.orchestration :as orchestration]
            [eacl.relationships.storage :as storage]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datascript.core :as api]
            [eacl.datascript.caveat-schema-test :as schema-races]
            [eacl.datascript.schema :as schema]
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

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (let [conn (schema/create-conn {:app/flag {}})
        now (atom 99)
        client (api/make-client conn {:clock #(deref now)
                                      :caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
    (inspection/check! {:client client :writer #(qualifiers/writer conn) :entid ds/entid :now now})))

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
        (eacl/delete-object! client (eacl/spice-object :user sid))
        (is (empty? (ds/datoms (ds/db conn) :aevt storage/reverse-attribute)))
        (is (empty? (ds/datoms (ds/db conn) :eavt qid)))
        (is (not (contains? (cache-trace/outcome #(eacl/write-schema! client {:schema replacement})) :fault)))))))
