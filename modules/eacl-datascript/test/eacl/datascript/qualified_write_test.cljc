(ns eacl.datascript.qualified-write-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
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
