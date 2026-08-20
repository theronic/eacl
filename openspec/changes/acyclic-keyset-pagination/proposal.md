# Proposal: Acyclic Keyset Pagination (Least-Derivation-Path Order)

## Why

Stable-discovery pagination made every page resume depend on server-side
state: the cursor is only an authenticated `(ordinal, boundary-eid)` edge,
and resuming it soundly requires the traversal's history-free checkpoint —
whose dominant component, the admitted set, grows Θ(ordinal) (measured:
~96 bytes × ordinal; 1.9 MB at ordinal 20,000). Without a retained
checkpoint (per-request `:cache? false`, checkpoint eviction, process
restart, or a page landing on a different node) the engine must replay the
entire logical prefix, so a paginated walk degrades from O(n) to O(n²) —
measured live on demo.eacl.dev/datahike at page size 1,000: 69 → 121 → 175
→ 232 → 287 → 475 ms per page with the cache off, versus a flat 2.8 ms
with it on. Acyclic plans — the overwhelmingly common schema shape — do
not need any of that machinery: their denotation is a finite union of
index ranges, and EACL's pre-discovery engine paginated them by keyset.
The pre-discovery keyset engine was retired for a real reason (recursive
paths re-derived their closure on every page: the recorded 76–1,034×
first-page regressions), but that reason does not apply to acyclic roots.

## What Changes

- **Per-plan order regime.** Sealed plans already compute `:recursive?`
  (Kahn's peel over the reachable rule graph). Acyclic root plans adopt a
  new public order — **least-derivation-path order** (canonical,
  history-free, keyset-resumable; see design.md) — while recursive plans
  retain stable first-discovery order, checkpoints, and governed replay
  unchanged. An acyclic root's entire reachable program is acyclic by
  construction, so the two regimes never mix inside one page.
- **BREAKING (versioned): order ABI v2 for acyclic plans.** The public
  result order of `lookup-resources`/`lookup-subjects` on acyclic roots
  changes from first-discovery order to least-derivation-path order.
  `:recursive?` (today deliberately excluded from the plan digest) enters
  the fingerprint together with the per-plan order mode, so every acyclic
  plan re-fingerprints; outstanding cursors fail typed
  (`:eacl.pagination/invalid-cursor` on fingerprint mismatch), never
  silently re-anchor. Recursive-plan cursors remain valid.
- **Constant-size self-contained cursors for acyclic plans.** The cursor
  payload becomes the boundary's derivation path — at most one
  `(rule-ordinal, eid)` pair per plan level (schema depth, typically 2–4)
  — instead of an ordinal that only a checkpoint or replay can interpret.
  Resume is a per-level index seek: O(schema depth) reads, independent of
  page ordinal, with no server-side state. This holds on both Datomic
  (peer segment reads) and Datahike (konserve/S3 with the LMDB tier):
  the enumeration visits one index stream at a time in derivation order,
  preserving the stream locality that a global-eid k-way merge would
  destroy (a merge needs every stream's head per stateless page — on the
  demo shape ~400 cold konserve reads before the first row; rejected in
  design.md).
- **Duplicate suppression without history.** An entity is emitted exactly
  at its lexicographically least derivation path; at emission time the
  engine checks for a smaller witness with bounded point probes (the
  bidirectional-arrow machinery), so no admitted set, no checkpoint, and
  no replay are ever required for correctness.
- **`:last`/`:before` on acyclic roots without `:complete-denotation`.**
  Descending iteration walks greatest-path order emitting each entity at
  its least path via the same witness check; reverse seeks replace the
  exhaust-then-window run.
- **Exact acyclic counts leave the reducer.** `count-resources`/
  `count-subjects` on acyclic roots run the same least-path enumeration
  (which is duplicate-free by construction) at index-scan constants,
  behind the same denotation-equivalence proof — discharging the standing
  "order-insensitive count needs an independent proof" caveat for the
  acyclic class.
- **Formal-first.** New Dafny leaves prove: least-path order is total and
  deterministic; the enumeration emits exactly the reverse/forward
  denotation, once per entity; seek-from-cursor equals the suffix; and
  descending agrees with ascending on the emission position. Registered in
  `verify-fast.sh` with an updated obligation pin; the existing
  discovery-order leaves are untouched.
- Point checks (`can?`) and all recursive-plan behavior are explicitly
  unchanged.

## Capabilities

### New Capabilities

- `acyclic-keyset-pagination`: least-derivation-path public order,
  self-contained constant-size cursors, descending windows, and exact
  counts for acyclic plans, certified equal to the existing denotation.

### Modified Capabilities

- `stable-discovery-enumeration`: the "one public order for every plan"
  requirement is narrowed to recursive plans; acyclic plans are governed
  by the new capability, selected statically per sealed plan, with the
  order mode sealed into the fingerprint.

## Impact

- **Engine**: new acyclic page/count evaluator in
  `modules/eacl/src/eacl/engine/` (resumable ordered DFS with
  least-witness suppression); routing split in `engine/v8.cljc` on
  `:recursive?`; `sealed_plan.cljc` folds `{:order-mode, :recursive?}`
  into the digest (fingerprint change for all plans — one re-seal per
  schema generation).
- **Cursors/relay**: acyclic cursor payload becomes the derivation path;
  authentication envelope unchanged; continuation checkpoints are simply
  not consulted for acyclic plans (the store remains for recursive ones).
- **Backends**: no new adapter operations — the enumeration uses the
  existing certified ordered scans and exact-bound probes on Datomic,
  Datahike, and DataScript alike; per-backend behavior differs only in
  read cost, which the derivation-order locality is chosen to respect.
- **Formal**: new leaves in `formal/stable-discovery/`; obligation pin and
  batch assignment updated; ASSURANCE_COVERAGE and execution-contract
  evidence extended; acyclic frozen page-order baselines regenerate
  (point-check and count expectations are order-independent and stand).
- **Public contract**: documented order change for acyclic roots
  (ascending by derivation coordinates ≈ creation-order-flavored, as in
  pre-discovery EACL); `:cache? false` pagination becomes O(page) instead
  of O(ordinal) on acyclic roots; cursor size becomes schema-depth-bounded.
