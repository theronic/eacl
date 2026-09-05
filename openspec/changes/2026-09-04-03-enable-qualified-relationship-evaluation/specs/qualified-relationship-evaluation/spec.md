## Purpose

Defines how EACL resolves sparse v9 Relationship qualifiers and composes Caveat-derived definite, conditional, and fault evidence through one authorization engine.

## ADDED Requirements

### Requirement: Ordinary Relationships retain a zero-lookup fast path
A Relationship whose qualifier component is `nil` SHALL participate as permanent unconditional edge evidence without resolving a qualifier entity or evaluating a Caveat. The common path MUST remain on the single v9 endpoint stream.

#### Scenario: Nil-qualified direct edge
- **WHEN** authorization encounters an ordinary endpoint value
- **THEN** it returns definite active edge evidence using no qualifier entity read

#### Scenario: Nil-qualified arrow page
- **WHEN** a page contains only ordinary edges
- **THEN** traversal preserves existing order and result semantics without allocating one qualifier object per edge

### Requirement: Non-nil qualifiers are resolved once and fail closed
Each distinct non-`nil` qualifier eid SHALL be resolved and validated at most once per top-level operation. Missing, malformed, wrong-version, disallowed, or otherwise invalid qualifier data MUST NOT be treated as an unconditional or absent edge. Admitted-writer single-ownership and immutability invariants MUST NOT be re-proved by a reverse whole-graph scan on the ordinary authorization path.

#### Scenario: Valid qualifier
- **WHEN** an endpoint pair references one valid singly owned qualifier
- **THEN** the request reuses one decoded qualifier for all encounters of that qid

#### Scenario: Missing qualifier
- **WHEN** a tuple references an entity that does not resolve
- **THEN** authorization returns a typed authoritative fault and never grants through that edge

#### Scenario: Fault is on a subtracting edge
- **WHEN** a deny/exclusion edge has corrupt qualifier data
- **THEN** the fault propagates rather than erasing the negative evidence and granting permission

#### Scenario: Managed writer ownership is already certified
- **WHEN** a supported-writer source returns a referenced qualifier under matching Relation/source proof
- **THEN** the hot path validates the referenced qualifier directly and does not enumerate other Relationships to prove single ownership again

### Requirement: Caveats produce detailed permissionship
A Caveated edge SHALL merge request context with Relationship-bound context using bound-value precedence and evaluate to definite true, definite false, conditional residual, or fault. EACL SHALL expose detailed permissionship equivalent to `has-permission`, `no-permission`, or `conditional-permission` for non-fault results.

#### Scenario: Caveat is true
- **WHEN** effective context makes the Caveat true
- **THEN** the edge contributes definite membership

#### Scenario: Caveat is false
- **WHEN** effective context makes the Caveat false
- **THEN** the edge contributes definite non-membership

#### Scenario: Context is incomplete
- **WHEN** missing parameters can change the Caveat result
- **THEN** the edge contributes conditional evidence with residual and missing fields

#### Scenario: Bound value conflicts with request value
- **WHEN** both contexts contain one declared parameter
- **THEN** the Relationship-bound value is evaluated

### Requirement: Conditional evidence composes through permission algebra
Union, intersection, exclusion, arrows, and supported recursion SHALL compose definite and conditional evidence according to their Boolean meaning while preserving bounded residuals, missing fields, order, and existing resource limits. Faults SHALL remain distinct from false.

#### Scenario: Unconditional union witness
- **WHEN** one union branch is definitely true and another is conditional
- **THEN** the union is definitely true

#### Scenario: Conditional intersection
- **WHEN** all known intersection branches are true and one branch is conditional
- **THEN** the intersection is conditional with the required residual

#### Scenario: Definite false intersection
- **WHEN** any intersection branch is definitely false
- **THEN** the intersection is definitely false even if another branch is conditional

#### Scenario: Conditional exclusion
- **WHEN** the left side is true and the right side is conditional
- **THEN** the exclusion is conditional with the logical residual `left && !right`

#### Scenario: Conditional arrow
- **WHEN** an intermediate edge or downstream permission is conditional
- **THEN** the resulting resource evidence preserves the combined condition without changing endpoint order

### Requirement: Qualified filtering preserves bounded work and completion state
Qualified and expired candidates SHALL consume the existing declared scan/evaluation work budgets. EACL MUST NOT perform an unbounded fill-to-page loop merely because candidates are false, expired, conditional, or faulty, and MUST preserve the distinction between a complete empty result and an incomplete result stopped by a deadline/resource bound.

#### Scenario: Many qualified candidates are filtered
- **WHEN** a requested page encounters more false, expired, or excluded qualified candidates than the operation budget permits
- **THEN** EACL returns the existing typed bounded-work outcome with resumable progress where supported
- **AND** it does not claim that the remaining result set is empty or complete

#### Scenario: Ordinary-only page
- **WHEN** every candidate has `nil` qualifier
- **THEN** qualification adds no secondary Relationship scan, qualifier fetch, or per-candidate Caveat allocation

### Requirement: Qualified writes require a certified publication strategy
A backend SHALL enable qualified Relationship writes only when it can publish both endpoint refs and the owning Relation mutation stamp atomically using either certified inline qualifier allocation or a concrete prepared qualifier eid. Capability selection SHALL occur at construction/activation, not as a per-edge authorization check.

#### Scenario: Inline-capable backend
- **WHEN** a backend is certified to resolve a newly created qualifier eid inside endpoint values
- **THEN** the committed writer may create and publish the qualifier in one transaction

#### Scenario: Prepared-reference backend
- **WHEN** a backend requires qualifier preparation
- **THEN** an unreferenced qualifier may be created first
- **AND** one later transaction atomically publishes both endpoint values, Relation stamp, and caller-composed application datoms

#### Scenario: Publication fails after preparation
- **WHEN** the final publication transaction fails
- **THEN** no qualified Relationship is visible and only inert orphan qualifier data may remain

#### Scenario: Backend capability is absent
- **WHEN** no certified publication strategy is available
- **THEN** qualified writes and schema activation fail before authorization rather than attempting a best-effort or two-half update

### Requirement: Qualifier changes replace one logical Relationship
Subject, Relation, and resource SHALL remain the Relationship identity. Caveat, bound context, and expiry differences SHALL be updated through `:touch`, not represented as parallel Relationships.

#### Scenario: Create differs only by qualifier
- **WHEN** `:create` targets an existing first-four identity with another Caveat/context/expiry
- **THEN** it reports `:eacl/relationship-conflict`

#### Scenario: Touch changes qualifier
- **WHEN** `:touch` changes any qualifier field
- **THEN** the semantic publication transaction replaces both exact endpoint values, removes the old current qualifier, and advances the Relation version atomically
- **AND** a backend using prepared references may have created the new unattached qualifier in an earlier inert preparation step

#### Scenario: Delete omits qualifier
- **WHEN** `:delete` names only the logical identity
- **THEN** it removes the stored tuple pair and owned qualifier regardless of their contents

### Requirement: Caveat evaluator capability is checked before serving
A selected schema that can reach Caveated Relationships SHALL be served only by a client with the matching certified evaluator/profile. Missing or mismatched capability MUST fail before current authorization.

#### Scenario: JVM supported profile
- **WHEN** a JVM client has the pinned evaluator for the schema profile
- **THEN** Caveated Relationships may be activated

#### Scenario: ClojureScript has no evaluator
- **WHEN** a CLJS client lacks a matching evaluator
- **THEN** it cannot serve Caveated schema and does not silently ignore Caveats

### Requirement: Boolean compatibility is fail closed
The public Boolean `can?` result SHALL be true only for definite `has-permission`. Definite denial, conditional permission, and authoritative faults SHALL not return true; detailed APIs SHALL preserve their distinct diagnostics.

#### Scenario: Conditional through can?
- **WHEN** a detailed check is conditional
- **THEN** `can?` returns false

#### Scenario: Definite grant through can?
- **WHEN** a detailed check is definite has-permission
- **THEN** `can?` returns true
