## MODIFIED Requirements

### Requirement: All declared protocol methods are implemented
Every method declared by `IAuthorizationReader`, `IAuthorizationWriter`, `ISnapshotSource`, and `IAuthorizationSnapshot` SHALL have a complete implementation on every EACL target advertising that capability. Public functions SHALL normalize alternate arities before dispatch. No supported call SHALL throw `AbstractMethodError`, an untyped missing-protocol exception, or another linkage error.

#### Scenario: Canonical relationship write works
- **WHEN** `write-relationship!` or `delete-relationship!` is called on a writable `acl` with any documented arity
- **THEN** it normalizes into the canonical relationship-write operation and completes under the shared write pipeline and token contract

#### Scenario: Read method works on acl and snapshot
- **WHEN** any public read is called on an `acl` or a snapshot
- **THEN** the target executes the canonical reader method
- **AND** never fails because a convenience arity or an obsolete monolithic method was omitted

#### Scenario: Capability is absent
- **WHEN** a public function receives an EACL target lacking the required reader, writer, source, or snapshot capability
- **THEN** it throws `:eacl/unsupported-capability` naming the capability and target kind, or `:eacl/invalid-authorization-target` for a non-EACL value
- **AND** never exposes a protocol implementation exception

### Requirement: Unimplemented and unsupported features throw typed errors
Every unsupported operation, inadmissible database value, consistency assertion failure, released-snapshot access, execution-constraint violation, or capability mismatch SHALL throw `ex-info` carrying equal stable `:type` and `:eacl/error` classifications. Validation SHALL occur before cache lookup, authorization evaluation, basis acquisition not required to classify the request, transaction planning, or submission.

#### Scenario: Selection required
- **WHEN** `:fully-consistent` is requested from a snapshot
- **THEN** EACL throws `:eacl.consistency/selection-required` with no cache lookup, evaluation, or source operation

#### Scenario: Snapshot behind floor
- **WHEN** an authenticated at-least token is newer than a snapshot
- **THEN** EACL throws `:eacl.consistency/freshness-unavailable` with `:reason :snapshot-behind` and bounded requested and actual revisions

#### Scenario: Basis conflict
- **WHEN** an exact token or cursor names a basis other than the snapshot's
- **THEN** EACL throws `:eacl.consistency/basis-conflict` with `:source :token` or `:source :cursor` before cache access

#### Scenario: Read-only target
- **WHEN** a mutation is invoked on a snapshot or a read-only `acl`
- **THEN** EACL throws `:eacl/unsupported-capability` with `:capability :write` before planning or submission

#### Scenario: Inadmissible database value
- **WHEN** a snapshot constructor receives a filtered, since, history, speculative, foreign-backend, or foreign-source value
- **THEN** EACL throws `:eacl/unsupported-database-value` naming the basis kind before any runtime state is created

#### Scenario: Released snapshot
- **WHEN** a read receives a released snapshot
- **THEN** EACL throws `:eacl/snapshot-released` before adapter or runtime access

#### Scenario: Dual classification
- **WHEN** any typed EACL error is inspected
- **THEN** `:type` and `:eacl/error` are present and equal
