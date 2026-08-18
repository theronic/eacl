# bounded-physical-execution Specification

> These deltas target requirements introduced by the in-progress
> `adopt-stable-discovery-enumeration` change. Archive that change first (or
> fold these deltas into it); the header text below matches its requirement
> names exactly.

## ADDED Requirements

### Requirement: Fetch replies are exact chunks

Every fetch-fn layer between the reducer and the adapter SHALL return exact chunks:
for a descriptor with exclusive bound `b` and positive physical limit `L`, the
layer (classification, retry, scan-response cache, telemetry) MUST return
exactly the first `min(L, remaining)` strictly ascending values greater than
`b` of the adapter's scan at the selected basis. A reply shorter than `L` MUST mean the
scan is exhausted after its last value. The reducer relies on this rule to
drop the scan frame; no layer may return a short reply for any other reason.

#### Scenario: Short reply

- **WHEN** a layer returns fewer than `L` values
- **THEN** no value greater than the last returned one exists in the scan at that basis

#### Scenario: Layer cannot satisfy the rule

- **WHEN** a layer holds fewer than `L` cached values after `b` and does not know the scan to be exhausted
- **THEN** it forwards the command to the next layer instead of replying

## MODIFIED Requirements

### Requirement: Chunk retention is bounded and disposable

Each open scan frame MAY retain its current fetched chunk for request-local reuse. Retained chunks MUST be bounded per request by count and weight, MUST be excluded from cursors and checkpoints, MUST be discarded on lifecycle or basis mismatch, and MUST be reconstructible from the authoritative logical bound — eviction merely causes a reread. Deep recursion MUST NOT accumulate unbounded per-depth buffers.

A client MAY additionally retain exact scan-response prefixes across requests in the scan-response cache (`exact-scan-response-cache`); such retention MUST be bounded by weight and per-entry cap, keyed by the complete validity scope including the scanned relation's generation, excluded from cursors and checkpoints, and reconstructible by reissuing the same command — its absence or eviction merely causes the original read.

The shell MUST demand only the physical values the page and its single semantic lookahead require; it MUST NOT request `physical-page-size + 1` when that value forces another backing read.

#### Scenario: Chunk eviction

- **WHEN** an unconsumed retained chunk is evicted under memory pressure
- **THEN** no cursor or checkpoint becomes invalid
- **AND** the next demand reissues the read from the authoritative logical bound

#### Scenario: Cross-request prefix eviction

- **WHEN** a scan-response prefix is evicted under weight pressure
- **THEN** no cursor, checkpoint, or answer becomes invalid
- **AND** the next request issues the original command it would have issued without the cache

#### Scenario: Interior storage boundary

- **WHEN** requesting one additional physical value would cross a backing-store boundary
- **THEN** the engine does not perform that read unless the canonical reducer actually needs it

### Requirement: The engine keeps exactly two closed cache artifacts

The engine caches exactly two semantic artifacts: progress checkpoints (complete quiescent reducer states for one exact execution identity) and completed answers (fully prepared public results). One physical accelerator is additionally permitted below the reducer: exact scan-response prefixes (`exact-scan-response-cache`), which replay adapter replies and never enter the reducer's semantic state. Byte and node caching belongs to the storage layer. An arbitrary traversal prefix MUST NOT be cached as a denotation or answer. A flat subproblem denotation MUST NOT be substituted into stable enumeration without a proof that substitution preserves the canonical discovery sequence, not merely set equality. Cancellation salvage is the latest valid checkpoint only.

The completed-answer key MUST incorporate the composite fingerprint. A cached answer containing pagination cursors MUST NOT be served across a basis change unless continuation of the embedded cursors remains permitted under the continuation rules (exact-basis reselection or the certified full-read-scope dependency proof).

#### Scenario: Timed-out long traversal

- **WHEN** a request times out after reaching a quiescent reducer boundary
- **THEN** the latest valid exact progress checkpoint may survive
- **AND** the incomplete page and partial denotation are not cached
- **AND** exact scan-response prefixes fetched before the timeout may be retained

#### Scenario: Set-equal cached subproblem

- **WHEN** a cached subproblem contains the correct unordered resources but lacks a sequence-refinement certificate
- **THEN** paginated enumeration does not substitute it into the reducer

#### Scenario: Scan-response prefix is not a subproblem

- **WHEN** the reducer's fetch is answered from a scan-response prefix
- **THEN** the reducer's admission, order, and discovered count are identical to the run that read the same values from the adapter

#### Scenario: Order flip does not serve stale-order answers

- **WHEN** the routing engine or order ABI changes within one process lifetime
- **THEN** completed answers keyed under the previous fingerprint are not served for the new one

#### Scenario: Cursor-bearing answer on a current-only topology

- **WHEN** a cached page whose cursors pin a superseded basis is requested on a topology that cannot reselect that basis
- **THEN** the answer is recomputed at a selectable basis
- **AND** a dead cursor is not served repeatedly from cache
