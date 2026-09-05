(ns eacl.datahike.qualified-write-test
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datahike.core :as api]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.qualifiers :as qualifiers]))

(deftest qualified-batches-publish-atomically
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}] options)
          config (:config (d/db conn))]
      (try
        (batch/check! {:write-schema! #(schema/write-schema! conn %)
                       :cas-attribute (when (:attribute-refs? options) db/entid)
                       :writer #(qualifiers/writer conn) :entid db/entid :strategy :prepared})
        (finally (d/release conn) (d/delete-database config))))))

(deftest public-qualified-writes-preserve-identity-and-commit-atomically
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}] options)
          config (:config (d/db conn))
          now (atom 99)]
      (try
        (public/check! {:client (api/make-client conn {:clock #(deref now)
                                                       :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                        :writer #(qualifiers/writer conn) :entid db/entid :now now
                        :cas-attribute (when (:attribute-refs? options) db/entid)})
        (finally (d/release conn) (d/delete-database config))))))
