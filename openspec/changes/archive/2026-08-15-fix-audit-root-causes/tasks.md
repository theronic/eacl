# Tasks: fix-audit-root-causes

Order follows the design's migration plan (risk-first). Every group ends with a verification gate; run tests via nREPL per repo convention (`clj-nrepl-eval`), never via cold `clojure -M:test`. Audit references (§N) point at [docs/reports/2026-07-06-eacl-full-source-audit.md](../../../docs/reports/2026-07-06-eacl-full-source-audit.md), which contains a REPL repro for every item — each repro becomes a regression test.

## 1. Schema-write safety (parser + write-schema!) — D1–D6

- [x] 1.1 Make `->eacl-schema` throw `ex-info {:type :eacl.schema/parse-error :failure (insta/get-failure tree)}` on instaparse failure input, and make `transform-schema` throw on non-`:schema` input instead of returning `nil` (§1)
- [x] 1.2 Add `//` and `/* */` comment support via a custom whitespace parser passed to instaparse `:auto-whitespace` (D2); keep `parse-schema`'s return contract unchanged
- [x] 1.3 Reject duplicate `definition` blocks, duplicate relation declarations within a definition, and permission/relation name collisions during extraction with typed errors naming the duplicate (§9, D3); verify `relation x: a | b` still expands to two Relations
- [x] 1.4 Add the full-retraction guard to `write-schema!` (`:eacl.schema/empty-schema-guard` when new schema has zero definitions and stored schema is non-empty) plus the `{:allow-empty-schema? true}` opts arity on `schema/write-schema!` only (D4)
- [x] 1.5 Rewrite `validate-schema-references` to validate arrows against the full **set** of subject types per relation (reject if any type lacks the target or target kinds differ across types, with types named in the error), and rewrite `resolve-component`/`collect-schema-info` to consult the same set so parser and validator cannot diverge (§10, D5)
- [x] 1.6 Flatten parenthesized union operands in `flatten-expression`; reject parenthesized arrow bases/targets with a typed validation issue in `collect-parse-tree-issues` (§8, D6)
- [x] 1.7 Regression tests for 1.1–1.6: malformed schema throws and leaves stored schema untouched (§1 repro); commented schema round-trips; duplicate-definition schema throws; both subject-type orders of the §10 repro are rejected; `(owner + editor)` paren schema accepted and behaves as union; empty-schema write throws without opt-in
- [x] 1.8 Gate: `eacl.datomic.schema-test`, parser tests, and `eacl.spice-test` green via nREPL

## 2. Relation lookup prefix scan — D7

- [x] 2.1 Replace `relation-datoms`'s `:a`–`:z` `d/index-range` with the verified `d/seek-datoms :avet` prefix scan, including the mandatory attr-eid guard in `take-while` (§2, D7 — exact code in design)
- [x] 2.2 Regression tests: end-to-end `can?`/`lookup-resources`/`lookup-subjects` for subject types `:zebra`, `:Admin`, `:my.app/user` (§2 repro); exact-prefix isolation test (`(:zone :owner)` does not match `(:zone :ownerx)` datoms or subsequent attributes)
- [x] 2.3 Gate: full `eacl.datomic.impl.indexed-test` green via nREPL

## 3. Strict object-ID resolution — D9–D11

- [x] 3.1 Introduce a supplied-but-unresolvable sentinel in `spice-object->internal` and the `spiceomic-read-relationships` filter resolution; short-circuit reads to empty results (`read-relationships` → `[]`, `lookup-resources`/`lookup-subjects` → empty page, `count-resources` → `{:count 0}`) and delete the corresponding client-layer asserts and the `lookup-subjects` `{:pre …}` (§4, D9, D13)
- [x] 3.2 Validate both endpoints in `spice-relationship->internal` before building tx-data; throw `ex-info {:type :eacl/unknown-object :object {:type … :id …}}` for create/touch/delete; the check verifies entity existence via datom presence (`(seq (d/datoms db :eavt eid))`), not mere `d/entid` resolution, so unallocated numeric eids are caught too (§11, D9)
- [x] 3.3 Make `impl/tx-relationship` strict by default with an `{:allow-tempids? true}` opts arity threaded through `resolve-relationship`/`object-id->eid-or-tempid`; update `fixtures/relationship-fixtures`, `txes-additional-account3+server`, and tempid-dependent tests to pass the flag (§12, D10)
- [x] 3.4 `make-client` option validation: accept canonical `:entid->object-id`, keep `:entity->object-id` as deprecated alias, throw `:eacl/invalid-config` on both-supplied or any unknown key (§5, D11)
- [x] 3.5 Regression tests: §4 leak repro returns `[]`; §11 ghost-write repro throws `:eacl/unknown-object`; §12 typo repro throws and mints no entity while the `:allow-tempids?` fixtures path still works; §5 repro returns `EXT-`-prefixed IDs; misspelled opt key throws
- [x] 3.6 Update `eacl.datomic.config-test` (missing-subject lookup now returns an empty page, not `thrown?`) and any indexed tests relying on assert behavior
- [x] 3.7 Gate: full suite green via nREPL

## 4. Cursor token handling — D12

- [x] 4.1 Rework `token->cursor`: `nil` → `nil`, raw map pass-through, any other failure (bad prefix, corrupt base64/EDN, expired) → `ex-info {:type :eacl/invalid-cursor :reason :undecodable|:expired}`; treat missing `:t` as no-expiry (§7)
- [x] 4.2 Add `:cursor-ttl-seconds` to `make-client` opts (default nil = no expiry) and thread it into every `cursor->token` call; delete the hardcoded 300 s default
- [x] 4.3 Add the two-part fingerprint `:f {:s <schema-digest> :p <paths-digest>}` in `build-v2-cursor` and the v3 recursive state (D12); on resume: equal `:s` proceeds; differing `:s` recomputes this query's paths and compares `:p` — equal proceeds, differing throws `ex-info {:type :eacl/stale-cursor}`; missing `:f` is accepted with a `log/warn`; document v3 cursor growth (§6) in `lookup-resources` docstring and README as a known limitation with the follow-up change noted (depends on 5.1's `schema-basis`; implement after group 5 or stub `:s` until then)
- [x] 4.4 Regression tests: garbage token throws; expired token throws when TTL configured; default-config token decodes with an old timestamp (§7 repro inverted); a schema change altering the queried permission's paths throws `:eacl/stale-cursor` on resume; an unrelated schema change resumes normally; unchanged schema resumes with no duplicates/gaps; raw-map cursors still work (back-compat test exists in `spice-test`)
- [x] 4.5 Gate: `eacl.spice-test` and `eacl.datomic.impl.indexed-test` green via nREPL

## 5. Schema-digest cache keys — D8

- [x] 5.1 Implement `(schema-basis db)` in `impl.indexed`: positively classify the db view first — plain (`as-of-t` nil, `since-t` nil, `is-filtered` false, `is-history` false) or as-of (`as-of-t` non-nil, others clear) compute the digest; anything else (filter/since/history/unrecognized) returns a unique sentinel. Digest = 128 bits (SHA-256 truncated — FIPS-safe) folded in index order over the history datoms (`[e v t added?]`) of `:eacl.relation/resource-type+relation-name+subject-type` and `:eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name`, filtered to `t ≤ (or (d/as-of-t db) (d/basis-t db))`; wrap the whole computation (including `.id` access) so any exception also returns a unique sentinel (forced cache miss, never staleness)
- [x] 5.2 Memoize the digest in a synchronized `WeakHashMap` keyed by the db value (Datomic `Db` equality is value- and content-based, REPL-verified — see 5.3 pins); memoize only positively classified views, never sentinels; include the digest in both `permission-paths-cache-key` and `recursive-query-plan-cache-key`; keep `evict-permission-paths-cache!` public and called from `write-schema!`; document the coverage invariant (every attr the path/plan computation reads must be a component of a digested tuple) at the `schema-basis` definition site
- [x] 5.3 Pin the REPL-verified Datomic facts as regression tests so a peer upgrade fails loudly: single-component edit rewrites the composite tuple in the same tx; `retractEntity` retracts the tuple into history; `(d/history (d/as-of db t))` is filtered to ≤ t; `(d/basis-t (d/as-of db t))` returns the underlying basis (hence the `as-of-t` filter); `d/history` works on `d/with` db-afters and includes speculative datoms; `.id` returns the same UUID on plain/as-of/with dbs; `d/is-filtered` is true only for `d/filter` dbs; `as-of-t`/`since-t`/`is-history` identify their views with with-dbs reading as plain; `d/history` on a `d/filter` db respects the filter (pinned with a hide-everything predicate; filter views stay uncached because predicates are arbitrary functions); `d/history` on a `d/since` db is since-filtered; `Db.equals` is value-based and content-aware (same-basis `d/db` calls equal; different-content `d/with` db-afters at the same `t` NOT equal)
- [x] 5.4 Regression tests: §3 repro — a plain programmatic permission retraction (no helper, no eviction) is immediately visible to `get-permission-paths`/`can?`; `d/as-of` at the pre-change basis returns the historical paths; a `d/with` speculative schema edit does not poison the shared cache for the real db; a `d/filter` db hiding permission entities does not poison the plain db's cache (and vice versa); mutation-class coverage — each of add/edit/retract × relation/permission changes the digest; unchanged-schema relationship writes still hit the cache (assert `calc-permission-paths` call count via `with-redefs` counter, as in the existing caching test)
- [x] 5.5 Document in README: invalidation is fully automatic for all schema writes; digest cost is O(all-time schema edits) per db value (identity-memoized), with pathological-churn guidance; note post-`d/excise` behavior is correct by construction
- [x] 5.6 Gate: full suite green via nREPL

## 6. API error contract — D13

- [x] 6.1 Implement `write-relationship!` (both arities) and `delete-relationship!` (both arities) on `Spiceomic`, delegating to `spiceomic-write-relationships!` (§13)
- [x] 6.2 Replace the consistency `assert` with `ex-info {:type :eacl/unsupported-consistency}` and `expand-permission-tree`'s bare `Exception` with `ex-info {:type :eacl/not-implemented}`; remove the vacuous type assert in `spiceomic-count-resources` (§16.1, §16.2, §16.11)
- [x] 6.3 Tests: `write-relationship!`/`delete-relationship!` round-trip; `(consistency/fresh token)` throws the typed error (existing `thrown? Throwable` assertion in `spice-test` keeps passing); validation behavior verified with `*assert*` bound false
- [x] 6.4 Gate: full suite green via nREPL

## 7. Housekeeping, docs, and the differential test — D14–D15

- [x] 7.1 Delete dead namespaces and files: `src/eacl/datomic/rules.clj`, `src/eacl/datomic/rules/optimized.clj`, `src/eacl/datomic/rules/optimized_old.clj`, `src/eacl/datomic/impl/datalog.clj`, the `Relationship` fn in `impl/base.clj`, root-level `simple_test.clj` / `test_large_offset.clj` / `test_cursor_pagination.clj`, and the commented-out `performance_test.clj`/`benchmark_test.clj` bodies (or move to `docs/attic/`) (§16.3, §15)
- [x] 7.2 Fix README: ID-configuration section names the canonical `make-client` keys (§5); quickstart "transact relationships" example rewritten around `create-relationships!` / `tx-relationship` with `:allow-tempids?` (§15); add a Breaking Changes section covering the D9/D11/D12/D10/D5 behavior changes and dead-namespace removal; document cursor TTL option and the unknown-ID contract
- [x] 7.3 Rename ns `eacl.datomic.parser_test` → `eacl.datomic.parser-test` (file stays `parser_test.clj`) so the cognitect runner's `-test$` pattern matches (§14)
- [x] 7.4 Fix test typos: `[:eacl/id "user2"]` → `"user-2"` in `indexed_test.clj` (~line 542) and give the empty `(testing "…arrow_relation works")` block (~line 434) its intended body (§16.9)
- [x] 7.5 Add the seeded differential property test (no new deps): generate small random schemas/graphs (direct, arrow, self-permission, multi-path, recursive parent) and assert `lookup-resources` set == `can?` ground truth == paginated union at page sizes 1/3/7 == `count-resources`, and the reverse via `lookup-subjects` (D15, audit §18.6)
- [x] 7.6 Final gate: full suite green via nREPL, then one cold `clj -X:test` run to confirm the runner discovers all namespaces (including the renamed parser tests) and everything passes
