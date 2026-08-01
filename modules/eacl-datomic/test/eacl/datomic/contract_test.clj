(ns eacl.datomic.contract-test
  (:require [clojure.test :refer [deftest]]
            [datomic.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(defn- seed-objects!
  [conn]
  @(d/transact conn
     (mapv (fn [{:keys [id]}]
             {:db/id id
              :eacl/id id})
       contract/smoke-objects)))

(deftest datomic-contract-test
  (with-mem-conn [conn schema/v6-schema]
    (let [client (datomic/make-client conn {})]
      (eacl/write-schema! client contract/smoke-schema)
      (seed-objects! conn)
      (eacl/create-relationships! client contract/smoke-relationships)
      (contract/assert-seeded-contracts! client))))
