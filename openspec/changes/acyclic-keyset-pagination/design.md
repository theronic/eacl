# Design: Acyclic Keyset Pagination

> Revision 2. The first revision had four defects found in adversarial
> review: (1) intermediate iteration for arrow-to-permission arms assumed
> a sorted closure it could not produce (fixed by per-level bounded
> sub-arm merges); (2) the same-arm witness check was one-sided and
> O(entity fan-in) (fixed: min-side interleaved intersections);
> (3) the count route overclaimed — witness probes cost more per value
> than the reducer's admitted set, and counts need no cursors (counts cut
> from scope); (4) coordinates were described per-level when they are
> per-scan. Cursor-compat ceremony is dropped: EACL is pre-release
> (8.0.0-SNAPSHOT); outstanding cursors simply fail typed on fingerprint
> mismatch, which costs nothing.

## Context

A sealed plan is a finite positive rule program. For an **acyclic** root
(no cycle anywhere in the reachable rule graph — `:recursive?` from
Kahn's peel in `sealed_plan.cljc`), every derivation of a result is a
finite path through the plan whose node sequence never repeats, so its
length is bounded by the plan's node count (a schema constant, typically
2–4 levels).

Today's stable-discovery order is *first-discovery* order: an entity's
position depends on which derivation the traversal reached first, which
depends on the whole traversal history — hence the Θ(ordinal) admitted
set in every checkpoint (measured ~96 B × ordinal; 1.9 MB at ordinal
20k), and prefix replay whenever no checkpoint is available: the
measured O(n²) cache-off walk (69→475 ms over six 1,000-row pages on
demo.eacl.dev/datahike) and node-hop replay on any multi-process
deployment.

Backends of record: Datomic peer (cheap warm segment scans) and Datahike
on konserve (S3 + warm LMDB tier), where *opening* an index stream is the
expensive unit (~37.7 ms per cold S3 miss recorded; ~zero warm).
DataScript follows Datahike's shape. Any acceptable design must bound
the number of index streams touched per page by a schema constant on all
three.

## Goals / Non-Goals

**Goals:**

- Self-contained cursors for acyclic plans: resume cost independent of
  page ordinal; zero server-side state required for correctness.
- Streams touched per page bounded by the schema (arms × depth), never
  by closure size or result ordinal — cold-S3 Datahike behaves like the
  current discovery DFS.
- One certified public order that is a pure function of
  (sealed plan, snapshot) — never of traversal history.
- Exact descending windows (`:last`/`:before`) for acyclic roots without
  `:complete-denotation`.
- Recursive plans byte-for-byte unchanged.

**Non-Goals:**

- Counts. Exact counts are not paginated, so memory-based dedup is
  legitimate for them and witness probes would be strictly slower than
  the reducer's admitted set; a set-based count fast path is a separate,
  simpler change.
- Changing recursive-plan pagination, checkpoints, or replay.
- Changing `can?`/point checks.
- Cursor migration or dual-ABI compatibility: pre-release, outstanding
  cursors fail typed on fingerprint mismatch and clients restart the
  walk. No proofing or ceremony is spent on rejected-cursor paths beyond
  what the existing envelope already does.
- Cross-basis cursor portability (cursors stay pinned to the exact
  basis, as today).

## Decisions

### D1 — Public order: lexicographic least-derivation-path over per-scan coordinates

A derivation of entity `e` is the finite choice sequence the plan
traversal makes: at each step, a sealed rule choice and, for each scan
that rule performs, the eid it binds. Its **coordinates** are that
interleaved sequence:

    [rule-ordinal₁, eid₁ᵃ, (eid₁ᵇ)?, rule-ordinal₂, ...]

- rule ordinals compare by the sealed `(rank, canonical-ordinal)`
  alternative order (already fingerprinted);
- eids compare ascending;
- a self-permission step contributes only its ordinal (no scan); an
  arrow-relation step contributes its via eid and its target eid; the
  plan determines each step's arity, so the flat sequence is
  unambiguous.

Coordinates are compared lexicographically. Distinct derivations have
distinct coordinates (the coordinates *are* the derivation), the
derivation set of an acyclic program is finite, so every derivable
entity has a unique **least derivation path**. The public order is
ascending least-path; each entity is emitted exactly once, at its least
path. This is a pure function of (plan, snapshot).

The realization is an ordered DFS: at each level, alternatives are taken
in sealed rule order and scan values in ascending eid order, so emission
order equals coordinate order without any sorting.

### D2 — Iterating a closure in eid order: per-level bounded sub-arm merges

An arrow-to-**relation** step iterates one index scan — already
ascending. An arrow-to-**permission** step must iterate the
intermediates of a sub-*denotation* (e.g. "accounts the subject can
admin" = `owner + parent->admin + platform->super_admin`) in ascending
eid. That set is produced by **merging the sub-arms' own ascending
streams** (each sub-arm bottoms out in index scans; nested
arrow-to-permission sub-arms merge recursively). Properties:

- The number of open streams per page is bounded by the plan's total
  alternative count times depth — a schema constant (≈10–20 realistic),
  NOT the closure size. This is the decisive difference from the
  rejected global-eid merge, whose stateless resume realizes one stream
  per *intermediate* (~400 cold konserve descents on the demo shape).
- The merge output is ascending, so intermediate-level duplicates
  (an account derivable as both owned and platform-admin'd) collapse
  for free at the merge front — no witness work at intermediate level.
- A merge resumes from a **single eid bound**: every sub-stream seeks
  strictly past it. Cursors therefore carry one eid per scan step, never
  per-sub-stream state.

### D3 — Entity-level duplicate suppression by min-side witness probes

When the DFS reaches entity `e` at path `p`, it emits `e` iff no
strictly smaller path derives `e`. Decomposed level-wise, a smaller path
either (a) takes an earlier rule at some level, or (b) the same rule
with a smaller eid at some scan. Both are decided by bounded reads on
the existing certified operations — and every check is an
**interleaved min-side intersection** (never a one-sided scan):

- (a) *earlier arm derives e*: for a relation arm, one exact-bound
  probe; for an arrow arm, the bidirectional intersection of e's via-set
  with the subject-side closure (`BidirectionalArrowIntersection.dfy`),
  recursing one level for deeper arms — depth-bounded.
- (b) *same arm, smaller intermediate*: ∃ i′ < i with (i′, via, e) and
  i′ in the arm's closure — the interleaved intersection of e's
  via-prefix below i (entity fan-in side) with the closure-below-i
  (subject side), consuming whichever side is smaller. A doc shared
  with 10,000 orgs whose granting intermediate is late costs the
  *subject's* side, not the fan-in.

Cost per emitted value: O(depth × earlier alternatives) bounded
intersections — typically 2–15 point reads. These scans hit the
projection cache when caching is on, so warm pages amortize them; with
caching off they are real reads but **flat per page**, which is the
contract being bought. Duplicate occurrences pay the same bounded check
and are skipped — stateless, unlike the admitted set, and bounded,
unlike replay.

### D4 — Cursor payload: the boundary coordinate sequence

The acyclic cursor carries the boundary result's coordinates — at most
one rule ordinal plus one or two eids per plan level, schema-bounded —
inside the existing authenticated envelope (HMAC, basis, fingerprint,
page size, direction: unchanged). Resume: seek each level strictly past
its coordinate (sub-arm merges seek all their streams past the one
bound), deepest level first, and continue the DFS. O(schema depth)
seeks, independent of ordinal, no server-side state. The 16 KB cursor
budget holds with orders of magnitude of headroom.

Fingerprint or basis mismatch fails typed exactly as the existing
envelope already specifies — nothing new is built for rejected cursors.

### D5 — Descending windows

`:last`/`:before` iterate coordinates in descending order (reverse
seeks; the adapter's `:desc` scan contract already exists) and emit each
entity only at its least path via the same witness predicate — in
descending iteration an entity's least path is the last of its paths
encountered, so ascending and descending walks agree on every emission
position by construction. The `:complete-denotation` requirement is
dropped for acyclic roots and retained for recursive ones.

### D6 — Per-plan routing sealed into the fingerprint

`seal-plan` adds `{:order-mode (:least-path | :first-discovery)
:recursive? bool}` to the plan record and INTO the canonical digest
(today `:recursive?` is deliberately excluded; that exclusion is wrong
the moment order depends on it). `order-contract` becomes
`:abi-version 2` documenting both modes. Acyclic roots seal
`:least-path`; recursive roots seal `:first-discovery` and keep the
entire stable-page/checkpoint/replay machinery. Engine routing
dispatches on the sealed mode only. Every plan re-fingerprints once;
outstanding cursors fail typed; pre-release, that is the whole
migration story.

### D7 — Backend neutrality

The evaluator consumes only the existing certified operations —
ordered forward/reverse scans with exclusive bounds through the routed
fetch-fn (classification, retry, telemetry, budgets, per-command
cut-point) — identically on Datomic, Datahike, and DataScript. No new
adapter operations, no backend-specific code paths. The design choices
that differ per backend are read-locality properties, and D2/D3 were
chosen so the worst page touches a schema-bounded number of streams
plus cache-friendly point probes.

### F — Formal plan (models green before any engine routing)

New leaves in `formal/stable-discovery/`, registered in
`verify-fast.sh` with an updated obligation pin:

- **F1 `LeastPathOrder.dfy`** — per-scan coordinates; strict total
  lexicographic order on the finite derivation set of an acyclic
  program; existence/uniqueness of least paths; order is a pure
  function of (program, tuples).
- **F2 `LeastPathEnumeration.dfy`** — the ordered DFS with per-level
  sub-arm merges and the smaller-witness filter emits exactly the
  reachable denotation (bridging `ReducerCompleteness` /
  `EaclForwardGrounding`), exactly once per entity, in ascending
  least-path order; the per-level merge of ascending duplicate-free
  streams is ascending and duplicate-free.
- **F3 `LeastPathResume.dfy`** — seeking every scan strictly past a
  boundary coordinate sequence equals the suffix of the full
  enumeration; descending emission positions equal ascending ones.
- **F4** — witness equivalence: the level-wise decomposition of
  "a strictly smaller path derives e" into earlier-rule and
  smaller-eid clauses, each decided by an interleaved min-side
  intersection, equals the order-theoretic predicate — built on
  `BidirectionalArrowIntersection.dfy`'s `DecideEqualsArmAnswer` and
  extending it per level.

Discovery-order leaves untouched. Executable evidence: randomized
result-set differentials against the stable-discovery reducer
(order-insensitive equality), order differentials against a
materialize-sort-dedup oracle, resume-from-every-boundary suffix
equality, ascending/descending agreement, and duplicate-heavy overlap
fixtures — on Datomic, Datahike, and DataScript.

## Risks / Trade-offs

- **[Per-value witness cost]** Late union arms pay bounded intersections
  per emitted value (~2–15 cache-friendly point reads). With caches on,
  page throughput may be comparable to — not better than — today's
  checkpoint resume; the purchased property is *flat, stateless* cost:
  page k ≈ page 1 with caches off, across restarts and nodes, where
  today is O(k) replay. Gate both directions: stateless flatness AND a
  ceiling on cache-on regression (target ≤1.5× today's warm page).
- **[Duplicate-heavy data]** An entity derivable via many alternatives
  pays a bounded witness check per occurrence. Bounded and stateless,
  but a pathological all-arms-overlap dataset approaches probe-bound
  throughput; gate with a worst-case overlap fixture.
- **[Deep acyclic schemas]** Witness recursion is depth × alternatives;
  depth is a schema constant and the traversal budgets apply. Document
  the shape.
- **[Two evaluators]** The engine permanently carries least-path and
  first-discovery machines, contained by the static split (an acyclic
  root's whole program is acyclic, so regimes never compose) and tied to
  one denotation by F2.
- **[Order intelligibility]** Results group by derivation arm, then
  intermediate, then eid — first-granting-path flavored, like today's
  discovery order; not a global eid sort. Documented; schema edits that
  change rank re-order results (already true today).

### Rejected alternatives

- **Global ascending-eid k-way merge**: free dedup, simplest order, but
  stateless resume realizes every *intermediate's* stream head (~400
  cold konserve/S3 descents before row one on the demo shape at
  37.7 ms/miss). Fails the Datahike goal. The per-level sub-arm merge
  (D2) keeps merge width schema-bounded instead.
- **Raw-eid coordinates for arrow-to-permission intermediates without
  merges** (revision 1): assumed a sorted closure that cannot be
  produced without materialization or merging — unimplementable as
  written; superseded by D2.
- **One-sided witness scans** (revision 1): O(entity fan-in) per
  emission on shared entities; superseded by min-side intersections.
- **Witness-probe counts** (revision 1): counts need no cursors, so
  memory dedup is legitimate and cheaper; counts cut from scope.
- **Encoding continuation state in cursors**: admitted set is
  Θ(ordinal) (1.9 MB at 20k vs a 16 KB budget); rejected on arrival.
- **Compressed/probabilistic admitted sets**: false positives silently
  drop results; unsound.
- **Single-arm-only keyset fast path**: sound but covers almost no real
  schemas; superseded by least-path over all acyclic plans.
