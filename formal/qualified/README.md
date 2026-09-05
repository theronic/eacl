# Qualified authorization model gate

This Phase 3 gate runs before production engine, cache, or cursor changes.
`EACL_NREPL_PORT=<dev port> bin/formal fast` runs the Phase 2 foundation gate
first, then this gate. Models are verification tools and are never request-path
dependencies. Passing this gate does not activate qualified authorization.

`QualifiedEvidence.dfy` models a residual as a set of Boolean completions of
the selected request's remaining Caveat atoms. This is a denotation, not the
production residual representation. Union, intersection, exclusion, and arrow
composition are pointwise Boolean operations. Any encountered authoritative
fault propagates before Boolean absorbers; CEL's internal expression-error
rules remain the separate Phase 2 contract. Positive recursion has monotone,
finite, least-fixed-point rules; negative dependencies are already stratified.

`QualifiedTemporal.dfy` models sparse references, inert preparation, atomic
pair publication, one captured time, the exclusive expiry boundary, and
decisive witness certificates. Structural qualifier faults precede expiry;
expiry precedes Caveat work. With fixed context and immutable data, time cannot
introduce a new leaf fault, but can remove an evaluator fault by expiring its
edge. This property justifies witness pruning without masking a newly arising
fault. The operator theorem composes over arbitrary finite evidence trees.
Recursive false evidence must retain completeness through the fixed point;
stopping when membership alone stabilizes is deliberately killed.

`QualifiedReuse.dfy` adds canonical (collision-checked) context/evaluator/query
identity, source/schema/Relation/qualifier dependencies, result kind, causal
basis compatibility, and half-open temporal intervals. Missing completeness
allows only the identical time and immutable basis. Live continuation requires
complete retained-state evidence for cross-time reuse. Pinned continuation
keeps basis/time while independently checking token lifetime and key
availability. Decode reuse requires exact basis, certified immutable writer
and Relation/version evidence, or complete native content proof.

The finite Clojure oracle uses completion sets so a production symbolic
encoding can be compared independently. It exercises all 16 residual sets for
two Boolean atoms, all operator outcome combinations, 512 three-node directed
graphs, expiry on recursive grant and subtracting inputs, incomplete
certificates, scope changes, and cursor skipped/frontier evidence. Recursive
membership is checked against a separate per-completion reachability closure.
The Phase 2 lifecycle model supplies preparation/publication transitions.

The production evidence bridge compares reduced ordered decision diagrams with
the independent completion sets across all four operators, all 16 residuals,
two authoritative faults, and complete/incomplete temporal certificates. Its
46,726 assertions cover denotation, deadlines, completeness, missing fields,
and canonical wire round trips. The qualification bridge adds 3,024 assertions
over expiry boundaries, bound/request contexts, malformed facts, allowance,
version, one-fetch request memoization, and evaluator suppression. The combined
finite gate also compares the existing scalar operator machine across 46,656
assertions for demanded intersection, exclusion, and arrow evidence, including
faults and incomplete certificates. The combined gate has 144,866 assertions.
Portable evidence tests additionally reject malformed/noncanonical encodings
and enforce node, depth, work, missing-field, and serialized-size bounds.

The lock records 15 seconds and 5,000,000 resources per proof effort, the exact
proof/assertion inventories, and SHA-256 pins for the oracle and mutation
inputs. The whole-tree formal gate retains its stronger existing reporting
requirements. Production refinement, native conformance, mutation controls
against production, performance qualification, and semantic activation remain
Phase 3 implementation obligations. The repository's broader mechanized host
refinement and independent review obligations remain explicit in the assurance
manifest; model success does not discharge them.
