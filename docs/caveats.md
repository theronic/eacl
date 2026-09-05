# Caveats and expiring Relationships (v9)

V9 adds named Caveats, conditional permissions, and an exclusive expiration time
on each Relationship. It uses the qualifier-eid storage ABI 9 landed in v8.
This branch's serving switch remains gated until the final activation checks
complete; the examples below describe the v9 contract. V8 and foundation-only
readers reject non-nil qualifier references and cannot serve an activated store.

The [executable example](examples/caveats.clj) covers conditional checks,
expiring grants and bans, pinned time, live-cursor restart, renewal, composed
writes, and deletion using DataScript and the optional JVM evaluator.

## Named definitions and schema admission

A definition is stored once and referenced by native entity ID:

```zed
caveat in_region(region string, accepted list<string>) {
  region in accepted
}

definition user {}
definition doc {
  relation viewer: user | user with in_region
  permission view = viewer
}
```

Use `eacl/write-schema!` to install this schema. `user with in_region` requires
that named Caveat; `user | user with in_region` permits either plain or Caveated
input. An expiry-only qualifier requires a plain alternative. Admission checks
an evaluator matching `eacl-cel/1` whenever any Relation names a Caveat, including
empty and unvisited Relations. Unused named definitions and expiry-only schemas
do not require an evaluator.

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

## Write and check a qualified Relationship

Create native endpoint entities using the backend's normal transaction API,
then pass public Relationship values to EACL:

```clojure
(def alice (eacl/spice-object :user "alice"))
(def report (eacl/spice-object :doc "report"))
(def grant
  (assoc (eacl/->Relationship alice :viewer report)
         :caveat "in_region"
         :caveat-context {"accepted" ["za"]}
         :valid-until-ms deadline-ms)) ; exclusive UTC epoch milliseconds
(eacl/create-relationship! client grant)

(eacl/check-permission client
  {:subject alice :resource report :permission :view
   :caveat-context {"region" "za"}})
;; includes {:allowed? true :permissionship :has-permission}
```

Omitting `region` produces `:conditional-permission`, `:allowed? false`,
`:missing-fields ["region"]`, and a canonical `:residual`. Supply the missing
context in a new request. `can?` returns true only for a definite grant.
At `deadline-ms`, this grant is inactive without a database write or collector.
An expiring ban in `viewer - banned` can conversely turn denial into permission.

Each Relationship has at most one named Caveat. Identity is still subject,
Relation and resource; differing context or expiry does not create an independent
grant. `:create` conflicts with an existing identity even after expiry. Use
`:touch` to renew, shorten, change context, or remove qualification:

```clojure
(eacl/write-relationship! client
  (assoc grant :operation :touch :valid-until-ms renewed-deadline-ms))
(eacl/write-relationship! client
  {:operation :touch :subject alice :relation :viewer :resource report})
(eacl/delete-relationship! client grant)
```

Batch updates use `{:operation :touch :relationship grant}` entries. Pass
`{:updates [...] :tx-data [...]}` to `write-relationships!` to commit application
datoms with the final publication. Identical intents coalesce; conflicting
qualifier values on one identity fail before allocation. Application datoms
cannot alter EACL's protected state.

For caller-managed native transaction composition, prepare each qualified value
with `prepare-relationship!`, acquire a snapshot **after** preparation, and pass
its opaque `:prepared-qualifier` handle alongside the corresponding update to
`tx-relationships`. Submit the returned native tx-data, then release the
snapshot. Preparation is inert; `discard-prepared-relationship!` removes an
unchanged, unattached preparation. The executable example shows this sequence.

## Public results and errors

Default lookups return definite `SpiceObject` results. `:result-policy :detailed`
returns `{:object ... :allowed? ... :permissionship ...}` items, including
conditional results and their missing fields/residuals. Detailed counts add
`:definite-count` and `:conditional-count`; they sum to `:count` and exclude
lookahead. A conditional interior edge may still compose into a definite result.

| Outcome or error | Meaning / caller action |
| --- | --- |
| `:has-permission` / `:no-permission` | Completed decision at the captured basis and time |
| `:conditional-permission` | Supply missing context in a new request; do not treat as a grant |
| `:eacl.caveat/invalid` | Invalid context, definition, profile, or resource bound; inspect the typed reason |
| `:eacl/invalid-relationship-qualifier` | Invalid public Caveat/context/expiry input |
| `:eacl/relationship-conflict` | Identity already exists for create, or is absent for replace |
| `:eacl.caveat/evaluator-unavailable` | Install/supply a matching evaluator before serving the schema |
| `:eacl/unsupported-capability` | The backend/writer lacks the required certified operation |
| `:eacl.authorization/evaluation-failure` | Encountered authoritative qualifier/Caveat fault; detailed reads fail |
| `:eacl.schema/relationship-qualifier-in-use` | A schema change would invalidate retained Relationships, including expired ones |
| `:eacl.pagination/restart-required` | Live temporal certificate ended; begin a new lookup without the cursor |
| `:eacl.pagination/invalid-cursor` | Authentication, scope, or envelope mismatch; do not silently reuse its boundary |

`can?` converts authoritative qualified evaluation failures to false for Boolean
compatibility. Invalid requests, cancellation, execution limits and backend
errors still propagate. Detailed checks and collections expose faults; they
never erase a malformed subtracting edge into an absent ban.

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
| Cached portable/native artifacts (shared capacity) / simultaneous builds | 256 / 4 |

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

Public `write-relationships!` chooses the certified publication strategy. The
internal staged writers enforce native schema/Relation fences, endpoint identity,
immutable qualifier facts and caller-datom restrictions. Use the public
preparation/planning APIs above for transaction composition.

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

## Schema replacement and backend admission

Committed and speculative schema replacement use the same qualified admission
checks. The optional JVM module registers its matching default; CLJS requires
an explicitly supplied, independently certified matching evaluator for Caveated
Relations. Expiry-only Relationships remain portable without an evaluator.

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
schema transaction.

## Physical Relationship inspection

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

## Decoded qualifier cache

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

## Point-answer validity intervals

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

Bundled backends use exact-basis qualified answer reuse because their raw-writer
interfaces do not provide a complete immutable-writer dependency proof. Lookup, count, range, and checkpoint caches keep their conservative
exact-time scope. Their retained certificates support the live continuation
checks described below.

## Trusted clocks and Peer skew

Each top-level operation captures one trusted evaluation time and uses it for
all traversed edges, batches and retained evidence. Request Caveat context cannot
override this time. The default uses wall-clock UTC epoch milliseconds with a
process-local non-decreasing high-water mark. A configured client `:clock`
function gets its own high-water wrapper. On a backward clock step, time holds
at the previous accepted value until the clock catches up; an already expired
grant cannot revive within that clock's lifetime.

The high-water mark is not durable and does not synchronize Peers. A slow Peer
can expire a grant late, and a fast Peer can expire it early. Keep serving clocks
synchronized, monitor offset and backward steps, and reject operations through
your trusted clock/service health policy when skew exceeds your application's
acceptable uncertainty. EACL adds no hidden grace period or distributed clock
agreement. A process restart or a newly configured clock must not be treated as
proof of temporal continuity. Causal tokens certify data visibility, not equal
wall time between Peers.

Explicit snapshots pin both database basis and evaluation time. They deliberately
support historical/simulation decisions, including a grant whose wall-clock
expiry has since passed. Use client-targeted checks and lookups for current
access control. A pinned snapshot is not a live authorization lease.

## Qualified cursors

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

## Coordinated rollout and rollback

1. Complete the v8 qualifier-eid storage migration and take a recoverable
   database/schema checkpoint. Install the additive Caveat and qualifier
   attributes through the backend's normal explicit preparation API. Startup
   does not migrate or scan every Relationship.
2. Upgrade every serving Peer to the v9-capable implementation before permitting
   non-nil qualifier writes. Drain older readers. Install and explicitly require
   the optional JVM evaluator, or supply an independently certified evaluator
   with the matching profile. Expiry-only schemas need no evaluator.
3. Verify each writer advertises its certified publication strategy, clock
   health is acceptable, and live read paths select a fresh operation time.
   Apply Caveated Relation alternatives using `write-schema!`; retained-data
   validation must succeed before allowing qualified writes.
4. Enable qualified writes as a coordinated application rollout. Exercise
   grant/ban expiry without writes, missing-context outcomes, and live cursor
   restart. Monitor typed faults and resource limits through the usual request
   diagnostics; never substitute an ordinary edge for a missing qualifier.

Rollback after qualified writes requires stopping those writes and restoring the
pre-activation data/schema checkpoint before returning to older readers. Merely
disabling a serving switch or removing the evaluator does not make qualified
stored data safe for v8 readers. Already issued qualified cursors are scoped to
the v9 semantic contract and must not be silently rebased onto an older reader.

Datomic, DataScript, direct Datahike, and the optional JVM evaluator remain in the
coordinated release set. The local Datalevin implementation is also tested, but
its Maven adapter stays excluded while
`dev.eacl/datalevin-embedded-eacl:1.0.2-eacl.2` is unpublished. Its local fork is
pinned at `a7e29c25a3034b54814e58a2d317e8c6877d1933`; a deployment needing that
adapter must resolve the publication and cold-consumer audit before release.

The [acceptance crosswalk](../formal/qualified/acceptance.md) maps production
refinement and killed controls. The [performance qualification](benchmarks/qualified-authorization.md)
records the numerical budgets, exact workload and measurement limits.
