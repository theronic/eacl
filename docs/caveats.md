# Caveat and qualifier foundation

This foundation follows the v8 qualifier-eid storage change (storage ABI 9).
It persists named Caveat definitions and provides portable validation, partial
evaluation, an optional JVM evaluator, and explicitly staged qualifier writers.
Qualified authorization and expiration take effect only in the subsequent
Phase 3 serving change. A Phase 2 client rejects non-nil qualifier references;
never seed them into a serving Phase 2 database.

## Named definitions and schema admission

A definition is stored once and referenced by native entity ID:

```zed
caveat in_region(region string, accepted list<string>) {
  region in accepted
}

definition user {}
definition doc {
  relation viewer: user
  permission view = viewer
}
```

This complete schema is accepted by Phase 2 `write-schema!`; the unused named
Caveat does not affect ordinary authorization. The parser also understands
`relation viewer: user with in_region`, but public schema admission rejects
that branch until Phase 3. `user with in_region` requires the Caveat;
`user | user with in_region` permits either plain or Caveated input. An
expiry-only qualifier requires a plain alternative.

Names and typed parameter names use ASCII identifiers, up to 64 bytes. CEL
keywords/type names and the `__eacl_` prefix are reserved. Definitions have
unique names, at most 32 parameters, canonical typed parameter payloads,
LF-normalized expression source, and profile ID `eacl-cel/1`. Source spacing
and comments otherwise remain part of definition identity. A non-Boolean root,
unknown parameter, duplicate name, unresolved Caveat, unsupported overload or
operation, malformed source, or excessive bound fails before schema replacement.
Errors retain the Caveat name, source span and expression-local offset where
available.

Updating a definition retains its native entity ID and advances schema
generation. Removal is rejected while any retained qualifier references it,
including an unattached preparation. A parameter change must remain compatible
with every retained bound context. Native schema/reference fences reject
competing writes; historical schema reads retain the selected definition.

## Profile 1 values and operations

| Type | Portable Clojure/CLJS value |
| --- | --- |
| `bool` | `true` or `false` |
| `int` | Exact integer from -9007199254740991 through 9007199254740991 |
| `string` | Valid Unicode text; no unpaired surrogates or normalization |
| `timestamp` | `[:timestamp epoch-ms]`, UTC years 0001 through 9999 |
| `list<T>` | Vector of one scalar type |
| `map<string,T>` | String-keyed map of one scalar type |

Supported operations are `!`, `&&`, `||`, scalar `==`/`!=`, integer/timestamp
`<`/`<=`/`>`/`>=`, list/map membership with `in`, string-keyed map indexing
with `m[key]` or `m.member`, and string `contains`, `startsWith`, `endsWith`.
Source literals are Boolean, exact integer, and JSON-style double-quoted
strings. Grouping and `//` comments are supported. Relational operators follow
CEL's shared precedence; use parentheses when mixing comparisons.

Nested containers, source container literals, macros, arithmetic, regex,
conditional expressions, null, floats, unsigned integers, bytes, duration,
conversions, timestamp selectors, and string ordering/size are excluded.
Repeated ungrouped unary operators are rejected; `!(!a)` is supported.
These are explicit profile limits, not full CEL or SpiceDB compatibility.

| Bound | Maximum |
| --- | ---: |
| Source UTF-8 bytes / tokens / grouping depth | 8192 / 1024 / 32 |
| Plan nodes / depth | 256 / 32 |
| String UTF-8 bytes | 4096 |
| Entries in each list or map | 128 |
| Total context entries / canonical payload bytes | 1024 / 16384 |
| Conservative evaluation work units | 1048576 |
| Cached programs / simultaneous program builds | 256 / 4 |

Time values use exact epoch milliseconds from -62135596800000 through
253402300799999. Input, encoded payload and plan limits are checked before
expensive parsing or evaluation. Work preflight includes both logical branches;
an oversized branch is rejected even behind an absorbing `true` or `false`.
Limits bound admitted work and retained programs, not wall-clock latency or
all JVM heap allocations.

## Context, outcomes and implementation capability

Request and Relationship-bound contexts have declared string keys. Both maps
are independently validated, then bound values override request values. A bad
request value cannot be hidden by a valid bound value. Empty bound context is
omitted from storage. Canonical versioned payloads sort keys by Unicode scalar
order and tag values explicitly; they contain no parser or evaluator objects.

The portable evaluator returns:

```clojure
{:outcome :true}
{:outcome :false}
{:outcome :conditional :missing-fields #{"region"}
 :residual [:in [:param "region"] [:literal [:list :string] ["za"]]]}
{:outcome :error :reason :missing-map-key}
```

Missing parameters can yield a conditional result. A missing key in a supplied
map is a fault. Logical absorbers work on either side: `false && fault` is
false, and `fault || true` is true. Otherwise faults are preserved rather than
reported as missing input. Typed failures also cover invalid context, profile
or payload, unsupported operations/overloads, resource limits, non-Boolean
native results, evaluator exceptions, and interruption.

Use `dev.eacl/eacl-caveats-jvm` for the optional JVM implementation; requiring
`eacl.caveats.jvm` registers its process default. See the [module guide](../modules/eacl-caveats-jvm/README.md)
for an executable example. The adapter explicitly handles cel-parser's returned
error objects, lowers literals to bindings, and preserves operand faults through
its qualified overload/unary adapter. Complete contexts execute once through
CEL; incomplete contexts execute once through EACL's portable partial evaluator.
No runtime model, oracle comparison, or second decision is involved.

Core and DataScript CLJS contain the value/plan tools and evaluator protocol,
with no CEL or ANTLR dependency. An independently certified supplied evaluator
must advertise the exact profile fingerprint; absent or mismatched capability
fails admission. Registration alone does not activate serving. Implementation
fingerprints include pinned artifacts and adapter semantics. The bounded JVM
program cache coalesces construction, retains successes only, and includes the
canonical definition and fingerprint in its key. Request values are not cached.

## Sparse qualifier storage and staged publication

The fifth endpoint tuple component is a native qualifier entity ID or nil.
The owner entity and first four components remain Relationship identity.
A nonempty qualifier has the mandatory format marker plus only its present
Caveat reference, canonical bound-context payload, and/or `valid-until-ms`.
There is no public `:eacl/id`, owner sidecar, global generation, or expiry index.
Empty qualifiers normalize to nil and allocate nothing. Context without a
Caveat, unknown fields, unresolved refs, and malformed times are rejected.

A qualifier is immutable and singly owned. A semantic replacement allocates a
fresh qid and atomically replaces both halves, removes the old qualifier,
advances the Relation stamp, and commits caller-composed application datoms.
Plain-to-plain replacement retains the existing pair while still fencing,
stamping, and committing application datoms. Deletion removes both halves and
the owned qualifier. A missing non-nil target is corruption, never nil.

| Backend | Certified strategy | Snapshot evidence |
| --- | --- | --- |
| Datomic | Inline allocation | Native assertion transaction |
| DataScript JVM and CLJS | Prepared reference | Native assertion transaction |
| Datahike, including attribute refs | Prepared reference | Native assertion transaction |
| Datalevin maintained EACL fork | Inline allocation | Exact snapshot; no creation-version claim |

`eacl.<backend>.qualifiers/writer` constructs an internal staged writer.
`eacl.relationships.staged/prepare!` creates an inert qualifier and opaque handle
bound to one writer, source, Relationship identity, schema generation and exact
qualifier facts. `plan-current` returns composable fenced transaction data while
releasing its owned snapshot before commit. `write!` prepares automatically on
backends that require it. Caller datoms cannot bypass protected EACL attributes
or execute arbitrary transaction functions. These APIs are for non-serving
fixtures and staged preparation only in Phase 2.

An unattached preparation cannot authorize. Publishing it must use its concrete
native qid and atomically attach both halves. Native commit-time facts and
schema/Relation fences reject stale, mutated, reused or misdirected handles.
Datomic additionally serializes Caveat-reference creation through its existing
schema UUID fence. Datalevin planners use one native read snapshot and close it
before committing. Native transaction rejection rolls back the pair and caller
application datoms together.

## Integrity and cleanup

Backend integrity namespaces expose `qualifier-proof-input` and
`qualifier-report` for explicit offline inspection. Datalevin requires an owned
native read snapshot. Reports distinguish missing, shared, malformed, asymmetric
and observed-mutated qualifiers, duplicate Relationship identities, invalid
Relation proofs, and valid unattached preparations. Proof inputs include source,
schema generation, owning Relation stamps and available assertion versions.
Before/after comparison must use the same source. A single snapshot does not
prove historical immutability, especially on an exact-only backend.

`eacl.relationships.qualifier-integrity/repair-pair!` restores one missing
qualified endpoint half after validating the remaining half, qualifier, native
endpoints, Relation allowance and global ownership evidence. It preserves the
qid and qualifier facts, advances the Relation stamp, and requires an exact
native head guard. Other corruption must be resolved before this operation.
It never creates a qualifier or repairs data during a serving read.

`staged/cleanup!` removes an unchanged unattached preparation through its handle.
After restart, `eacl.relationships.qualifier-integrity/cleanup-orphans!` can
collect one bounded batch (default 100, maximum 1000). This is an explicit
whole-store offline scan with bounded report samples, not a bounded scan or an
authorization hot path. Corrupt data blocks collection. An exact native head
guard rejects any write after the scan, including a concurrent attachment;
retry by taking a fresh snapshot. Collection never supplies expiration semantics.

Install the additive Caveat and qualifier attributes through each backend's
explicit schema installation/preparation API before using staged persistence.
Existing storage migration remains explicit; startup does not scan or migrate
all Relationships. Datalevin requires its maintained fork, external source
lifecycle/security/watermark configuration, and an admitted write token from
physical schema preparation. Reuse the normal backend upgrade procedure when
opening an existing protected store; do not bypass its write policy.

`valid-until-ms` is typed and immutable in this phase but entirely inert for
authorization. Time-aware checks, conditional permissionship, request context,
qualified schema activation, and cache/cursor validity enter together in Phase 3.

## Phase 3 schema admission implementation

The Phase 3 branch implements committed and speculative Caveated schema
admission behind the disabled qualified semantic epoch. When enabled, any
Caveated Relation requires an evaluator matching `eacl-cel/1`, including empty
or unvisited Relations. The optional JVM module registers its matching default;
CLJS requires an explicitly supplied matching evaluator. Unused named Caveats
and expiry-only schemas remain evaluator-independent.

Datomic and Datalevin advertise certified inline publication. DataScript and
direct-writer Datahike advertise certified prepared-reference publication.
Unsupported writer topologies fail with `:eacl/unsupported-capability` before
qualified authorization cache lookup or schema publication.

Changing a Relation's Caveat alternatives preserves its native eid. Existing
Relationships, including expired rows, must satisfy the new alternatives;
otherwise replacement fails with
`:eacl.schema/relationship-qualifier-in-use`. Concurrent Relationship changes
are fenced at commit. Datalevin performs schema validation and generation reads
inside one owned snapshot, then releases that snapshot before submitting the
schema transaction. The qualified semantic epoch remains disabled while the
remaining cache, cursor, integrity, and release obligations are completed.

## Phase 3 physical Relationship inspection

When qualified serving is enabled, `read-relationships` defaults to
`:relationship-state :stored`. It returns retained rows with their optional
`:caveat`, `:caveat-context`, and `:valid-until-ms` metadata, including expired
rows. Select `:relationship-state :expiry-active` to exclude rows whose deadline
is at or before the request's captured trusted time. Responses label the selected
state and `:evaluation-time-ms`. Explicit snapshots retain their captured time.

```clojure
(eacl/read-relationships client
  {:resource/type :doc :resource/id "report" :first 20
   :relationship-state :expiry-active})
```

These modes inspect storage and expiry without evaluating Caveats. A row with
an unsatisfied or conditional Caveat can therefore appear in either view.
Use the existing `:authorization` filter or an authorization operation when a
permission decision is needed. Physical inspection alone does not require a
Caveat evaluator. Qualifier corruption remains a typed fault.

Expiry-active filtering spends the existing candidate-work budget on every
examined row, including skipped expired rows. A bounded page may be empty and
still return continuation state. It does not scan an arbitrarily long expired
prefix to fill a page. Forward, reverse, exact and partial endpoint queries
preserve qualifier alignment through public rendering and cache reuse.

Expiry retains Relationship identity. `:create` conflicts with an expired row;
`:touch` renews or shortens its deadline by replacing the immutable qualifier.
Omitting `:valid-until-ms` on a replacement removes expiry. Omitting all qualifier
metadata returns the row to the nil-qid representation when the Relation permits
plain input. No collection job is required for expiration correctness.

## Phase 3 decoded qualifier cache

Qualified clients have an optional bounded local decode cache. Configure
`:qualifier-cache {:max-entries 256}` or disable it with `:qualifier-cache false`.
The capacity counts both exact-basis and content-proof indices together. The
client's `eacl.cache/no-cache` setting disables this tier too; request
`:cache? false` bypasses it, while `:populate-cache? false` permits retained reads
without publishing new entries. Source lifecycle rotation replaces the tier.
It is private decoded data and is not part of exported authorization snapshots.

Exact-basis hits omit qualifier refetching. Across bases, current adapters compare
all native qualifier fields, the marker assertion version when available,
named Caveat definition, owning Relation content, and source lifecycle before
reusing the decoded structure. Datalevin uses its complete content proof without
claiming a native creation version. A publication-capable backend does not by
itself certify that every external writer preserves immutable qualifiers, so
cross-basis hits still perform bounded native content reads. This detects
unstamped in-place mutations, deletion and entity reuse without scanning the
graph for reverse ownership. Expiry and Caveat evaluation run for each request;
neither Boolean decisions nor conditional evidence enter this decode cache.

## Phase 3 point-answer validity intervals

Point checks retain their completed evidence with its original evaluation time,
exclusive deadline, completeness and permissionship. On the same immutable
basis and canonical request/evaluator scope, a later check may reuse that answer
only within its certified interval. A permanent decisive witness can provide an
unbounded interval. An incomplete certificate permits only the original time
and exact basis. Cache key separation still covers every supplied context field,
including fields not used by the selected Caveat.

Reaching an interval's deadline is a cache miss even when the entry remains in
memory. EACL recomputes and may replace the expired interval; an expiring ban can
therefore change a denial into a grant without any database write. Replacement
compares the expected immutable entry atomically. An older pinned snapshot may
recompute its original answer but cannot displace a newer interval under the
same cache key. Token lifetimes and cursor continuation remain separate checks.

Cross-basis qualified answer reuse remains disabled pending its complete writer
and dependency proofs. Lookup, count, range, and checkpoint caches keep their conservative
exact-time scope. Their retained certificates support the live continuation
checks described below. The release gate remains disabled throughout these steps.

## Phase 3 qualified cursors

A lookup on a client uses live time. Each resumed request captures a fresh trusted
sample and checks it against the cursor's complete exclusive validity interval.
At the deadline, or after it, EACL returns `:eacl.pagination/restart-required`.
Start a new lookup without `:after` or `:before` to obtain the current view. Keep
the desired filters and context; the new result sequence can include an object
that an expiring ban previously hid before the old boundary. EACL never silently
restarts or applies that old boundary to the changed temporal view.

A lookup on an explicit `eacl/snapshot` uses its pinned basis and captured time.
Its cursors continue that historical view even after wall-clock expiry. A pinned
snapshot can therefore preserve a past grant; use client-targeted lookups for
current access-control decisions. Live and pinned cursor modes are distinct.
Changing the complete Caveat context, evaluator identity, result policy, or mode
requires a new lookup. Cursor authentication and token TTL apply independently.

Certificates cover examined candidates, including skipped rows, conditional
results and subtracting evidence, together with retained frontier, lookahead and
checkpoint state. Cache and range reuse preserve those certificates. A missing
or incomplete retained proof allows continuation only at its original evaluation
time; a later live request returns `:temporal-certificate-incomplete` as the
restart reason. EACL does not scan an unseen suffix merely to manufacture a proof.
Stored physical Relationship inspection is timeless; expiry-active inspection
and authorization-filtered inspection retain their observed expiry deadlines.

## Qualified cache scope and reset traces

Qualification-dependent answers retain the complete immutable basis, canonical
whole request context and evaluator identity. Exact basis identity covers the
selected schema, Caveat definitions, Relation allowance and qualifier contents.
Conditional result categories and evidence remain part of validated values;
collection values additionally require their versioned temporal certificate.

| Retained value | Time and qualification scope |
| --- | --- |
| Decoded qualifier | Exact basis or complete native content; never an authorization answer |
| Raw scan response | Structural identity and aligned qualifier refs; independent of time and context |
| Derived schema plan | Schema generation and structural input; independent of request time |
| Point answer | Exact basis and context/evaluator, with resident interval acceptance |
| Lookup, count and range | Exact basis, context/evaluator and captured time, with retained certificates |
| Denotation and recursive checkpoint | Exact qualification scope and canonical evidence certificates |
| Stable page checkpoint | Exact scope and a validated certificate for the complete retained state |
| Rendered page | Exact request/time/mode and authenticated cursor tokens; token TTL remains separate |

Current bundled backends permit raw external writers. Qualified managed answer
promotion therefore stays disabled: ordinary Relation stamps and publication
capability cannot certify all qualifier and Caveat dependencies against such
writers. Exact-basis fallback is deliberate. It also prevents unknown in-place
changes from borrowing an answer from another native snapshot. Structural cache
reuse does not acquire a clock dependency simply because qualifiers are enabled.

The same native trace runs on all four backends, including both Datahike attribute
representations. Cold, warm, repeated and read-only-cache reads agree across
context changes, positive and subtracting expiry without writes, qualifier touch,
bound-context replacement, Caveat expression replacement and lifecycle rotation.
The trace checks points, both lookup directions, detailed/default result policies
and limited/unlimited counts. DataScript additionally checks authoritative faults
on subtracting qualifiers, unstamped native changes, and restored entity IDs.
Datalevin persists its new lifecycle and recreates the client; its process-local
cache-clear operation is not a substitute for that recovery procedure.

## Qualified object deletion

`delete-object!` removes Relationship state, including expired Relationships.
Each transaction removes both exact endpoint values and their owned qualifier,
with native basis, schema and Relation guards. Transaction limits apply after
pair expansion and guard construction. A batch boundary cannot leave a live
half-pair pointing at an already removed qualifier. Self-relationships and a
surviving peer of an already removed native object use the same cleanup path.

A stale deletion plan is rejected if the selected native basis changes before
submission, including an unstamped identity change or a qualifier replacement.
Other unattached preparations remain inert and are not collected by this API.
A referenced expired qualifier continues to protect its Caveat from schema
removal; removing the relationship and owned qualifier releases that dependency.

Datalevin supplies exact retractions from the selected owned read snapshot for
qualified deletion, then releases that selection before submitting a guarded
batch. Its existing ordinary commit-time deletion path remains available while
the qualified semantic epoch is disabled. This change bounds submitted
transactions; adapters that materialize object retractions retain that existing
planning behavior.
