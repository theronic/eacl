# EACL backend modules

Authorization semantics and cache orchestration live in `modules/eacl`.
Database access, immutable snapshot selection, transactions, exact-snapshot
recovery, and cursor protection remain adapter responsibilities. Applications
depend on one adapter module; backend authors depend on core.

## Choose a module

| Module | Runtime | Consistency and snapshots | Derived schema | Cursors |
| --- | --- | --- | --- | --- |
| `eacl-datomic` | Clojure/JVM | current Peer DB, explicit sync barrier, causal floor, targeted catch-up plus exact `d/as-of T` | certified generation reuse | authenticated; proof-equivalent current continuation or full-history exact reconstruction |
| `eacl-datahike` | Clojure/JVM | current connection DB; durable temporal history when enabled, otherwise conditional retained-commit selection | certified generation reuse | authenticated; proof-equivalent current continuation or configuration-honest exact reconstruction |
| `eacl-datascript` | Clojure and ClojureScript | current connection DB; no arbitrary exact selection | certified generation reuse | authenticated proof-equivalent current continuation |
| `eacl-datalevin` | Clojure/JVM | fresh owned native read snapshot at the local embedded head; causal-floor polling; no historical exact selection | certified ordered-generation reuse | authenticated proof-equivalent current continuation |
| `eacl` | Clojure and ClojureScript | supplied by an adapter | flat bounded frequency/recency artifacts and request-local fallback | shared protocol, engine, proof, and cache implementation |

Capabilities are configuration-specific and are validated before
authorization. Ordinary calls select one current immutable snapshot and do not
perform historical selection.

All bundled adapters store and evaluate the same canonical permission
expressions, including union, intersection, and exclusion. Datahike additionally
implements the certified density-bounded direct-membership batch; Datomic,
DataScript, and Datalevin use their certified fallback paths. The shared engine
owns operator semantics, planning, cursor identity, caching, and limits. See
[Permission set algebra](permission-set-algebra.md).

`eacl-datalevin` is implemented but not yet published. It depends on the
explicit public read-snapshot API in the maintained
`dev.eacl/datalevin-embedded-eacl` fork. Neither coordinate may be treated as
released until a clean remote-only consumer resolves both from immutable
public SCM revisions.

## Consistency matrix

| Backend | Minimize latency | Fully consistent | At least as fresh | Exact snapshot | Certified schema generation | Ordered-generation cache proof |
| --- | --- | --- | --- | --- | --- | --- |
| Datomic Pro | current Peer value | bounded `d/sync` then current | targeted catch-up | `d/as-of T` | yes | yes |
| Datahike | current connection value | qualified writer head | revision/commit catch-up | durable with temporal history; otherwise retained-commit conditional | yes | yes |
| DataScript | serialized current value | serialized current head | waits for connection revision | unsupported | yes | yes, in the in-process mutation topology |
| Datalevin | fresh explicit native reader | qualified local embedded head | retries fresh readers to the authenticated revision floor | unsupported | yes, one snapshot-bound index probe | yes; storage-enforced scalar commit generations |

## Aggregate capability matrix

Batch evaluation and both authorized relationship-page routes live in shared
core; adapters do not implement private loops or page evaluators.

| Backend | `check-permissions` | Authorized scan route | Enumerate direct-match certificate | Request snapshot lifecycle |
| --- | --- | --- | --- | --- |
| Datomic Pro | yes | yes | yes | borrowed immutable Peer DB value |
| Datahike | yes | yes | yes | borrowed or configuration-qualified historical value |
| DataScript | yes, CLJ/CLJS | yes, CLJ/CLJS | yes, CLJ/CLJS | borrowed serialized immutable DB value |
| Datalevin | yes | yes | yes, one snapshot-bound index probe | owned explicit reader, acquiring platform thread only, deterministic close |

Every adapter must certify that `:direct-match?` denotes membership of exactly
one `(subject, relation, resource)` triple on the selected snapshot. The shared
enumerate route reuses this certificate and performs one probe per authorized
candidate; it does not re-evaluate the permission. Every bundled adapter also
implements the independent one-probe `:schema-generation` operation. This is a
separate obligation from ordered-generation proof support.

Datalevin's fully-consistent mode is not a replica barrier. A fresh explicit
reader on the qualified local embedded environment is the local-head
linearization point. Remote, HA, WAL, virtual-thread, and unsafe LMDB profiles
are rejected during construction. Multiple connection atoms in one process
share the store and LMDB serializes commits. A writer from another process is
detected by persisted `max-tx` continuity; the stale connection aborts and is
write-unusable until reopened.

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
(datalevin/make-client conn {:cache cache/no-cache
                             ;; plus mandatory lifecycle, watermark,
                             ;; and signing options
                             })
```

Every bundled adapter also certifies EACL's schema generation independently
of ordered relationship-generation proofs. The selected adapter memoizes the
one-probe read. One flat bounded standard cache reuses validation catalogs, permission
paths, dependency closures, routing analysis, direct-grant relations, and
sealed plans by complete source/lifecycle/adapter/schema/artifact keys across
relationship-only revisions; it has no generation container or registry.
Datalevin also lifts completed answers when its complete scalar proof frame
remains equal across revisions. Shared denotations remain exact-basis only.
An uncertified third-party adapter receives the same reuse within one request
only; native revision is not used as a derived-state fallback.

Exact immutable-snapshot lookup is always first. After an exact miss, complete
proof-backed reuse across unrelated forward transactions is automatic when the
request is deterministic and the adapter supplies certified ordered
generations. The retained identity contains the source lifecycle, schema
generation, and scalar maximum generation over the complete relation
dependency closure.

Authenticated `at-exact-snapshot` requests and ordinary selected bases use the
same bounded exact-basis answer tier. Its key binds the complete source scope,
lifecycle, native revision and locator, basis kind, adapter/identity contract,
engine ABI, semantic request, result shape, demand, and limit identity.
Historical requests do not use managed proof lifting; public IDs, basis,
tokens, cursors, and metadata are rebuilt from the selected adapter on every
hit.

All authorization-relevant schema, relationship, identity/liveness, repair,
and safe-deletion mutations must use EACL APIs or documented EACL transaction
data/functions transacted intact. Datalevin additionally enforces this at its
datom transaction and administrative boundaries: unadmitted protected writes,
missing or stale stamps, and frozen-schema mutation abort atomically. Direct KV
writes, file modification, or opening the database with an upstream artifact
remain outside that boundary and can leave stale proof-backed state. Recovery
requires quiescing affected traffic, repairing data, expiring or recreating
every affected client in every process, and then resuming.
`prepare-cache-coherence!`, an identical `write-schema!`, and cache rotation
are not data repair.

The exact lifecycle functions are:

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

Datalevin deliberately has no process-local lifecycle rotation. A restore or
rollback must durably rotate the externally owned source lifecycle and reset
its external revision watermark before constructing a replacement client.
`eacl.datalevin.core/expire-cache!` rejects attempts to fake that operation in
memory.

See [cache operations](v8-consistency-cache-operations.md) for proof
availability, custom-codec, time-travel, and multi-process lifecycle rules.

## Exact history and cursor lifetime

Datomic treats a valid same-source exact `T` ahead of the local Peer as lag.
It waits boundedly on `(d/sync conn T)`, cancels the future on timeout or
interruption, verifies `basis >= T`, and evaluates only `(d/as-of db T)`. If
`T` is already local it skips synchronization. Ordinary Datomic history has no
EACL age-based exact/cursor expiry.

`eacl.datahike/create-conn` enables `:keep-history? true` by default. This
costs additional storage and write amplification but permits temporal exact
reconstruction after named commit records are collected. Explicit
`{:keep-history? false}` opts out: exact selection is then conditional on a
retained commit graph, and collected commits may become unavailable.

Cursors have no default TTL on any backend. A positive
`:cursor-ttl-seconds` is an application policy; cache/checkpoint eviction only
causes replay. Datomic excision and Datahike purge/cutoff, reset, branch force,
or history replacement require quiescence, completion, shared source-lifecycle
rotation, complete client/cache detachment, and deliberate signing-key/wire
version policy before traffic resumes.

Datalevin cursors are current-revision continuations. Because the adapter has
no historical selector, a continuation whose selected revision is no longer
the current semantic snapshot fails closed; it is not reconstructed from LMDB
history.

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
| Datalevin qualified embedded profile | call `eacl.datalevin.safe-retraction/prepare!`, then submit through `eacl.datalevin.safe-retraction/transact-retract-entity!` |
| Function-unsafe remote topology | use `delete-object!`, then native entity deletion |

Do not combine relationship additions involving a target with safe retraction
of that target in the same application transaction. Use batched
`delete-object!` for very high-degree targets and backend integrity reports for
damage whose former eid is unknown.

## Recursive permissions and safety controls

All adapters use the same stable-discovery engine: one sealed plan per
permission root and certified schema generation (or per request when the
generation is unavailable), and one width-one deterministic reducer that
admits each (node, entity) exactly once. Each client accepts positive
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

Every routed adapter read runs inside the engine's three-outcome
classification boundary: a foreign adapter failure (a storage or driver
exception) is classified `:retryable` and retried up to three times for the
same read-demand descriptor under the request's original absolute deadline,
then surfaces as `:eacl.scan/failure` with `:classification` and
`:cause-class`; typed EACL errors (contract violations, limits, deadlines,
cancellation) pass through unwrapped and unretried. Attempts are reported as
`:adapter-attempts` in the traversal work stats.

Each client also accepts a `:service-admission` bulkhead for the routed
enumerations (point checks, lookups, counts) and a replay ledger for cursor
replays; slots are held for the full synchronous call chain of the work:

```clojure
(datascript/make-client
 conn
 {:service-admission
  {:max-concurrent 64        ; enumerations holding a slot at once
   :max-replays 16           ; concurrent cursor replays in total
   :max-replays-per-key 2}}) ; concurrent replays of one continuation
```

Rejections are `:eacl.service/admission-rejected` and
`:eacl.service/replay-rejected`; an omitted option installs no bulkhead.
Client construction fails closed with `:eacl.topology/unqualified` when the
backend adapter's declared execution profile does not certify the strict,
unique, replayable, strict-progress, atomic scan contract over an immutable
basis that stable discovery requires (the four bundled adapters do within
their certified topologies).

## Permission-tree expansion

All bundled adapters implement `expand-permission-tree` through one portable
CLJ/CLJS kernel. The strict request is `{:resource object :permission keyword}`
plus optional `:consistency`, `:timeout-ms`, and `:cancellation-token`; the response is
`{:expanded-at token :tree-root node}`. The token and every definition,
relationship, and rendered ID in the tree come from one selected immutable
adapter. Datomic can replay the exact token throughout ordinary unreplaced
history. Datahike can do so durably with temporal history, or conditionally
while a named commit remains retained when temporal history is disabled;
DataScript supplies current/causal selection but no arbitrary historical
reconstruction.

The tree is a shallow structural explanation, not a flattened authorization
answer. It preserves union, intersection, directed exclusion, permission, and
arrow boundaries, empty branches, and duplicate multiplicity. Child/subject
order is deliberately unspecified except that exclusion retains left/right
operand order.
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
nodes, transaction behavior, cursor identity, the independent
`:schema-generation` operation, and optional ordered-generation proof
capability. A third-party adapter without certified proof support remains a
correct exact-basis adapter. Returning nil for schema generation also
disables cross-request derived-state reuse while preserving request-local
reuse.

Backend authors should follow the [adapter boundary
inventory](v8-backend-adapter-boundary.md) and run the shared public API,
recursive, aggregate, cache, mutation, and independent-oracle contracts.

Adapters over live or closeable database handles must also implement the
[basis-source lifecycle](v8-snapshot-provider-migration.md). A borrowed
provider is correct only for an already immutable value. An owned reader must
declare its thread/runtime restrictions, keep the handle inside the complete
request scope, eagerly realize all returned data, and release every accepted
and rejected candidate deterministically.

## Downstream adapter recut

The separate `eacl-spicedb` repository must be recut against this core before
it can claim v8 compatibility. Its reader boundary must implement or explicitly
reject the new `:schema-generation` and certified `:direct-match?` obligations,
wire `check-permissions` and both authorized pagination query shapes through
the shared contracts where its topology permits, adopt the current encrypted
cursor ABI, and pass the aggregate conformance suite. An older published
`eacl-spicedb` artifact is not source- or wire-compatible merely because scalar
operations still compile.

## Released v7 permission upgrade and unreleased-v8 reset

Ordinary v8 permission rows contain one canonical expression payload. There is
no flat-permission compatibility reader or dual-write representation. Datomic
accepts released v7 flat permission rows only through the explicit
`eacl.migrations.v7-to-v8/migrate!` maintenance path. The migration validates
the complete replacement and authoritative attribute meanings, rejects any
relation identity change, then atomically swaps permission rows and stamps
permission storage version 8. Released v7 relationship attributes and tuples
are reused without enumeration, backfill, rewrite, or rebuild. See
[Migrating Datomic permissions from v7 to v8](migration-v7-to-v8.md).

An ordinary v8 client fails closed on flat-only, mixed, duplicated,
conflicting, corrupt, or unsupported-format permission storage. The optional
`:auto-migrate-v7` client setting is explicit opt-in to the same migration,
not a compatibility reader.

If source control is rolled back across this expression-storage change,
dispose of and recreate development databases with the schema belonging to the
selected source revision. Do not open an expression-capable database with an
older binary. No compatibility is claimed for persisted cursors across that
rollback, and no migration or dual-write path is provided between superseded
unreleased-v8 representations. This reset contract is distinct from the
released v6-to-v7 relationship migration and released v7-to-v8 permission
migration utilities.
