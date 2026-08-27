## MODIFIED Requirements

### Requirement: One immutable request proof frame
The backend-neutral contract SHALL expose one immutable frame per request for the exact selected adapter, lineage, and basis: the certified schema generation, the canonical relation closure, and the scalar dependency frontier derived from one `:proof-frame` read. Completed answers, managed subproblems, schema planning, cursor validation, and checkpoint keys SHALL consume that one frame and the one lineage value from the request context rather than acquiring or digesting duplicate evidence. Purpose SHALL be expressed by key shape; no artifact SHALL carry a separately issued equivalence certificate.

#### Scenario: Exact cache hits first
- **WHEN** exact-basis lookup succeeds before a frame is needed
- **THEN** the adapter performs no relation-generation reads for that completed answer

#### Scenario: Several request consumers need the frame
- **WHEN** schema planning, a completed-answer lookup, managed subproblems, cursor validation, and checkpoint lookup require the same schema and relation evidence
- **THEN** they share the lazily acquired frame scoped to that request and the adapter reads each relation generation at most once

#### Scenario: Subproblem adds an unproved relation
- **WHEN** a managed subproblem declares a dependency outside the complete relation set established by the request frame
- **THEN** that subproblem is not admitted as a managed hit or publication from partial evidence

#### Scenario: Proof provider fails
- **WHEN** the adapter proof operation throws transiently or a generation is absent
- **THEN** the request remains exact-only, no partial frame is retained, and nothing is disabled

#### Scenario: Proof provider violates the contract
- **WHEN** the adapter returns malformed, duplicate, non-canonical, or above-revision evidence
- **THEN** the request remains exact-only and the runtime disables managed lifting until `expire-cache!`

### Requirement: Certified ordered-generation adapter capability
A cache-capable basis adapter SHALL certify immutable snapshot identity, a `:proof-frame` operation returning exactly the requested canonical relation generations in the same numeric domain as its native revision, a certified `:schema-generation` operation reading the transactionally maintained schema stamp, generations that never exceed the selected basis's revision, and native committed revisions that are globally ordered across supported commits within one lineage. The frame SHALL NOT return a schema generation of its own. Adapters without the ordered-generation capability SHALL remain valid exact-basis adapters.

#### Scenario: Bundled backend certification
- **WHEN** Datomic, Datahike, DataScript, or Datalevin executes a supported relationship mutation
- **THEN** adapter certification observes that every affected relation generation equals the committed revision of that transaction, exceeds every prior generation, and is reported in the revision domain

#### Scenario: Datomic domain conversion
- **WHEN** the Datomic adapter reads a relation stamp datom
- **THEN** it reports `(d/tx->t (:tx datom))`, the same domain as `basis-t`

#### Scenario: Exact-basis-only adapter
- **WHEN** an adapter supplies stable immutable snapshot identity but not certified ordered generations
- **THEN** the shared engine may use exact-basis caching without cross-basis managed reuse

#### Scenario: Third-party adapter declares numbers only
- **WHEN** a third-party adapter declares ordered generations but fails the domain, ceiling, atomic-stamping, or lineage obligations in certification
- **THEN** the adapter is not certified for the ordered-generation capability
