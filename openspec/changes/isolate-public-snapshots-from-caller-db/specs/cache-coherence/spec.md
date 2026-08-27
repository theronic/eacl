# Delta: cache-coherence

## Purpose

Defines how EACL evaluates explicitly speculative snapshots without allowing
uncommitted content to enter shared caches or discarding committed proofs that
remain valid under a completely known, disjoint speculative change set.

## ADDED Requirements

### Requirement: Speculative evaluation never populates a cache

Any EACL read, planning, or authorization operation whose selected source is a
snapshot produced by `eacl/with` or `eacl/with-schema` SHALL NOT publish its
computed result, subproblem, relationship projection, schema compilation,
identity projection, transaction plan, checkpoint, cursor proof, or other
derived value into any shared or private cache tier. This prohibition SHALL
apply regardless of the native database identity, basis, transaction instant,
caller options, or whether the same content is later committed.

#### Scenario: same-basis speculative answer cannot poison the committed answer

- **GIVEN** a speculative Datomic value and a different committed value have
  the same database identity, basis `t`, and `:db/txInstant`
- **WHEN** an authorization operation is evaluated on the speculative snapshot
  and later on the committed client
- **THEN** no value computed from the speculative snapshot is available to the
  committed operation through any cache tier
- **AND** the committed operation returns the answer for committed content

#### Scenario: repeated speculative reads do not create a speculative cache

- **GIVEN** an EACL speculative snapshot
- **WHEN** the same authorization operation is evaluated more than once
- **THEN** each cache miss is evaluated against that snapshot
- **AND** no speculative result is published for a later operation to reuse

### Requirement: Speculative snapshots may reuse only validated committed proofs

A speculative operation MAY read a cache entry produced from committed EACL
state only after the entry passes ordinary committed validation against the
speculative snapshot's committed root and carries a complete dependency
witness for that operation. Reuse SHALL additionally require:

- the entry's relationship dependency set is disjoint from the snapshot's
  cumulative affected relationship set; and
- the entry's schema dependency set is disjoint from the snapshot's cumulative
  affected schema-component set; and
- every other answer-affecting dependency dimension covered by the entry's
  proof, including mutable identity or ordering inputs, is proven unchanged or
  disjoint from the speculative transaction effects.

An entry selected only by native database identity, exact basis, transaction
instant, or an incomplete dependency witness MUST NOT be read by a speculative
operation. A result computed after such a miss SHALL remain uncached.

#### Scenario: unrelated committed proof is reused without publication

- **GIVEN** a committed cache entry depends on relationship `{R1}` and schema
  components `{C1}`
- **AND** a speculative snapshot cumulatively affects only relationship `{R2}`
  and schema component `{C2}`
- **WHEN** the same operation is evaluated on the speculative snapshot
- **THEN** EACL MAY reuse the committed entry after ordinary proof validation
- **AND** the speculative operation publishes no cache entry

#### Scenario: affected relationship prevents reuse

- **GIVEN** a committed proof depends on relationship `R`
- **AND** a speculative transaction adds, retracts, repairs, or otherwise
  changes relationship content for `R`
- **WHEN** the dependent operation is evaluated on the speculative snapshot
- **THEN** EACL MUST evaluate against the speculative database value
- **AND** it MUST NOT publish the result

#### Scenario: affected schema component prevents reuse

- **GIVEN** a committed proof depends on a relation, permission, definition, or
  other schema component changed by `with-schema`
- **WHEN** the dependent operation is evaluated on the speculative snapshot
- **THEN** EACL MUST evaluate against the prospective schema and database value
- **AND** it MUST NOT publish the result

#### Scenario: exact-basis entry is not a content witness

- **GIVEN** a cache entry is addressable by the speculative value's database
  identity and basis but lacks the required complete dependency witness
- **WHEN** an operation is evaluated on the speculative snapshot
- **THEN** EACL MUST NOT read that entry

### Requirement: Speculative effect sets are cumulative and fail closed

Every speculative snapshot SHALL retain its committed root and a cumulative
effect certificate covering relationship, schema, and every other
answer-affecting proof dimension. Chaining `eacl/with` or `eacl/with-schema`
SHALL union new effects with all parent effects and SHALL never subtract an
earlier effect, even when a later speculative transaction appears to restore
the earlier content. If any effect dimension required for a reuse decision is
unknown or incomplete, committed cache reuse SHALL be disabled for that
decision.

#### Scenario: chained speculative changes accumulate

- **GIVEN** `s1` speculatively affects relationship `R1`
- **AND** `s2` is derived from `s1` and affects schema component `C2`
- **WHEN** a committed cache entry is considered while evaluating on `s2`
- **THEN** reuse requires relationship dependencies disjoint from `R1`
- **AND** schema dependencies disjoint from `C2`

#### Scenario: apparent restoration does not shrink the effect set

- **GIVEN** one speculative transaction changes relationship `R`
- **AND** a later transaction in the same chain restores the original tuple
- **WHEN** an entry depending on `R` is considered
- **THEN** EACL MUST treat `R` as affected and MUST NOT reuse the entry

#### Scenario: unknown effect disables reuse

- **GIVEN** an adapter cannot completely classify the EACL effects of a
  speculative transaction
- **WHEN** any committed cache entry is considered for the resulting snapshot
- **THEN** EACL MUST evaluate the operation without cache reuse or publication

#### Scenario: application data has unclassified identity effects

- **GIVEN** speculative transaction data contains application datoms whose
  effect on EACL identity, existence, ordering, or externalization proofs is
  not certified as irrelevant
- **WHEN** a committed cache entry is considered
- **THEN** EACL MUST treat the required effect dimension as unknown
- **AND** evaluate without cache reuse or publication

### Requirement: Effects come from local speculative application, not the transaction log

`eacl/with` SHALL determine relationship effects from the local native
speculative transaction result, including datoms emitted by transaction
functions, rather than trusting only the caller's input transaction forms.
`eacl/with-schema` SHALL derive schema effects from the validated schema
replacement plan. Implementations SHALL NOT call `d/log`, `d/tx-range`, drain
the transaction log, or install transaction listeners to establish speculative
or committed cache coherence.

#### Scenario: transaction function expands relationship changes

- **GIVEN** speculative transaction data invokes a transaction function that
  emits relationship changes not explicit in the input form
- **WHEN** `eacl/with` returns the speculative snapshot
- **THEN** its cumulative relationship effects include every relation changed
  by the emitted datoms

#### Scenario: no transaction-log inspection

- **WHEN** EACL creates or evaluates committed and speculative snapshots
- **THEN** cache-coherence management performs no `d/log`, `d/tx-range`, log
  drain, or transaction-listener operation

### Requirement: Relation version remains the committed watermark

Public relationship transaction helpers and committed relationship writers
SHALL emit one idempotent `:eacl.relation/version` backend-native equivalent
per distinct affected relation in a transaction. The same planned forms MAY be
used with `eacl/with`, but a speculative version value SHALL NOT serve as a
content witness and SHALL NOT be published to cache state.

#### Scenario: many relationships in one relation emit one stamp

- **GIVEN** a batch of N relationship changes all using one relation
- **WHEN** the batch is planned by an EACL relationship helper for commit or
  prospective application
- **THEN** exactly one relation-version stamp is emitted for that relation
