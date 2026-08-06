# EACL-FORMAL-059 — stale clean-build JavaScript expectations

A clean CI rebuild of the generated JavaScript kernel exposed two stale CLJS
assurance-harness expectations. The direct page-window smoke still passed the
removed `limit` and `cursor` presence flags to the current four-field
`RawPageRequest`; JavaScript ignored the extra function arguments, so the
fixture incorrectly expected the removed `limit` field to make the generated
request invalid. Public host-boundary tests already own rejection of removed
v8 API fields.

The CLJS production fixture also expected recursive pages `[10 20]`, then
`[30]`, while the equivalent JVM fixture and a freshly generated JavaScript
runtime both produce `[10 30]`, then `[20]` from the same deterministic
worklist. The broader local run had used a stale generated artifact and masked
the mismatch.

This was not an authorization or production-runtime defect. It was an
assurance-harness/source-refinement defect that stopped the release gate.
The CLJS tests now model the current generated datatype, and the JVM and CLJS
production fixtures consume one shared cross-runtime page expectation. A
source regression prevents reintroducing either stale contract.
