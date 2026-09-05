> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../../../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](../../review-2026-09-04.md). The original artifact follows unchanged.

## ADDED Requirements

### Requirement: Caveat-aware permission checks accept bounded request context

EACL SHALL expose a Caveat-aware permission-check operation that accepts an
optional canonical request-context map in addition to the subject, permission,
resource, consistency, and execution controls. Request context SHALL be part of
the selected authorization request and SHALL remain fixed for every subproblem
in that operation.

The operation SHALL reject malformed, non-canonicalizable, type-invalid, or
over-limit context before evaluator execution or cache publication.

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

- **WHEN** request context violates type, depth, entry-count, collection, or
  encoded-size limits
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

Pagination and count surfaces SHALL document whether they return definite
results only or structured definite/conditional totals; they MUST NOT silently
collapse conditional results into grants.

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
valid-time, while Caveat request context SHALL belong to one authorization
request rather than become persistent snapshot state. `eacl/with` and
`eacl/with-schema` SHALL not silently copy caller request context into stored
relationships.

Relationship-bound context SHALL enter only through an explicit relationship
write/update qualifier and SHALL be persisted separately from request context.

#### Scenario: Reuse one snapshot with different request context

- **WHEN** a caller checks the same immutable EACL snapshot with two different
  request-context maps
- **THEN** each check has a distinct semantic request identity
- **AND** the snapshot's database basis and valid-time remain unchanged

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
- **AND** can start a fresh query that includes newly active earlier identities
