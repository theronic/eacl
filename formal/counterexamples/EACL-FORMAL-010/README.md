# EACL-FORMAL-010 — generated worklist reordered acyclic pages

An acyclic `owner + viewer` permission with interleaved resource EIDs is the
smallest witness. The legacy acyclic engine merges the two ordered relation
streams globally. The generated fixed-point worklist instead emitted values in
rule/work discovery order.

The Dafny proofs were internally valid but too weak for this cutover: set
soundness/completeness does not imply ordered-sequence equivalence, and the
rendering theorem assumed identical input sequences. Unsafe all-root routing
has been removed. Recursive-SCC-dependent roots retain traversal-order
authority; acyclic roots retain the ordered merge until its generated
refinement is proved and meets the performance gate.

The Dafny ordered-merge model now proves single-step refinement and that each
bounded chunk reconstructs the exact complete merge. Generated Java and
JavaScript act as executable oracles for the optimized CLJ/CLJS lazy merge.
Per-EID and generated-sequence hot-path prototypes were both rejected by the
latency gate, so production retains the zero-overhead source specialization.
That closes the observed ordering bug, but the end-to-end formal claim remains
withheld until source-digested refinement and independent review are recorded.
