# eacl-v8-root-fixes — session handoff

Checkpoint for a fresh session with none of the prior context. Read this,
then `proposal.md` → `design.md` → `tasks.md` → `specs/**`. Progress:
**54/54 primary tasks and all triggered follow-ons are complete.** Groups
11.4 and 12.2–12.3 landed in the final pass. Only 6.4 remains deferred with
its recorded synchronous-CLJS-crypto cause.

## What this change is

EACL is a SpiceDB/Zanzibar-style authorization library (relations,
permissions, relationships, `can?`, resource/subject enumeration) over three
backends (Datomic Pro, Datahike, DataScript) with a Dafny-generated decision
kernel. Two audit waves (recorded in
`docs/reports/2026-08-07-eacl-v8-exploration-review.md` Parts I–II and
`docs/reports/2026-08-07-eacl-v8-raw-recursive-performance-investigation.md`)
found ten root causes (R1–R10 in `proposal.md`). This change fixes them
correctness-first, then speed, then simplicity. v8 is **pre-release: no
migration path is owed**, so breaking cursor/token formats and defaults is
sanctioned.

## Where the code lives (critical)

- **This worktree** is `/Users/petrus/Code/eacl`, checked out detached at the
  requested v8 commit `c757d3ddea925ef8eb7d96a24fc569ab22642b94` with the
  OpenSpec implementation in the working tree. Module layout: `modules/eacl`
  (core, `.cljc`), `modules/eacl-datomic|-datahike|-datascript`.
- **NOT `main`** — main is v7.3. The audit's read-only reference copy of the
  pre-change v8 tip is `/Users/petrus/.codex/worktrees/4916/eacl` (branch
  `codex/restore-v8-enumeration-performance`); diff against it, never edit it.
- The generated kernel artifacts (`target/formal/java/classes`,
  `target/formal/browser/EaclKernel.browser.js`) are Dafny-built, on the
  classpath, not committed.
- **Concurrent-agent hazard:** a delegated background agent once edited this
  worktree while a new session was reading it. Before editing, check
  `ps aux | grep -i claude` and recent mtimes
  (`find modules -mmin -10`), and wait for quiescence if something is live.

## How to work in this repo (hard-won gotchas)

1. **Stale-nREPL mixing produces false test results.** Run every Clojure test
   through `clj-nrepl-eval` against a fresh nREPL and require changed
   namespaces with `:reload`. If a renamed test Var remains in a persistent
   namespace, `remove-ns` before the reload.
2. **Forward references bite on warm reloads.** Finish with a fresh nREPL
   namespace load; do not use the Clojure CLI as a test runner.
3. **Public source-closure ledger.** Touching public engine/backend src
   changes the reachable-var closure; CI enforces it. Regenerate with
   `node bin/public-source-closure.mjs write` then `... check`. The ROOTS
   live in `bin/public-source-closure.mjs` — deleting or moving a root var
   (e.g. a record) requires editing that list (the de-fork retargeted the
   DS/DH roots to `eacl.client.orchestration/ClientAuthorization`).
   If clj-kondo throws invalid-arity on `lazy_merge_sort.cljc`:
   `echo "{}" > .clj-kondo/config.edn`.
4. **Datomic tempid hazard.** Negative-long tempids resolve into implicit
   partitions whose eids exceed 2^53, which `eacl.secure-format` rejects.
   Use STRING tempids in fixtures (`recursive_fixture.cljc` shows how).
5. **Verification EDN files are ratchets, not code.** Never loosen a bound to
   pass a test. When a deliberate semantic change moves a number, re-record
   it WITH an inline rationale naming the task (precedent: group 5's keyset
   scan envelope; 8.4's path-calc 10→11; group 7's `:answer` budget in
   `performance-gates.edn`).
6. **Reflection gate.** `bin/reflection-gate` (in test.yml) compiles core +
   backends with `*warn-on-reflection*`; any warning fails CI. Gotcha it
   exists to remember: reader metadata attached to an UNQUOTED form inside
   syntax-quote is dropped — hint through a let-bound local in macros.
7. **The api map holds vars.** `eacl.datascript.core`/`eacl.datahike.core`
   pass `#'impl/...` vars (not values) to the shared orchestration so
   `with-redefs` instrumentation works. Keep it that way.
8. **Verify-everything command set** (fresh JVM, all must be 0F/0E):
   `eacl.verified-kernel-test eacl.backend.v8-test
   eacl.single-flight-coordination-test eacl.subproblem-cache-test
   eacl.subproblem-maintenance-test eacl.cache-test eacl.relay-test
   eacl.datascript.recursive-op-count-test eacl.datascript.keyset-recursion-test
   eacl.datascript.contract-test eacl.datascript.consistency-v3-test
   eacl.datascript.cache-model-test eacl.datascript.impl-test
   eacl.datahike.contract-test eacl.datahike.cache-model-test
   eacl.datomic.raw-op-count-test eacl.datomic.recursive-cache-test
   eacl.datomic.contract-test eacl.datomic.consistency-v3-test
   eacl.datomic.lookup-cache-test eacl.datomic.cache-differential-test
   eacl.datomic.cache-model-test eacl.datomic.trusted-surface-audit-test
   eacl.formal.counterexample-replay-test eacl.characterization-fixture-test`.
   Formal smoke and CLJS compilation must likewise be initiated through the
   running nREPL (`formal/smoke/cljs/run` is the reference wrapper). Plus
   ledger check + reflection gate.

## Done (commits, newest first)

- `44ecbb1` **11.1–11.2 + 12.1** — hygiene deletions (envelope cache path,
  zed-v2 constructors, relay frontier branch, `:latest-result`,
  `:shared-cache-store`/`:lookup-cache-store`), absence audit test,
  reflection gate + hints, batching trigger recorded `:triggered`.
- `f88555e` **9.2+9.4** — DS/DH client fork collapsed onto
  `eacl.client.orchestration` (one record, one make-client, api map);
  DS core 1,098→175 lines, DH 1,074→180.
- `1dafb9f` **9.1** — one filter contract on all backends
  (`eacl.relationships.filters`): nil anchors throw `:nil-anchor-keys`,
  the DS/DH nil-type/relation wildcard hole (audited P0-5) is closed,
  `:cursor`/`:limit` classify uniformly.
- `00f3f0a` **8.1–8.4** — every backend defaults
  `:coherence-authority :unknown` (stale-ALLOW pinning regression);
  docs rewritten (managed denotation reuse is LIVE; sorted per-relation
  stamp vector); randomized managed-vs-cache-free oracles on all three
  backends (`eacl.<backend>.cache-model-test`, DataScript port also in the
  CLJS runner); dependency-closure completeness guard in
  `compile-recursive-plan`.
- `c1e333c` **7.1–7.4** — completed answers are the weighted SubproblemStore's
  third tier (LRU, budget/4 per-entry ceiling via the verified publication
  decision, `:oversized-rejections`); honest FIFO sighting-window admission;
  `bounded-assoc`/`admit-entry?`/standalone maps/portable `LocalStore`
  deleted. Deviations recorded in tasks.md 7.2.
- `b4e83a6` and earlier — groups 1–6 (see git log and the previous handoff in
  history; group summaries remain accurate in `tasks.md`).

## Completed follow-ons — implementation record

Run items sequentially; they share files with each other far less than the
finished groups did, but 9.2-tail and 11.4 both touch cursor formats.

### Completed after this handoff: Group 9.2-tail

Datomic relationship pages now execute through `eacl.engine.relationships`
with the shared `:relationship-index` cursor edge. The Datomic page-token
version was bumped so tokens carrying the retired private edge fail as typed
invalid cursors. Focused API, implementation, contract, codec, and Spice suites
were re-goldened and passed.

### Completed after this handoff: Group 9.3-Datomic

Datomic construction now uses the shared `:security-*`,
`:cursor-ttl-seconds`, and `:object-id->lookup-ref` names. Its old spellings
remain non-mixable legacy aliases, Zed-token options are documented Datomic
extensions, and unknown-option errors expose the shared key shape. The
decorative provider-store option is rejected; the canonical
`eacl.cache/no-cache` value controls the real private stores.

### 1. Group 9.2-tail — Datomic relationship pages onto `eacl.engine.relationships` (DONE)

Replace `eacl.datomic.impl/relationship-page` (impl.clj ~440–525: the
private scan-plan/relationship-datoms machinery over `d/seek-datoms` with
`:relationship`-kind cursor edges) with the shared planner/executor
`eacl.engine.relationships/execute-page` that DS/DH already use
(`plan-scans` builds per-relation-def scan-specs; `execute-page` takes a
`scan-fn [spec resume-edge direction] -> rows` where each row is
`{:spec-idx n :subject-id eid :resource-id eid :relationship (->Relationship ...)}`;
edges are `{:kind :relationship-index :v 1 :scan-index n :subject-id eid
:resource-id eid}`). Steps:
1. Write a Datomic `scan-fn` over the v7 tuple indexes (see
   `eacl.datascript.impl/read-relationships`'s scan fns as the model; the
   endpoint-pair codec is shared already —
   `eacl.relationships.endpoint-pair/forward-value`/`reverse-value` +
   `eacl.datascript.db/eavt-endpoint-prefix`-style seeks exist as
   `d/seek-datoms` calls in the current private code you are deleting).
2. **Cursor format changes** (pre-release, sanctioned): the datomic
   relationship page token's internal edge switches from `:relationship`
   kind to `:relationship-index`. Update the datomic core sites that
   validate/authenticate the edge (`authenticate-page-bound`,
   `internal-page-query`, `validate-page-token-schema!` callers around
   core.clj 1075–1126) and the cursor-context version constant so old
   tokens fail with typed `:eacl.pagination/invalid-cursor`, not silently.
3. Delete the superseded private fns (scan-plan, relationship-datoms,
   matching-index-datom?, relationship-edge/-item,
   validate-relationship-bound!, relationship-page).
4. Re-golden `eacl.datomic.contract-test` +
   `eacl.datomic.api-contract-test` + `eacl.datomic.impl` read tests; the
   `datascript-large-relationship-cursor-skips-item-proof-test` has a
   Datomic twin — check `raw-op-count`/`v8-characterization` for cursor
   fixtures. The shared `assert-unified-filter-validation!` already runs on
   Datomic and must stay green.
5. Update the closure-ledger roots if any datomic fn named there dies.

### 2. Group 9.3-Datomic — option-family unification (DONE)

DS/DH now share `eacl.client.orchestration/base-client-opt-keys`
(`:security-key(ring)/(kid)`, `:cursor-ttl-seconds`, ...). Datomic still
uses `:page-token-key(ring)/(kid)` + `:zed-token-*` + `:page-token-ttl-seconds`
(core.clj ~2325–2340 known keys, ~2600–2790 parsing). Decide and implement
ONE family (design D-7 says one token-key family, one cursor-TTL name,
uniform unknown-option errors): recommended — accept the shared names on
Datomic as canonical aliases, keep the zed-specific extension keys
documented via a Datomic `:extra-client-opt-keys`-style doc block, and make
unknown-option ex-data shape match the orchestration's (`:unknown-keys` +
`:known-keys`). Also resolve the now-decorative `:cache {:store adapter}`
provider option: either wire provider adapters back to something real
(continuation store override) or reject with a helpful error — the spec
demands "options that had no effect now either work or are rejected".
Re-golden `eacl.datomic.config-test` + `lookup_cache_test` option blocks.

### 3. Group 10 — CLJS production engine (R8; DONE)

The production CLJS default now composes
`eacl.engine.portable-decisions` and `eacl.engine.portable-indexed`; the
generated JavaScript adapter and runtime live only under `formal/smoke/` as
the differential oracle. The release JAR packages the portable CLJC/CLJS
sources and generated JVM classes, but no generated browser IIFE, extern, or
loader metadata.

CI builds the formal differential runner, full DataScript/core suite, payload
audit graphs, and indexed benchmark under `:advanced` with warnings-as-errors.
Local certification passed **44 tests / 9,963 assertions**, **167 tests / 9,556
assertions**, and the injected portable-authority suite at **165 tests / 4,554
assertions** with 73 clients and every required traversal operation observed.
The full counterexample/mutation/characterization/bundle gate passed **71 tests
/ 18,778 assertions**.

`formal/verification/cljs-production.edn` records 8,684 ns/result at the
16,384-result reference size against a 15,000 ceiling. The portable engine adds
15,335 raw / 3,409 Java-GZIP bytes over the empty advanced runtime, within the
32 KiB / 8 KiB budgets and more than an order of magnitude below the retired
591,497-byte IIFE. The production bundle gate rejects BigNumber and generated
runtime markers. Browser authorization is explicitly advisory with a required
generated-JVM server re-check; the native-number Dafny ESM alternative remains
recorded.

### 4. Group 11.4 — Dafny cleanup pass (DONE)

`Pagination.dfy` and the ordinal rebase/backward-render/`AfterCursor` surface
are gone. `PageWindow.dfy` owns the retained normalization, window, keyset, and
continuation laws. A CI cleanup test checks every active source for the retired
markers and requires an exact one-to-one mapping from all 26 Dafny files to
the assurance matrix. The regenerated tree verifies 8,594 proof efforts with
a 24,106,086 maximum under the 50M deterministic limit.

### 5. Groups 12.2–12.3 — wave-batched scan protocol (DONE)

`IndexedBatching.dfy` implements request-ordered forward/reverse waves of at
most 64 commands, ordered response folding, and rollback to the original state
when fuel ends before a complete wave. `IndexedBatchCompleteness.dfy` gives
exact pending-scan ghost views and generalized forward/reverse coverage
invariants. Generated JVM and portable CLJS authorities use the same protocol;
cursor digests commit emission-order version 2. Independent streams require
exactly `2×ceil(streams/64)+1` crossings. Current evidence: JVM differential
51/16,156, CLJS differential 45/9,971 (normal and advanced), crossing gates
39/8,500, replay 61/18,310, and mutation control 249/249.

The post-regeneration all-backend nonbenchmark authority wrapper was also
started after its stale `watermark-test` inventory entry was replaced by the
live trusted-surface audit. It emitted no failure but was interrupted by its
600-second local timeout while Datahike enumeration was CPU-active, so that
attempt is not a fresh pass and should not be reported as one. The focused
OpenSpec completion gates above all completed.

## Deferred with cause (decisions, not omissions)

- **6.4 AEAD portable codec:** sync CLJS GCM is not responsibly
  implementable (design D-4 status note). Portable cursors stay
  HMAC-authenticated; split documented in release notes.
- **Datahike provider-store port (was in 9.3):** dropped — it would have
  copied the exact provider surface 11.1 deleted as dead.
- **Managed-by-default:** stays off; revisit only after the 8.3 randomized
  managed oracles have soaked in CI.

## Current perf picture

Wave batching replaces the old one-scan-per-round-trip profile. A 128-stream
independent fixture now produces two 64-command waves and exactly five kernel
crossings. Populated star recursion fell from roughly 4,067 crossings to about
99–102, with remaining variance explained by explicitly counted fuel yields.
The advanced portable CLJS 16,384-result gate measured 8,209 ns/result against
the 15,000 ceiling with a 0.62 largest/smallest normalized ratio. Raw first-50
still pays the documented sorted-keyset closure trade.
