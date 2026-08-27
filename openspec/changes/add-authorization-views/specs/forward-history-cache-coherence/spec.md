## RENAMED Requirements

- FROM: `### Requirement: Exact-current generation isolation`
- TO: `### Requirement: Exact-basis generation isolation`

- FROM: `### Requirement: Exact requests never use managed cross-snapshot lifting`
- TO: `### Requirement: Historical bases never use managed cross-snapshot lifting`

## MODIFIED Requirements

### Requirement: Exact-basis generation isolation
The exact completed-answer tier SHALL be one tier keyed by complete basis identity and complete semantic request. The runtime SHALL retain a bounded set of basis generations, each owning the exact answers and the subproblem store for one basis, and SHALL evict whole generations by recency. A request at one basis MUST NOT observe an entry, projection, or subproblem of another basis.

#### Scenario: Several live bases
- **WHEN** an `acl` serves current requests while retained snapshots at older bases are read concurrently
- **THEN** each basis uses only its own generation and publication from one basis cannot populate another

#### Scenario: Late publication
- **WHEN** computation at one basis finishes after newer bases have generations
- **THEN** its result is keyed to its own basis and cannot masquerade as another generation

#### Scenario: Same basis from any target
- **WHEN** an `acl` read, a captured snapshot, and a direct snapshot name the identical basis
- **THEN** they share one generation and an answer computed through one is an exact hit for the others

#### Scenario: Retention is bounded
- **WHEN** retained-basis, weight, entry, or admission bounds evict a generation
- **THEN** a later request at that basis recomputes on its immutable value
- **AND** eviction does not imply snapshot, token, or cursor expiry

### Requirement: Historical bases never use managed cross-snapshot lifting
A request at a historical-class basis — an as-of or other exact historical value — SHALL probe only exact answers bound to its identical basis. It SHALL NOT use relation or schema proof equality to lift a managed answer computed at another revision and SHALL NOT validate historical answers using current-only or no-history stamps. An ordinary-class basis MAY use managed lifting under the lineage-scoped rule regardless of whether it was selected as current, captured, loaded by exact locator, or supplied directly.

#### Scenario: Managed answer has equal proof at a historical basis
- **WHEN** a managed answer computed at `T2` has proof equal to a historical basis `T1`
- **THEN** the request does not use it unless an exact entry exists for `T1`

#### Scenario: Ordinary basis loaded by locator
- **WHEN** an ordinary value is selected by exact locator and a managed answer computed at an older revision in the same lifecycle has an equal proof
- **THEN** EACL may return that answer

#### Scenario: Exact miss
- **WHEN** no exact answer matches a historical basis
- **THEN** EACL evaluates against the already selected basis and publishes only at that exact key

#### Scenario: Public response metadata
- **WHEN** an exact answer is reused
- **THEN** tokens, cursor envelopes, cache basis, external identifiers, and other public metadata are rebuilt from the selected basis

### Requirement: Exact cache identity is complete and lifecycle-scoped
The canonical exact-basis key SHALL include stable backend/source/branch identity, configured source lifecycle, native revision and exact locator, basis kind, adapter fingerprint and identity contract, engine/order ABI, normalized semantic request, result kind and shape, normalized demand, and answer-affecting limits. It SHALL exclude `:schema-identity` and every physical attribute-schema fingerprint. Numeric revision equality alone SHALL NOT establish identity, and inadmissible basis kinds SHALL have no exact identity.

#### Scenario: Source lifecycle rotates
- **WHEN** `expire-cache!` installs a new lifecycle
- **THEN** every prior exact entry becomes unreachable even if native revision numbers repeat

#### Scenario: Adapter semantics change
- **WHEN** adapter fingerprint, identity contract, engine/order ABI, or an answer-affecting limit changes
- **THEN** entries computed under the prior identity are ineligible

#### Scenario: Inadmissible value
- **WHEN** a filtered, since, history, or speculative value reaches the engine facade
- **THEN** it receives no exact identity and is not identified with the ordinary value it wraps

### Requirement: Explicit lifecycle boundary
Cache correctness SHALL cover ordinary forward history only. Database restore, reset, branch force, history replacement, source reseeding, or equivalent replacement SHALL require the consumer to quiesce and rotate the lifecycle of every affected `acl` in every process before serving requests. Public snapshots over admissible values participate in caching by basis class; only the engine facade bypasses completed-answer caching.

#### Scenario: Consumer restores a database
- **WHEN** a consumer restores or replaces the database history
- **THEN** the consumer rotates every affected `acl` lifecycle before resuming authorization traffic

#### Scenario: Backend can detect replacement
- **WHEN** a backend exposes reliable source-lifecycle replacement evidence
- **THEN** EACL may automatically rotate its lifecycle instead of requiring an explicit expiry call

#### Scenario: Engine-facade database value
- **WHEN** a low-level caller evaluates a value through the engine facade rather than a public snapshot
- **THEN** completed-answer caching is bypassed and `eacl.request.context/make-context` constructs an isolated request context

#### Scenario: Direct snapshot over an admissible value
- **WHEN** a public snapshot is constructed directly from an ordinary or as-of value
- **THEN** it uses the exact-basis generation for its identity and, for ordinary class, frame-admissible managed entries, exactly as a selected snapshot of the same basis would

#### Scenario: Retained snapshot after source advance
- **WHEN** a retained snapshot is read after newer transactions commit
- **THEN** it uses its own generation and frame-admissible managed entries from the same lifecycle without combining state from a newer basis

#### Scenario: Complete lifecycle rotation
- **WHEN** `expire-cache!` rotates an `acl` lifecycle
- **THEN** completed answers, managed subproblems, plans, derived schema caches, continuations, visited pages, revision checkpoints, diagnostics, and token/cursor source identity from the prior lifecycle become unreachable by new requests
- **AND** a retained snapshot from the prior lifecycle still evaluates correctly at its basis
