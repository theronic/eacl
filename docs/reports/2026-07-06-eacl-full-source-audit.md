# EACL Full Source Audit — Bugs & Recommendations

- **Date:** 2026-07-06
- **Branch:** `codex/recursive-stable-cursor-max-depth` (HEAD `8af4e95`)
- **Scope:** All of `src/` (core, datomic impl, indexed engine, schema, parser, lazy merge-sort), all of `test/`, README, and stray root files.
- **Method:** Full source read, then empirical verification of every suspected bug against a live nREPL (in-memory Datomic, v7 schema). Findings below are marked **VERIFIED** (reproduced in the REPL) or **CODE-READ** (confirmed by inspection only). The full existing test suite passes (35 tests, 376 assertions, 0 failures), so none of the verified bugs are currently covered by tests.

**Good news first:** the core engines are sound. Two differential property tests were run as part of this audit and passed:

1. Recursive engine, 501-folder tree (`read = reader + parent->read`): `lookup-resources` set == `can?`-derived ground truth == paginated collection (limit 7) == `count-resources`, with zero duplicates.
2. Non-recursive multi-path arrow (`admin = account->admin + shared`, 40 servers interleaved across 2 accounts + 2 direct grants): full enumeration == paginated collection at limits 1/3/7, sorted, no duplicates.

The bugs cluster at the *edges*: schema parsing/writing, keyword collation in one index scan, cache invalidation, ID-configuration plumbing, and error handling.

---

## Severity index

| # | Severity | Finding | Status |
|---|----------|---------|--------|
| 1 | **Critical** | `write-schema!` silently **deletes the entire schema** when the schema string fails to parse (incl. schemas containing `//` comments) | VERIFIED |
| 2 | **Critical** | `relation-datoms` `:a`–`:z` index range makes relations with certain subject-type keywords **invisible to permission evaluation** | VERIFIED |
| 3 | High | Permission-path/plan caches keyed by `(.id db)` are never invalidated by data — **revoked permissions keep granting access** (multi-peer, programmatic schema changes, `as-of` views) | VERIFIED |
| 4 | High | `read-relationships` with a **nonexistent** `:subject/id`/`:resource/id` returns **all** relationships (filter degrades to global scan) | VERIFIED |
| 5 | High | `make-client` silently **ignores the README-documented `:entid->object-id` option** (actual key: `:entity->object-id`) | VERIFIED |
| 6 | High | v3 recursive cursors grow **unboundedly** (~48 bytes/emitted resource) and leak raw Datomic eids to clients | VERIFIED |
| 7 | Medium | Expired/garbage cursor tokens decode to `nil` → pagination **silently restarts at page 1** | VERIFIED |
| 8 | Medium | Parenthesized permission expressions (valid SpiceDB) crash with bare `AssertionError` | VERIFIED |
| 9 | Medium | Duplicate `definition` blocks / duplicate relations silently last-win (first block dropped → destructive deltas) | VERIFIED |
| 10 | Medium | Arrow-target validation for multi-subject-type relations is **declaration-order-dependent** | VERIFIED |
| 11 | Medium | `create-relationships!` with nonexistent subject/resource throws raw Datomic `not-an-entity` error | VERIFIED |
| 12 | Medium | `impl/tx-relationship` silently creates **ghost entities** for unresolvable string IDs | VERIFIED |
| 13 | Medium | `write-relationship!` / `delete-relationship!` protocol methods unimplemented → `AbstractMethodError` | VERIFIED |
| 14 | Low | `eacl.datomic.parser_test` namespace (underscore) is never run by `clj -X:test` | CODE-READ |
| 15 | Low | README quickstart “transact relationships” example cannot work against the v7 schema | VERIFIED |
| 16 | Low | Assorted: vacuous assert, assertion-based validation, dead namespaces, doc/impl gaps, test typos | CODE-READ |

---

## 1. [Critical] `write-schema!` silently wipes the schema on parse failure — VERIFIED

**Where:**
- [parser.clj:81](../../src/eacl/spicedb/parser.clj) `parse-schema` returns the instaparse *failure object* on bad input; nothing ever checks `insta/failure?`.
- [parser.clj:167-171](../../src/eacl/spicedb/parser.clj) `transform-schema` returns `nil` for a non-vector parse tree, so `->eacl-schema` produces `{:relations [] :permissions []}`.
- [schema.clj:369-409](../../src/eacl/datomic/schema.clj) `write-schema!` then computes deltas of *existing schema vs empty schema* → retracts **everything**.

**Repro (REPL-verified):**

```clojure
(schema/write-schema! conn "definition user {}
   definition account {
     relation owner: user
     permission admin = owner")   ; <- missing closing brace
;; => returns deltas retracting ALL relations & permissions. No exception.
;; read-schema afterwards: {:relations [] :permissions []}
```

Two aggravating factors, both verified:

- **`//` comments are not supported by the grammar.** A schema pasted from the SpiceDB playground (which emits comments) fails to parse and triggers exactly this path: `(parser/->eacl-schema (parser/parse-schema "// comment\ndefinition user {}"))` ⇒ `{:relations [] :permissions []}` — silently.
- `collect-parse-tree-issues` walks the failure object with `postwalk` and finds nothing (MapEntries don’t match any case), so validation does not throw either.

If relationships exist, the orphan check throws a *misleading* “Cannot delete relation …” error. If none exist (fresh environments, staging, tests, or types without relationships yet), the schema is destroyed and the malformed string is stored as `:eacl/schema-string`.

**Recommendations:**
1. In `parse-schema` (or at the top of `->eacl-schema`): `(when (insta/failure? parse-tree) (throw (ex-info "Schema parse error" {:failure (insta/get-failure parse-tree)})))`. This is a two-line fix that eliminates the data-loss path.
2. Make `transform-schema` throw rather than return `nil` for unexpected input (defense in depth).
3. Add `//` and `/* */` comment support. With instaparse this is cleanest via a custom whitespace parser passed to `:auto-whitespace` (whitespace-or-comments idiom), so comments are legal anywhere whitespace is.
4. Belt-and-braces: have `write-schema!` refuse to proceed (or require an explicit `:allow-full-retraction? true` opt) when the *new* schema parses to zero definitions while the existing schema is non-empty. A one-character typo should never be able to empty the schema.
5. Add tests: parse-failure throws; comment-bearing schema round-trips; malformed schema leaves DB untouched.

## 2. [Critical] `relation-datoms` `:a`–`:z` range breaks permissions for legal type names — VERIFIED

**Where:** [indexed.clj:53-63](../../src/eacl/datomic/impl/indexed.clj)

```clojure
(let [start-tuple [resource-type relation-name :a]
      end-tuple   [resource-type relation-name :z]]
  (d/index-range db :eacl.relation/resource-type+relation-name+subject-type start-tuple end-tuple))
```

The scan is bounded by *subject-type* keywords `:a` and `:z`. Any relation whose **subject type** sorts outside that window is invisible to `calc-permission-paths` / `resolve-self-relation` / `find-relation-def` — and therefore to `can?`, `lookup-resources`, `lookup-subjects`, and the recursive planner. The relationship data writes fine; evaluation just never sees the schema edge. Failure is silent (a `log/warn "Missing Relation definition"`).

**Repro (REPL-verified):**

```clojure
@(d/transact conn [(impl/Relation :zone :owner :zebra)
                   (impl/Permission :zone :admin {:relation :owner})])
;; relationship written and visible in the index:
;;   :relationship-tuple-exists 1
(idx/can? db (spice-object :zebra [:eacl/id "zebra-1"]) :admin (spice-object :zone [:eacl/id "zone-1"]))
;; => false  (should be true)
```

Empirically missed subject types: `:zebra` (sorts after `:z`), `:Admin` (uppercase sorts before `:a`), `:my.app/user` (namespaced keywords sort outside the plain-keyword window entirely). `:z` itself is also excluded (exclusive end). Only plain lowercase keywords strictly inside the window work. Nothing validates or warns about this at schema-write time.

**Recommended fix (verified in REPL):** replace the bounded range with a prefix scan + attribute/prefix guard — the same pattern `subject->resources` already uses:

```clojure
(defn relation-datoms [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [attr-eid (d/entid db :eacl.relation/resource-type+relation-name+subject-type)]
      (->> (d/seek-datoms db :avet :eacl.relation/resource-type+relation-name+subject-type
                          [resource-type relation-name])
           (take-while (fn [d]
                         (and (= attr-eid (:a d))
                              (= resource-type (nth (:v d) 0))
                              (= relation-name (nth (:v d) 1)))))))
    []))
```

This returned correct datoms for `:zebra`, `:Admin`, `:my.app/user`, and `:user` in testing. Note the `(= attr-eid (:a d))` guard is required — `seek-datoms` iterates past the end of the attribute’s segment.

**Also add:** a generative/table test that round-trips schema + check across adversarial type names (`:zebra`, `:Zoo`, `:z`, `:a`, namespaced, unicode).

## 3. [High] Permission-path & plan caches never invalidate on schema change — VERIFIED

**Where:** [indexed.clj:101-117](../../src/eacl/datomic/impl/indexed.clj). Both `permission-paths-cache` and `recursive-query-plan-cache` key on `[(.id db) resource-type permission-name]`. `(.id db)` is the *database UUID* — verified identical across transactions. So a cache entry is valid forever unless explicitly evicted.

`write-schema!` calls `evict-permission-paths-cache!` ([schema.clj:408](../../src/eacl/datomic/schema.clj)), which covers exactly one case: schema written via `write-schema!` *in the same JVM*. Not covered:

1. **Programmatic schema changes** — the README-documented “Advanced: Programmatic Schema” path (`d/transact` of `Relation`/`Permission` maps). Verified: after retracting a permission entity, the cache still returns the old path — i.e. **a revoked permission continues to grant access** until process restart or LRU pressure:

   ```clojure
   ;; after retractEntity of the :admin permission:
   {:same-id? true, :paths-after-retraction-via-cache 1, :paths-after-retraction-fresh 0}
   ```
2. **Multiple peers** — the README explicitly recommends scaling Datomic peers horizontally. `write-schema!` on peer A evicts A’s atom; peers B…N serve stale authorization *indefinitely*.
3. **`d/as-of` / `d/history` views** — same `.id`, so a time-travel db resolves *current* cached paths instead of the schema as of that basis.

**Recommendations (in order of preference):**
1. **Peer-safe invalidation via `tx-report-queue`:** ship a small listener (the repo already has the pattern in [test/eacl/report_queue.clj](../../test/eacl/report_queue.clj)) that evicts both caches whenever a transaction touches any `:eacl.relation/*` or `:eacl.permission/*` attribute. This covers all three cases for live conns and is cheap.
2. Alternatively (or additionally), include a *schema basis* in the cache key: maintain a single well-known schema-version entity bumped by `write-schema!`, and read its latest `:tx` with one `d/datoms :eavt` call per query (cheap). This fixes `as-of` correctness too (key by the tx visible *in that db view*), but doesn’t catch programmatic writes unless they also bump the version.
3. At minimum, document loudly that programmatic schema writes require `evict-permission-paths-cache!` **on every peer**.
4. Note `evict-permission-paths-cache!` already resets both caches — keep them coupled in whatever fix lands.

## 4. [High] `read-relationships` with a nonexistent ID returns ALL relationships — VERIFIED

**Where:** [core.clj:101-113](../../src/eacl/datomic/core.clj) `spiceomic-read-relationships` resolves `:subject/id` / `:resource/id` via `object-id->entid`, which yields `nil` for an unknown ID, and then `assoc`es that `nil` into the filters. [impl.clj:221-248](../../src/eacl/datomic/impl.clj) `read-relationships` treats a `nil` id as “no filter” (its own missing-id `throw` is unreachable through this wrapper because the wrapper already nil’ed the key), so the query falls through to `scan-global-relationships` and `relationship-matches-filters?` matches everything.

**Repro (REPL-verified):**

```clojure
(eacl/read-relationships client {:resource/type :account :subject/id "i-do-not-exist"})
;; => ALL account relationships (alice's AND bob's) — should be [] or an error
```

In any multi-tenant context where a caller-supplied ID reaches `read-relationships`, this is a data leak; it can also feed bulk-delete flows (`read → delete-relationships!`) with the wrong set.

**Recommendation:** in `spiceomic-read-relationships`, when an ID filter is present but resolves to `nil`, either throw `ex-info` (matching the intent of the impl-level guards) or return `[]`. Pick one and test it. Same check for `:resource/id`.

## 5. [High] `make-client` ignores the documented `:entid->object-id` option — VERIFIED

**Where:** [core.clj:281-309](../../src/eacl/datomic/core.clj) destructures `entity->object-id` (entity → id), but the README (“EACL ID Configuration”, both examples) documents `entid->object-id` (db, eid → id). Options maps are not validated, so the documented key is silently dropped and the default `:eacl/id` mapping is used.

**Repro (REPL-verified):** configuring `{:entid->object-id (fn [db eid] (str "EXT-" ...))}` per the README returns un-prefixed IDs; the README’s “identity functions” example likewise silently returns `:eacl/id` strings instead of eids.

**Why it matters:** ID mapping is exactly the kind of config people set once and trust. Silent fallback to `:eacl/id` means apps using `:your/id` per the README get `nil` external IDs (or seemingly working behavior in environments where `:eacl/id` happens to exist) with no error.

**Recommendations:**
1. Accept **both** keys (`:entid->object-id` taking precedence, adapting arities), or rename to match the README — but keep backward compatibility with `:entity->object-id` since production code uses it.
2. Validate the opts map: throw on unrecognized keys. This one assertion would have caught the drift immediately.
3. Fix the README examples to whatever the canonical key is.
4. Add a config test that exercises a *non*-`:eacl/id` attribute end-to-end (current `config_test.clj` only overrides `object-id->ident`).

## 6. [High] v3 recursive cursors grow without bound and leak eids — VERIFIED

**Where:** [indexed.clj:395-403, 445-470, 903-917](../../src/eacl/datomic/impl/indexed.clj). The v3 cursor **is** the whole recursion state: `:stack` (pending tasks), `:best-depth` (every discovered fact), and `:emitted` (every resource ever returned). `default-internal-cursor->spice` passes v3 through unchanged ([core.clj:60-62](../../src/eacl/datomic/core.clj)), so raw eids (stack tasks, relation eids, emitted set) go to the client and come back.

**Repro (REPL-verified):** wide tree (root + 500 children), page size 50: cursor token = 3,058 bytes after page 1; 5,322 after page 2; 12,122 after 250 results — ~48 bytes per emitted resource. Extrapolated: ~5 MB cursor at 100k results; at the stated 10M-entity goal this is unusable. Each request must upload/download the full state, and `cursor->token`’s base64-EDN inflates it further.

Secondary issues, same mechanism:
- **Raw eid exposure** contradicts the README’s own guidance (“internal Datomic eids should not be exposed to consumers”) and makes v3 cursors invalid after a backup/restore (eids are not stable), unlike v2 cursors which are converted to external IDs.
- The cursor embeds `:max-depth` and throws on mismatch (good), but nothing versions it against **schema changes**; a plan change mid-pagination resumes against different seeds with undefined results (see also §16.8).

**Recommendations:**
1. Short-term: document the growth characteristic; consider capping (`:emitted` count or serialized size) and failing with a typed error advising a narrower query.
2. Medium-term options (trade-offs, pick deliberately):
   - **Server-side state**: keep recursion state in a bounded cache keyed by an opaque token id; the client carries only the id. Costs: state affinity/TTL, or a shared store for multi-peer.
   - **Algorithmic**: emitting in a *globally sorted* order per node would let “already emitted” be re-derived as `eid <= high-water-mark` instead of a set — that’s the direction the pre-frontier implementation took and was abandoned for performance; if revisited, the `:best-depth` map can also be dropped from the cursor.
   - **Compression**: delta-encode the emitted set (sorted eids) + zstd before base64. This buys maybe 5–10× but doesn’t change the asymptotics.
3. Regardless: run v3 cursors through the same eid↔external-id coercion as v2 (`:emitted`, `:stack` task cursors, `:best-depth` keys) so cursors survive restores and don’t leak internals.

## 7. [Medium] Expired or corrupt cursor tokens silently restart pagination — VERIFIED

**Where:** [core.clj:28-46](../../src/eacl/datomic/core.clj). `token->cursor` returns `nil` for: expired `:t` (TTL default 300 s), missing `:t`, undecodable base64/EDN, or any string not starting with `eacl1_`. All call sites use `(some->> (token->cursor ...) ...)`, so `nil` flows into the query as “no cursor” → **page 1 again**, no error.

**Repro (REPL-verified):** token minted with `ttl-seconds -10` (and a garbage token) both decode to `nil`; a lookup with them returns the first page.

A batch consumer that takes >5 minutes between pages (very plausible while processing pages of 1,000) silently loops back to the start — duplicates at best, an infinite loop at worst. Note also `cursor->token`’s `ttl-seconds` option is dead code from the API’s perspective: `spiceomic-lookup-resources` etc. never pass opts, so 300 s is unconfigurable.

**Recommendations:**
1. Distinguish “no cursor” from “bad cursor”: throw `ex-info {:type ::invalid-cursor}` (or `::expired-cursor`) when a non-nil token fails to decode or is expired. SpiceDB clients expect a FAILED_PRECONDITION-style error here.
2. Thread a `:cursor-ttl-seconds` client option through `make-client` opts → `cursor->token`; consider defaulting to no expiry (the TTL protects nothing security-critical — the token is not authenticated anyway).
3. Test: expired token throws; tampered token throws; nil cursor returns page 1.

## 8. [Medium] Parenthesized permission expressions crash with a bare `AssertionError` — VERIFIED

**Where:** grammar accepts `paren-expr` ([parser.clj:54-57](../../src/eacl/spicedb/parser.clj)) but `extract-base-expr-identifier` ([parser.clj:455-461](../../src/eacl/spicedb/parser.clj)) only handles identifier children, yielding `{:type :identifier :name nil}` → `resolve-component` → `{:permission nil}` → `impl/Permission`’s `{:pre [(or relation permission)]}`.

**Repro (REPL-verified):** `permission manage = (owner + editor)` ⇒ `AssertionError: Assert failed: (or relation permission)`.

**Recommendation:** since EACL is union-only, parens are semantically trivial — flatten them: in `transform-arrow-expr`/`flatten-expression`, recurse into `paren-expr → permission-expr` and splice the resulting components into the union. If you’d rather not support them yet, add a `:paren-expr` check to `collect-parse-tree-issues` with a clear “unsupported” message. Either way, no assertion crashes.

## 9. [Medium] Duplicate definitions / relations silently last-win — VERIFIED

**Where:** `extract-definitions` and `extract-relations` both pour into maps ([parser.clj:128-165](../../src/eacl/spicedb/parser.clj)), so a repeated `definition account {...}` or repeated `relation owner: ...` silently drops the earlier one.

**Repro (REPL-verified):** two `definition account` blocks ⇒ only the second block’s relations survive. Combined with `write-schema!` delta semantics, the first block’s relations/permissions become **retractions** (or misleading orphan errors) — the same destructive family as §1. SpiceDB rejects duplicate definitions.

**Recommendation:** detect duplicates during extraction and throw with the definition/relation name. Also consider rejecting a permission and relation sharing a name on one type (SpiceDB does; EACL’s `resolve-component` silently prefers the relation).

## 10. [Medium] Arrow validation is declaration-order-dependent for multi-type relations — VERIFIED

**Where:** `validate-schema-references` builds `relation-subject-types` as a plain map keyed `[res-type rel-name]` ([schema.clj:252-257](../../src/eacl/datomic/schema.clj)) — **last** declared subject type wins; the parser’s `collect-schema-info` uses the **first** type ref ([parser.clj:528-532](../../src/eacl/spicedb/parser.clj)) to classify arrow targets. Validation and resolution disagree, and both ignore the full set.

**Repro (REPL-verified):** with `permission mgmt` defined on `user` only:

```
relation owner: user | group   →  write-schema! REJECTS  (validates against :group)
relation owner: group | user   →  write-schema! ACCEPTS  (validates against :user)
```

Identical semantics, opposite outcomes.

**Recommendation:** validate arrows against **all** subject types of the source relation. Then pick a policy:
- *SpiceDB-strict:* reject unless the target exists on every subject type.
- *Match the runtime:* EACL’s evaluator unions over the intermediate types that have the target (missing ones contribute nothing) — if that’s the intended semantics, accept when ≥1 type has the target and warn for the others.
Either is defensible; order-dependence is not. Also align `resolve-component`’s relation-vs-permission classification to consult all types (mixed relation/permission targets across types should be an explicit error).

## 11. [Medium] Writes to nonexistent subjects/resources give raw Datomic errors — VERIFIED

**Where:** `spice-relationship->internal` ([core.clj:115-119](../../src/eacl/datomic/core.clj)) maps unknown external IDs to `nil` without checking; the nil lands in tx-data.

**Repro (REPL-verified):** `create-relationships!` with subject `"ghost-user"` ⇒ `IllegalArgumentException :db.error/not-an-entity Unable to resolve entity: in datom [nil :eacl.v7.relationship/… ]`.

**Recommendation:** validate both endpoints resolve in `spice-relationship->internal` and throw `ex-info` naming `{:subject {:type :user :id "ghost-user"}}`. This also covers `:touch`/`:delete`, where a nil currently makes `relationship-exists?` return false and delete silently no-op.

## 12. [Medium] `impl/tx-relationship` silently creates ghost entities for unresolvable string IDs — VERIFIED

**Where:** `object-id->eid-or-tempid` ([impl.clj:49-54](../../src/eacl/datomic/impl.clj)) intentionally passes unresolvable strings through as tempids (fixtures rely on this for same-transaction entity+relationship creation).

**Repro (REPL-verified):** a typo’d resource id `"acct-1x"` transacts fine and mints a **new entity** whose only attribute is the reverse relationship tuple — no `:eacl/id`, unreachable by external ID, and the intended grant never lands on the real `"acct-1"`. No warning.

**Recommendation:** make tempid pass-through **opt-in**, e.g. `(tx-relationship db rel {:allow-tempids? true})` used by fixtures, defaulting to throwing on unresolvable IDs. Alternatively accept explicit tempid wrappers (`(->tempid "acct-1")`) so intent is unambiguous. As-is, every caller of the advanced API is one typo away from silent permission loss + junk entities.

## 13. [Medium] Unimplemented protocol methods throw `AbstractMethodError` — VERIFIED

**Where:** `IAuthorization` declares `write-relationship!` and `delete-relationship!` ([eacl/core.clj:43-60](../../src/eacl/core.clj)); `Spiceomic` implements neither ([datomic/core.clj:226-279](../../src/eacl/datomic/core.clj)). Verified both arities throw `AbstractMethodError`.

**Recommendation:** implement them (trivial delegations to `spiceomic-write-relationships!`) or drop them from the protocol. Also note `expand-permission-tree` throws a plain `Exception. "not impl."` — prefer `ex-info` with `{:type ::not-implemented}` for programmatic handling.

## 14. [Low] Parser tests never run under `clj -X:test` — CODE-READ

**Where:** [test/eacl/datomic/parser_test.clj:1](../../test/eacl/datomic/parser_test.clj) declares `(ns eacl.datomic.parser_test ...)` — underscore, not hyphen. The cognitect test-runner’s default include pattern is `#".*-test$"`, which `eacl.datomic.parser_test` does not match, so the whole namespace is silently excluded from `clj -X:test` runs (it runs fine when required explicitly, which is why it looks alive from the REPL).

**Recommendation:** rename the ns to `eacl.datomic.parser-test` (file name stays `parser_test.clj`). Grep CI output for the namespace to confirm it appears afterwards.

## 15. [Low] README quickstart relationship-transaction example is broken — VERIFIED

**Where:** README “Now you can transact relationships:” shows `(Relationship "platform-tempid" :platform "account1-tempid")` inside a `d/transact`.

- With `eacl.datomic.impl.base/Relationship` this emits v6 `:eacl.relationship/*` attrs, which **do not exist** in `v7-schema` — verified: `:db.error/not-an-entity Unable to resolve entity: :eacl.relationship/resource-type`.
- With `eacl.datomic.impl/Relationship` (what fixtures import) it returns an `eacl.core.Relationship` *record*, which is not transactable data either — and its `:pre` requires `{:type ... :id ...}` maps, not bare strings.

The working pattern is `(impl/tx-relationship db (Relationship subject relation resource))` as used in fixtures.

**Recommendations:** fix the README example to use `tx-relationship` (or `create-relationships!` on the client); delete or clearly deprecate `base/Relationship` (nothing in the live path uses it, and it can only produce broken tx-data under v7); consider making `v6-schema` alias emit a deprecation note since it now *is* v7.

## 16. Low-severity & hygiene (code-read unless noted)

1. **Vacuous assertion:** `spiceomic-count-resources` asserts `(= (:type subject-ent) (:type subject))` where `subject-ent` is `subject` with only `:id` updated — always true ([core.clj:188-190](../../src/eacl/datomic/core.clj)). Presumably meant to check the resolved entity’s actual type; either implement that or delete it.
2. **Assertion-based API validation:** `lookup-subjects` rejects a missing resource via `{:pre ...}` ([indexed.clj:959](../../src/eacl/datomic/impl/indexed.clj)) — verified `AssertionError: Assert failed: (:id (:resource query))` for an unknown resource ID. `lookup-resources`/`count-resources` use `assert` similarly ([core.clj:159-166,186-190](../../src/eacl/datomic/core.clj)). Asserts vanish when `*assert*` is false and produce untyped errors; use `ex-info` consistently, and decide (and test) missing-object behavior: empty result vs typed error. Currently it’s assert-crash in three shapes.
3. **Dead/misleading namespaces:** `eacl.datomic.rules` (entirely commented), `eacl.datomic.rules.optimized`, `rules/optimized_old.clj`, and `eacl.datomic.impl.datalog` all target v6 `:eacl.relationship/*` attrs that no longer exist in the installed schema — if anyone wires them up they fail at query time. `eacl.impl.spicedb` is an empty stub. Root-level `simple_test.clj`, `test_large_offset.clj`, `test_cursor_pagination.clj` are fully commented-out scripts (also untracked, per git status). Recommend deleting or moving to `docs/attic/`; they actively mislead contributors about which engine is live.
4. **`:resource/id-prefix` documented but unimplemented:** the protocol docstring for `read-relationships` advertises it ([eacl/core.clj:27-36](../../src/eacl/core.clj)); `relationship-matches-filters?` ignores it. Implement or remove from the doc.
5. **`:create` uniqueness race:** `tx-update-relationship` checks existence against the read-time db ([impl.clj:257-278](../../src/eacl/datomic/impl.clj)); two concurrent `:create`s of the same relationship both pass and both “succeed” (datom add is idempotent, so no duplicate data, but SpiceDB `CREATE` semantics say the second must fail). Enforcing this requires a transaction function; alternatively document `:create` as best-effort and recommend `:touch`.
6. **`write-schema!` orphan-check race:** relationships transacted between the orphan check and the retraction tx can be orphaned. Low likelihood; a transaction function (or re-check inside the tx) would close it.
7. **Cursor `:p` is keyed by path index:** v2 cursors store per-path intermediate positions as `{path-idx eid}` ([indexed.clj:318-326](../../src/eacl/datomic/impl/indexed.clj)). A schema change between pages reorders/renumbers paths and silently mis-skips. Cheap hardening: include a hash of the path set in the cursor and restart-or-throw on mismatch.
8. **Recursive cursor vs schema change:** same class as above for v3 (`:stack` embeds relation eids and node vectors). A plan hash in the cursor would catch it.
9. **Test typo:** [indexed_test.clj:542](../../test/eacl/datomic/impl/indexed_test.clj) uses `[:eacl/id "user2"]` (no hyphen; entity doesn’t exist) so the assertion passes vacuously — the eid resolves to `nil` and `can?` is `false` regardless. Also [indexed_test.clj:434](../../test/eacl/datomic/impl/indexed_test.clj) has a `(testing "...")` with no body; the intended assertion sits outside it.
10. **Cursor inclusivity is undocumented:** the resource-level cursor is *exclusive* (results resume after `:e`; `subject->resources` seeks from `(inc cursor)`), while the per-path intermediate cursor stored in `:p` is *inclusive* (re-scanned via `inclusive-cursor->exclusive`’s `dec`). Both are correct, but the contract lives only in the arithmetic; docstrings on `subject->resources`/`resource->subjects`/`extract-cursor-eid` would help — the `test/eacl/datomic/impl/indexed_test.clj:656` comment (“cursor seems weird here. shouldn’t it be exclusive?”) suggests it has already cost debugging time.
11. **`consistency/fresh` returns a constant:** `(defn fresh [token] :fresh)` ignores its token, and `spiceomic-can?`’s consistency check is an `assert` (see 16.2). Fine for now, but the API-compat story would be better served by `ex-info` with `{:type ::unsupported-consistency}`.

## 17. Performance observations (not bugs, worth tracking)

1. **`arrow-via-intermediates` is O(intermediates) seeks per page** ([indexed.clj:303-316](../../src/eacl/datomic/impl/indexed.clj)): one `subject->resources` seek per intermediate, then a pairwise-fold merge over all non-empty streams. A subject with 10k accounts pays 10k seeks before the first result of an arrow path. The `:p` cursor only skips the *prefix* of intermediates with no remaining results. If this becomes hot, a k-way heap merge plus per-intermediate lazy seeks would drop the constant factor; the README’s parallel-path caveat already gestures at this.
2. **Reverse lookups have no recursive engine:** `lookup-subjects` always uses the depth-limited recursive descent (`lookup-subject-eids*`), whose visited-set only guards the current ancestor chain — in dense permission DAGs the same (resource, permission) state can be re-explored once per distinct path (exponential worst case), and there is no cross-branch memoization. Forward got the frontier engine; reverse may eventually want the same treatment.
3. **`can*` similarly re-explores shared substructure** (no memoization across branches). Fine at current graph sizes; will matter for deep org-hierarchy schemas.
4. **`find-relations` in `read-relationships` scans all relation entities with `d/q` then filters in memory** ([impl.clj:136-154](../../src/eacl/datomic/impl.clj)) — fine (schema is sparse), just noting it contrasts with the indexed discipline elsewhere.

## 18. Test-coverage recommendations

The suite is strong on engine semantics (pagination equivalence, dedup, cycles, max-depth) and weak exactly where the verified bugs live:

1. **Schema-write failure paths:** parse errors, comments, duplicate definitions, zero-definition guard (§1, §9).
2. **Adversarial type names** through the full stack (§2).
3. **Cache invalidation:** schema change → immediate effect on `can?`, including programmatic writes and (if adopted) the tx-report listener (§3).
4. **Client ID-config matrix:** custom attribute end-to-end incl. cursors and `read-relationships` (§5) — would also have caught §4.
5. **Cursor abuse:** expired, tampered, cross-query cursor reuse, v3-cursor-into-non-recursive-query and vice versa (§7, §16.7-8).
6. **Differential/property test:** codify the audit’s cross-check (`lookup-resources` set == `can?` ground truth == paginated union == `count-resources`; ditto reverse) over randomized small graphs. This is the single highest-leverage test for engine regressions.
7. Re-enable parser tests in CI (§14).

## 19. Suggested fix order

1. §1 parse-failure guard + zero-definition guard (hours, removes data-loss).
2. §2 `relation-datoms` prefix scan (small, verified fix included above).
3. §4 nonexistent-ID guard in `read-relationships` (small, closes leak).
4. §5 `make-client` opts validation + README correction.
5. §7 typed cursor errors + TTL config.
6. §3 cache invalidation strategy (tx-report listener).
7. §8–§13 as batched medium fixes.
8. §6 recursive-cursor design decision (needs a plan doc; interacts with SpiceDB-compat goals).
9. §16 hygiene sweep + §18 test additions alongside each fix.

---

*Verification environment: nREPL on port 7910, Datomic peer 1.0.6733 in-memory, Clojure 1.12.0-alpha5. All “VERIFIED” items have exact repro snippets above; run them against `datomic:mem://` databases with `schema/v7-schema` installed.*
