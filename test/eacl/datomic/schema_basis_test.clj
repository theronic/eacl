(ns eacl.datomic.schema-basis-test
  "Pins the permission-path cache-scoping contract and the Datomic facts it
  relies on, plus the audit §3 regression (no cache-slot sharing across db
  bases).

  The contract: the cache scope is an exact fingerprint of the EACL definition
  datoms visible in the queried db value, so two db values share a cached path
  set only when their schemas are identical — however the schema changed
  (write-schema!, raw d/transact, d/with, excision) and whether or not
  :eacl/schema-version was bumped.

  Issue #74's requirement still holds and is pinned here: the fingerprint is
  memoised per db VALUE, so relationship and application writes never recompute
  permission paths."
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
  (with-mem-conn [conn schema/v7-schema]
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
  (with-mem-conn [conn schema/v7-schema]
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

      ;; This used to assert the opposite — that a programmatic edit served
      ;; stale paths until write-schema! or a manual eviction. The scope is now
      ;; a fingerprint of the definition datoms themselves, so it does not
      ;; matter how the definitions changed.
      (testing "programmatic schema edits are visible to the caches without a version bump"
        ;; a viewer-only subject: their access depends solely on the
        ;; `admin = … + viewer` row that is about to be retracted.
        @(d/transact conn [{:eacl/id "v"}])
        @(d/transact conn (impl/tx-relationship (d/db conn)
                            (Relationship (spice-object :user [:eacl/id "v"])
                                          :viewer
                                          (spice-object :account [:eacl/id "a"]))))
        (let [v               (spice-object :user [:eacl/id "v"])
              viewer-perm-eid (d/q '[:find ?e .
                                     :where
                                     [?e :eacl.permission/resource-type :account]
                                     [?e :eacl.permission/permission-name :admin]
                                     [?e :eacl.permission/target-name :viewer]]
                                   (d/db conn))
              version-before  (idx/schema-version (d/db conn))]
          (is (true? (idx/can? (d/db conn) v :admin a))
              "viewer grants :admin under schema-v2, and this caches it")
          @(d/transact conn [[:db.fn/retractEntity viewer-perm-eid]])
          (is (= version-before (idx/schema-version (d/db conn)))
              "raw d/transact does not bump :eacl/schema-version")
          (is (= 1 (count (idx/get-permission-paths (d/db conn) :account :admin)))
              "the retracted permission row is gone from the paths anyway")
          (is (false? (idx/can? (d/db conn) v :admin a))
              "…so the access it granted is revoked immediately")
          (is (true? (idx/can? (d/db conn) u :admin a))
              "while the surviving `admin = owner` row still grants"))))))

(deftest speculative-db-cannot-poison-live-cache-test
  ;; A d/with database inherits the database uuid, reads as :plain
  ;; (is-filtered/as-of-t/since-t are all false/nil on it) and does not bump
  ;; :eacl/schema-version — so under the old [database-uuid schema-version]
  ;; scope it shared a cache slot with the committed database. Evaluating a
  ;; permission against a speculative schema change published the speculative
  ;; paths under the LIVE key and granted a permission the committed schema
  ;; does not define, for the life of the process.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    @(d/transact conn (impl/tx-relationship (d/db conn)
                        (Relationship (spice-object :user [:eacl/id "u"])
                                      :owner
                                      (spice-object :account [:eacl/id "a"]))))
    (idx/evict-permission-paths-cache!)
    (let [db  (d/db conn)
          u   (spice-object :user [:eacl/id "u"])
          a   (spice-object :account [:eacl/id "a"])]

      (testing "a speculative ADDITION does not leak into the committed database"
        (let [speculative (:db-after (d/with db [(Permission :account :view {:relation :owner})]))]
          (is (true? (idx/can? speculative u :view a))
              "the speculative db sees its own new permission")
          (is (false? (idx/can? db u :view a))
              "the committed db must not: :view does not exist there")
          (is (empty? (idx/get-permission-paths db :account :view)))
          (is (empty? (:data (idx/lookup-resources db {:subject       u
                                                       :permission    :view
                                                       :resource/type :account
                                                       :first         10}))))))

      (testing "a speculative RETRACTION does not leak either (the false-denial direction)"
        (let [perm-eid    (d/q '[:find ?e .
                                 :where
                                 [?e :eacl.permission/resource-type :account]
                                 [?e :eacl.permission/permission-name :admin]]
                               db)
              speculative (:db-after (d/with db [[:db.fn/retractEntity perm-eid]]))]
          (is (false? (idx/can? speculative u :admin a)))
          (is (true? (idx/can? db u :admin a))
              "the committed db still grants :admin"))))))

(deftest unstamped-database-cache-is-still-exact-test
  ;; :eacl/schema-version is only written by write-schema!, so a database built
  ;; with programmatic Relation/Permission maps (or migrated with no :schema)
  ;; had a nil stamp and therefore a scope that never changed: definitions
  ;; added after the first query stayed invisible for the life of the process.
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [(Relation :account :owner :user)])
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    @(d/transact conn (impl/tx-relationship (d/db conn)
                        (Relationship (spice-object :user [:eacl/id "u"])
                                      :owner
                                      (spice-object :account [:eacl/id "a"]))))
    (idx/evict-permission-paths-cache!)
    (let [u (spice-object :user [:eacl/id "u"])
          a (spice-object :account [:eacl/id "a"])]
      (is (nil? (idx/schema-version (d/db conn)))
          "no write-schema! has run: there is no version stamp to key on")
      (is (false? (idx/can? (d/db conn) u :admin a))
          "populates the cache with the pre-change (empty) paths")

      (testing "a permission added programmatically afterwards takes effect"
        @(d/transact conn [(Permission :account :admin {:relation :owner})])
        (is (nil? (idx/schema-version (d/db conn))))
        (is (true? (idx/can? (d/db conn) u :admin a))))

      (testing "and retracting it programmatically takes effect too"
        @(d/transact conn [[:db.fn/retractEntity
                            (d/q '[:find ?e .
                                   :where [?e :eacl.permission/permission-name :admin]]
                                 (d/db conn))]])
        (is (false? (idx/can? (d/db conn) u :admin a)))))))

(deftest unrelated-transact-keeps-cache-test
  ;; Issue #74 itself: relationship/application writes must not bust the path
  ;; cache — neither before any write-schema! (nil version) nor after one.
  (with-mem-conn [conn schema/v7-schema]
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

(deftest schema-scope-is-computed-once-per-db-value-test
  ;; The scope is now a fingerprint of the definition datoms, which costs one
  ;; O(number-of-definitions) scan. That is only affordable because it is
  ;; memoised on the db VALUE: computing it per get-permission-paths call (or
  ;; per can?) would put a schema-sized scan on the hottest path in EACL.
  (with-mem-conn [conn schema/v7-schema]
    (schema/write-schema! conn schema-v1)
    @(d/transact conn [{:eacl/id "u"} {:eacl/id "a"}])
    @(d/transact conn (impl/tx-relationship (d/db conn)
                        (Relationship (spice-object :user [:eacl/id "u"])
                                      :owner
                                      (spice-object :account [:eacl/id "a"]))))
    (idx/evict-permission-paths-cache!)
    (let [scans (atom 0)
          orig  @#'idx/calc-schema-scope
          u     (spice-object :user [:eacl/id "u"])
          a     (spice-object :account [:eacl/id "a"])]
      (with-redefs [idx/calc-schema-scope (fn [db] (swap! scans inc) (orig db))]
        (let [db (d/db conn)]
          (testing "many checks against one db value fingerprint it once"
            (dotimes [_ 50]
              (is (true? (idx/can? db u :admin a)))
              (idx/lookup-resources db {:subject u :permission :admin
                                        :resource/type :account :first 10}))
            (is (= 1 @scans)
                (str "expected one fingerprint scan for one db value, got " @scans)))

          (testing "d/db at an unchanged basis is the same value and still hits"
            (idx/can? (d/db conn) u :admin a)
            (is (= 1 @scans)))

          (testing "a new db value costs exactly one more scan, however many calls follow"
            @(d/transact conn [{:eacl/id "unrelated"}])
            (let [db2 (d/db conn)]
              (dotimes [_ 20] (idx/can? db2 u :admin a))
              (is (= 2 @scans)))))))))

(deftest filtered-views-cannot-poison-cache-test
  (with-mem-conn [conn schema/v7-schema]
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
              "the version stamp does not describe a filter view")
          (is (not= (idx/schema-scope filtered) (idx/schema-scope db))
              "and the filtered view fingerprints to its own cache scope")))

      (testing "since and history views have no meaningful version stamp"
        (is (nil? (idx/schema-version-stamp (d/since db 0))))
        (is (nil? (idx/schema-version-stamp (d/history db))))
        (is (some? (idx/schema-version-stamp db)))
        (is (some? (idx/schema-version-stamp (d/as-of db (d/basis-t db)))))))))
