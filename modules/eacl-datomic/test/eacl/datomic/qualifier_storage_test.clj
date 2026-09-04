(ns eacl.datomic.qualifier-storage-test
  (:require [clojure.test :refer [deftest is]]
            [datomic.api :as d]
            [eacl.datomic.core :as api]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.db :as db]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.safe-retraction :as safe]
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
