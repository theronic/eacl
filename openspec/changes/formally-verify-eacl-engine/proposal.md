## Why

EACL's authorization result is security-critical, but today its correctness is established by reviews, curated oracles, randomized tests, and backend conformance suites rather than a machine-checked proof. The shared v8 engine now contains enough recursive traversal, pagination continuation, snapshot, and cache-reuse state that subtle false grants can survive conventional testing, so EACL needs a precise semantics and a proof-carrying implementation boundary.

## What Changes

- Define an executable, backend-independent semantics for EACL relations, self-permissions, arrows, recursive permission components, authorization checks, forward/reverse lookup, counts, and traversal-limit failures.
- Add a mechanically verified authorization kernel and prove that direct and recursive traversal return exactly the least-fixed-point authorization relation for every finite valid input.
- Specify and verify Relay cursor laws: stable scope, valid boundaries, ordered de-duplication, page concatenation without omissions or duplicates, snapshot-equivalent continuation, and fail-closed rejection of invalid or stale state.
- Specify and verify cache laws: a hit is observationally equal to recomputation on the selected immutable snapshot; exact hits are generation-identical; managed reuse is framed by complete relation dependencies and forward transaction stamps; exact/arbitrary DB work bypasses completed answers; and lifecycle races cannot expose stale publication.
- Make backend behavior an explicit trusted contract rather than part of the proof: adapters provide immutable-snapshot, complete ordered-scan, identity, causal-anchor, exact-selection, and dependency-proof operations, with executable contract tests for Datomic, DataScript, and Datahike.
- Add bounded temporal model checking and model-derived stateful tests to search adversarial write/cache/cursor interleavings, retain every minimized counterexample as a regression, and require known proof-harness mutants to be detected.
- Differentially and shadow-test the verified kernel against the current Clojure engine and the independent authorization/causal oracles before making the verified kernel authoritative.
- Add reproducible formal-toolchain and CI entry points. Formal tools remain development/build dependencies and do not add Datomic, DataScript, or Datahike to the backend-neutral runtime.
- Preserve the public EACL API, persisted schema, relationship representation, token envelope, and declared backend consistency behavior unless a counterexample demonstrates that existing behavior is unsound; any such correction will be documented with its minimized witness.

## Capabilities

### New Capabilities

- `formally-verified-authorization-engine`: Defines the formal semantics, proof obligations, trusted boundary, executable conformance bridge, counterexample workflow, and release gates for a sound EACL engine.

### Modified Capabilities

None.

## Impact

- Affected shared code: `modules/eacl/src/eacl/engine/v8.cljc`, `cache.cljc`, `relay.cljc`, `relationships/relay.cljc`, `backend/v8.cljc`, and their public-client orchestration.
- Affected adapter verification: Datomic, DataScript, and Datahike contract suites; backend implementations remain outside the formal proof and are checked against explicit assumptions.
- New repository artifacts: formal semantics and proofs, temporal models, generated JVM integration code, differential generators, minimized counterexamples, toolchain locks, and CI jobs.
- New development tooling: a pinned verification-aware JVM-targeting toolchain and a pinned TLA+ model checker; no new production service or database dependency.
- Release risk is controlled by an opt-in shadow phase, output/exception equivalence checks, performance budgets, and a reversible authoritative-engine switch.
