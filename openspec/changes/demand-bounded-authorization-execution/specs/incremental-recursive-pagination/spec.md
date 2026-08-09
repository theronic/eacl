## ADDED Requirements

### Requirement: The certified route fixes public order and cursor ABI
For one selected immutable snapshot, EACL SHALL derive the public result order
and cursor kind from the certified demand route, not from evaluation mode or
cache state. Certified acyclic enumeration SHALL use strictly ascending
positive internal EIDs with `:lookup-eid` boundaries. Recursive enumeration
SHALL use the versioned generated logical order with `:recursive-logical`
boundaries that bind both ordinal and external result identity. Explicit
completion MAY change the evaluator used to obtain closure but MUST NOT change
that route's public order or cursor ABI.

This requirement and the remaining requirements in this capability replace
the conflicting sorted-recursive-keyset, one-cursor-kind, implicit-completion,
and route-change-continuation requirements in the earlier active
`eacl-v8-root-fixes` change. Those superseded requirements MUST NOT be treated
as part of the EACL v8 release or certification contract.

#### Scenario: Complete evaluation of an acyclic root
- **WHEN** explicit completion routes a certified acyclic root through the fixed-point evaluator
- **THEN** EACL canonicalizes the completed artifact once to strictly ascending EID order before publication
- **AND** pages retain `:lookup-eid` cursors identical to demand-mode acyclic pages

#### Scenario: Complete evaluation of a recursive root
- **WHEN** explicit completion exhausts a recursive root
- **THEN** EACL preserves the generated logical result sequence without numeric sorting
- **AND** pages retain `:recursive-logical` cursors identical to demand-mode recursive pages

#### Scenario: Certified route changes
- **WHEN** schema, data-sensitive routing evidence, or an ordering ABI change selects a different public route for a continuation
- **THEN** EACL accepts the cursor only if its authenticated proof establishes the identical public order and boundary interpretation
- **AND** otherwise returns a typed stale-cursor or consistency-conflict error before traversal

### Requirement: Recursive order is a deterministic logical ABI
The recursive evaluator SHALL emit one total deterministic logical result order
with a complete tie-breaker. The sequence MUST be independent of backend chunk
size, generated fuel, physical wave batching, cache hit pattern, page size,
host map/set iteration, and runtime.

#### Scenario: Chunk-size permutation
- **WHEN** the same immutable graph is evaluated with every certified adapter chunk size
- **THEN** the complete logical result sequence is identical

#### Scenario: Cache permutation
- **WHEN** one evaluation uses no cache, another has arbitrary exact command hits, and both select proof-equivalent graphs
- **THEN** their logical result sequence and cursor boundaries are identical

#### Scenario: Cross-runtime order
- **WHEN** CLJ and CLJS evaluate the same certified graph, schema plan, and execution contract
- **THEN** they emit identical ordered external result identities and page flags

### Requirement: Recursive pages are demand bounded
A forward or continued recursive page of size `N` SHALL request at most the
work needed to restore its authenticated position and produce `N+1` subsequent
ordered distinct results or prove exhaustion. EACL MUST NOT materialize and
sort the complete denotation merely to render a bounded page.

#### Scenario: First page of broad denotation
- **WHEN** a subject has a broad recursive denotation and requests `:first N`
- **THEN** EACL stops after `N+1` ordered results or exhaustion
- **AND** unrelated tail width does not increase the cold first-page command trace

#### Scenario: Continuation hit
- **WHEN** a valid private continuation is retained
- **THEN** EACL resumes it and demands only the next page window plus sentinel

#### Scenario: Continuation miss
- **WHEN** a cursor is valid but private continuation state is absent
- **THEN** EACL replays deterministic traversal to the authenticated ordinal/boundary under the request deadline and limits
- **AND** does not silently restart the public walk from page one

### Requirement: Cursor identity binds execution and order
Every recursive cursor SHALL authenticate source/branch/incarnation, graph and
dependency proof, canonical query and operation, schema/engine/adapter/identity
and ordering ABI, direction, ordinal, boundary identity, execution mode,
answer-affecting limits, and absolute expiry. A mismatch MUST be rejected before
continuation traversal.

#### Scenario: Ordering ABI changes
- **WHEN** a cursor minted under ordering ABI `O0` is presented to `O1`
- **THEN** EACL returns a typed invalid/stale cursor error
- **AND** does not reinterpret its ordinal under `O1`

#### Scenario: Page size changes
- **WHEN** a valid cursor is resumed with another positive page size while all bound semantic fields match
- **THEN** EACL resumes the same boundary and applies the new page demand

#### Scenario: Execution mode conflicts
- **WHEN** a cursor's bound execution mode is incompatible with the resumed request
- **THEN** EACL returns a typed consistency/cursor conflict rather than changing work policy silently

#### Scenario: Traversal limits change
- **WHEN** a cursor minted under normalized limit map `L0` is resumed by a client using different normalized map `L1`
- **THEN** EACL rejects the cursor before continuation lookup or traversal
- **AND** does not reuse high-limit private work under a lower-limit execution contract

### Requirement: Proof-equivalent continuation is safe
EACL SHALL continue a cursor on another snapshot only under a verified
proof-equivalence or compatible exact-snapshot rule.
A cursor MAY continue on a different current immutable snapshot only when its
complete schema, relationship, identity, and ordering dependency proof is
equal and the traversal-order theorem establishes observational equivalence.
Relevant proof changes MUST NOT produce a hybrid walk.

#### Scenario: Unrelated mutation
- **WHEN** current data advances only outside the cursor's complete dependencies
- **THEN** EACL may continue on current without duplicates or omissions
- **AND** subsequent cursors bind the newly selected graph context

#### Scenario: Relevant mutation with exact fallback
- **WHEN** the proof changes and the backend supports verified recovery of the cursor's original exact snapshot without violating a newer freshness floor
- **THEN** EACL may continue only on that verified exact snapshot

#### Scenario: Relevant mutation without fallback
- **WHEN** the proof changes and no compatible exact snapshot is supported or retained
- **THEN** EACL returns a typed stale-cursor or consistency-conflict error
- **AND** does not silently restart or continue on changed data

#### Scenario: Newer at-least floor conflicts
- **WHEN** a continuation also requests an at-least floor whose qualifying snapshots have a changed dependency proof
- **THEN** EACL returns a typed newer-floor conflict
- **AND** does not fall back to an older exact snapshot

### Requirement: Backward requests have explicit work semantics
Backward pagination SHALL use deterministic prefix replay and bounded retained
window state. A recursive bare `:last` request MUST require
`:evaluation :complete-denotation`; EACL MUST NOT hide full-prefix exhaustion
behind default demand mode.

#### Scenario: Before cursor
- **WHEN** a caller requests `:last N :before cursor`
- **THEN** EACL replays only as required to reconstruct the bounded prefix window under deadline and limits
- **AND** returns the preceding `N` results in public order

#### Scenario: Bare last in demand mode
- **WHEN** a recursive request supplies `:last N` without `:before` under demand evaluation
- **THEN** EACL returns a typed complete-evaluation-required error before traversal

#### Scenario: Bare last in complete mode
- **WHEN** the same request explicitly selects complete-denotation evaluation
- **THEN** EACL may exhaust the denotation and return the final `N` results

### Requirement: Pagination work and recovery are observable
EACL SHALL separately count first-page commands, continuation hits, replayed
commands/values, exact fallback, proof-equivalent current continuation, and
stale/conflict rejection. Page cache hits MUST preserve the cursor's selected
snapshot and ordering contract.

#### Scenario: Page cache hit
- **WHEN** an exact completed page response is reused
- **THEN** provenance identifies the page hit and original computation basis
- **AND** consistency validation identifies the selected snapshot on which it is valid
