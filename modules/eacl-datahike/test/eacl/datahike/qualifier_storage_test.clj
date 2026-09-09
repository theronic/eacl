(ns eacl.datahike.qualifier-storage-test
  (:require [eacl.datahike.qualifiers :as qualifiers]
            [eacl.relationships.qualifier-sweep-contract :as sweep]
            [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [eacl.datahike.core :as api]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.safe-retraction :as safe]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.storage :as admission]
            [eacl.relationships.storage-contract :as contract]))

(defn direct-probe [& args]
  (let [calls (atom 0)
        seek d/seek-datoms]
    (try
      (with-redefs [d/seek-datoms (fn [& args] (swap! calls inc) (apply seek args))]
        (apply impl/direct-match? args))
      (finally (is (= 1 @calls) "one native seek per identity probe")))))

(deftest qualified-storage-fails-closed-and-cleans-exactly-test
  (let [conn (schema/create-conn)
        config (:config (d/db conn))
        _ (safe/prepare! conn)]
    (try
      (contract/exercise-qualified-corruption!
       {:client (api/make-client conn {}) :direct-probe direct-probe
          :read-identity impl/find-one-relationship-id
          :plan-create #(impl/tx-update-relationship %1 {:operation :create :relationship %2})
          :snapshot #(d/db conn)
        :transact! #(d/transact conn %) :entid ddb/entid
        :stamp #(vector :db/add % :eacl/relation-version :db/current-tx)
        :rows #(d/datoms %1 {:index :aevt :components [%2]}) :safe-retract! #(d/transact conn (safe/retract-entity-tx-data (d/db conn) %))})
      (finally (d/release conn) (d/delete-database config)))))

(deftest bootstrap-is-idempotent-and-validates-completed-storage-test
  (let [conn (schema/create-conn)
        config (:config (d/db conn))]
    (try
      (contract/exercise-bootstrap!
       {:bootstrap! #(admission/bootstrap! conn)
        :evidence #(admission/evidence (d/db conn))
        :transact! #(d/transact conn %)})
      (finally (d/release conn) (d/delete-database config)))))

(defn with-sweep-fixture [f]
  (doseq [options [nil {:attribute-refs? true} {:schema-flexibility :read}]]
    (let [conn (schema/create-conn nil options) config (:config (d/db conn))]
      (try
        (f {:client (api/make-client conn {}) :snapshot #(d/db conn)
            :transact! #(d/transact conn %) :entid ddb/entid
            :writer #(qualifiers/writer conn) :rows (:all-rows (qualifiers/read-api))})
        (finally (d/release conn) (d/delete-database config))))))

(deftest cleanup-sweep-native-work-and-transition-contract
  (sweep/exercise-work! with-sweep-fixture)
  (sweep/exercise-hostile! with-sweep-fixture)
  (sweep/exercise-budgets! with-sweep-fixture))
