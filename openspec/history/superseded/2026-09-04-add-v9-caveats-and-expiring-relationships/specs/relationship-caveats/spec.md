## Purpose

Define Phase 2 SpiceDB-compatible Caveat behavior for EACL relationships,
including schema definitions, sparse relationship-bound context, request
context, conditional permission results, and deterministic evaluation.

## ADDED Requirements

### Requirement: JVM Caveats use the selected cel-parser implementation

The JVM Caveat implementation SHALL use [exoscale/cel-parser](https://github.com/exoscale/cel-parser), coordinate `com.exoscale/cel-parser`, for CEL parsing and interpretation. EACL SHALL pin an exact version/build and a versioned admitted configuration/profile before Phase 2 activation. EACL SHALL retain responsibility for Caveat schema grammar, static name/type/profile validation, context precedence/conversion, partial-result semantics, permission algebra, expiry, bounded execution, and public result/error mapping. Parser acceptance SHALL NOT substitute for required schema validation.

JVM/ANTLR program objects SHALL remain internal rebuildable runtime state. Authoritative Caveat definitions, expression payloads, typed values, and portable cache/cursor data SHALL use the canonical EACL representation. Selecting the library SHALL NOT imply complete SpiceDB compatibility, native partial evaluation, or CLJS execution, or replace their qualification gates.

#### Scenario: JVM schema admission and evaluation

- **WHEN** a supported Caveat is admitted and evaluated on the JVM
- **THEN** the EACL integration validates its CEL body under the declared parameter environment before commit and evaluates through the pinned cel-parser implementation
- **AND** EACL maps its result into the specified definite, conditional, or typed-error outcome

#### Scenario: Repeated evaluation of an unchanged definition

- **WHEN** an unchanged Caveat is evaluated repeatedly under the same complete compiler/runtime identity
- **THEN** its resident program is reused without parsing the source per Relationship
- **AND** rebuilding a missing program from the authoritative definition yields the same behavior

#### Scenario: Dependency or configuration changes

- **WHEN** the cel-parser build, adapter semantics, enabled options, declared macros/functions, or extension implementation changes
- **THEN** the evaluator identity changes and incompatible compiled/cache/cursor state is not reused

#### Scenario: Portable export

- **WHEN** Caveat definitions or reusable state cross process/runtime boundaries
- **THEN** the portable representation contains no serialized JVM/ANTLR program object dependency

#### Scenario: Error returned as a value

- **WHEN** the selected library returns an error value rather than throwing
- **THEN** the adapter reports the specified typed operation failure
- **AND** Clojure truthiness does not turn that value into a grant

#### Scenario: Required evaluator facility is unqualified

- **WHEN** name/type validation, conditional semantics, or execution limits cannot meet the admitted profile
- **THEN** Phase 2 activation remains blocked until the integration is qualified or the unsupported construct is explicitly excluded from the profile

### Requirement: Named Caveats reference shared immutable expression entities

Each named Caveat definition SHALL reference one expression entity containing a versioned canonical payload of exact CEL source, typed parameter declarations, and semantic profile identity. Relationship slot five SHALL continue to reference the named definition; the expression entity SHALL NOT add a tuple component or a per-Relationship entity. Schema reads SHALL reconstruct the named declaration from this representation.

Managed schema writes SHALL validate and atomically intern identical expression payloads, including under concurrent admission. Canonical identity SHALL include source text, parameter names/types in canonical order, and profile, and SHALL exclude the Caveat name, Relationship identity, expiry, and request/bound values. Equal canonical payloads SHALL share an expression within one database. Logical equivalence of different formulas, whitespace normalization, and parameter renaming SHALL NOT be required. Digest-based lookup SHALL compare full canonical payloads and fail explicitly on unequal content under the same digest.

Published expression payloads SHALL be immutable under admitted writes. Editing a named definition SHALL update its expression reference and schema generation atomically, with existing in-use and compatibility guards. Schema proofs SHALL cover the reference and full expression payload. Eids SHALL be interpreted in the selected database/source/branch; a bare eid SHALL NOT establish cross-view content equivalence. Relationship deletion and collection SHALL NOT delete shared expression entities. The initial implementation SHALL retain unused expressions rather than introduce shared-expression collection.

#### Scenario: Two names have identical expressions

- **WHEN** two named Caveats declare identical canonical source, parameters, and profile
- **THEN** they reference one interned expression entity while retaining distinct named schema identities

#### Scenario: Same text with a different parameter type

- **WHEN** two admitted expressions have equal source but different parameter types or semantic profiles
- **THEN** they have distinct expression identities and cannot alias one program-cache entry

#### Scenario: Equivalent formulas have different text

- **WHEN** two formulas are logically equivalent but have different source text
- **THEN** the implementation may retain separate expression entities and programs
- **AND** it does not claim semantic equivalence from a shared digest or a name

#### Scenario: Concurrent interning

- **WHEN** concurrent supported schema writes admit identical expression payloads
- **THEN** committed references resolve to one interned entity for that content
- **AND** a digest collision with unequal content rejects admission without partial schema changes

#### Scenario: Definition replacement preserves old expression content

- **WHEN** a supported schema edit changes the expression of an existing named Caveat
- **THEN** the definition references the appropriate immutable expression and its schema generation changes
- **AND** a retained old database view still resolves its original expression under the backend's retention contract

#### Scenario: Relationship collection leaves shared expressions available

- **WHEN** collection removes the last Relationship using a Caveat
- **THEN** it removes only the endpoint pair and owned non-empty bound context
- **AND** it does not retract the named definition or shared expression

#### Scenario: Missing expression payload

- **WHEN** a selected named definition references a missing or malformed authoritative expression
- **THEN** the affected operation fails with a typed authoritative-state error
- **AND** a previously cached program does not conceal the missing payload

### Requirement: Clients compile programs lazily during traversal

An EACL client SHALL construct a program on the first cache miss when traversal actually evaluates an expiry-active Caveated Relationship. Construction SHALL use the selected snapshot's validated expression content. Client construction, ordinary schema reads, stored Relationship enumeration, expired candidates, and paths not evaluated SHALL NOT trigger eager runtime program compilation. Bounded validation at schema-write admission SHALL remain required and SHALL NOT be deferred to this first traversal.

On the JVM, the integration SHALL use `exoscale.cel.parser/make-program` to build reusable parsed programs and `eval-for` for repeated evaluation with fresh effective bindings. It SHALL NOT use `parse-eval` per Relationship. Program construction SHALL include any reusable EACL validation/evaluation plan required by the admitted profile. Programs SHALL remain independent of request bindings, Relationship-bound values, expiry, and residual/result state. Program-cache lifecycle and identity SHALL satisfy the separate cache requirements.

#### Scenario: Unused and expired Caveats

- **WHEN** a client reads a schema with several Caveats but traverses only uncaveated or expired edges
- **THEN** it performs no runtime program compilation for those unused expressions

#### Scenario: First and repeated traversal

- **WHEN** an active Caveated edge first needs evaluation and its program is absent
- **THEN** the client builds and retains the program under the expression/evaluator key
- **AND** later evaluations reuse it while resident, including through another Caveat name with identical expression content

#### Scenario: Fresh bindings for a shared program

- **WHEN** two Relationships or concurrent requests evaluate one program with different effective contexts
- **THEN** each evaluation observes only its own context and produces its own result
- **AND** neither the first bindings nor the first result becomes cached program state

#### Scenario: Invalid schema is rejected before lazy traversal

- **WHEN** a schema write contains an undefined name, unsupported overload, or non-Boolean expression
- **THEN** admission rejects it before commit even if no traversal would immediately reach that Caveat

### Requirement: Warm program evaluation accounts for macro bodies and bounded work

For admitted collection macros, the qualified integration SHALL reuse parsed body plans rather than reparse source for each collection element or Relationship evaluation. The inspected upstream visitor reparses bodies inside iterations; top-level program caching alone SHALL NOT establish this requirement. An unqualified macro SHALL be rejected at schema admission under the published profile. Qualification SHALL instrument nested macro-body parser invocations and distinguish program construction from warm evaluation.

The integration SHALL enforce explicit input, parse/tree, collection, regex, and aggregate execution limits. It SHALL qualify actual work termination rather than assume a timeout cancels underlying execution. Cold program construction, concurrent build admission, context conversion, and repeated evaluations SHALL be accounted for separately where appropriate and SHALL NOT bypass request limits.

#### Scenario: Repeated collection macro evaluation

- **WHEN** an admitted macro is evaluated over several collection elements using a resident program
- **THEN** parser instrumentation records no body-source reparsing during those evaluations
- **AND** evaluation still applies the correct per-element and per-request bindings

#### Scenario: Nested macro

- **WHEN** the admitted profile includes nested macros
- **THEN** the same reuse and work-limit requirements apply to every nested body

#### Scenario: Cold build exceeds its budget

- **WHEN** program construction or build admission exceeds the configured execution limit
- **THEN** the affected request receives a typed limit failure
- **AND** no partial program or authorization result is published


### Requirement: Caveat definitions are typed schema declarations

EACL SHALL parse, validate, persist, read, compare, and replace SpiceDB-style
top-level Caveat definitions. A Caveat definition SHALL have a stable name,
typed parameters, and a bounded CEL expression that produces a boolean or a
partial result when required context is absent.

EACL SHALL publish a versioned Caveat compatibility profile. Syntax, parameter
types, functions, and operators outside that profile MUST be rejected at schema
write rather than approximated.

#### Scenario: Valid Caveat definition

- **WHEN** a schema declares a Caveat whose parameters and expression are
  supported by the active compatibility profile
- **THEN** EACL persists a canonical named Caveat schema entity referencing its interned expression
- **AND** schema reads reproduce an equivalent declaration
- **AND** all supported runtimes interpret the same canonical typed representation, with programs built lazily when evaluated

#### Scenario: Unknown Caveat parameter

- **WHEN** a Caveat expression references a name absent from its parameter list
- **THEN** schema validation fails with a typed Caveat error
- **AND** no schema replacement commits

#### Scenario: Non-boolean expression

- **WHEN** a Caveat expression cannot produce a boolean result
- **THEN** schema validation rejects it

#### Scenario: Unsupported CEL construct

- **WHEN** a Caveat uses syntax, a type, a function, or an overload outside the
  declared EACL compatibility profile
- **THEN** schema validation rejects it explicitly
- **AND** does not silently change its meaning

#### Scenario: Caveat definition changes

- **WHEN** a supported schema write changes a Caveat's parameters or expression
- **THEN** the schema generation changes
- **AND** cached plans and answers from the old definition cannot be reused

### Requirement: Relation subject branches declare allowed Caveats

A relation type reference SHALL be able to name one allowed Caveat using
SpiceDB-compatible `with <caveat>` syntax. A logical relationship may attach
zero or one Caveat, and the selected Caveat SHALL be valid for the exact
resource relation, subject type, wildcard state, and optional subject relation.

A schema MAY include both uncaveated and caveated branches for the same subject
form to make the Caveat optional. When only a caveated branch matches, the
relationship write SHALL require that Caveat.

#### Scenario: Optional Caveat branch

- **GIVEN** a relation allows both `user` and `user with ip_allowlist`
- **WHEN** a relationship is written for a user with no Caveat
- **THEN** the write is valid
- **WHEN** the same relation is written with `ip_allowlist`
- **THEN** the Caveated write is also valid

#### Scenario: Required Caveat branch

- **GIVEN** the only matching branch is `user with ip_allowlist`
- **WHEN** a relationship is written without a Caveat
- **THEN** EACL rejects it with a typed required-Caveat error

#### Scenario: Caveat is not allowed

- **WHEN** a relationship names a Caveat not allowed by its matching relation
  branch
- **THEN** EACL rejects the write before transaction submission

#### Scenario: Missing Caveat definition

- **WHEN** a relation branch references an undefined Caveat
- **THEN** schema validation fails before persistence

#### Scenario: More than one Caveat on one relationship

- **WHEN** a relationship update attempts to attach multiple Caveats
- **THEN** EACL rejects it
- **AND** callers must combine the condition inside one Caveat definition

### Requirement: Caveat qualifiers use tuple slots five and six

For a Caveated relationship, endpoint slot five SHALL contain the internal eid
of the selected schema-level Caveat definition. Endpoint slot six SHALL be
either `nil` or the eid of one relationship-specific Caveat context entity.
Both endpoint halves SHALL contain identical values.

A relationship without a Caveat SHALL contain `nil` in both slots. A context ref
without a Caveat ref SHALL be an authoritative integrity fault that aborts an
affected operation; it SHALL NOT be converted to absent membership.

#### Scenario: Uncaveated relationship

- **WHEN** a relationship has no Caveat
- **THEN** both endpoint halves contain `nil` in slots five and six
- **AND** no Caveat context entity is created

#### Scenario: Caveat with empty bound context

- **WHEN** a relationship attaches a Caveat with no relationship-bound values
- **THEN** slot five contains the Caveat definition eid
- **AND** slot six is `nil`
- **AND** request context may supply the Caveat parameters

#### Scenario: Caveat with non-empty bound context

- **WHEN** a relationship attaches a Caveat with one or more bound values
- **THEN** slot five contains the Caveat definition eid
- **AND** slot six contains one context eid shared by both endpoint halves

#### Scenario: Mismatched Caveat qualifiers

- **WHEN** the endpoint halves contain different Caveat or context refs
- **THEN** integrity reporting identifies the mismatch
- **AND** an affected authorization operation fails with
  `:eacl/invalid-relationship-state`

### Requirement: Relationship-bound Caveat context is sparse and immutable

Non-empty relationship-bound Caveat context SHALL live in one internal,
singly-owned entity referenced by endpoint slot six. The entity SHALL contain
one canonical, type-preserving, bounded context-map payload and SHALL have no
public object ID.

An empty context SHALL use a nil tuple ref and no entity. Context entities SHALL
not be deduplicated across relationships. Once referenced by a committed
relationship, a context entity SHALL be immutable; changing context SHALL
replace the entity and both endpoint refs atomically.

#### Scenario: Sparse context storage

- **WHEN** a Caveated relationship binds no parameter values
- **THEN** it allocates no relationship-specific context datom

#### Scenario: Non-empty context storage

- **WHEN** a Caveated relationship binds a non-empty context map
- **THEN** EACL stores one canonical payload datom on one context entity
- **AND** arbitrary context bytes are not duplicated in the two endpoint tuples

#### Scenario: Context changes through touch

- **WHEN** `:touch` changes relationship-bound context
- **THEN** EACL creates a replacement immutable context entity
- **AND** replaces both endpoint refs and retracts the old singly-owned entity
  in the same admitted transaction

#### Scenario: Context entity is shared out of band

- **WHEN** integrity inspection finds one context eid referenced by more than
  one logical relationship
- **THEN** it reports an ownership violation
- **AND** repair does not guess which relationship owns it

#### Scenario: Context payload changes in place out of band

- **WHEN** a context payload is mutated without replacing the endpoint ref
- **THEN** database-visible content proofs detect the change
- **AND** prior cached results are not reused

### Requirement: Relationship writes validate Caveat context

Relationship-bound context SHALL be validated against the selected Caveat's
declared parameter names and types before transaction submission. Context input
SHALL use a canonical JSON-like value model compatible with the declared
SpiceDB profile and EACL's portable CLJ/CLJS encoding.

EACL SHALL enforce configured depth, entry-count, string/byte, collection, and
encoded-size limits without truncation.

#### Scenario: Partially bound context

- **WHEN** relationship context supplies a valid subset of Caveat parameters
- **THEN** the write succeeds
- **AND** unsupplied parameters remain available for request-time context

#### Scenario: Unknown bound key

- **WHEN** relationship context includes a key not declared by the Caveat
- **THEN** EACL rejects the write with a typed context error

#### Scenario: Bound value has wrong type

- **WHEN** a relationship-bound value cannot inhabit the declared parameter type
- **THEN** EACL rejects the write

#### Scenario: Oversized bound context

- **WHEN** bound context exceeds any configured structural or encoded-size limit
- **THEN** EACL rejects it before allocating a context entity
- **AND** does not truncate it into a potentially granting value

### Requirement: Caveat and expiry do not change relationship identity

EACL SHALL permit at most one stored relationship for a
resolved subject/relation/resource identity, including the endpoint owner and
existing wildcard/subject-relation form, regardless of Caveat, Caveat context, or
expiry. Caveat qualifiers SHALL be mutable properties of that relationship,
matching SpiceDB behavior.

`:create` SHALL conflict with any existing qualifier state. `:touch` SHALL
replace Caveat, context, and expiry atomically. `:delete` SHALL not require
the caller to supply Caveat or context.

#### Scenario: Duplicate differs only by Caveat

- **WHEN** a caller creates the same logical relationship with another Caveat
- **THEN** EACL reports `:eacl/relationship-conflict`
- **AND** does not store a second relationship

#### Scenario: Duplicate differs only by context

- **WHEN** a caller creates the same logical relationship with different bound
  context
- **THEN** EACL reports `:eacl/relationship-conflict`

#### Scenario: Touch replaces Caveat

- **WHEN** `:touch` changes an uncaveated relationship to a Caveated one or
  changes the selected Caveat
- **THEN** one canonical relationship remains with the requested qualifiers

#### Scenario: Delete omits Caveat

- **WHEN** a caller deletes a Caveated relationship by logical identity
- **THEN** EACL deletes it without requiring Caveat name or context
- **AND** retracts its singly-owned context entity

### Requirement: Request context merges with bound context using SpiceDB precedence

Caveat-aware authorization and lookup requests SHALL accept a bounded canonical
request-context map. EACL SHALL merge request context with
relationship-bound context so bound values take precedence for duplicate keys.

The merge SHALL be shallow by parameter name: a bound map value replaces
the whole request parameter, rather than recursively merging nested maps.
Unknown request keys SHALL remain outside the expression parameter environment
and SHALL remain in the conservative request identity. Structural/size admission precedes the merge; Caveat-specific type conversion
SHALL apply to the effective merged values before evaluation. A caller MUST NOT override a value fixed on the relationship.

#### Scenario: Request supplies missing value

- **GIVEN** a relationship binds `cidr` but not `user_ip`
- **WHEN** the request supplies a valid `user_ip`
- **THEN** the evaluator receives both values

#### Scenario: Bound value overrides request value

- **GIVEN** a relationship binds `expected_aud`
- **WHEN** the request supplies a different `expected_aud`
- **THEN** the evaluator receives the relationship-bound value

#### Scenario: Shadowed request value has wrong Caveat type

- **GIVEN** a relationship binds a valid value for a Caveat parameter
- **WHEN** a structurally valid request supplies a value of another type for that same parameter
- **THEN** Caveat type validation uses the bound value and does not reject the shadowed value as the effective parameter

#### Scenario: Bound map replaces request map

- **GIVEN** a relationship binds parameter `settings` to a map without key `admin`
- **WHEN** request context supplies a `settings` map containing `admin`
- **THEN** the effective `settings` is exactly the bound map
- **AND** the request cannot add keys through recursive merging

#### Scenario: Request value has wrong type

- **WHEN** request context supplies an invalid type for a visible Caveat
  parameter
- **THEN** EACL returns a typed invalid-context request error
- **AND** does not treat the edge as true

#### Scenario: Oversized request context

- **WHEN** request context exceeds a configured limit
- **THEN** EACL rejects the request before cache-key construction or evaluation

### Requirement: Caveat evaluation returns definite or conditional membership

For an expiry-active, structurally valid Caveated relationship, EACL SHALL
evaluate the selected Caveat using the merged context. Evaluation SHALL produce
one of:

- true: the relationship may participate in the graph;
- false: the relationship is absent from the effective graph;
- partial: required context is missing, so the relationship contributes a
  conditional residual expression and missing parameter names.

Evaluation errors, malformed definitions, unsupported values, or missing
referenced entities SHALL abort the affected operation with a typed error.
They SHALL NOT become false/absent operands or successful no-permission
results inside permission algebra. Only a valid false evaluation or a well-formed Relationship that has expired
contributes definite absence.

The EACL adapter SHALL explicitly represent absent declared parameters and map unresolved outcomes into canonical residuals and missing fields. Native unknown/partial evaluation SHALL NOT be assumed from the cel-parser API. The qualified integration SHALL provide this behavior for the admitted profile. Missing context SHALL NOT be implemented by binding absent parameters to nil, by treating every unbound-variable error as conditional, or by treating a set of missing names as a complete residual formula. The checked expression and effective bindings needed to reproduce a residual SHALL remain part of its canonical identity. Reported evaluation failures and execution-limit violations SHALL remain errors rather than unknown values.

#### Scenario: Missing parameter is unknown

- **WHEN** an admitted Caveat result still depends on an absent declared parameter
- **THEN** the adapter returns conditional membership with the relevant missing fields and reproducible residual identity
- **AND** the Relationship is neither denied merely because a binding is absent nor granted unconditionally

#### Scenario: Known Boolean branch decides an expression

- **WHEN** the pinned CEL profile determines a definite result despite an unknown operand
- **THEN** the adapter preserves that definite result rather than reporting irrelevant missing context

#### Scenario: Failure is not missing context

- **WHEN** the cel-parser integration reports an evaluation failure or the EACL work budget is exceeded
- **THEN** the affected operation fails with the specified typed error
- **AND** the failure is not converted to false or conditional subtracting evidence

#### Scenario: Caveat evaluates true

- **WHEN** every required parameter is known and the Caveat evaluates true
- **THEN** the relationship participates subject to all other qualifiers

#### Scenario: Caveat evaluates false

- **WHEN** every required parameter is known and the Caveat evaluates false
- **THEN** the relationship does not participate

#### Scenario: Context is missing

- **WHEN** the result depends on one or more absent parameters
- **THEN** the relationship produces a conditional result
- **AND** identifies the missing parameter names

#### Scenario: Inactive relationship is Caveated

- **WHEN** expiry makes a Caveated relationship inactive
- **THEN** the engine rejects it before loading bound context or invoking the
  Caveat evaluator

#### Scenario: Evaluator error

- **WHEN** evaluator execution fails or exceeds a configured bound
- **THEN** EACL returns a typed evaluation failure
- **AND** publishes neither a successful permissionship nor a continuation

### Requirement: Conditional conditions compose through permission algebra

The engine SHALL preserve conditional residual conditions until permission
evaluation is determined. Union, intersection, exclusion, arrows, recursion,
and subject-relation traversal SHALL combine definite and conditional branches
without collapsing missing context to false prematurely.

An unconditional witness MAY determine a positive union despite another
conditional branch. A definite subtracting witness MAY determine an exclusion
denial. When missing context can still change the final answer, the result SHALL
remain conditional and report the complete minimal or conservative missing
field set.

#### Scenario: Unconditional union witness

- **GIVEN** a permission is `owner + viewer`
- **WHEN** `owner` is definitely true and `viewer` is conditional
- **THEN** the permission is has-permission

#### Scenario: Conditional union

- **WHEN** every possible granting branch is false or conditional and at least
  one conditional branch could grant
- **THEN** the permission is conditional-permission

#### Scenario: Conditional intersection

- **WHEN** one required intersection branch is conditional and no branch is
  definitely false
- **THEN** the permission is conditional-permission

#### Scenario: Definite false intersection

- **WHEN** one required intersection branch is definitely false
- **THEN** the permission is no-permission even if another branch is
  conditional

#### Scenario: Conditional exclusion

- **GIVEN** a permission is `viewer - banned`
- **WHEN** `viewer` is true and `banned` is conditional
- **THEN** the result remains conditional because missing context can determine
  whether access is subtracted

#### Scenario: Arrow contains conditional edge

- **WHEN** an arrow path depends on a conditional relationship
- **THEN** the resulting subject/resource membership retains that condition

### Requirement: Public Caveat-aware results match SpiceDB permissionship

The Caveat-aware permission API SHALL return one of
`:has-permission`, `:no-permission`, or `:conditional-permission`.
A conditional response SHALL include the context field names still required to
determine the answer and MAY include a bounded diagnostic residual condition.

The existing Boolean `can?` API SHALL return true only for
`:has-permission`. It SHALL return false for both no-permission and
conditional-permission so missing context never grants access.

Lookup operations SHALL distinguish definitely authorized and conditionally
authorized results. Continuations SHALL preserve that result semantics.

#### Scenario: Complete true check

- **WHEN** Caveat evaluation and graph traversal determine access
- **THEN** the Caveat-aware API returns `:has-permission`
- **AND** `can?` returns true

#### Scenario: Complete false check

- **WHEN** graph traversal determines access is absent
- **THEN** the Caveat-aware API returns `:no-permission`
- **AND** `can?` returns false

#### Scenario: Incomplete check

- **WHEN** missing context prevents a definite decision
- **THEN** the Caveat-aware API returns `:conditional-permission`
- **AND** returns the missing field names
- **AND** `can?` returns false

#### Scenario: Conditional lookup result

- **WHEN** a lookup candidate is reachable only under a residual Caveat
- **THEN** the lookup result marks that candidate conditional rather than
  silently including it as definitely authorized

### Requirement: Caveat dependencies are cache- and cursor-complete

Any cache entry, subproblem, continuation, explanation, or cursor influenced by
Caveats SHALL distinguish the canonical request context, Caveat definition and
schema generation, relationship context eid and payload, evaluator
profile/fingerprint, residual condition, missing fields, and result kind.

A relation-version proof alone SHALL NOT authorize reuse after a context payload
or evaluator dependency changes. Cache/context identity SHALL be bounded before
hashing or canonicalization.

#### Scenario: Request context changes

- **WHEN** two requests differ in a context value visible to any possible
  Caveat path
- **THEN** they cannot collide in cache or cursor identity

#### Scenario: Caveat definition changes

- **WHEN** schema replacement changes a referenced Caveat
- **THEN** old plans, answers, and continuations cannot be reused

#### Scenario: Context payload changes out of band

- **WHEN** a context entity's payload changes without a relation-version update
- **THEN** database-visible proof validation rejects prior results

#### Scenario: Conditional result is replayed as definite

- **WHEN** an external cache attempts to substitute a conditional value for a
  definite value or vice versa
- **THEN** complete authenticated entry identity rejects the substitution

### Requirement: Caveat faults and orphaning fail closed

A missing Caveat definition, missing context entity, malformed payload, invalid
type, mismatched endpoint qualifier, evaluator incompatibility, or unsupported
profile SHALL never unconditionalize a relationship.

Committed schema replacement SHALL reject removal of a Caveat definition still
referenced by stored relationships with no inert-orphan exception in v9. Referenced parameter removals and type
changes SHALL be rejected when they would invalidate stored bound context or
relation branches. Commit-time schema guards SHALL serialize these checks
with relationship writes. Integrity reports SHALL identify
orphaned and malformed Caveat data.

#### Scenario: Caveat definition is missing

- **WHEN** a relationship references a Caveat entity absent from the selected
  schema
- **THEN** the affected operation fails with a typed authoritative-state error
- **AND** integrity reports the orphan

#### Scenario: Context entity is missing

- **WHEN** slot six references an absent context entity
- **THEN** the affected operation fails with a typed authoritative-state error
- **AND** the missing entity is neither empty context nor false membership

#### Scenario: Referenced Caveat removal

- **WHEN** schema replacement would remove a Caveat used by stored
  relationships
- **THEN** the committed write fails with a typed in-use error

#### Scenario: Unknown writer creates invalid Caveat data

- **WHEN** an out-of-band writer creates malformed Caveat qualifiers
- **THEN** authorization fails closed
- **AND** proof and integrity paths expose the change

### Requirement: Relationship entities remain a future assertion boundary

Caveat definitions, shared expression entities, and sparse context entities SHALL NOT require EACL to reify
every relationship. First-class relationship or grant-assertion entities MAY be
introduced only when the domain requires independent assertion identity, such
as multiple grantors, independently revocable grants, multiple simultaneous
intervals, provenance, approvals, or stable grant IDs.

#### Scenario: Ordinary Caveated relationship

- **WHEN** one Caveat with optional context qualifies one logical relationship
- **THEN** the endpoint pair remains the authoritative relationship
- **AND** the context entity is qualifier data, not a relationship vertex

#### Scenario: Multiple independent grants are requested

- **WHEN** a future feature requires two independently revocable grants for one
  subject/relation/resource identity
- **THEN** that feature receives a separate storage-model proposal
- **AND** is not simulated by duplicate v9 qualifier variants

### Requirement: Cross-runtime Caveat conformance

The JVM and ClojureScript implementations SHALL consume one canonical typed
Caveat representation and produce equivalent definite, false, partial,
missing-context, and error results. Phase 2 SHALL include differential fixtures
against the declared SpiceDB compatibility profile. The JVM path SHALL use the
pinned cel-parser adapter. The CLJS path SHALL provide equivalent behavior without
requiring JVM/ANTLR program objects at JavaScript runtime. Phase 2 SHALL
remain gated on this qualification; library selection SHALL NOT silently reduce
the supported runtime contract or introduce remote evaluation.

#### Scenario: JavaScript runtime boundary

- **WHEN** DataScript Caveats run under CLJS/Node
- **THEN** the qualified execution path consumes the portable representation without loading Java classes
- **AND** its results agree with the JVM cel-parser adapter and declared reference corpus

#### Scenario: Shared conformance corpus

- **WHEN** the same Caveat, relationship context, and request context are
  evaluated on every supported runtime
- **THEN** the permissionship and missing-context results are identical

#### Scenario: SpiceDB reference fixture

- **WHEN** a fixture uses syntax and values inside the declared compatibility
  profile
- **THEN** EACL and the pinned SpiceDB reference produce equivalent observable
  results

#### Scenario: Runtime divergence

- **WHEN** differential testing finds a semantic mismatch
- **THEN** Phase 2 release is blocked until EACL rejects the construct or the
  runtimes agree


### Requirement: Caveat value encoding is explicitly typed and portable

Before Phase 2 activation, EACL SHALL freeze a versioned supported type/coercion
profile and an injective typed encoding for every admitted CEL value. The
secure-format v1 envelope SHALL NOT be mistaken for that value model: native
doubles and integers beyond the JS safe range are not directly supported.
Unsupported values SHALL be rejected without rounding, overflow, or conflating
the types exposed to `any`. The reference SpiceDB release or commit SHALL be
recorded with the differential corpus.

#### Scenario: Large integer and double encoding

- **WHEN** the profile admits a signed/unsigned 64-bit integer or a double
- **THEN** CLJ and CLJS preserve the exact declared value and its type in canonical bytes
- **AND** numeric strings follow the declared conversion rule rather than silently rounding through a JS number

#### Scenario: Encoding is not yet qualified

- **WHEN** a profile type lacks a shared encoding or conformance result
- **THEN** Phase 2 admission for that type remains disabled

### Requirement: Authoritative faults never erase subtracting evidence

An encountered authoritative integrity or evaluation failure SHALL abort the
entire affected check, lookup, count, explanation, or batch operation. EACL
MUST NOT turn it into false or absent membership. `can?` SHALL propagate the
typed error. No successful reusable result SHALL be published from the failed
operation. This is separate from conditional permission caused by missing
request context.

#### Scenario: Corrupt subtracting edge

- **GIVEN** permission is `viewer - banned`, `viewer` is true, and `banned` has missing context storage, mismatched halves, or duplicate variants
- **WHEN** the operation encounters the fault
- **THEN** it fails with a typed error
- **AND** it cannot grant by dropping `banned`

#### Scenario: Subtracting evaluator fails

- **GIVEN** permission is `viewer - banned` and the `banned` Caveat exceeds its evaluation limit
- **WHEN** the evaluator reports failure
- **THEN** the whole operation fails rather than treating `banned` as false

### Requirement: Recursive residual evaluation terminates within declared limits

Recursive Caveat evaluation SHALL use canonical bounded residual identity and
fixed-point processing. Repeated visits to equivalent conditional states MUST
NOT grow residual formulas indefinitely. Unsupported recursive constructs SHALL
be rejected at schema admission. Work-limit exhaustion SHALL be a typed failure.

#### Scenario: Conditional cycle

- **WHEN** a supported recursive relation revisits the same residual state
- **THEN** evaluation converges without unbounded expression unfolding
- **AND** the shared conformance corpus verifies permissionship and missing fields

#### Scenario: Same Caveat with different bound values

- **WHEN** two paths attach one Caveat definition with different bound contexts
- **THEN** their residual identities preserve those bindings
- **AND** simplification cannot equate the paths merely by Caveat name

### Requirement: Caveat evaluation has no ambient mutable inputs

For a fixed selected schema, Relationship qualifiers, and admitted request context, the evaluator SHALL produce deterministic results within configured work limits. It MUST NOT read an ambient clock, network service, or other mutable state outside the declared request and dependency scope. Clock-like Caveat parameters SHALL be explicit context values and SHALL NOT override trusted Relationship expiry time.

#### Scenario: Repeated deterministic evaluation

- **WHEN** a Caveat is evaluated repeatedly with identical declared inputs
- **THEN** its permissionship, residual identity, missing fields, and error classification agree

#### Scenario: Ambient clock request

- **WHEN** a schema requests an evaluator feature that reads an undeclared current clock or external service
- **THEN** admission rejects the construct rather than making cached results depend on hidden state

### Requirement: Expired and speculatively inert references retain Caveat lifecycle guards

Caveat in-use checks SHALL cover stored references even when their Relationships are expired or their Relations are retained inert in a speculative snapshot. The existing speculative Relation orphan policy SHALL NOT permit removing a still-referenced Caveat definition or invalidating its stored bound context. The caller SHALL first remove or rewrite the references through an explicit guarded Relationship operation.

#### Scenario: Expired reference still exists

- **WHEN** schema replacement removes a Caveat referenced only by expired stored Relationships
- **THEN** the replacement fails with a typed in-use error until those references are deleted or rewritten

#### Scenario: Relation is retained inert

- **WHEN** speculative schema replacement retains Relationship tuples inert while also removing their referenced Caveat
- **THEN** the Caveat lifecycle guard rejects that replacement

### Requirement: Caveat activation is a semantic capability boundary

Phase 1 SHALL reject non-nil Caveat qualifiers and Caveat feature use with a typed unsupported-feature error. Phase 2 SHALL enable the same reserved tuple positions only after profile, encoding, evaluator, algebra, cache/cursor, and cross-runtime conformance gates pass. An encountered unsupported Caveat SHALL NOT be treated as an absent edge.

#### Scenario: Phase 1 encounters a Caveated subtracting edge

- **WHEN** a Phase 1 reader encounters a non-nil Caveat slot on evidence for an exclusion
- **THEN** it fails the affected operation rather than granting by omitting the edge

#### Scenario: Phase 2 enables the qualified profile

- **WHEN** the capability epoch activates after all Phase 2 gates pass
- **THEN** existing seven-slot positions retain their meanings
- **AND** old current-serving clients and reusable artifacts are fenced by the compatibility contract
