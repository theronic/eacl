## ADDED Requirements

### Requirement: Exact deduplicated acyclic enumeration

For a certified acyclic permission, EACL MUST produce the exact denotational authorization set for forward lists, reverse lists, and counts. Resources reachable through multiple grant paths SHALL be emitted and counted once, with stable ordering and existing page-boundary semantics.

#### Scenario: Owner receives direct and inherited view grants

- **WHEN** an owner can view a server through admin, account, team, VPC, or shared-admin paths
- **THEN** the server appears once in pagination and contributes one to the exact view count

#### Scenario: Super-user spans all servers

- **WHEN** the super-user is authorized for every seeded server
- **THEN** exact count equals the number of distinct authorized servers and paginated union equals the same set

#### Scenario: Explicit count limit is smaller than the result

- **WHEN** an exact count exceeds the documented public `count-limit`
- **THEN** EACL returns the documented bounded failure and never returns an approximate or partial count as exact

### Requirement: Deterministic acyclic work bounds

The verified acyclic implementation SHALL expose deterministic logical-work counters and satisfy checked-in linear work envelopes based on relevant indexed inputs, active grant streams, suppressed duplicates, and unique outputs. It MUST NOT perform recursive fixed-point rounds for certified acyclic requests.

#### Scenario: Multipath exact count

- **WHEN** exact count evaluates a 10,000-server Explorer permission with overlapping grant paths
- **THEN** indexed scan, merge, and duplicate-suppression work remains within the checked-in acyclic envelope and all recursive work counters remain zero

#### Scenario: Cold cached count uses bounded projection batches

- **WHEN** a representative subject's exact count consumes 12,000 of 40,000 seeded servers under the recursive schema with empty cycle guards
- **THEN** each count window and its sentinel are covered by a bounded cached projection batch, backend seeks remain within the checked-in cold-count envelope, and recursive work remains zero

#### Scenario: First visit to a deep resumed page

- **WHEN** a matching continuation exists for a page after several prior pages
- **THEN** logical work is bounded by that page's window and lookahead rather than by the total prefix from page one

### Requirement: V7-relative latency gates

The release performance harness MUST compare v8 and v7 using the same dataset seed, schema, request sequence, runtime mode, warmed process conditions, and host. For the named 10,000- and 50,000-resource scenarios, v8 warmed median latency SHALL be no worse than 2.0 times the recorded v7 median, subject to the harness's checked-in variance policy.

The harness MUST enforce a recorded latency ratio only when the current
operating system, architecture, operating-system version, CPU model, logical
processor count, physical or container memory, maximum JVM heap, JDK, VM
implementation/vendor, backend/runtime, and measurement method exactly match
the baseline host and JVM class. A mismatch or incomplete class MUST fail
closed: mismatch is reported as `not-applicable`, missing metadata is an error,
and neither outcome may be reported as a latency pass. Correctness and
deterministic work gates remain mandatory. Release qualification still requires
applicable matched-host latency evidence for every named scenario.

#### Scenario: Ten-thousand-server owner count

- **WHEN** the harness measures the exact view count for the representative owner on matched v7 and v8 datasets
- **THEN** v8 satisfies the 2.0-times-v7 latency gate and the deterministic acyclic work envelope

#### Scenario: Ten-thousand-server pagination

- **WHEN** the harness measures first visits to successive pages for the representative 8,000-server user
- **THEN** v8 satisfies the 2.0-times-v7 latency gate and shows no page-ordinal work growth

#### Scenario: Fifty-thousand-server super-user count

- **WHEN** the harness measures the super-user's exact server count at 50,000 resources
- **THEN** v8 satisfies the 2.0-times-v7 latency gate, completes exactly, and records no recursive traversal work

#### Scenario: CI runner does not match the recorded host

- **WHEN** any exact host-class field differs from the recorded v7 baseline
- **THEN** the ratio gate reports `not-applicable` rather than comparing raw milliseconds
- **AND** exact results and deterministic work envelopes remain mandatory
- **AND** the mismatch cannot qualify the release's matched-host latency requirement

### Requirement: Formal and generated authority gates

Any change to routing, shared enumeration, continuation semantics, counting, or work bounds MUST be represented in the Dafny formal authority and regenerated into the supported JVM and browser artifacts. A release SHALL pass formal verification, clean regeneration, generated-boundary checks, refinement checks, cross-runtime vectors, backend differential tests, and applicable mutation controls.

#### Scenario: Generated source differs after clean regeneration

- **WHEN** a checked-in or packaged generated artifact cannot be reproduced from the accepted formal source
- **THEN** validation fails and the change is not release-ready

#### Scenario: DataScript continuation is disconnected

- **WHEN** a mutation removes the DataScript continuation context or forces prefix replay
- **THEN** continuation, logical-work, and cross-backend regression gates fail

#### Scenario: Acyclic request is forced through recursive traversal

- **WHEN** a mutation bypasses certified routing for an acyclic permission
- **THEN** route, recursive-counter isolation, and 50,000-resource acceptance gates fail

### Requirement: Backend and runtime result parity

Datomic, DataScript, and Datahike, and the supported JVM and browser generated runtimes, SHALL return equivalent authorization results and exact counts for the same normalized schema, facts, request, and snapshot.

#### Scenario: Explorer acceptance matrix

- **WHEN** the 10,000-resource Explorer workload is executed across supported backend and runtime combinations
- **THEN** all combinations match the denotational oracle for routes, ordered pages, page cursors, deduplicated unions, counts, and bounded failures
