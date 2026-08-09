# eacl-v8-root-fixes — session handoff

Checkpoint for a fresh session with none of the prior context. Read this,
then `proposal.md` → `design.md` → `tasks.md` → `specs/**`. Progress:
**48/54 tasks** — groups 1–8 complete, 9 complete except two recorded
Datomic sub-items, 11.1–11.3 complete, 12.1 recorded (**:triggered**).
Remaining engineering: the two Datomic 9.x sub-items, group 10 (CLJS
engine — the one large remaining build), 11.4 (Dafny cleanup, may trail),
and 12.2–12.3 (wave batching — triggered, its own multi-week track).

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

- **This worktree** is
  `/Users/petrus/Code/eacl/.claude/worktrees/eacl-v8-correctness-optimization-a58fe3`
  on branch `claude/eacl-v8-correctness-optimization-a58fe3` — the v8
  lineage plus this change's commits. Module layout: `modules/eacl` (core,
  `.cljc`), `modules/eacl-datomic|-datahike|-datascript`.
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

1. **Stale-nREPL mixing produces false test results.** After any src change
   that matters, verify on a FRESH JVM:
   `clojure -M:dev -e "(require 'the.ns :reload) ..."` or run suites via a
   fresh `clojure -M:dev -e "(require 'clojure.test 'ns1 ...) (run-tests ...)"`.
   Always double-quote code args (single quotes strip `!`). When piping
   output, remember the pipe eats the exit code — write to a log file and
   `echo $?` (this bit us once tonight: a "green" run had 2 failures).
2. **Forward references bite on cold compile but not warm REPL.** Finish with
   a cold `clojure -M:dev -e "(require ...)"` before committing.
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
   Formal smoke: `clojure -M:dev:formal-smoke -e "(require ... 'eacl.formal.production-kernel-test) ..."`.
   CLJS: `clojure -M:datascript-cljs-test && node target/datascript-cljs-test.js`
   (failures=0 errors=0). Plus ledger check + reflection gate.

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

## Remaining work — instructions

Run items sequentially; they share files with each other far less than the
finished groups did, but 9.2-tail and 11.4 both touch cursor formats.

### 1. Group 9.2-tail — Datomic relationship pages onto `eacl.engine.relationships`

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

### 2. Group 9.3-Datomic — option-family unification

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

### 3. Group 10 — CLJS production engine (R8; the big one; own sessions)

Design D-8. The switch point is
`eacl.formal.production-kernel-js/default-selection` (currently
`{:kernel generated-javascript-kernel}`). The promotion target is the
handwritten CLJC engine `eacl.engine.indexed`
(`formal/smoke/clj/eacl/engine/indexed.cljc` — move it back under
`modules/eacl/src` when promoting). **Honest scoping from this session:**
the kernel boundary is two protocols in `eacl.verified-kernel`
(`DecisionKernel/-decide` with ~15 pure decision operations, and
`IndexedTraversalKernel` — compile/init/drive/resume/continue-page/read —
the scan-command state machine). Backing those with the handwritten engine
means writing a handwritten CLJS state machine honoring the generated
contract, certified by the existing rig
(`formal/smoke/cljs/.../verified_authority_test_runner.cljs`, cross-runtime
vectors, 62-case counterexample replay, mutation controls, plus the new
`eacl.datascript.cache-model-test` already in the CLJS runner). That is
multi-session work; do NOT start it at the end of a long session. Tractable
first steps in order: (a) 10.2's rig-against-CLJS-engine job — the
semantics-bridge differential (`formal/smoke/clj/.../semantics_bridge_test`)
already compares `eacl.engine.indexed` to the generated kernel on JVM; add
the CLJS twin to CI. (b) 10.3's `:advanced` build job (expect it red; the
452 unexterned access sites live in the generated foreign-lib consumers —
`eacl.formal.production-kernel-js` + `dafny-seq` shim). (c) 10.4's ns/result
ceiling gate recorded into
`formal/verification/explorer-v8-release.edn`-style EDN. (d) only then the
engine swap itself.

### 4. Group 11.4 — Dafny cleanup pass (may trail indefinitely)

Delete the ordinal rebase family + backward-render mode + `AfterCursor` arm
from `formal/dafny` (dead generated ops are never invoked, so this is
proof-tree hygiene, not behavior); retarget or delete `Pagination.dfy`;
update `formal/verification/assurance-matrix.edn` so every model maps to
shipped code; regenerate kernels/vectors/manifests via `bin/formal`. Needs a
Dafny toolchain and the regeneration pipeline; budget a full session.

### 5. Groups 12.2–12.3 — wave-batched scan protocol (TRIGGERED)

The 12.1 decision is recorded in
`formal/verification/explorer-v8-recursive-performance.edn`
(`:batching-trigger-decision`). The proof plan (pending-scans ghost-view
generalization through `IndexedForwardCompleteness`/`IndexedReverseCompleteness`/
`IndexedRefinement`; drive returns bounded command batches; resume folds
ordered responses; fuel exhaustion Yields without partial batches; cursor
digests version the emission order) lives in the workflow record cited in
design D-9. 6–10 weeks of Dafny; run it as its own OpenSpec change or a
dedicated track, not inside a night session.

## Deferred with cause (decisions, not omissions)

- **6.4 AEAD portable codec:** sync CLJS GCM is not responsibly
  implementable (design D-4 status note). Portable cursors stay
  HMAC-authenticated; split documented in release notes.
- **Datahike provider-store port (was in 9.3):** dropped — it would have
  copied the exact provider surface 11.1 deleted as dead.
- **Managed-by-default:** stays off; revisit only after the 8.3 randomized
  managed oracles have soaked in CI.

## Current perf picture

Unchanged from post-group-5 recording (groups 6–9 don't touch crossing
counts): star/chain counts 3.7–5.9× v7, deep checks 5.7×–56× (v7
stack-overflows where v8 completes on chain-10k), raw first-50 pays the
documented keyset closure trade. The path to ≤2.0× on crossing-dominated
ops is 12.2 batching (triggered) — everything host-side is done.
