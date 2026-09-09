> **Superseded relationship-read requirements (2026-09-09):** The later `2026-09-09-remove-relationship-read-authorization` change replaces every requirement below to retain, construct, validate or migrate to `read-relationships :authorization`. Nested demo branches use plain indexed reads with the exact parent/type/relation, pagination, consistency and cache controls, and perform no endpoint permission checks. Removed authorization fields are rejected on presence, including nil/null. Viewing subject and permission remain inputs to root lookups, the access inspector and permission checks only. Library callers needing endpoint authorization compose direct reads with `can?` or `check-permissions` on one snapshot and own filtered pagination. Historical completed tasks and measurements below describe the earlier release, not the new contract. All unrelated requirements remain in force. Apply the companion delta before archiving; do not restore the superseded clauses.

## Purpose

Define the supported public lookup contract after removing relationship predicates, preventing obsolete requests from broadening results while preserving ordinary authorization and a safe migration for dependent consumers.

## ADDED Requirements

### Requirement: Removed lookup filters fail closed at admission

`lookup-resources`, `lookup-subjects`, `count-resources` and `count-subjects` SHALL reject the presence of either `:resource/relationship` or `:subject/relationship` with `:eacl/error :eacl.pagination/unsupported-filter` and the offending `:filter`. Presence SHALL be rejected regardless of value, including nil, false, malformed clauses or valid former clauses. If both keys are present, `:resource/relationship` SHALL be reported first. For valid supported reader targets and request maps, rejection SHALL occur before new snapshot acquisition, backend reads, identity conversion, cursor recovery, lookahead submission or cache reads/writes, including retained/speculative snapshot views. Existing malformed-target, non-map, ownership and lifecycle errors SHALL remain errors; this contract does not override their precedence or release a caller-owned snapshot. The API SHALL NOT silently discard, reinterpret or translate either filter. Count rejection SHALL NOT be presented as removal of previously supported count-filter semantics.

#### Scenario: Formerly valid filter on either lookup
- **WHEN** either lookup receives either removed key with a formerly valid clause
- **THEN** it returns the specified unsupported-filter error without selecting a snapshot or reading a cached answer

#### Scenario: Nil and malformed values do not disable the rejection
- **WHEN** a removed key is supplied with nil, false, an empty map or a malformed anchor
- **THEN** the request fails with the same unsupported-filter contract rather than becoming an unfiltered lookup

#### Scenario: Both filters are supplied
- **WHEN** the request contains both removed keys
- **THEN** the error identifies `:resource/relationship` deterministically

#### Scenario: Lookup query is reused for a count
- **WHEN** either count operation receives a removed relationship-filter key
- **THEN** it fails unsupported-filter admission instead of returning a count for the broader unfiltered authorization query

#### Scenario: Invalid filtered read targets a retained snapshot
- **WHEN** a valid open caller-owned snapshot receives a request map containing a removed key
- **THEN** the read fails before query work and the caller-owned snapshot remains usable for a subsequent valid request

### Requirement: Ordinary authorization behavior is preserved

Requests without removed keys SHALL retain their existing supported validation, result membership, ordering, permission semantics, qualified evidence/result policies, pagination, consistency, deadlines, cancellation and cache controls. This removal SHALL NOT remove internal filtering required by ordinary recursive/operator lookups, `check-permissions`, or `read-relationships` with `:authorization`. It SHALL NOT require permission-schema, relationship-data or storage-format changes.

#### Scenario: Ordinary forward and reverse lookup
- **WHEN** valid unfiltered forward or reverse lookups traverse direct, arrow, union, intersection, exclusion or recursive permissions supported by the schema
- **THEN** results and forward/backward page traversal satisfy the existing scalar and ordered-page contracts

#### Scenario: Qualified results retain evidence
- **WHEN** an unfiltered lookup evaluates qualified relationships under its supported result policy
- **THEN** conditional/definite membership, evidence and time/context-sensitive cache behavior remain correct

#### Scenario: Batch and authorized scan remain available
- **WHEN** a caller submits a valid batch check or authorized relationship read
- **THEN** the existing shared-snapshot, bounded-work and failure contracts remain supported without requiring either removed lookup key

### Requirement: Obsolete cursors cannot cross into another query

A cursor created for a removed filtered lookup SHALL NOT be accepted as a cursor for an ordinary lookup or relationship read, including when the client omits the former filter. Such continuation SHALL fail with the supported typed cursor error, never silently restart or return a broader result. A request still containing a removed key SHALL fail at admission even if it supplies a previously cached page or valid old cursor. Ordinary cursor scope and ABI compatibility SHALL be retained by this removal; a separate compatibility change SHALL require explicit plan revision. Removed filter dimensions SHALL NOT be erased from historical cache or cursor identities to enable reuse.

#### Scenario: Filter stripped from a historical request
- **WHEN** a cursor from a pre-removal filtered lookup is supplied to an unfiltered lookup
- **THEN** continuation fails typed cursor validation and emits no resource or subject rows

#### Scenario: Removed request has a cached answer
- **WHEN** a request carrying a removed key could otherwise match a retained cached page
- **THEN** it fails unsupported-filter admission before cache access

#### Scenario: Ordinary cursor survives the removal
- **WHEN** a pre-removal ordinary-query cursor has an available valid basis, unchanged dependencies, unexpired credentials and compatible runtime identities
- **THEN** this API removal alone does not invalidate its continuation

### Requirement: Retained relationship pages preserve complete dependency scope

Authorized relationship-page reuse and cursor continuation SHALL continue to depend on both branch relationship membership and the requested permission's dependencies, or use the existing exact-basis fallback where no complete dependency proof is available. Removal of lookup filters SHALL NOT weaken these proofs. Supported live/exact/freshness modes SHALL retain their existing rules when data changes; the migration SHALL NOT silently switch modes to stabilize pages.

#### Scenario: Branch membership changes independently of permission
- **WHEN** the anchored branch relationship is removed or added while the subject's permission on the endpoint remains unchanged
- **THEN** live requests do not reuse a stale branch page, and exact-snapshot continuation follows the selected immutable basis contract

#### Scenario: Permission changes independently of branch membership
- **WHEN** a permission-granting relationship changes while the anchored branch edge remains present
- **THEN** page reuse and continuation enforce the selected consistency mode and complete authorization dependencies

### Requirement: Dependent demo migration preserves authorization and page meaning

A release adopting the removal SHALL have a validated companion migration for known demo callers. Nested branches SHALL use existing indexed relationship reads with authorization on the displayed endpoint, with the branch's type/relation/anchor and selected authorization subject/permission applied before pagination. The migration SHALL preserve the canonical schema and dataset. It SHALL NOT use sequential per-candidate public permission checks, materialize the complete relationship collection, or silently loop through unlimited windows to fill a page.

The displayed object mapping SHALL be verified against the fixture's relationship multiplicity and absence of qualifiers/userset endpoints at generation, seed and artifact qualification boundaries, including larger supported seed sizes. These checks SHALL NOT introduce a full production relationship scan on page load. The automatic migration SHALL apply only to the canonical unqualified fixture; arbitrary qualified/duplicate relationship rows SHALL NOT be advertised as equivalent to filtered lookup results. Stored-row inspection and expiry-only filtering SHALL NOT be represented as evaluating a relationship caveat. Existing qualified lookup and relationship-read semantics SHALL remain unchanged. Relationship order and cursors SHALL be used consistently; old lookup cursors SHALL NOT be reused.

Authorized nested wire requests SHALL supply a complete authorization subject type/id, permission and displayed resource type. Missing/partial fields SHALL fail validation rather than falling back to unfiltered or raw relationship inspection. All obsolete lookup wire keys SHALL be rejected, including partial and nil forms, consistently by browser and JVM transports. Separate raw relationship inspection with a completely absent authorization clause SHALL retain its existing supported behavior.

#### Scenario: Platform accounts retain selected-subject authorization
- **WHEN** the selected subject expands Accounts under `platform:platform`
- **THEN** the read constrains subject type/id to that platform, resource type to account and relation to platform, authorizes the account resource endpoint, and displays only accepted account endpoints

#### Scenario: Bounded empty branch page
- **WHEN** the authorized read exhausts its candidate window without accepting a row but returns a continuation
- **THEN** backend, wire and frontend preserve that continuation and bounded status and permit Next rather than declaring the branch exhausted

#### Scenario: An expired or caveated parent edge is encountered outside the fixture contract
- **WHEN** an attempted general migration relies on a stored expired or caveat-false parent edge while the endpoint is independently viewable
- **THEN** it is not accepted as equivalent to the former qualified relationship filter; the caller requires an explicitly reviewed existing-API composition rather than silent translation

#### Scenario: A nested wire request is incomplete
- **WHEN** a nested authorized relationship request omits its displayed resource type or part of its authorization subject/permission group
- **THEN** transport validation rejects it before backend execution and does not retry as a raw relationship read

#### Scenario: Accepted sentinel is reused on the next page
- **WHEN** a page stops after examining an accepted lookahead sentinel
- **THEN** continuation preserves EACL's opaque inclusive cursor semantics and neither skips nor duplicates that resource

#### Scenario: Query-local measurements and user inputs
- **WHEN** an authorized branch page is fetched or its route changes during migration
- **THEN** latency/cache status refer to the complete page operation, displayed counts are not inferred from candidate-window size, and cursor recovery does not override selected subject/resource or permission inputs

#### Scenario: Short pages precede a displayed global range
- **WHEN** earlier pages contain fewer accepted rows than their requested page size
- **THEN** any displayed global range uses cumulative accepted rows rather than page number multiplied by requested page size, and a root-wide authorization count is not labeled as the branch total

#### Scenario: A superseded response arrives late
- **WHEN** a pending branch response completes after its relevant subject, permission, cache, backend or basis input has changed
- **THEN** it does not replace the current scope's rows or query evidence

### Requirement: Removal verification preserves amplification safeguards

Verification SHALL cover rejection across supported backends and CLJ/CLJS surfaces, retained ordinary lookup correctness, batch/authorized-read request sharing, and historical cursor isolation. Positive tests and benchmarks for the removed API SHALL be retired without deleting retained behavior from mixed tests or formal gates. Comparative consumer verification SHALL include broad-access subjects on narrow branches and sparse-access subjects on the platform-wide account branch, recording examined candidates and backend work as well as latency under cold, warm and cache-bypassed conditions. A single public invocation SHALL NOT be presented as proof of bounded total work or a performance improvement.

Foreground measurement SHALL disable lookahead for isolated request comparisons; configured background lookahead SHALL be verified and reported separately without changing its retained semantics. Candidate-window accounting SHALL distinguish predicate examinations from exhaustion probes, authorization backend work and background pages. Test-case budgets SHALL be chosen before measurement and SHALL NOT be increased after a failing result merely to declare success.

#### Scenario: Sparse subject on the platform-wide branch
- **WHEN** candidate work is measured for a user who can access few of the platform's accounts
- **THEN** the result reports observed candidate/backend work and bounded-page behavior without claiming cost proportional only to returned accounts

#### Scenario: Independent request-sharing fixes survive removal
- **WHEN** retained batch and authorized-read counter tests execute
- **THEN** snapshot ownership, invariant-work reuse and absence of nested public scalar calls continue to satisfy their existing contracts

#### Scenario: Foreground page schedules background work
- **WHEN** lookahead is enabled and a page has a continuation
- **THEN** any background pages remain within configured depth/concurrency constraints and are not hidden inside a claim that the user action did only one page's work

### Requirement: Release documentation and consumers agree on the removed contract

Current API documentation and active requirements SHALL stop advertising both lookup clauses. Historical introduction evidence SHALL remain distinguishable from current requirements. Publication/adoption SHALL identify compatible core, backend and demo artifacts and SHALL NOT upgrade a known caller to a removal build while that caller still emits either key. External-consumer and first-release information SHALL be reported only when established.

Migration documentation SHALL distinguish the two removed lookup directions. It SHALL NOT represent changing `:authorization :on` as equivalent to authorizing varying candidate subjects on one fixed resource. If such a reverse-filter consumer is identified, its bounded relationship-read/batch-check composition SHALL select one snapshot, preserve typed failures, bound total candidate work and implement accepted-result pagination explicitly without claiming native lookup ordering or qualified equivalence. This removal SHALL NOT add a new public replacement filter or unused demo operation.

#### Scenario: Demo upgrade is attempted before caller migration
- **WHEN** a known browser, JVM or Jank demo caller still constructs either removed key
- **THEN** release qualification fails until the companion caller migration is verified

#### Scenario: An already-open browser sends an old request to a new service
- **WHEN** a mixed-version client/service pair encounters obsolete filter fields or incompatible cursors
- **THEN** it returns an explicit validation or cursor error with user-preserving recovery, never an unfiltered fallback

#### Scenario: A reverse-filter caller needs a replacement
- **WHEN** a known caller requires related candidate subjects who can access one fixed resource
- **THEN** its migration evaluates those candidates as subjects of checks on that fixed resource, preserves one snapshot and bounded pagination, and does not substitute the different question answered by `:authorization :on :subject`
