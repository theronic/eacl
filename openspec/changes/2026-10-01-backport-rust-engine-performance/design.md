# Design

## Context

Measurements, mechanisms and code locations are in proposal.md; the budgets
are in `specs/operator-engine-performance/spec.md`. All numbers come from
the eacl-rust benchmark graph (`crates/eacl-bench`, generator mirrored in
`clj/eacl_bench/bench.clj`) on DataScript.

**Routing on #210.** An operator plan takes the delegated route when no
operator permission lies on a positive cycle, or when its recursive
components are linearly guarded:

- operator nodes run on `eacl.operator.vector-evaluator` (lookups, counts)
  or `eacl.operator.evaluator` (checks) over a delegated view;
- union-only operands go to the oracle in `engine.v8/delegated-operand-oracle`:
  `stable-route/check-many-eids` (`:batched`, one request-scoped membership
  context) or `stable-route/check-eids` (`:point`);
- candidates come from a delegated operand's own union plan or the flattened
  generator of `eacl.operator.cover-plan`, enumerated by
  `engine.v8/filtered-lookup-page` in chunks of at most 256.

Everything else, and every resource the guarded search defers, runs on the
tabled evaluator `eacl.operator.recursive` ("Route C").

**Profiles at 10⁵ on #210** (JFR, nearest-EACL-caller shares):

- broad count: `operator.evaluator/check-eids` 58% inclusive, 14% self, called
  per candidate from the vector evaluator's `:arrow-membership` branch;
- 256-check batch: `stable-route/probe-check-eids` 73% inclusive, one point
  search per demand;
- narrow lookup: `stable-route/fault-free-checker` 51% inclusive;
- union-only count: `stable-reducer/release-one` 54% inclusive;
- non-linear Route C check: `solve-bounds` 66% inclusive,
  `dependency-first-components` 23% self, `sorted-questions` 15% self;
- single write after #213 and the schema-reuse PR: response token
  (`causal-token/issue` → `secure-format/encode-authenticated`) 34%.

**Constraints.**

- Kleene pointwise fault semantics (owner-approved, implemented by its own
  PR earlier in this stack): a union absorbs on a definite true, an
  intersection on a definite false, an exclusion's left operand on a definite
  false and its right operand on a definite true; a fault never stops
  evaluation early. Every decision below preserves them.
- Certificates must be sound (`QualifiedTemporal.dfy`,
  `WitnessCertificateIsSound`): the value holds throughout the certified
  interval. Detailed lookup items must equal `check-permission`.
- State is per request (0tx builds a client per request); the per-client
  denotation tier of #203 may reuse certified values.
- Portable CLJC, identical on the JVM and in ClojureScript; no new
  dependency.
- Budgets are authored before sampling. Each new decision procedure is
  certified by a Dafny model, an executable refinement against a
  transcription of it, and registered mutation controls killed on both
  runtimes.

## Goals / Non-Goals

**Goals:** meet the budgets in the spec without changing any permissionship,
residual or fault, and with sound certificates.

**Non-goals:** storage layout changes (DataScript tuples stay as they are),
cross-client caches, and the cursor or order ABI changes that D9 lists as
owner decisions.

## Decisions

### D1. A benchmark gate first

`eacl.bench.drive-parity-test` (DataScript, `^:benchmark`) generates the
eacl-bench graph at 10⁴ and 10⁵ (10⁶ on demand), asserts the generator's
relationship count and FNV-1a checksum against the Rust values, and measures
every workload of the report. Budgets compare medians within one JVM run, so
machine load cancels:

- operator against union twin: `view` against `view_any` for the same
  subject and resource;
- batch against single check;
- cache-off walk against cache-miss walk;
- non-linear recursion against its union twin
  (`reach = viewer + (parent->reach & link->reach)` against
  `reach_union = viewer + parent->reach_union`).

The gate records work counters (adapter commands, fetched values, probes,
candidates examined) and asserts upper bounds on them where a decision
promises a work bound (D2, D7).

### D2. Cost-ordered operand evaluation

Node ids stay in canonical order, so plan fingerprints and cursors do not
change. Sealing derives, outside the fingerprint, an evaluation order per
`:any-true` and `:all-true` node: relation leaves, then arrows to relations,
then arrows and permissions that are not recursive, then recursive ones,
then operator permissions; canonical order breaks ties. The vector and scalar
operator evaluators iterate children in that order.

- Under Kleene semantics a union's and an intersection's permissionship do
  not depend on child order.
- **Certificate rule (owner decision).** Evaluation short-circuits at the
  first decisive operand in this static cost order and skips the rest. When
  both operands of a binary composition are already evaluated and decisive
  (both came from the table or a memo), the composition keeps the later of
  their deadlines, which costs nothing and removes order dependence wherever
  no extra work is involved: both decisive, the later deadline; one
  decisive, its deadline; neither, the earlier one (the Rust port's
  `qual/arena.rs` `certificate`). The deadline is a deterministic function of
  the compiled plan, the snapshot, the evaluation time and the operands the
  static order evaluates; it is sound (`QualifiedTemporal.dfy`,
  `WitnessCertificateIsSound`). It must not depend on cache state or
  request history: a decision whose certificate becomes public (a
  conditional result's residual) is re-decided from point operands with no
  request-scoped memo, as #200 already does (the Rust port's CF-03). #219
  keeps "left wins when both are decisive"; step 2 owns the change, its
  lemma in `QualifiedTemporal.dfy` and its mutation control. The release
  notes say: "a decision's deadline is a sound, implementation-defined
  bound". Campaigns compare deadlines with a widest-witness reference as a
  band: never later than the reference, never earlier than the evaluation
  time.
- `check-permission` and lookups use the same order, so detailed items still
  equal checks. `compiler-plan-compatibility` gains `:operand-order`, so a
  completed answer computed under the old order is not reused as if computed
  under the new one.

Alternatives rejected: reordering only when every skipped child is plain
(needs #209's certificate, which Kleene removes); widest-witness
certificates (a search per grant, which defeats this decision).

### D3. Set-at-a-time arrows in the vector evaluator

The `:arrow-membership` branch decides all pending candidates together:

1. read each candidate's via edges in chunks (the existing
   `resource->subjects` scan), or the subject's holdings of the via relation
   when they are fewer;
2. ask the oracle once for every distinct `(subject, intermediate)` pair, or
   dispatch relation targets as one probe batch;
3. combine per candidate in via order, stopping at the first definite true,
   with doubling batches of intermediates.

The value and certificate of each candidate equal the scalar arrow's: same
intermediates, same order, same first decisive witness.

### D4. Structural certainty from the generator

The cover reducer records, per emitted candidate, whether its first
discovery path used only plain edges (no qualifier slot on any edge, read
from the compact edge without a qualifier read). Such a candidate holds the
generator node (an exclusion's left operand, an intersection's anchor, or
the flattened union) plainly: it is passed to the vector evaluator as an
evidence witness, and only the remaining operands are decided. A candidate
first found through a qualified edge is decided as today.

### D5. Order-free set evaluation for counts

Counts need no order. For one subject, compute per plan node a sure set S
and a maybe set M under the structural abstraction (plain edge = true,
qualified edge = unknown):

| node | S | M |
|---|---|---|
| relation `p` | resources where the subject holds `p` plainly | all resources where it holds `p` |
| `a ∪ b` | `Sa ∪ Sb` | `Ma ∪ Mb` |
| `a ∩ b` | `Sa ∩ Sb` | `Ma ∩ Mb` |
| `a − b` | `Sa − Mb` | `Ma − Sb` |
| `via→q` | resources with a plain via edge to `S(q)` | resources with any via edge to `M(q)` |

Recursion is a least fixed point per component in dependency order,
semi-naive on both sets. Then `count = |S(root)| + #{x ∈ M∖S : exact(x) = Has}`,
and `:count-limit l` stops after `l + 1`. Sets are persistent sets of eids
(portable); M∖S members are decided by the batched oracle with one table.
Lookups keep their cover order and may use S as a membership oracle (D4);
pages in eid order from M are an owner decision (D9).

### D6. One table per `check-permissions` batch

Group the batch's demands by subject, permission, resource type and caveat
context. Decide each group's resources with one batched oracle evaluation
(operator plans) or one `check-many-eids` (union plans), then emit decisions
in demand order. If the grouped evaluation throws (limit, deadline,
operational error), fall back to the sequential path for the remaining
demands, which reproduces the exact failing demand index.

### D7. Bidirectional recursive membership

For an arrow into a recursive union-only permission such as
`viewer_group->member` with `member = direct + sub->member`, decide
"subject ∈ member(g)" from whichever side is smaller: top-down (the existing
search from `g`) or bottom-up (the subject's direct groups, closed upward
through the forward index of `sub`), interleaving the two in doubling steps
until one decides. The subject's upward closure is computed once per request
and shared by every candidate. Expiring edges use the leveled rule of
`LeveledMembership.dfy` on the reversed graph.

### D8. Tabled evaluator in place of Route C

Goals are `(node, entity)` pairs interned to integers per request. Entries
are Fresh, Active or Complete. Evaluation restarts on demand: evaluating a
goal is a pure function of the table that either returns a value or the
Fresh children it needs. A component completes at its leader (lowest
discovery index reached) when a pass changes no member; values accumulate
(`prior ∪ derived`) over the Kleene evidence lattice, so termination follows
from the finite lattice of values and deadlines. An exclusion reads its right
operand only when that operand's lower stratum is complete. Arrow fan-out is
demanded in doubling batches; relation probes are memoized per request. One
table serves every chunk of a lookup and every demand of a batch.

**One evaluator (owner decision).** The end state is the Rust port's: tabled
exact evaluation for point decisions, and structural certainty (D4, D5) for
enumerations, with exact decisions of uncertain candidates from the same
table. Delegation (#199), the leveled search (#200) and the guarded search
(#202) are retired once the tabled evaluator meets their budgets on the
step-1 gate and on `eacl.bench.operator-shapes-test`; the retired routes
may stay as test oracles only. Recursive checkpoints change format
(`checkpoint-version` bump); an outstanding cursor of the old format is
rejected with the typed stale-cursor error, never answered with a wrong
page, and the release notes say so. Under qualification cursors are
basis-bound, so few survive a deploy anyway.

### D9. Continuation without replay

Cache-off pages replay the cover from its first candidate. Result order stays
the cover's: undefined but deterministic (owner decision; ascending eid is
withdrawn). Acyclic paths already resume from the ordered index with keyset
cursors. Cyclic paths can yield an entity lower than one already emitted, so
their continuation needs a checkpoint of what was seen: a cache-off walk
keeps a continuation checkpoint for its cursor (a continuation record, not
an answer cache), and a page resumes from it instead of replaying from the
first candidate. A cursor whose checkpoint is gone falls back to replay,
which stays correct.

### D10. Constant factors

- `secure-format`: keep JVM byte arrays end to end for UTF-8, HMAC, AES-CTR
  and base64url; outputs stay byte-identical (differential against the
  previous implementation over random values, plus pinned vectors).
- Request scaffolding: validate derived-schema identities once per
  generation; precompute per-client option maps and adapter capabilities.

## Certification

What "update formal models, certify implementation matches models" means for
each decision, following the stack's practice (#199–#204):

| Decision | Dafny | Executable refinement | Mutation controls | Contract |
|---|---|---|---|---|
| D2 | `OperandOrder.dfy`: Kleene ∪/∩ permissionship invariant under child permutation; the first decisive witness's certificate is sound | `delegation-refinement-test` with random child permutations against the stratified fixed point and the tabled route; qualified differential: lookup items equal checks, certificates hold at sampled times | evaluation order drops a child; a fault taken as decisive | `:delegated-operator-recursion` gains the order theorems |
| D3 | lemma in `VectorPredicate.dfy`: the aligned arrow equals the pointwise scalar arrow, value and certificate | vector-evaluator differential, batched against scalar, per candidate, over random programs and qualifiers | intermediate dropped; decision assigned to the wrong candidate; via qualifier ignored | same entry |
| D4 | lemma in `CandidateCover.dfy`: a plain cover witness proves the generator node | campaign: every plain-flagged candidate's generator node is plainly true by exact evaluation | plainness ignores the qualifier slot; plainness kept across a qualified edge | same entry |
| D5 | `StructuralBounds.dfy`: S ⊆ Has(t, ctx) ⊆ non-F(t, ctx) ⊆ M for every time and context; semi-naive per component equals the least fixed point; the count decomposition | transcription of the semi-naive evaluator beside production, per node and round; independent check that every exact decision lies between S and M | qualified edge counted sure; `Sa − Sb` for exclusion; delta not propagated through an arrow | new `:structural-operator-bounds` operation and theorem policy |
| D6 | none new (`MemoizedMembership.dfy` covers retained answers across searches) | batch against sequential `check-permission` per demand: decisions, residuals, failing index | memo shared across contexts; error attributed to the wrong index | `:check-permissions` entry points |
| D7 | `BidirectionalMembership.dfy`, extending `BidirectionalArrowIntersection.dfy`: upward closure from the subject equals top-down reachability; the leveled certificate is preserved by reversal; work bounded by the smaller side | refinement against the memoized search on random group forests with qualified edges | upward closure skips a level; side choice ignored (caught by the gate's command bound) | `:delegated-operator-recursion` |
| D8 | `TabledFixpoint.dfy`: restart-on-demand tabling with leader completion computes the least fixed point of each positive component over the evidence lattice, reads exclusions only from completed strata, terminates, and short-circuits soundly on approximations; refines `PermissionSetAlgebra.dfy` and `SignedDependencyStratification.dfy` | transcription beside production, tables compared after each completion; differentials against the retained Route C (test-only), the stratified fixed point, and the Rust port (eacl-rust `crates/eacl-diff` port campaign, 10⁶+ steps, on a dedicated nREPL of the PR worktree); counterexample replay | component completed with pending work; overwrite instead of accumulate (two grounded witnesses with different deadlines on one cycle); short-circuit on an approximation without a re-run; exclusion read before completion; goal-id collision | `:operator-engine-phase-b` entry points move to the new evaluator; `OperatorRecursiveGeneratedPolicy*.dfy` stay covered as historical models |
| D9 | the resumed reducer state equals the replayed one at the boundary (`RelayCheckpointExecution.dfy` style) | walk differential over page sizes, cache on and off, writes between pages, lost checkpoints | resume one past or one before the boundary; checkpoint of another walk accepted | `:lookup-cursor-continuation` |
| D10 | none | byte-identity differential and pinned vectors | none needed (identity tests) | none |

Every PR runs the CI-equivalent battery, the DataScript ClojureScript suite
in both modes, `bin/formal fast`, `bin/formal verify` for touched models,
`bin/formal source-closure`, the mutation-control suite,
`counterexample-replay`, and `bin/formal manifest` (exit 3 by design while
the four mechanized source refinements remain open; a new Dafny file must be
listed in some operation contract or the manifest fails).

## Risks / Trade-offs

- **Certificates.** D2 can report a different, still sound, certificate than
  #210 for a union with several decisive witnesses. Cross-request reuse
  (#203) keys on certified scope, so an older answer is never reused past its
  own certificate.
- **Kleene dependency.** D2, D3, D5 and D8 rely on order-independent fault
  semantics; they must land after the Kleene PR.
- **Memory.** S and M for a broad subject hold about 160k eids at 10⁶; they
  are request-local and counted against the existing traversal limits.
- **Order.** Only D9's second option changes result order.

## Migration Plan

Each PR bumps the compatibility identity of what it changes
(`compiler-plan-compatibility` for D2–D5 and D8, `checkpoint-version` for D8,
the cursor version for D9). An outstanding cursor of an older format gets the
typed stale-cursor error, never a wrong page; clients restart the walk.

## Decisions

The owner answered the design's open questions on 2026-10-01:

1. Operator lookup order stays the cover's deterministic order (D9).
2. Certificates follow the first decisive operand in the static cost order,
   and the later deadline when both operands are already decisive (D2);
   never dependent on cache state or request history.
3. One evaluator: tabled exact decisions plus structural certainty; the
   specialized searches retire behind the gates (D8).
4. The checkpoint and cursor versions may change with D8; old cursors get
   the typed stale-cursor error.
5. When #213 meets #206, wildcard-subject detection reads only the subject's
   `:eacl/id` datom, so the endpoint check stays O(1).
