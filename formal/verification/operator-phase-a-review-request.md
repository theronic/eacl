# Independent review request: abstract operator Phase A

## Review boundary

Review branch `agent/design-operator-engine-performance` at base commit
`8dc3b16498788dd822b68e1c4fe25b37a8e8879f`. The review covers OpenSpec
change `design-demand-driven-set-algebra-engine`, tasks 2.1 through 2.15.
Production implementation is deliberately out of scope and must remain
disabled.

The machine-readable evidence bundle is
`formal/verification/operator-phase-a.edn`. It records the 525 new proof-leaf
obligations, six generated-boundary obligations, the 9,325-obligation
whole-tree result, source digests, temporal mutations, generated-runtime
vectors, artifact sizes, and the explicitly closed Phase B gate.

## Required adversarial checks

The reviewer must independently determine whether:

1. the typed object catalog is complete for every finite relation and arrow
   partition, and expression denotation covers relation, permission, one-hop
   arrow, union, intersection, and ordered exclusion without assuming a global
   complement universe;
2. the expression-table builder preserves every leaf denotation, the compiled
   plan denotes the source expression, every positive and negative dependency
   induced through named permissions and supported arrows appears in the
   signed graph, and every negative edge inside a recursive component is
   rejected;
3. executable reachability, exact SCC certificate validation, finite-path
   negative-cycle rejection, and canonical diagnostic order are derived rather
   than trusted; the generated signed-graph decision must reject malformed or
   noncanonical input before accepting a certificate;
4. the candidate-cover proof cannot omit a result, recursive exact generation
   reaches the same least fixed point, and a child cannot issue a parent
   witness before completing its exact local predicate; witness reuse must be
   confined to the same derivation;
5. scalar and vector predicates agree for every candidate after grouping,
   sorting, deduplication, scatter, short-circuit masking, malformed responses,
   cancellation, and atomic failure, with the mask/DAG schedule connected to
   scalar expression denotation rather than specified by the desired result;
6. adaptive batching bounds physical work while cursor and continuation state
   advance only by logically consumed candidates;
7. dense-prefix selection handles checked inclusive spans without overflow and
   sparse fallback remains exact; binary and n-ary seekable kernels preserve
   exact generic anchor-filter order, uniqueness, and logical boundaries under
   only the stated strict-order and inclusive-reseek premises; in particular,
   n-ary intersection positions operands in sealed order until exhaustion and
   otherwise considers every head before jumping the driver to the maximum
   child head instead of repeatedly applying a binary filter; its
   demand-stopping execution must issue no work at zero demand,
   return exactly the demanded generic prefix, and establish the claimed
   anchor-round, operand-seek, driver-seek, and combined-seek bounds from the
   same result/work function; its exact per-round operand-seek trace must stop
   at the first exhausted child without opening later operands in that round;
8. anchor-gated recursive conjunction's event delta, normalized retained state,
   duplicate handling, and iterative operational worklist are connected to the
   recursive least fixed point for every fact-arrival order; typed identities
   do not collide and retained parent state is bounded by admitted anchor
   facts;
9. exclusion consumes only completed exact lower-stratum absence and propagates
   every incomplete or failed outcome without partial authorization;
10. cache reuse depends on the complete static signed closure despite witnesses,
   short-circuiting, grouping, or absent runtime reads, and lifecycle/generation
   premises are neither omitted nor circular; every present temporal-model cache
   entry, not only every hit, must belong to the current lifecycle; the theorem
   must connect snapshot, projection, generation, and denotation rather than
   assume cache equality;
11. the temporal model and six killed mutants cover publication, cancellation,
    logical progress, checkpoint identity, lifecycle expiry, and negative
    completion without converting bounded checking into an unbounded claim;
12. `DecideOperatorBatch` and `DecideOperatorSignedGraph` are only abstract
    generated smoke boundaries; `OperatorProofKernel` is proof-only, its policy
    refinement is genuine, and no production parser, storage, sealer,
    evaluator, backend, cache, or routing source accepts intersection or
    exclusion;
13. the abstract per-stratum result is not misrepresented as proof that a
    future concrete evaluator holds a stable complete lower-stratum fact
    context, or that a future expression compiler emits the proved recursive
    join head, slot, and anchor inputs—both remain section-10 source-refinement
    obligations; and
14. every theorem, obligation count, digest, mutation total, artifact size, and assurance
    qualification agrees with its owning source and generated report.

## Reproduction

Run from a clean generated target with the locked toolchain:

```text
bin/formal source-closure
bin/formal format
bin/formal verify
bin/formal build-java
bin/formal build-js
bin/formal browser-bundle
bin/formal artifact-size
bin/formal manifest
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
bin/formal apalache-mutation-control
EACL_NREPL_PORT=<dev-port> bin/formal mutation-control
EACL_NREPL_PORT=<formal-smoke-port> formal/smoke/cljs/run
openspec validate design-demand-driven-set-algebra-engine --strict --no-interactive
```

`bin/formal manifest` must validate the evidence and then exit 3 because the
pre-existing complete-engine release gate is intentionally withheld. Any exit
2 is invalid evidence.

## Attestation gate

The reviewer must check in a separate attestation containing their identity,
review date, reviewed commit, evidence-bundle SHA-256, reproduced commands and
results, findings and dispositions, and an explicit statement that production
operator acceptance remains absent. Until that signed-off artifact exists,
task 2.16 remains open and section 3 must not begin.
