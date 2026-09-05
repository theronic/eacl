(ns eacl.datomic.qualified-write-test
  (:require [clojure.test :refer [deftest]]
            [datomic.api :as d]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.cache-trace-contract :as cache-trace]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.datomic.core :as api]
            [eacl.datomic.caveat-schema-test :as schema-races]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.qualifiers :as qualifiers]))

(deftest qualified-batches-publish-atomically
  (let [uri (str "datomic:mem://qualified-batch-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri)]
    (try
      (schema/install! conn)
      @(d/transact conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
      (batch/check! {:write-schema! #(schema/write-schema! conn %)
                     :writer #(qualifiers/writer conn) :entid d/entid :strategy :inline})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest public-qualified-writes-preserve-identity-and-commit-atomically
  (let [uri (str "datomic:mem://public-qualified-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        now (atom 99)]
    (try
      (schema/install! conn)
      @(d/transact conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
      (public/check! {:client (api/make-client conn {:clock #(deref now)
                                                     :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                      :writer #(qualifiers/writer conn) :entid d/entid :now now})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest schema-alternatives-preserve-relation-identities-and-retained-data
  (let [uri (str "datomic:mem://schema-allowance-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri)]
    (try
      (schema/install! conn)
      (allowance/check! {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                         :writer #(qualifiers/writer conn)
                         :read-schema schema/read-schema :interleave! schema-races/interleave! :entid d/entid})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest stored-and-active-inspection-preserve-aligned-native-qualifiers
  (let [uri (str "datomic:mem://public-qualified-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        now (atom 99)]
    (try
      (schema/install! conn)
      @(d/transact conn [{:db/ident :app/flag :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
      (inspection/check! {:client (api/make-client conn {:clock #(deref now)
                                                         :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                          :writer #(qualifiers/writer conn) :entid d/entid :now now})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest qualified-cache-traces-match-uncached-authorization
  (let [uri (str "datomic:mem://qualified-cache-trace-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri) now (atom 99)]
    (try
      (schema/install! conn)
      (cache-trace/check! {:client (api/make-client conn {:clock #(deref now)
                                                          :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                           :writer #(qualifiers/writer conn) :now now :expire-cache! api/expire-cache!})
      (finally (d/release conn) (d/delete-database uri)))))
