# Design

## Context

See proposal.md for the problem and the measured baselines; the budgets are
in `specs/operand-bounded-set-algebra/spec.md`.

**Routing after PR #199.** A recursive operator plan takes the delegated
route when no permission that reaches an operator lies on a cycle
(`delegated-permissions` is non-nil). On that route:

- operator nodes run on the acyclic vector evaluator over a delegated view;
- union-only operands go to an oracle. Lookups and counts use `:batched`,
  i.e. `stable-route/check-many-eids`, the memoized plain search certified
  by `MemoizedMembership.dfy`. Checks use `:point`, i.e.
  `stable-route/check-eids`;
- candidates come from one delegated operand's own sealed union plan when
  the root's anchor chain ends at such an operand. Otherwise they come from
  the per-node synthetic cover of `eacl.operator.cover-plan`, which is sealed
  through a wrapper adapter.

Every other recursive operator plan runs on the tabled evaluator in
`eacl.operator.recursive`. Acyclic operator plans use keyset least-path
covers.

**Evidence rules that constrain the design.**

- `evidence/combine` keeps the deadline of the left-most complete decisive
  witness. A union's certificate therefore depends on operand order.
- The formal model (`QualifiedTemporal.dfy`, `WitnessCertificateIsSound`) and
  the certificate requirements of the qualified-evaluation change require
  only soundness: the value holds throughout the certified interval.
- `check-eids` stops at its first decisive witness, so its deadline is the
  first-found path's.
- Public results show a deadline in exactly one place: the `:residual` of a
  conditional result, which encodes the deadline and `complete?`.
- Relationships only expire; there is no `valid-from`. Time can therefore add
  access only through an exclusion whose subtracted evidence expires.
- Faults are demand-driven. A fault a search meets must be reported, but a
  decisive witness found first prunes alternatives that are never demanded.

**Constraints.**

- All state is per request. 0tx builds a client per request, and there is no
  shared cache.
- The code is portable CLJC and must behave the same on the JVM and in
  ClojureScript.
- Recursive order must be deterministic and independent of page size and
  cache state. Cursors authenticate the generator's fingerprint.
- Pages are demand-bounded.
- Benchmark budgets are authored before sampling.
- Each new decision procedure is certified by a Dafny model, an executable
  refinement against a transcription of it, and registered mutation controls.

## Goals / Non-Goals

**Goals:**

- Meet the spec's budgets while keeping every answer and every guarantee the
  spec lists.
- Keep the plans, generators and cursors that PR #199 gave single-operand
  delegated plans.
- Share one flattened-generator builder between union roots and guarded
  recursion.

**Non-Goals:**

- Acyclic operator plans. Their keyset cursors encode cover coordinates, so
  flattening their covers is a separate change.
- Deciding caveated (conditional) evidence or faults in the memoized
  searches. Both stay on the exact point path.
- Recursion through an operator that is not linearly guarded, beyond
  constant-factor work on the tabled evaluator.
- Changing `evidence/combine` or `check-eids` certificates.
- Any cache shared across clients or requests.

## Decisions

### D1. Decide expiring evidence level by level

For one subject and one evaluation time, define a union-only operand's graph
at level `v` as the graph that keeps:

- every plain edge and witness;
- every expiring edge or witness whose deadline is at least `v`.

The operand holds at level `v` exactly when its root reaches a witness in
that graph. The operand's grant ends at `T* = max{v : it holds at v}`: the
widest decisive witness, i.e. the latest first-expiry over witness paths.

The search runs the certified plain search at level `+∞`, then at a sequence
of lower levels:

- **Skipped deadlines.** It skips expiring evidence whose deadline is below
  the current level and records the latest such deadline. A negative it
  memoizes keeps the latest deadline skipped in its exhausted closure, so a
  later search that reuses the negative inherits it.
- **Next level.** The next level is the latest skipped deadline. A search
  found at level `v` has `T* = v`: no witness path's bottleneck lies strictly
  between `v` and the previous level, because its first skipped edge would
  have raised the next level. Levels stop when nothing was skipped.
- **Answers.** A search found at `+∞` returns plain `true`. One found at a
  finite `v` returns `Evidence{true, v, complete}`. An exhausted search
  returns plain `false`, which is correct because a union-only operand can
  only lose access over time.
- **Memo.** There is one memo per level. The plain theorem holds for each
  level's graph unchanged.
- **Conditional and faulty evidence.**
  - Conditional evidence (caveat residuals, incomplete evidence) is skipped
    and flagged. A decisive witness found at any level still decides, because
    a union absorbs it.
  - An exhausted search that flagged conditional evidence routes the resource
    to `check-eids`.
  - Any fault met routes the resource to `check-eids`. A decisive witness
    found first prunes faults that were never demanded, as `check-eids`
    does.
- **Residual-bearing results.** Where a batch's combined operator decision is
  conditional, the vector evaluator recomputes that candidate with the
  `:point` oracle. A conditional result's `:residual`, which encodes a
  deadline, then equals `check-permission`'s.

*Why the widest witness.* It depends only on the graph and the time, so
paging and replay are deterministic, and it is the exact end of the grant.
It is sound under the certificate contract. The point check's first-found
deadline depends on its search order and cannot be reproduced once answers
are memoized.

*Alternatives considered:*

- **Plain-first search with fallback.** It does not help a subject whose
  only access is an expiring share.
- **Per-root widest-path search (Dijkstra).** It memoizes only exact root
  values and explores closures again for each root.
- **Evidence-valued fixed point over residuals.** Its certificates are
  order-dependent and cannot match point results.
- **Making `check-eids` compute the widest witness.** Every point check would
  pay for extra search, and it changes pinned point-check behaviour.

### D2. Flattened generators for recursive operator plans

The builder walks from the root along covers:

- a union contributes all its children;
- an intersection contributes its anchor, and an exclusion its left operand;
- a relation leaf becomes a relation row;
- an arrow leaf becomes one row per partition. A relation target gives an
  arrow-to-relation row. A delegated permission target gives an arrow to the
  real permission. Any other target gives an arrow, typed with
  `:source-subject-type`, to that permission's synthetic node;
- a permission leaf becomes a reference to the real permission if it is
  delegated, otherwise to its synthetic node.

Each non-delegated permission reached gets one synthetic node, named
`[resource-type [:eacl.operator.generator/node permission]]`; the root is
always one. Rows are deduplicated.

- **Single-operand plans.** If the root's rows are exactly one reference to
  one delegated permission, that permission's own plan is the generator, as
  in PR #199.
- **Sealing.** The synthetic plan is sealed through a wrapper adapter, the
  technique `cover-plan` already uses. It is memoized under
  `[::operator-generator plan-fingerprint root]`, and its reads are checked
  against the operator plan's relation closure.
- **Cursors and caches.** The synthetic plan is the cover, so cursors and the
  scope digest authenticate its fingerprint. The compatibility identity's
  `:recursive-generator` becomes `:flattened-union-generator-v1`, which
  retires completed answers keyed to the old generator.
- **Filtering.** Delegated plans keep the delegated evaluation; guarded plans
  (D3) use the guarded one.
- **Relation leaves.** A union root filters every candidate through its
  other terms. Profiled after flattening, a relation term such as `deleter`
  cost one probe per candidate, and on qualified requests one adapter
  command per candidate. That was most of the gap to `delete`.
  - In a batched delegated evaluation, the vector evaluator decides a
    relation leaf for forward candidates of one subject from that subject's
    grants of the relation slice (`stable-route/subject-holdings`).
  - The grants come from one forward scan of at most 256 edges per request:
    the scan the membership search already makes. When the scan is
    incomplete, the evaluator probes as before.
  - A decision is the same stored compact edge a probe returns, qualified
    on the same request, so every value and certificate is unchanged.

*Alternatives considered:*

- **Merging one stream per child.** Deduplication, a shared order and cursor
  state for several streams make it heavier than one union plan.
- **Keeping the per-node cover.** That is the slowdown being removed.
- **Flattening least-path covers too.** It changes keyset cursor coordinates;
  that is a non-goal.

### D3. Guarded recursion through operators

**Classification.** This is a plan analysis, derived outside the plan
fingerprint. A cyclic component containing operator-reaching permissions is
*linearly guarded* when every intersection or exclusion node in its members'
expressions meets two conditions:

- exactly one child depends on the component (for an exclusion, that child is
  the left operand);
- every other child is a leaf whose targets lie outside the component: a
  relation, a permission reference, or an arrow.

A plan takes the guarded route when every such component is linearly
guarded. On that route:

- component members are decided by the guarded search;
- union-only permissions are decided by the D1 search;
- operator nodes above the components are decided by the vector evaluator
  over a delegated view.

**Guarded search.** A memoized depth-first search over states
`(permission, node, entity)` inside the members' expressions:

- a union continues to every child;
- a guarded operator continues to its recursive child only when its guards
  hold for the subject at that entity. Guards are decided exactly: relations
  from holdings, outside permissions through the oracle, arrows through
  intermediates and the oracle;
- a leaf that targets the component continues to that permission's root
  state;
- any other leaf is a witness condition.

**Evidence.**

- Positive evidence follows D1: plain values, expiring levels, conditional
  evidence skipped and flagged, faults routed to the fallback.
- A subtracted guard opens its edge only when plainly false, and closes it
  when plainly true.
- A subtracted guard that is expiring, conditional or faulty routes the
  resource to the exact fallback, because time could then add access.
- The fallback is the tabled evaluator's point evaluation for that resource.

**Why it is certified by the existing model.** For one subject and snapshot
every guard outcome is fixed, so the guarded graph is static and
`MemoizedMembership`'s theorem applies to it. A new lemma shows that
reachability in the guarded graph equals the stratified least fixed point of
a linearly guarded component.

**Generator.** The D2 builder. For `inherited`, the anchor is the relation
`eligible` (relations win anchor selection), so the generator is
`reader + eligible`. Where an anchor recurses, the member's synthetic node
recurses as well.

*Alternatives considered:*

- **Guarded rules inside the stable reducer.** This changes the certified
  reducer and its order proofs.
- **Constant factors on the tabled evaluator alone.** Profiled, it would
  still leave roughly an order of magnitude against the union twin.

### D4. Tabled evaluator constant factors

The tabled evaluator spends 42% of its self time building printed question
keys and 11% sorting questions. The printed keys also order the questions
that feed checkpoints and digests, so they stay. The changes are:

- **Memoized keys.** Each question's printed key is built once per
  evaluation and reused. The strings are the same, so every order is the
  same.
- **Decorated sorts.** Sorts by component key compute each key once per
  sort.
- **Condensation.** Each demand round condenses the question graph without
  sorting it. Only the condensation that is observable, at exit, is sorted.
- **Probe attachment.** Only questions with unattached probes are visited,
  in the same sorted order.
- **Command identity.** It is computed only when a command is emitted.

Every decision, certificate, checkpoint, digest and counter stays identical.
The functions that registered mutation controls redefine stay var-called.

### D5. Certification

- **Dafny.**
  - Levels: the level sequence finds `T*`, per-level memos are sound, and
    certificates are sound.
  - Guards: reachability in the guarded graph equals the stratified least
    fixed point.
  - Both live in `formal/dafny/`, under the `:delegated-operator-recursion`
    contract.
- **Executable refinement.**
  - A DataScript campaign with expiring tuples builds each level's graph
    independently. It runs a transcription of the leveled search beside
    production, compares decisions, per-level memos and certificates, and
    checks certificates against an independently computed widest witness.
  - The random-schema delegation campaign gains guarded roots and union
    roots. Its answers are compared with the stratified semantics and the
    tabled route, and each generator must cover its root.
- **Mutation controls.**
  - the next level set below the latest skipped deadline;
  - the first level's deadline reported as the certificate;
  - a conditional-only resource answered false;
  - a union term dropped from the flattened generator;
  - truncated holdings taken as complete;
  - a guard ignored;
  - a subtracted guard's polarity flipped;
  - a non-linear component classified as guarded.
- **Gate.** `eacl.bench.operator-shapes-test`, with the spec's budgets
  authored before sampling.

### D6. Delivery

Three stacked PRs, each carrying the relevant tasks and passing the full
battery, the ClojureScript suite and the formal gates:

1. Against the #199 branch: this change's artifacts, the gate, and D1.
2. Stacked on PR 1: D2.
3. Stacked on PR 2: D3 and D4.

## Risks / Trade-offs

- [Many distinct deadlines multiply levels] → levels are bounded by the
  distinct deadlines actually met, with one memo per level; the whole-chart
  share is gated.
- [Batched decisive certificates differ from point-check certificates] →
  they are sound and exact, and never appear in public decisive results.
  Results that carry a residual are recomputed on the point path.
- [Flattened generators change order for affected plans] → cursors are
  rejected once; the compatibility bump retires completed answers.
- [Selective guards make the generator much larger than the result] → cost
  stays bounded by generator plus guard decisions; the budgets are stated
  against relaxation plus guard.
- [Non-linear recursion stays comparatively slow] → D4 constant factors
  only; documented.
- [Search order decides which faults are demanded] → any fault met routes to
  the point path, as today.

## Migration Plan

No stored data changes. When this is deployed, outstanding recursive
operator cursors for plans whose generator changes fail once with
`:eacl.pagination/invalid-cursor`, and clients restart the walk. Rolling
back behaves the same way in reverse: the old code rejects cursors minted
under a flattened generator by fingerprint.
