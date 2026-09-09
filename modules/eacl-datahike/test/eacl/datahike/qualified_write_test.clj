(ns eacl.datahike.qualified-write-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.write-contention-contract :as contention]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.deletion-contract :as deletion]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.client.orchestration :as orchestration]
            [eacl.caveats.definition-test :as errors]
            [eacl.caveats.schema-admission-test :as schemas]
            [eacl.core :as eacl]
            [eacl.datahike.core :as api]
            [eacl.datahike.caveat-schema-test :as schema-races]
            [eacl.datahike.db :as db]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.storage :as admission]
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

(deftest schema-alternatives-preserve-relation-identities-and-retained-data
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [] options) config (:config (d/db conn))]
      (try
        (allowance/check! {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                           :writer #(qualifiers/writer conn)
                           :read-schema schema/read-schema :interleave! schema-races/interleave! :entid db/entid})
        (finally (d/release conn) (d/delete-database config))))))

(deftest externally-created-connection-supports-qualified-preparation-and-snapshot-publication
  (let [config (update schema/default-config :store assoc :id (random-uuid))
        _ (d/create-database config)
        conn (d/connect config)
        now (atom 99)]
    (try
      (d/transact conn (schema/merge-schema [{:db/ident :app/flag :db/valueType :db.type/long
                                              :db/cardinality :db.cardinality/one}]))
      (admission/bootstrap! conn)
      (is (nil? (get (:config (d/db conn)) schema/live-source-id-key)))
      (public/check! {:client (api/make-client conn {:clock #(deref now)
                                                     :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                      :writer #(qualifiers/writer conn) :entid db/entid :now now})
      (finally (d/release conn) (d/delete-database config)))))

(deftest remote-writer-cannot-admit-a-qualified-schema
  (let [conn (schema/create-conn) config (:config (d/db conn))]
    (try
      (let [client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})]
        (binding [orchestration/*qualified-authorization-enabled?* true]
          (with-redefs [db/direct-writer? (constantly false)]
            (is (nil? (qualifiers/publication-capability (d/db conn))))
            (is (= :eacl/unsupported-capability
                   (errors/error-type #(eacl/write-schema! client {:schema (schemas/source "user with enabled")})))))))
      (finally (d/release conn) (d/delete-database config)))))

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}] options)
          config (:config (d/db conn))
          now (atom 99)]
      (try
        (inspection/check! {:client (api/make-client conn {:clock #(deref now)
                                                           :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                            :writer #(qualifiers/writer conn) :entid db/entid :now now
                            :cas-attribute (when (:attribute-refs? options) db/entid)})
        (finally (d/release conn) (d/delete-database config))))))

(deftest qualified-cache-traces-match-uncached-authorization
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [] options) config (:config (d/db conn)) now (atom 99)]
      (try
        (cache-trace/check! {:client (api/make-client conn {:clock #(deref now)
                                                            :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                             :writer #(qualifiers/writer conn) :now now :expire-cache! api/expire-cache!})
        (finally (d/release conn) (d/delete-database config))))))

(deftest qualified-object-deletion-is-atomic-and-bounded
  (doseq [options [{} {:attribute-refs? true}]]
    (let [conn (schema/create-conn [] options) config (:config (d/db conn))]
      (try
        (deletion/check! {:client (api/make-client conn {:clock (constantly 200)
                                                         :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                          :writer #(qualifiers/writer conn)})
        (finally (d/release conn) (d/delete-database config))))))

(deftest qualified-native-cas-contention-replans-from-a-new-basis
  (let [conn (schema/create-conn)
        client (api/make-client conn {})]
    (contention/check! client #(qualifiers/writer conn))
    (contention/terminal-validation-check! client)))
