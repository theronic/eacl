# stable-discovery-enumeration Specification

## Purpose

Define the exact semantics, stable order, deduplication, pagination, cursor, continuation, and consistency contracts for EACL lookup enumeration after removal of global entity-ID ordering.

## ADDED Requirements

### Requirement: Declarative answer and operational order are separate

For one immutable basis and normalized positive permission program, EACL MUST return exactly the root resources in the program's least-fixed-point denotation. Their public order MUST be the first-discovery order of the versioned canonical reducer, not a global entity-ID sort or physical completion order.

#### Scenario: Complete traversal

- **WHEN** a finite valid lookup is run to exhaustion
- **THEN** its unordered result set equals the least-fixed-point answer
- **AND** its ordered result sequence equals the canonical reducer's first-discovery sequence

#### Scenario: Equal logical execution

- **WHEN** the same exact basis, normalized query, sealed plan, order ABI, adapter order contract, and page size are evaluated twice
- **THEN** both evaluations produce the same result sequence and page boundaries

### Requirement: Sealed plans define every semantic ordering input

The compiler MUST produce an immutable sealed plan containing direction-specific static rule vectors, dense canonical ordinals, forward consumers indexed by granted node, reverse rules indexed by head node, a certified static shortest-remaining-storage-read-distance rank (0/1 edge costs, linear checker), and the transition descriptors and admission-key codecs the generic reducer executes. Each alternative vector MUST be ordered by `(rank, canonical-ordinal)`. The complete plan, order contract, transition interface, admission-key granularity, and adapter scan-order contract MUST be folded into one composite fingerprint bound to cursors, checkpoints, and completed-answer cache keys.

Mutable cache state, latency, worker completion, host map iteration, physical chunk width, and runtime byte comparison MUST NOT affect the sealed plan's semantic order. Plan compilation MUST NOT consult relationship data.

#### Scenario: Cost-ranked alternatives

- **WHEN** several rules can expand the same logical node
- **THEN** the reducer visits them in increasing certified rank and then canonical ordinal
- **AND** changing runtime latency or cache residency does not change that order

#### Scenario: Incompatible plan

- **WHEN** a cursor's composite fingerprint or order ABI differs from the current executable contract
- **THEN** EACL rejects the cursor explicitly
- **AND** does not reinterpret it under the new plan

### Requirement: One generic reducer with exact per-kind admission

Both traversal directions MUST execute on one generic pure reducer over the sealed plan's transition interface, using a persistent work stack, an exact admitted-identity set, static plan cursors, a scalar discovered-result count, and logical scan frames. Direction MUST be supplied entirely by plan compilation. The reducer MUST NOT maintain dynamic symmetric grant/consumer buckets, joined-pair history, or a separate emitted-result set.

Admission-key granularity is fixed by the plan and is part of the order ABI: merge-point work (forward grants, reverse goals) MUST be keyed by target node plus entity identity, never by producing rule, so overlapping derivations deduplicate; scan occurrences MUST be keyed by rule ordinal plus binding, excluding the resume bound.

#### Scenario: Duplicate derivations

- **WHEN** overlapping or recursive paths derive the same logical work identity more than once
- **THEN** only its first exact admission expands
- **AND** its root resource appears at most once in the complete result sequence

#### Scenario: Recursive cycle

- **WHEN** a recursive relationship cycle revisits an admitted logical goal
- **THEN** the duplicate goal is ignored without further expansion
- **AND** finite valid traversal still terminates or reaches an explicit configured resource limit

#### Scenario: Union arms reach one resource

- **WHEN** two union alternatives derive the same target node for the same entity
- **THEN** the second derivation is deduplicated by the merge-point admission key
- **AND** the resource is emitted exactly once

### Requirement: Result uniqueness holds by construction

Every compiled plan MUST have a single root emission point whose admission key is the emitted entity's identity; the first admission of that key is the emission. No compiler injectivity proof obligation and no separate emitted-result set are required. Each adapter MUST certify a total injective mapping between internal and external identities; an adapter that violates it fails certification.

#### Scenario: Multiple root derivations

- **WHEN** distinct derivation paths reach the root for the same entity
- **THEN** only the first reaching derivation emits
- **AND** later derivations are suppressed by the root admission key

### Requirement: The canonical reducer is pure and head-driven

For a sealed plan and immutable adapter values, each reducer step MUST be deterministic and MUST either perform one bounded pure transition or return the exact read demand for the canonical head without changing semantic state. Only a fully validated response for that exact head may be integrated, and its semantic effects MUST commit atomically: if integrating a response would exceed any semantic limit, EACL MUST report a typed uncommitted failure with no subset of the response's identities admitted.

Reducer state MUST exclude futures, backend handles, clocks, request IDs, cache epochs, physical permits, retry state, and response buffers.

#### Scenario: Read is required

- **WHEN** the canonical head requires storage and no validated response is available
- **THEN** the reducer returns the exact read demand
- **AND** leaves its semantic state unchanged

#### Scenario: Limit failure during integration

- **WHEN** integrating a response would exceed an exact semantic-state limit
- **THEN** EACL reports a typed uncommitted failure
- **AND** no subset of the response's logical identities is admitted

### Requirement: Logical release width is fixed at one value

The reducer MUST release exactly one ordered scan value per logical transition. Logical release width MUST NOT be configurable and MUST NOT appear in the order ABI. An adapter MAY fetch a bounded ordered chunk, but the authoritative scan frame MUST store only the logical exclusive continuation bound; fetched chunks remain detachable request-local buffers excluded from cursors and checkpoints. A logical-width mutant control MUST guard the release gate.

#### Scenario: Different physical chunk sizes

- **WHEN** the same strict scan is fetched with different legal chunk sizes
- **THEN** both executions produce the same logical transition trace and result sequence

#### Scenario: Page boundary inside a chunk

- **WHEN** a page finishes after consuming only part of a fetched chunk
- **THEN** the unconsumed values remain optional private acceleration only
- **AND** replay from the authoritative logical bound produces the same suffix

### Requirement: Pagination uses authenticated result edges

A public cursor MUST be a bounded HMAC-authenticated token binding the cursor format version, the exact basis, backend and source lifecycle, normalized query and principal, composite fingerprint, order-ABI version, fixed positive page size, boundary result's one-based ordinal, canonical external identity, expiry, and key version. Source lifecycle names the logical storage generation of the backend source (the identity that changes on store re-creation, restore-from-backup, or destructive maintenance); a lifecycle mismatch MUST reject the cursor. It MUST NOT contain private reducer state, a cache pointer, a probabilistic filter, or a rolling prefix commitment. Authentication is integrity-only; confidentiality is not required.

The same edge token MAY be used as an `after` or `before` boundary; navigation mode is request input, not cursor identity.

#### Scenario: Forward navigation

- **WHEN** a valid edge at ordinal `i` is used with `after`
- **THEN** checkpoint restoration or replay validates that edge
- **AND** the next page starts at ordinal `i + 1`

#### Scenario: Backward navigation

- **WHEN** a valid edge at ordinal `i` is used with `before` and page size `p`
- **THEN** EACL resumes positioned after ordinal `max(0, i - 1 - p)`, so the returned page contains ordinals `max(1, i - p)` through `i - 1` in forward order
- **AND** validates the supplied edge at ordinal `i` as the final lookahead

#### Scenario: Page-size change

- **WHEN** a caller presents a cursor with a different page size
- **THEN** EACL rejects it as incompatible

### Requirement: Pages compose without gaps or duplicates

Page construction MUST retain at most one undelivered lookahead result beyond the page. Concatenating every forward page from a valid start MUST equal the complete canonical sequence exactly. Repeating or concurrently forking an earlier cursor MUST be idempotent and MUST NOT consume its parent continuation.

#### Scenario: Full pagination

- **WHEN** a caller follows every `after` cursor to exhaustion
- **THEN** every complete result appears exactly once
- **AND** concatenation equals a one-shot traversal at the same basis and plan

#### Scenario: Repeated cursor

- **WHEN** the same valid cursor is used multiple times or concurrently
- **THEN** every use is side-effect free and every successful use returns the identical page (the outcome may vary between success and typed resource-exhaustion with checkpoint residency)
- **AND** later navigation does not invalidate the earlier edge while its basis and token remain valid

### Requirement: Exact continuation state is retained or reconstructed

EACL MUST implement exact continuation through an immutable quiescent checkpoint or deterministic replay from the sealed initial state. A checkpoint MUST contain complete history-free reducer state — including the undelivered lookahead/boundary segment — sufficient to produce the residual canonical suffix, and MUST exclude physical buffers. On a checkpoint miss, EACL MUST replay under governed work and time limits and validate the cursor boundary's ordinal and external identity before returning results.

Approximate continuation and false-positive deduplication are forbidden. When continuation budgets or checkpoint weight limits make a page unreachable, EACL MUST fail with a typed resource-exhaustion result distinct from stale-cursor.

#### Scenario: Checkpoint miss

- **WHEN** private continuation state has been evicted
- **THEN** EACL deterministically replays the canonical prefix under governed work and time limits
- **AND** either validates the exact edge and continues or fails without publishing a page

#### Scenario: Lookahead survives checkpointing

- **WHEN** a checkpoint is captured after the has-next lookahead discovered a result
- **THEN** resuming from that checkpoint delivers the pending result at the next ordinal
- **AND** no result is lost to admission-key suppression

#### Scenario: Unreachable deep page

- **WHEN** the admission set exceeds the checkpoint weight cap and replay exceeds its budget
- **THEN** EACL returns a typed resource-exhaustion failure
- **AND** does not report the cursor as stale

### Requirement: Continuation respects consistency and basis rules

Cursor continuation MUST satisfy the request's consistency mode, evaluated against the basis actually used for continuation. Continuation on a basis other than the cursor's exact basis is permitted only under a certified dependency proof covering the traversal's full read scope — every scan replay performs, including observed-empty ranges — unchanged since the cursor basis. The typed cursor-consistency-conflict fires exactly when no permissible continuation basis (the exact basis, or a newer basis admitted by such a proof) satisfies the request's freshness requirement; a stale basis is never served silently. Absent a proof, a topology that cannot reselect the exact basis MUST reject continuation with a stale-cursor or exact-basis-unavailable result. Reminting a cursor against a changed basis at the same entity ID or numeric position is forbidden.

#### Scenario: Fresher-than-cursor requirement

- **WHEN** a continuation request carries a freshness requirement that no permissible continuation basis satisfies
- **THEN** EACL fails with the typed cursor-consistency-conflict

#### Scenario: Current-only topology with certified proof

- **WHEN** the topology cannot reselect the exact basis but certifies the traversal's full read scope unchanged
- **THEN** continuation proceeds on the newer basis
- **AND** produces the sequence the exact basis would have produced

#### Scenario: Database basis changed without proof

- **WHEN** the exact cursor basis cannot be selected and no full-read-scope proof exists
- **THEN** EACL returns an exact-basis-unavailable or stale-cursor result
- **AND** does not silently continue on the new basis

### Requirement: Point checks and counts keep operation-appropriate plans

Point authorization MUST remain anchored to the known subject and resource with early termination; it MUST NOT enumerate the root universe to answer one known-resource question. Exact count MUST exhaust the exact history-free stable-discovery reducer by default; its result equals the cardinality of the complete denotation. Count MAY use an order-insensitive specialization only if that route is independently proven equal to the lookup denotation. Stable discovery order is required for paginated enumeration, not for internal aggregation.

#### Scenario: Point authorization

- **WHEN** the caller asks whether one known resource is authorized
- **THEN** EACL does not enumerate unrelated roots merely to reuse the forward page engine

#### Scenario: Exact count specialization

- **WHEN** a backend-specific count route is selected
- **THEN** its returned count equals the cardinality of the complete stable-discovery denotation
