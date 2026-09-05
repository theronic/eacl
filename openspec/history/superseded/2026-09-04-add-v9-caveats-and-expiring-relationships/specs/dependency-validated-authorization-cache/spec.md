## ADDED Requirements

### Requirement: Expiry cache reuse requires a checked temporal certificate

Every reusable completed answer, denotation, projection, residual, count, page, or continuation that can change with Relationship expiry SHALL carry its computation evaluation time and a conservative exclusive reuse deadline. Nil deadline SHALL mean no future expiry can change that artifact under unchanged non-time dependencies. Reuse SHALL require selected time at or after the certificate's start and strictly before a finite deadline, in addition to all existing complete database, source, schema, identity, and semantic proof checks.

The certificate SHALL be checked on every accepted hit. Provider TTL, asynchronous eviction, unchanged database basis, and Relation-version equality SHALL NOT replace that check. Exact-time identity or no publication SHALL be the fallback when no complete expiry proof is available. Cache lifting across database revisions SHALL retain its existing causal/source constraints independently of time.

#### Scenario: Grant expires on one database basis

- **WHEN** a cached grant's necessary Relationship expires without a database write
- **THEN** the grant is a miss at the exclusive deadline despite equal Relation versions

#### Scenario: Cached denial becomes a grant

- **GIVEN** read is viewer minus banned, viewer is permanent, and banned expires at 100
- **WHEN** a denial computed at 90 is requested at 100 on the same basis
- **THEN** it cannot be reused under a certificate extending past that ban expiry
- **AND** fresh evaluation can return has-permission

#### Scenario: Dependency changes before expiry

- **WHEN** selected time is inside the certificate but a relevant database dependency changes
- **THEN** the ordinary proof rejects reuse

#### Scenario: Earlier explicit snapshot

- **WHEN** a frozen snapshot's evaluation time precedes the certificate start
- **THEN** forward-time reuse rejects it even if database proofs match

#### Scenario: Incomplete proof

- **WHEN** the engine cannot establish a complete temporal certificate
- **THEN** it uses exact-time identity or evaluates without reusable publication
- **AND** does not encode unknown expiry as an unbounded deadline

#### Scenario: Delayed eviction

- **WHEN** a provider still returns an authenticated entry after its reuse deadline
- **THEN** the client rejects it before returning an authorization result

### Requirement: Caveat cache identity is context-complete

For every cache entry or subproblem influenced by a possible Caveat path,
lookup scope, dependency certificates, and authenticated value data together
SHALL distinguish:

- the complete canonical request context admitted for the operation;
- each relevant named Caveat definition, its expression reference and complete canonical expression payload, and schema generation;
- each relationship Caveat context eid and authoritative payload;
- the Caveat compatibility profile and evaluator fingerprint, including the
  pinned JVM cel-parser build, EACL adapter/plan format and partial/error semantics, answer-affecting options,
  enabled declarations/macros/functions, and extension implementation identities;
- permissionship, residual condition, and missing-context result data; and
- the ordinary complete relation dependency proof.

The lookup key SHALL be computable before evaluation. Permissionship, residual
condition, and missing fields SHALL be authenticated value data, not inputs
that require knowing the answer before a lookup. Definition and bound-context
content, including referenced expression payloads, SHALL be dependency-certificate data.

A relation-version proof alone SHALL NOT authorize reuse after context payload,
Caveat definition/expression, request context, or evaluator semantics change.

#### Scenario: Same graph and different request context

- **WHEN** two requests select the same database and evaluation time but differ in a
  context value visible to a possible Caveat path
- **THEN** their semantic cache identities differ

#### Scenario: Bound context overrides request context

- **WHEN** relationship-bound context fixes a value also present in request
  context
- **THEN** lookup identity includes the admitted request context and dependency
  validation covers the authoritative bound values
- **AND** a caller-supplied replacement cannot alias the bound result

#### Scenario: Caveat definition changes

- **WHEN** schema replacement changes a referenced Caveat definition
- **THEN** the changed schema generation rejects entries computed under the old
  definition

#### Scenario: Caveat context changes out of band

- **WHEN** a context payload changes while its eid and relation version remain
  unchanged
- **THEN** database-visible proof validation rejects the prior entry

#### Scenario: CEL configuration changes without a database write

- **WHEN** a deployment changes cel-parser build, EACL numeric/partial/error handling, or a custom extension implementation while the database is unchanged
- **THEN** the changed evaluator identity prevents reuse of incompatible compiled programs and authorization results

#### Scenario: Evaluator profile changes

- **WHEN** the Caveat compatibility profile or evaluator fingerprint changes
- **THEN** entries produced by the old evaluator are cache misses

#### Scenario: Expression payload changes out of band

- **WHEN** a referenced expression payload changes while its eid, claimed content key, and Relation versions remain unchanged
- **THEN** selected-view schema content validation rejects prior answer reuse and any stale eid-to-expression resolution
- **AND** a compiled program does not substitute for authoritative content validation

### Requirement: Program caches reuse expression structure independently of results

Each client SHALL maintain a bounded, rebuildable local program cache for lazily evaluated Caveat expressions. Its key SHALL distinguish the complete canonical expression payload and evaluator fingerprint, including library/build, adapter/plan format, types/profile, options, numeric/partial/error behavior, and extensions. Digest lookup SHALL verify canonical payload equality. The cache SHALL NOT be keyed solely by Caveat name, expression eid, CEL source alone, or schema generation.

A fast eid-to-content mapping SHALL be scoped and validated against the selected database source/lifecycle and schema view, including speculative branches. Identical canonical content MAY share a program across validated views; coincident eids SHALL NOT establish equivalence. Program entries SHALL retain executable structure only, with no request/bound context, residual, permissionship, or temporal authorization certificate. Relationship expiry and changed bindings SHALL NOT by themselves require rebuilding an unchanged program, and a program hit SHALL NOT authorize answer reuse.

The client SHALL establish canonical content identity and collision checks when admitting or refreshing the validated mapping. Repeated traversal within that qualified scope SHALL reuse a cached identity/handle without canonicalizing, hashing, or comparing the complete expression source per Relationship. Selected-view proof rules SHALL still govern mapping validity.

Concurrent misses for one key SHALL share one in-flight construction. Completed entries and in-flight work SHALL be bounded; successful immutable programs alone SHALL be published. Failed builds SHALL release their in-flight entries and return typed failures to affected callers without poisoning later retries. Eviction SHALL permit equivalent reconstruction. Per-caller cancellation SHALL NOT corrupt a program or another caller's shared construction. Runtime-specific programs SHALL NOT be serialized into database entities, portable cache snapshots, or cursors.

#### Scenario: Different names share one resident program

- **WHEN** two named Caveats resolve to equal canonical expression content under the same evaluator fingerprint
- **THEN** evaluations in one client reuse the resident program
- **AND** their schema branch permissions and authorization dependencies remain independently validated

#### Scenario: Context changes while the program stays resident

- **WHEN** the same expression is evaluated with different bound or request values
- **THEN** the program is reused with fresh bindings
- **AND** no previous evaluation result is returned solely because the program key matches

#### Scenario: Concurrent cold requests

- **WHEN** concurrent traversals miss the same program key
- **THEN** one construction serves those callers within the configured build limits
- **AND** each caller evaluates using its own context after construction

#### Scenario: Eviction or failed construction

- **WHEN** a program is evicted or construction fails
- **THEN** a later eligible traversal can rebuild it from validated authoritative expression content
- **AND** neither a partial program nor a fabricated denial is retained

#### Scenario: Eid collision across database views

- **WHEN** two sources or divergent speculative views use the same expression eid for different content
- **THEN** their validated resolution and program keys distinguish that content

#### Scenario: Unrelated schema change

- **WHEN** schema generation changes but a referenced expression and evaluator fingerprint remain identical
- **THEN** the client may retain its program
- **AND** answer, traversal-plan, and cursor reuse still follow the ordinary schema and dependency proof rules

#### Scenario: Expression expires on a Relationship

- **WHEN** a Relationship carrying an expression reaches its expiry deadline
- **THEN** authorization time certificates reject incompatible results and traversal omits that edge
- **AND** the pure program may remain resident for other active Relationships or retained snapshots

#### Scenario: Warm catalog and program

- **WHEN** the selected schema catalog already validates the definition-to-expression mapping and the program is resident
- **THEN** repeated evaluation of that expression needs no per-Relationship expression database read or compilation
- **AND** it reuses a qualified identity/handle without rehashing or comparing the complete source per edge
- **AND** required bound-context reads, ordinary proofs, fresh bindings, and evaluation work remain separately accounted for

### Requirement: Conditional results cannot alias definite results

The completed-answer and subproblem value formats SHALL distinguish
`:has-permission`, `:no-permission`, and `:conditional-permission`.
Conditional entries SHALL authenticate their residual condition and missing
context fields where retained. Cache lookup MUST NOT substitute one
permissionship state for another.

#### Scenario: Conditional value is replayed as a grant

- **WHEN** an external provider returns a validly shaped conditional value under
  a key for a definite grant
- **THEN** complete key/value authentication rejects it

#### Scenario: Missing field set changes

- **WHEN** two conditional evaluations require different context fields
- **THEN** their retained result identities differ

#### Scenario: Boolean can? uses a conditional cache entry

- **WHEN** `can?` obtains a valid cached conditional permissionship
- **THEN** it returns false rather than treating the entry as a grant

### Requirement: Caveat algebra caching preserves possible paths

Caveat-aware cache proofs SHALL cover all positive, negative, and conditional
paths that can affect the result. Runtime short-circuiting MAY retain a smaller
witness only when the proof establishes that omitted paths cannot alter the
permissionship or residual condition.

#### Scenario: Unconditional union witness

- **WHEN** one union branch grants unconditionally and another is conditional
- **THEN** a cached has-permission result need not retain the irrelevant
  conditional branch as a result dependency
- **AND** its ordinary static relationship dependency closure remains complete

#### Scenario: Conditional union

- **WHEN** every possible granting branch is false or conditional
- **THEN** a cached conditional result commits to all residual alternatives
  needed to reproduce it

#### Scenario: Conditional exclusion

- **WHEN** a true positive branch is reduced by a conditional subtracting branch
- **THEN** the cached result remains conditional
- **AND** commits to the subtracting residual condition

#### Scenario: Missing proof

- **WHEN** EACL cannot prove that a cached residual condition is complete
- **THEN** it evaluates without reusable Caveat result publication

### Requirement: Expiry and Caveat ABI changes invalidate portable caches

The authorization ABI used by exact and managed keys, exported cache snapshots,
subproblem values, and restored entries SHALL change for the v9 relationship
representation and Phase 2 Caveat semantics. Restoring an artifact whose ABI
does not commit to seven-slot qualifiers, evaluation time, request context, and
conditional result semantics SHALL fail closed.

#### Scenario: Restore old cache snapshot

- **WHEN** a v9 client restores a cache snapshot produced by the old
  relationship ABI
- **THEN** it rejects the snapshot before any entry influences authorization

#### Scenario: Restore Phase 1 artifact in Phase 2

- **WHEN** Phase 2 enables Caveats and encounters a Phase 1 cache artifact whose
  ABI cannot encode conditional semantics
- **THEN** it rejects or treats the artifact as a miss

#### Scenario: Shared provider contains old entries

- **WHEN** a shared provider returns an authenticated entry carrying an old
  authorization ABI
- **THEN** the v9 client treats it as a miss


### Requirement: Expiry certificates belong to each reusable artifact

Each certificate SHALL prove its own retained contents stable. Derivation SHALL cover all expiry-sensitive positive, negative, and conditional evidence required to establish those contents, including skipped candidates, lookahead, and retained frontier. The minimum future deadline over a complete relevant evidence set SHALL be a valid conservative bound; the deadline of returned positive edges alone SHALL NOT suffice. A stable final answer SHALL NOT widen an unstable child or continuation certificate. Static Relation dependency closure SHALL remain complete.

Authoritative faults SHALL NOT be cached as denials. Expiry-only monotonicity optimizations SHALL require proof over the full relevant permission expression, including nested exclusion, recursion, and Caveat context; absence of a direct subtraction in the top-level expression SHALL NOT suffice.

#### Scenario: Stable union with expiring child

- **GIVEN** permanent owner makes owner plus viewer true while viewer expires at 100
- **WHEN** the engine retains both the Boolean union answer and the viewer denotation at 90
- **THEN** the answer can be unbounded only under its complete proof
- **AND** the viewer denotation's certificate ends no later than 100

#### Scenario: Conditional result loses missing fields

- **WHEN** an expiring conditional branch can change a cached residual or missing-field set
- **THEN** that result's deadline covers the transition even if a coarser Boolean result stays unchanged

#### Scenario: Ban before the cursor boundary

- **WHEN** retained lookup state skipped an earlier identity due to a ban that can expire
- **THEN** its certificate covers that negative evidence rather than only already-emitted identities

#### Scenario: Proven positive-only denial

- **WHEN** the full relevant expression is proven positive-only and its Caveat inputs and ordinary dependencies are fixed
- **THEN** an implementation may prove that a denial remains a denial as edges expire
- **AND** that optimization cannot be generalized to exclusion-containing expressions without a separate proof

### Requirement: Collection follows ordinary Relationship invalidation

Actual collector deletion SHALL publish the affected Relation mutation identities in the same transaction as authoritative pair/context removal. Cache validation SHALL observe those changes through existing dependency rules even if effective authorization at or after the collection cutoff is unchanged. Merely scanning or observing that time passed SHALL NOT require a Relation mutation. An optional future derived-index-only repair SHALL NOT become authoritative Relationship content.

#### Scenario: Expired stored Relationship is collected

- **WHEN** collection retracts an expired pair in a cached dependency scope
- **THEN** its Relation mutation identity changes and prior dependency-equivalent reuse is rejected

#### Scenario: Unrelated Relation is collected

- **WHEN** collector deletions touch only Relations outside a request's complete dependency closure
- **THEN** those deletions alone do not invalidate that request's Relation proof

#### Scenario: Expiry without deletion

- **WHEN** only time advances beyond a Relationship deadline
- **THEN** temporal proof checks handle invalidation without manufacturing a database mutation
