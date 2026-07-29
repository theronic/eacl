# EACL Full-Source Bug Hunt — Cache Scoping, Dangling Tuples, Recursive Scaling

**Date:** 2026-07-29
**Scope:** Complete read of `src/` at `2ccc6c4` (branch `codex/v7.3-cursor-frontiers`), plus runtime
verification of every finding on a **freshly started nREPL** (port 7788, `clojure -M:dev:nrepl`).
**Baseline:** `73 tests, 1297 assertions, 0 failures, 0 errors` on that fresh REPL
(`indexed-test`, `spice-test`, `schema-test`, `config-test`, `parser-test`, `differential-test`,
`v6-to-v7-test`, `schema-basis-test`). Every bug below is present with the suite fully green.

> Read alongside [2026-07-28-v7.3-cursor-frontier-review.md](2026-07-28-v7.3-cursor-frontier-review.md).
> That review's findings 1–7, 9, 11 are fixed on this branch and are **not** repeated here.
> Its finding 8 (silent-empty on unknown permission names) is re-raised only where a *new* variant
> was found (§4). Its finding 10 (`count-subjects` unexposed) is still open and is noted in §12.

---

## What I could not break

Before the findings, the things I actively attacked and could not falsify — these are solid:

| Attacked | Result |
|---|---|
| v7.3 direction-scoped cursor frontiers: 20 accounts × 200 servers, shuffled eids so intermediate order is uncorrelated with result order; 3 seeds × 4 users × 2 permissions × page sizes {1,2,3,5,7,13,100}, forward **and** backward, against a `can?` oracle | **168/168 walks exact**, 13 314 results enumerated, zero skips/dupes |
| `count-resources` agreement with the paginated union on the same corpus | exact |
| Recursive **reverse** lookup (`lookup-subjects` through `parent->read`), 6 seeds × 8 folders, vs. `can?` oracle, incl. page size 1 and `count-subjects` | **48/48 exact** — note this path has *no* coverage in `differential-test`, which only checks recursive forward |
| Token layer: `d/as-of` basis pinning (wrote new grants mid-walk, page 3 correctly did not see them), cross-subject token replay | pinned correctly; replay rejected with `Page token does not match the current query.` |
| `write-schema!` idempotency: second write of an identical schema string | zero deltas, schema-version **not** bumped |

The frontier algorithm itself is correct. Every bug below is in the layers around it.

---

## Severity index

| # | Sev | Finding | Verified |
|---|---|---|---|
| 1 | **Critical** | `d/with` speculative db **poisons the live db's permission-path cache** → `can?` grants a permission the committed schema does not define | REPL |
| 2 | **High** | Databases with no `:eacl/schema-version` stamp cache permission paths **forever**, with no invalidation path — a whole class of installs (programmatic schema, `migrate!` without `:schema`) | REPL |
| 3 | **High** | `:db.fn/retractEntity` on a permissioned entity leaves **dangling relationship tuples**: `can?` returns `true` for deleted resources, `lookup-subjects` lists deleted subjects, and the orphans are **unremovable through the public API** | REPL |
| 4 | **High** | Recursive pagination is O(N²) and **hard-fails mid-walk** at `:max-derived-grants` — page 1 succeeds, page 100 throws | REPL |
| 5 | Medium | `{:subject/id nil}` (and any anchor key present-but-nil) defeats the `:eacl.filters/missing-anchor` guard → silent **global index scan** | REPL |
| 6 | Medium | Bare `:last` works for acyclic permissions and **throws** for recursive ones — adding `parent->read` to a schema breaks existing callers | REPL |
| 7 | Medium | `count-resources` / `count-subjects` `doall` the entire result set — head-retention OOM on large grant sets | code |
| 8 | Medium | Recursive `:last`/`:before` at ordinal 0 returns `has-next-page? true` with a `nil` `end-cursor`; `:after nil` silently restarts at page 1 → naive loops never terminate | REPL |
| 9 | Low | `eacl.datomic.impl/can?` 2-arity is broken (`ArityException`) | REPL |
| 10 | Low | `:create` conflict, `:unspecified` and `can!` throw bare `java.lang.Exception` — undiscriminable by callers | REPL |
| 11 | Low | `relationship-exists?` consults only the forward index, so a half-written pair can never be repaired by `:touch`/`:delete` | code |
| 12 | Info | Performance: reflective `(.id db)` on the hot cache path, uncached `traversal-permission?`, per-datom vector allocation in the scan loop, full relation-table scan per `read-relationships` | REPL |

---

## 1. `d/with` speculative databases poison the live permission-path cache — **Critical**

**Files:** [indexed.clj:325-350](../../src/eacl/datomic/impl/indexed.clj#L325-L350) (`classified-view`,
`schema-cache-scope`), [indexed.clj:363-437](../../src/eacl/datomic/impl/indexed.clj#L363-L437)
(`permission-paths-cache-key`, `get-permission-paths`).

### The defect

The cache scope is `[(str (.id db)) (str (schema-version db))]`. Neither component distinguishes a
`d/with` speculative database from the committed database it was derived from:

- `(.id db)` is the **database** uuid; a `d/with` db inherits it verbatim.
- `d/is-filtered` / `d/as-of-t` / `d/since-t` are all `false`/`nil` on a `d/with` db, so
  `classified-view` positively classifies it as `:plain`.
- `:eacl/schema-version` is only asserted by `write-schema!`, so a `d/with` that adds a
  `Permission` entity does not change it.

Result: a `d/with` db and its source share a cache key while having **different schemas**.
Whichever one is queried first writes its permission paths into the shared slot.

### Verified repro (fresh REPL)

```clojure
(schema/write-schema! conn "definition user {}
                            definition account { relation owner: user
                                                 permission admin = owner }")
;; …u owns a…
(def db (d/db conn))
;; preview a schema change WITHOUT committing it
(def spec (:db-after (d/with db [(base/Permission :account :view {:relation :owner})])))

(idx/can? spec (spice-object :user U) :view (spice-object :account A))  ;=> true   (correct for `spec`)
(idx/can? db   (spice-object :user U) :view (spice-object :account A))  ;=> true   ← WRONG
```

`:view` **does not exist** in the committed database. Expected `false`. The false grant persists
for the life of the process (or until `evict-permission-paths-cache!`), affects every peer thread,
and is silent.

The mirror case is equally real: a `d/with` that *retracts* a `Permission`, evaluated first, caches
the narrowed path set onto the live db and produces false **denials**.

### Why the existing note does not cover this

[indexed.clj:298-302](../../src/eacl/datomic/impl/indexed.clj#L298-L302) says programmatic edits
"do not bump the version, so caches may serve paths computed from the pre-edit schema." That
describes the *speculative db reading stale live paths*. The demonstrated failure is the opposite
and far worse direction: **the speculative db writes its schema into the live db's cache slot.**
Nothing in the code or docs anticipates that.

### Recommended fix

The invariant that must hold is: *two db values sharing a cache key must have identical Relation +
Permission datom sets.* Three options, cheapest-correct first:

**(a) Content fingerprint, memoised per basis (recommended).** Key the path cache on a fingerprint
derived from the schema datoms, and memoise the *fingerprint* (not the paths) on
`[db-id, basis-t, as-of-t]` in a small bounded map:

```clojure
(defn- schema-fingerprint* [db]
  (letfn [(f [attr] (reduce (fn [[n mx] d] [(inc n) (max mx (:tx d))]) [0 0]
                            (d/datoms db :aevt attr)))]
    [(f :eacl.relation/relation-name)
     (f :eacl.permission/permission-name)
     (some-> (schema-version db) str)]))
```

This is exact (additions change the count and tx; retractions change the count; same-tx
retract+add changes the max tx), and it preserves the whole point of issue #74: a
relationship-only transaction bumps `basis-t`, so the fingerprint is recomputed once — and comes
out **identical**, so every path entry still hits. A `d/with` db has its own `basis-t`
(verified: source `1008`, with-db `1009`), so it gets its own fingerprint computation and, because
its schema differs, its own cache key. Cost: one O(#schema-entities) scan per new db value,
amortised across every query at that basis.

**(b) Include `(d/basis-t db)` in the scope.** One line, exact, closes both this and §2 — but
re-introduces exactly what #74 removed: every write transaction invalidates every path entry.

**(c) Bail out of the cache when the stamp is absent.** Closes §2 only, not this. See §2.

Whatever is chosen, `evict-permission-paths-cache!` should be documented as *mandatory* after any
`d/with`-based permission evaluation until the fix lands, and the note at
[indexed.clj:288-309](../../src/eacl/datomic/impl/indexed.clj#L288-L309) should be corrected —
today it understates the failure mode.

---

## 2. Databases with no schema-version stamp cache paths permanently — **High**

**Files:** [indexed.clj:339-350](../../src/eacl/datomic/impl/indexed.clj#L339-L350),
[schema.clj:437-459](../../src/eacl/datomic/schema.clj#L437-L459),
[v6_to_v7.clj:348](../../src/eacl/migrations/v6_to_v7.clj#L348).

### The defect

`schema-version` is `nil` unless `write-schema!` has run at least once. When it is `nil` the scope
degrades to `[(str (.id db)) nil]` — a key that is **constant for the lifetime of the database**.
Every permission path computed against such a database is cached forever, and nothing can ever
invalidate it, because `write-schema!` is the only writer of the stamp.

Affected installs:

- Anyone building schema programmatically with `eacl.datomic.impl/Relation` and
  `eacl.datomic.impl/Permission` — a documented public API, and the style EACL's own
  `test/eacl/datomic/fixtures.clj` uses.
- Any database migrated with `(migrate! conn {})` — `write-schema!` runs only `(when schema …)`,
  so a v6→v7 migration without `:schema` never stamps.
- Any database whose schema predates the `:eacl/schema-version` attribute and has not been
  re-written since.

### Verified repro (fresh REPL)

```clojure
@(d/transact conn [(base/Relation :account :owner :user)])   ; programmatic schema, no write-schema!
;; …u owns a…
(idx/can? (d/db conn) (spice-object :user U) :admin (spice-object :account A))  ;=> false (correct — no :admin yet)

@(d/transact conn [(base/Permission :account :admin {:relation :owner})])       ; add the permission

(idx/schema-version (d/db conn))                                               ;=> nil
(idx/can? (d/db conn) (spice-object :user U) :admin (spice-object :account A))  ;=> false  ← WRONG, expected true
(idx/get-permission-paths (d/db conn) :account :admin)                          ;=> []     ← stale empty
(idx/evict-permission-paths-cache!)
(idx/can? (d/db conn) (spice-object :user U) :admin (spice-object :account A))  ;=> true   (proves the cache is the cause)
```

The failure direction shown here is fail-closed (a granted permission does not work). The
retraction direction is fail-**open**: programmatically retracting a `Permission` leaves the cache
granting it indefinitely.

### Recommended fix

1. Adopt §1(a) — a content fingerprint makes this class of bug structurally impossible.
2. Independently, and cheaply, **make the contract enforceable instead of aspirational**:
   - `schema-cache-scope` should return `uncached-scope` when `(schema-version db)` is `nil`.
     Un-stamped databases then get correct-but-uncached behaviour rather than fast-and-wrong.
   - Export a public `eacl.datomic.schema/stamp-schema-version!` so programmatic-schema users can
     opt back into caching after their own writes.
   - Have `migrate!` stamp a version unconditionally (it already transacts a schema-string entity
     in `stamp-storage-version!` — one extra `:eacl/schema-version (d/squuid)` there is free).
   - Consider a `make-client` warning (not a hard failure) when the database contains
     `:eacl.relation/*` definitions but no `:eacl/schema-version` — same spirit as
     `assert-storage-compatible!`.

---

## 3. `retractEntity` leaves dangling relationship tuples — **High**

**Files:** [impl.clj:130-146](../../src/eacl/datomic/impl.clj#L130-L146) (`add-relationship-txes`
/ `retract-relationship-txes`), [schema.clj:176-195](../../src/eacl/datomic/schema.clj#L176-L195)
(the two `:db.type/tuple` attributes), [core.clj:329-349](../../src/eacl/datomic/core.clj#L329-L349)
(`resolve-existing-object`).

### The defect

A v7 relationship is two datoms on two *different* entities:

```
[subject-eid  …/subject-type+relation+resource-type+resource  [st rel-eid rt resource-eid]]
[resource-eid …/resource-type+relation+subject-type+subject   [rt rel-eid st subject-eid]]
```

The peer entities appear only *inside the tuple values*. Datomic's `:db.fn/retractEntity` follows
`:db.type/ref` **attributes**; it does not follow ref-typed *components of a heterogeneous tuple*,
and heterogeneous tuples cannot be `:db/isComponent`. So retracting a permissioned entity — the
ordinary Datomic idiom for deleting a domain entity — removes only the half of each relationship
that happens to live on that entity, and leaves the other half dangling.

### Verified repro A — deleted **resource**, `can?` returns `true`

```clojure
;; user "u" is :owner of account "a"; permission admin = owner
@(d/transact conn [[:db.fn/retractEntity A]])            ; delete the account entity

(seq (d/datoms (d/db conn) :eavt A))                                             ;=> nil  (entity is gone)
(idx/can? (d/db conn) (spice-object :user U) :admin (spice-object :account A))   ;=> true  ← WRONG
(idx/lookup-resources (d/db conn) {…:permission :admin :resource/type :account}) ;=> [17592186045423]
(idx/count-resources  (d/db conn) {…})                                           ;=> {:count 1}
```

With the default `:eacl/id` coercion the public `can?` is accidentally shielded (the lookup ref no
longer resolves). But with the **README-documented** eid-as-external-id configuration
([README.md:473-475](../../README.md#L473)):

```clojure
(def acl (core/make-client conn {:object-id->ident identity
                                 :entid->object-id (fn [_ e] e)}))
(eacl/can? acl (spice-object :user U) :admin (spice-object :account A))  ;=> true  ← privilege on a deleted entity
```

Through *any* configuration, `lookup-resources` still returns the ghost, coerced to
`#SpiceObject{:type :account, :id nil}`.

### Verified repro B — deleted **subject**, orphan is unremovable

```clojure
@(d/transact conn [[:db.fn/retractEntity SU]])           ; delete the user entity

(idx/lookup-subjects db {:resource (spice-object :account SA) :permission :admin
                         :subject/type :user :first 5})
;=> [17592186045422]                                     ← deleted user still listed
(eacl/lookup-subjects acl {…})
;=> [#SpiceObject{:type :user, :id nil, :relation nil}]

;; and it can never be cleaned up through EACL:
(eacl/delete-relationship! acl (spice-object :user "su") :owner (spice-object :account "sa"))
;=> throws  "Unknown object: :user with id \"su\" does not exist."
(eacl/delete-relationship! acl (spice-object :user SU)   :owner (spice-object :account "sa"))
;=> throws  "Unknown object: :user with id 17592186045422 does not exist."
```

`resolve-existing-object` correctly refuses to write relationships for nonexistent endpoints — but
that same guard makes the orphan **permanently unreachable**. Only a raw `d/transact` retracting
the tuple datom by hand can remove it.

Note also that `can?` and `lookup-subjects` now **disagree**, which breaks the invariant
`differential-test` asserts everywhere else. Any downstream job driven by `lookup-subjects`
("notify all admins", "sync relationships to SpiceDB") will act on ghost subjects with `nil` ids.

### Recommended fix

This needs both a runtime guard and a documented workflow:

1. **Ship a cascade helper**, e.g. `eacl.datomic.impl/tx-delete-object`, that emits retractions for
   both directions of every relationship touching an eid — `d/datoms :eavt eid <forward-attr>` for
   the forward half, and for the reverse half a bounded `d/index-range` over
   `…/resource-type+relation+subject-type+subject` per (type, relation) pair. Expose it on
   `IAuthorization` as `delete-object!` / `delete-relationships-for-object!`.
2. **Document it prominently** in the README's write section and in "Limitations, Deficiencies &
   Gotchas": *"`:db.fn/retractEntity` on a permissioned entity does not remove its relationships.
   Call `eacl/delete-object!` first."* Today the README says nothing about this at all.
3. Consider a cheap consistency checker (`eacl.datomic.repair/find-orphaned-tuples`) plus a
   repair transaction, since existing production databases will already contain orphans.
4. Optional hardening: have `lookup-resources` / `lookup-subjects` skip results whose eid has no
   datoms. That is one extra `d/datoms` probe per returned row and would make ghosts invisible even
   before an operator repairs the data — worth measuring against the latency budget.

---

## 4. Recursive pagination is O(N²) and hard-fails mid-walk — **High**

**Files:** [indexed.clj:570-573](../../src/eacl/datomic/impl/indexed.clj#L570-L573)
(`*recursive-traversal-limits*`), [indexed.clj:942-982](../../src/eacl/datomic/impl/indexed.clj#L942-L982)
(`collect-forward-after` seek mode), [indexed.clj:1750-1778](../../src/eacl/datomic/impl/indexed.clj#L1750-L1778)
(`count-resources`).

### The defect

Recursive pages carry an **ordinal**, and resuming replays the traversal from scratch, discarding
results until the ordinal is reached. Work per page therefore grows linearly with the page's
offset, and the per-page `:derived-grants` counter is checked against a hard limit of `100 000`.

### Verified measurement

400 folders, all children of one root the user reads; `permission read = reader + parent->read`;
page size 10, walking to the end:

| page | `:derived-grants` | `:advanced-stream-datoms` |
|---|---|---|
| 0 | 11 | 11 |
| 8 | 91 | 91 |
| 16 | 171 | 171 |
| 24 | 251 | 251 |
| 32 | 331 | 331 |
| 40 | 401 | 401 |

Exactly `page × page-size` — full prefix replay, no reuse. Total cost to enumerate N results is
`O(N² / page-size)`.

The limit then converts that into an outright failure. Scaling the limit down proportionally to
demonstrate the mechanism at test size:

```clojure
(binding [idx/*recursive-traversal-limits* {:max-derived-grants 200 …}]
  (count (:data (idx/lookup-resources fdb (assoc fq :first 10))))  ;=> 10        page 1 fine
  (count (collect-fwd fdb fq 10))                                  ;=> throws :eacl.recursive-traversal/limit-exceeded
  (idx/count-resources fdb fq))                                    ;=> throws :eacl.recursive-traversal/limit-exceeded
```

With the shipped default of `100 000`, any recursive permission whose grant set exceeds ~100 k
**breaks partway through pagination**: page 1 returns fine, deep pages throw. `count-resources` and
`count-subjects` on such a permission always throw, because they walk to the end
([indexed.clj:1760-1773](../../src/eacl/datomic/impl/indexed.clj#L1760-L1773)). This directly
contradicts the README's stated goal of "10M permissioned entities with real-time performance" for
any schema containing a recursive permission (the common `folder`/`group` hierarchy shape).

Compounding it: `*recursive-traversal-limits*` is a dynamic var with no `make-client` option, so an
operator cannot raise it without `alter-var-root` or a `binding` around every call site — and the
5-minute default page-token TTL means a slow deep walk can expire before it completes anyway.

### Recommended fix

Short term, cheap:

- Make the limits configurable through `make-client` (thread them through `opts` rather than the
  dynamic var), and document the ceiling explicitly in "Limitations".
- Track the replay separately from genuinely new work: the seek phase should either not count
  toward `:max-derived-grants`, or count against a much larger dedicated budget. Failing a deep
  page because the *prefix* was large is the wrong signal.
- Make `count-resources` / `count-subjects` on recursive permissions either stream without
  ordinal-replay (one traversal, count as you go — they already control the whole walk, so they do
  not need cursors at all) or document that they are bounded.

Medium term: this is the materialised-grants design already sketched in
`docs/plans/2026-05-17-recursive-pagination-effective-grants-plan.md`. The ordinal-replay engine is
correct but is not a pagination strategy that scales; deep pages need a resumable frontier, as the
acyclic engine got in v7.3.

---

## 5. Present-but-nil anchor keys defeat the `read-relationships` guard — **Medium**

**File:** [impl.clj:436-440](../../src/eacl/datomic/impl.clj#L436-L440).

```clojure
(when-not (some #(contains? filters %) relationship-anchor-keys) (throw …))
```

`contains?` tests key *presence*, not value. `{:subject/id nil}` therefore satisfies the guard,
while every downstream consumer treats the value as absent
(`subject-eid (when subject-id (d/entid db subject-id))` →`nil`), so `scan-plan` falls through to
`:global-reverse` with a `nil` tuple prefix — an unbounded scan of the entire reverse relationship
index.

Verified:

```clojure
(count (:data (impl/read-relationships db {:subject/id nil :first 5})))  ;=> 5   (global scan, no error)
(impl/read-relationships db {:first 3})                                  ;=> throws :eacl.filters/missing-anchor
```

This is a regression of the fix for finding 3 in the 2026-07-28 review, and `{:subject/id nil}` is
exactly what you get from `{:subject/id (get-in request [:params :user-id])}` when the parameter is
missing — the realistic path to an accidental full-index scan in production.

**Fix:** `(some #(some? (get filters %)) relationship-anchor-keys)`, and reject explicit `nil`
values for anchor keys with a distinct message so the caller learns their parameter was empty.

Related, lower priority: `{:resource/relation :owner}` alone is accepted as an anchor but also
produces a global scan whenever more than one relation shares that name
([impl.clj:227-233](../../src/eacl/datomic/impl.clj#L227-L233) only builds a tuple prefix when
`single-relation-hint` matches exactly one relation). Worth either documenting or requiring a type
alongside a bare relation filter.

---

## 6. Bare `:last` is accepted for acyclic permissions and rejected for recursive ones — **Medium**

**Files:** [indexed.clj:1031-1034](../../src/eacl/datomic/impl/indexed.clj#L1031-L1034),
[indexed.clj:1340-1343](../../src/eacl/datomic/impl/indexed.clj#L1340-L1343).

```clojure
(idx/lookup-resources db  (assoc acyclic-q :last 2))     ;=> works, returns the last page
(idx/lookup-resources db4 (assoc recursive-q :last 2))   ;=> throws "Bare :last is not supported for
                                                         ;;          recursive traversal pagination."
```

Whether a permission is "recursive" is a property of the *schema*, invisible at the call site. A
caller doing `{:last 20}` to show the newest page works fine until someone adds
`permission read = reader + parent->read` to the schema — at which point the same call starts
throwing at runtime. That is a breaking schema change disguised as an additive one.

**Fix:** pick one contract and make it uniform. Either (a) support bare `:last` for recursive
permissions by exhausting the traversal into a bounded ring buffer — honest but O(N), so it should
be gated on the same configurable limit as §4 — or (b) reject bare `:last` for *all* permissions
and require an explicit `:before`, which is a breaking change but at least a consistent one. If (a)
is chosen, note that `count-resources` already exhausts the traversal, so the cost is precedented.
Either way, `traversal-permission?` should be exposed (or surfaced in `read-schema`) so callers can
discover which permissions have which capabilities.

---

## 7. `count-resources` / `count-subjects` retain the whole result set — **Medium**

**Files:** [indexed.clj:1774-1778](../../src/eacl/datomic/impl/indexed.clj#L1774-L1778),
[indexed.clj:1804-1808](../../src/eacl/datomic/impl/indexed.clj#L1804-L1808).

```clojure
(let [{:keys [results]} (lazy-merged-lookup db forward-direction query page-req)
      counted (doall results)]
  {:count (count counted) :limit -1})
```

`counted` is bound to the **head** of the fully realised lazy seq, so the entire grant set is
materialised and held for the duration. At EACL's stated target of ~10 M permissioned entities,
counting a broad permission retains ~10 M boxed `Long`s plus cons cells — several hundred MB, on a
call the README describes only as "slow".

**Fix:** one line each — `(reduce (fn [n _] (inc n)) 0 results)`. Nothing else consumes `counted`.

While there: the `:size max-page-size` in the constructed `page-req` is dead
(`lazy-merged-lookup` reads only `:direction` and `:bound`), which reads as if counting were capped
at 10 000 when it is not. Drop it or make it real.

---

## 8. Recursive backward pagination emits an inconsistent empty page; `:after nil` restarts — **Medium**

**Files:** [indexed.clj:1055-1059](../../src/eacl/datomic/impl/indexed.clj#L1055-L1059),
[indexed.clj:1364-1368](../../src/eacl/datomic/impl/indexed.clj#L1364-L1368),
[indexed.clj:39-82](../../src/eacl/datomic/impl/indexed.clj#L39-L82) (`normalize-page-request`).

Paging backward from the *first* result of a recursive walk (ordinal 0):

```clojure
{:data []
 :page-info {:start-cursor nil, :end-cursor nil,
             :has-next-page? true,          ; ← hard-coded at indexed.clj:1058
             :has-previous-page? false}}
```

`has-next-page? true` with a `nil` `end-cursor` violates the contract every other page upholds.
It becomes a liveness bug when combined with `normalize-page-request`, which accepts an explicit
`nil` bound and silently means "start over":

```clojure
(idx/lookup-resources db (assoc q :first 2 :after nil))  ;=> page 1, no error
```

So the natural client loop —
`(loop [c nil] (let [p (lookup … :after c)] (when (has-next-page? p) (recur (end-cursor p)))))` —
never terminates once it lands on that page: `has-next-page?` is true, `end-cursor` is nil,
`:after nil` returns page 1, repeat.

**Fix:** two independent one-liners.
1. `:has-next? (boolean (seq items))` in the recursive `:desc` branches — an empty backward page has
   no successor to point at.
2. In `normalize-page-request`, treat a present-but-`nil` `:after`/`:before` as an error
   (`:eacl.pagination/invalid-cursor`), mirroring the `:cursor`/`:limit` rejections directly above
   it. Silently restarting is the worst of the three options.

---

## 9. `eacl.datomic.impl/can?` 2-arity is broken — **Low**

**Files:** [impl.clj:18-22](../../src/eacl/datomic/impl.clj#L18-L22),
[indexed.clj:1618](../../src/eacl/datomic/impl/indexed.clj#L1618).

```clojure
(impl/can? db {:subject … :permission … :resource …})
;=> clojure.lang.ArityException: Wrong number of args (2) passed to: eacl.datomic.impl.indexed/can?
```

`impl/can?` declares `([db demand] (impl.indexed/can? db demand))` but `impl.indexed/can?` has only
the 4-arity form. The map arity has never worked. (The protocol-level `eacl/can?` map arity on
`Spiceomic` is fine — it destructures before dispatching.)

**Fix:** either implement the map arity in `impl.indexed/can?` or delete the dead 2-arity from
`impl/can?`. It has no test coverage either way.

---

## 10. Bare `java.lang.Exception` for write conflicts — **Low**

**Files:** [impl.clj:42-47](../../src/eacl/datomic/impl.clj#L42-L47) (`can!`),
[impl.clj:490-500](../../src/eacl/datomic/impl.clj#L490-L500) (`:create` / `:unspecified`).

```clojure
(impl/tx-update-relationship db {:operation :create :relationship <existing>})
;=> java.lang.Exception  ":create relationship conflicts with existing tuple relationship"
```

Everything else in EACL throws `ex-info` with a typed `:eacl/error` or `:type` key; these three do
not, so a caller cannot distinguish "this relationship already exists" (usually retryable/ignorable
— it is exactly what `:touch` is for) from any other failure without string-matching the message.

**Fix:** `ex-info` with `{:type :eacl/relationship-conflict, :relationship …}`,
`{:type :eacl/unsupported-operation}` and `{:type :eacl/unauthorized, :subject …, :permission …,
:resource …}` respectively.

---

## 11. `relationship-exists?` checks only the forward index — **Low**

**File:** [impl.clj:148-158](../../src/eacl/datomic/impl.clj#L148-L158).

`:touch` skips writing when the forward datom exists, and `:delete` skips retracting when it does
not. Either half of a relationship pair being absent — which §3 shows is reachable — leaves the
other half permanently unrepairable through the API: `:touch` decides "already there", `:delete`
decides "nothing to do".

**Fix:** check both tuples, and make `:touch` assert whichever half is missing (`add-relationship-txes`
is already idempotent) and `:delete` retract both unconditionally. Datomic ignores a retraction of an
absent datom, so `:delete` can simply drop the existence check entirely.

---

## 12. Performance observations (measured)

Not bugs, but each is on a hot path and each is cheap to fix.

**a. Reflective `(.id db)` in the cache key.** [indexed.clj:348](../../src/eacl/datomic/impl/indexed.clj#L348)
has no type hint, so every `get-permission-paths` call makes a reflective invocation. Measured cost
of `schema-cache-scope` ≈ **1.2 µs/call**, of which `(.id db)` is the bulk. `get-permission-paths`
is called once per path expansion per recursion level, so this is multiplied several times per
query. Measured `can?` on a trivial 1-relation schema: **3.2 µs** single-threaded — cache-key
construction is a substantial share of that.

Throughput does still scale with cores (12-core box: 3.17 µs/op at 1 thread → 0.797 µs/op at 8
threads, a 4× aggregate speedup), so the `swap!` on the LRU atom is not currently a hard
serialisation point — but it is sub-linear, and every cache *hit* performs
`(swap! permission-paths-cache cache/hit cache-key)`, which rebuilds the LRU map on a single shared
atom. Worth revisiting if the cache-key work is reduced and this becomes the dominant term.

**b. `traversal-permission?` is recomputed on every lookup.** [indexed.clj:554-559](../../src/eacl/datomic/impl/indexed.clj#L554-L559),
called at [indexed.clj:1732](../../src/eacl/datomic/impl/indexed.clj#L1732) and
[:1746](../../src/eacl/datomic/impl/indexed.clj#L1746). It walks the whole permission dependency
graph via `permission-query-dependencies` → `get-permission-paths` on every single
`lookup-resources` / `lookup-subjects` / `count-*` call. Measured **7.8 µs** on a two-node graph;
it grows with schema size and is a pure function of the schema. It should share the same
schema-scoped cache as the paths themselves.

**c. Per-datom vector allocation in the scan loop.** `subject->resources` /`resource->subjects`
([indexed.clj:181-191](../../src/eacl/datomic/impl/indexed.clj#L181-L191),
[:206-216](../../src/eacl/datomic/impl/indexed.clj#L206-L216)) evaluate
`(= prefix (subvec (vec v) 0 3))` for **every datom scanned** — two allocations plus a vector
compare per datom, in the innermost loop of the whole engine. Comparing the three elements
positionally (`(and (= (nth v 0) …) (= (nth v 1) …) (= (nth v 2) …))`) is allocation-free and
identical in behaviour.

**d. `read-relationships` scans every relation definition per call.**
[impl.clj:172-190](../../src/eacl/datomic/impl.clj#L172-L190) runs an unbounded `d/q` over all
`:eacl.relation/relation-name` datoms and filters in Clojure, when
`:eacl.relation/resource-type+relation-name+subject-type` exists precisely to make this an index
seek. Schemas are small, so this is bounded — but it is per-call overhead on a read API.

**e. Duplicate path traversal after self-permission expansion.** `frontier-permission-paths`
([indexed.clj:439-456](../../src/eacl/datomic/impl/indexed.clj#L439-L456)) can emit the same path
twice — e.g. `permission view = owner + admin` where `permission admin = owner` expands to
`[{:relation owner} {:relation owner}]`. Results are correct (the merge dedupes), but the index is
scanned twice and both entries collapse onto one `path-frontier-key`. `(distinct …)` on the
expansion, keyed by `path-frontier-identity`, removes the duplicate work.

---

## 13. Test-coverage gaps worth closing

Every bug above survived a green 73-test / 1297-assertion suite. The gaps that let them through:

| Gap | Suggested test |
|---|---|
| No test ever evaluates a permission against a `d/with` or otherwise non-`(d/db conn)` database | §1 repro verbatim, asserting the live db is unaffected |
| No test uses programmatic schema **and** mutates it mid-test on one connection | §2 repro verbatim |
| Nothing deletes a permissioned entity | §3 repros A and B; assert `can?` ⇔ `lookup-resources` ⇔ `lookup-subjects` agreement after `retractEntity` |
| `differential-test` covers recursive **forward** only | add `check-reverse-invariants!` to `differential-recursive-test` — I verified 48/48 exact, so this should land green and stay green |
| No test walks a recursive permission deep enough to hit `:max-derived-grants` | a `binding`-scoped small-limit test asserting the intended error contract, whatever it becomes |
| Anchor-filter tests pass only *absent* keys | add `{:subject/id nil}`, `{:resource/type nil}` |
| No test asserts the `has-next-page? ⇒ end-cursor` invariant | property-check it across every page of every existing pagination test |

---

## 14. Suggested order of work

1. **§1 + §2 together** — one fix (schema-content fingerprint) closes both, and they are the only
   findings that make EACL grant access it should not. Ship the `evict-permission-paths-cache!`
   guidance immediately as a stopgap.
2. **§3** — `delete-object!` plus README warning plus an orphan finder. Production databases
   almost certainly contain orphans already, so the repair tool matters as much as the fix.
3. **§5, §7, §8, §11** — small, self-contained, no API change.
4. **§4 and §6** — these need a design decision (limits as config vs. resumable recursive
   frontiers, and one uniform `:last` contract). Worth their own plan document.
5. **§9, §10, §12** — cleanup, safe to batch.

---

*Verification environment: fresh nREPL on port 7788 (`clojure -M:dev:nrepl`), Datomic Peer
1.0.7622, in-memory databases, Clojure 1.11.4, 12-core darwin. Every code block marked "verified"
was executed against that REPL; findings 7, 11 and 12d/12e are code reads and are marked as such.*

---

## Remediation (2026-07-29, branch `fix/2026-07-29-cache-scope-dangling-tuples`)

| # | Status | Change |
|---|---|---|
| 1, 2 | **Fixed** | The permission-path cache scope is now an exact fingerprint of the EACL definition datoms in the queried `db` value (`attr-fingerprint` over the two `:db.unique/identity` composite tuples: count + entity hash + max tx — Datomic re-asserts a composite tuple with a fresh tx whenever any component changes), memoised on the `db` value itself. `d/with`, `d/filter`, unstamped and programmatically-edited databases each resolve against their own schema and cannot share a slot. Issue #74's requirement is preserved and now pinned by a test: an unrelated `d/transact` yields the same fingerprint, so no path is recomputed. `:eacl/schema-version` is retained but demoted to informational. |
| 3 | **Fixed** | New `eacl/delete-object!` (protocol) / `impl/tx-delete-object` retracts both halves of every relationship touching an object, finding them from the object's own datoms *and* by exact `:avet` lookup per schema Relation — so it works before or after the entity is retracted. `impl/orphaned-relationship-halves` + `impl/tx-retract-orphaned-relationships` repair existing damage. The public `can?` no longer answers from a retracted entity's surviving half (`existing-eid` datom probe, closing the eid-as-external-id privilege escalation). README gained a "Deleting a permissioned entity" section and a Limitations entry. |
| 4 | **Partly fixed** | `count-resources`/`count-subjects` on recursive permissions now do ONE traversal instead of replaying the prefix per `max-page-size` page (was `O(N²)` and tripped the limit long before a large grant set could be counted). Limits are configurable via `make-client`'s `:recursive-traversal-limits` (merged with defaults so a partial override cannot disable the rest), and the limit-exceeded message explains the deep-page cause and the fix. **The `O(N²)` page replay itself remains** — that needs the materialised-grants engine in `docs/plans/2026-05-17-recursive-pagination-effective-grants-plan.md`. Now documented in Limitations. |
| 5 | **Fixed** | Anchor check uses `some?` on the value, not `contains?` on the key; the error names the nil-valued keys. |
| 6 | **Fixed** | Bare `:last` works for recursive permissions (`collect-trailing-items`), matching the acyclic contract. |
| 7 | **Fixed** | `count-*` reduce instead of `doall`; the dead `:size` in the constructed page-req is gone. |
| 8 | **Fixed** | `page-response` (and `relationship-page`) clamp both `has-*-page?` flags to false on an empty page, so `has-next-page?` always implies a usable `end-cursor`. A present-but-nil `:after`/`:before` now throws `:eacl.pagination/invalid-cursor` instead of silently restarting at page 1. |
| 9 | **Fixed** | `impl/can?` map arity destructures and forwards to the 4-arity. |
| 10 | **Fixed** | `:eacl/relationship-conflict`, `:eacl/unsupported-operation`, `:eacl/unauthorized` via `ex-info`. |
| 11 | **Fixed** | `relationship-exists?` requires both halves; `:delete` retracts unconditionally (Datomic ignores absent retractions), so a half-pair is repairable by `:touch` and removable by `:delete`. |
| 12 | **Fixed** (a, b, c, e) / **deferred** (d) | (a) `(.id ^datomic.Database db)` is hinted and now runs once per `db` value, not per call; the scope memo is a plain map (LRU hit bookkeeping cost ~6x a map lookup on the hottest path). (b) `traversal-permission?` is memoised under the same schema scope. (c) Element-wise tuple-prefix comparison — no per-datom vector allocation. (e) `frontier-permission-paths` dedupes by `path-frontier-identity`. (d) `find-relations`' relation-table scan is unchanged: bounded by schema size, and touching the `read-relationships` scan planner was more risk than the gain. |

**Not done, deliberately.** Read-time filtering of ghost results from `lookup-resources`/`lookup-subjects`
was considered and rejected for this change: it costs one datom probe per candidate row on every
page, and doing it inside the recursive engine would shift emission ordinals and invalidate
in-flight cursors. `delete-object!` plus the orphan repair pass fixes the data instead. The
residual is that a database with existing orphans still lists them until repaired.

### Verification

Full suite on a **fresh** nREPL: **90 tests, 1636 assertions, 0 failures, 0 errors**
(baseline before this change: 73 / 1297). New coverage: `object-deletion-test` (7 tests),
`api-contract-test` (9), `schema-basis-test` gained the speculative-db, unstamped-database and
scope-memo-cost contracts, and `differential-test` gained recursive **reverse** invariants —
a path that previously had none (860 assertions in that namespace, up from ~360).

**Benchmarks.** Protocol: fresh JVM per version, `^:benchmark` suite only, two consecutive runs,
second run reported (the first is JIT-dominated — first-run first-page medians ranged 2.3–2.7 ms
on *both* versions, so any single-run comparison is meaningless here).

`multipath-pagination-benchmark`, 15 000 servers over a 4-arrow-path permission:

| | before | after |
|---|---|---|
| First page (`:first 50`) median / p95 | 1.36 / 2.26 ms | 1.34 / 1.95 ms |
| Forward pagination, early / late / max page | 1.56 / 1.37 / 1.75 ms | 1.40 / 1.30 / 1.64 ms |
| Reverse pagination, early / late / max page | 1.62 / 1.51 / 1.75 ms | 1.61 / 1.42 / 1.84 ms |
| Deep-page medians, first / middle / last | 1.32 / 0.86 / 0.44 ms | 1.24 / 0.86 / 0.41 ms |
| Traversal calls by depth (frontier effectiveness) | 282 / 184 / 79 | 282 / 184 / 79 |

**Flat, within run-to-run noise, no regression.** The identical traversal-call counts confirm the
frontier algorithm is untouched. Paging is dominated by index traversal (~282 scan calls for a
50-row page), so cache-key overhead was never a large share of it.

`can?` is where the cache change actually lands. Measured with identical version-agnostic code on
a fresh JVM per version, median of 9 batches after warmup:

| schema | | before | after | |
|---|---|---|---|---|
| 51 definitions | **public `eacl/can?`** | 2.90 µs | **2.24 µs** | **−23%** |
| 601 definitions | **public `eacl/can?`** | 3.12 µs | **2.52 µs** | **−19%** |
| 51 definitions | raw `impl.indexed/can?`, warm | 2.29 µs | **1.37 µs** | −40% |
| 601 definitions | raw `impl.indexed/can?`, warm | 2.69 µs | **1.50 µs** | −44% |
| 51 definitions | raw, a new `db` value every call | 2.34 µs | 3.42 µs | +46% |
| 601 definitions | raw, a new `db` value every call | 2.54 µs | 15.67 µs | +518% |

The public path is what applications call, and it is **19–23% faster** — even though finding 3
added two datom existence probes to it. The cache work more than paid for them.

The last two rows are the whole cost of exact scoping, stated plainly: a `db` value the memo has
not seen pays one definition-index scan. "A new `db` value every call" means the database advances
between *every single check*; the scan is bounded by schema size, never by data size, and 601
definitions (60 definition blocks) is a deliberately extreme schema. The constant was already
reduced ~4x during this work — direct index iteration instead of a seq walk (2x), and a plain-map
memo instead of LRU hit bookkeeping (2x on the hit path).

Pinned going forward by `permission-check-benchmark` (warm and cold `can?` medians, with
thresholds) and by `schema-scope-is-computed-once-per-db-value-test`, which deterministically
asserts the scan happens once per `db` value and not per call.
