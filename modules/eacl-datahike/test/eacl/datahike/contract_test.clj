(ns eacl.datahike.contract-test
  "The shared backend contract, on Datahike. A backend is finished when this
   passes — it is the same suite `eacl.datascript.contract-test` runs, so the
   two backends are held to one definition rather than to separate tests that
   drifted.

   The suite runs TWICE, once per attribute representation. Datahike reports a
   datom's `:a` as the attribute keyword by default and as a numeric ref under
   `:attribute-refs? true` (Datomic's representation). Composite relation and
   permission tuples need replikativ/datahike#921 to derive in the latter mode,
   and code comparing `:a` directly against a keyword stops matching. Both
   failures deny permissions, so both representations remain mandatory."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.cache :as cache]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.verified-kernel :as verified]))

(defn- seed-objects!
  "The contract's objects, addressed by `:eacl/id`. Negative `:db/id`s are
   tempids, as in the DataScript contract test."
  [conn]
  (d/transact conn
              (vec (map-indexed (fn [idx {:keys [id]}]
                                  {:db/id (- (inc idx))
                                   :eacl/id id})
                                contract/smoke-objects))))

(deftest generated-authority-is-the-default-with-explicit-legacy-rollback-test
  (let [conn (datahike/create-conn)
        default-selection
        (get-in (datahike/make-client conn {}) [:opts :engine-selection])
        legacy-selection
        (get-in
         (datahike/make-client
          conn
          {:engine-selection {:mode :legacy-authoritative}})
         [:opts :engine-selection])]
    (is (= :verified-authoritative (:mode default-selection)))
    (is (satisfies? verified/DecisionKernel (:kernel default-selection)))
    (is (= :legacy-authoritative (:mode legacy-selection)))
    (is (nil? (:kernel legacy-selection)))))

(defn- run-contract!
  [config]
  (let [conn   (datahike/create-conn nil config)
        client (datahike/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-v8-seeded-contracts! client)
    (contract/assert-v8-cache-disabled!
     (datahike/make-client conn {:cache cache/no-cache}))))

(defn- run-recursive-contract!
  [config]
  (let [conn (datahike/create-conn nil config)
        client
        (datahike/make-client
         conn
         {:coherence-authority :managed})]
    (eacl/write-schema! client contract/recursive-schema)
    (d/transact
     conn
     (vec
      (map-indexed
       (fn [index {:keys [id]}]
         {:db/id (- (inc index))
          :eacl/id id})
       contract/recursive-objects)))
    (eacl/create-relationships! client contract/recursive-relationships)
    (contract/assert-v8-recursive-contracts! client)
    (doseq [limit-key [:max-derived-grants
                       :max-advanced-datoms
                       :max-queued-work]]
      (contract/assert-v8-recursive-safety-limit!
       (datahike/make-client
        conn
        {:cache cache/no-cache
         :recursive-traversal-limits {limit-key 1}})))))

(deftest datahike-contract-test
  (testing "attributes as keywords (datahike's default)"
    (run-contract! nil))

  (testing "attributes as numeric refs (:attribute-refs?, Datomic's representation)"
    (run-contract! {:attribute-refs? true})))

(deftest datahike-recursive-v8-contract-test
  (testing "attributes as keywords"
    (run-recursive-contract! nil))
  (testing "attributes as numeric refs"
    (run-recursive-contract! {:attribute-refs? true})))

(deftest datahike-multi-connection-cache-proof-test
  (doseq [attribute-refs? [false true]]
    (testing (str "database-visible proofs with attribute refs "
                  attribute-refs?)
      (let [conn-1 (datahike/create-conn nil
                                         {:attribute-refs? attribute-refs?})
            conn-2 (d/connect (:config (d/db conn-1)))
            store (cache/local-store)
            client-1 (datahike/make-client conn-1 {:cache store})
            client-2 (datahike/make-client conn-2 {:cache store})
            query {:subject (contract/->user "user-2")
                   :permission :view
                   :resource/type :server
                   :first 10}]
        (eacl/write-schema! client-1 contract/smoke-schema)
        (seed-objects! conn-1)
        (eacl/create-relationships!
         client-1 contract/smoke-relationships)

        (let [miss (eacl/lookup-resources client-1 query)
              hit (eacl/lookup-resources client-1 query)]
          (clojure.test/is (= [] (:data miss)))
          (clojure.test/is (false? (:cached? miss)))
          (clojure.test/is (true? (:cached? hit))))

        (eacl/create-relationship!
         client-2
         (contract/->user "user-2")
         :owner
         (contract/->account "account-1"))

        (let [after-write (eacl/lookup-resources client-1 query)]
          (clojure.test/is (false? (:cached? after-write)))
          (clojure.test/is
           (= #{(contract/->server "server-1")
                (contract/->server "server-2")}
              (set (:data after-write)))))))))
