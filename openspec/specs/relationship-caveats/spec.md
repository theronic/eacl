# relationship-caveats Specification

## Purpose

Defines shared typed SpiceDB-style Caveat definitions, bounded Relationship-bound context, and a versioned CEL compatibility/evaluator contract ready for later traversal activation.

## Requirements

### Requirement: Caveat definitions are shared named schema objects
A Caveat definition SHALL be stored once per schema name with canonical typed parameters, CEL source, and evaluator profile identity. Relationship qualifiers SHALL reference the definition entity and MUST NOT duplicate the expression source.

#### Scenario: Two Relationships use one Caveat
- **WHEN** two qualifier entities bind the same named Caveat
- **THEN** both reference one Caveat definition entity and store only their own optional bound context

#### Scenario: Definition changes
- **WHEN** `write-schema!` changes a named Caveat expression or parameter declaration
- **THEN** schema generation advances atomically and the new selected schema exposes the new canonical definition

#### Scenario: Same expression under two names
- **WHEN** two named Caveats contain equal source and parameter declarations
- **THEN** each remains a distinct named schema definition
- **AND** this phase does not introduce a separate cross-name expression-interning lifecycle

### Requirement: Caveat declarations are typed and statically bounded
Schema admission SHALL parse Caveat declarations, require a Boolean result in the supported EACL CEL profile, resolve every variable to one declared parameter, validate Relation `with caveat` references, and enforce configured source/plan/type limits before durable schema replacement.

#### Scenario: Valid declaration
- **WHEN** a supported Caveat declares typed parameters and a supported Boolean expression
- **THEN** schema validation produces one canonical Caveat definition and Relation references may resolve it

#### Scenario: Unknown parameter
- **WHEN** an expression references a name absent from its parameter declarations
- **THEN** schema validation fails before commit

#### Scenario: Unsupported construct or type
- **WHEN** a Caveat uses a CEL construct, function, overload, or type outside the versioned profile
- **THEN** schema validation fails with a typed profile error

#### Scenario: Non-Boolean root
- **WHEN** a Caveat expression cannot produce Boolean permissionship
- **THEN** schema validation rejects it

### Requirement: Relation branches explicitly allow Caveats
A Relation subject branch SHALL declare which Caveat may qualify a Relationship. A Relationship may carry zero or one Caveat, and the stored Caveat MUST be allowed for its resolved Relation branch.

#### Scenario: Allowed optional Caveat
- **WHEN** a branch declares `with office_hours` and a qualifier references `office_hours`
- **THEN** internal Relationship validation accepts that Caveat binding

#### Scenario: Caveat is not declared on the branch
- **WHEN** a qualifier references a Caveat not allowed by the resolved Relation subject branch
- **THEN** validation rejects the Relationship

#### Scenario: Only a Caveated branch is declared
- **WHEN** a Relation permits `user with office_hours` without a plain `user` alternative
- **THEN** an unqualified or expiry-only Relationship is rejected
- **AND** the required Caveat cannot be omitted

#### Scenario: Plain and Caveated alternatives coexist
- **WHEN** a Relation permits `user | user with office_hours`
- **THEN** both an unqualified Relationship and one qualified by `office_hours` are valid alternatives
- **AND** they share one first-four Relationship identity rather than becoming parallel grants

#### Scenario: More than one Caveat is requested
- **WHEN** a Relationship update attempts to bind multiple Caveats
- **THEN** validation rejects it rather than creating multiple logical Relationships

### Requirement: Relationship-bound Caveat context is canonical and sparse
A qualifier MAY store one bounded canonical map of parameter values for its Caveat. Empty context SHALL be omitted. Bound keys and values MUST conform to the definition's declarations, and stored values SHALL override request values with the same key during future evaluation.

#### Scenario: Partial bound context
- **WHEN** a qualifier binds a valid subset of Caveat parameters
- **THEN** it stores one canonical payload containing only those values

#### Scenario: Unknown bound key
- **WHEN** bound context contains a key absent from the Caveat declaration
- **THEN** validation fails before qualifier creation

#### Scenario: Bound value has wrong type
- **WHEN** a stored value does not conform to its declared CEL type
- **THEN** validation fails before qualifier creation

#### Scenario: Duplicate request key
- **WHEN** future request context supplies a value also present in bound context
- **THEN** the formally specified effective context uses the Relationship-bound value

### Requirement: EACL owns a versioned cel-parser compatibility profile
The JVM default Caveat adapter SHALL pin `com.exoscale/cel-parser` 0.1.8 and expose only the EACL-qualified CEL profile. Durable Caveat identity SHALL use canonical EACL source/types/profile, not serialized parser programs or library implementation objects.

#### Scenario: Complete-context program evaluation
- **WHEN** all required values are known and the expression is inside the profile
- **THEN** the adapter evaluates a cached or newly built cel-parser program and translates the result to the EACL outcome model

#### Scenario: cel-parser returns an error value
- **WHEN** `eval-for` returns a CEL error object instead of throwing
- **THEN** the adapter classifies it as an evaluator error and never treats the object as truthy authorization

#### Scenario: Program cache is evicted
- **WHEN** a compiled program is absent
- **THEN** it is rebuilt from the canonical selected definition without changing authorization semantics

#### Scenario: Dependency version changes
- **WHEN** the cel-parser build, admitted profile, or value adapter changes
- **THEN** evaluator identity changes and future cache/cursor compatibility cannot alias the old evaluator

### Requirement: Caveat evaluation has explicit partial outcomes
The Caveat subsystem SHALL define deterministic `true`, `false`, `conditional`, and `error` outcomes. Missing context SHALL produce a conditional residual only when unavailable values can still change the result; short-circuiting MAY produce a definite result despite other missing parameters.

#### Scenario: Missing value matters
- **WHEN** an expression's result depends on an absent parameter
- **THEN** evaluation returns conditional outcome with canonical residual and missing-field set

#### Scenario: Missing value is short-circuited
- **WHEN** known operands determine the Boolean result without an absent parameter
- **THEN** evaluation returns the definite result rather than conditional

#### Scenario: Type or overload fails
- **WHEN** supplied context selects an invalid overload or violates a declared type
- **THEN** evaluation returns an error outcome, not missing context or false

#### Scenario: Repeated deterministic evaluation
- **WHEN** the same definition, profile, bound context, and request context are evaluated repeatedly
- **THEN** the canonical outcome and residual are identical

### Requirement: Evaluator capability is explicit across runtimes
A client SHALL activate Caveated schema only when it has an evaluator certified for the schema's profile. The bundled cel-parser evaluator SHALL be JVM-only; ClojureScript SHALL fail before serving Caveated Relationships unless a separately supplied evaluator has matching identity and conformance evidence.

#### Scenario: JVM default evaluator
- **WHEN** a JVM client enables a supported Caveat profile
- **THEN** the pinned default evaluator can validate and execute the profile

#### Scenario: ClojureScript without evaluator
- **WHEN** a CLJS client selects a schema that can reach Caveated Relationships but supplies no matching evaluator
- **THEN** client/schema activation fails closed before authorization

#### Scenario: Custom evaluator fingerprint differs
- **WHEN** a supplied evaluator advertises a different profile or semantic fingerprint
- **THEN** it cannot serve the selected Caveat schema

### Requirement: Qualifiers do not create parallel Relationship identities
Caveat name and bound context SHALL be replaceable qualifiers of one subject/Relation/resource Relationship. `:create` MUST conflict with an existing first-four identity even when Caveat data differs; future `:touch` SHALL be the replacement operation.

#### Scenario: Different Caveat
- **WHEN** a second create differs from an existing Relationship only by Caveat name
- **THEN** it reports a Relationship conflict

#### Scenario: Different bound context
- **WHEN** a second create differs only by bound context
- **THEN** it reports a Relationship conflict

#### Scenario: Identity-only delete
- **WHEN** a future delete names subject, Relation, and resource without Caveat/context
- **THEN** it identifies whichever qualified variant is stored
