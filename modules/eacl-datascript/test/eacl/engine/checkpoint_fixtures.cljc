(ns eacl.engine.checkpoint-fixtures
  "Portable fixtures for the CLJS checkpoint/refinement suites."
  (:require [datascript.core :as ds]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def recursive-fixture
  {:schema contract/recursive-schema
   :objects contract/recursive-objects
   :relationships contract/recursive-relationships
   :resource-type :folder
   :permission :read
   :principals
   {:alice (eacl/spice-object :user "recursive-user")
    :stranger (eacl/spice-object :user "denied-user")}
   :reverse-resources
   {:leaf (eacl/spice-object :folder "folder-11")
    :root (eacl/spice-object :folder "folder-0")}})

(def acyclic-fixture
  {:schema contract/smoke-schema
   :objects contract/smoke-objects
   :relationships contract/smoke-relationships
   :resource-type :server
   :permission :view
   :principals
   {:super-user (contract/->user "user-1")
    :alice (contract/->user "user-1")
    :stranger (contract/->user "user-2")}
   :reverse-resources
   {:leaf (contract/->server "server-2")
    :root (contract/->server "server-1")}})

(defn fixture
  [fixture-key]
  (if (= :explorer-acyclic fixture-key)
    acyclic-fixture
    recursive-fixture))

(defn seed-client!
  ([fixture]
   (seed-client! fixture {}))
  ([{:keys [schema objects relationships] :as fixture} client-options]
   (let [conn (datascript/create-conn)
         client (datascript/make-client conn client-options)]
     (eacl/write-schema! client schema)
     (ds/transact!
      conn
      (vec
       (map-indexed
        (fn [index {:keys [id]}]
          {:db/id (- (inc index)) :eacl/id id})
        objects)))
     (doseq [batch (partition-all 500 relationships)]
       (eacl/create-relationships! client (vec batch)))
     {:fixture fixture :conn conn :client client})))
