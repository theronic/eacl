# implementation-simplicity-and-performance Specification

## Purpose
TBD - created by archiving change demand-bounded-authorization-execution. Update Purpose after archive.
## Requirements
### Requirement: One semantic execution pipeline

EACL MUST normalize each public authorization request once and route cache hits,
cache misses, and cache bypasses through one semantic execution pipeline. Cache
reuse MAY remove exact evaluator commands or supply a completed compatible
artifact, but MUST NOT select a second cache-specific evaluator, stopping rule,
consistency path, error mapping, or resource-limit policy.

#### Scenario: Cold miss and bypass share production control flow

- **WHEN** equivalent requests execute against the same selected snapshot with a
  cold enabled cache and with `:cache? false`
- **THEN** both requests traverse the same normalized execution-contract and
  generated-evaluator entry points
- **AND** their production branch ledger differs only at bounded cache lookup
  and publication boundaries
- **AND** their semantic results, errors, commands, stopping reason, and limits
  are identical

#### Scenario: Warm reuse remains a decorator

- **WHEN** a compatible warm artifact answers part or all of a request
- **THEN** cache validation and substitution occur at a typed boundary in the
  same pipeline
- **AND** no duplicated cache-only implementation of authorization semantics is
  authoritative

### Requirement: Superseded stateful mechanisms are removed

The v8 implementation MUST delete superseded full-denotation-on-demand-default,
cache-owned projection widening, caller-waiting single-flight, cache computation
semaphores, EACL schema read locks, and DataScript historical-registry paths once
their replacements pass the release gates. Compatibility shims MUST be limited
to input validation or typed migration errors and MUST NOT retain an old
semantic evaluator or mutable coordination path.

#### Scenario: Source inventory rejects an obsolete path

- **WHEN** the release source inventory detects an obsolete coordinator,
  evaluator entry point, lock acquisition, registry, or cache-owned traversal
- **THEN** verification fails with the exact production symbol and owning claim
- **AND** the release cannot qualify the implementation as simplified

### Requirement: Structural simplicity is measured by semantic machinery

EACL MUST maintain a checked-in, source-derived structural inventory covering
public-to-engine semantic paths, answer-affecting branches, mutable coordinators,
locks/semaphores, background tasks, cache artifact kinds, and generated/host
semantic forks. The release value for each category MUST be no greater than its
pre-change baseline unless a reviewed requirement identifies the exact addition
and an equal-or-greater superseded mechanism is removed.

#### Scenario: Cosmetic line deletion cannot mask new complexity

- **WHEN** total source lines decrease but an answer-affecting branch, mutable
  coordinator, semantic fork, or background task is added
- **THEN** the structural inventory reports the category increase
- **AND** the simplicity gate fails absent an explicit reviewed replacement row

### Requirement: Deterministic work does not regress

The new implementation SHALL preserve results and non-regressing logical work
for the checked-in point, bounded-count, exact-count, first-page, continued-page,
cache-hit, miss, and bypass corpus
while holding each executed-command, fetched-value, consumed-value, generated
transition, allocation-proxy, and retained-logical-unit metric at or below its
ratcheted baseline. A cache miss MUST equal the bypass trace; a cache hit MUST be
a command-removing refinement. The gate MUST compare logical metrics and MUST
NOT use wall-clock time as a semantic substitute.

#### Scenario: Faster common case hides a work regression

- **WHEN** median wall-clock time improves but any deterministic work metric
  exceeds its operation/shape baseline
- **THEN** the deterministic non-regression gate fails
- **AND** the regression cannot be waived by the timing result alone

### Requirement: Measured performance is separately regression-gated

Pinned, reproducible benchmarks MUST compare client-private cache bookkeeping,
point checks, bounded counts, exact counts, and first/continued pages against
checked-in fixed and ratio thresholds for latency, throughput, allocation, and
retained memory where the runtime exposes them. Measurements MUST disclose
warmup, sample count, runtime, hardware class, backend, variance, and confidence
method, and MUST remain explicitly distinct from proved semantic/work claims.
Relative latency gates MUST compare only exactly matching host and JVM classes,
including processor, memory, heap, JDK, and VM implementation. Missing baseline
metadata MUST be a harness error. A mismatched or incompletely observable
current class MUST report the ratio gate as `not-applicable`, list missing
fields, never report the ratio as passed, and continue to enforce every portable
correctness and deterministic-work gate. Release qualification MUST include
applicable matched-host evidence; a portable work-only CI pass is not a
substitute.

#### Scenario: Candidate exceeds a performance threshold

- **WHEN** a statistically valid benchmark exceeds either its absolute ceiling
  or permitted baseline ratio after the prescribed rerun protocol
- **THEN** the release gate fails with the raw samples and environment metadata
- **AND** no deterministic proof claim is rewritten to imply a wall-clock proof

#### Scenario: Platform-sensitive artifact representation

- **WHEN** supported compiler, JVM, operating-system, or compression
  implementations produce semantically equivalent artifacts with different
  byte representations
- **THEN** exact reference measurements retain their complete environment as
  evidence but MUST NOT be enforced as cross-platform equality
- **AND** the release gate enforces reviewed absolute and incremental ceilings,
  production-graph exclusions, and semantic parity independently
- **AND** the allowed variance MUST NOT permit any applicable ceiling or
  forbidden-runtime invariant to be bypassed

### Requirement: Simplicity and performance evidence is certification-linked

Every structural inventory and non-regression result MUST be mapped into the
implementation-conformance ledger and reproducible certification bundle with
source closure and digests. Deleted paths MUST have negative source assertions,
and measured results MUST carry the exact production artifact digest they test.

#### Scenario: Evidence targets a different binary or source closure

- **WHEN** simplicity or performance evidence does not match the candidate
  production artifact and source-closure digest
- **THEN** the conformance gate rejects the evidence as stale
- **AND** prospective external certification remains unavailable

### Requirement: Exact semantic aliases do not duplicate traversal

After sealing and certifying the complete semantic graph as acyclic, EACL SHALL
derive its least-path execution frontier and canonicalize an arrow target only
when the target permission's complete normalized body is exactly one
same-resource self-permission. Resolution SHALL stop conservatively at a
missing definition, composite body, relation-dependent body, or cycle. Before
normalization, each path SHALL receive a provider-order-independent canonical
position. Canonicalization SHALL remove only frontier entries equal in every
semantic and work field other than that position, including source identity,
direction, relation/path, target type and permission, physical order and
capability, admission-key granularity, and static limit/cursor ABI or
coordinate interpretation. Equal entries SHALL retain the earliest
pre-normalization canonical position. EACL SHALL rebuild only the affected
acyclic frontier indexes, order certificate, and fingerprint input. It MUST
NOT collapse the requested root, remove semantic nodes or reachability, rewrite
a recursive/cyclic stable plan, inspect relationship data to infer an alias,
widen a backend scan, or change denotation or public sequence.

#### Scenario: Two arrow targets differ only by a pure alias

- **WHEN** two acyclic arrow paths have complete equal semantic/work identities after their target permissions resolve through exact pure aliases
- **THEN** EACL constructs one canonical traversal stream at the earliest deterministic pre-normalization path position
- **AND** exact count, page order, point decisions, and stopping conditions are unchanged

#### Scenario: Composite or cyclic permission body

- **WHEN** a target definition is missing, composite, relation-dependent, or revisited by a pure-alias chain
- **THEN** EACL does not erase a component or recurse indefinitely
- **AND** cycle handling remains conservative and denotationally correct

#### Scenario: Alias appears in a recursive plan

- **WHEN** the sealed semantic graph is recursive or cyclic
- **THEN** alias canonicalization does not rewrite the root, semantic nodes,
  reachability, or recursive scheduling

#### Scenario: Provider encounter order changes

- **WHEN** equivalent normalized schema rows arrive in different provider order
- **THEN** the canonical frontier identities, retained first positions, public
  sequence, and fingerprint are identical
