(ns eacl.datahike.contract-test
  "The shared backend contract, on Datahike. A backend is finished when this
   passes — it is the same suite `eacl.datascript.contract-test` runs, so the
   two backends are held to one definition rather than to separate tests that
   drifted."
  (:require [clojure.test :refer [deftest]]
            [datahike.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]))

(defn- seed-objects!
  "The contract's objects, addressed by `:eacl/id`. Negative `:db/id`s are
   tempids, as in the DataScript contract test."
  [conn]
  (d/transact conn
              (map-indexed (fn [idx {:keys [id]}]
                             {:db/id (- (inc idx))
                              :eacl/id id})
                           contract/smoke-objects)))

(deftest datahike-contract-test
  (let [conn   (datahike/create-conn)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-seeded-contracts! client)))
