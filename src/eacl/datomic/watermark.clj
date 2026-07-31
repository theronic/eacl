(ns eacl.datomic.watermark
  "Cache epochs derived from per-relation change stamps written by EACL itself.

  The exact-result cache needs a key component that changes when — and only
  when — something that could alter this answer changes. Datomic `basis-t` is
  the naive choice and is a ~0% hit rate on a busy database: an unrelated
  invoice write mints a new key.

  Two rejected designs, and why:

  - The relationship coordinator's `observed-t`. UNSOUND. A coordinator only
  learns about writes made through its own process, so a second app server's
  write leaves it untouched and the cache serves a stale answer. That is
  privilege escalation, not staleness.

  - Scanning `d/tx-range` for EACL datoms. Sound, and it was what shipped
  first, but it can only ever answer THAT something changed, never WHAT. Every
  EACL write therefore invalidated every cached answer, which made the cache
  cost more than it saved on any database with write traffic spread across
  relations. Measured over 400 reads on a schema where `doc/view` and
  `folder/editor` share nothing, writing folder relationships between reads
  (same process, so the numbers are comparable to each other):

    can?, arrow fan-out 400   no cache 19.2us   global epoch 102.4us   here 6.9us
    lookup-resources :first 50   106.2us              158.4us              53.3us

  Evaluations per 400 reads went 400 -> 340 -> 0. A global epoch was thus
  slower than no cache at all: every read paid a publication it could never
  read back, and the store thrashed on entries nothing would ever match.

  What ships instead: EACL's relationship tx-data helpers stamp each affected
  relation with the transaction changing it (see
  `eacl.datomic.impl/bump-relation-version`), atomically with the relationship
  datoms. An answer's epoch is the max stamp over the relations that answer
  actually depends on, so churn on any other relation cannot touch it.

  Soundness rests on three properties:

  1. The stamp lands in the same transaction as the relationship datoms, so no
     db value can show one without the other, and no reader can observe the
     data without the stamp regardless of which process or connection wrote it.
  2. Tx entity ids increase monotonically with `t`, so any new write to a
     dependency produces a stamp strictly greater than every existing one and
     the max strictly increases. A max can never miss a write.
  3. The dependency set comes from the schema and is fixed for a schema
     generation, which is already part of every cache key.

  What this deliberately does NOT cover, and why that is safe: EACL relation
  and permission DEFINITIONS. Those move only through `write-schema!`, which
  bumps `:eacl/schema-version`, which is already a cache-key component. A
  client latches one schema generation for its lifetime and must be recreated
  after an out-of-band schema write — that was already true before this
  namespace existed.

  What it deliberately does not APPLY to: historical reads. A cursor page or
  `at-exact-snapshot` request keys on the basis it pins instead. Stamps are
  :db/noHistory, so `d/as-of` resolves them only until the database indexes and
  collects the superseded values — on a fresh in-memory database they still
  appear, which is precisely what makes this dangerous to rely on. Once they
  are gone, every historical basis would read 'no stamp' and collide on one
  key. A pinned basis cannot fail that way, and keeping a cache warm for every
  point in time is a non-goal.

  Verified against Datomic (see eacl.datomic.watermark-test): unrelated
  application transactions do not move an epoch, unrelated RELATION churn does
  not move it either, and creates, deletes, deletes-by-object, schema changes,
  writes from another connection, and raw `d/transact` of relationship tx-data
  all do."
  (:require [datomic.api :as d]))

(def relation-version-attr
  "Installed by eacl.datomic.schema/v7-schema and by write-schema! when a
  database predates it. Stamped by EACL's relationship tx-data helpers."
  :eacl/relation-version)

(defn attribute-eid
  "The :eacl/relation-version attribute eid, or nil if it is not installed.

  Resolved from the caller's db and memoised, never resolved once at client
  construction: a client may legitimately be built before the EACL schema is
  installed — this repo's own benchmark seeding does exactly that — and an
  eagerly-resolved nil would silently mean 'no stamps exist' forever."
  [state db]
  (or @state
      (when-let [eid (d/entid db relation-version-attr)]
        (reset! state eid)
        eid)))

(defn- max-relation-stamp
  "Greatest stamp over `relation-eids`. A relation with no stamp — never
  written since the attribute was installed — contributes 0, which is correct:
  its first write lands a strictly greater tx id."
  [db attr-eid relation-eids]
  (reduce (fn [acc relation-eid]
            (let [stamp (some-> ^datomic.Datom
                                (first (d/datoms db :eavt relation-eid attr-eid))
                                (.v))]
              (if (and stamp (> (long stamp) (long acc)))
                (long stamp)
                acc)))
          0
          relation-eids))

(defn epoch-for
  "An epoch for an answer over `relation-eids`, as observed in `db`, or nil when
  no precise epoch can be established.

  Equal epochs guarantee that every relation the answer depends on holds
  identical relationship tuples. Unequal epochs mean nothing beyond
  'recompute'. nil means the answer must not be retained at all.

  nil is returned when:

    - the caller did not compute a dependency set (nil), or
    - the dependency set is empty, or
    - `:eacl/relation-version` is not installed — a v7 database stamped before
      per-relation stamps existed, which starts caching the moment its next
      write-schema! installs the attribute.

  The old fallback was `basis-t`, which any transaction invalidates. That is
  not merely a low hit rate: it measured WORSE than no cache at all (49us vs
  24us for `can?` under churn), because every read pays a publication it can
  never read back. Declining to retain is strictly better than retaining under
  a key nothing will match.

  Empty is deliberately NOT treated as 'depends on nothing, cache forever'.
  It is also what a dependency-calculation bug looks like, and the failure mode
  of getting that wrong is a permanently cached wrong answer. A permission with
  no relation dependencies grants nothing and is cheap to recompute, so the
  conservative reading costs nothing real."
  [state db relation-eids]
  (when (seq relation-eids)
    (when-let [attr-eid (attribute-eid state db)]
      (max-relation-stamp db attr-eid relation-eids))))

(defn make-epoch-state
  "Per-client memo for the attribute eid. Cheap; holds no db value."
  []
  (atom nil))

(defn safe-epoch-for
  "epoch-for, degrading to nil — decline to retain — on any failure."
  [state db relation-eids]
  (when state
    (try
      (epoch-for state db relation-eids)
      (catch Exception _ nil))))
