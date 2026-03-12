(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest]]
            [datascript.core :as ds]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(defn- seed-objects!
  [conn]
  (ds/transact! conn
    (map-indexed (fn [idx {:keys [id]}]
                   {:db/id (- (inc idx))
                    :eacl/id id})
      contract/smoke-objects)))

(deftest datascript-contract-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-seeded-contracts! client)))
