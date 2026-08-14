# Formal iteration policy

Status: exploration acceptance policy. This file is ignored by Git and does
not alter production or release claims.

## The heuristic

Slow formal verification is presumptive evidence of one of four defects:

1. the semantic state contains deployment or historical state that the
   implementation would also have to retain or coordinate;
2. the transition interface is too broad, so one local operation depends on
   unrelated global invariants;
3. the algorithm performs global search, merge, sorting, or pairwise work that
   has not been made explicit in its cost model;
4. the proof encodes the system poorly even if the intended implementation is
   sound and fast.

The fourth case prevents treating solver speed as a theorem about runtime.
The first three cases make proof latency a useful design smell. The default
reaction to a slow or brittle proof is therefore to simplify the state,
interface, or algorithm before tuning solver triggers.

Fast verification is necessary for iteration, not sufficient for acceptance.
A small proof can verify the wrong abstraction quickly. The source-refinement
bridge, independent executable oracle, mutation controls, and backend
benchmarks remain mandatory.

Verification latency is therefore a product-design metric, not merely a proof
tooling metric. In the normal agentic edit loop, the formal leaf must be the
cheapest trustworthy feedback mechanism. A claim that repeatedly needs global
history reconstruction, monolithic state exploration, or unrelated lemmas is
presumed to expose an implementation boundary that would also be expensive or
fragile. The accepted correction is a smaller sufficient state, a local
certificate, or a sharper transition contract—not a weaker claim. Independent
runtime work/allocation/read benchmarks then test whether generated source
actually preserves that locality.

## Three concentric loops

| Loop | Purpose | Development-host budget | Contents |
|---|---|---:|---|
| Leaf | Correct one claim while editing | target under 2 s; hard ceiling 5 s | one Dafny module or one TLC family plus its mutants |
| Semantic gate | Reject incompatible design changes quickly | target under 7 s; hard ceiling 10 s | all leaf models, all formal mutants, fixed-seed randomized refinement |
| Qualification | Establish that modeled cost reaches production | no edit-loop budget; report distribution | source differential tests, allocation/heap measurements, backend commands, physical GET/PUT counts, cold/warm latency |

The hard ceilings are acceptance thresholds on the pinned development host,
not machine-independent guarantees. A breach does not authorize deleting a
claim. It opens a design investigation and records whether the cause is model
encoding, tool startup, host contention, or genuine semantic coupling.

## Slow-proof diagnostic loop

Treat a budget breach as a design failure until evidence identifies a tooling
failure. Use this order; do not start by weakening the theorem or increasing a
solver timeout:

1. Measure a focused leaf in a fresh process and separate fixed tool startup
   from verification or state-space work.
2. Minimize a failing or slow witness while preserving the same checked claim,
   assumption fingerprint, and mutation classification.
3. Remove irrelevant physical, historical, or deployment state from the
   semantic transition. Replace proof-only histories with exact partitions,
   monotone ordinals, or local certificates.
4. Split an interface only when the resulting local contracts compose back to
   the original theorem. A fast collection of unrelated lemmas is not a proof
   of the system.
5. Change triggers, induction structure, or tool orchestration only after the
   semantic state is already minimal. Record when the improvement is tooling
   rather than architecture so it earns no runtime claim.
6. Rerun the complete semantic gate, every required mutant, the independent
   executable oracle, and the source-refinement bridge. Obligation counts and
   assumption fingerprints may not silently decrease.
7. Requalify the corresponding physical claim: allocation, retained heap,
   operation count, cold/warm latency, and remote requests. A fast logical proof
   that models a backend read as one constant-time step proves no S3, DynamoDB,
   or JDBC performance property.

This makes the heuristic deliberately asymmetric:

- **slow proof => investigate and usually simplify the design;**
- **fast proof => the design is cheap to reason about, but runtime speed remains
  unproven.**

The desired end state is not merely a short verifier run. It is one small
sufficient semantic state whose local proof, source representation, and
physical cost model all expose the same operations. That correspondence is the
part expected to transfer from fast verification to a fast implementation.

## Model construction rules

1. A leaf model owns one claim family: denotation, reducer order, static
   reverse traversal, pagination, lifecycle, physical capacity, or cache
   publication.
2. Pure reducer state contains no futures, clocks, request IDs, threads,
   backend handles, cache epochs, or physical permits.
3. Proof-only history is rejected when a monotone partition or local witness
   establishes the same theorem. Runtime should not pay for ghost history.
4. A global optimization is represented by a compact certificate with a
   linear checker whenever possible. The shortest-read rank generator is not
   trusted; edge inequalities plus a well-founded witness path are.
5. Quantification is over the smallest exact domain. Direction-specific
   algorithms get direction-specific models instead of a symmetric universal
   machine.
6. Correctness and cost are distinct theorems. A reachability proof does not
   imply first-page efficiency, heap bounds, or physical-read bounds.
7. Every concurrency or lifecycle safety claim has a focused mutant that must
   fail for the intended invariant. A proof that also accepts its mutant is
   vacuous or incomplete.
8. Randomized models use independent oracles and print replayable seeds. They
   do not call production compiler, rank, or traversal helpers.
9. Source refinement is tested through public parsing/compilation and compares
   complete canonical structures, not selected examples or shared helpers.
10. Solver-specific tuning is the last remedy. First split the theorem,
    strengthen the interface invariant, remove irrelevant state, or replace a
    global argument with a local certificate.
11. Fast success may not be purchased with an unreviewed proof escape hatch.
    The gate rejects Dafny assumptions, axioms, external declarations, skipped
    verification, and wildcard termination. TLA+ finite-domain/type premises
    are allowed only behind an exact audited assumption-block fingerprint;
    changing it is an explicit proof-boundary review.
12. Do not add a second state machine for a source/backend question already
    implied by an existing semantic theorem. State the missing refinement
    contract and test it at that boundary. For example, `AtomicAttempt.tla`
    already says failed/partial physical attempts cannot publish or integrate;
    whether a driver reports a storage miss as `Failure` or a legitimate empty
    range as `ValidatedComplete([])` is a source/physical classification test,
    not a larger reducer model. Redundant formal state slows iteration and can
    create two subtly different authorities for the same claim.

## Runtime correspondence required for cost claims

A fast cost proof is relevant to implementation only when the production data
structures preserve the same operations:

- admitted logical work uses expected constant-time set membership;
- reverse traversal uses the sealed rules-by-head index, one exact admitted
  goal set, and the same right-edge stack discipline as forward traversal;
- scheduling uses a stack plus precomputed rank, not an N-way merge or global
  result sort;
- rank validation scans plan nodes and edges linearly;
- scan continuation advances monotonically and cannot reopen prior ranges;
- checkpoint capture retains an immutable state reference rather than walking
  or serializing the frontier on the request thread.

If production violates one of these correspondences, it loses the formal cost
claim even if denotational refinement still passes.

An intentionally rejected architecture illustrates the policy. The generic
reverse fixed-point machine introduced dynamic grant/consumer goal cells and
a Cartesian-pair proof. Once the sealed plan supplied exact static reverse
rules, that join had no semantic role: reverse lookup is ordinary traversal of
transposed grounded grants. Replacing four join/type leaves with the
16-obligation static frontier proof and deleting its randomized join campaign
cut the focused 2,000-case randomized run from about 2.7 seconds to about 1.1
seconds. The simpler model also matches the smaller runtime state.

## Current evidence

On 2026-08-14, the complete gate with the final multi-page cursor theorem,
public-cursor/private-checkpoint composition, history-free runtime refinement,
bounded page/lookahead state, exact runtime-checkpoint composition, and the
executable pagination family, owned-transient runtime oracle, and exact-cache
boundary, atomic logical admission, and Relay edge pagination ran three
consecutive times in 4.36--4.72 seconds wall time (median 4.58 seconds) on the
current development host. That former gate checked 369 Dafny
obligations, five TLC model families, killed all 19 required TLC
mutants, and ran 16,000 fixed-seed randomized refinement checks. Every Dafny
leaf completed below two seconds. Four independent Dafny verifier processes
replace the former 6.78-second monolithic invocation, and the gate fails if
their verified-obligation counts do not sum to the declared aggregate. The
former aggregate reached 451 after adding authenticated edge identity, exact
Relay/checkpoint execution, the bounded target driver, combined concrete hot
loop, full-state ownership, and typed EACL path composition. Three consecutive
complete runs took about 4.94--5.39 seconds (median 5.10 seconds), and the
executable campaign killed twenty-one controls. This is within the soft and
hard budgets, but it is not evidence for
JVM allocation, Datahike node-cache behavior, S3 GET counts, or response
latency.

An isolated profile of the first 24 Dafny leaves on the same host ranged from
0.50 to 1.20 seconds. The slowest leaf was typed forward grounding, not
scheduling, pagination, join costing, or lifecycle coordination. This matters because the
parallel full-gate wall time could otherwise conceal a locally monolithic
proof. During editing, run the changed leaf first; run the complete gate before
accepting an interface change. Keep source/adapter qualification outside that
edit loop so remote startup and storage latency cannot mask semantic feedback.
The subsequently added cursor/checkpoint composition, history-free runtime,
bounded-page, and exact runtime-checkpoint bridge leaves verified in 0.67,
1.03, 0.68, and 0.89 seconds respectively, preserving that range across all
28 leaves. The subsequently added owned-transient ownership leaf verified ten
obligations in 0.69 seconds, preserving the range across the then-current 29
leaves. The cache-boundary leaf added seven obligations in 0.87 seconds,
bringing the current total to 30 leaves.
The exact-dedup lower-bound leaf added two obligations in 0.50 seconds,
bringing the current total to 31 leaves.
The atomic logical-admission leaf added seven obligations in 0.82 seconds,
bringing the then-current total to 32 leaves.
Replacing the incorrect public page-start/subtraction model with a 16-obligation
internal boundary leaf and a 29-obligation Relay edge-cursor leaf added one
leaf and 20 net obligations, bringing the then-current total to 33 leaves.
The 26-obligation authenticated-edge leaf and then-22-obligation
Relay/checkpoint-execution leaf each verified in about 1.1 seconds. They bind
the external edge identity and prove the history-free backward execution plan,
bringing the then-current total to 35 leaves and 417 obligations. The
14-obligation target driver, 7-obligation combined concrete runtime,
full-state 12-obligation ownership replacement, 9-obligation typed EACL path
bridge, and two additional Relay/driver composition obligations brought the
then-current total to 38 leaves and 451 obligations. Three complete gates took
about 4.94--5.39 seconds (median 5.10 seconds) and killed twenty-one executable
mutation controls in addition to all 19 TLC mutants; the larger 160,000-check
campaign took 6.07 seconds.
The 11-obligation record-framing leaf plus the two-obligation increase from
simplifying rank certificates to one witnessed-edge index first brought the
total to 39 leaves and 464 obligations. Replacing the four obsolete reverse
join/type leaves with `StaticReverseFrontier.dfy` first brought the total to
36 leaves and 434 obligations. The logical-scan cursor/replacement leaf and
one-value scan-normalization leaf first brought the total to 38 leaves and 469
obligations. The latter proves both physical-width independence and safe
buffer dematerialization in about one second. It replaces the 26-obligation
checkpoint-buffer pin leaf because physical buffers are no longer checkpoint
authority. The 10-obligation bounded-sidecar leaf first brought the total to
39 leaves and 479 obligations; it also verifies in about one second. Five
checkpoint-ordinal obligations first made the total 484: they prove that
concurrent exact candidates are states on one deterministic trajectory and
that replacing a lower ordinal with a higher ordinal is exact and
nonregressing. The 11-obligation weighted checkpoint-slot leaf first made the
total 40 leaves and 495 obligations and proves positive weighting,
nonregression, capacity
preservation, exact rejection, and release-only eviction in 0.79 seconds.
Replacing the 21-obligation forward-only static-consumer leaf with the
23-obligation direction-parameterized static-index leaf keeps 40 leaves and
raises the current guarded total to 497 obligations while proving the same
membership, uniqueness, cursor, and exhaustion claims for both forward target
indexes and reverse head indexes. The new leaf verified in under one second;
the first complete 497-obligation gate took 6.5 seconds. The independent
23-obligation sealed-vector leaf then made exact membership, unique ordinals,
and strict `(rank, ordinal)` ordering a unique-sequence theorem rather than
only an executable convention. It includes constructive membership-only,
rank-only tie, and duplicate-ordinal controls, verifies in about one second,
and raises the total to 41 leaves and 520 obligations. The 8-obligation
sealed-plan/right-stack composition leaf then closed the unique-vector to
concrete-pop-trace seam and contained a wrong-push-order counterexample,
bringing the suite to 42 leaves and 528 obligations. The 8-obligation
exact-count composition leaf now proves that exhausted unique results and the
history-free scalar count equal reachable root-denotation cardinality, while
constructively refuting admitted-work counting. It verifies in about one
second and raises the current total to 43 leaves and 536 obligations. Three
consecutive complete gates took about 7.8 seconds: below the ten-second hard
ceiling, but above the seven-second soft target.
Every new leaf verifies in about one second. The gate now also
manifest-checks and runs six local source-refinement bridges:
124 public-schema cases with 19 rejection families, and 248 normalized-schema
compiler cases with exact semantic rule and direction-index comparisons, plus
32 ranked sealed-plan prototype cases with nine focused controls. The first
bridge independently reconstructs persistence identities and checks
duplicate-arm collapse; the second deliberately treats the current compiler
as a black box; the third remains a ranked-plan prototype rather than
production source; the fourth checks the minimum authenticated edge-cursor
shape with 15 context mutations and 14 controls. The fifth runs 512
source-shaped physical completion/cancellation permutations and four controls.
The sixth runs 24 checkpoint publication orders, eleven exact-context
mutations, six cancellation prefixes, 64 real CAS-contention rounds, and four
controls. The gate runs 18,000 randomized checks with twenty-two executable
controls. Earlier consecutive 495-obligation runs took 6.16, 6.52, and 6.4
seconds. Combining the randomized campaign and the six namespace-isolated
source bridges in one Clojure process—solely to amortize classpath/JVM startup,
without changing a seed, assertion, model, mutant, or assumption—reduced
subsequent complete runs to 6.56 and 6.12 seconds. This remains below the
seven-second target and ten-second hard ceiling.
A separate
two-seed campaign checked 2,000 ranked plans in 31.96
seconds. These checks close only the parser/normalized reusable-compiler seam
and the abstract ranked-plan design; replacement production rank/order,
adapter scans, traversal source, cryptography, and backend qualification
remain outside that status.
Splitting the longest TLC mutation family into another concurrent JVM raised
the gate to 7.25 seconds through contention, so that harness-only change was
reverted. Rebalancing the existing five TLC worker queues alone also did not
materially improve the gate; splitting the 39 semantically independent Dafny
leaves into four verifier batches did. Local leaf latency and dependency
structure, not maximum process count, are the edit-loop control variables.

`verify-leaf.sh` is the normal correction loop. A representative Dafny leaf
verified in 0.75 seconds; the largest TLC family, ProgressCheckpoint plus all
six mutants, took 0.78 seconds after the accepted same-family runner change.
`verify-fast.sh` is the acceptance loop and retains every model,
mutation control, randomized family, exact aggregate obligation-count guard,
exact discovered-versus-declared Dafny/TLC/source-bridge manifests, a Dafny
escape-hatch audit, and an exact TLA+ assumption-boundary fingerprint. The
scripts now
enforce the documented five-second leaf and ten-second full-gate hard wall
ceilings as executable regressions.

Before the runner change, the largest TLC leaf was `ProgressCheckpoint`: the
checked model plus six required mutants took about four seconds because the
shell harness started seven JVMs. This was process overhead, not state-space
growth. One fresh JVM for that family now runs all seven checks in 0.78
seconds. Fourteen cyclic and reverse-cyclic orders (98 total runs) produced the
exact expected classifications in 1.75 seconds including startup. Equivalent
order probes passed for every other family.

The isolation boundary is essential. Reusing one TLC JVM across different
model families corrupted later outcomes: valid models returned specification
or safety-evaluation errors instead of success. The accepted runner therefore
amortizes startup only inside one family, starts a fresh JVM for every family,
maps each `TLC.process()` error constant through `EC.ExitStatus`, requires
`SUCCESS` for the valid configuration and `VIOLATION_SAFETY` for every mutant,
and explicitly terminates after the family. Differential probes against the
former process-isolated runner passed every family and every cyclic/reverse
check order. Faster feedback does not weaken process isolation or mutation
classification.

The simplification pressure has already improved the design:

- reducer history became the admitted/processed partition;
- complete result history became a proof-only observation; a 9-obligation
  refinement erases it to stack, exact admission, and one scalar count, while
  a 14-obligation page wrapper retains at most page plus lookahead;
- request-owned transient builders replaced persistent-path mutation in the
  runtime representation; a 10-obligation ownership leaf separates live
  branches from frozen immutable snapshots and forbids mutation after freeze;
- the generic emitted-result set disappeared after proving concrete root
  identity injective;
- forward traversal lost dynamic grant/consumer goal cells;
- the sealed forward consumer vector gained a local exact-membership and
  exact-cursor refinement rather than a runtime join;
- grounding split into small directional lemmas;
- forward grounding now carries explicit node/relation/allowed-subject/entity
  types and rejects the historical cross-resource-type arrow-grant mutant;
- a 4-obligation producer refinement connects sealed consumer vectors and
  on-demand arrow scans to exactly the fully grounded outgoing edges;
- reverse traversal now uses only a static predecessor index, exact goal
  admission, and a work stack; its 16-obligation frontier leaf proves sound
  admission, fresh progress, and complete exhaustion;
- a generic 14-obligation transpose/path theorem plus a 9-obligation direct
  typed-EACL instantiation establishes reverse lookup denotation equals forward
  authorization for exact `Grant(node, resource)` identities without inventing
  a numeric packing scheme;
- a 4-obligation reverse producer refinement connects sealed head indexes,
  predecessor scans, and base-owner scans to that transposed denotation;
- scheduler, cancellation, capacity, and cache lifecycle moved outside the
  semantic reducer;
- completed speculative chunks now remain inside both a request window and a
  service-wide response lease until every live pin/cache owner releases them;
- weighted leases reserve strictly positive conservative chunk weight before
  I/O and preserve aggregate capacity through completion, pin, eviction, and
  release in a 20-obligation leaf verifying in about 0.6 seconds;
- shortest-read ordering became a proof-carrying local certificate rather
  than trusted global planner code;
- the abstract front-headed sequence acquired an 11-obligation refinement to
  a right-edge persistent-vector stack, verifying in about 0.8 seconds;
- history erasure and right-edge layout are no longer independent toy claims:
  a 7-obligation combined concrete runtime proves the complete hot-loop state
  and output step/run relation, while the ownership model now freezes and forks
  that full state rather than a bare set;
- a 14-obligation target-result driver proves exact new-output buffering and a
  scalar stop bound; the formal and executable `>`-instead-of-`>=` mutant shows
  how a denotationally correct driver can retain one result too many;
- undelivered lookahead acquired a 22-obligation residual-suffix refinement,
  verifying in about 0.9 seconds.
- cursor state lost its per-result rolling digest after the pagination model
  exhibited the decisive counterexample: equal delivered prefixes do not imply
  equal next pages. Exact deterministic replay from an authenticated context
  plus ordinal is both the real premise and the smaller implementation. The
  public model then split correctly: a 16-obligation internal delivered-boundary
  leaf composes with reducer checkpoints, while a 29-obligation Relay leaf
  proves one-based edge cursors, `after end-cursor`, `before start-cursor`,
  short first/last pages, and same-sequence backward display. This refuted the
  earlier page-start subtraction abstraction, which overlaps pages when the
  terminal page is short.
- authenticated edge identity and checkpoint execution stayed separate from
  arithmetic: two local leaves prove exact boundary identity, reject replay
  drift, make `after` match a constant-size last-result checkpoint field, and
  make `before` resume at the previous page start and validate its edge with
  one bounded lookahead. This caught and removed the incorrect idea of
  resuming a history-free reducer at the backward page's exclusive end.
- subproblem cache authority narrowed from complete denotation sets to exact
  ordered projections: a seven-obligation leaf proves projection substitution
  and exhibits a complete, set-correct fresh subtree whose sequence is wrong
  under an overlapping request-local admission set.
- exact online dedup gained a two-obligation lower bound: a summary capable of
  answering membership for every future identity must distinguish every
  admitted subset, so ordinal/count-only state cannot replace exact admission.
- logical resource limits moved before mutation: a seven-obligation leaf
  proves a staged batch either updates admitted/frontier/join counts entirely
  within all caps or leaves state byte-for-byte unchanged, and exhibits the
  post-check partial-mutation failure.
- eager scan batches were replaced by one-value logical release. A 17-
  obligation leaf proves physical-width independence and safe arbitrary buffer
  dematerialization. Making the logical exclusive bound the sole checkpoint
  authority deleted the larger checkpoint-buffer ownership leaf and lets the
  runtime govern or discard physical accelerators without changing semantics;
  a separate 10-obligation leaf bounds newest-retained request sidecars without
  reintroducing buffer state into the reducer.

## Stop conditions

Implementation must not begin merely because the semantic gate is fast. The
exploration closes only when:

1. all abstract correctness and lifecycle claims pass the fast gate;
2. the complete production compiler/source refinement contract is executable
   and kills its required mutants (the parser and reusable semantic compiler
   sub-seams now pass; replacement rank/order/traversal do not yet);
3. frozen old-versus-new workloads show the expected logical-work reduction
   without a DataScript or Datomic regression outside the accepted envelope;
4. cursor cryptographic assumptions and exact-basis behavior are explicit;
5. the stale-code/model/claim deletion plan leaves one semantic authority;
6. every remaining unknown is classified as release qualification rather than
   an unresolved architectural choice.
