# Design: fix-audit-root-causes

## Context

The 2026-07-06 audit ([docs/reports/2026-07-06-eacl-full-source-audit.md](../../../docs/reports/2026-07-06-eacl-full-source-audit.md)) verified 15 bugs with REPL repros. The traversal engines are sound (differential tests passed); every defect is root-caused in one of five edge layers:

1. **Schema-write pipeline trust** — `parse-schema` returns instaparse failure objects nothing checks; `transform-schema` converts them to `nil`; `->eacl-schema` converts `nil` to an empty schema; `write-schema!` diffs old-vs-empty and retracts everything. Duplicate definitions last-win into the same destructive delta path. Arrow validation consults only the last-declared subject type while resolution consults the first.
2. **One bounded index scan** — `relation-datoms` ranges subject types between literal `:a` and `:z`, hiding relations whose subject-type keyword sorts outside that window (uppercase, `z*`, all namespaced keywords).
3. **Cache keying** — permission-path/plan caches key on `(.id db)` (the immutable database UUID). Eviction is a side effect of same-JVM `write-schema!` only; other peers, programmatic schema writes, and `as-of` views read stale paths.
4. **Nil-tolerant ID resolution** — unresolvable external IDs become `nil` (silently matching everything in `read-relationships`, or landing in tx-data as raw Datomic errors) or become tempids (minting ghost entities). `make-client` silently drops unknown option keys, including the README-documented one.
5. **Nil-on-failure cursor decoding** — expired/undecodable tokens decode to `nil`, indistinguishable from "first page".

Constraints: EACL is in production at CloudAfrica and in the author's side projects; Datomic peer 1.0.6733; no new runtime dependencies; wire/storage formats (v7 tuples, `eacl1_` token prefix, schema entities) must remain readable by existing deployments; repo convention is nREPL-driven testing.

## Goals / Non-Goals

**Goals:**

- Convert every verified silent-failure into either correct behavior or a loud, typed error.
- Fix root causes: a strict parse→validate→diff pipeline; prefix scans instead of keyword-bounded ranges; a schema-basis-aware cache key; a strict ID-resolution boundary with one coherent unknown-ID contract; fail-loud cursor decoding.
- Keep all existing *valid* configurations, schemas, cursors-in-flight (raw maps), and data working unchanged.
- Leave the codebase honest: dead v6 namespaces deleted, README matching reality, all tests actually running in CI.

**Non-Goals:**

- The v3 recursive-cursor growth/eid-leak redesign (audit §6). Architectural; follow-up change. This change only adds a cursor↔schema fingerprint guard and documentation.
- `:create` uniqueness under concurrency (audit §16.5) — requires a transaction function; deferred, documented as best-effort.
- New SpiceDB features (subject relations, intersection/exclusion, multi-level arrows, caveats).
- Performance work (`arrow-via-intermediates` fan-out, reverse-lookup memoization) — tracked in the audit, not touched here beyond not regressing.

## Decisions

### D1. Parse pipeline fails loudly at one choke point

`->eacl-schema` becomes the choke point: it throws `ex-info` with `{:type :eacl.schema/parse-error :failure (insta/get-failure tree)}` when handed a failure object, and `transform-schema` throws (never returns `nil`) on unexpected shapes. `parse-schema` keeps returning instaparse results unchanged (REPL ergonomics, existing tests).

*Alternative considered:* throwing inside `parse-schema`. Rejected: failure objects are useful interactively, and `->eacl-schema` is the single path `write-schema!` uses — one guard covers all writers.

### D2. Comments become whitespace in the grammar

Support `//` line and `/* */` block comments by supplying a custom whitespace parser to instaparse's `:auto-whitespace` (the documented whitespace-or-comments idiom). Comments are then legal anywhere whitespace is, matching SpiceDB.

*Alternative considered:* regex-stripping comments before parsing. Rejected: fragile against future string/caveat syntax and reports wrong error positions.

### D3. Duplicates are rejected during extraction

`extract-definitions` and `extract-relations` currently pour into maps (last-wins). Both are changed to detect collisions and throw `ex-info` naming the duplicate (`{:type :eacl.schema/duplicate-definition}` / `::duplicate-relation`). Additionally, a permission sharing a name with a relation on the same type is rejected (SpiceDB does the same; today `resolve-component` silently prefers the relation).

### D4. Full-retraction guard in `write-schema!`

If the *new* schema parses to zero definitions while the stored schema is non-empty, `write-schema!` throws `{:type :eacl.schema/empty-schema-guard}`. Escape hatch: `(schema/write-schema! conn schema-string {:allow-empty-schema? true})` — a new optional opts arity on the schema-namespace fn only; the `IAuthorization` protocol method signature is unchanged and always guarded. With D1–D3 this guard should never fire from well-formed input; it is belt-and-braces against future parser gaps.

### D5. Arrow validation checks all subject types, strictly

`validate-schema-references` builds `relation-subject-types` as `{[res-type rel-name] #{subject-types}}` (a set, not last-wins). An arrow `rel->target` is valid only if **every** subject type of `rel` has `target`, and `target` resolves to the same kind (relation vs permission) on all of them; errors enumerate the offending types.

*Why strict (SpiceDB semantics) over permissive (match the runtime's union-over-types-that-have-it):* the project's stated goal is a clean migration path to SpiceDB — schemas EACL accepts must be accepted by SpiceDB. The runtime's silent no-op for missing types was never a documented feature. Risk of rejecting existing production schemas is noted in Risks; the error message tells the author exactly which type/target to add.

`resolve-component`'s first-type classification becomes safe under D5 (all types agree on kind), but is still rewritten to consult the full set so parser and validator can never diverge again.

### D6. Parenthesized unions flatten; parenthesized arrow bases are rejected

Since EACL is union-only, `(a + b)` as a union operand is semantically just `a + b`: `flatten-expression` recurses into `paren-expr → permission-expr` and splices the components. A `paren-expr` appearing as an arrow base or target (`(a + b)->c`) is rejected with a clear validation issue (SpiceDB arrows take relation bases; today this crashes with a bare `AssertionError`).

### D7. `relation-datoms` becomes a prefix scan (fix verified in the audit)

```clojure
(let [attr-eid (d/entid db :eacl.relation/resource-type+relation-name+subject-type)]
  (->> (d/seek-datoms db :avet :eacl.relation/resource-type+relation-name+subject-type
                      [resource-type relation-name])
       (take-while (fn [d] (and (= attr-eid (:a d))
                                (= resource-type (nth (:v d) 0))
                                (= relation-name (nth (:v d) 1)))))))
```

The `(= attr-eid (:a d))` guard is mandatory — `seek-datoms` iterates past the attribute's segment (verified during the audit; without it the scan crashes on the next attribute's non-tuple values). This is the same pattern `subject->resources` already uses. Audited the other bounded ranges (`scan-global-relationships`, `count-relationships-using-relation`): both bound only the trailing *eid* component under an exact keyword prefix — correct as-is.

### D8. Cache keys carry a schema-history digest; invalidation is derived, not signaled

> **SUPERSEDED (2026-07-14, issue #74).** The derived digest was reverted: computing it requires a schema-history scan per fresh db basis, i.e. after **every** `d/transact` — unrelated relationship writes paid for schema-change detection, which does not scale under write load. The shipped design is a signaled stamp: `write-schema!` asserts a fresh `:eacl/schema-version` **squuid** on the schema singleton in the same transaction as any definition change, and both caches key on `[(.id db) version …]` (one AVET lookup, no scans, untouched by unrelated transactions). Of the six loopholes below that motivated the digest: the squuid (not a counter) closes the counter-elision race; nothing memoizes eids, so there is no anchor-eid hazard; no `t` arithmetic remains (as-of views read their era's version datom natively); positive view classification and failure-degrades-to-miss are retained verbatim. The one consciously **accepted** loophole is the unenforceable-contract one: programmatic relation/permission datom edits (raw `d/transact`, `d/with`, excision) no longer invalidate anything and may be served stale paths until the next `write-schema!` or manual `evict-permission-paths-cache!` — per issue #74, users must not manage EACL schema outside the API. Cursor fingerprints keep the same two-part `{:s :p}` shape with `:s` = the version string. The digest text below is retained as the record of why the derived approach was tried.

Both caches key on `[(.id db) (schema-basis db) …]` where `(schema-basis db)` is a 128-bit digest (SHA-256 truncated to 128 bits — collision resistance, not security; SHA-256 rather than MD5 so FIPS-mode JVMs don't silently lose caching to the failure fallback) folded, in index order, over the **history datoms** of the two unique composite tuple attributes — `:eacl.relation/resource-type+relation-name+subject-type` and `:eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name` — each datom contributing `[e v t added?]`, filtered to `t ≤ (or (d/as-of-t db) (d/basis-t db))`.

An earlier draft of this decision used an anchor entity (`[:eacl/id "schema-string"]` max-`:tx`) plus a `:eacl/schema-version` counter and touch helpers for programmatic writers. An adversarial review found six loopholes in that shape; each clause of the digest design closes one:

- **Derived, not signaled** — any transaction touching any component of any relation/permission entity rewrites that entity's composite tuple *in the same transaction*, and `retractEntity` retracts it (both REPL-verified). The tuple histories are therefore a complete record of path-relevant schema mutations: programmatic writers are covered automatically, and there is no touch contract to forget (the unenforceable-contract loophole).
- **History, not current datoms** — a pure retraction leaves no current datom but does leave history; max-over-current-datoms designs miss revocations entirely.
- **Content digest, not a max-`:tx`/counter** — immune to the counter-elision race (two peers asserting the same incremented value produce no datom for the second, silently skipping invalidation); immune to `d/excise` *lowering* a max and resurrecting ancient entries (excised history changes the digest, which is a correct invalidation); immune to speculative `d/with` dbs colliding with a future real transaction at the same `t` (different content ⇒ different digest; identical content ⇒ identical eids and paths, so sharing is harmless). `d/history` works on with-db db-afters and includes speculative datoms (REPL-verified).
- **No anchor entity** — nothing to retract/recreate out from under a memoized eid.
- **Explicit `as-of-t` filter** — `(d/history (d/as-of db t))` is correctly filtered (REPL-verified), but `(d/basis-t (d/as-of db t))` returns the *underlying* basis, not `t` (REPL-verified, surprising) — so the filter must use `d/as-of-t` when present. Keeping the explicit filter also proofs the code against history/as-of composition differences across Datomic versions.
- **Positive view classification; unclassifiable views are never cached** — the digest is computed only for db values positively classified as *plain* (`as-of-t` nil, `since-t` nil, `is-filtered` false, `is-history` false — `d/with` db-afters classify plain, which is safe per the previous clause) or *as-of* (`as-of-t` non-nil, others clear). Everything else — `d/filter`, `d/since`, history dbs, or anything unrecognized — gets a unique sentinel key: computed fresh from that view, never shared. `d/filter` views are excluded because predicates are arbitrary functions (possibly impure or time-dependent), so a digest of their filtered history cannot be trusted as a shared key. *Empirical correction during implementation:* an earlier audit pass concluded `d/history` on a `d/filter` db ignores the filter — that came from a vacuous all-pass predicate (both behaviors coincide for it); a hide-everything predicate shows history **respects** the filter, which is now the pinned fact. The classification rule stands on the arbitrary-predicate rationale. (`d/since` history is since-filtered — verified — but since-views are excluded anyway: they hide old schema *and* old relationships, so caching them buys nothing.)
- **Failure degrades to a miss, never a stale hit** — any exception during digest computation (exotic db types, `.id` access failing after a peer upgrade, history unsupported somewhere) likewise yields a fresh unique sentinel key → guaranteed cache miss → paths recomputed from the actual db value passed. There is no failure path — thrown or classified — that serves a stale entry.

**Memoization:** the digest is memoized in a synchronized `WeakHashMap` keyed by the db value itself — one history scan per `(d/db conn)` call rather than per permission check, and weak keys release with the db value. `WeakHashMap` uses `.equals`, and Datomic `Db` equality is value-based *and content-aware* (REPL-verified: two `d/db` calls at the same basis are equal; two `d/with` db-afters at the same `t` with different speculative content are **not** equal) — so the memo can only unify db values Datomic itself declares equal, which by the verified semantics implies identical visible history and therefore identical digests. Only positively classified views are memoized; sentinel results are never stored. If a future peer version loosened `Db.equals` to ignore speculative content, aliasing would be confined to speculative-vs-speculative views at the same `t` (a real db at that `t` compares unequal to a with-db by content) — and the pinned equality tests in task 5.3 would fail loudly first. Cost: O(all-time schema edits) per db value — tens of microseconds against hot peer segments for realistic schema churn.

**Coverage invariant (documented at the definition site):** every attribute that path/plan computation reads must be a component of one of the digested composite tuples; introducing a new path-relevant attribute outside them requires adding its history to the digest. Today the paths read exactly relation `{resource-type, relation-name, subject-type, eid}` and permission `{resource-type, permission-name, source-relation-name, target-type, target-name}` — all tuple components (relation eids change only via retract+recreate, which the tuple history records).

`evict-permission-paths-cache!` stays public as a manual override and `write-schema!` keeps calling it (immediate local effect; harmless). The `:eacl/schema-version` attribute and touch helpers from the earlier draft are **dropped** — no storage additions, no writer contract.

*Alternatives considered:*
- **Anchor entity + version counter + touch helpers** (earlier draft). Rejected for the six loopholes above — chiefly that a documented contract for programmatic writers is exactly the class of silent failure this change exists to eliminate.
- **`tx-report-queue` listener** evicting on schema-attr datoms. Rejected: `d/tx-report-queue` returns *the* queue for a connection — EACL consuming it would steal reports from applications that already use it (CloudAfrica does; see `test/eacl/report_queue.clj`), and it adds thread lifecycle to `make-client`.
- **Key by `basis-t`**: correct but evicts on every relationship write, making the cache useless under write load; also aliases speculative `d/with` dbs against future real bases.
- **Status quo + documentation**: leaves the verified stale-grant hazard in multi-peer production.

*Verified against Datomic peer 1.0.6733 (REPL, 2026-07-06):* component-edit rewrites the composite tuple same-tx; `retractEntity` retracts the tuple into history; `(d/history (d/as-of db t))` filters to ≤ t; `(d/basis-t (d/as-of db t))` = underlying basis (hence the `as-of-t` filter); `d/history` works on `d/with` db-afters; `.id` returns the same UUID on plain/as-of/with dbs; writes to unallocated numeric eids are rejected by the transactor; `d/is-filtered` is true only for `d/filter` dbs (false for plain/as-of/since/with); `as-of-t`/`since-t`/`is-history` positively identify their views (with-dbs read as plain); `d/history` on a `d/filter` db respects the filter (pinned with a hide-everything predicate — an earlier all-pass-predicate check was vacuous); `d/history` on a `d/since` db is since-filtered. Task 5.3 pins each of these as a regression test so a peer upgrade that changes any of them fails loudly.

### D9. One unknown-ID contract: reads empty, writes throw

Aligned with SpiceDB (object IDs are opaque there; unknown IDs simply match nothing) and with `can?`'s existing `false`:

| Operation | Unknown subject/resource ID today | New behavior |
|---|---|---|
| `can?` | `false` | `false` (unchanged) |
| `read-relationships` | **all relationships** (leak) | `[]` |
| `lookup-resources` / `count-resources` | `AssertionError` | empty page / `{:count 0}` |
| `lookup-subjects` | `AssertionError` | empty page |
| `write-relationships!` (create/touch/delete) | raw Datomic `not-an-entity` / silent no-op | `ex-info {:type :eacl/unknown-object, :object {:type … :id …}}` |

Implementation: `spice-object->internal` and the `read-relationships` filter resolution return a sentinel distinguishing "no filter supplied" from "supplied but unresolvable"; unresolvable reads short-circuit to empty, unresolvable writes throw before tx-data is built. Writes throw (rather than SpiceDB's accept-any-string) because EACL relationships are eid-based — a write that cannot resolve is unsatisfiable, and today's silent tempid/no-op variants are the audit's §11/§12.

The write-side check must verify entity **existence** (`(seq (d/datoms db :eavt eid))`), not mere `d/entid` resolution: `d/entid` passes numeric inputs through unchanged, so a plausible-but-unallocated eid "resolves" — the transactor then rejects it with a raw `:db.error/invalid-entity-id` (REPL-verified). The existence check turns that into the same typed `:eacl/unknown-object` error. Read paths need no extra check — seeks on nonexistent eids naturally yield empty results, consistent with the contract.

### D10. `impl/tx-relationship` tempids become opt-in

`(tx-relationship db rel)` throws `:eacl/unknown-object` for unresolvable string IDs; `(tx-relationship db rel {:allow-tempids? true})` restores tempid pass-through for same-transaction entity+relationship creation. Fixtures and the two tests that exploit tempids pass the flag. `resolve-relationship`/`object-id->eid-or-tempid` thread the option down.

*Alternative considered:* an explicit `(->tempid "x")` wrapper type. Rejected for now: heavier API surface; the boolean opt covers the two real call sites (fixtures, quickstart docs) and keeps `tx-relationship` data-in/data-out.

### D11. `make-client` validates options; canonical ID key matches the README

- Accept `:entid->object-id` (`(fn [db eid] …)`) as the canonical key — it is what the README has documented all along.
- Keep `:entity->object-id` (`(fn [ent] …)`) working as a deprecated alias (production code uses it); supplying **both** throws.
- Throw `ex-info {:type :eacl/invalid-config, :unknown-keys […], :known-keys […]}` on any unrecognized key. This single check would have caught the original drift.

### D12. Cursor tokens fail loudly and gain a schema fingerprint

- `token->cursor` contract: `nil` → `nil` (no cursor); raw map → pass-through (back-compat); a non-nil string that fails to decode, fails the `eacl1_` prefix, or is expired → `ex-info {:type :eacl/invalid-cursor, :reason :expired|:undecodable}`.
- TTL: default **no expiry**. `make-client` gains `:cursor-ttl-seconds`; when set, `cursor->token` embeds `:t` and decoding enforces it (the existing `ttl-seconds` plumbing is finally connected; today it is dead code and 300 s is hardcoded). Tokens without `:t` never expire. Rationale: the TTL protects nothing security-relevant (tokens are unauthenticated data), while the 5-minute default silently corrupts every batch job slower than 300 s/page.
- Fingerprint: cursors (v2 and v3 state) carry `:f {:s <schema-digest> :p <paths-digest>}` — the D8 schema-history digest at mint time plus a 128-bit digest (SHA-256 truncated, as in D8) of `pr-str` of the query's resolved paths (v2) or plan (v3). On resume: equal `:s` ⇒ proceed (identical schema history ⇒ identical paths — exact, no hashing involved in the common case); differing `:s` ⇒ recompute this query's paths and compare `:p` — equal ⇒ proceed (the schema change didn't touch this query), differing ⇒ throw `{:type :eacl/stale-cursor}`. Unrelated schema changes therefore do *not* invalidate in-flight cursors, and reordered `:p` path indices / stale v3 `:stack`s are caught. Residual false-negative requires a 128-bit collision *and* a mid-pagination schema change to the same permission — negligible. Cursors lacking `:f` (minted before this change) are accepted for one release with a `log/warn`.

### D13. Protocol completeness and typed errors in the client layer

- Implement `write-relationship!` (both arities) and `delete-relationship!` (both arities) as delegations to `spiceomic-write-relationships!`.
- Replace client-layer `assert`s (subject existence in `lookup-resources`/`count-resources`, the `{:pre …}` in `lookup-subjects`, the consistency assert) with the D9 behaviors and `ex-info {:type :eacl/unsupported-consistency}` respectively. Asserts are compile-time-removable and untyped; the audit found three different failure shapes for the same class of input.
- Delete the vacuous `(= (:type subject-ent) (:type subject))` assert in `count-resources`.
- `expand-permission-tree` throws `ex-info {:type :eacl/not-implemented}` instead of bare `Exception`.

### D14. Housekeeping is part of the change, not spec'd

Deletions (`eacl.datomic.rules`, `eacl.datomic.rules.optimized`, `rules/optimized_old.clj`, `eacl.datomic.impl.datalog`, `base/Relationship`, root-level commented scripts), README corrections (ID-configuration keys per D11; quickstart relationship example rewritten around `tx-relationship`/`create-relationships!`), the `eacl.datomic.parser_test` → `eacl.datomic.parser-test` ns rename, and the two test typos ride along as tasks without spec requirements. The commented-out namespaces reference v6 attrs absent from the installed schema — they cannot work and actively mislead.

### D15. Verification: differential property test + per-fix regression tests

Every fix lands with the audit's repro as a regression test. Additionally, a seeded, hand-rolled randomized differential test (no new deps — `test.check` not introduced) generates small graphs and asserts the audit's invariant: `lookup-resources` set == `can?`-derived ground truth == paginated union (several page sizes) == `count-resources`, and the reverse for `lookup-subjects`. All tests runnable via nREPL per repo convention.

## Risks / Trade-offs

- [Strict arrow validation (D5) may reject existing production schemas that relied on silent per-type no-ops] → The error lists exactly which subject types lack the target; migration is additive (define the missing permission). Ship note in README breaking-changes section. If a real schema cannot be made SpiceDB-valid, revisit with an explicit permissive flag rather than silent behavior.
- [Reads-return-empty (D9) can mask caller typos that previously blew up with `AssertionError`] → Typos on *writes* still throw; `can?` was already `false`-on-unknown, so the contract is now uniform and documented. Apps that want existence checks should check existence, not rely on authz-layer asserts.
- [Digest scan cost grows with all-time schema-edit history] → Identity-memoized per db value (one scan per `(d/db conn)` call); O(schema edits ever) is microseconds against hot segments for realistic churn. Pathological continuous schema churn is an ops smell in its own right — documented; and `d/excise` of ancient schema history, should anyone ever need it, is handled *correctly* by the digest (it invalidates).
- [128-bit digest collision could alias two schema states] → Non-adversarial input (your own schema), pairwise probability ~2⁻⁶⁴ — the same class as hardware bit-flip rates. Accepted and documented; no cheaper mechanism avoids it without reintroducing a writer contract.
- [`(.id db)` is not documented public API] → REPL-verified on plain/as-of/with dbs against peer 1.0.6733; access is wrapped so any failure falls back to a unique sentinel key (cache miss — correct, merely slower), and a pinned CI test makes a peer upgrade that changes it fail loudly. Note the digest includes entity ids, so even a cross-database `.id` collision could only share entries whose paths are identical.
- [A future path-relevant attribute added outside the digested tuples would escape invalidation] → Coverage invariant documented at the definition site; task 5.4's mutation-class regression tests (add/edit/retract × relation/permission each must change the digest) make the invariant executable.
- [No-expiry default for cursor TTL (D12) means tokens circulate indefinitely] → Tokens are basis-relative pagination state, not credentials; staleness is now caught by the `:f` fingerprint for schema changes, and data drift between pages was already the documented `(d/db conn)` caveat. Deployments wanting expiry set `:cursor-ttl-seconds`.
- [Deleting dead namespaces breaks any out-of-tree requires of them] → They reference storage attrs absent from the v7 schema; any such require was already broken at runtime. Noted in breaking changes.
- [`:f` fingerprint digests contain values derived from relation eids] → Not stable across DB rebuilds — acceptable: cursors are already documented as basis-bound; a false mismatch just forces a clean restart with a loud `:eacl/stale-cursor` error instead of silent corruption.

## Migration Plan

1. Land in dependency order (mirrors audit §19): D1–D4 (schema-write safety) → D7 (relation-datoms) → D9/D10/D11 (ID boundary) → D12 (cursors) → D8 (cache basis) → D5/D6 → D13 → D14/D15 throughout.
2. Storage: **no changes at all** — the schema-history digest is derived entirely from existing datoms; no new attributes, no migration transaction. No relationship/tuple/token format changes; cursors minted by old code remain decodable (`:f` absent → warn-and-accept for one release).
3. Consumers upgrade checklist (goes into README breaking-changes section): unknown-ID read behavior, `make-client` opts validation, cursor errors instead of silent restarts, `tx-relationship` tempid opt-in, strict arrow validation, dead-namespace deletion.
4. Rollback: `git revert` — no data migrations to unwind; nothing was written to storage that old code could even observe.

## Open Questions

- None blocking. Two deliberate deferrals recorded: (a) v3 recursive-cursor redesign (needs its own change; interacts with SpiceDB-compat and possible server-side cursor state), (b) transactional `:create` uniqueness (needs a transaction function; today documented as best-effort).
