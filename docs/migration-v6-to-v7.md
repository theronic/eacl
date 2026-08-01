# EACL v6 → v7 Migration Guide

**Status:** current as of 2026-07-14.
**Applies to:** any EACL database written by a version pinned to a git SHA **before** commit `de9ebbc` (“Port EACL v7 to cursor-tree parity”, 2026-03-12). If your `deps.edn` pins an older SHA — including `884a1d0`, which README quickstarts have circulated — you are on v6 and this guide applies to you. (Not sure? [`eacl.migrations.v6-to-v7/detect-storage-version`](../modules/eacl-datomic/src/eacl/migrations/v6_to_v7.clj) classifies your database directly.)

v7 changed **how Relationships are stored in Datomic**. The public API (`eacl.core/IAuthorization`), the SpiceDB schema DSL, and the Relation/Permission schema entities are unchanged. Your *stored relationship data* must be rewritten once, and a small number of internal call sites must be updated if you reached below the public API.

EACL ships the migration as a namespace: [`eacl.migrations.v6-to-v7`](../modules/eacl-datomic/src/eacl/migrations/v6_to_v7.clj). It is **additive, idempotent, and reversible** up to the final cleanup step, and it is end-to-end tested in [`modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj`](../modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj) against real v6-model databases.

**v7 refuses to start against unmigrated v6 data.** `eacl.datomic.core/make-client` checks the storage version recorded in Datomic at construction time and throws `{:type :eacl/storage-version}` if the database still contains unmigrated v6 relationship entities — because v7 code reads only v7 tuples, starting up anyway would silently answer *every* permission check with `false`/empty. Migration is opt-in: run `migrate!` yourself, or pass `:auto-migrate-v6` to `make-client`.

---

## 1. What changed: the relationship storage model

### v6: one Datomic entity per Relationship

Each relationship was a first-class entity carrying five scalar attributes, plus two auto-computed composite tuples (`:db/tupleAttrs`) for index traversal — **7 datoms per relationship**:

```clojure
{:eacl.relationship/subject-type  :user
 :eacl.relationship/subject       17592186045425   ; ref to your entity
 :eacl.relationship/relation-name :owner
 :eacl.relationship/resource-type :account
 :eacl.relationship/resource      17592186045427}  ; ref to your entity
;; + :eacl.relationship/subject-type+subject+relation-name+resource-type+resource   (unique identity)
;; + :eacl.relationship/resource-type+resource+relation-name+subject-type+subject  (unique identity)
```

### v7: two tuple datoms, no relationship entity

Relationships are no longer entities. Each relationship is stored as **2 datoms**: a forward tuple asserted on your **subject** entity and a reverse tuple asserted on your **resource** entity. Both are heterogeneous, cardinality-many tuples (`:db/tupleTypes`, values supplied by EACL — not computed):

```clojure
;; on the SUBJECT entity:
[:db/add <subject-eid> :eacl.v7.relationship/subject-type+relation+resource-type+resource
 [<subject-type-kw> <relation-eid> <resource-type-kw> <resource-eid>]]

;; on the RESOURCE entity:
[:db/add <resource-eid> :eacl.v7.relationship/resource-type+relation+subject-type+subject
 [<resource-type-kw> <relation-eid> <subject-type-kw> <subject-eid>]]
```

Two details matter for migration:

1. **The relation is referenced by entity id, not by name.** The second tuple element is the eid of the `Relation` schema entity (the one carrying `:eacl.relation/resource-type` / `relation-name` / `subject-type`), not the relation-name keyword. Every relationship you migrate must resolve its `[resource-type relation-name subject-type]` triple to an existing Relation entity.
2. **Tuples live on *your* domain entities.** After migration, your permissioned entities carry EACL tuple datoms directly. This is what buys the read/write locality: traversal seeks straight through `d/index-range` over these tuples without touching intermediate entities.

Why v7: 2 datoms instead of 7 per relationship, better index locality for the cursor-tree traversal engine, and relation identity by ref instead of repeated keywords. See [plans/2026-03-12-eacl-v7-parity-plan.md](plans/2026-03-12-eacl-v7-parity-plan.md) for the design rationale.

### New in the v7 schema

- `:eacl/schema-version` (uuid) — a cache-invalidation stamp asserted by `write-schema!` whenever definitions change. The permission-path cache keys on it (issue #74). It is absent on a freshly migrated database, and that is a valid state: the stamp first appears on your first definition-changing `write-schema!`.
- `:eacl/storage-version` (long) — the relationship storage-model version, stamped `7` by a completed `migrate!`. This is what `make-client`'s startup check reads: a stamp of `7` (or higher) tells v7 that any remaining v6 relationship entities are migrated leftovers, not live data.

### Unchanged

- Relation entities (`:eacl.relation/*`) and unified Permission entities (`:eacl.permission/*`) — **identical in v6 and v7**, including their `:eacl/id` conventions and unique identity tuples.
- `:eacl/id`, `:eacl/schema-string`, and the stored `"schema-string"` singleton entity.
- The SpiceDB schema DSL and `write-schema!` semantics.
- The public `IAuthorization` protocol: `can?`, `lookup-resources`, `lookup-subjects`, `count-resources`, `read-relationships`, `write-relationships!`, `create-relationships!`, `delete-relationships!`, `read-schema`, `write-schema!`.
- The `schema/v6-schema` var was removed. Update bootstrap code that transacted it to `@(d/transact conn schema/v7-schema)` (or let `migrate!`/`ensure-v7-attributes!` install the attributes for you).

---

## 2. Code changes

### If you only use the public API

Three notes:

- **`make-client` now checks the storage version.** Construction throws `{:type :eacl/storage-version}` against unmigrated v6 relationship data (see the intro). Fresh databases and migrated databases are unaffected. Opt into automatic migration with `{:auto-migrate-v6 true}` or `{:auto-migrate-v6 {:schema "definition user {} ..."}}` (any [`migrate!`](../modules/eacl-datomic/src/eacl/migrations/v6_to_v7.clj) options map).
- **`make-client` option rename.** `:entity->object-id (fn [entity] id)` is deprecated in favour of `:entid->object-id (fn [db eid] id)`. The old key still works as an alias; supplying both throws. Unknown option keys now throw `{:type :eacl/invalid-config}` instead of being silently ignored — if you had a typo'd option in v6, v7 will tell you about it at client construction.
- **The pagination API changed.** `:cursor`/`:limit` are rejected with a typed error; paginate with `:first`/`:after` (forward) or `:last`/`:before` (backward). Lookups and `read-relationships` return `{:data [...] :page-info {:start-cursor ... :end-cursor ... :has-next-page? ... :has-previous-page? ...}}`.
- **Discard persisted cursors.** v7.3 retains the v7.2 `eacl3_...` page-token format: tokens are AES-GCM-encrypted, bound to the query and its Datomic basis, and expire after 5 minutes by default (`:page-token-ttl-seconds` to tune; `:page-token-key`/`:page-token-keyring` for multi-peer deployments). V7.3 additionally carries authenticated per-path frontiers for deep acyclic pages. Any v6 cursor value you stored is rejected after the upgrade. Treat pagination sessions as ephemeral across the migration.

### If you used internals

| v6 | v7 |
|---|---|
| `(d/transact conn [(impl/Relationship subj rel res)])` — `Relationship` returned a transactable entity map | `Relationship` returns plain data. Transact `(impl/tx-relationship db relationship)`, which returns the two tuple assertions. |
| `eacl.datomic.impl.base/Relationship` | **Removed.** Its output referenced attributes that no longer exist; transacting it fails with `:db.error/not-an-entity`. |
| String IDs in relationship tx-data acted as tempids implicitly | `tx-relationship` throws `{:type :eacl/unknown-object}` on unresolvable IDs. Pass `{:allow-tempids? true}` for same-transaction entity+relationship creation. |
| `eacl.datomic.rules*`, `eacl.datomic.impl.datalog` namespaces | **Removed** (dead v6-era Datalog implementations). |
| Custom queries against `:eacl.relationship/*` attributes or tuple indices | Rewrite against `read-relationships`, or the v7 tuple attributes if you must go low-level. |

Before/after for the common fixtures pattern (creating entities and relationships in one transaction):

```clojure
;; v6
@(d/transact conn
  [{:db/id "user1-tempid" :eacl/id "user1"}
   {:db/id "acct1-tempid" :eacl/id "acct1"}
   (impl/Relationship (->user "user1-tempid") :owner (->account "acct1-tempid"))])

;; v7
(let [db (d/db conn)]
  @(d/transact conn
    (concat
      [{:db/id "user1-tempid" :eacl/id "user1"}
       {:db/id "acct1-tempid" :eacl/id "acct1"}]
      (impl/tx-relationship db
        (impl/Relationship (spice-object :user "user1-tempid") :owner (spice-object :account "acct1-tempid"))
        {:allow-tempids? true}))))
```

### Behavior changes you inherit on the way

Upgrading from v6 also jumps over the 2026-07 audit root-cause fixes. All of them convert silent failures into correct behavior or loud, typed errors; storage and token formats are unchanged, and valid configurations and schemas work unmodified. If your error handling assumed v6's silent behaviors, update it now. Full details in the [full source audit report](reports/2026-07-06-eacl-full-source-audit.md).

- `write-schema!` **throws** on unparseable schema strings (v6 silently retracted the entire stored schema on a parse failure), on duplicate `definition`/relation declarations, and when replacing a non-empty schema with zero definitions (opt out with `{:allow-empty-schema? true}`). `//` and `/* */` comments are supported.
- Arrow targets are validated against **all** subject types of the source relation (v6 was declaration-order-dependent: only the last-declared type was checked).
- Reads with unknown object IDs return **empty results** (v6's `read-relationships` returned *all* relationships — a data leak — and lookups threw `AssertionError`s); writes throw `{:type :eacl/unknown-object}`.
- `make-client` throws `{:type :eacl/invalid-config}` on unknown option keys (v6 silently ignored them, so a typo'd ID-coercion config silently fell back to `:eacl/id`).
- Expired/corrupt page tokens are rejected with an error (v6 decoded them to nil and silently restarted pagination at page one). v7.2+ tokens are additionally encrypted and bound to the operation, query shape, and Datomic basis, so a token can never be replayed against a different query.
- `impl/tx-relationship` requires `{:allow-tempids? true}` to treat unresolvable string IDs as tempids (a typo'd ID in v6 silently created a ghost entity).
- Dead v6-era namespaces were removed: `eacl.datomic.rules*`, `eacl.datomic.impl.datalog`, and `eacl.datomic.impl.base/Relationship` (see the internals table above).

---

## 3. Migrating stored data

### The short version

```clojure
(require '[eacl.migrations.v6-to-v7 :as migrations])

;; 1. Backup. Rehearse this whole sequence on a restored copy first.
;; 2. Pause relationship writes (reads stay up).
(migrations/migrate! conn {:schema "definition user {} ..."})   ; your full SpiceDB schema string
;; 3. Deploy v7 code, resume writes. make-client now starts (the migration is stamped).
;; 4. After a soak period of days:
(migrations/retract-v6-relationship-entities! conn {})
```

Alternatively, opt in at startup and let client construction migrate:

```clojure
(eacl.datomic.core/make-client conn {:auto-migrate-v6 {:schema "definition user {} ..."}})
```

`migrate!` runs, in order: install v7 attributes → normalize legacy schema-entity IDs → re-assert your schema via `write-schema!` → backfill v7 tuples for every v6 relationship → verify completeness (throws `{:type :eacl.migration/incomplete}` rather than proceed) → optionally retract v6 entities (`{:retract-v6-entities? true}`; default false) → stamp `:eacl/storage-version 7`. It is idempotent — re-run it freely after an interruption or as a catch-up pass.

**Schema is migrated by re-assertion, not conversion.** `migrate!` does not interpret the v6 schema entities stored in Datomic; you pass your schema string and `write-schema!` validates and re-asserts it. On a standard v6 database this is a zero-delta no-op that keeps relation eids stable; outdated schema entities (anything not in the string you pass) are retracted by `write-schema!`'s delta logic. `:schema` is technically optional — the Relation/Permission model is identical in v6 and v7, so stored entities carry over — but passing it is recommended: it validates your schema round-trips and cleans up stale definitions.

The rest of this section explains what `migrate!` does and why each property holds, using the same namespace functions — read it before running against production; run the steps individually only if you need custom control.

### Design

The backfill writes v7 tuples **alongside** the v6 relationship entities without touching them:

- **v6 code ignores v7 tuples** (it reads relationship entities), so you can backfill while the old version serves reads.
- **v7 code ignores v6 entities** (it reads tuples), so leftover v6 entities are inert after you deploy — cleanup can wait.
- **The backfill is idempotent.** Re-asserting an identical tuple datom is a no-op, so you can safely re-run it at any point.

The one thing the design does **not** absorb is relationship *writes racing the migration*: a relationship created or deleted through v6 code after the backfill snapshot exists only on the v6 side. Additions are fixed by re-running `migrate!`; deletions are not (the stale tuple would resurrect the permission). **Pause relationship writes during the window.** Reads — `can?`, lookups — stay up throughout, which is what matters for authorization. If you truly cannot pause writes, `missing-tuples` (and set-diffing `read-relationships` output) gives you what drifted to reconcile before deploying.

### Sequence

| Step | Action | App runs | Reversible? |
|---|---|---|---|
| 1 | Backup; rehearse on a restored copy | v6 | — |
| 2 | Detect storage version | v6 | — |
| 3 | **Pause relationship writes**; run `migrate!` (installs attrs, re-asserts schema, backfills, verifies, stamps) | v6 | additive, idempotent |
| 4 | Deploy v7 code; resume writes | **v7** | redeploy v6 to roll back |
| 5 | Soak, then cleanup: `retract-v6-relationship-entities!` | v7 | **point of no return** |

### Step 1 — Backup and rehearse

Take a Datomic backup. Restore it somewhere disposable and run this entire guide against the copy first. The backfill runs at full read speed of your peer; rehearsal also tells you how long the write-pause window will be.

### Step 2 — Detect what you're running

```clojure
(migrations/detect-storage-version (d/db conn))
;; => :v6    — v6 relationship entities only: this guide applies
;;    :v7    — v7 tuples only: nothing to migrate
;;    :mixed — both: mid-migration, or migrated but not yet cleaned up
;;    :none  — no relationship data at all (fresh database)
```

The startup check is more precise than this summary: it passes whenever the database has an `:eacl/storage-version` stamp ≥ 7 **or** contains no v6 relationship entities at all. `:mixed` *without* a stamp is treated as unmigrated — it could be an interrupted backfill — which is why `migrate!` stamps as its final step, after verification.

### Step 3 — `migrate!` (what each phase does)

1. **`ensure-v7-attributes!`** transacts `schema/v7-schema`. Additive and idempotent against a v6 database: the shared attribute definitions are byte-identical (Datomic no-ops them) and only `:eacl/schema-version`, `:eacl/storage-version` and the two `:eacl.v7.relationship/*` tuple attributes are new. A running v6 application is unaffected.

2. **`normalize-schema-entity-ids!`** gives any Relation/Permission entity missing an `:eacl/id` its canonical id (derived from its own attributes, via upsert on the unique identity tuples). This only matters for ancient installs that predate the `:eacl/id` convention: `write-schema!` addresses schema entities by `[:eacl/id ...]` when retracting, so un-normalized entities could neither be managed nor cleaned up. Returns 0 on databases written by any recent v6.

3. **`write-schema!`** with your `:schema` string — validation, delta computation, retraction of schema entities not in the new schema. Note the interaction with step 4: if your schema string drops a relation that stored v6 relationships still use, the backfill will throw `{:type :eacl.migration/missing-relation}` and the migration aborts additively — nothing is lost; fix the schema string (or retract the dead rows) and re-run.

4. **`backfill-relationship-tuples!`** resolves each v6 relationship's `[resource-type relation-name subject-type]` triple to its Relation eid and asserts the forward + reverse tuples, in batches (default 500 relationships = 1,000 datoms per transaction). For tens of millions of relationships, lower `:batch-size` or adapt `v6-relationship->v7-txes` into a pipelined `transact-async` loop, and run against a dedicated peer.

5. **`verify-backfill`** streams every v6 relationship and confirms both its tuples exist, via per-row index lookups (no large sets in memory). `migrate!` throws `{:type :eacl.migration/incomplete}` with a sample of missing rows rather than stamp an unverified migration. v7 tuple counts *exceeding* the v6 entity count is not a failure — relationships written through v7 code after deploy have no v6 counterpart. You can also run it (or `missing-tuples`) yourself any time before cleanup.

6. **`stamp-storage-version!`** records `{:eacl/storage-version 7}` so `make-client` knows the remaining v6 entities are leftovers, not live data. **If you migrated manually before this namespace existed** (following an earlier revision of this guide), your database has tuples but no stamp and `make-client` will refuse to start: either re-run `migrate!` (idempotent) or call `stamp-storage-version!` directly.

### Step 4 — Deploy v7 code, resume writes

Update your pin to a current SHA and deploy. From this moment all reads and writes go through the tuples; the v6 entities are inert. Spot-check real permissions through the v7 client:

```clojure
(require '[eacl.core :as eacl] '[eacl.datomic.core])

(def acl (eacl.datomic.core/make-client conn {...your id-coercion config...}))
(eacl/can? acl known-subject :some-permission known-resource)   ; => expected answer
(eacl/lookup-resources acl {:subject known-subject :permission :view :resource/type :server :first 10})
```

### Step 5 — Cleanup (after a soak period)

Once you're satisfied — days, not minutes; this forfeits the easy rollback:

```clojure
(migrations/retract-v6-relationship-entities! conn {})   ; batched, idempotent

(migrations/detect-storage-version (d/db conn))
;; => :v7
```

---

## 4. Rollback

- **Before cleanup:** redeploy your v6 pin. The v6 relationship entities are still intact and v6 code ignores both the v7 tuples and the `:eacl/storage-version` stamp. Relationships written *while on v7* exist only as tuples and disappear from v6's view — keep the soak window's writes in mind, or re-run a reverse sync if you had significant write traffic.
- **After cleanup:** restore from the Step 1 backup. There is no in-place path back.

---

## 5. Edge cases & gotchas

- **Relationships with no matching Relation.** The backfill throws `{:type :eacl.migration/missing-relation}` with the offending triple in `ex-data`. These rows never resolved to a Relation in v6 traversal either. Either add the missing Relation to your schema string, or retract the dead relationship entities, then re-run `migrate!`.
- **Very old v6 installs without `:eacl/id` on schema entities** are handled automatically by `normalize-schema-entity-ids!` (see Step 3.2) — the report's `:normalized-schema-entity-ids` count tells you it happened.
- **Dangling refs.** A v6 relationship whose subject or resource entity was retracted migrates into a tuple pointing at the dead eid. It behaves the same as it did in v6 (matches nothing meaningful), but if you want to use the migration as a cleaning pass, pre-filter such rows by checking the eids under `:eacl.relationship/subject` / `:eacl.relationship/resource` still resolve to live entities.
- **`d/as-of` history.** Time-travel views with a basis **before** the backfill contain no v7 tuples, so v7 code sees an empty permission graph there. If you audit permissions against historical bases, keep a v6-pinned tooling environment around for pre-migration history.
- **Old attributes stay installed.** Datomic cannot uninstall attributes; the seven `:eacl.relationship/*` definitions remain in your schema forever, unused (they are kept, documented, as `eacl.migrations.v6-to-v7/v6-relationship-schema` for reference and test fixtures). Harmless. Storage from retracted entities is only truly reclaimed by decanting to a new database, which is far outside this guide's scope.
- **Cursors don't survive.** Covered above, worth repeating: any persisted v6 cursor is rejected under v7.2+, and page tokens expire after 5 minutes by default. Mid-flight pagination sessions must restart.
- **Storage during the transition.** Between backfill and cleanup you carry both representations (~9 datoms per relationship instead of 7). After cleanup, the live size is 2 datoms per relationship — a net reduction — though history retains everything.

---

## 6. FAQ

**`make-client` suddenly throws `{:type :eacl/storage-version}` — why?**
Your database contains v6 relationship entities and no migration stamp. This is the startup guard doing its job: v7 reads only tuples, so starting anyway would answer every check with `false`/empty. Run `migrate!`, or pass `:auto-migrate-v6`, or — if you already migrated manually — `stamp-storage-version!`. See §3.

**Do I need downtime?**
No read downtime. Permission checks keep working through the whole sequence — on v6 code until the deploy, on v7 code after. You need a relationship-*write* pause between the backfill and the v7 deploy: typically minutes, sized by your rehearsal.

**How long does the backfill take?**
It is one indexed scan plus 2 datoms written per relationship. At the ~800k-relationship scale EACL is benchmarked against, expect minutes, dominated by transactor throughput.

**Can I run `migrate!` more than once?**
Yes — it's idempotent. Re-running after an interruption or as a catch-up pass adds only whatever is missing, and re-verifies.

**Is the migration itself tested?**
Yes — [`modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj`](../modules/eacl-datomic/test/eacl/migrations/v6_to_v7_test.clj) builds real v6-model databases (entity-per-relationship storage) and exercises the full path end-to-end: `migrate!` with and without schema re-assertion, `make-client`'s refusal and `:auto-migrate-v6`, idempotency, cleanup, ancient no-`:eacl/id` schema entities, missing-Relation aborts, and the manual-migration-then-stamp flow.

**I never call `write-schema!` — do the caches work?**
Yes. A database with no `:eacl/schema-version` stamp is valid; caches key on a nil stamp plus database identity. But only `write-schema!` bumps the stamp, so programmatic schema edits require a manual `evict-permission-paths-cache!` on every peer. Adopting `write-schema!` (which `migrate!`'s `:schema` option does for you) is the supported path.

**Does this change my SpiceDB sync story?**
No. Relationships remain 3-tuples of `[subject relation resource]` at the API boundary; only their Datomic encoding changed. If you tail the transactor queue, watch the two `:eacl.v7.relationship/*` attributes instead of `:eacl.relationship/*` entities.
