## Why

`eacl/expand-permission-tree` is part of the public authorization protocol but every shipped client raises `:eacl/not-implemented`. Issue #111 requires snapshot-consistent permission introspection whose supported semantics are checked against SpiceDB and whose completeness, cycle, and resource-limit rules have executable formal evidence rather than relying only on examples.

## What Changes

- Implement `eacl/expand-permission-tree` for Datomic, DataScript, and Datahike over one selected immutable snapshot.
- Return an explicit EACL map corresponding to Authzed's `ExpandPermissionTreeResponse` and `PermissionRelationshipTree` messages.
- Support direct relations, same-resource relation and permission references, EACL's supported single-level arrows, empty branches, absent resource ids, and recursive graphs.
- Define SpiceDB compatibility as shallow topology and leaf-membership equivalence for EACL-supported schemas and SpiceDB-valid string ids, after field conversion and order-insensitive multiset normalization.
- Reject malformed or ambiguous requests before snapshot selection and fail closed on cycles, deadlines, structural limits, codec failures, or adapter-contract violations without returning a partial tree.
- Add a dedicated, validated `:permission-tree-limits` client option because existing authorization traversal limits do not model expansion depth, tree nodes, or repeated leaf occurrences.
- Add a Dafny model and proof obligations for tree well-formedness, sum-typed direct-leaf exactness, union denotation, empty expansion, active-path cycle rejection, emitted-child depth, and monotone all-or-error budget accounting. The formal claim will explicitly retain adapters and Clojure-to-Dafny correspondence as trusted boundaries.
- Add version-pinned black-box SpiceDB golden fixtures, shared cross-backend contracts, CLJ/CLJS property tests, concurrency tests, and documentation.

## Capabilities

### New Capabilities

- `permission-tree-expansion`: Snapshot-consistent, bounded, formally modeled shallow expansion of a resource relation or permission into algebraic union nodes and direct-subject leaves across the supported EACL backends.

### Modified Capabilities

None. The adapter arity and required-operation set remain unchanged; expansion consumes the existing snapshot operations incrementally and owns its public limits and error contract.

## Impact

- Public API: `eacl.core/IAuthorization.expand-permission-tree` changes from a typed not-implemented failure to an operational read API.
- Public client configuration: all shipped clients accept `:permission-tree-limits` with documented defaults and strict validation.
- Shared implementation: a new portable CLJC permission-tree namespace, request validation, incremental guarded scan consumption, selected-snapshot token issuance, and shared contract fixtures.
- Backend wiring: DataScript and Datahike use shared orchestration; Datomic retains its consistency wrapper but delegates the semantic expansion to the same portable implementation.
- Formal evidence: a new Dafny module plus assurance-matrix and manifest coverage; this does not alter the generated authorization decision kernel or claim mechanical extraction of the Clojure implementation.
- Tests and documentation: version-pinned Docker fixture provenance, backend integration and mutation tests, CLJS coverage, READMEs, and release notes.
- Dependencies and storage: no new runtime dependency, persisted attribute, schema migration, token-format change, or completed-tree cache.
