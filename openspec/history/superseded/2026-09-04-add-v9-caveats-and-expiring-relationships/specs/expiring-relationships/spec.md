## Purpose

Define expiry-only Relationship behavior, trusted evaluation-time snapshots, consumer-owned future creation, and bounded optional collection without depending on a timely boundary transaction.

## ADDED Requirements

### Requirement: Relationships have one optional exclusive expiry

Each stored Relationship SHALL have an optional `valid-until` represented identically in slot seven of both endpoint tuples. EACL SHALL normalize supplied values to exact UTC epoch-millisecond integers in the inclusive range `[-9007199254740991, 9007199254740991]`. Nil SHALL mean no expiration; no integer sentinel SHALL mean infinity. Non-integer, out-of-range, and precision-losing inputs SHALL fail with a typed invalid-expiry error before submission.

A well-formed stored Relationship SHALL be expiry-active exactly when its bound is nil or the selected evaluation time is strictly less than the bound. Eligibility remains subject to Caveats and the selected permission expression.

#### Scenario: Permanent Relationship

- **WHEN** expiry is omitted
- **THEN** both tuples contain nil in slot seven and time passage alone does not expire the Relationship

#### Scenario: Exact expiry boundary

- **WHEN** evaluation time equals `valid-until`
- **THEN** the Relationship is inactive in both traversal directions

#### Scenario: Last representable deadline

- **WHEN** expiry is 9007199254740991
- **THEN** it remains finite and is inactive at that exact evaluation time

#### Scenario: Precision would be lost

- **WHEN** normalization would round or overflow a supplied deadline
- **THEN** admission fails without writing endpoint or context data

### Requirement: Future creation belongs to consumers

EACL SHALL expose no scheduled activation through Relationship `valid-from`, transaction metadata, or schedule references in this v9 contract. Unsupported start/schedule qualifiers SHALL be rejected rather than ignored. A successfully stored Relationship SHALL be eligible as soon as a reader selects a database basis containing it, subject to expiry and Caveats.

Consumers SHALL create future Relationships through ordinary writes when due. The contract SHALL permit delayed insertion and SHALL NOT silently extend an absolute deadline to compensate for that delay. Already-expired valid writes SHALL be admitted as stored but inactive Relationships, subject to normal identity conflicts.

#### Scenario: Consumer creates when due

- **WHEN** a consumer transacts a due Relationship and a reader selects the committed basis containing it
- **THEN** the Relationship can participate immediately if unexpired and its Caveat permits
- **AND** the write invalidates affected Relation dependencies normally

#### Scenario: Delayed creation misses its expiry

- **WHEN** a delayed write creates a Relationship whose supplied deadline has passed
- **THEN** the stored deadline is unchanged and the Relationship is inactive

#### Scenario: Start bound is supplied

- **WHEN** a write supplies `valid-from` or a schedule qualifier
- **THEN** it fails with a typed unsupported-qualifier error
- **AND** it does not silently create an immediately active Relationship

### Requirement: Expiry needs no boundary write

Expiration SHALL be determined from the authoritative tuple and selected time without a timer, scheduler, transaction, listener notification, cache-eviction callback, or collection pass. Passing time SHALL NOT bump `:eacl/relation-version`. Time-dependent reusable results SHALL instead satisfy the expiry-certificate contract.

#### Scenario: No writes at the deadline

- **WHEN** time reaches expiry while the database basis and Relation versions stay unchanged
- **THEN** a fresh client-targeted authorization excludes the expired Relationship

#### Scenario: Collector is disabled

- **WHEN** expired tuples remain indefinitely because no collector runs
- **THEN** expiration remains correct for checks, lookups, counts, and every permission operator

### Requirement: Expiry applies to positive and subtracting evidence

Expiry SHALL be checked before an edge contributes to union, intersection, exclusion, arrow traversal, recursion, lookup, count, or explanation. An inactive Relationship SHALL contribute no positive, negative, or conditional evidence. Expiry SHALL be checked before bound-context loading and Caveat evaluation for that edge, after required local shape validation.

#### Scenario: Positive witness expires

- **WHEN** the only granting witness expires
- **THEN** the permission changes from has-permission to no-permission at the boundary

#### Scenario: Ban expires

- **GIVEN** `read = viewer - banned`, permanent viewer, and banned expiring at 100
- **WHEN** evaluation moves from 90 to 100 on one database basis
- **THEN** read changes from no-permission to has-permission without a write

#### Scenario: Intermediate arrow expires

- **WHEN** an intermediate Relationship on an arrow path expires
- **THEN** neither forward nor reverse traversal follows that edge

#### Scenario: Expired Caveated Relationship

- **WHEN** a well-formed Relationship is expired
- **THEN** its Caveat does not require context loading or evaluation for that edge

### Requirement: Evaluation time is trusted and captured once

Each top-level authorization view SHALL pair one immutable database basis with one trusted exact evaluation time in the portable range. Client-targeted operations SHALL capture a fresh time even when reusing a database pin. All subproblems and batch items SHALL use that time. Explicit EACL snapshots SHALL freeze it; speculative derivation SHALL preserve it.

Invalid clock values and violations of the configured nondecreasing client-clock contract SHALL cause typed clock errors. Documentation SHALL state the supported multi-peer clock/skew model. EACL MUST NOT expire individual negative edges early as a generic safe-skew rule.

#### Scenario: Clock crosses expiry during a check

- **WHEN** wall time crosses a deadline during one check
- **THEN** all of its subproblems still use the single captured time

#### Scenario: Batch crosses expiry

- **WHEN** a batch spans a wall-clock expiry boundary
- **THEN** all batch results use the same selected basis and time

#### Scenario: Test clock

- **WHEN** a deterministic configured clock moves to the exact deadline
- **THEN** the boundary can be tested without sleeps or boundary transactions

#### Scenario: Invalid or regressing clock

- **WHEN** the clock returns an invalid value or regresses contrary to its client contract
- **THEN** current snapshot acquisition fails without falling back to an unqualified graph

#### Scenario: Early expiry would remove a ban

- **WHEN** a proposed clock-uncertainty adjustment would expire subtracting evidence before its deadline
- **THEN** it cannot grant under a per-edge early-expiry rule
- **AND** any supported uncertainty policy must prove whole-permission invariance or return a typed failure

### Requirement: Relationship reads distinguish stored and expiry-active state

Stored Relationship reads SHALL expose normalized expiry and an explicit `:active` or `:expired` status at the selected evaluation time. Expiry-active reads SHALL omit expired tuples. These statuses SHALL describe expiry alone, not Caveat truth or final authorization. No `:scheduled` status SHALL exist in this contract.

Expired assertions SHALL retain identity, schema in-use, mutation, audit, and integrity significance until deleted. API documentation SHALL distinguish Relationship reads from permission lookups.

#### Scenario: Read retained expired data

- **WHEN** a stored read selects an expired Relationship
- **THEN** it returns its deadline and expired status
- **AND** an expiry-active read omits it

#### Scenario: Active does not mean authorized

- **WHEN** a Relationship is unexpired but its Caveat is false or conditional
- **THEN** its Relationship-read status remains active
- **AND** permission lookup applies the Caveat and complete permission expression separately

### Requirement: Renewal replaces qualifiers atomically

Expiry SHALL NOT alter logical Relationship identity. `:create` SHALL conflict with any stored assertion of the identity, including expired data. `:touch` SHALL create when absent or atomically replace its qualifiers when present. `:delete` SHALL require no expiry or Caveat values. Different identities with different deadlines SHALL be batchable together.

Each actual renewal SHALL update both endpoint tuples and the affected Relation mutation identity in the same admitted transaction. No committed absence gap or original-transaction-metadata mutation SHALL be required.

#### Scenario: Create after expiry before collection

- **WHEN** create targets a retained expired identity
- **THEN** it fails with `:eacl/relationship-conflict`
- **AND** touch can renew the identity with an explicit new deadline

#### Scenario: Create after collection

- **WHEN** collection has deleted the identity and no concurrent writer recreates it
- **THEN** a guarded create can succeed

#### Scenario: Renewal with unchanged bound context

- **WHEN** touch changes only expiry
- **THEN** it replaces both deadline values atomically and can retain the same immutable context ref

#### Scenario: Different deadlines in one batch

- **WHEN** a batch updates distinct identities with different deadlines
- **THEN** each retains its own deadline independently of shared transaction metadata

### Requirement: Collection is optional bounded maintenance

EACL SHALL provide an explicitly invoked, bounded collection operation over authoritative forward Relationships without requiring a per-Relationship expiration index. It SHALL select finite expiries at or before an admitted retention cutoff no later than the trusted collection time. It SHALL report progress and work limits, counting scanned candidates independently of deletions. Authorization correctness SHALL be independent of whether or when it runs.

Each actual deletion SHALL revalidate the current exact endpoint pair and owned context at commit, then retract them and advance `:eacl/relation-version` for every affected Relation once in the same transaction. Scanning and no-op batches SHALL NOT require a Relation bump. A stale collector plan SHALL never delete a renewed or replaced Relationship.

#### Scenario: Bounded scan finds no expired Relationships

- **WHEN** the collector exhausts its scan budget without finding an eligible expiry
- **THEN** it reports bounded progress without claiming all stored data was examined
- **AND** it does not mutate Relation versions solely for scanning

#### Scenario: Collection races renewal

- **WHEN** touch replaces an expired candidate before its collection transaction commits
- **THEN** current-state guards prevent deleting the replacement
- **AND** collection retries or reports/skips the race explicitly

#### Scenario: Collection deletes several Relationships of one Relation

- **WHEN** one admitted batch deletes several eligible pairs belonging to one Relation
- **THEN** both halves and owned context are removed atomically
- **AND** that Relation advances once for the transaction

#### Scenario: Concurrent rows appear behind the scan cursor

- **WHEN** another writer adds eligible data behind a collector cursor on a frozen scan basis
- **THEN** pass completion is scoped to that basis and a later pass can collect the new data

#### Scenario: Future cutoff

- **WHEN** a requested cutoff exceeds trusted collection time
- **THEN** collection rejects it rather than deleting still-active Relationships

### Requirement: Collection and historical reconstruction have separate contracts

Collection SHALL remove current stored assertions under the selected retention policy and SHALL NOT promise database excision or durable byte reclamation. Historical evaluation SHALL require an appropriate retained database basis with its authoritative schema/context and explicit EACL snapshot time. An earlier time alone on a post-collection basis SHALL NOT recreate deleted data.

#### Scenario: Current basis after collection

- **WHEN** collection deletes expired data
- **THEN** stored reads and identity conflicts reflect its removal
- **AND** earlier-time reconstruction is not inferred from the current basis alone

#### Scenario: Retained historical snapshot

- **WHEN** the backend still supports an older EACL snapshot containing the assertion and its context/schema
- **THEN** it can evaluate using its fixed basis and time subject to documented retention and token limits
