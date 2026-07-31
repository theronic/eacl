(ns eacl.datomic.watermark-test
  "Soundness and precision of per-relation cache epochs.

  Two things are being asserted here, and they pull in opposite directions.

  PRECISION: an epoch must NOT move for a write that cannot change the answer.
  That is the whole point — the previous log-scanning epoch moved on every EACL
  write anywhere, which made the exact cache cost more than it saved on any
  database with write traffic spread across relations.

  SOUNDNESS: an epoch MUST move for every write that can. The tempting
  shortcut — trusting the relationship coordinator's in-process `observed-t` —
  is unsound, and `another-connections-write-is-not-served-from-cache-test` is
  that exact scenario. It passes only because the stamp is transacted into the
  database alongside the relationship datoms, so no reader can see the data
  without also seeing the stamp, whoever wrote it."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.watermark :as watermark]))

(def ^:private direct-schema
  "definition user {}
   definition doc {
     relation owner: user
     permission view = owner
   }")

(def ^:private multi-relation-schema
  "doc/view and folder/edit share nothing, so folder churn must not disturb a
  cached doc answer. team->member reaches doc/view through an arrow, so team
  churn must."
  "definition user {}
   definition team {
     relation member: user
   }
   definition doc {
     relation owner: user
     relation team: team
     permission view = owner + team->member
   }
   definition folder {
     relation editor: user
     permission edit = editor
   }")

(defn- exact-client [conn]
  (core/make-client conn {:cache {:exact-results? true}
                          :page-token-key "watermark-test"}))

(defn- seed! [conn acl]
  (eacl/write-schema! acl direct-schema)
  @(d/transact conn [{:eacl/id "alice"} {:eacl/id "bob"}
                     {:eacl/id "d1"} {:eacl/id "d2"}]))

(defn- seed-multi! [conn acl]
  (eacl/write-schema! acl multi-relation-schema)
  @(d/transact conn (into [{:eacl/id "alice"} {:eacl/id "d1"}
                           {:eacl/id "t1"}]
                          (for [n (range 30)] {:eacl/id (str "f" n)}))))

(defmacro ^:private counting-can?
  "Runs body with a counter of actual can? evaluations."
  [calls & body]
  `(let [original# impl/can?]
     (with-redefs [impl/can? (fn [db# s# p# r#]
                               (swap! ~calls inc)
                               (original# db# s# p# r#))]
       ~@body)))

;; --- precision: the whole point --------------------------------------------

(deftest unrelated-transactions-keep-the-exact-cache-hot-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "application writes that touch no EACL data"
          (dotimes [n 25]
            @(d/transact conn [{:db/doc (str "unrelated " n)}])
            (is (true? (eacl/can? acl alice :view d1))))
          (is (= 1 @calls)
              "25 intervening transactions, still one evaluation"))))))

(deftest unrelated-relation-churn-keeps-the-exact-cache-hot-test
  ;; The capability per-relation stamps exist for. Under the previous
  ;; log-scanning epoch every one of these folder writes invalidated the cached
  ;; doc answer: 340 evaluations per 400 reads, and 102.4us against 19.2us for
  ;; no cache at all. The cache was pure overhead under EACL write traffic.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed-multi! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "writes to a relation doc/view does not read"
          (dotimes [n 25]
            (eacl/create-relationship!
             acl (->Relationship alice :editor (spice-object :folder (str "f" n))))
            (is (true? (eacl/can? acl alice :view d1))))
          (is (= 1 @calls)
              "25 EACL relationship writes to folder/editor, still one evaluation"))

        (testing "but a write to a relation it DOES read still invalidates"
          (eacl/delete-relationship! acl (->Relationship alice :owner d1))
          (is (false? (eacl/can? acl alice :view d1)))
          (is (= 2 @calls)))))))

;; --- soundness: every way EACL data can change -----------------------------

(deftest relationship-writes-move-the-epoch-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "a create is observed"
          (eacl/create-relationship! acl (->Relationship alice :owner d1))
          (is (true? (eacl/can? acl alice :view d1)))
          (is (= 2 @calls)))

        (testing "a retraction is observed"
          (eacl/delete-relationship! acl (->Relationship alice :owner d1))
          (is (false? (eacl/can? acl alice :view d1)))
          (is (= 3 @calls)))))))

(deftest arrow-dependencies-move-the-epoch-test
  ;; doc/view reaches team/member through an arrow. A write to team/member
  ;; changes the answer without touching any relation named on doc, so the
  ;; dependency set has to be the transitive one, not the local one.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed-multi! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          t1 (spice-object :team "t1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship t1 :team d1))
      (counting-can? calls
        (is (false? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "granting membership on the far side of the arrow"
          (eacl/create-relationship! acl (->Relationship alice :member t1))
          (is (true? (eacl/can? acl alice :view d1))
              "a write two hops away must still invalidate")
          (is (= 2 @calls)))

        (testing "and revoking it"
          (eacl/delete-relationship! acl (->Relationship alice :member t1))
          (is (false? (eacl/can? acl alice :view d1)))
          (is (= 3 @calls)))))))

(deftest delete-object-moves-the-epoch-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (eacl/delete-object! acl d1)
        (is (false? (eacl/can? acl alice :view d1))
            "delete-object! retracts through a different code path and must
             stamp the relations it clears")
        (is (= 2 @calls))))))

(deftest raw-transact-of-relationship-tx-data-moves-the-epoch-test
  ;; tx-relationship is public and documented for callers who want to write a
  ;; relationship inside their own transaction. Because the stamp is part of
  ;; the tx-data it RETURNS, such a caller publishes the change without
  ;; knowing the cache exists.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? acl alice :view d1)))
        @(d/transact conn (impl/tx-relationship
                           (d/db conn)
                           (->Relationship alice :owner d1)))
        (is (true? (eacl/can? acl alice :view d1))
            "a relationship written outside the EACL client still takes effect")
        (is (= 2 @calls))))))

(deftest concatenated-tx-relationship-output-does-not-conflict-test
  ;; The stamp's value is the transaction entity, not a fresh id, so N helpers
  ;; in one transaction emit N identical datoms rather than N conflicting ones.
  ;; A per-call squuid would make this throw :db.error/datoms-conflict and
  ;; break every caller that batches relationship writes.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          bob (spice-object :user "bob")
          d1 (spice-object :doc "d1")
          d2 (spice-object :doc "d2")
          db (d/db conn)]
      @(d/transact conn (concat
                         (impl/tx-relationship db (->Relationship alice :owner d1))
                         (impl/tx-relationship db (->Relationship bob :owner d1))
                         (impl/tx-relationship db (->Relationship alice :owner d2))))
      (is (true? (eacl/can? acl alice :view d1)))
      (is (true? (eacl/can? acl bob :view d1)))
      (is (true? (eacl/can? acl alice :view d2))))))

(deftest every-delete-object-batch-publishes-its-own-relations-test
  ;; delete-object! transacts its tx-data in batches of 1000 ops, and
  ;; tx-delete-object deduplicates stamps across the WHOLE result — so the
  ;; stamp for a relation lands in whichever batch first mentions it. Every
  ;; later batch would then retract relationships of that relation while
  ;; announcing nothing, and a reader that cached between the two batches would
  ;; keep serving a revoked grant. The invariant asserted here is per batch,
  ;; because that is the unit that becomes visible to readers.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (eacl/write-schema! acl direct-schema)
          _ @(d/transact conn (into [{:eacl/id "alice"}]
                                    (for [n (range 800)]
                                      {:eacl/id (str "doc" n)})))
          alice (spice-object :user "alice")]
      (eacl/write-relationships!
       acl (for [n (range 800)]
             {:operation :create
              :relationship (->Relationship alice :owner
                                            (spice-object :doc (str "doc" n)))}))
      (let [db (d/db conn)
            tx-data (impl/tx-delete-object db (d/entid db [:eacl/id "alice"]))
            batches (partition-all 1000 tx-data)
            relation-eid (d/q '[:find ?r . :where
                                [?r :eacl.relation/relation-name :owner]] db)]
        (is (< 1 (count batches))
            "the fixture must actually span more than one batch, or this
             test proves nothing")
        (doseq [[n batch] (map-indexed vector batches)]
          (let [stamped (impl/stamp-relation-versions batch)
                retracts-owner? (some #(and (= :db/retract (nth % 0))
                                            (vector? (nth % 3 nil))
                                            (= relation-eid (nth (nth % 3) 1)))
                                      batch)]
            (when retracts-owner?
              (is (some #(= [:db/add relation-eid :eacl/relation-version
                             "datomic.tx"]
                            %)
                        stamped)
                  (str "batch " n " retracts :owner relationships and must
                        publish that it did")))))))))

(deftest a-batched-delete-object-invalidates-cached-answers-test
  ;; End-to-end smoke test, NOT a proof of the batching invariant above: run
  ;; sequentially with no read between batches, the stamp the first batch
  ;; publishes is enough to invalidate, so this still passes with per-batch
  ;; stamping removed. It does catch a stamp that never moves at all.
  ;; every-delete-object-batch-publishes-its-own-relations-test is the one
  ;; that pins the per-batch invariant.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (eacl/write-schema! acl direct-schema)
          _ @(d/transact conn (into [{:eacl/id "alice"}]
                                    (for [n (range 800)]
                                      {:eacl/id (str "doc" n)})))
          alice (spice-object :user "alice")
          last-doc (spice-object :doc "doc799")]
      (eacl/write-relationships!
       acl (for [n (range 800)]
             {:operation :create
              :relationship (->Relationship alice :owner
                                            (spice-object :doc (str "doc" n)))}))
      (is (true? (eacl/can? acl alice :view last-doc)))
      (eacl/delete-object! acl alice)
      (is (false? (eacl/can? acl alice :view last-doc))
          "a grant retracted in a late batch must not survive in the cache"))))

(deftest another-connections-write-is-not-served-from-cache-test
  ;; THE loophole. Two connections to one database is what two app servers look
  ;; like. A process-local watermark stays at its initial value here and the
  ;; reader serves a stale answer; a stamp transacted with the data does not.
  (with-mem-conns [reader-conn writer-conn schema/v7-schema]
    (let [reader (exact-client reader-conn)
          writer (core/make-client writer-conn {})
          _ (seed! reader-conn reader)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? reader alice :view d1)))
        (is (= 1 @calls))

        (eacl/create-relationship! writer (->Relationship alice :owner d1))

        (is (true? (eacl/can? reader alice :view d1))
            "a write from another connection must invalidate the reader's cache")
        (is (= 2 @calls))

        (testing "and a revoke from that other connection too"
          (eacl/delete-relationship! writer (->Relationship alice :owner d1))
          (is (false? (eacl/can? reader alice :view d1)))
          (is (= 3 @calls)))))))

(deftest schema-writes-move-the-epoch-test
  ;; Definitions are NOT covered by relation stamps; they are covered by
  ;; :eacl/schema-version, which is already a cache-key component.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (eacl/write-schema! acl "definition user {}
                                 definition doc { relation owner: user
                                                  relation editor: user
                                                  permission view = editor }")
        (is (false? (eacl/can? acl alice :view d1))
            "view no longer includes owner")
        (is (= 2 @calls))))))

;; --- the epoch component itself ---------------------------------------------

(defn- deps [db resource-type permission]
  (impl.indexed/permission-relationship-eids db resource-type permission))

(deftest epoch-tracks-only-its-own-dependencies-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache false})
          _ (seed-multi! conn acl)
          state (watermark/make-epoch-state)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          doc-epoch #(let [db (d/db conn)]
                       (watermark/epoch-for state db (deps db :doc :view)))
          e0 (doc-epoch)]
      (is (integer? e0))

      (testing "unrelated Datomic transactions"
        (dotimes [_ 3] @(d/transact conn [{:db/doc "x"}]))
        (is (= e0 (doc-epoch))))

      (testing "unrelated EACL relation"
        (eacl/create-relationship!
         acl (->Relationship alice :editor (spice-object :folder "f0")))
        (is (= e0 (doc-epoch))
            "folder/editor is not in doc/view's dependency set"))

      (testing "a dependency"
        (eacl/create-relationship! acl (->Relationship alice :owner d1))
        (let [e1 (doc-epoch)]
          (is (not= e0 e1))
          (is (> e1 e0) "stamps are transaction ids, so they only increase")

          (dotimes [_ 3] @(d/transact conn [{:db/doc "y"}]))
          (is (= e1 (doc-epoch)) "and it settles again"))))))

(deftest epoch-is-nil-when-it-cannot-be-established-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache false})
          _ (seed! conn acl)
          db (d/db conn)
          state (watermark/make-epoch-state)]
      (is (nil? (watermark/epoch-for state db nil))
          "no dependency set was computed")
      (is (nil? (watermark/epoch-for state db []))
          "an empty dependency set is what a dependency-calculation bug looks
           like, so it declines to retain rather than caching forever")
      (is (some? (watermark/epoch-for state db (deps db :doc :view)))))))

(deftest epoch-without-the-relation-version-attribute-is-nil-test
  ;; A v7 database stamped before per-relation stamps existed. Declining to
  ;; retain is correct: keying on basis-t instead measured worse than no cache.
  (with-mem-conn [conn (remove #(= :eacl/relation-version (:db/ident %))
                               schema/v7-schema)]
    (let [db (d/db conn)
          state (watermark/make-epoch-state)]
      (is (nil? (watermark/epoch-for state db [(d/entid db :eacl/id)]))))))

(deftest epoch-state-built-before-the-eacl-schema-still-works-test
  ;; The attribute eid used to be resolved once at client construction. A
  ;; client built before the EACL schema is installed — which this repo's own
  ;; benchmark seeding does — then held nothing and detected NOTHING, so
  ;; answers went stale permanently. Resolution is lazy and memoised.
  (with-mem-conn [conn []]
    (let [state (watermark/make-epoch-state)]
      (is (nil? (watermark/epoch-for state (d/db conn) [1]))
          "nothing to resolve yet")
      @(d/transact conn schema/v7-schema)
      (let [acl (core/make-client conn {:cache false})
            _ (seed! conn acl)
            alice (spice-object :user "alice")
            d1 (spice-object :doc "d1")
            doc-epoch #(let [db (d/db conn)]
                         (watermark/epoch-for state db (deps db :doc :view)))
            e0 (doc-epoch)]
        (is (integer? e0)
            "the attribute resolves once it exists")
        (eacl/create-relationship! acl (->Relationship alice :owner d1))
        (is (not= e0 (doc-epoch)))))))

(deftest write-schema-installs-relation-stamps-on-an-older-database-test
  (with-mem-conn [conn (remove #(= :eacl/relation-version (:db/ident %))
                               schema/v7-schema)]
    (is (nil? (d/entid (d/db conn) :eacl/relation-version)))
    (let [acl (core/make-client conn {:cache {:exact-results? true}
                                      :page-token-key "watermark-test"})]
      (seed! conn acl)
      (is (some? (d/entid (d/db conn) :eacl/relation-version))
          "write-schema! installs the attribute so an upgraded database starts
           caching without a migration")
      (let [alice (spice-object :user "alice")
            d1 (spice-object :doc "d1")
            calls (atom 0)]
        (eacl/create-relationship! acl (->Relationship alice :owner d1))
        (counting-can? calls
          (is (true? (eacl/can? acl alice :view d1)))
          (dotimes [n 5]
            @(d/transact conn [{:db/doc (str "unrelated " n)}])
            (is (true? (eacl/can? acl alice :view d1))))
          (is (= 1 @calls)))))))

(deftest epoch-failure-degrades-to-no-retention-test
  (is (nil? (watermark/safe-epoch-for nil nil [1]))
      "no epoch state: nothing to retain under")
  (let [exploding (reify clojure.lang.IDeref
                    (deref [_] (throw (ex-info "boom" {}))))]
    (is (nil? (watermark/safe-epoch-for exploding nil [1]))
        "a failing epoch declines to retain, which is a miss, never a wrong
         answer")))
