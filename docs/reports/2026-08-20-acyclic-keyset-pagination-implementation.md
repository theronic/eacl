# Acyclic Keyset Pagination — Implementation Report

**Date:** 2026-08-20
**Change:** `openspec/changes/acyclic-keyset-pagination`
**Branch:** `agent/acyclic-keyset-pagination` (base `main` @ 9d5f67b)
**Status:** merged to `main` via [PR #139](https://github.com/theronic/eacl/pull/139)
(merge commit 17b94a2). One post-merge CI incident, fixed by
[PR #140](https://github.com/theronic/eacl/pull/140) — see §7.

---

## 1. Summary

Acyclic sealed plans now paginate by **keyset in least-derivation-path
order** instead of replaying the stable-discovery reducer from ordinal
zero (or resuming a Θ(ordinal) continuation checkpoint). Cursors carry
per-level derivation coordinates and are fully self-contained: no
server-side continuation state, no cache dependency, multi-node safe at
a pinned basis. Recursive plans keep first-discovery order and
checkpoints; the mode is sealed per-plan into the fingerprint (order
ABI v2), so a cursor can never be replayed under the wrong order.

Headline numbers (serialized fresh-JVM A/B vs `main` @ 9d5f67b, 20k-doc
arrow fixture, medians of 3 fixtures/side, final post-review state):

| Metric | main | branch | Δ |
|---|---|---|---|
| cache-off 100×50 walk, total | 910 ms | 156 ms | 5.8× |
| cache-off deep page (page 100) | 16.1 ms | 1.32 ms | flat in ordinal |
| cache-on warm 100×50 walk | 140.2 ms | 161.3 ms | 1.15× (≤1.5× gate; 3rd run at parity) |
| bare `:last 50` window | 73.0 ms | 1.32 ms | 55× |
| explorer 10k :page work | — | 92–98 advanced-datoms/page | envelope 34/144/8 |

The cache-off pathology this change targets — demo latency growing with
the page ordinal (O(k²) total replay) — is gone: the deep page is
*cheaper* than page 1 (which still pays plan compile).

## 2. Problem

Root-caused on demo.eacl.dev/datahike with caching disabled: page k of
a lookup replayed discovery through all k·n prior emissions, so
per-page latency grew linearly and a full walk was O(k²). With caching
enabled, continuation checkpoints hid the replay but each checkpoint's
admitted set is Θ(ordinal) (~96 B/entry → ~1.9 MB at 20k), lives
in-process only, and made cursors cache-dependent — a cache miss (or a
different node) fell back to full replay.

## 3. Formal models (all green before any engine routing)

Three new Dafny leaves under `formal/stable-discovery/`, registered in
`verify-fast.sh`; the exact-obligation pin moved to **631** and the gate
wall ceiling was honestly raised 10 → 12 s:

- **`LeastPathOrder.dfy`** (33 obligations): per-scan derivation
  coordinates over an acyclic `StableReducer.Program`; strict total
  lexicographic order; existence/uniqueness of the least path per
  derivable entity; the order is a pure function of (program, tuples).
- **`LeastPathEnumeration.dfy`** (46): the ordered DFS with the
  smaller-witness emission filter emits exactly the reachable
  denotation (bridged to `ReducerCompleteness`), exactly once per
  entity, in ascending least-path order; pruning repeated interior
  states preserves the sequence; leaf-level merge of ascending streams
  stays ascending and duplicate-free.
- **`LeastPathResume.dfy`** (11): seeking every level strictly past a
  boundary equals the suffix of the full enumeration (`:after`);
  descending iteration emits each entity at the same position
  (`:last`/`:before` without `:complete-denotation`).

Witness decomposition (a strictly-smaller path exists ⇔ earlier-rule ∨
same-rule-smaller-eid clauses, each decided by a min-side interleaved
intersection) builds on `BidirectionalArrowIntersection.dfy`'s
`DecideEqualsArmAnswer`.

Dafny practicalities that cost time: `witness` is a reserved word;
biconditional-over-existential postconditions do not survive branch
joins (split into directional lemmas); mixed-length `decreases` tuples
need the equal-prefix-longer convention.

## 4. Implementation shape

- **`eacl.engine.sealed-plan`**: order contract bumped to ABI v2;
  plans gain `:order-mode` (`:least-path` for acyclic roots,
  `:first-discovery` for recursive ones, decided by Kahn) **inside the
  canonical digest**, so the mode is part of the fingerprint a cursor
  pins.
- **`eacl.engine.least-path`** (new, cljc): resumable nested ordered
  DFS — full per-scan coordinates `[rule-ordinal, eid…]`, one active
  scan per level, per-level witness pruning, chunked scans with a
  cut-point before every adapter command, reducer-equivalent typed
  budgets, direction-aware fetch seam (`adapter-fetch-fn` — the
  reducer's own seam hardcodes `:asc`), request-shared witness-child
  memo, min-side `least-common` intersection.
- **`eacl.engine.v8`**: `stable-lookup-page` dispatches on the sealed
  `:order-mode`; the least-path route maps `:after`/`:before`/`:last`
  to per-level seeks, reverses descending emissions to canonical order,
  reports its work to `*recursive-traversal-stats*`
  (emissions → `:derived-grants`, commands → `:advanced-datoms`, scan
  opens → `:stream-opens`), and thunks the continuation-cache context
  so the keyset route never builds it.
- **Cursors**: `:least-path-edge {kind version order-abi fingerprint
  traversal coords}` — relay passes coords through (portable envelope
  is the confidential `eacl_c5_` AES-CTR-HMAC envelope; Datomic's AES-GCM
  token remains confidential);
  wrong-fingerprint or wrong-arity cursors fail typed through the
  existing envelope. Pre-release stance per project decision: no
  legacy-cursor migration or rejection machinery beyond the typed
  failure.
- **No checkpoints for acyclic plans**: continuation checkpoints remain
  only for recursive (first-discovery) plans.

Verification at the merged state: formal gate 631 obligations green;
full CLJ battery **700 tests / 29,827 assertions / 0 failures** (fresh
JVM); DataScript CLJS **203 / 7,431**; engine property harness green
(naive-oracle order + coordinate equality, reducer set-equality,
resume-from-every-boundary, ascending/descending agreement,
work-bounded stream opens, typed budgets); client round-trips on both
Datomic and DataScript clients (flat cache-off op-stats, `:last` /
`:before` under demand, lookup-subjects walks).

## 5. Adversarial review findings (fixed pre-merge, commit d3844e7)

An oracle-based review after the first green implementation found one
real correctness bug and two structural perf problems:

1. **Order bug (correctness).** The evaluator walked each node's arms
   in the plan's (rank, ordinal) list order while stamping *sealed
   ordinals* into coordinates. Wherever those orders diverge, emissions
   left lexicographic coordinate order and the witness "earlier" domain
   disagreed with `compare-coords`. The shipped 3-level harness
   *cannot* express the divergence; a 4-level dual-arrow oracle fixture
   produced 46 random-seed failures. `design.md` D1 had specified the
   wrong order — against the Dafny `Lex`, the oracle, and the ABI docs.
   Fixed: arm traversal is ordinal-ordered everywhere coordinates are
   involved; regression test pins emission order to sealed ordinals.
   Lesson: the design doc was wrong while the Dafny was right — the
   implementation had followed the doc.
2. **Witness-child re-enumeration (perf).** Every emission's
   arrow-permission witness re-walked the target closure: 122,008
   commands for a page of 10 over a 10k-group arm. Fixed with the
   request-shared witness-children memo promised in task 3.2 plus a
   min-side least-common intersection: 20,418 commands (6×; floor =
   main walk + one shared child walk).
3. **Eager continuation context (perf).** v8 forced the
   continuation-cache thunk before dispatching on `:order-mode`, so
   every cache-on acyclic page paid canonicalization + proof-frame
   resolution + ~5 backend reads for state the keyset route never
   reads. Fixed by thunking end to end; cache-on overhead fell from
   1.27× to 1.15× (third run at parity).

Plus typed cursor-arity errors (`check-arity!`) and honest
documentation of the plaintext-coords disclosure on portable envelopes.

## 6. Two observation-basis recalibrations (physical work unchanged)

Wiring the evaluator's own counters into the observer stats changed
what tests *observe*, not what the engine *does*
(`:adapter-attempts` identical before/after):

- The explorer `:page` work envelope was re-recorded
  (finally 34/144/8 after the witness-memo fix).
- The cache-differential adjacent-page ±10 flatness assertion was
  re-anchored to a 4× early-page ceiling over the whole walk: in
  least-path order, per-page cost is *content*-bounded (cheap
  derivations emit first), so adjacent windows legitimately differ by
  more than a constant.

## 7. Post-merge CI incident (fixed)

`main` went red on the `dafny-and-generated-boundaries` job at the
merge commit: `bin/formal source-closure` failed because the
post-review commits added four defs that enter the public decision
closure (`check-arity!`, `least-common`, `shared-child-pull` in
`eacl.engine.least-path`; `report-least-path-run!` in
`eacl.engine.v8`) without regenerating
`formal/verification/public-source-closure.json` — the ledger was last
regenerated four commits earlier. It did not block the merge because
the **`pull_request` workflow run skips that job** (it runs on `push`
events); the PR looked green while the branch-push run had already
failed.

Fix: [PR #140](https://github.com/theronic/eacl/pull/140)
(`agent/source-closure-refresh`) regenerates the ledger — 61 roots
(unchanged, matches `backend-dispatch.edn`), 1409 → 1413 definitions —
with the delta reviewed def-by-def against the shipped commits. No
`.dfy` changed after the gate last ran green, so only the ledger step
was affected.

Two durable lessons:

- **Run `bin/formal source-closure` (or the full `bin/formal`
  sequence) after any commit that adds/removes defs**, not only after
  the commit that first touches a namespace. The Clojure battery does
  not cover this Node-side check.
- **The `pull_request` event does not run the dafny job.** A green PR
  page is not evidence that job passed; check the branch `push` run
  before merging.

## 8. Remaining work

- **7.4 Post-merge demo verification** (the only open task in the
  change): build `8.0.0-SNAPSHOT` with Java 26 classes and
  `:local-repo "/Users/petrus/.m2/repository"`, run
  `formal/smoke/java/run` first, restart the datahike demo and the
  eacl-datomic-solidjs server (Datomic transactor: port 4380, **not**
  the 4334 default), and confirm cache-off pagination is flat
  end-to-end in the UI. All demo JVMs are currently **down** — the
  serialized bench protocol killed them (transactor included).
  PR #140 should merge first so `main` is green.
- **8.6 follow-ups (recorded, deliberately not spun off)** — whether
  these become GitHub issues or openspec changes is an operator
  decision (public repo):
  (a) plaintext coords disclosure on portable envelopes (encrypt vs
  externalize is a threat-model call);
  (b) cache-on acyclic walks still run the full answer-cache pipeline
  per page and mint near-unreusable per-bound entries (auto-bypass vs
  caller `:cache? false`);
  (c) Datahike as-of wrapper materializes+sorts the whole endpoint
  segment per command (~4,000× measured, pre-existing, multiplied by
  probe-heavy pages);
  (d) Datahike hitchhiker-tree descending `rslice` is O(database) if
  that index config is ever reachable (protocol-incompatible in the
  pinned build);
  (e) backend `guard-scan!` realizes unbounded scans when guards are
  enabled;
  (f) the datascript module's isolated classpath is broken
  (persistent-sorted-set 0.3.0 lacks `CurrentCache`; loads only under
  the aggregate root where datahike's 0.4.137 shadows it).
- **Non-blockers recorded elsewhere**: DataScript explorer COUNT
  latency ceilings fail on `origin/main` too on this host (re-baseline
  on an idle matched host); stale `:production-map` names in
  `execution-contract.edn`; witness constants unmeasured on
  Datahike-S3 tiers (the gate covers the bound, not the constant).

## 9. Operational lessons (beyond §7's)

- **Certify on a fresh JVM only.** A warm REPL `:reload` reported `:ok`
  through a delimiter error a fresh JVM rejected. Worse: a *failed*
  `:reload` leaves a partial namespace (defs before the error updated,
  after it stale), and piping eval output through `grep` swallowed the
  compile error — hours of "verification" ran stale code. Run the bare
  `(require … :reload)` alone and read its output before trusting
  anything after it.
- **Never `:reload-all` in a shared REPL** — it redefines `eacl.core`
  protocols and orphans live records ("No implementation of
  :write-schema!").
- **Serialize A/B benchmarks on fresh JVMs** with every other JVM
  killed; parallel or warm-JVM comparisons contaminated results twice.
- **Oracle fixtures must be at least as deep as the divergence you are
  hunting**: the order bug was invisible at 3 levels and instant at 4
  with dual arrows.

## 10. State at handover

- `main` @ 17b94a2 contains the full change; Tests workflow green;
  Formal verification red **only** on the stale ledger, fixed by
  PR #140 (pending merge).
- Working checkouts live under the session scratchpad
  (`…/scratchpad/eacl-proposal` on `agent/source-closure-refresh`,
  `…/scratchpad/eacl-main` at the 9d5f67b baseline); everything of
  value is committed and pushed.
- No nREPLs, demo servers, or transactors are running on this host.
