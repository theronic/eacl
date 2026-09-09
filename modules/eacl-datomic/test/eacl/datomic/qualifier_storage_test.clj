(ns eacl.datomic.qualifier-storage-test
  (:require [eacl.datomic.qualifiers :as qualifiers]
            [eacl.relationships.qualifier-sweep-contract :as sweep]
            [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.core :as api]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.db :as db]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.safe-retraction :as safe]
            [eacl.datomic.storage :as admission]
            [eacl.relationships.storage-contract :as contract]))

(defn direct-probe [& args]
  (let [calls (atom 0)
        seek d/seek-datoms]
    (try
      (with-redefs [d/seek-datoms (fn [& args] (swap! calls inc) (apply seek args))]
        (apply db/direct-match? args))
      (finally (is (= 1 @calls) "one native seek per identity probe")))))

(deftest qualified-storage-fails-closed-and-cleans-exactly-test
  (let [uri (str "datomic:mem://qualifier-contract-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        _ (schema/install! conn)
        _ (safe/install! conn)]
    (try
      (contract/exercise-qualified-corruption!
       {:client (api/make-client conn {}) :direct-probe direct-probe
          :read-identity impl/find-one-relationship-id
          :snapshot #(d/db conn)
        :transact! #(deref (d/transact conn %)) :entid d/entid
        :stamp #(vector :db/add % :eacl/relation-version "datomic.tx")
        :rows #(d/datoms %1 :aevt %2) :safe-retract! #(deref (d/transact conn (safe/retract-entity-tx-data %)))})
      (finally (d/release conn) (d/delete-database uri)))))

(deftest bootstrap-is-idempotent-and-validates-completed-storage-test
  (let [uri (str "datomic:mem://bootstrap-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)
        _ (schema/install! conn)]
    (try
      (contract/exercise-bootstrap!
       {:bootstrap! #(admission/bootstrap! conn)
        :evidence #(admission/evidence (d/db conn))
        :transact! #(deref (d/transact conn %))})
      (finally (d/release conn) (d/delete-database uri)))))

(defn with-sweep-fixture [f]
  (with-mem-conn [conn schema/v8-schema]
    (f {:client (api/make-client conn {}) :snapshot #(d/db conn)
        :transact! #(deref (d/transact conn %)) :entid d/entid
        :writer #(qualifiers/writer conn) :rows #(d/datoms %1 :aevt %2)})))

(deftest cleanup-sweep-native-work-and-transition-contract
  (sweep/exercise-work! with-sweep-fixture)
  (sweep/exercise-hostile! with-sweep-fixture)
  (sweep/exercise-budgets! with-sweep-fixture))
