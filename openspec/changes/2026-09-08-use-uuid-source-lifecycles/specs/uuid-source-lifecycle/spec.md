## Purpose

Define a UUID-only authorization-source lifecycle contract that preserves cross-process identity, rejects obsolete artifacts, and separates proved correctness from measured representation costs.

## ADDED Requirements

### Requirement: Explicit lifecycle values are native UUIDs

Every explicitly supplied public source lifecycle SHALL be a native UUID in the supported host runtime representing a valid canonical 128-bit identity. EACL SHALL preserve immutable captured identity through public basis views and artifact round trips, including when the host permits mutation of caller-owned wrappers. UUID type/protocol membership alone SHALL not substitute for value validation. Strings, keywords, maps and vectors SHALL produce a typed lifecycle-upgrade-required error without implicit parsing, stringification or hashing. Present nil, false, malformed UUID instances and unsupported values SHALL fail with a typed invalid-lifecycle error rather than selecting a default. Text configuration parsing SHALL be explicit host-side work before calling EACL.

#### Scenario: UUID-shaped string is obsolete input
- **WHEN** a caller supplies a string containing a canonical UUID as its lifecycle
- **THEN** EACL reports that a native UUID is required and does not construct or rotate a client

#### Scenario: Explicit nil cannot select a default
- **WHEN** the lifecycle key is present with a nil value
- **THEN** construction fails without initializing reusable cache state

#### Scenario: Native UUID round trip
- **WHEN** a valid lifecycle UUID is exposed in a basis or decoded from a supported artifact
- **THEN** its type and all 128 bits equal the original value

#### Scenario: Host UUID wrapper does not contain a UUID
- **WHEN** a caller supplies a UUID instance containing malformed text or an object that only claims UUID protocol membership
- **THEN** EACL rejects it before construction or rotation

#### Scenario: Caller mutates an exposed basis UUID
- **WHEN** the caller attempts to mutate the UUID exposed by a public basis or decoded artifact
- **THEN** captured identity and equality/hash behavior remain unchanged

#### Scenario: Caller supplies a poisoned UUID hash
- **WHEN** a concrete CLJS UUID has valid text and an incorrect cached hash
- **THEN** capture constructs an independent UUID with the correct native hash before freezing it

#### Scenario: Caller mutates a previously supplied wrapper
- **WHEN** a host permits the caller to mutate its original UUID wrapper after EACL captures it
- **THEN** the captured lifecycle and every authority-bearing identity remain unchanged

### Requirement: Initial and coordinated lifecycle ownership remain explicit

Backends supporting an omitted lifecycle SHALL use the same documented reserved initial UUID across independent clients. Datalevin SHALL continue to require an explicit externally persisted lifecycle and SHALL reject the reserved initial UUID. Source identity and branch SHALL remain independently required. Restart and ordinary commits SHALL preserve the configured lifecycle. Full cache reset SHALL continue to accept the current UUID and retire private runtime incarnation. A transition from a noninitial lifecycle to the reserved initial UUID SHALL fail before changing state. Actual history replacement SHALL use a fresh coordinated noninitial UUID; same-UUID cache reset is not history-replacement evidence. Hosts SHALL coordinate and not recycle lifecycle identities across history replacement; EACL SHALL document this assumption without claiming the UUID type provides distributed coordination.

#### Scenario: Independent readers of the same durable source
- **WHEN** two supported clients use the omitted initial lifecycle or the same persisted explicit UUID with otherwise matching scope and keys
- **THEN** lifecycle validation permits their supported artifact exchange without per-reader randomization

#### Scenario: Datalevin lifecycle is not provisioned
- **WHEN** a Datalevin client is constructed without a persisted explicit UUID or with the reserved initial UUID
- **THEN** it fails before serving requests

#### Scenario: Return to retired initial identity
- **WHEN** a noninitial source is rotated to the initial sentinel
- **THEN** EACL rejects it and leaves the installed runtime intact

#### Scenario: Same-UUID full local reset
- **WHEN** full expiry is explicitly passed the current UUID
- **THEN** it preserves public identity, resets private incarnation and proof health, and prevents old in-flight or retained state from publishing into new stores

#### Scenario: Host lost a previously rotated lifecycle
- **WHEN** the host cannot recover the authoritative lifecycle of a previously rotated source
- **THEN** the supported recovery procedure requires unavailability or explicit coordinated recovery instead of silently resetting to the initial UUID

### Requirement: Canonical UUID representation preserves type and bounds

The upgraded portable format SHALL define one canonical UUID scalar representation with identical meaning and encoded bytes across supported runtimes. UUID and equal-looking string values SHALL remain distinct, including as collection keys. The decoder SHALL reject unknown tags, malformed or noncanonical UUID spellings and out-of-budget artifacts. Existing source-identifier and exact-locator field domains SHALL not expand merely because the serializer gains a UUID scalar.

#### Scenario: UUID and string coexist as keys
- **WHEN** an allowed map contains a UUID key and the string containing the same UUID text
- **THEN** encoding and decoding preserve both distinct entries without comparator collision

#### Scenario: Caller retains a narrower canonical domain
- **WHEN** a caller disables UUID scalars at a canonical boundary
- **THEN** encoding, canonicalization and decoding all reject UUIDs, including nested keys and values

#### Scenario: High-bit UUID crosses runtimes
- **WHEN** either 64-bit half contains values beyond JavaScript's exact Number integer range
- **THEN** CLJ-to-CLJS and CLJS-to-CLJ round trips preserve every bit

#### Scenario: Noncanonical typed UUID input
- **WHEN** an encoded UUID uses uppercase hexadecimal, an abbreviated spelling or an unrecognized tag
- **THEN** the artifact is rejected without authorization, backend selection or cache installation

### Requirement: Lifecycle replacement preserves retained-state isolation

Ordinary commits and narrow answer-cache clearing SHALL preserve the public lifecycle. Full lifecycle replacement SHALL detach the appropriate private runtime state according to existing snapshot ownership rules. Retained immutable snapshots SHALL remain evaluable under their captured identity but SHALL NOT repopulate replacement runtime state or issue artifacts falsely labeled with the new lifecycle. Private runtime incarnation identities SHALL remain private and independent from the public UUID.

#### Scenario: Old request completes after rotation
- **WHEN** a request captured the old runtime before a coordinated lifecycle change and completes afterward
- **THEN** its result follows the existing captured-snapshot contract and cannot publish into the replacement runtime

#### Scenario: Narrow clear races a validated restore
- **WHEN** narrow clear replaces cache stores while a validated restore is pending under the same source incarnation
- **THEN** restore may rebase and install atomically, whereas full expiry must invalidate the captured source incarnation

#### Scenario: Unrelated commit retains reusable proofs
- **WHEN** a commit leaves an answer's dependencies unchanged within the same lifecycle
- **THEN** the representation change does not prevent reuse allowed by the existing dependency proof

### Requirement: Upgraded lifecycle artifacts have explicit version boundaries

Every affected authenticated artifact and reusable identity domain SHALL distinguish its upgraded meaning from the legacy format. Unsupported legacy tokens, cursors and explicit snapshot restores SHALL return typed upgrade outcomes and SHALL NOT be translated or executed. UUID-shaped strings in old artifacts SHALL not be promoted to native UUID authority. New-format tampering SHALL retain invalid-artifact behavior. Version recognition alone SHALL never establish authenticity or permission.

#### Scenario: Legacy token contains a UUID string
- **WHEN** a caller submits a supported-to-recognize old-format token with a UUID-shaped lifecycle string
- **THEN** EACL requests a fresh artifact instead of selecting a source from that token

#### Scenario: Standalone cursor preflight
- **WHEN** standalone pagination receives an sd1 token, false, or a raw map as a cursor
- **THEN** it reports an upgrade-required error for sd1 and an invalid-cursor error for the other values before adapter operations, without restarting pagination

#### Scenario: Same UUID under a tampered new envelope
- **WHEN** a new-format artifact has a correct-looking UUID but fails authentication
- **THEN** it is rejected before source acquisition or authorization

### Requirement: UUID correctness is connected to production behavior

Release qualification SHALL include representation injectivity and equality-preserving refinement, complete lineage isolation and lifecycle-transition invariants connected to the actual supported runtimes. Independent executable tests and failing mutation controls SHALL cover host codec, source selection, artifact validation and runtime publication. Reports SHALL distinguish mathematical proof, bounded temporal exploration, empirical refinement and trusted assumptions; random UUID uniqueness and distributed host coordination SHALL not be claimed as unconditional theorems.

#### Scenario: Lifecycle or source scope is omitted
- **WHEN** a mutation removes either lifecycle or source scope from an authority-bearing identity comparison
- **THEN** the corresponding negative control fails the release gate with a concrete isolation witness

#### Scenario: Codec truncates a UUID half
- **WHEN** a mutated runtime codec drops or rounds some UUID bits
- **THEN** independent cross-runtime refinement rejects the implementation

### Requirement: UUID efficiency is measured without weakening existing gates

Qualification SHALL compare native UUIDs with current UUID strings, structured lifecycles and the short initial default under matched host/runtime workloads. It SHALL cover allocation, retained memory, encode/decode and authenticated sizes, cache hit/miss/bypass, rotation, token/cursor operations and snapshots. Existing deterministic work and performance gates SHALL remain binding. Missing UUID-specific budgets SHALL be authored before candidate sampling. The change SHALL add no required storage/network call or per-hit UUID parsing and SHALL report a measured tie as no demonstrated speedup.

The operator-reviewed acceptance of 2026-09-08 makes this change's UUID-specific CLJS latency and retained-heap comparisons diagnostic for the in-browser DataScript demo use case. Qualification SHALL retain and disclose the original failed comparisons separately from the accepted trade-off. JVM budgets, correctness and formal gates, deterministic work, and wire/resource limits SHALL remain binding.

#### Scenario: Native UUID reduces heap but grows tokens
- **WHEN** a candidate allocates less but produces larger authenticated artifacts
- **THEN** qualification reports both effects, checks predeclared byte ceilings and does not claim a wire-size reduction

#### Scenario: Unmatched benchmark hosts
- **WHEN** the baseline and candidate host/runtime classes do not match
- **THEN** the ratio result is not applicable rather than passed and cannot replace matched-host release evidence

#### Scenario: Post-sampling budget relaxation
- **WHEN** a candidate exceeds a predeclared performance or resource budget
- **THEN** qualification fails until the implementation changes or a separately reviewed requirement revision authorizes the trade-off

#### Scenario: Reviewed CLJS trade-off
- **WHEN** the UUID change's CLJS latency or retained-heap comparison exceeds its original diagnostic budget
- **THEN** qualification records the failed comparison and the operator's scoped acceptance separately, without claiming a speedup or weakening JVM, correctness, deterministic-work or artifact-bound requirements
