(ns eacl.datahike.contract-test
  "The shared backend contract, on Datahike. A backend is finished when this
   passes — it is the same suite `eacl.datascript.contract-test` runs, so the
   two backends are held to one definition rather than to separate tests that
   drifted.

   The suite runs TWICE, once per attribute representation. Datahike reports a
   datom's `:a` as the attribute keyword by default and as a numeric ref under
   `:attribute-refs? true` (Datomic's representation), and the second mode is
   the one that fails silently rather than loudly: composite tuples need
   replikativ/datahike#921 to be derived at all, and any code comparing `:a`
   against a keyword simply stops matching. Both are permission DENIALS, so a
   single-mode suite would go green while every check answered false."
  (:require [clojure.test :refer [deftest testing]]
            [datahike.api :as d]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]))

(defn- seed-objects!
  "The contract's objects, addressed by `:eacl/id`. Negative `:db/id`s are
   tempids, as in the DataScript contract test."
  [conn]
  (d/transact conn
              (vec (map-indexed (fn [idx {:keys [id]}]
                                  {:db/id (- (inc idx))
                                   :eacl/id id})
                                contract/smoke-objects))))

(defn- run-contract!
  [config]
  (let [conn   (datahike/create-conn nil config)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-seeded-contracts! client)))

(deftest datahike-contract-test
  (testing "attributes as keywords (datahike's default)"
    (run-contract! nil))

  (testing "attributes as numeric refs (:attribute-refs?, Datomic's representation)"
    (run-contract! {:attribute-refs? true})))
