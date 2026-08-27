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

The maintained Datalevin fork makes this mutation contract executable for its
datom transaction and administrative APIs. A persisted store policy guards and
freezes every physical EACL attribute except application identity, verifies
schema and relation stamps after transaction-function and `retractEntity`
expansion, materializes each stamp from the committing `max-tx`, and requires a
per-open token held by the EACL writer. Consequently an unadmitted protected
write, including direct retraction of an entity that owns relationship tuples,
aborts instead of silently invalidating proof. Use `delete-object!` or
`eacl.datalevin.safe-retraction/transact-retract-entity!` for those deletions.
Raw KV writes, direct file modification, and opening the directory with an
upstream artifact that lacks the policy remain outside the trusted boundary.

Caching does not alter results:

- `eacl.cache/no-cache` disables it for a client;
- `:cache? false` bypasses lookup and publication for one operation;
- `:populate-cache? false` keeps lookup and request-local memoization enabled
  while suppressing completed-answer, managed-subproblem, checkpoint, and
  visited-page publication for one operation;
- failed, timed-out, partial, malformed, or unproved work is never published
  as a completed result; and
- cache data is never written to the application database.

## Cache layers

| Layer | Reuse scope | Purpose |
| --- | --- | --- |
| Exact completed answer | Same semantic operation and complete immutable basis identity | Skips the complete operation with no proof read; ordinary and retained historical generations share one bounded composite-key tier |
| Proof-backed completed answer | Same semantic operation, lineage, schema generation, and dependency frontier | Reuses across either revision direction when the selected basis has a readable complete frame |
| Identity projection | Same backend, identity contract, and internal id | Shares `internal-id->object` renderings while a page is externalized |
| Sealed plan | Same source scope, lifecycle, schema generation, and permission root | Reuses the compiled stable-discovery plan across requests and unrelated transactions; `expire-cache!` drops it |
| Schema-derived generation | Same engine ABI, adapter/source scope, lifecycle, and certified schema generation | Shares parsed validation catalogs, permission roots and paths, dependency closures, routing analysis, direct-grant relations, cycle guards, and sealed plans |
| Latest checkpoint | One client, query, lineage, complete plan frame, plan fingerprint, traversal, anchor, page size, and authenticated boundary | Resumes history-free reducer state plus its lookahead on the same or an equal-frame basis; native revision is not part of the key |
| Visited page | One authenticated query and exact immutable basis | Reuses an already-externalized page (and learns the adjacent opposite-direction page); external identity rendering is not covered by frame equality |

Completed-answer keys include the normalized operation, principal, permission,
query, bounds, evaluation mode, and result shape. Public IDs and metadata are
rendered from the selected database after an internal result is resolved.
Partially processed worklists and incomplete pages are not completed answers.

Aggregate batch, scan-route, and enumerate-route results use these same layers;
they do not have a weaker side cache. The exact key binds the operation and
complete normalized aggregate shape, including authorization or direct-
relationship clauses, page direction/demand, candidate window, and selected
snapshot. Proof-backed aggregate reuse additionally binds every direct
relationship dependency used by the filter. A request-local repeated decision
may remove work inside one aggregate but is not reported as a durable
`:cached? true` result. Datalevin applies the same exact-first, proof-backed
aggregate rules as the other ordered-generation adapters: an unrelated commit
may reuse a complete aggregate page, while a changed dependency frame misses.

## Certified schema generation

Every bundled adapter implements the independent `:schema-generation`
operation. It reads EACL's transactionally maintained schema stamp with at
most one index probe, and the selected adapter memoizes that result. A managed
schema write advances the stamp; a relationship-only or unrelated write does
not. Datalevin reads this scalar stamp and each requested scalar relation stamp
from the same owned immutable reader used by the authorization request.

A certified stamp selects one bounded, client-owned derived generation. Its
key contains the engine ABI, backend and adapter identity, source scope,
lifecycle, and schema generation—never the native database revision. All
pure schema artifacts, including sealed plans and validation catalogs, live
inside that generation. A relationship write can therefore advance the
native revision without causing definition reads or plan sealing on the next
request.

If an adapter cannot certify a schema generation, EACL creates the same
derived slots in a request-local floor and discards them when the request
ends. It never falls back to native revision keying and has no process-global
sealed-plan FIFO. `expire-cache!` clears the client's generation registry;
ordinary relationship writes leave it intact.

## Exact-first lookup

For a completed operation on an admitted basis EACL resolves:

1. an exact answer for the selected immutable database value;
2. a proof-backed answer when complete proof is available;
3. engine evaluation, optionally using safe cached subproblems; and
4. publication into every eligible exact and proof-backed tier.

An exact hit performs no ordered-generation proof reads and no schema reads;
the independent schema-generation operation is not forced on that path.
Request validation runs on the miss path against the schema parsed once per schema
generation (a hit implies the request validated under an equal generation;
an unstamped database validates against a direct read). A proof-backed hit is
promoted into the exact store for the selected value, so the next identical
request on that value is exact.

Ordinary and authenticated historical selections share one bounded exact-basis
tier. Its composite identity includes backend and source scope, configured
lifecycle, native revision and exact locator, basis kind, adapter fingerprint
and identity contract, engine/order ABI, normalized semantic request, result
kind, demand, and every answer-affecting limit. Equal numeric revisions alone
are insufficient. Each retained basis owns its exact answers and subproblem
store. A historical miss evaluates on the already selected immutable adapter
and may probe managed proof-backed entries when that historical value can read
a complete contract-valid frame in the native revision domain. An unreadable
historical frame remains exact-only. Public tokens, cursor envelopes, cache
basis, external IDs, and selected-basis metadata are rebuilt on every hit.

Caller-constructed database values are not accepted as source bases and cannot
enter the completed-answer cache. Basis admission requires the source adapter's
complete semantic identity; a missing lifecycle, revision, locator, basis kind,
adapter fingerprint, identity contract, or ABI component fails before lookup or
publication.

## Automatic proof-backed coherence

Every deterministic cacheable request on an admissible basis is automatically
eligible after its exact miss when its complete frame is readable.

Lineage is the complete source scope paired with the operator lifecycle:

```clojure
{:source-scope {:backend backend :source-id source-id :branch branch}
 :source-lifecycle lifecycle}
```

For any two selected values in one lineage, a reusable completed answer must
have equal:

- lineage;
- normalized semantic operation and result shape;
- schema assertion generation; and
- scalar dependency frontier.

Revision order is not a reuse predicate. The formal history orders values to
reason about intervening commits, but its equality conclusion is symmetric.
An older retained basis can therefore reuse a newer answer, and a newer basis
can reuse an older answer, when their lineage and complete proof are equal.
`EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` states the older-selected
case explicitly.

Durable backends persist source identity across reopen. Non-durable sources
mint one fresh identity per live source—DataScript per connection, Datahike
memory stores even when the caller supplies a fixed store id, and Datomic
`mem` databases through their generated database id. A configuration label is
never accepted as lineage for a recreated non-durable source.

The dependency set is the complete canonical set of relationship relations
that can affect the normalized request under the selected schema. Its frontier
is the maximum stored native transaction generation over that set, or `0` for
an empty set. The constant-size cache descriptor is therefore:

```clojure
{:schema-generation schema-generation
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

Each request owns one lazy proof frame bound to its exact adapter, lineage, and
immutable database value. Equal dependency closures share their resolved
evidence. The adapter's `:proof-frame` operation returns only the canonical
vector `[[relation-id generation] ...]`; the independent certified
`:schema-generation` operation supplies the schema component. Core requires
the selected revision, schema generation, and every relation generation to be
portable non-negative exact integers in one domain, and requires schema and
relation generations to be at or below the selected revision. It then derives
the scalar frontier and can derive subset frontiers only from relations already
in the proved closure. The frame never combines evidence from another adapter,
lineage, or snapshot.

Proof is unavailable when:

- the adapter does not advertise certified ordered generations;
- schema or relation generations are absent;
- dependency extraction is incomplete or non-canonical;
- the complete closure exceeds 4,096 relations, or a managed subproblem
  exceeds its configured `:managed-proof-max-atoms` bound;
- the provider throws;
- the selected value cannot read the historical generations it names;
- the request uses a filtered, speculative, or caller-constructed value;
- caching is disabled, the response is incomplete, or the operation is not
  deterministic; or
- a custom identity codec lacks its stable deterministic contract.

An unavailable proof is exact-only for that request. It is not an availability
or authorization error and never uses partial evidence or substitutes an
initial generation. A complete changed proof is a normal managed miss, not
proof unavailability. `cache-stats` reports `:proof-unavailable` and
`:proof-unavailable-reasons`.

Malformed shape, wrong cardinality, duplicate or non-canonical relation ids,
non-integer generations, and generations above the selected revision are
adapter contract violations, not ordinary unavailability. The request still
evaluates authoritatively on its exact selected basis; exact caching and token
issuance continue. The client atomically disables managed lifting until
`expire-cache!`. `cache-stats` exposes the sticky flag and violation counts by
reason. An optional `:proof-contract-reporter` runs once per reason per
lifecycle. Cursor validation treats violated evidence as unavailable and
therefore uses exact fallback or returns a typed stale outcome; it never treats
two violations as proof equality.

## Custom identity codecs

Built-in `:eacl/id` conversion is deterministic and proof-eligible. A custom
`:entid->object-id`/`:object-id->lookup-ref` codec receives an opaque
client-local fingerprint and exact caching by default. It gains cross-snapshot
proof-backed completed-answer reuse only when the client supplies both:

```clojure
{:adapter-fingerprint [:my-app/id-codec 1]
 :adapter-deterministic? true}
```

The application must certify that the codec is deterministic, injective, and
round-trips every permissioned identity. Proof-equivalent cursor continuation
has the stronger requirement that one internal object's public identity never
changes within a source lineage; custom codecs must additionally set
`:identity-immutable? true`. Without that explicit immutability contract,
cursors remain exact-basis-bound even when managed completed answers are
enabled. Processes that exchange cursors must use the same portable
fingerprint, codec, and identity contract.

The built-in codec defaults to `:identity-immutable? true`. This is a supported
writer premise, not a property enforced by Datomic, Datahike, or DataScript's
physical schema: applications MUST treat `:eacl/id` as immutable for an
entity's lifetime. Set `:identity-immutable? false` when that premise does not
hold; EACL then rejects cross-basis cursors instead of risking a hybrid public
stream after an ID reassignment.

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

Read existing cache state without publishing cross-request state:

```clojure
(eacl/check-permission
 acl
 {:subject subject
  :permission :view
  :resource resource
  :populate-cache? false})
```

Every cache-capable public read accepts this option, including batch, count,
relationship, permission-tree, and paginated operations. It is excluded from
cache, cursor, and continuation identities. With `:cache? false` it is accepted
but irrelevant because lookup and publication are both bypassed.

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

Cursors authenticate the operation, normalized query, engine and ordering ABI,
adapter and identity contracts, native revision and exact locator, boundary,
and one continuation context: `lineage`, `frame`, and `closure-digest`.
Lineage is the source scope plus source lifecycle. The frame is the certified
schema generation plus the scalar frontier over the complete canonical
relation closure; the closure itself is represented by a domain-separated
digest. Cursor validation, answer lookup, checkpoint lookup, and cursor
re-minting consume the same request-owned frame, so each relation generation
is read at most once per closure in one request.

A later basis may continue the boundary only inside the same lineage with an
equal frame and closure digest. Transactions outside the closure therefore do
not invalidate forward or reverse pagination. A schema write or mutation of a
relation inside the closure changes the frame and can never continue on the
changed basis. The sealed-plan read-scope guard rejects a compiled reducer that
could scan a relation outside that closure.

After a changed or unavailable frame, an `acl` may select the cursor's original
immutable basis only when its source advertises exact selection and the
request's freshness floor permits it. Acceptance then compares authenticated
source scope, lifecycle, revision, and exact locator; it does not read a proof
frame from the historical value. Datomic and appropriately configured
Datahike sources provide this fallback. DataScript and Datalevin are
current-only and return `:eacl.pagination/stale-cursor` with reason
`:frame-changed` when the current frame differs. No backend emulates history
with a hidden retained-value registry.

Cursor lifetime follows source identity, not process lifetime. Reopening the
same durable Datomic database, durable Datahike store, or Datalevin store keeps
the lineage and accepts an equal-frame cursor. Recreating a DataScript
connection, an in-memory Datahike store, or an independent Datomic memory
database mints a fresh live-source id; an old cursor is rejected with
`:source-scope` before any frame read, even with identical data, the default
constant lifecycle, and shared token keys. Restore, reset, purge, excision, or
branch replacement requires lifecycle rotation.

Cursors carry no expiry unless a positive `:cursor-ttl-seconds` is configured.
Cache TTL, answer eviction, page-navigation eviction, and checkpoint eviction
do not limit cursor age; they only cause deterministic replay. An old cursor
continues the original historical enumeration. Consumers that require current
authorization at object-consumption time must make a separate current check.
`:populate-cache? false` is excluded from cursor identity and does not change
validation or page contents; it suppresses checkpoint and visited-page
publication for that request.

Datalevin also exposes `eacl.datalevin.core/clear-answer-cache!` for an
operational answer-cache clear that preserves its persisted source lifecycle
and certified schema-derived plans. This is useful for miss-path measurement
and bounded-cache administration; it is deliberately weaker than lifecycle
expiry and is not valid recovery after restore, rollback, or unsupported
mutation.

An admissible `as-of` value can use proof-backed reuse when it can read the
schema and relation generations visible at that value and they pass the same
domain and ceiling checks. Datomic retains relation-version history for this
purpose; Datahike requires readable retained history. `since`, filtered,
speculative, and caller-constructed values remain outside managed reuse. Exact
historical evaluation is always authoritative.

## Metrics and evidence

Each backend exposes `cache-stats`, including exact/proof-backed hits, misses,
bypasses, proof-unavailable reasons, sticky proof-contract violations, puts,
expirations, admission rejections, evictions, live weights, and avoided backend
work. Its `:continuations` section reports checkpoint hits, publications,
replacements, occupancy, and miss reasons (`:absent`, `:evicted`,
`:boundary-mismatch`, `:overweight`, `:plan-mismatch`, and
`:population-disabled`). Lookup and count responses
also expose `:cached?` and `:cache-basis`; `can?` returns only a Boolean.

The cache-free evaluator is the behavioral oracle. Differential and randomized
tests compare cached and bypassed results across all bundled backends. Dafny
proves the scalar-frontier theorem and distinguishes exact-basis and managed
cache decisions under the documented adapter
obligations. Backend I/O effects, temporal-history retention, future
cancellation, and canonical-key truthfulness are certified adapter assumptions,
not kernel theorems. Backend certification and real-store regressions establish
the executable trusted boundary. See [formal verification](formal-verification.md) and the
[scalar-frontier measurements](benchmarks/results/2026-08-11-scalar-frontier-coherence.md).
