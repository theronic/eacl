# Design: Acyclic Keyset Pagination

## Context

A sealed plan is a finite positive rule program. For an **acyclic** root
(no cycle anywhere in the reachable rule graph — `:recursive?` from Kahn's
peel in `sealed_plan.cljc`), every derivation of a result is a finite path
through the plan:

    root --rule r1--> intermediate i1 --rule r2--> ... --> entity e

where each step is either a base index scan (relation / arrow-via edge) or
a static rule edge (self-permission, arrow target). The complete
derivation is identified by its **coordinates**: the canonical rule
ordinal chosen at each level plus the eid bound at each scan step. Plan
depth bounds the coordinate count (schema property, typically 2–4).

Today's stable-discovery order is *first-discovery* order: the position of
an entity depends on which derivation the DFS reached first, which depends
on the whole traversal history — hence the Θ(ordinal) admitted set in
every checkpoint, and prefix replay whenever no checkpoint is available
(the O(n²) cache-off walk measured on demo.eacl.dev/datahike, and the
node-hop replay on any multi-process deployment).

Backends of record: Datomic peer (cheap warm segment scans) and Datahike
on konserve (S3 + warm LMDB tier), where *opening* an index stream is the
expensive unit — a hitchhiker-tree descent of konserve reads, ~37.7 ms per
cold S3 miss per the recorded measurements, ~zero warm. DataScript
follows Datahike's shape. Any acceptable design must bound the number of
streams touched per page on all three.

## Goals / Non-Goals

**Goals:**

- O(1)-in-depth, self-contained cursors for acyclic plans: resume cost
  independent of page ordinal, with zero server-side state required.
- Stream locality: a page touches one index stream at a time (plus
  bounded witness probes) — never all streams per page — so cold-S3
  Datahike behaves like the current discovery DFS, not like a k-way merge.
- One certified order that is a pure function of (plan, snapshot), never
  of traversal history.
- Exact descending windows and exact counts for acyclic plans at
  index-scan constants, under the same proof.
- Recursive plans byte-for-byte unchanged.

**Non-Goals:**

- Changing recursive-plan pagination, checkpoints, or replay.
- Changing `can?`/point checks (already order-free).
- A global ascending-eid public order (see rejected alternative).
- Cross-basis cursor portability (cursors stay pinned to the exact basis,
  as today).

## Decisions

### D1 — Public order: lexicographic least-derivation-path

For an acyclic plan, define the order key of a *derivation* as the
sequence of its coordinates, compared lexicographically:

    [(rule-ordinal_1, eid_1), (rule-ordinal_2, eid_2), ...]

with rule ordinals compared by the sealed `(rank, canonical-ordinal)`
alternative order (already fingerprinted) and eids ascending. An entity's
**position is its least derivation path**; entities are emitted in
ascending least-path order, each exactly once.

Why this and not first-discovery: least-path order is a pure function of
the plan and the snapshot — no history. Why this and not global eid
order: global order forces a k-way merge whose stateless resume must
realize every stream's head (≈400 cold konserve descents before row one
on the demo shape); least-path order is realized by an ordered DFS that
drains one stream at a time.

Descending (`:last`/`:before`) iterates derivations in *descending* path
order but still emits an entity only at its least path (same witness
check, evaluated when the entity is seen); ascending and descending
therefore agree on every emission position by construction.

### D2 — Duplicate suppression by least-witness probes, not history

When the DFS reaches entity `e` at path `p`, it emits `e` iff **no
strictly smaller path derives `e`**. The check is decided by bounded
point reads against the same certified index operations the
bidirectional point check uses (no new adapter operations):

- *Earlier root arm* `a' < p`'s arm: does arm `a'` derive `e`? One
  exact-bound probe for a relation arm; a bidirectional intersection
  (min-side bounded, `BidirectionalArrowIntersection.dfy`) for a
  two-layer arrow arm; for a deeper acyclic arrow arm, the same check one
  level down (depth-bounded recursion).
- *Same arm, earlier intermediate* `i' < i`: enumerate `e`'s via-set
  ascending from 0 (one scan, cut off at `i`), and for each candidate
  `i'` decide "subject reaches `i'`" with a point check. Via fan-in per
  entity is typically 1–3; the scan stops at the current intermediate.

Cost per emitted value: O(depth × earlier alternatives) probes —
typically 1–5 point reads, ~1–2 µs warm on Datomic, warm-LMDB-local on
Datahike. Duplicate-heavy entities pay the probes and are skipped —
bounded work per duplicate occurrence, comparable to today's admitted-set
lookup but stateless.

This is the load-bearing decision: dedup becomes a pure predicate of
`(entity, path, snapshot)`, which is what makes cursors self-contained.

### D3 — Cursor payload: the boundary derivation path

The acyclic cursor carries `[(rule-ordinal, eid), ...]` for the boundary
result — at most plan-depth pairs — inside the existing authenticated
envelope (HMAC, basis, fingerprint, page size, direction; unchanged).
Resume: seek each level's stream strictly past its coordinate, deepest
level first; continue the DFS. O(depth) seeks, independent of ordinal.
The demo's 16 KB cursor bound holds with three orders of magnitude of
headroom.

Cursor authentication semantics are unchanged: fingerprint mismatch,
basis mismatch, and malformed paths fail typed exactly as today. A
validated path whose coordinates no longer exist at the pinned basis is
`:eacl.pagination/stale-cursor`, as today.

### D4 — Per-plan routing sealed into the fingerprint

`seal-plan` adds `{:order-mode (:least-path | :first-discovery)
:recursive? bool}` to the plan record and **into the canonical digest**
(today `:recursive?` is deliberately excluded; that exclusion is wrong
the moment order depends on it). `order-contract` gains
`:abi-version 2` with both modes documented. Acyclic roots get
`:least-path`; recursive roots keep `:first-discovery` and the entire
existing stable-page/checkpoint/replay machinery. Engine routing in
`v8.cljc` dispatches on the sealed mode — never on a runtime property.

Consequences: every plan re-fingerprints once at upgrade; outstanding
acyclic *and* recursive cursors fail typed on fingerprint mismatch (a
one-time, versioned invalidation — recursive cursors could be preserved
only by keeping their fingerprint inputs byte-identical, which the
`:recursive?` digest inclusion precludes; accepting one uniform
invalidation is simpler and honest).

### D5 — Counts and last-windows ride the same enumeration

- `count-*` on acyclic roots: run the least-path enumeration to
  exhaustion counting emissions (duplicate-free by D2), honoring
  `:count-limit` with target `limit+1`. This replaces reducer exhaustion
  (~3.5 µs/value machinery) with scan+probe constants and discharges the
  documented "order-insensitive specialization requires an independent
  denotation-equivalence proof" condition — the proof is F2 below.
- `:last`/`:before` on acyclic roots: descending DFS with reverse seeks;
  the `:complete-denotation` requirement is dropped for acyclic roots and
  retained for recursive ones.

### D6 — Backend neutrality

The evaluator consumes only the existing certified operations —
`:subject->resources`/`:resource->subjects` ordered scans with exclusive
bounds, through the routed fetch-fn (classification, retry, telemetry,
budgets) — identically on Datomic, Datahike, and DataScript. The design
choice that differs per backend is *read locality*, and D1/D2 were chosen
so the worst page touches O(depth + probes) streams, which all three
backends serve well; no backend-specific code paths, no new adapter
obligations.

### F — Formal plan (model first, then implementation)

New leaves in `formal/stable-discovery/`, registered in `verify-fast.sh`
with an updated obligation pin:

- **F1 `LeastPathOrder.dfy`** — the path order is a strict total order on
  derivations of an acyclic program; least paths exist and are unique per
  derivable entity; the order is a pure function of (program, tuples).
- **F2 `LeastPathEnumeration.dfy`** — the ordered DFS with the
  least-witness filter emits exactly the reachable denotation
  (`ReducerCompleteness` supplies the reachable-set side), exactly once
  per entity, in ascending least-path order; count-by-emission equals
  denotation cardinality (licenses D5).
- **F3 `LeastPathResume.dfy`** — seeking every level strictly past a
  boundary path and continuing equals the suffix of the full enumeration
  (keyset resume soundness); descending emission positions equal
  ascending ones.
- **F4** — witness-check equivalence: the probe-decided "smaller path
  exists" predicate equals the order-theoretic one, built on
  `BidirectionalArrowIntersection.dfy`'s intersection lemmas.

The discovery-order leaf family is untouched. Executable evidence:
randomized differentials against (a) the stable-discovery reducer's
*result set* (order-insensitive equality) and (b) a naive
materialize-sort-dedup oracle for the *order*; frozen acyclic page-order
baselines regenerate once.

## Risks / Trade-offs

- **[Order ABI break]** Acyclic result order changes and all cursors
  invalidate once at upgrade → versioned `order-contract` v2; cursors
  fail typed, clients restart walks from page one. Mitigation: release
  note + the demo's stale-cursor handling already treats this correctly.
- **[Per-value probe overhead]** Late union arms pay O(earlier arms)
  probes per emitted value (~1–5 point reads). On Datomic this roughly
  matches today's reducer constant; the win there is resume asymptotics
  and statelessness, not raw page throughput. Mitigation: arm-level
  short-circuits (first arm pays zero; relation-arm probes are single
  reads); measured gates before/after per backend.
- **[Duplicate-heavy data]** An entity derivable via many alternatives
  pays its witness probes at each later occurrence. Bounded per
  occurrence and stateless, but a pathological all-arms-overlap dataset
  approaches probe-bound throughput. The count route shares the bound.
  Gate with a worst-case overlap fixture.
- **[Deep acyclic schemas]** Witness recursion is depth × alternatives;
  a deliberately deep acyclic schema (e.g. 20-level arrow chains)
  multiplies probe cost. Depth is a schema constant and the existing
  traversal budgets apply; document the shape.
- **[Cold-S3 first page]** O(depth) stream opens plus probe reads per
  page — same order as today's discovery DFS, far below the rejected
  merge's O(#streams); still nonzero on a stone-cold tier. No regression
  relative to today; documented.
- **[Two evaluators]** The engine permanently carries least-path and
  first-discovery machines. Contained by the static `:recursive?` split
  (an acyclic root's whole program is acyclic, so regimes never compose)
  and by F2's equivalence tying both to one denotation.

### Rejected alternatives

- **Global ascending-eid k-way merge**: free dedup and the simplest
  order, but stateless resume must realize every stream head — on the
  demo's super-user shape ≈400 streams, i.e. hundreds of cold konserve/S3
  descents before the first row (recorded S3 profile: 37.7 ms/miss). Fails
  the Datahike goal; rejected.
- **Encoding continuation state in cursors**: the admitted set is
  Θ(ordinal) (measured 1.9 MB at ordinal 20k against a 16 KB cursor
  budget); rejected on arrival.
- **Compressed/probabilistic admitted sets**: any false positive drops a
  result silently; unsound, rejected.
- **Single-arm-only keyset fast path**: sound but covers almost no real
  schemas (one permission in the demo schema); superseded by least-path,
  which covers every acyclic plan.
