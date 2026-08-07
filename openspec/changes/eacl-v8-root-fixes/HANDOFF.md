# eacl-v8-root-fixes — session handoff

Checkpoint for a fresh session with none of the prior context. Read this,
then `proposal.md` → `design.md` → `tasks.md` → `specs/**`. Progress: **31/54
tasks**, groups 1–6 landed (with recorded partials), group 7 in-flight,
groups 8–12 remaining.

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

- **This worktree** is on branch `claude/eacl-v8-correctness-optimization-a58fe3`,
  which was fast-forwarded onto the v8 tip and now *is* the v8 lineage plus
  this change's commits. The module layout is `modules/eacl` (core, `.cljc`),
  `modules/eacl-datomic|-datahike|-datascript`.
- **NOT `main`** — main is v7.3. The audit's read-only reference copy of the
  pre-change v8 tip is `/Users/petrus/.codex/worktrees/4916/eacl` (branch
  `codex/restore-v8-enumeration-performance`); use it only to diff against, not
  to edit.
- The generated kernel artifacts (`target/formal/java/classes`,
  `target/formal/browser/EaclKernel.browser.js`) were copied in from the 4916
  worktree and are on the classpath; they are Dafny-built (not committed).

## How to work in this repo (hard-won gotchas)

1. **Stale-nREPL mixing produces false test results.** This repo has bitten us
   repeatedly: a long-lived nREPL that loaded pre-edit namespaces will report
   phantom failures (or phantom passes) after you edit src. **After any src
   change that matters, start a FRESH nREPL** and run the suite there:
   `nohup clojure -M:nrepl > /tmp/nrepl.log 2>&1 &` then `sleep 25` then
   `clj-nrepl-eval --discover-ports` (use the port whose directory is THIS
   worktree). Always double-quote code for `clj-nrepl-eval` (single quotes
   strip `!`). For a definitive check, cold-compile in a clean process:
   `clojure -M:dev -e "(require 'some.ns) (println :ok)"`.
2. **Forward references bite on cold compile but not warm REPL.** A warm REPL
   with everything already loaded will accept a fn used above its def; the CI
   cold compile won't. Always finish with a `clojure -M:dev -e "(require ...)"`
   cold check before committing.
3. **Public source closure ledger.** Touching any public engine/backend src
   changes the reachable-var closure; CI enforces it. Regenerate with
   `node bin/public-source-closure.mjs write` then `... check`. If clj-kondo
   throws an "invalid-arity" error on `lazy_merge_sort.cljc` you're missing a
   local kondo config — `echo "{}" > .clj-kondo/config.edn` (it's gitignored;
   the script's own `--config` carries the needed `:lint-as` for
   `with-request-engine`).
4. **Datomic tempid hazard.** On Datomic 1.0.7622, negative-long tempids
   resolve into implicit partitions whose eids exceed 2^53, which
   `eacl.secure-format` rejects (`:eacl.format/invalid`) on relationship
   writes. Use STRING tempids in fixtures (see
   `modules/eacl/test/eacl/bench/recursive_fixture.cljc`). The explorer fixture
   still uses negative-longs — a live finding for group 9 (`backend-unification`).
5. **Delegating to agents works well here** for large mechanical passes
   (re-goldening test suites after a semantics change, sweeping a deleted
   symbol). Give them: the exact new contract, the fresh-nREPL discipline
   above, "preserve every data/soundness assertion, re-golden only what the
   semantics changed," and an explicit "STOP and report if you find a real src
   bug rather than patching src." They must run a fresh JVM at the end.
6. **Verification EDN files are ratchets, not code.** Op-count envelopes
   (`formal/verification/recursive-op-count-envelopes.edn`) and latency
   baselines (`explorer-v8-recursive-performance.edn`) record *current truth*;
   each fix tightens the relevant number. Don't loosen a bound to make a test
   pass — that inverts the whole gate philosophy.

## Done (commits, newest first at time of writing)

- `8a3377b` 11.3 partial — parser fn returns nil instead of printing
- `e71c5ce` 11.3 partial — hot-path schema warning deduped + `*schema-warning-reporter*`
- `d915cb2` **6.1–6.3+6.5** — dependency-scoped cursor validity (the big group-6 win)
- `9b81320` 11.1 partial — deleted dead `watermark` ns
- `c1aabfc` — recorded post-group-5 gate medians + keyset raw-page trade note
- `bf551b9` **5.2–5.8** — keyset recursive pagination (fixes V4 skip/dup)
- `42918cd` 5.1 — sorted canonical denotations
- `5a9a1bd` **4.1–4.4** — kernel-boundary phase 1 (host marshalling)
- `8f7b4e6` 3.4 — post-group-3 truth + ratchets
- `0b7e7d1` 3.3 — nil-store short-circuits
- `9047e70` 3.2 — request-local schema cache
- `49abb6b` 3.1 — per-adapter schema-proof memo
- `126c71a` **2.1–2.4** — wedge-free single flight (fixes the deadlock)
- (group 1: `1a7fb5e` 1.4, `e9cf7e8` 1.6, `bf28d46` 1.5, `a371466` 1.3,
  `f34c625` 1.2, `23c549c` 1.1) — gates + counters first

### What groups 1–6 delivered (the substance, not just labels)

- **Group 1 (gates first):** observer counters (`verified-kernel/*kernel-crossing-stats*`,
  `backend.v8/*backend-op-stats*`, `engine.v8/*request-shape-stats*` — the last
  split out in group 4 so acyclic routes still show ZERO recursive work);
  populated-recursion fixtures (`recursive_fixture.cljc`, star/chain/mixed/
  broad-union, exact count oracles); per-push op-count gates
  (`recursive_op_count_test.clj` on DataScript + Datomic raw twin) against
  ratcheted envelopes; matched-v7 latency baselines + acceptance gate
  (`recursive_performance_gate_test.clj`, wired into `formal.yml`);
  cache-maintenance invariants (`subproblem_maintenance_test.cljc`); wired the
  dormant `apalache-mutation-control` and explorer gates into CI + a
  ledger↔registry consistency test.
- **Group 2 (R3 deadlock):** `subproblem_cache.cljc` restructured to
  owner-acquires-first ("i-b" in design D-1) with body-level
  `*computation-owner*` binding; the deterministic wedge schedule
  (`single_flight_coordination_test.clj`) verifiably froze the pre-fix code and
  passes now; `:stolen-computations` metric; honest hit/wait split.
- **Group 3 (R4 raw waste):** per-adapter `:schema-proof` memo (2–3 scans → 1),
  `request-schema-cache` bound by the Datomic raw facades, nil-store
  short-circuits. Raw `can?` deep 15×→~9× v7; first-50 4.3×→2.7×.
- **Group 4 (R5 marshalling):** limits/fuel marshalled once per traversal
  (JVM + CLJS), type-name interning (JVM), empty-response interning with an
  immutability pin, host per-value walk removed for the certified validator.
- **Group 5 (R2 skip/dup — the one HIGH correctness defect):** recursive route
  now emits keyset `:lookup-eid` cursors over canonical SORTED denotations,
  sliced through the existing certified `DecideAcyclicPage` (no new Dafny).
  Probe-then-continue keeps streaming economics for page-sized results;
  larger raw results pay closure-once (documented trade + client remediation).
  O(log n) `can?` membership; counts publish denotations. Deleted 21 dead
  engine forms + the `:cursor-bound-rebase` generated op + both benchmarks.
  Fixed by construction (eids can't move), pinned by `keyset_recursion_test.clj`.
- **Group 6 (R1 whole-DB validity):** cursor continuation proofs are now
  dependency-scoped (schema stamp + per-relation stamps). Unrelated writes →
  continuation REUSE (nil `:cursor-recovery`, zero traversal work) instead of
  rebase-on-every-write; relevant writes still recover. Schema-generation check
  is unconditional (Datomic bypass removed; stamp is the real mutation
  identity). Verified decisions get computed inputs (expired/scope-mismatch
  tokens rejected AT the kernel). Defaulted-key startup warning + GCM rotation
  docs.

## In-flight

- **Group 7 (answer-cache fold-in, R6)** was delegated to a background agent
  (started ~19:1x). It folds completed answers into the `SubproblemStore` as an
  `:answer` tier (weighted, LRU, oversized-rejecting) and deletes
  `bounded-assoc`/`admit-entry?`/the dead `local-store` from `cache.cljc`.
  **NEXT SESSION: first `git status`** — if the agent left uncommitted changes
  in `cache.cljc`/`subproblem_cache.cljc`/`datomic core.clj`/cache tests/
  `docs/cache.md`, cold-compile and run the group-7 verify list from
  `tasks.md` 7.x on a FRESH JVM; if green, commit as "7.1–7.4"; if broken or
  absent, re-delegate group 7 from the `answer-cache-bounding` spec. Do NOT
  assume it finished — check the tree.

## Remaining (groups 8–12) — order and pointers

Dependency order matters; several groups collide on shared files (docs/cache.md,
datascript/core, cache tests) so run them sequentially, not in parallel.

1. **Group 8 — managed certification + DataScript default flip (R9).** Spec:
   `specs/managed-reuse-certification/spec.md`; design D-5. The verification
   wave (A3) already decided: flip DataScript default to
   `:coherence-authority :unknown` (exact code changes are in the
   `docs/reports` review's Part II §13 and the A3 verdict — the stale-ALLOW
   repro must become a passing regression). Fix the stale "denotations
   disabled" docs (they're live). Add `:managed` to the randomized differential
   oracles (currently they run `:unknown` only, so the managed tier is
   untested). Add the dependency-closure completeness assert to
   `compile-recursive-plan`. **Must land after group 7** (shares cache docs +
   differential test files).
2. **Group 9 — backend de-fork (R7).** Spec: `backend-unification`. Write the
   unified filter-validator + error-contract tests FIRST (red on current
   DS/DH: nil type/relation anchors currently wildcard instead of throwing —
   the corrected V7 semantics). Then collapse the ~900-line DS/DH orchestration
   fork into core over the 21-op SPI; move Datomic's private relationship-page
   reimpl onto `eacl.engine.relationships`; one token-key option family. Also
   fix the explorer fixture's negative-long tempids (gotcha #4). Biggest
   mechanical group — good candidate for careful agent delegation.
3. **Group 10 — CLJS production engine (R8).** Spec: `cljs-production-engine`;
   design D-8. Promote the handwritten CLJC oracle engine (currently in
   `formal/smoke/`) to the CLJS production kernel via the `:cljs` kernel-default
   branch, keeping the generated JS kernel as the differential oracle. Add a CI
   `:advanced` build (broken today: zero externs, 452 access sites) and an
   absolute ns/result ceiling gate. The recorded fallback if one-engine-
   everywhere is mandated: widen `{:nativeType}` + replace the Proxy rope +
   emit ESM/externs.
4. **Group 11 — trusted-surface hygiene (R10), remainder.** Done: watermark
   deleted (11.1 partial), hot-path warning + parser print (11.3 partial).
   REMAINING: delete the dead authenticated-envelope completed-cache path +
   `:shared-cache-store`/`:lookup-cache-store` options + zed-v2 constructors
   (`modules/eacl-datomic/src/eacl/datomic/consistency.clj`: `zed-token`/
   `token-data`/`token-revision` and their exclusively-dead helpers — the LIVE
   fns to KEEP are `derive-signing-key`, `revision-checkpoints`,
   `checkpoint-values`, `observe!`, `revision-at-least-seconds-ago`, and the
   shared crypto `hmac-sha-256`/`utf8-bytes`/`signing-algorithm`/
   `signing-key-domain`; delete `consistency_test.clj`'s zed-token tests) +
   relay `:path-frontiers` branch + `:latest-result` kind. 11.2:
   `*warn-on-reflection*` in CI + hint the sites (confirmed warnings in
   `datomic/backend.clj:69`, `datomic/consistency.clj:151,180` (die with zed-v2),
   `datomic/core.clj` lock-field refs ~1304/2150+ and `nextBytes` ~80). 11.4
   is the Dafny cleanup pass (may trail indefinitely — dead generated ops are
   never invoked): delete the ordinal rebase family + backward-render mode +
   `AfterCursor` arm, retarget/delete `Pagination.dfy`, update the assurance
   matrix, regenerate kernels/vectors/manifests.
5. **Group 12 — batched scan protocol (D-9 phase 2), CONDITIONAL.** Only if the
   populated-recursion latency gate still fails 2.0× after groups 3–5 (12.1 is
   the trigger evaluation — record the decision either way in the gate EDN).
   The recorded medians show counts/deep-chain checks remain crossing-dominated
   (~3.7–6× v7), so the trigger likely fires. This is 6–10 weeks of Dafny proof
   work (coverage-invariant generalization via the ghost-view trick); it's the
   only remaining architectural lever and is explicitly gated on measured need.

## Deferred with cause (not remaining work — decisions)

- **6.4 (AEAD portable codec):** deferred. Sync AES-GCM on CLJS isn't
  responsibly implementable here (WebCrypto is Promise-only; no vetted sync
  GCM). Recorded in design.md D-4 status note + Open Questions. Portable
  cursors stay HMAC-authenticated (not encrypted); the split is documented.
- **Managed-by-default:** stays off until the group-8 randomized oracle soaks.
- **Endpoint-local (per-tuple) stamps, adaptive sizing:** design Non-Goals;
  follow-up changes.

## Current perf picture (raw Datomic, matched-v7, `explorer-v8-recursive-performance.edn`)

Post-group-5 (fresh-JVM gate): star-2k first-50 pays the keyset closure per raw
page (documented trade — client walks amortize; small raw results keep
early-stop); counts ~3.7×, deep-chain checks ~5–7×, all `:known-regression`
(gate asserts completion, doesn't fail). v7 stack-overflows on chain-10k deep
`can?` where v8 completes (recorded robustness win). The gate flips ops to
`:enforced` as groups tighten; group 12 (if triggered) is what reaches ≤2.0×
on the crossing-dominated ops.

## Verify-everything command set

Fresh JVM, then the correctness-critical suites (all must be 0F/0E):
`eacl.verified-kernel-test eacl.backend.v8-test eacl.single-flight-coordination-test
eacl.subproblem-cache-test eacl.cache-test eacl.datascript.recursive-op-count-test
eacl.datomic.raw-op-count-test eacl.datomic.recursive-cache-test
eacl.datascript.keyset-recursion-test eacl.relay-test eacl.datascript.contract-test
eacl.datomic.contract-test eacl.datascript.consistency-v3-test
eacl.datomic.consistency-v3-test`. Plus CLJS: `clojure -M:datascript-cljs-test`
then `node target/datascript-cljs-test.js` (failures=0). Plus the closure
ledger check and (heavy, ~1 GiB JVM) the recursive + explorer gates in
`formal.yml`.
