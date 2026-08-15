## Context

See `proposal.md` for motivation and `specs/permission-tree-expansion/spec.md` for the observable contract. `IAuthorization` already declares `expand-permission-tree`; the shared orchestration client and Datomic `Spiceomic` currently throw `:eacl/not-implemented`.

The v8 snapshot adapter exposes the necessary raw facts: `relation-defs`, `permission-defs`, `resource->subjects`, object-id conversion, immutable selection, and native revision identity. Permission definitions retain flat union components but not authored component order. The shipped relationship scans are ordered lazy index walks, while optional generic runtime guards currently realize a scan eagerly. Existing recursive authorization limits describe derived grants, advanced datoms, and queued work; they do not accurately describe an introspection tree.

Black-box compatibility evidence uses the version-pinned SpiceDB v1.56.0 Docker service. The public API uses `SHALLOW` expansion. Its algebra is set-based; repeated protobuf fields carry results but do not establish a canonical semantic order.

Concurrent repository work may touch shared files. Every implementation edit must re-read the target and preserve unrelated changes; no broad rewrites or generated-source replacement are permitted.

## Goals / Non-Goals

**Goals:**

- Isolate one portable CLJC expansion kernel from the authorization-decision engine.
- Preserve supported SpiceDB shallow topology without reconstructing unavailable authored order.
- Make identity, cycle, deadline, and structural-limit behavior explicit and fail closed.
- Prove the mathematical kernel's central safety and semantic properties in Dafny, then differentially test the handwritten CLJC refinement.
- Keep selection, reads, rendering, and token issuance on one immutable adapter.

**Non-Goals:**

- Add unsupported SpiceDB schema features or a recursive direct-subject expansion mode.
- Treat successful Dafny verification as proof of Clojure, adapter, database, codec, scheduler, or cryptographic correctness.
- Add a completed-tree cache, change token formats, alter persisted schema, or change the required adapter-operation arity set.
- Guarantee production vector order or hard cancellation of a synchronous non-cooperative adapter operation.

## Decisions

### 1. Keep the public response as an explicit EACL mapping

The public response is `{:expanded-at token :tree-root node}`. Nodes use `SpiceObject`, keyword relation names, `:union`, and vectors corresponding mechanically to the Authzed messages. This retains all oneof and annotation information and supersedes the dormant historical nested-vector examples, which never represented a successful shipped contract.

The queried root descriptor always carries the exact supplied external `SpiceObject` plus an optional internal id. Same-resource descendants reuse that descriptor. Scanned arrow targets and leaf subjects use `{type, internal-id}` descriptors and are externalized later. This prevents unresolved numeric ids from being mistaken for renderable entities and prevents equal internal ids of different types from merging.

### 2. Build topology in a focused portable namespace

Add `eacl.permission-tree` as a CLJC namespace over `eacl.backend.v8`, `eacl.execution`, and public core records. Do not add expansion to the generated/formally verified authorization evaluator or reuse compiled permission paths: those paths describe denotation and may flatten proof topology.

The builder reads raw relation and permission definitions from the selected adapter. A root with relation definitions becomes one combined direct leaf; a root with permission definitions becomes a union. Neither is an unknown-root error; both simultaneously are an adapter-contract violation. Permission components implement same-resource relation/permission dispatch and arrow unions exactly as specified. Exact duplicate normalized definition rows may collapse because persisted schema is set-valued, but distinct paths and nested union boundaries remain.

Traversal is sequential and uses an explicit enter/exit work stack. Sequential evaluation makes budget and fake-clock failure boundaries reproducible across CLJ and CLJS. Active-path keys include object type, a request-local identity key, and permission name. Exit frames remove only the current branch, so diamonds remain legal. The implementation does not recursively depend on the host call stack.

### 3. Treat collection order as representation, not semantics

Production code does not sort external ids and therefore never adds `secure-format` portability as an object-id constraint. It may sort schema-only keys composed of keywords for reproducible local work, but callers cannot rely on any union-child or direct-subject vector order.

Tests use a recursive unordered-multiset normalizer. The normalizer sorts only controlled portable fixture encodings, retains duplicate multiplicity, preserves annotations and node variants, and never flattens nested unions. Cross-backend comparison omits `:expanded-at`; token behavior is tested separately because native revisions are backend-specific.

### 4. Give tree expansion its own structural budget

Add `default-permission-tree-limits` and strict normalization for depth, schema components, relationship values, tree nodes, and leaf-subject occurrences. Limits are positive portable exact integers and partial client overrides merge with defaults. They are not included in cache fingerprints because expansion is not cached.

One request-local budget value records all counters. Consumption checks occur before adding a schema component, realizing/accepting a relationship value, creating a node, or emitting a leaf subject. Errors contain only the operation, dimension, limit, and aggregate counters. Output is materialized entirely within the request; no lazy tree escapes.

Add incremental guarded helpers for expansion's schema-definition and relationship sequences rather than changing existing adapter arities or disabling guards. Definition rows are deadline-checked and metered before accumulation; relationship scans validate natural ids, strict order, uniqueness by adjacent order, and bounds as values are consumed. Shipped adapters therefore stop lazy realization at the permission-tree limit. A third-party adapter may still perform arbitrary work before returning a sequence; the documented deadline boundary remains one already-running synchronous adapter operation or realization.

### 5. Separate logical construction from selected-snapshot rendering

Internal nodes contain typed descriptors, never public backend ids. A final iterative renderer uses `internal-id->object` on the already-selected adapter and memoizes each `[type internal-id]` conversion for the request. `nil` or invalid conversion is a typed boundary failure. The supplied root descriptor bypasses conversion and is rendered exactly as supplied.

No global schema-derived or completed-tree cache participates in the first version. Request-local memoization may retain validated relation/permission definitions and rendered external identities because the selected adapter is immutable. Data-dependent subtrees are not memoized so branch multiplicity, accounting, and codec call boundaries remain obvious.

### 6. Select once and issue the response token from that adapter

Shared orchestration validates the request, creates one `:expand-permission-tree` execution contract, selects one context, runs the builder and renderer, then issues a token from that exact adapter's source scope and native revision. A small selected-adapter token helper is shared with Datomic without refreshing a connection.

Datomic enters its existing `execute-request` and consistency selector before calling the portable kernel. DataScript and Datahike use shared orchestration. Deadline checks surround validation completion, selection, every definition read, every scan realization, work-frame processing, rendering conversion, and token issuance. The existing honest overrun statement applies: no subsequent work begins after expiry, but synchronous foreign work cannot be forcibly preempted.

### 7. Model the semantic kernel in Dafny and state the proof boundary

Add `formal/dafny/PermissionTree.dfy` with finite identifiers and datatypes for object keys, typed objects, relation definitions, permission components, direct relationships, annotated trees, budgets, and outcomes. The model uses sequences for representation and multisets/sets for semantic equality. It defines shallow expansion with explicit fuel, active paths, and monotone counters.

The verified obligations include:

- every successful node is oneof-well-formed and annotated with its expansion target;
- direct leaves contain exactly the matching snapshot relationships across all declared subject types;
- union denotation is the union of child denotations and is invariant under child permutation;
- an absent resource has the same schema topology with empty data-dependent leaves;
- an active-path revisit cannot produce success;
- successful counters stay within every configured limit and failures produce no tree;
- budget consumption is monotone and the same immutable snapshot parameter governs every read.

The model includes executable witness methods for direct, union, arrow, empty, diamond, cycle, sum-typed relations, emitted-child depth, and limit cases plus mutation-control lemmas whose negated claims must fail verification. `bin/formal verify` verifies the module under the locked toolchain and records its exact proof effort. The assurance matrix and manifest name the theorem and retain `:adapter-contract`, `:host-source-specializations`, and Clojure-to-Dafny correspondence as residual assumptions.

Handwritten CLJC is not mechanically extracted. An independent pure reference evaluator and property generators compare runtime topology and failure classes over exhaustive bounded schemas/relationships, including permutations and type/id collisions. This is differential refinement evidence, not a formal Clojure semantics proof.

### 8. Make SpiceDB evidence reproducible

Each golden fixture stores the Docker image tag and digest, schema, relationships, request, captured protobuf JSON tree, and normalized expected EACL tree. The fixture is captured by a black-box run of the SpiceDB v1.56.0 Docker image at the recorded digest. Regular tests remain offline. Normalization ignores token bytes, default-valued empty subject relations, and unordered union/leaf order but preserves multiplicity, annotations, variants, and nested boundaries.

The compatibility fixture set covers direct and empty relations, union flattening within one permission, nested permission boundaries, arrow-to-relation, arrow-to-permission, multi-subject-type relations, absent objects, and duplicate paths. Custom/non-string ids are tested only as EACL extensions.

## Risks / Trade-offs

- [A verified model can be mistaken for verified production code] → Record the source-refinement and adapter boundaries in the model header, assurance matrix, manifest, and documentation.
- [Trees can grow exponentially] → Use explicit structural limits, sequential deterministic accounting, one deadline, and all-or-error materialization.
- [A non-cooperative adapter can overrun the deadline] → Start no later work, fail after return, and make no hard-cancellation claim.
- [No production ordering guarantee complicates display code] → Document unordered semantics and provide a fixture/test normalizer without restricting custom ids.
- [Active-path logic can accidentally reject diamonds] → Use enter/exit frames rather than a global visited set and prove/test the distinction.
- [Concurrent edits can be overwritten] → Re-read and compare every target immediately before patching; preserve unrelated hunks and stop on overlap.
- [Published SpiceDB behavior evolves] → Pin the image tag and digest and require deliberate fixture regeneration when advancing versions.

## Migration Plan

1. Verify the Dafny semantic model, executable witnesses, mutation controls, and assurance metadata before runtime implementation.
2. Add failure-first reference and CLJC unit tests, then implement the isolated portable kernel and limits.
3. Wire shared orchestration and Datomic separately, running focused nREPL tests after each target.
4. Add version-pinned black-box SpiceDB fixtures, all-backend contracts, CLJS coverage, concurrency/deadline tests, and documentation.
5. Run the complete formal, static, CLJ, and CLJS gates and strict OpenSpec validation.

Rollback is a code-only revert restoring `:eacl/not-implemented`; no stored data, token, cursor, or cache migration is required.
