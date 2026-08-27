## MODIFIED Requirements

### Requirement: Shared public API conformance
The repository SHALL provide a backend-neutral v8 conformance suite invoked by every supported adapter over equivalent fixtures. The suite SHALL run every public read over five target kinds — writable `acl`, read-only `acl`, captured snapshot, explicitly selected snapshot, and directly constructed snapshot — wherever the backend declares the capability, and SHALL run every mutation over writable `acl` only.

#### Scenario: Core operation matrix
- **WHEN** an adapter runs the suite against every supported target kind at one basis
- **THEN** schema reads and round trips, relationship CRUD and deletion, permission checks, lookups, counts, Relay pagination, filters, permission trees, unknown anchors, cancellation, deadlines, cache provenance, and typed errors are equivalent across target kinds
- **AND** mutations succeed only through a writable `acl`

#### Scenario: No backend-only expected results
- **WHEN** a behaviour belongs to the common reader, writer, source, or snapshot contract
- **THEN** its expected result is defined once in shared test support rather than copied into backend suites

#### Scenario: Capability-honest subset
- **WHEN** a backend lacks exact selection, a writable connection, an admissible direct value kind, or another optional capability
- **THEN** the shared suite requires the documented `:eacl/unsupported-capability` or `:eacl/unsupported-database-value` behaviour
- **AND** does not allow emulation through hidden mutable state

## ADDED Requirements

### Requirement: Authorization snapshot conformance
The shared suite SHALL prove, with source acquisition instrumented separately from immutable index reads, that snapshots are basis-stable, acquire nothing after construction, share runtime state only by cache class, honour lifecycle and execution constraints, and interoperate across processes through tokens.

#### Scenario: Acquisition counts
- **WHEN** the suite constructs an `acl`, captures one snapshot, and runs the full read matrix repeatedly on it
- **THEN** instrumentation observes zero acquisitions at construction, exactly one at capture, and zero during every later snapshot read

#### Scenario: Direct snapshot
- **WHEN** an adapter constructs a snapshot from an admissible application-owned value and runs the read matrix
- **THEN** instrumentation observes no source acquisition
- **AND** every response, cursor, token, and cache basis names that value

#### Scenario: Concurrent source advance
- **WHEN** the source advances while one snapshot serves permission, relationship, lookup, count, and paginated reads
- **THEN** every result is consistent with the snapshot's basis
- **AND** a later `acl` request may observe the new basis according to its own descriptor

#### Scenario: Lifecycle matrix
- **WHEN** the suite releases transient, retained owned, and retained borrowed snapshots, releases twice, and reads after release
- **THEN** transient snapshots release in `finally`, owned snapshots release natively once, borrowed snapshots close no caller resource, and post-release reads fail typed before backend work

#### Scenario: Thread affinity
- **WHEN** a source declares thread affinity and a snapshot is read or released from another thread
- **THEN** the typed execution-constraint error is thrown before backend work

#### Scenario: Cross-process token round trip
- **WHEN** two runtimes over one source share a keyring and lifecycle, one writes and returns a token, and the other selects `at-exact-snapshot` with it
- **THEN** the selected basis is identical
- **AND** on a backend supporting exact-by-locator selection, zero current-head acquisitions are observed

#### Scenario: Cache-class sharing
- **WHEN** two snapshots at different bases share one runtime and the managed-lifting fixtures run
- **THEN** ordinary-class snapshots reuse frame-admissible managed entries in either revision direction, snapshots without a readable frame reuse only exact entries, and no projection or subproblem crosses bases
