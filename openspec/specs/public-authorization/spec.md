# public-authorization Specification

## Purpose

Defines the public client and snapshot boundary that lets EACL preserve cache
coherence while supporting explicit, composable prospective relationship and
permission-schema evaluation.

## Requirements

### Requirement: Public operations accept only clients and EACL snapshots

Public authorization, lookup, count, relationship-read, schema-read, and
snapshot lifecycle operations SHALL accept an EACL client or an
EACL-created snapshot as their database source. They SHALL NOT accept a
caller-supplied native immutable database value. Public backend namespaces
MUST NOT expose a constructor that wraps a caller native database value as an
ordinary or speculative EACL snapshot.

Calling implementation namespaces or protocols directly is outside the public
contract and SHALL carry no cache-coherence guarantee.

#### Scenario: authorization has no caller database parameter

- **WHEN** an application calls `can?`, permission checks, lookups, counts, or
  `read-relationships` through the public API
- **THEN** the call has no parameter meaning "evaluate this native database
  value"
- **AND** evaluation uses the client-selected snapshot or a passed
  EACL-created snapshot

#### Scenario: raw d/with and d/filter values cannot enter publicly

- **GIVEN** a caller holds a database returned by native `d/with`, `d/filter`,
  `d/as-of`, `d/since`, or `d/history`
- **WHEN** the caller uses only public EACL APIs
- **THEN** no public snapshot constructor or authorization operation accepts
  that value as the evaluation database

#### Scenario: internal bypass has no coherence guarantee

- **GIVEN** a consumer invokes an EACL implementation protocol or namespace to
  inject a native database value
- **WHEN** authorization or cache operations follow
- **THEN** EACL makes no public cache-coherence guarantee for that execution

### Requirement: EACL with is the explicit speculative provenance boundary

`(eacl/with client-or-snapshot tx-data)` SHALL apply transaction data through
the backend's native in-memory transaction facility and return an immutable
EACL speculative snapshot. The returned snapshot SHALL retain the committed
root and cumulative effects needed by the cache-coherence requirements.
Calling `eacl/with` on a speculative snapshot SHALL be supported.

EACL SHALL NOT attempt to infer whether an arbitrary native database value is
speculative. Provenance is established because the caller selected
`eacl/with`. Transaction data that directly mutates EACL permission-schema
storage SHALL be rejected with a typed error directing the caller to
`eacl/with-schema`.

Public `eacl/tx-relationship` SHALL accept an EACL snapshot and a relationship
operation and SHALL return backend transaction data using the same resolution,
validation, paired storage, guards, and relation-version stamping rules as the
committed relationship writer.

#### Scenario: relationship helper transaction data is prospective

- **GIVEN** EACL relationship transaction helpers produce transaction data
  containing relationship changes and relation-version stamps
- **WHEN** that data is passed to `eacl/with`
- **THEN** the returned snapshot reflects the changes without committing them
- **AND** public authorization and relationship reads evaluate that snapshot

#### Scenario: with is composable

- **GIVEN** `s1` was returned by `eacl/with`
- **WHEN** the caller invokes `(eacl/with s1 tx2)`
- **THEN** the returned `s2` contains both prospective transactions
- **AND** its coherence metadata conservatively includes both transactions

#### Scenario: direct schema mutation is rejected

- **GIVEN** transaction data directly adds, retracts, or replaces EACL schema
  storage
- **WHEN** it is passed to `eacl/with`
- **THEN** EACL rejects it before returning a snapshot
- **AND** identifies `eacl/with-schema` as the supported operation

### Requirement: Speculative snapshots are immutable read targets

Public committed write operations, including `write-relationships!` and
`write-schema!`, SHALL accept committed clients and SHALL NOT treat an
immutable snapshot as a mutable transaction target. Prospective relationship
changes SHALL use `eacl/with`; prospective schema replacement SHALL use
`eacl/with-schema`.

#### Scenario: committed writer rejects a snapshot target

- **GIVEN** an ordinary or speculative EACL snapshot
- **WHEN** it is passed to `write-relationships!` or `write-schema!`
- **THEN** the operation fails with a typed immutable-target error
- **AND** no committed or speculative state is mutated

### Requirement: With-schema shares committed schema planning

`(eacl/with-schema client-or-snapshot schema options)` SHALL parse, normalize,
validate references, enforce the empty-schema guard, plan replacement, and
identify changed schema components using the same pure planning rules as
`write-schema!`. It SHALL apply the planned transaction only to a new
speculative snapshot and SHALL support chaining before or after `eacl/with`.

#### Scenario: prospective permission change affects authorization

- **GIVEN** a valid prospective schema changes a permission expression
- **WHEN** `can?`, lookup, count, or relationship read is evaluated on the
  resulting snapshot
- **THEN** the operation uses the prospective schema
- **AND** the committed client remains unchanged

#### Scenario: prospective schema uses committed validation

- **GIVEN** a schema has a parse error, unresolved reference, or disallowed
  empty replacement
- **WHEN** it is passed to `eacl/with-schema`
- **THEN** it fails with the same typed validation category as
  `write-schema!`
- **AND** no speculative snapshot is returned

### Requirement: With-schema has explicit speculative orphan policies

`eacl/with-schema` SHALL accept `:orphan-policy :error` and
`:orphan-policy :retain-inert`, defaulting to `:error`.

Under `:error`, prospective replacement that would orphan stored
relationships SHALL fail with `:eacl.schema/relation-in-use`, matching the
committed safety rule.

Under `:retain-inert`, EACL SHALL apply the prospective schema without
retracting, enumerating, or counting all affected relationship tuples. It SHALL
determine presence through a bounded indexed existence check per removed
relation or an equivalent non-enumerating operation. The resulting snapshot
SHALL expose a speculative diagnostic identifying each removed relation for
which relationship data was found, without requiring an exact tuple count.

Retained relationship datoms SHALL be semantically invisible to public
authorization and relationship reads while their relation definition is
absent. If an equivalent relation definition is restored later in the same
speculative chain, the retained relationships MAY become visible again.

`:retain-inert` SHALL be rejected by committed `write-schema!` and SHALL NOT
weaken committed schema-orphan guards.

#### Scenario: default policy rejects prospective orphans

- **GIVEN** stored relationships use a relation removed by a prospective schema
- **WHEN** `eacl/with-schema` is called without an orphan policy
- **THEN** it throws `:eacl.schema/relation-in-use`

#### Scenario: retain-inert avoids O(N) relationship removal

- **GIVEN** N relationships use a relation removed by a prospective schema
- **WHEN** `eacl/with-schema` is called with
  `{:orphan-policy :retain-inert}`
- **THEN** it returns a speculative snapshot without retracting, enumerating,
  or counting those N relationships
- **AND** its speculative diagnostic identifies the removed relation as having
  retained relationship data

#### Scenario: retained relationships are hidden

- **GIVEN** a `:retain-inert` snapshot physically retains relationships for a
  relation absent from its prospective schema
- **WHEN** public authorization or `read-relationships` runs on that snapshot
- **THEN** those relationships have no semantic effect and are not returned

#### Scenario: identical relation restoration may reactivate retained data

- **GIVEN** a speculative chain removed a relation with `:retain-inert`
- **WHEN** a later `with-schema` restores an equivalent relation definition
- **THEN** the previously retained relationships MAY participate in reads and
  authorization again
- **AND** the cumulative cache effect sets remain conservative

#### Scenario: committed writer rejects retain-inert

- **WHEN** `write-schema!` is called with `:orphan-policy :retain-inert`
- **THEN** it rejects the option before committing
- **AND** committed orphan protection remains enabled

### Requirement: Consistency modes select committed snapshots internally

When the target is a client, consistency modes SHALL select committed EACL
snapshots internally. `minimize-latency` SHALL reuse the client pin without a
required remote-store read; `at-least-as-fresh` SHALL refresh only when the pin
does not satisfy the requested token; `fully-consistent` or explicit refresh
MAY read the committed head; and `at-exact-snapshot` SHALL select a committed
historical snapshot from a token rather than accept a caller database value.

When the target is an EACL snapshot, evaluation SHALL use that immutable
snapshot. A consistency option MUST NOT silently replace it by refreshing the
client.

#### Scenario: minimize-latency reuses a remote pin

- **GIVEN** a remote-store client has a committed pin from warm or refresh
- **WHEN** a public operation uses `minimize-latency`
- **THEN** it uses that pin without a required branch-head read

#### Scenario: snapshot evaluation does not refresh

- **GIVEN** an EACL speculative snapshot
- **WHEN** a public operation is evaluated with a consistency descriptor
- **THEN** evaluation remains on that snapshot
- **AND** no committed refresh replaces its prospective content
