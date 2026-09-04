(ns eacl.datahike.qualifier-storage-test
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [eacl.datahike.core :as api]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]
            [eacl.datahike.safe-retraction :as safe]
            [eacl.datahike.db :as ddb]
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
