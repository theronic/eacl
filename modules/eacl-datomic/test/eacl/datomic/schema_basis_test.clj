(ns eacl.datomic.schema-basis-test
  "Pins the Datomic behaviors the schema-digest cache scope (D8) relies on,
  plus regressions for the cache-staleness bugs from the 2026-07-06 audit (§3).
  A peer upgrade that changes any pinned behavior must fail loudly here —
  every one was REPL-verified against peer 1.0.6733."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Relation Permission Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private rel-tuple-attr :eacl.relation/resource-type+relation-name+subject-type)

(defn- hist-rows
  [db]
  (->> (d/datoms (d/history db) :aevt rel-tuple-attr)
       (map (juxt :v :added (comp d/tx->t :tx)))
       (sort-by last)
       (vec)))

(deftest pinned-datomic-behaviors-test
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
    (let [db1 (d/db conn)
          _   @(d/transact conn [[:db/add [:eacl/id "eacl.relation::account::owner::user"]
                                  :eacl.relation/relation-name :holder]])
          db2 (d/db conn)
          _   @(d/transact conn [[:db.fn/retractEntity [:eacl/id "eacl.relation::account::owner::user"]]])
          db3 (d/db conn)]

      (testing "a single-component edit rewrites the composite tuple in the SAME tx"
        (let [t2 (d/basis-t db2)
              rows (set (hist-rows db2))]
          (is (contains? rows [[:account :holder :user] true t2]))
          (is (contains? rows [[:account :owner :user] false t2]))))

      (testing "retractEntity retracts the tuple into history"
        (is (contains? (set (hist-rows db3))
                       [[:account :holder :user] false (d/basis-t db3)])))

      (testing "(d/history (d/as-of db t)) is filtered to <= t"
        (is (= [[[:account :owner :user] true (d/basis-t db1)]]
               (hist-rows (d/as-of db3 (d/basis-t db1))))))

      (testing "(d/basis-t (d/as-of db t)) returns the UNDERLYING basis — the digest filter must use d/as-of-t"
        (is (= (d/basis-t db3) (d/basis-t (d/as-of db3 (d/basis-t db1)))))
        (is (= (d/basis-t db1) (d/as-of-t (d/as-of db3 (d/basis-t db1))))))

      (testing "d/history works on d/with db-afters and includes speculative datoms"
        (let [dbw (:db-after (d/with db3 [(Relation :x :y :z)]))]
          (is (some (fn [[v added _t]] (and (= v [:x :y :z]) added))
                    (hist-rows dbw)))))

      (testing ".id returns the same database UUID on plain/as-of/with views"
        (is (= (str (.id db3))
               (str (.id (d/as-of db3 (d/basis-t db1))))
               (str (.id (:db-after (d/with db3 [])))))))

      (testing "view classification predicates identify their views; with-dbs read as plain"
        (is (false? (d/is-filtered db3)))
        (is (false? (d/is-filtered (d/as-of db3 (d/basis-t db1)))))
        (is (false? (d/is-filtered (d/since db3 (d/basis-t db1)))))
        (is (true?  (d/is-filtered (d/filter db3 (fn [_ _] true)))))
        (is (true?  (d/is-history (d/history db3))))
        (is (false? (d/is-history db3)))
        (is (nil?   (d/as-of-t (:db-after (d/with db3 [])))))
        (is (nil?   (d/since-t (:db-after (d/with db3 [])))))
        (is (some?  (d/since-t (d/since db3 (d/basis-t db1))))))

      (testing "d/history on a d/filter db RESPECTS the filter"
        ;; NOTE: an earlier audit pass concluded the opposite from a vacuous
        ;; all-pass predicate (both behaviors coincide for it). Pinned with a
        ;; hide-everything predicate instead. Filter views are still never
        ;; cached: predicates are arbitrary functions (possibly impure or
        ;; time-dependent), so their digests are not trustworthy as shared keys.
        (let [hide-everything (d/filter db3 (fn [_ _] false))]
          (is (zero? (count (hist-rows hide-everything))))))

      (testing "d/history on a d/since db is since-filtered"
        (is (< (count (hist-rows (d/since db3 (d/basis-t db1))))
               (count (hist-rows db3)))))

      (testing "Db equality is value- and content-based (safe to key the digest memo by db value)"
        (is (.equals db3 (d/db conn)))
        (let [wa (:db-after (d/with db3 [(Relation :wa :r :user)]))
              wb (:db-after (d/with db3 [(Relation :wb :r :user)]))]
          (is (false? (.equals wa wb))
              "different-content with-dbs at the same t must not be equal"))))))

(deftest programmatic-schema-change-invalidation-test
  ;; Audit §3: with (.id db)-keyed caches, a permission revoked via plain
  ;; d/transact kept GRANTING ACCESS from cache. Invalidation is now derived
  ;; from the schema history digest — no helper, no eviction, any peer.
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
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

      @(d/transact conn [[:db.fn/retractEntity
                          [:eacl/id "eacl:permission::account::admin::self::relation::owner"]]])
      (let [db2 (d/db conn)]
        (testing "the revoked permission no longer grants access — no manual eviction"
          (is (= 0 (count (idx/get-permission-paths db2 :account :admin))))
          (is (false? (idx/can? db2 u :admin a))))

        (testing "as-of at the pre-change basis resolves the HISTORICAL paths, even after both were cached"
          (is (= 1 (count (idx/get-permission-paths (d/as-of db2 (d/basis-t db1)) :account :admin))))
          (is (= 0 (count (idx/get-permission-paths db2 :account :admin)))))))))

(deftest mutation-classes-change-digest-test
  ;; Executable coverage invariant: every mutation class of both schema entity
  ;; kinds must change the digest.
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
    (let [digests (atom [(idx/schema-basis-digest (d/db conn))])
          snap!   (fn [] (swap! digests conj (idx/schema-basis-digest (d/db conn))))]
      ;; add relation
      @(d/transact conn [(Relation :account :viewer :user)])
      (snap!)
      ;; edit relation component
      @(d/transact conn [[:db/add [:eacl/id "eacl.relation::account::viewer::user"]
                          :eacl.relation/relation-name :viewer2]])
      (snap!)
      ;; retract relation
      @(d/transact conn [[:db.fn/retractEntity [:eacl/id "eacl.relation::account::viewer::user"]]])
      (snap!)
      ;; add permission
      @(d/transact conn [(Permission :account :view {:relation :owner})])
      (snap!)
      ;; edit permission component
      @(d/transact conn [[:db/add [:eacl/id "eacl:permission::account::view::self::relation::owner"]
                          :eacl.permission/target-name :viewer]])
      (snap!)
      ;; retract permission
      @(d/transact conn [[:db.fn/retractEntity [:eacl/id "eacl:permission::account::view::self::relation::owner"]]])
      (snap!)
      (is (every? string? @digests))
      (is (= (count @digests) (count (distinct @digests)))
          "each mutation class must produce a distinct digest"))))

(deftest speculative-and-filtered-views-cannot-poison-cache-test
  (with-mem-conn [conn schema/v6-schema]
    @(d/transact conn [(Relation :account :owner :user)
                       (Permission :account :admin {:relation :owner})])
    (let [db (d/db conn)
          perm-lookup-ref [:eacl/id "eacl:permission::account::admin::self::relation::owner"]]

      (testing "a d/with speculative schema edit computes its own paths and leaves the real db's entry intact"
        (idx/evict-permission-paths-cache!)
        (let [real-paths (idx/get-permission-paths db :account :admin)
              dbw        (:db-after (d/with db [[:db.fn/retractEntity perm-lookup-ref]]))
              spec-paths (idx/get-permission-paths dbw :account :admin)]
          (is (= 1 (count real-paths)))
          (is (= 0 (count spec-paths)))
          (is (= real-paths (idx/get-permission-paths db :account :admin))
              "the real db still resolves its own paths after the speculative computation")))

      (testing "a d/filter db hiding the permission cannot publish empty paths under the plain db's key"
        (idx/evict-permission-paths-cache!)
        (let [perm-eid   (d/entid db perm-lookup-ref)
              filtered   (d/filter db (fn [_db datom] (not= perm-eid (:e datom))))
              filt-paths (idx/get-permission-paths filtered :account :admin)]
          (is (= 0 (count filt-paths)) "the filtered view itself must not see the permission")
          (is (= 1 (count (idx/get-permission-paths db :account :admin)))
              "the plain db, queried AFTER the filtered view, must still see it")
          (is (nil? (idx/schema-basis-digest filtered))
              "filter views are unclassifiable and never share the cache"))))))

(deftest unchanged-schema-cache-hit-test
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
        (idx/get-permission-paths (d/db conn) :account :admin)
        (is (= 1 @calls))
        ;; unrelated (non-schema) relationship write between queries
        @(d/transact conn (impl/tx-relationship (d/db conn)
                            (Relationship (spice-object :user [:eacl/id "u"])
                                          :owner
                                          (spice-object :account [:eacl/id "a"]))))
        (idx/get-permission-paths (d/db conn) :account :admin)
        (is (= 1 @calls)
            "unchanged schema across relationship writes must hit the cache")))))
