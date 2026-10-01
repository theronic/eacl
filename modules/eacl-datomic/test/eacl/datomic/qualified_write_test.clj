(ns eacl.datomic.qualified-write-test
  (:require [clojure.test :refer [deftest]]
            [datomic.api :as d]
            [eacl.caveats.publication-batch-contract :as batch]
            [eacl.caveats.public-write-contract :as public]
            [eacl.caveats.schema-allowance-contract :as allowance]
            [eacl.caveats.relation-removal-contract :as removal]
            [eacl.caveats.inspection-contract :as inspection]
            [eacl.caveats.partial-scan-contract :as partial-scan]
            [eacl.caveats.permission-tree-contract :as permission-tree]
            [eacl.caveats.deletion-contract :as deletion]
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

(deftest partial-relationship-walks-over-qualified-rows-are-total-and-terminate
  (let [uri (str "datomic:mem://partial-scan-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        now (atom 1000)]
    (try
      (schema/install! conn)
      (partial-scan/check! {:client (api/make-client conn {:clock #(deref now)
                                                           :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                            :writer #(qualifiers/writer conn) :now now})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest permission-trees-list-qualified-relationships-without-evaluating-them
  (let [uri (str "datomic:mem://permission-tree-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        now (atom 1000)]
    (try
      (schema/install! conn)
      (permission-tree/check! {:client (api/make-client conn {:clock #(deref now)
                                                              :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                               :writer #(qualifiers/writer conn) :now now})
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

(deftest qualified-object-deletion-is-atomic-and-bounded
  (let [uri (str "datomic:mem://qualified-deletion-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri)]
    (try
      (schema/install! conn)
      (deletion/check! {:client (api/make-client conn {:clock (constantly 200)
                                                       :caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                        :writer #(qualifiers/writer conn)})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest removing-a-relation-with-qualified-relationships-reports-relation-in-use
  (let [uri (str "datomic:mem://relation-removal-" (random-uuid))
        _ (d/create-database uri) conn (d/connect uri)]
    (try
      (schema/install! conn)
      (removal/check! {:client (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                       :writer #(qualifiers/writer conn)})
      (finally (d/release conn) (d/delete-database uri)))))
