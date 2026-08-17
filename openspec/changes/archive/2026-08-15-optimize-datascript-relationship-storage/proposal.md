## Why

DataScript currently stores each relationship as an entity with five component
datoms and five derived tuple datoms, while Datomic Pro and Datahike on PR #92
store the same logical relationship as two endpoint-local datoms. DataScript
1.7.8 cannot declare heterogeneous tuples, but it can index ordinary vector
values and traverse them in either direction, making the heavier relationship
entity unnecessary.

## What Changes

- Store each DataScript relationship as exactly two cardinality-many indexed
  vector values: a forward value on the subject entity and a reverse value on
  the resource entity, with the same component order used by Datomic Pro and
  Datahike.
- Replace relationship-entity queries and five derived relationship indexes
  with guarded EAVT and AVET `datoms`, `seek-datoms`, `rseek-datoms`, and
  prefix scans over the two endpoint attributes.
- Converge DataScript relationship mutation, direct matching, adjacency,
  pagination, relation-in-use, object cleanup, integrity reporting, and content
  proof behavior with the Datomic/Datahike endpoint-pair implementation.
- Preserve the accepted ghost-half contract: consumers must remove
  relationships through EACL before retracting endpoint entities, while
  `:touch`, `:delete`, object cleanup, and an offline integrity report detect or
  repair incomplete pairs.
- Add JVM and ClojureScript regression coverage for index boundaries,
  forward/reverse seek behavior, cursor order, half-pair repair, content-proof
  invalidation, and shared backend conformance.
- Benchmark storage cardinality and representative read/write paths against the
  relationship-entity implementation.
- **BREAKING**: prerelease DataScript databases using relationship entities are
  not dual-read or migrated. Recreate demo databases or reload relationships
  through the EACL API.

## Capabilities

### New Capabilities

- `converged-relationship-storage`: Defines the two-value endpoint-pair storage,
  index access, mutation, integrity, proof, compatibility, and validation
  contract shared logically by DataScript, Datahike, and Datomic Pro.

### Modified Capabilities

None.

## Impact

- Affects `modules/eacl-datascript` schema, storage adapter, backend proof
  adapter, object deletion, relationship APIs, tests, and documentation.
- Reuses backend-neutral relationship planning and shared conformance suites;
  no DataScript dependency is introduced into the core module.
- Removes DataScript relationship component attributes, the unique full-key
  tuple, and four relationship scan tuples from the active v8 schema.
- Targets a new stacked PR whose base is PR #92
  (`codex/datahike-datomic-relationship-tuples`).
