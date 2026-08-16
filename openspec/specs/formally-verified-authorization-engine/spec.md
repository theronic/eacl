# formally-verified-authorization-engine Specification

## Purpose
TBD - created by archiving change formally-verify-eacl-engine. Update Purpose after archive.
## Requirements
### Requirement: Versioned executable authorization semantics
The repository SHALL contain a versioned, executable, backend-independent formal semantics for typed EACL objects, relationship tuples, relation definitions, same-resource permissions, arrow permissions, union, recursive permissions, and authorization requests. Recursive authorization SHALL be defined as the least fixed point of the schema rules over a finite snapshot.

#### Scenario: Recursive schema meaning
- **WHEN** a valid finite schema contains one or more cyclic permission dependencies
- **THEN** the formal semantics assigns the unique least-fixed-point authorization relation rather than cutting the cycle at an implementation-defined depth

#### Scenario: Semantic version changes
- **WHEN** a change alters the meaning of any accepted schema or request
- **THEN** the semantics version and compatibility evidence change with it

### Requirement: Proven authorization decision exactness
The verified authorization kernel SHALL mechanically prove, for every valid finite schema and snapshot satisfying the adapter contract, that a successful permission check returns `true` if and only if the requested subject-permission-resource tuple belongs to the formal authorization relation.

#### Scenario: Authorized request
- **WHEN** the formal least-fixed-point semantics derives a requested grant
- **THEN** the verified permission check returns `true`

#### Scenario: Unauthorized request
- **WHEN** the formal least-fixed-point semantics does not derive a requested grant
- **THEN** the verified permission check returns `false`

#### Scenario: Recursive cycle without a seed
- **WHEN** a cyclic permission component has no derivation from a direct relationship
- **THEN** the verified permission check does not manufacture a grant from the cycle

### Requirement: Proven traversal, lookup, and count equivalence
The verified kernel SHALL mechanically prove that non-recursive and recursive forward lookup, reverse lookup, and permission checks are projections of the same formal authorization relation. Successful lookup output SHALL contain each semantic result exactly once, and count output SHALL equal lookup cardinality unless an explicit count limit reports truncation.

#### Scenario: Forward lookup
- **WHEN** a subject, permission, resource type, valid schema, and immutable snapshot are evaluated
- **THEN** forward lookup returns exactly the resources granted by the formal semantics with no duplicates

#### Scenario: Reverse lookup
- **WHEN** a resource, permission, subject type, valid schema, and immutable snapshot are evaluated
- **THEN** reverse lookup returns exactly the subjects granted by the formal semantics with no duplicates

#### Scenario: Count without truncation
- **WHEN** count is evaluated without reaching a caller-supplied count limit
- **THEN** its value equals the cardinality of the corresponding complete lookup result

#### Scenario: Count with truncation
- **WHEN** more semantic results exist than a caller-supplied count limit
- **THEN** count reports the limit and an explicit truncated result rather than claiming complete cardinality

### Requirement: Proven termination and fail-closed limits
The verified kernel SHALL mechanically prove termination for every finite valid input. A configured traversal-work limit SHALL abort the entire operation with a typed limit error before an incomplete result is reported as complete or an unproved grant is returned.

#### Scenario: Finite recursive graph
- **WHEN** recursive traversal runs on a finite graph within configured safety limits
- **THEN** it terminates after deriving the fixed point and returns the exact result

#### Scenario: Traversal limit exceeded
- **WHEN** recursive proof work would exceed a configured safety limit
- **THEN** the operation returns the typed recursive-traversal limit error and returns no partial authorization decision as final

### Requirement: Proven cursor safety and page completeness
The verified kernel SHALL mechanically prove that every accepted lookup or
relationship cursor is bound to its operation, normalized non-page query,
result kind, execution identity, dependency scope, and permitted graph. A
relationship cursor represents an authenticated physical position rather than
one traversal direction, so the same position MAY be used as an `after` or
`before` bound for the same operation and normalized non-page query. For
one fixed adapter, query, and permitted immutable graph, each page SHALL be the
specified window of one deterministic complete result sequence, and
concatenating a valid complete page walk SHALL reproduce that sequence without
omissions or duplicates. EACL SHALL NOT promise that this internal pagination
sequence is a global, lexical, domain, or cross-adapter order.

#### Scenario: Complete forward walk
- **WHEN** a caller follows successive `:first` and `:after` cursors to exhaustion without changing the permitted graph
- **THEN** concatenated page data equals the complete deterministic result sequence exactly once

#### Scenario: Complete backward window
- **WHEN** a caller requests `:last` and `:before` windows supported by the selected traversal
- **THEN** each response is the mathematically correct preceding window with accurate page flags

#### Scenario: Cross-query cursor reuse
- **WHEN** a cursor is presented to another operation, normalized non-page query scope, or result kind
- **THEN** EACL rejects it with a typed invalid-cursor error before traversal

#### Scenario: Reverse from an authenticated position
- **WHEN** a relationship cursor from the same operation, normalized non-page query, execution identity, and permitted graph is used in the opposite page direction
- **THEN** EACL treats it as the exclusive physical bound for that direction without moving or duplicating results

#### Scenario: Changed authorization dependencies
- **WHEN** current has advanced and the cursor's authenticated exact graph cannot be selected
- **THEN** EACL returns the specified conflict, divergence, or expiry error and does not continue on mixed graph state

#### Scenario: Empty page
- **WHEN** a page contains no data
- **THEN** it exposes no start or end cursor and does not advertise an unreachable next or previous page

### Requirement: Proven cache observational equivalence
The verified kernel SHALL mechanically prove that every returned cache hit equals fresh evaluation of the same semantic request on the selected immutable snapshot. Exact-current entries SHALL be accepted only from the identical immutable selected generation. Managed-current entries SHALL additionally require the same schema generation and a complete relevant relation dependency stamp under the explicit forward stamped-writer contract. Exact, historical cursor, and arbitrary-DB operations SHALL bypass completed-answer lookup and publication.

#### Scenario: Exact cache hit
- **WHEN** a valid cache entry was computed for the identical immutable selected DB generation and semantic key
- **THEN** EACL may return its value and the value equals fresh evaluation

#### Scenario: Managed unrelated-write reuse
- **WHEN** a valid entry was computed before an unrelated forward transaction and the schema generation plus complete relevant relation transaction stamp are unchanged
- **THEN** EACL may return the entry and the least-fixed-point frame theorem establishes equality with selected-snapshot recomputation

#### Scenario: Exact or arbitrary snapshot
- **WHEN** an operation selects an exact/historical snapshot or accepts an arbitrary low-level database value
- **THEN** EACL bypasses completed-answer lookup and publication

#### Scenario: Incomplete managed stamp
- **WHEN** EACL cannot obtain one valid current transaction stamp for every compiled relevant relation dependency
- **THEN** it rejects managed reuse and computes from the selected snapshot, while exact-current reuse remains independently sound

#### Scenario: Lifecycle expiry race
- **WHEN** an in-flight computation publishes after explicit client cache expiry
- **THEN** publication can reach only the captured old lifecycle and cannot repopulate the new lifecycle

### Requirement: Explicit trusted computing base and adapter obligations
The verification artifacts SHALL enumerate every unproved language, compiler, runtime, cryptographic, canonicalization, and backend assumption. The backend contract SHALL state immutable-snapshot, identity, schema completeness, ordered-scan, direction duality, bound, direct-match, causal-anchor, exact-selection, and dependency-proof obligations.

#### Scenario: Verification claim inspection
- **WHEN** a reviewer opens the generated verification manifest
- **THEN** every proved operation is mapped to its theorem and every residual assumption is listed without implying that the assumption was proved

#### Scenario: Adapter certification
- **WHEN** a Datomic, DataScript, Datahike, or third-party adapter runs the shared certification suite
- **THEN** the report identifies which backend obligations passed, failed, or were not tested

#### Scenario: Uncertified adapter
- **WHEN** an adapter has not passed all obligations required by an operation
- **THEN** EACL documentation and manifests do not describe that composed operation as formally verified

### Requirement: Cross-runtime verified behavior
The same verified source semantics SHALL govern the authoritative Clojure/JVM and supported ClojureScript decision paths. Boundary code SHALL reject values that cannot be represented exactly and SHALL preserve result, ordering, cursor, cache, and typed-error behavior across runtimes for portable inputs.

#### Scenario: Portable cross-runtime case
- **WHEN** the same portable schema, graph, request, cache state, and cursor history run on supported CLJ and CLJS targets
- **THEN** both targets produce semantically equivalent results and errors

#### Scenario: Out-of-range internal identity
- **WHEN** an internal identifier cannot be represented exactly by a target runtime
- **THEN** the boundary returns a typed validation error rather than rounding, truncating, or authorizing with a different identity

### Requirement: Counterexample-driven bug discovery
The repository SHALL provide coherent generators and temporal models that search direct, recursive, forward, reverse, cursor, cache, restore, reset, branch, retention, tampering, and provider-failure behavior. Every discovered mismatch or invariant violation SHALL be minimized, reproducible, classified, and retained as a regression before it is considered resolved.

#### Scenario: Differential mismatch
- **WHEN** the formal semantics, verified kernel, legacy engine, public API, or supported runtime disagree
- **THEN** the run fails with a reproducible seed and emits or records the smallest available schema, graph, request, and state trace

#### Scenario: Model-checker violation
- **WHEN** temporal exploration violates a cache, cursor, or causal-history invariant
- **THEN** the counterexample trace is translated into a failing executable regression test before the implementation is corrected

#### Scenario: Security-significant defect
- **WHEN** a counterexample permits a false grant, stale authorization, cross-scope cursor, or mixed-snapshot result
- **THEN** the bug ledger records affected versions/backends, security impact, root cause, fix, and closing proof/test

### Requirement: Verification harness mutation controls
The verification and differential harness SHALL contain registered incorrect variants representing each critical bug class, and the corresponding proof, model check, or differential target SHALL detect every registered variant.

#### Scenario: Known traversal mutant
- **WHEN** CI evaluates a registered wrong-direction, premature-cycle-cut, de-duplication, or frontier mutant
- **THEN** at least one required correctness gate fails

#### Scenario: Known cache or cursor mutant
- **WHEN** CI evaluates a registered incomplete-dependency, numeric-ancestry, scope-omission, fail-open, or publication-race mutant
- **THEN** at least one required correctness gate fails

### Requirement: Dimensionally sound resource regression gates
The verification harness SHALL keep logical work, backend operations, allocation, retained state, verifier effort, and wall time as separate resource dimensions. It SHALL prefer deterministic work/scaling assertions for algorithmic complexity and use host latency only as an explicitly qualified runtime gate.

#### Scenario: Point authorization over a broad subject
- **WHEN** `can?` checks one concrete resource while the subject can reach increasing numbers of unrelated resources
- **THEN** the generated authoritative engine anchors the search at the concrete resource and its backend/logical work does not grow merely with that unrelated subject fanout

#### Scenario: Host latency variability
- **WHEN** the same benchmark runs on hosts with different service rates
- **THEN** deterministic scaling/work gates still detect algorithmic regressions independently of the separately recorded absolute latency ceiling

### Requirement: Differential cutover evidence without a production rollback engine
The verified kernel SHALL NOT become authoritative until it agrees with the formal semantics and every unexplained difference from the former engine has been resolved. Unsound former behavior SHALL be corrected rather than encoded into the formal semantics solely for compatibility. Once those gates pass, EACL v8 SHALL contain one production authorization decision engine; former handwritten implementations MAY remain only as test oracles, characterization fixtures, and minimized counterexamples.

#### Scenario: Unexplained shadow disagreement
- **WHEN** shadow execution produces a legacy/verified difference that has not been classified against the formal semantics
- **THEN** the authoritative-engine rollout is blocked

#### Scenario: Former-engine false grant
- **WHEN** a minimized witness proves the former engine grants a tuple absent from the formal authorization relation
- **THEN** EACL fixes the behavior, retains the witness as a regression, and documents compatibility impact before rollout

#### Scenario: Released v8 runtime
- **WHEN** a consumer constructs a Datomic, Datahike, or DataScript client
- **THEN** every authorization, pagination, consistency, cursor, and cache decision uses the generated authoritative kernel and no engine-selection option can reactivate a handwritten production decision path

### Requirement: Reproducible verification and release evidence
The repository SHALL pin verification tools and solvers, verify downloaded tool artifacts, provide deterministic proof/model/build entry points, and generate a release manifest containing theorem status, tool and source digests, adapter certification, tested runtimes, counterexample-corpus revision, residual assumptions, and performance-gate status.

#### Scenario: Clean verification run
- **WHEN** a contributor or CI uses the documented pinned toolchain
- **THEN** the formal proofs, generated targets, temporal checks, regression corpus, and fast differential suite can be reproduced without relying on an unpinned global verifier

#### Scenario: Missing proof or admitted obligation
- **WHEN** a required theorem is unproved, admitted, times out, or relies on an undocumented assumption
- **THEN** the formal verification CI job fails and the release manifest does not mark the affected operation verified

#### Scenario: Release artifact
- **WHEN** an EACL release is prepared
- **THEN** its verification manifest identifies the exact proved source and generated artifacts shipped by that release

