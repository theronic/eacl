## ADDED Requirements

### Requirement: Caveat-aware permission checks accept bounded request context

EACL SHALL expose a Caveat-aware permission-check operation that accepts an
optional canonical request-context map in addition to the subject, permission,
resource, consistency, and execution controls. Request context SHALL be part of
the selected authorization request and SHALL remain fixed for every subproblem
in that operation.

The operation SHALL reject malformed, non-canonicalizable, or structurally
over-limit context before cache-key construction. Caveat-specific type validation
SHALL reject invalid effective parameter values after bound-context precedence
is applied, before evaluator execution or successful cache publication.

#### Scenario: Check without request context

- **WHEN** a caller checks a permission without request context
- **THEN** EACL evaluates uncaveated paths normally
- **AND** evaluates Caveated paths using only relationship-bound values

#### Scenario: Check with request context

- **WHEN** a caller supplies valid request context
- **THEN** every Caveat path in that check observes the same canonical request
  context

#### Scenario: Context changes during execution

- **WHEN** caller-owned mutable input changes after request admission
- **THEN** the in-flight check continues using the captured canonical context
- **AND** authorization does not observe the mutation

#### Scenario: Invalid request context

- **WHEN** request context violates structural depth, entry-count, collection, or
  encoded-size limits, or an effective parameter has an invalid Caveat type
- **THEN** EACL rejects the request with a typed context error
- **AND** no fallback uncontextualized check runs

### Requirement: Permission checks expose three-state permissionship

A successful Caveat-aware check operation SHALL return exactly one permissionship
state. Typed evaluation, execution-limit, clock, and authoritative integrity
failures are errors, not a fourth permissionship state:

- `:has-permission`;
- `:no-permission`; or
- `:conditional-permission`.

A conditional response SHALL include the canonical set of missing context field
names needed to determine the result. It MAY include bounded diagnostic
information or a residual condition, but that information MUST NOT be required
to safely interpret permissionship.

#### Scenario: Definite grant

- **WHEN** the graph and Caveats determine that access exists
- **THEN** the result is `:has-permission`
- **AND** missing context is empty

#### Scenario: Definite denial

- **WHEN** the graph and Caveats determine that access does not exist
- **THEN** the result is `:no-permission`
- **AND** missing context is empty

#### Scenario: Missing context can change the result

- **WHEN** one or more missing Caveat parameters can still change the final
  authorization result
- **THEN** the result is `:conditional-permission`
- **AND** the response names the missing fields

#### Scenario: Missing context is irrelevant

- **WHEN** an unconditional path determines access despite another conditional
  path
- **THEN** the result is `:has-permission`
- **AND** irrelevant missing fields are not reported as making the result
  conditional

### Requirement: Boolean can? remains fail-closed

The existing Boolean `can?` convenience operation SHALL return true only when
the Caveat-aware permissionship is `:has-permission`. It SHALL return false for
`:no-permission` and `:conditional-permission`.

`can?` SHALL propagate typed execution or integrity failures; it MUST NOT
convert a failed subproblem into false membership inside the permission graph.

A caller requiring missing-context diagnostics MUST use the Caveat-aware result
operation rather than infer them from `can?`.

#### Scenario: Conditional result through can?

- **WHEN** the Caveat-aware result is `:conditional-permission`
- **THEN** `can?` returns false

#### Scenario: Definite result through can?

- **WHEN** the Caveat-aware result is `:has-permission`
- **THEN** `can?` returns true
- **WHEN** the result is `:no-permission`
- **THEN** `can?` returns false

### Requirement: Caveat-aware lookup preserves result state

Authorization lookups that can traverse Caveated relationships SHALL accept the
same bounded request context and SHALL distinguish definitely authorized from
conditionally authorized subjects or resources. A result reachable only under
a residual Caveat MUST NOT be presented as definitely authorized.

Compatibility lookups and numeric counts SHALL include only definitely authorized
identities. Caveat-aware lookups SHALL mark each definite or conditional result;
Caveat-aware counts SHALL report separate definite and conditional identity
counts. Identity deduplication and permission algebra SHALL precede either count.
No surface SHALL silently collapse conditional results into grants.

#### Scenario: Definite lookup result

- **WHEN** a resource is reachable through a definite authorization path
- **THEN** lookup marks it `:has-permission`

#### Scenario: Conditional lookup result

- **WHEN** a resource is reachable only if missing Caveat context satisfies a
  residual condition
- **THEN** lookup marks it `:conditional-permission`
- **AND** includes the missing field names applicable to that result

#### Scenario: Boolean-only lookup compatibility

- **WHEN** an existing compatibility lookup returns only definitely authorized
  resources
- **THEN** it omits conditional results
- **AND** documentation directs callers to the Caveat-aware lookup for
  conditional candidates

### Requirement: Snapshot and speculative boundaries capture Caveat request state

An EACL snapshot SHALL continue to represent database basis, schema, and
evaluation time, while Caveat request context SHALL belong to one authorization
request rather than become persistent snapshot state. `eacl/with` and
`eacl/with-schema` SHALL not silently copy caller request context into stored
relationships.

Relationship-bound context SHALL enter only through an explicit relationship
write/update qualifier and SHALL be persisted separately from request context.

#### Scenario: Reuse one snapshot with different request context

- **WHEN** a caller checks the same immutable EACL snapshot with two different
  request-context maps
- **THEN** each check has a distinct semantic request identity
- **AND** the snapshot's database basis and evaluation time remain unchanged

#### Scenario: Prospective relationship with bound context

- **WHEN** a caller uses `eacl/tx-relationship` and `eacl/with` to add a
  Caveated relationship with bound context
- **THEN** the speculative snapshot contains the persisted qualifier state
- **AND** later permission checks may add separate request context

#### Scenario: Request context is not persisted

- **WHEN** a permission check supplies request context
- **THEN** EACL does not transact or attach it to any relationship


### Requirement: Effective result limits do not hide scan exhaustion

Effective lookup and count SHALL charge inactive candidates and validation work
to request limits. A work-limit failure SHALL NOT be returned as a successful
empty page, end-of-stream, exact count, or ordinary denial. Temporal restarts
SHALL be explicit errors and SHALL emit no resumed page under the old cursor.

#### Scenario: Active result follows a large inactive run

- **WHEN** scanning reaches its work limit before finding or excluding further active results
- **THEN** the operation returns a typed limit error
- **AND** no successful exhaustion marker or continuation is published

#### Scenario: Temporal restart required

- **WHEN** live resumption crosses the retained state's certified interval
- **THEN** the caller receives a typed restart requirement
- **AND** can start a fresh query that includes earlier identities newly authorized by expiry of subtracting evidence

### Requirement: Public authorization captures database and expiry time together

Each top-level client-targeted check, lookup, count, explanation, relationship read, or batch SHALL use one selected immutable database basis and one fresh trusted evaluation time. Reusing a minimize-latency database pin SHALL NOT reuse the preceding request's clock sample. An explicit EACL snapshot SHALL freeze its captured time as well as its database basis; speculative derivation SHALL preserve that time. Request context remains separate from snapshot state.

Ordinary authorization SHALL NOT accept a caller-supplied native filtered database or a request-context field as an override of the trusted expiry clock. Selecting a database basis with a causal consistency token SHALL NOT by itself freeze evaluation time.

#### Scenario: Same database pin after expiry

- **WHEN** two client-targeted checks reuse one database basis on opposite sides of a Relationship deadline
- **THEN** each check uses its own captured time and observes the appropriate expiry state

#### Scenario: Explicit snapshot after wall-clock expiry

- **WHEN** an EACL snapshot captured before expiry is evaluated after wall-clock expiry
- **THEN** evaluation still uses its original basis and time
- **AND** documentation identifies it as a fixed historical view, subject to token and retention limits

#### Scenario: Prospective update

- **WHEN** an explicit snapshot is passed to `eacl/with` or `eacl/with-schema`
- **THEN** the derived snapshot retains the parent's evaluation time
- **AND** guarded Relationship helpers and schema validation retain committed/speculative parity

#### Scenario: Caller supplies a false current time

- **WHEN** request Caveat context contains a clock-like parameter
- **THEN** it affects only Caveats that declare that parameter
- **AND** it cannot revive an expired Relationship by overriding the trusted clock

### Requirement: Conditional counts are not definite permissions

Count results SHALL distinguish definitely authorized identities from identities that could be authorized if missing Caveat context were supplied. Compatibility numeric counts SHALL count the definite set only. An identity reached through both definite and conditional paths SHALL be classified once using the composed permission result.

#### Scenario: Definite and conditional paths meet

- **WHEN** one identity has a definite union witness and another conditional witness
- **THEN** Caveat-aware count adds one to the definite count and zero to the conditional count for that identity

#### Scenario: Only conditional paths exist

- **WHEN** one identity remains conditional after permission algebra
- **THEN** compatibility count excludes it and Caveat-aware count includes it only in the conditional count
