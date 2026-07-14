(ns eacl.datomic.schema-basis-test
  "Pins the write-schema!-driven cache-invalidation contract (issue #74) and
  the Datomic view-classification facts the cache scope relies on, plus the
  audit §3 regression (no cache-slot sharing across db bases).

  The contract: ONLY eacl.datomic.schema/write-schema! invalidates the
  permission-path caches — it bumps :eacl/schema-version in the same
  transaction as any definition change. Unrelated d/transact calls must leave
  every cache key untouched. Programmatic edits of relation/permission datoms
  bypass the stamp and are explicitly unsupported (issue #74)."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Relation Permission Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private schema-v1
  "definition user {}
   definition account { relation owner: user
                        permission admin = owner }")

(def ^:private schema-v2
  "definition user {}
   definition account { relation owner: user
                        relation viewer: user
                        permission admin = owner + viewer }")

(deftest pinned-datomic-behaviors-test
  (with-mem-conn [conn schema/v6-schema]
    (schema/write-schema! conn schema-v1)
    (let [db1 (d/db conn)
          _   (schema/write-schema! conn schema-v2)
          db2 (d/db conn)]

      (testing ".id returns the same database UUID on plain/as-of/with views (scope key component)"
        (is (= (str (.id db2))
               (str (.id (d/as-of db2 (d/basis-t db1))))
               (str (.id (:db-after (d/with db2 [])))))))

      (testing "view classification predicates identify their views; with-dbs read as plain"
        (is (false? (d/is-filtered db2)))
        (is (false? (d/is-filtered (d/as-of db2 (d/basis-t db1)))))
        (is (false? (d/is-filtered (d/since db2 (d/basis-t db1)))))
        (is (true?  (d/is-filtered (d/filter db2 (fn [_ _] true)))))
        (is (true?  (d/is-history (d/history db2))))
        (is (false? (d/is-history db2)))
        (is (nil?   (d/as-of-t (:db-after (d/with db2 [])))))
        (is (nil?   (d/since-t (:db-after (d/with db2 [])))))
        (is (some?  (d/since-t (d/since db2 (d/basis-t db1))))))

      (testing "the version datom is a plain cardinality-one assert: as-of views read their era's value"
        (is (some? (idx/schema-version db1)))
        (is (not= (idx/schema-version db1) (idx/schema-version db2)))
        (is (= (idx/schema-version db1)
               (idx/schema-version (d/as-of db2 (d/basis-t db1)))))))))

(deftest write-schema-invalidation-test
  ;; Audit §3 regression, reworked for issue #74: invalidation is signaled by
  ;; write-schema!'s version bump (visible to every peer via the db), not
  ;; derived from db content and not dependent on the local eviction.
  (with-mem-conn [conn schema/v6-schema]
    (schema/write-schema! conn schema-v1)
    @(d/transact conn [{:db/id "u" :eacl/id "u"} {:db/id "a" :eacl/id "a"}])
    @(d/transact conn (impl/tx-relationship (d/db conn)
                        (Relationship (spice-object :user [:eacl/id "u"])
                                      :owner
                                      (spice-object :account [:eacl/id "a"]))))
    (idx/evict-permission-paths-cache!)
    (let [db1 (d/db conn)
          u   (spice-object :user [:eacl/id "u"])
          a   (spice-object :account [:eacl/id "a"])]
      (is (true? (idx/can? db1 u :admin a)))
      (is (= 1 (count (idx/get-permission-paths db1 :account :admin))) "cache populated")

      (testing "write-schema! invalidates via the version key alone — no local eviction needed (the cross-peer story)"
        (with-redefs [idx/evict-permission-paths-cache! (fn [] nil)]
          (schema/write-schema! conn schema-v2))
        (let [db2 (d/db conn)]
          (is (= 2 (count (idx/get-permission-paths db2 :account :admin)))
              "new version, new cache slot: paths recomputed without eviction")
          (is (true? (idx/can? db2 u :admin a)))

          (testing "as-of at the pre-change basis resolves the HISTORICAL paths, even after both were cached"
            (is (= 1 (count (idx/get-permission-paths (d/as-of db2 (d/basis-t db1)) :account :admin))))
            (is (= 2 (count (idx/get-permission-paths db2 :account :admin)))))))

      (testing "programmatic schema edits are invisible to the caches — by design (issue #74)"
        (let [viewer-perm-eid (d/q '[:find ?e .
                                     :where
                                     [?e :eacl.permission/resource-type :account]
                                     [?e :eacl.permission/permission-name :admin]
                                     [?e :eacl.permission/target-name :viewer]]
                                   (d/db conn))]
          @(d/transact conn [[:db.fn/retractEntity viewer-perm-eid]])
          (is (= 2 (count (idx/get-permission-paths (d/db conn) :account :admin)))
              "raw d/transact did not bump the version: stale paths served until the next write-schema!")
          (idx/evict-permission-paths-cache!)
          (is (= 1 (count (idx/get-permission-paths (d/db conn) :account :admin)))
              "manual evict-permission-paths-cache! is the recovery hatch"))))))

(deftest unrelated-transact-keeps-cache-test
  ;; Issue #74 itself: relationship/application writes must not bust the path
  ;; cache — neither before any write-schema! (nil version) nor after one.
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    (idx/evict-permission-paths-cache!)
    (let [calls (atom 0)
          orig  idx/calc-permission-paths]
      (with-redefs [idx/calc-permission-paths (fn [& args]
                                                (swap! calls inc)
                                                (apply orig args))]
        (testing "pre-version databases (no write-schema! yet) still cache across unrelated writes"
          (idx/get-permission-paths (d/db conn) :account :admin)
          (is (= 1 @calls))
          @(d/transact conn (impl/tx-relationship (d/db conn)
                              (Relationship (spice-object :user [:eacl/id "u"])
                                            :owner
                                            (spice-object :account [:eacl/id "a"]))))
          (idx/get-permission-paths (d/db conn) :account :admin)
          (is (= 1 @calls)
              "unchanged schema across relationship writes must hit the cache"))

        (testing "after write-schema!, the version stays put across unrelated writes: cache stays hot"
          (schema/write-schema! conn schema-v1)
          (idx/get-permission-paths (d/db conn) :account :admin)
          (let [warm @calls]
            @(d/transact conn [{:eacl/id "unrelated-entity"}])
            @(d/transact conn (impl/tx-relationship (d/db conn)
                                (Relationship (spice-object :user [:eacl/id "u"])
                                              :owner
                                              (spice-object :account [:eacl/id "unrelated-entity"]))))
            (idx/get-permission-paths (d/db conn) :account :admin)
            (is (= warm @calls)
                "d/transact of relationships/entities must not recompute paths (issue #74)")))))))

(deftest filtered-views-cannot-poison-cache-test
  (with-mem-conn [conn schema/v6-schema]
    (schema/write-schema! conn schema-v1)
    (let [db       (d/db conn)
          perm-eid (d/q '[:find ?e .
                          :where
                          [?e :eacl.permission/resource-type :account]
                          [?e :eacl.permission/permission-name :admin]]
                        db)]
      (testing "a d/filter db hiding the permission cannot publish empty paths under the plain db's key"
        (idx/evict-permission-paths-cache!)
        (let [filtered   (d/filter db (fn [_db datom] (not= perm-eid (:e datom))))
              filt-paths (idx/get-permission-paths filtered :account :admin)]
          (is (= 0 (count filt-paths)) "the filtered view itself must not see the permission")
          (is (= 1 (count (idx/get-permission-paths db :account :admin)))
              "the plain db, queried AFTER the filtered view, must still see it")
          (is (nil? (idx/schema-version-stamp filtered))
              "filter views are unclassifiable and never share the cache")))

      (testing "since and history views are likewise unclassifiable"
        (is (nil? (idx/schema-version-stamp (d/since db 0))))
        (is (nil? (idx/schema-version-stamp (d/history db))))
        (is (some? (idx/schema-version-stamp db)))
        (is (some? (idx/schema-version-stamp (d/as-of db (d/basis-t db)))))))))
