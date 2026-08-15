# EACL backend modules

Authorization semantics and cache orchestration live in `modules/eacl`.
Database access, immutable snapshot selection, transactions, exact-snapshot
recovery, and cursor protection remain adapter responsibilities. Applications
depend on one adapter module; backend authors depend on core.

## Choose a module

| Module | Runtime | Consistency and snapshots | Cursors |
| --- | --- | --- | --- |
| `eacl-datomic` | Clojure/JVM | current Peer DB, explicit sync barrier, causal floor, exact `d/as-of` | authenticated; proof-equivalent current continuation or exact reconstruction |
| `eacl-datahike` | Clojure/JVM | current connection DB; configured head barrier and retained exact selection when supported | authenticated; proof-equivalent current continuation or supported exact reconstruction |
| `eacl-datascript` | Clojure and ClojureScript | current connection DB; no arbitrary exact selection | authenticated proof-equivalent current continuation |
| `eacl` | Clojure and ClojureScript | supplied by an adapter | shared protocol, engine, proof, and cache implementation |

Capabilities are configuration-specific and are validated before
authorization. Ordinary calls select one current immutable snapshot and do not
perform historical selection.

All public list operations use Relay controls: `:first`/`:after` or
`:last`/`:before`. Counts use optional `:count-limit`. `delete-object!` is
available on every bundled adapter and removes relationships touching an
object without retracting the object entity itself.

## Cache and mutation rules

Every bundled backend creates a bounded client-private cache unless explicitly
disabled:

```clojure
(require '[eacl.cache :as cache])

(datascript/make-client conn {:cache cache/no-cache})
(datahike/make-client conn {:cache cache/no-cache})
(datomic/make-client conn {:cache cache/no-cache})
```

Exact immutable-snapshot lookup is always first. After an exact miss, complete
proof-backed reuse across unrelated forward transactions is automatic when the
request is deterministic and the adapter supplies certified ordered
generations. The retained identity contains the source lifecycle, schema
generation, and scalar maximum generation over the complete relation
dependency closure.

All authorization-relevant schema, relationship, identity/liveness, repair,
and safe-deletion mutations must use EACL APIs or documented EACL transaction
data/functions transacted intact. Unsupported raw mutation can leave stale
proof-backed state. Recovery requires quiescing affected traffic, repairing
data, expiring or recreating every affected client in every process, and then
resuming. `prepare-cache-coherence!`, an identical `write-schema!`, and cache
rotation are not data repair.

The exact lifecycle functions are:

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

See [cache operations](v8-consistency-cache-operations.md) for proof
availability, custom-codec, time-travel, and multi-process lifecycle rules.

## Optional atomic entity retraction

Every embedded bundled backend can support EACL's safe target-only retraction
where its transaction-function topology permits it. Multiple and repeated
invocations compose in one transaction. A known numeric eid can repair a
peer-only ghost; a missing lookup ref cannot recover the former eid.

| Backend/configuration | Preparation |
| --- | --- |
| Datomic Peer/Pro | `eacl.datomic.safe-retraction/install!` |
| DataScript CLJ/CLJS named form | `eacl.datascript.safe-retraction/install!` |
| DataScript direct in-process form | `eacl.datascript.safe-retraction/prepare!` |
| Datahike function-safe named topology | `eacl.datahike.safe-retraction/install!` |
| Datahike direct in-process topology | `eacl.datahike.safe-retraction/prepare!` |
| Function-unsafe remote topology | use `delete-object!`, then native entity deletion |

Do not combine relationship additions involving a target with safe retraction
of that target in the same application transaction. Use batched
`delete-object!` for very high-degree targets and backend integrity reports for
damage whose former eid is unknown.

## Recursive permissions and safety controls

All adapters use the same stable-discovery engine: one sealed plan per
permission root and one width-one deterministic reducer that admits each
(node, entity) exactly once. Each client accepts positive
`:recursive-traversal-limits` overrides:

```clojure
(datascript/make-client
 conn
 {:recursive-traversal-limits
  {:max-derived-grants 200000
   :max-advanced-datoms 200000
   :max-queued-work 200000}})
```

Exceeding a ceiling throws `:eacl.recursive-traversal/limit-exceeded`. Use
`:count-limit` for bounded counts and raise traversal limits only after
representative heap and load testing.

## Permission-tree expansion

All bundled adapters implement `expand-permission-tree` through one portable
CLJ/CLJS kernel. The strict request is `{:resource object :permission keyword}`
plus optional `:consistency`, `:timeout-ms`, and `:cancellation-token`; the response is
`{:expanded-at token :tree-root node}`. The token and every definition,
relationship, and rendered ID in the tree come from one selected immutable
adapter. Datomic can replay the exact token while history is retained;
Datahike can do so only in configurations with retained exact selection;
DataScript supplies current/causal selection but no arbitrary historical
reconstruction.

The tree is a shallow structural explanation, not a flattened authorization
answer. It preserves union, permission, and arrow boundaries, empty branches,
and duplicate multiplicity. Child/subject order is deliberately unspecified.
Use `can?` for membership decisions and compare normalized tree topology with
multisets when order is irrelevant.

Clients accept `:permission-tree-limits` with positive portable exact integers:

```clojure
{:max-depth 50
 :max-schema-components 100000
 :max-relationship-values 100000
 :max-tree-nodes 100000
 :max-leaf-subjects 100000}
```

These are construction-time ceilings; requests cannot override them. Limit,
deadline, cycle, codec, adapter-contract, unknown-root, and consistency
failures are typed and return no partial tree. This feature adds no schema,
stored attribute, dependency, or database migration.

Every bounded read accepts the same per-request cooperative cancellation
token. Create it with `eacl.core/cancellation-token` and signal it with
`eacl.core/cancel!`. Adapters do not need a new SPI operation: the shared
orchestrator checks the token at its stages and the engine checks it at every
reducer transition (which brackets each adapter command). A synchronous command already in progress must return before the
check can observe cancellation; adapter implementations with their own long
loops should call `eacl.execution/check!` at bounded internal checkpoints.

## Backend extension boundary

The adapter operation map validates snapshot/source identity, consistency,
object conversion, schema definitions, adjacency, direct matches, recursive
nodes, transaction behavior, cursor identity, and optional ordered-generation
proof capability. A third-party adapter without certified proof support remains
a correct exact-current adapter.

Backend authors should follow the [adapter boundary
inventory](v8-backend-adapter-boundary.md) and run the shared public API,
recursive, cache, mutation, and independent-oracle contracts.
