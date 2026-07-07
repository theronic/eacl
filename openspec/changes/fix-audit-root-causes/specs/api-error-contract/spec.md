# api-error-contract

`IAuthorization` protocol completeness and typed error behavior for the Datomic client. Covers the `Spiceomic` record in `eacl.datomic.core` and protocol declarations in `eacl.core`.

## ADDED Requirements

### Requirement: All declared protocol methods are implemented
Every method declared on `IAuthorization` SHALL have an implementation on the Datomic client. `write-relationship!` (operation arity and map arity) and `delete-relationship!` (positional and map arities) SHALL delegate to the relationship-write pipeline. No protocol method SHALL throw `AbstractMethodError`.

#### Scenario: write-relationship! works
- **WHEN** `(write-relationship! client :touch subject :owner resource)` is called with resolvable objects
- **THEN** the relationship exists afterwards and the call returns a `:zed/token`

#### Scenario: delete-relationship! works
- **WHEN** `(delete-relationship! client subject :owner resource)` is called for an existing relationship
- **THEN** the relationship no longer exists afterwards

### Requirement: Unimplemented and unsupported features throw typed errors
`expand-permission-tree` SHALL throw `ex-info` with `:type :eacl/not-implemented`. Passing a consistency other than `fully-consistent` to `can?` SHALL throw `ex-info` with `:type :eacl/unsupported-consistency`. These SHALL be `ex-info` (catchable by `:type`), not bare `Exception`s or `assert`s.

#### Scenario: Non-full consistency is a typed error
- **WHEN** `can?` is called with `(consistency/fresh token)`
- **THEN** an `ex-info` with `:type :eacl/unsupported-consistency` is thrown

### Requirement: Client input validation does not rely on assertions
Input validation in the client layer (missing/invalid subjects, resources, cursors, configuration) SHALL be enforced with typed `ex-info` errors or the documented empty-result contract — never with `assert`/`{:pre …}`, whose behavior disappears when `*assert*` is disabled. Vacuous checks (assertions that can never fail, such as comparing a value to itself) SHALL be removed.

#### Scenario: Validation survives disabled assertions
- **WHEN** the client namespaces are compiled with `*assert*` bound to `false` and an invalid input from the unknown-ID contract is supplied
- **THEN** the documented behavior (empty result or typed error) still occurs

#### Scenario: No vacuous type assertion in count-resources
- **WHEN** `count-resources` is called with a valid subject
- **THEN** no self-comparing type assertion exists in the code path (verified by inspection/test of behavior with mismatched entity types)
