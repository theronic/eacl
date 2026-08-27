# Design: Isolate public snapshots and add cache-safe speculative `with`

## Context

See `proposal.md` for motivation. The critical Datomic constraint is that a
consumer cannot inspect a `datomic.Database` value and reliably decide whether
it came from committed history or `d/with`. A speculative value can share
database identity, basis `t`, and `:db/txInstant` with different committed
content. Neither `basis-t` nor transaction instant is a content witness.

EACL v8 already relies on immutable selected snapshots and proof-validated
cache reuse. That design remains sound for committed history, but only if raw
caller database values cannot cross the public snapshot boundary and values
computed from explicit speculation are never published.

Constraints:

- EACL may use native snapshot and basis operations, including `d/basis-t`, but
  cache coherence must not use `d/log`, `d/tx-range`, log draining, or
  transaction listeners.
- `:eacl.relation/version` remains the committed relationship watermark.
- Public what-if testing must support chaining, relationship transaction data,
  transaction-function effects, and prospective schema changes.
- Calling EACL implementation namespaces is an unsupported coherence bypass.

## Goals / Non-Goals

**Goals**

- Make provenance structural: public values are a client or an EACL-created
  snapshot.
- Support immutable, composable `eacl/with` and `eacl/with-schema` snapshots.
- Reuse only committed cache proofs whose complete dependencies are unaffected.
- Publish nothing derived from a speculative snapshot.
- Share schema replacement planning between committed and speculative paths.
- Offer a speculative-only way to retain large orphaned relationship sets as
  inert data.

**Non-Goals**

- Detecting speculation by inspecting an arbitrary native database value.
- Hashing Datomic database content.
- Making immutable snapshots into transaction connections.
- Caching speculative results, even in snapshot-private overlay caches.
- Automatically promoting speculative work after a real commit.
- Supporting raw EACL schema-storage mutation through generic `eacl/with`.
- Providing coherence to callers that use EACL internals.

## Decisions

### D1. The public boundary owns database provenance

Public readers accept an EACL client or `IAuthorizationSnapshot`. Public
snapshot capture remains:

```clojure
(eacl/snapshot acl)
(eacl/snapshot acl consistency)
```

Backend functions such as `(datomic/snapshot acl db)` that wrap a caller
database value are removed from public namespaces. There is no public
`:speculative?`, `:ordinary?`, or `:populate-cache?` assertion because EACL
cannot verify a caller's claim.

An ordinary snapshot originates from client connect/warm, committed refresh,
consistency selection, or committed historical selection. A speculative
snapshot originates only from `eacl/with` or `eacl/with-schema`. The snapshot
record carries its kind internally; this is trusted provenance from the call
path, not inference from the native value.

Alternative rejected: admit raw values after comparing `basis-t`,
`:db/txInstant`, class, database id, or the latest connection basis. All can be
equal for different speculative and committed content or fail for legitimate
historical views.

### D2. Consistency selection is a client operation

| Consistency | Committed snapshot selection | Required store I/O |
|---|---|---|
| `minimize-latency` | Reuse client pin | None |
| `at-least-as-fresh` | Reuse pin if it satisfies `T`, otherwise refresh | Only when stale |
| `fully-consistent` / refresh | Replace pin from committed head | Allowed |
| `at-exact-snapshot` | Select committed history from token `T` | Backend-defined |

Evaluating an EACL snapshot never refreshes or replaces it. This is especially
important for remote Datahike stores, where `minimize-latency` must not turn
every request into a branch-head GET.

### D3. Generic `eacl/with` applies once and certifies observed effects

```clojure
(eacl/with acl tx-data)  ; => speculative snapshot
(eacl/with snap tx-data) ; => chained speculative snapshot
```

The adapter operation returns the native transaction report, not merely
`db-after`. The shared orchestration performs:

1. Select the parent's immutable database and committed root.
2. Reject transaction forms that directly target EACL schema storage.
3. Apply `tx-data` with the backend's native in-memory transaction operation.
4. Inspect the report's actual emitted datoms to classify EACL relationship,
   identity, existence, ordering, and other proof-relevant effects. This sees
   effects expanded by transaction functions.
5. Resolve changed relations to stable semantic coordinates, such as resource
   type plus relation name, using `db-before` for retractions and `db-after`
   for additions. Native relation eids alone are insufficient across schema
   removal and recreation.
6. Union the resulting effect certificate with the parent's cumulative
   certificate and return an immutable speculative snapshot.

If an adapter cannot completely classify an answer-affecting dimension, that
dimension is `::unknown`. Unknown disables committed cache reuse for operations
requiring it but does not prevent correct cache-free evaluation. Unclassified
application datoms therefore disable reuse.

Alternative rejected: derive effects only from input transaction forms. A
transaction function can emit relationship cleanup or other changes that are
not syntactically present in its invocation.

### D4. Public relationship transaction helpers preserve the writer contract

A public read-only `tx-relationship` planner accepts an EACL snapshot and a
relationship operation and returns backend transaction data. It uses the same
resolution, validation, paired-half mutation, endpoint/schema guards, and one
idempotent relation-version stamp per distinct relation as the committed
writer planner.

This gives consumers flexible transaction composition without exposing a raw
database constructor. The actual `eacl/with` report remains authoritative for
the speculative effect certificate. Stamps make the helper safe for documented
committed transaction composition but are not speculative content witnesses.

### D5. Speculative queries are read-through and never write-through

There is no `cache-with`, overlay id, or speculative delta `Δ`. A speculative
query context sets publication permission to false for every persistent cache
tier and derived memo that can outlive the operation. Request-local evaluation
state may be used during one operation and is discarded when the operation
finishes.

On lookup, the speculative context may consult the existing committed managed
cache. On a miss it evaluates against the speculative database and returns the
answer directly without publishing it. Repeating the operation may therefore
recompute. This is an intentional simplicity and safety trade-off suitable for
tests and demos.

Alternative rejected: snapshot-private speculative caches. They require a new
lifecycle and identity scheme, and a same-`t` sibling must never become
reachable through an accidentally shared tier. The performance gain is not
worth that complexity for prospective testing.

### D6. Committed proof reuse requires a complete disjointness certificate

For a speculative operation with dependency witness `D` and cumulative effect
certificate `A*`, a committed candidate is reusable only when:

```text
ordinary-valid-at-root?(candidate)
AND complete-proof?(candidate)
AND disjoint?(D.relationships, A*.relationships)
AND disjoint?(D.schema-components, A*.schema-components)
AND disjoint-or-proven-unchanged?(D.other, A*.other)
```

The ordinary validator first proves that the candidate is valid for the
committed root using source lifecycle, causal anchors, schema/relationship
proofs, semantic identity, identity/ordering proofs, and authentication as
already required by v8. Speculative disjointness is an additional condition.

The exact completed-answer tier is not consulted by native `(db-id, t)` for a
speculative snapshot. Exact keys, transaction instants, and speculative
relation-version values do not prove content. An entry lacking any dependency
dimension needed for disjointness is a miss.

Cumulative effects only grow. Even if a later speculative transaction restores
the root's visible data, EACL does not attempt semantic diff minimization.

### D7. Schema replacement has one pure planning path per adapter

Each supported adapter exposes a pure `plan-schema-replacement` operation
shared by its committed and speculative paths, shaped like:

```clojure
(plan-schema-replacement db schema options)
;; => {:speculative-tx-data       ...
;;     :changed-schema-components ...
;;     :affected-relationships    ...
;;     :removed-relations         ...
;;     :diagnostics               ...
;;     :no-op?                    ...}
```

The adapter planner owns parsing, normalization, reference validation,
expression limits, the empty-schema guard, native transaction data, and
concurrency-guard inputs. The shared backend-neutral semantic planner owns
orphan policy, semantic diff certification, and canonical stable schema
component identities.

`write-schema!` adds committed generation and concurrency guards and transacts
the plan. `with-schema` applies the plan through the native in-memory operation,
unions schema and relationship effects into the speculative certificate, and
never publishes parsed, normalized, compiled, planned, or evaluated prospective
schema material to a cache. Generic `eacl/with` rejects direct schema-storage
datoms because it cannot reconstruct this semantic plan safely.

### D8. Orphan policies differ only at the speculative boundary

`with-schema` supports:

```clojure
{:orphan-policy :error}         ; default
{:orphan-policy :retain-inert}  ; speculative only
```

`:error` runs the same relation-in-use preflight as `write-schema!` and emits
the same typed failure.

`:retain-inert` omits relation-unused preflight and transaction guards only
from the speculative plan. It does not enumerate, count, or retract the N
relationship tuples. Bounded indexed endpoint-presence probes per removed
relation build diagnostics. The snapshot exposes warnings through
`eacl/speculative-diagnostics`, containing stable relation identities and
presence, not exact counts.

Authorization compilation and relationship scans start from definitions in
the prospective schema. Consequently, tuple datoms referencing a removed
relation are physically retained but semantically inert and absent from public
`read-relationships`. This is distinct from a dangling relationship half: both
tuple halves may remain structurally intact while their schema definition is
absent.

If a later speculative schema restores an equivalent relation and the backend
storage representation reconnects it to the retained tuples, they may become
visible. This reactivation is intentional. Guaranteeing permanent masking
would require tuple-level masks or O(N) retraction and is outside
`:retain-inert`.

Committed `write-schema!` rejects `:retain-inert`; persisting schema-orphaned
tuples would weaken EACL's committed storage invariant and allow accidental
future resurrection.

### D9. Snapshots remain immutable readers

`IAuthorizationSnapshot` implements reader and lifecycle capabilities, not
`IAuthorizationWriter`. Passing a snapshot to `write-relationships!` or
`write-schema!` produces the existing typed unsupported-capability family,
specialized as an immutable-target diagnostic where practical.

Alternative deferred: a separate mutable speculative sandbox could implement
writer protocols, but it is unnecessary for the simple functional API and
would introduce synchronization and lifecycle semantics. It is not part of
this change.

### D10. No transaction-log coherence

Committed relation versions remain one idempotent native transaction value per
distinct changed relation. Schema generation remains the committed schema
watermark. Speculative effect discovery reads only the local in-memory
transaction report and schema plan. No path calls `d/log`, `d/tx-range`, drains
a transaction log, or depends on a listener for correctness.

## Risks / Trade-offs

- **A speculative cache miss recomputes on every operation.** → Retain safe
  committed proof reuse; prospective testing values correctness and simplicity
  over repeated-query speed.
- **Unclassified application datoms disable reuse.** → Continue evaluating
  correctly without persistent publication.
- **Effect extraction misses a transaction-function datom.** → Standardize the
  adapter report contract and fail the effect dimension to `::unknown` whenever
  complete extraction cannot be demonstrated.
- **Stable relation identity differs across backends.** → Compare canonical
  semantic coordinates in speculative certificates, retaining native eids only
  for backend access and committed stamps.
- **Retained inert data can reactivate.** → Make reactivation normative and
  expose diagnostics; use the default `:error` policy when that behavior is not
  desired.
- **Removing raw snapshot constructors breaks advanced callers.** → Document
  `eacl/with`, `with-schema`, and transaction helpers. Internal bypass remains
  possible but explicitly forfeits coherence guarantees.
- **Pinned clients may be stale under minimize-latency.** → This is the existing
  consistency contract; callers choose refresh or stronger consistency when
  freshness is required.

## Migration Plan

1. Remove public raw-database snapshot constructors and public database-target
   authorization arities; retain implementation access only as unsupported
   internals.
2. Add speculative snapshot provenance and cumulative effect certificates with
   publication disabled by construction.
3. Standardize adapter native-with reports and conservative effect extraction.
4. Add the public `tx-relationship` planner and composable `eacl/with`.
5. Extract pure schema replacement planning and add `eacl/with-schema` with
   `:error` and speculative-only `:retain-inert`.
6. Enable committed managed-proof read-through under complete disjointness.
7. Add collision, chaining, transaction-function, identity-effect, schema, and
   orphan-policy conformance tests across supported adapters.
8. Update cache, consistency, backend, and README documentation before release.

Rollback removes the new speculative entry points while retaining the safer
public raw-database prohibition and committed cache-coherence behavior.
