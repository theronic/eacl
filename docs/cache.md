# EACL cache

EACL's cache is a bounded, client-private optimization. The selected immutable
database value and the cache-free evaluator remain authoritative: a miss,
rejected entry, unavailable proof, eviction, or disabled cache recomputes the
operation and cannot turn a deny into an allow.

## Consumer contract

One public operation selects one immutable database value. Schema resolution,
normalization, traversal, proof acquisition, result rendering, and cursor
construction all use that selected value. A long-running request may continue
against its older selected value while later transactions commit.

EACL guarantees cache coherence only when authorization-relevant mutations use
supported EACL paths:

- schema changes use `eacl/write-schema!`;
- relationship additions, deletions, repairs, and object cleanup use EACL APIs
  or EACL-produced transaction data transacted intact; and
- permissioned identity/liveness and entity deletion use documented EACL
  cleanup or `:eacl.fn/retractEntity` paths.

Unrelated application datoms are outside this requirement. Splitting EACL
transaction data or directly changing authorization schema, relationship
tuples, permissioned identity, or entity liveness is unsupported and can leave
a managed entry stale.

Caching does not alter results:

- `eacl.cache/no-cache` disables it for a client;
- `:cache? false` bypasses lookup and publication for one operation;
- failed, timed-out, partial, malformed, or unproved work is never published
  as a completed result; and
- cache data is never written to the application database.

## Cache layers

| Layer | Reuse scope | Purpose |
| --- | --- | --- |
| Exact completed answer | Same semantic operation and canonical ordinary immutable snapshot | Skips the complete operation with no proof read; retained historical generations share one bounded composite-key tier |
| Proof-backed completed answer | Same semantic operation, lifecycle, schema generation, and dependency frontier | Survives unrelated forward transactions |
| Identity projection | Same backend, identity contract, and internal id | Shares `internal-id->object` renderings while a page is externalized |
| Sealed plan | Same source scope, lifecycle, schema generation, and permission root | Reuses the compiled stable-discovery plan across requests and unrelated transactions; `expire-cache!` drops it |
| Schema paths | Same schema generation | Shares permission-path and dependency-closure derivations used for cache dependencies and cursor proofs |
| Latest checkpoint | One authenticated query, exact snapshot, page size, and boundary | Resumes a continued page from the retained engine state plus its lookahead segment without publishing incomplete traversal as an answer |
| Visited page | One authenticated query and immutable snapshot | Reuses an already-externalized page (and learns the adjacent opposite-direction page) |

Completed-answer keys include the normalized operation, principal, permission,
query, bounds, evaluation mode, and result shape. Public IDs and metadata are
rendered from the selected database after an internal result is resolved.
Partially processed worklists and incomplete pages are not completed answers.

## Exact-first lookup

For an ordinary current completed operation EACL resolves:

1. an exact answer for the selected immutable database value;
2. a proof-backed answer when complete proof is available;
3. engine evaluation, optionally using safe cached subproblems; and
4. publication into every eligible exact and proof-backed tier.

An exact hit performs no generation proof reads and no schema reads: request
validation runs on the miss path against the schema parsed once per schema
generation (a hit implies the request validated under an equal generation;
an unstamped database validates against a direct read). A proof-backed hit is
promoted into the exact store for the selected value, so the next identical
request on that value is exact.

After successful authenticated `at-exact-snapshot` selection, EACL probes only
the snapshot-exact completed-answer tier. Its composite identity includes the
backend/source/branch and configured lifecycle, native revision and exact
locator, ordinary exact-view kind, adapter fingerprint and identity contract,
engine/order ABI, normalized semantic request, result kind, demand, and every
answer-affecting limit. Equal numeric revisions alone are insufficient. A miss
evaluates on the already selected immutable adapter and may publish only the
completed answer; it never probes or publishes managed proof-backed entries or
partial traversal state.

Current requests may seed the same tier only when their adapter certifies that
the native locator is independently exact-selectable. Filtered, `since`,
history, speculative, caller-constructed, and current-only DataScript values
cannot acquire an ordinary snapshot-exact identity. Public tokens, cursor
envelopes, cache basis, external IDs, and selected-snapshot metadata are rebuilt
on every hit.

## Automatic proof-backed coherence

Every deterministic, cacheable, ordinary current request is automatically
eligible after its exact miss.

For selected snapshots `S <= T`, a reusable completed answer must have equal:

- adapter/source lifecycle;
- normalized semantic operation and result shape;
- schema assertion generation; and
- scalar dependency frontier.

The dependency set is the complete canonical set of relationship relations
that can affect the normalized request under the selected schema. Its frontier
is the maximum stored native transaction generation over that set, or `0` for
an empty set. The constant-size cache descriptor is therefore:

```clojure
{:schema-stamp schema-generation
 :dependency-stamp maximum-dependency-generation}
```

The scalar maximum is sound because every supported mutation commits its tuple
changes and stamps every affected relation atomically with the same native
transaction generation, and that generation is later than every relation
generation visible before the commit. If a relevant relation changed after
`S`, its first mutation must make the frontier at `T` greater than the frontier
at `S`. An unrelated transaction changes neither the dependency slices nor the
frontier. A schema change advances the schema generation.

The proof would not be sound under independently monotone relation counters:
`{A 10, B 5}` could become `{A 10, B 7}` without changing the maximum. The
bundled adapters instead use globally ordered native committed transactions.
This backend ordering and atomic-stamping behavior is certified by adapter
tests; the database engines themselves are part of the trusted boundary.

No listener, wall clock, TTL, transaction-log scan, relationship-content scan,
mutation journal, graph head, or database-global cache CAS is validity
evidence. Relation-local commit guards may retry competing writers to the same
relation; unrelated relations share no EACL coordination point.

## Proof frame and unavailability

Each request owns one lazy proof frame bound to its exact adapter, source
lifecycle, and immutable database value. Equal dependency closures share their
resolved evidence. The frame validates the schema generation and the complete
canonical `[relation-id generation]` set, derives the scalar frontier, and can
derive subset frontiers only from relations already in the proved closure. It
never combines evidence from another adapter, lifecycle, or snapshot.

Proof is unavailable when:

- the adapter does not advertise certified ordered generations;
- schema or relation generations are missing, malformed, partial,
  duplicated, or non-canonical;
- dependency extraction is incomplete or non-canonical;
- the complete closure exceeds 4,096 relations, or a managed subproblem
  exceeds its configured `:managed-proof-max-atoms` bound;
- the provider throws;
- the request uses an arbitrary historical, filtered, speculative, or
  caller-constructed database value;
- caching is disabled, the response is incomplete, or the operation is not
  deterministic; or
- a custom identity codec lacks its stable deterministic contract.

An unavailable proof is exact-only for that request. It is not an availability
or authorization error and never uses partial evidence or substitutes an
initial generation. A complete changed proof is a normal managed miss, not
proof unavailability. `cache-stats` reports `:proof-unavailable` and
`:proof-unavailable-reasons`.

## Custom identity codecs

Built-in `:eacl/id` conversion is deterministic and proof-eligible. A custom
`:entid->object-id`/`:object-id->lookup-ref` codec receives an opaque
client-local fingerprint and exact caching by default. It gains cross-snapshot
proof-backed reuse only when the client supplies both:

```clojure
{:adapter-fingerprint [:my-app/id-codec 1]
 :adapter-deterministic? true}
```

The application must certify that the codec is deterministic, injective, and
round-trips every permissioned identity. Processes that exchange cursors must
use the same portable fingerprint and codec. Without the explicit contract,
another client rejects the cursor even when token keys and source lifecycle
match.

## Capacity, concurrency, and configuration

Completed answers and identity projections have separate weighted
least-recently-used budgets (the `:denotation` tier budget is still accepted
by the store configuration but no engine path publishes into it). A value
heavier than its tier's admission ceiling is rejected rather than displacing
the tier. `:max-entries` bounds the
second-sighting window and client-private continuation/navigation stores; the
answer weight budget bounds completed answers.

Identical concurrent misses compute independently. Requests never wait on an
EACL cache semaphore or inherit another request's failure. Completed results
race bounded best-effort publication. Late publication from an expired
lifecycle is unreachable from the replacement lifecycle.

Historical exact entries share the answer tier's weight/LRU/admission bounds;
retaining more immutable generations does not make memory unbounded. Eviction
causes exact recomputation and is never interpreted as token or cursor expiry.

Typical configuration:

```clojure
(def acl
  (eacl.datomic.core/make-client
   conn
   {:cache
    {:max-entries 4096
     :subproblem-cache
     {:enabled? true
      :projection-max-weight (* 8 1024 1024)
      :denotation-max-weight (* 8 1024 1024)
      :answer-max-weight (* 16 1024 1024)
      :managed-proof-max-atoms 256}}}))
```

Datomic accepts `{:cache {:remember-answers :on-repeat}}`; Datahike and
DataScript accept `{:cache {:admit-on-repeat? true}}` for second-sighting
completed-answer admission.

Disable all answer caching:

```clojure
(require '[eacl.cache :as cache])

(eacl.datomic.core/make-client conn {:cache cache/no-cache})
(eacl.datahike.core/make-client conn {:cache cache/no-cache})
(eacl.datascript.core/make-client conn {:cache cache/no-cache})
```

Bypass one call:

```clojure
(eacl/check-permission
 acl
 {:subject subject :permission :view :resource resource :cache? false})
```

Use the bypass as a semantic oracle and measure representative workloads before
tuning for latency.

## Recovery and lifecycle expiry

Ordinary supported forward transactions require no manual expiry. After an
unsupported authorization mutation:

1. quiesce or drain affected authorization traffic in every process;
2. repair invalid tuples, schema, identity, or liveness through a supported
   path;
3. expire or recreate every affected client in every process; and
4. resume only after repair and rotation finish.

Use the exact backend call:

```clojure
(eacl.datomic.core/expire-cache! acl)
(eacl.datahike.core/expire-cache! acl)
(eacl.datascript.core/expire-cache! acl)
```

When several processes exchange cursors or revision tokens, generate one new
bounded lifecycle value and pass it as the second argument to every call.
Expiry swaps exact, proof-backed, subproblem, schema-plan, cursor,
continuation, navigation, and checkpoint state.

`prepare-cache-coherence!` initializes missing generation state but cannot
discover an old unstamped mutation. An identical `write-schema!` can be a
database no-op. Neither is a flush. Cache expiry also does not repair a ghost
relationship; use safe retraction, `delete-object!`, or the backend integrity
tools first.

Rotate lifecycle state after reset, restore, branch replacement, or any event
that can reuse or regress native revisions. Equal revision numbers from
different source histories are not comparable.

Treat Datomic excision and Datahike purge/cutoff, branch force, reset, or other
destructive history operations as explicit lifecycle replacement. Quiesce
affected authorization traffic, complete the destructive operation, rotate
the shared lifecycle and all clients/caches, deliberately retire or retain the
appropriate signing keys and wire versions, then resume. An unchanged
lifecycle provides no safety across concurrent history destruction.

## Cursors and time travel

Cursors are authenticated and scoped to operation, normalized query, adapter,
source lifecycle, ordering, and snapshot/proof identity. A proof-equivalent
current value may continue a walk. Otherwise, a history-capable backend may
reconstruct the authenticated exact snapshot; if it cannot, the cursor fails
closed. DataScript does not emulate history with hidden retained database
values.

Cursors carry no expiry unless a positive `:cursor-ttl-seconds` is configured.
Cache TTL, answer eviction, page-navigation eviction, and checkpoint eviction
do not limit cursor age; they only cause deterministic replay. An old cursor
continues the original historical enumeration. Consumers that require current
authorization at object-consumption time must make a separate current check.

EACL does not promise proof-backed cache availability for `as-of`, `since`,
filtered, speculative, or caller-constructed database values. Exact historical
evaluation remains authoritative. Reusing older cache segments for arbitrary
time travel is an optional optimization, not part of the coherence contract.

## Metrics and evidence

Each backend exposes `cache-stats`, including exact/proof-backed hits, misses,
bypasses, proof-unavailable reasons, puts, expirations, admission rejections,
evictions, live weights, and avoided backend work. Lookup and count responses
also expose `:cached?` and `:cache-basis`; `can?` returns only a Boolean.

The cache-free evaluator is the behavioral oracle. Differential and randomized
tests compare cached and bypassed results across all bundled backends. Dafny
proves the scalar-frontier theorem and distinguishes current-exact,
snapshot-exact, and managed cache decisions under the documented adapter
obligations. Backend I/O effects, temporal-history retention, future
cancellation, and canonical-key truthfulness are certified adapter assumptions,
not kernel theorems. Backend certification and real-store regressions establish
the executable trusted boundary. See [formal verification](formal-verification.md) and the
[scalar-frontier measurements](benchmarks/results/2026-08-11-scalar-frontier-coherence.md).
