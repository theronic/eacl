# bounded-physical-execution Specification

## Purpose

Define how width-one physical execution, atomic failure classification, cancellation, chunk retention, checkpoints, and the two closed cache artifacts refine the canonical stable-discovery reducer without changing public semantics. This capability replaces the earlier `deterministic-concurrent-traversal` draft: concurrent physical execution is out of scope and is preserved only as a designed seam.

## ADDED Requirements

### Requirement: Logical occurrence and physical read identities are distinct

Every canonical scan occurrence MUST have an exact logical identity including its semantic continuation. A physical read descriptor MUST separately include the backend and source lifecycle, exact basis, index or operation, all bounds, exact exclusive physical position, projection, requested physical limit, and chunk ABI. A transport attempt MUST have a third identity used only for cancellation, retry, and telemetry. Descriptor equality MUST compare complete canonical values, not hashes alone; physical position and physical chunk limit MUST NOT alter the logical occurrence identity.

#### Scenario: Descriptor equality is well defined

- **WHEN** the same logical occurrence is retried after a retryable failure
- **THEN** the retry uses an equal physical descriptor at the same exact basis
- **AND** telemetry counts a new transport attempt for the same descriptor

### Requirement: Physical execution is width one and serially integrated

All physical reads in this change execute at effective width one on every topology through the direct reducer-to-adapter path: no executor tasks, futures, descriptor index, speculative issue, or read coalescing. Only the response for the current canonical head integrates. A future concurrency change MUST refine width-one execution — identical successful result sequence, page membership, cursors, and checkpoints — and MUST NOT alter the order ABI; the parked read-ahead, coalescing, and capacity models are its starting obligations. The adapter capability record MUST keep semantic concurrent-read safety separate from deployment width so that change requires no SPI break.

#### Scenario: Direct path everywhere

- **WHEN** any qualified topology executes a lookup
- **THEN** execution uses the direct width-one path
- **AND** still observes request cancellation, deadlines, and service admission

#### Scenario: Future concurrency cannot change output

- **WHEN** a later change introduces physical concurrency for a topology
- **THEN** its successful public output equals width-one execution at the same basis and plan

### Requirement: Adapter reads have exactly three classified outcomes

A physical attempt MUST produce exactly one of: complete (validated, strictly ordered, duplicate-free, bounded, strictly progressing values — possibly legitimately empty), failure (classified retryable or terminal, carrying a machine-readable cause code such as missing-node, decode-error, or retry-exhausted, plus the count of any discarded partial values), or cancelled. Only complete integrates. Partial values, missing storage nodes, deserialization errors, and retry exhaustion MUST leave reducer state unchanged and MUST be reported as failures, never as empty results. An adapter that collapses failures into empty results or unclassified nil is not qualified.

EACL's own exact-basis selection MUST follow the same discipline: genuine basis absence maps to the contractual unavailable signal; every other Throwable propagates as a classified retryable or terminal failure. Catching Throwable and returning nil is forbidden.

#### Scenario: Partial backend response

- **WHEN** a backend produces some values and then fails
- **THEN** the attempt is discarded atomically as a classified failure
- **AND** none of those values enters the reducer or any cache

#### Scenario: Legitimate empty scan

- **WHEN** the adapter certifies successful exhaustion with no values
- **THEN** the reducer integrates the empty result as semantic completion

#### Scenario: Transient fault during exact-basis selection

- **WHEN** a transient storage fault occurs while reselecting a cursor's exact basis
- **THEN** EACL reports a classified retryable failure
- **AND** does not report the cursor's snapshot as expired

#### Scenario: Read completes after cancellation

- **WHEN** a synchronous backend read physically completes after the request's cancellation signal was observed
- **THEN** the attempt outcome is recorded as cancelled at its transport-attempt identity
- **AND** the response is discarded without integrating or caching

### Requirement: Retries preserve the semantic read

Every retry MUST retain the exact basis and descriptor, use the original absolute deadline, discard partial output, and be counted separately from logical scan occurrences. Nested driver or SDK retries MUST be inventoried in the topology's physical exposure model.

#### Scenario: Retryable failure

- **WHEN** the canonical read fails with a certified retryable cause before the deadline
- **THEN** a retry may issue for the same exact descriptor
- **AND** no relative timeout extends the original deadline

### Requirement: Chunk retention is bounded and disposable

Each open scan frame MAY retain its current fetched chunk for request-local reuse. Retained chunks MUST be bounded per request by count and weight, MUST be excluded from cursors and checkpoints, MUST be discarded on lifecycle or basis mismatch, and MUST be reconstructible from the authoritative logical bound — eviction merely causes a reread. Deep recursion MUST NOT accumulate unbounded per-depth buffers.

The shell MUST demand only the physical values the page and its single semantic lookahead require; it MUST NOT request `physical-page-size + 1` when that value forces another backing read.

#### Scenario: Chunk eviction

- **WHEN** an unconsumed retained chunk is evicted under memory pressure
- **THEN** no cursor or checkpoint becomes invalid
- **AND** the next demand reissues the read from the authoritative logical bound

#### Scenario: Interior storage boundary

- **WHEN** requesting one additional physical value would cross a backing-store boundary
- **THEN** the engine does not perform that read unless the canonical reducer actually needs it

### Requirement: Cancellation is cooperative, nonpublishing, and physically accounted

The host MUST check cancellation, the original absolute deadline, service retirement, and work budgets at bounded reducer cut points, before and after backend issue and integration, during rendering, and before response publication. Once the cancellation signal is observed at a cut point, no further semantic transitions, backend issues, or retries may occur, and publication of an uncommitted page or child cursor MUST be prevented; the immutable parent cursor remains reusable. A cancelled request's service-admission slot MUST remain held until its synchronous backend call physically returns, because a deadline is not a physical lifetime bound on the reviewed stores.

#### Scenario: HTTP disconnect during traversal

- **WHEN** the request cancellation signal fires before a page is committed
- **THEN** EACL stops semantic traversal and new physical issue at the next cut point
- **AND** the immutable parent cursor remains reusable

#### Scenario: Canceled noninterruptible read

- **WHEN** a request cancels while its backend call continues running
- **THEN** semantic processing stops immediately
- **AND** the admission slot remains charged until the call terminates

### Requirement: Service-edge admission bounds aggregate exposure

One service-edge admission component MUST bound concurrent enumerations, hold slots per the cancellation rule, and own the replay admission ledger (bounded total replays, bounded waiters, per-admission-key quotas). Per-request work, time, and memory budgets come from the execution contract. There is no speculative capacity, canonical reserve, or weighted response lease in this change.

#### Scenario: Replay stampede

- **WHEN** many requests miss checkpoints for the same continuation concurrently
- **THEN** the replay ledger admits a bounded number and coalesces or rejects the rest with a typed result

### Requirement: Progress checkpoints are latest, exact, and bounded

A progress checkpoint MUST be captured only between reducer transitions with no pending integration. It MUST contain exact immutable semantic state — including the undelivered lookahead/boundary segment — and record delivered and discovered counts distinctly. It MUST exclude physical buffers. It MUST be keyed by the complete exact execution identity: backend and source lifecycle, exact basis, normalized query and principal, traversal direction, composite fingerprint, order-ABI version, and fixed page size. For one key, only a checkpoint with a strictly greater canonical transition ordinal may replace the retained checkpoint. Publication MUST be synchronous, bounded, best effort, and `O(1)` with respect to traversal size; there is no asynchronous checkpoint queue. The checkpoint store MUST expose entry-count and byte-weight caps as client configuration, and an overweight checkpoint is dropped without failing the otherwise replayable request.

#### Scenario: Older completion arrives later

- **WHEN** a slower older execution attempts to publish after a newer checkpoint exists for the same key
- **THEN** the retained checkpoint does not regress

#### Scenario: Overweight checkpoint

- **WHEN** a complete checkpoint exceeds its configured weight
- **THEN** publication is dropped without failing the request

#### Scenario: Plan change does not alias checkpoints

- **WHEN** the composite fingerprint changes without a schema change
- **THEN** old checkpoints are unreachable under the new fingerprint
- **AND** continuation degrades to replay or explicit cursor rejection

### Requirement: The engine keeps exactly two closed cache artifacts

The engine caches exactly: progress checkpoints (complete quiescent reducer states for one exact execution identity) and completed answers (fully prepared public results). Byte and node caching belongs to the storage layer. An arbitrary traversal prefix MUST NOT be cached as a denotation or answer. A flat subproblem denotation MUST NOT be substituted into stable enumeration without a proof that substitution preserves the canonical discovery sequence, not merely set equality. Cancellation salvage is the latest valid checkpoint only.

The completed-answer key MUST incorporate the composite fingerprint. A cached answer containing pagination cursors MUST NOT be served across a basis change unless continuation of the embedded cursors remains permitted under the continuation rules (exact-basis reselection or the certified full-read-scope dependency proof).

#### Scenario: Timed-out long traversal

- **WHEN** a request times out after reaching a quiescent reducer boundary
- **THEN** the latest valid exact progress checkpoint may survive
- **AND** the incomplete page and partial denotation are not cached

#### Scenario: Set-equal cached subproblem

- **WHEN** a cached subproblem contains the correct unordered resources but lacks a sequence-refinement certificate
- **THEN** paginated enumeration does not substitute it into the reducer

#### Scenario: Order flip does not serve stale-order answers

- **WHEN** the routing engine or order ABI changes within one process lifetime
- **THEN** completed answers keyed under the previous fingerprint are not served for the new one

#### Scenario: Cursor-bearing answer on a current-only topology

- **WHEN** a cached page whose cursors pin a superseded basis is requested on a topology that cannot reselect that basis
- **THEN** the answer is recomputed at a selectable basis
- **AND** a dead cursor is not served repeatedly from cache

### Requirement: Retained state is compact and owned

The engine MUST use specialized immutable per-kind admission key representations (not generic vectors) on the JVM, request-owned transient builders with freeze-before-publish and fork-from-persistent discipline enforced by the engine (the runtime does not enforce transient thread ownership), a right-edge work stack, and compact logical scan frames. Frozen published state MUST be immutable; physical workers and caches never receive builders. Memory qualification measures these representations directly.

#### Scenario: Publication freezes state

- **WHEN** a checkpoint or page is published
- **THEN** the published state is immutable
- **AND** subsequent request mutation forks from a persistent snapshot
