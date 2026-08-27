## MODIFIED Requirements

### Requirement: Randomized differential coverage of the managed tier
A randomized cached-versus-cache-free differential oracle SHALL run with the managed tier active on every bundled backend that advertises ordered generations, interleaving EACL-API relationship, schema, and object-deletion writes with checks, lookups, counts, and paginated reads, asserting answer equality at every step. The generator SHALL include requests at retained older bases after newer computations have published, and concurrent publication in either revision order, so direction-agnostic lifting is exercised.

#### Scenario: Managed oracle in CI
- **WHEN** the differential suites run in CI
- **THEN** at least one generator-driven configuration per ordered-generation backend has managed answer caching enabled and its interleaved-write comparisons pass

#### Scenario: Older retained basis hits a newer entry
- **WHEN** the generator reads at a retained basis after a newer basis published an equal-frame entry
- **THEN** the reused answer equals cache-free evaluation at the retained basis

## ADDED Requirements

### Requirement: Generation domain, ceiling, and lineage obligations are executable
Adapter certification SHALL execute, against each bundled adapter and any third-party adapter claiming ordered generations: that relation generations and the native revision share one numeric domain; that after every supported mutation each affected relation's generation equals the committed revision; that every generation readable at any selected basis is less than or equal to that basis's revision; and that a non-durable source mints a distinct source identity per live source. Mutation controls for these obligations SHALL run against the adapter implementation; a registry entry whose detector only restates the expected values in test-local literals SHALL NOT discharge them.

#### Scenario: Future stamp control
- **WHEN** a control adapter reports a generation above its revision
- **THEN** certification observes the contract-violation classification and the runtime disablement

#### Scenario: Domain drift control
- **WHEN** a control Datomic adapter reports transaction entity ids instead of `t`
- **THEN** the ceiling obligation fails certification

#### Scenario: Reconnected in-memory source
- **WHEN** certification reconnects a DataScript or memory Datahike source with identical content
- **THEN** the new source scope differs and artifacts from the old source are rejected
