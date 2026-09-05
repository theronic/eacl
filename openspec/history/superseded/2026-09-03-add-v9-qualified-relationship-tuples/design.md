> **DEPRECATED / SUPERSEDED — 2026-09-04.** This unimplemented proposal is retained for historical review only. Its requirements and unchecked tasks are withdrawn from the implementation plan; do not apply or sync these deltas into the main specifications. Use the [replacement v9 proposal](../2026-09-04-add-v9-caveats-and-expiring-relationships/proposal.md).
>
> The replacement uses **seven-slot Caveat + expiry-only endpoint tuples**, removes scheduled activation and the mandatory expiration index, and retains time-aware cache/cursor checks. See the [review findings and preserved REPL evidence](review-2026-09-04.md). The original artifact follows unchanged.

## Context

See `proposal.md` for motivation. EACL v8 retains the v7 persisted relationship
ABI: one four-component forward tuple on the subject and one four-component
reverse tuple on the resource. The shared codec, backend adapters, ordered
scans, mutation guards, cleanup, integrity, cache proofs, and cursors all assume
that shape.

The v7 representation is efficient because one endpoint-local seek returns the
opposite eid directly. The former relationship-entity representation used seven
datoms per relationship and still required covering indexes for efficient
forward and reverse traversal. v9 must preserve the two-datom hot path while
adding native validity and SpiceDB-compatible Caveats.

SpiceDB is the behavioral reference for Caveats and qualifier identity. Its SQL
datastores keep Caveat name, relationship-bound Caveat context, and expiration
on the relationship row, while the resource/relation/subject columns remain the
relationship key. It does not permit two relationships that differ only by
Caveat or expiration. Relationship-bound context overrides request context for
the same key, and checks can return no permission, has permission, or
conditional permission with missing context fields.

Relevant inspected EACL areas include:

- `modules/eacl/src/eacl/relationships/storage.cljc`
- `modules/eacl/src/eacl/relationships/endpoint_pair.cljc`
- `modules/eacl/src/eacl/spicedb/parser.cljc`
- each backend schema, database adapter, writer, cleanup, and integrity module
- the cache, cursor, public authorization, and converged storage specifications

Relevant inspected SpiceDB areas include:

- `internal/datastore/common/sql.go`
- `internal/datastore/postgres/readwrite.go`
- `internal/datastore/postgres/schema/indexes.go`
- PostgreSQL, CockroachDB, and Spanner Caveat/expiration migrations
- the AuthZed Caveats and expiring-relationships contracts

## Goals / Non-Goals

### Goals

- Preserve exactly two authoritative endpoint datoms for every logical
  relationship.
- Preserve one authoritative endpoint attribute and one ordered candidate
  stream per traversal direction; account separately for validation reads.
- Freeze an eight-component v9 ABI that can support both native validity and
  Caveats without another relationship-storage break.
- Keep the owning endpoint eid plus its first four tuple components as the
  logical relationship identity.
- Store arbitrary relationship-bound Caveat context outside the hot endpoint
  tuples, without reifying every relationship.
- Match SpiceDB Caveat context precedence, duplicate relationship, mutation,
  deletion, and conditional-result behavior.
- Make validity and Caveats sound under permission algebra, caches, cursors,
  speculative snapshots, cleanup, integrity, and unknown-writer proofs.
- Deliver validity first and Caveats second without changing tuple arity or
  component positions between phases.
- Reject populated old relationship stores rather than retain dual-read or
  migration complexity.

### Non-Goals

- Support multiple independently revocable grant assertions for the same
  subject/relation/resource identity.
- Support more than one Caveat attached to a single relationship.
- Support recurring schedules, time-zone recurrence rules, grant provenance,
  approvals, stable public grant IDs, or independent revocation tokens.
- Depend on Datahike's experimental transaction-level valid-time feature.
- Use a scheduler or garbage collector to make activation or expiration
  correct.
- Implement an automatic, online, offline, or mixed-format v7-to-v9
  relationship migration.
- Preserve arbitrary past valid-time reconstruction beyond each backend's
  configured history retention.
- Add further inline relationship qualifiers after v9; eight components consume
  Datomic's tuple arity budget.

## Decisions

### 1. Use one fixed eight-component v9 endpoint ABI

The authoritative values are:

```clojure
;; Forward value stored on subject-eid
[subject-type
 relation-eid
 resource-type
 resource-eid
 caveat-eid
 caveat-context-eid
 valid-from-ms
 valid-until-ms]

;; Reverse value stored on resource-eid
[resource-type
 relation-eid
 subject-type
 subject-eid
 caveat-eid
 caveat-context-eid
 valid-from-ms
 valid-until-ms]
```

Proposed persisted attribute names are:

```clojure
:eacl.v9.relationship/subject-type+relation+resource-type+resource+caveat+caveat-context+valid-from+valid-until
:eacl.v9.relationship/resource-type+relation+subject-type+subject+caveat+caveat-context+valid-from+valid-until
```

Datomic Pro, Datahike, and Datalevin declare:

```clojure
[:db.type/keyword
 :db.type/ref
 :db.type/keyword
 :db.type/ref
 :db.type/ref
 :db.type/ref
 :db.type/long
 :db.type/long]
```

DataScript stores the same fixed eight-element vector and enforces the same
logical types and cross-runtime integer range.

Every optional component remains physically present:

```text
slot 5 caveat-eid          nil when uncaveated
slot 6 caveat-context-eid  nil when no non-empty bound context exists
slot 7 valid-from-ms       nil when unbounded below
slot 8 valid-until-ms      nil when unbounded above
```

A context ref without a Caveat ref is invalid. A Caveat ref with a nil context
ref means the Caveat is attached with an empty relationship-bound context.

The owning endpoint eid and first four components identify the relationship.
The first four components alone are only an endpoint-local key: two subjects
can legitimately hold identical forward tuple values. Components five through
eight qualify that identity. Conflict, deduplication, repair, and cache keys
MUST retain the owning endpoint, including wildcard/subject-relation identity
represented by the existing endpoint codec.

**Alternatives considered**

- **Bring back relationship entities.** Rejected. Efficient traversal would
  still require two covering indexes, while ordinary relationships would pay
  scalar-datom, lifecycle, write-amplification, and cache-density costs.
- **Use one Caveat-binding ref.** Rejected in favor of two explicit slots. A
  separate Caveat-definition ref plus optional context ref avoids an extra
  binding-definition datom, lets an empty-context Caveat allocate no
  relationship-specific entity, and mirrors SpiceDB's separate Caveat name and
  context columns.
- **Inline Caveat context.** Rejected. The arbitrary payload would be duplicated
  in both endpoint values and enlarge the hottest ordered indexes.
- **Use separate permanent and qualified relationship attributes.** Rejected.
  Negative checks and every enumeration or graph hop would need two seeks and
  an ordered merge.
- **Use a shorter Phase 1 tuple.** Rejected. It would force another persisted
  ABI migration when Phase 2 enables Caveats.

### 2. Put Caveat refs before validity, but keep all qualifiers trailing

The index-significant order remains:

```text
endpoint entity and attribute
  -> endpoint type
  -> relation eid
  -> opposite type
  -> opposite eid
  -> Caveat definition
  -> Caveat context
  -> valid-from
  -> valid-until
```

The decisive performance property is that all qualifiers follow the opposite
eid. Adjacency and pagination therefore remain ordered by the opposite eid, and
a normal graph hop has one ordered candidate stream. This is an index-layout
property, not a guarantee of one total read or constant page latency.

Among trailing qualifiers, Caveat-before-validity has no material effect on
ordinary lookup complexity because v9 permits only one stored relationship per
owner-qualified first-four identity. The chosen order instead provides these benefits:

- it mirrors SpiceDB's conceptual row order of Caveat, Caveat context, then
  expiration;
- it reserves Phase 2 positions before Phase 1 is released;
- it groups the two Caveat fields and the two validity fields;
- it lets the Caveat-free Phase 1 writer emit two explicit nils followed by the
  validity interval.

Physical component order does not dictate evaluation order. The engine reads
all eight components from one tuple value, rejects an inactive relationship
before dereferencing Caveat context, and invokes the Caveat evaluator only for
temporally active caveated edges.

A direct logical-identity probe seeks the first four components with full-arity
low/high sentinels. A relation scan seeks the first three components and pages
by component four. Every backend must use full eight-slot bounds where its
vector/tuple comparator requires equal arity. Ascending inclusive bounds use
backend-certified low qualifier sentinels; descending inclusive bounds use high
qualifier sentinels. Exclusive pagination skips the entire boundary identity.
Nil is a low sentinel, not a descending high sentinel. Sentinels are query
values, never persisted validity values. Validate negative timestamps and the
representable extremes, not only post-epoch examples.

**Alternative considered: leading validity.** Rejected. It would destroy
endpoint-local adjacency ranges, and one lexicographic ordering cannot make the
two-sided interval predicate `from <= t < until` a single complete range.

**Alternative considered: validity before Caveat.** Correct but not preferred.
It has the same hot-path complexity. The selected Caveat-first order better
matches the Phase 2 storage model and SpiceDB qualifier representation.

### 3. Store Caveat definitions as shared schema entities

Phase 2 adds schema-level Caveat entities with canonical identity, typed
parameters, source/AST payload, and compatibility profile. Conceptually:

```clojure
{:db/id caveat-eid
 :eacl/id "eacl.caveat:ip_allowlist"
 :eacl.caveat/name :ip_allowlist
 :eacl.caveat/parameters-payload canonical-parameters
 :eacl.caveat/expression-payload canonical-expression
 :eacl.caveat/profile :spicedb-v9}
```

The exact persisted payload fields may be consolidated, but the authoritative
representation must be deterministic, bounded, and included in schema
generation and schema-content proofs.

A relation type reference can allow a Caveat using SpiceDB-compatible syntax:

```zed
caveat ip_allowlist(user_ip ipaddress, cidr string) {
  user_ip.in_cidr(cidr)
}

definition device {
  relation viewer: user | user with ip_allowlist
}
```

The duplicate uncaveated and caveated subject branches make the Caveat
optional. A caveated branch without an uncaveated equivalent makes it required
for that subject form.

Each stored relationship has zero or one `caveat-eid`. The selected Caveat must
be allowed by the exact relation/subject-type/subject-relation branch.

### 4. Store non-empty relationship-bound context in a sparse context entity

Relationship-specific Caveat context lives in an internal entity referenced by
slot six:

```clojure
{:db/id context-eid
 :eacl.caveat-context/payload canonical-context}
```

The payload is one canonical, type-preserving, bounded value using EACL's secure
portable encoding. It represents a map of parameter names to partially bound
values. It is not stored as one attribute per context key.

The context entity:

- has no public object ID;
- exists only when the bound context is non-empty;
- is owned by exactly one logical relationship;
- is immutable after creation;
- is not deduplicated across relationships;
- is replaced atomically when `:touch` changes context;
- is retracted when the relationship is deleted or replaced;
- remains qualifier data rather than a graph vertex.

For a Caveat with no relationship-bound values:

```clojure
[..., caveat-eid nil valid-from valid-until]
```

No context entity is allocated. Request context can supply all required values.

For an uncaveated relationship:

```clojure
[..., nil nil valid-from valid-until]
```

No Caveat-specific entity or lookup exists.

On an integrity-certified source, payload retrieval adds one point read when
an active caveated relationship has non-empty bound context. That count excludes
ownership, pair, schema, and content-proof validation; unknown-writer sources
may require additional work. Caveat definitions are expected to come from the
compiled snapshot schema rather than an entity lookup per leaf.

**Alternative considered: always allocate a binding entity.** Rejected. It
would allocate at least one extra datom even for a Caveat with empty bound
context and add an unnecessary definition indirection.

**Alternative considered: shared context entities.** Rejected. Sharing would
require reference counting or global reachability collection and would make
safe deletion materially more complex.

### 5. Match SpiceDB relationship identity and mutation semantics

Exactly one stored relationship may exist for a logical identity:

```text
subject type/eid + relation eid + resource type/eid
```

Caveat definition, bound context, `valid-from`, and `valid-until` do not make
new identities. Two relationships differing only in these qualifiers cannot
coexist.

Public operation semantics are:

- `:create`: conflict when any stored v9 assertion has the same owner and first four
  components, including future, expired, uncaveated, or differently caveated
  assertions;
- `:touch`: create when absent, otherwise atomically replace Caveat, context,
  validity, endpoint pair, and sparse auxiliary data;
- `:delete`: delete by logical identity without requiring Caveat, context, or
  validity values.

Commit-time conflict control must operate on the owner plus first-four key,
not complete tuple equality. Every create, touch, delete, and collector plan
must validate the state it replaces at commit time. A relation-generation CAS
with replanning or a transaction function over the current database is suitable;
serialized submission of stale raw retractions/additions is insufficient.
Unsupported writer topologies reject qualified writes rather than downgrade to
plan-time checks. Advance each affected relation generation once per admitted
transaction; an unchanged idempotent touch need not advance it.

Out-of-band duplicate qualifier variants are authoritative corruption. An
encountered fault raises `:eacl/invalid-relationship-state` for the operation;
it is never converted to absent/false inside permission algebra. For example,
dropping a corrupt `banned` edge from `viewer - banned` grants access. Bounded
repair may replace/remove all variants, but cannot retract a context whose
exclusive ownership is unproven. Quarantine ambiguous auxiliary data for
explicit integrity repair rather than damaging another relationship.

This follows SpiceDB and avoids introducing implicit multiple-grant semantics.
If EACL later needs independently revocable grants, those are first-class grant
assertions with independent IDs, not duplicate v9 tuples.

### 6. Phase 1 implements fixed storage and native validity

Phase 1 installs and exclusively reads/writes the eight-slot v9 values. Caveat
slots five and six must be nil in admitted Phase 1 relationship writes. A
non-nil Caveat slot encountered from an out-of-band writer raises a typed
unsupported-feature/integrity error until Phase 2 is enabled; it cannot be
dropped from an exclusion operand.

Validity uses the half-open interval:

```text
[valid-from, valid-until)
```

with activity at `t`:

```clojure
(and (or (nil? valid-from)  (<= valid-from t))
     (or (nil? valid-until) (< t valid-until)))
```

Bounds are normalized to exact UTC epoch-millisecond integers. Supplied bounds
must satisfy `valid-from < valid-until` when both are present. The portable
range is `[-9007199254740991, 9007199254740991]`; nil denotes infinity. Reject
precision-losing normalization rather than silently rounding a supplied instant.

Every top-level authorization view pairs one immutable database basis with one
trusted captured `valid-at-ms`. Client-targeted operations capture a fresh time
even when they reuse a minimize-latency database pin. Explicit EACL snapshots
freeze both basis and valid-time; `eacl/with` and `eacl/with-schema` preserve
that time.

Validity is checked before Caveat processing and before an edge participates in
union, intersection, exclusion, arrow traversal, recursion, lookup, or count.

### 7. Phase 2 implements SpiceDB-compatible Caveats

Phase 2 enables the already-reserved Caveat slots and adds:

- parsing and validation of top-level Caveat definitions and `with caveat`
  relation type references;
- canonical typed Caveat definitions and a versioned SpiceDB compatibility
  profile;
- relationship-write validation and sparse bound-context entities;
- request Caveat context on authorization and lookup operations;
- relationship-bound context precedence over request context for duplicate
  keys;
- deterministic bounded CEL evaluation;
- has-permission, no-permission, and conditional-permission results;
- missing-context field reporting;
- conditional-result composition through the complete permission algebra;
- complete cache, cursor, explanation, and proof dependencies.

Evaluation failure is separate from the three successful permissionship
states. Encountered errors, resource limits, and malformed authoritative data
abort the operation; `can?` propagates the typed failure and never interprets a
failed subtracting branch as false. No successful answer or continuation is
published from that failed operation.

The evaluator may have runtime-specific implementations, but all supported
runtimes must consume the same canonical typed representation and pass the same
SpiceDB-derived conformance corpus. Unsupported Caveat syntax, types, functions,
or values are rejected at schema/write admission rather than approximated.

The internal engine cannot collapse missing context to false too early. A leaf
may produce:

```clojure
{:permissionship :conditional-permission
 :residual-condition ...
 :missing-context #{...}}
```

Union, intersection, exclusion, and arrows combine residual conditions so an
unconditional granting witness can eliminate irrelevant conditions, while a
missing value that could still change the answer remains conditional.

The public Caveat-aware check returns a three-state result. Existing `can?`
remains a convenience Boolean and returns true only for
`:has-permission`; both `:no-permission` and `:conditional-permission` produce
false.

### 8. Bound context overrides request context

Phase 2 uses the SpiceDB merge rule:

```clojure
(effective-context request-context relationship-bound-context)
```

where values from the relationship-bound map replace request values with the
same key.

This prevents a caller from overriding policy values fixed when the relationship
was written. Context is validated against the Caveat parameter types before
evaluation. Unknown keys may be retained in request identity but are not visible
to an expression unless named by its parameter environment.

Both stored and request context are untrusted inputs and receive explicit limits
for depth, entry count, string/byte size, collection size, and encoded size
before compilation, evaluation, or cache-key construction. Structural validation
happens at admission; Caveat-specific type conversion happens after bound values
replace request values, so a shadowed request value is not incorrectly
type-checked as the effective parameter. The merge is shallow by parameter
name, not a recursive merge of nested map parameters.

The existing secure-format v1 is only an envelope, not the CEL value model: it
rejects doubles and integers outside the JS safe range. Before enabling Phase 2,
freeze a versioned typed encoding for every supported CEL value (including
exact int/uint and double semantics), lower it to envelope-supported values,
and test CLJ/CLJS byte identity. Do not widen the existing cache/token numeric
contract silently. Pin the SpiceDB reference release/commit and explicitly
list supported syntax, types, coercions, functions, and rejected features.
Until that profile passes differential tests, compatibility is a scoped goal,
not a claim of complete CEL or SpiceDB support.

### 9. Caveat and temporal data extend cache and cursor proofs

Relation-version equality remains necessary but is not sufficient.

Validity can change an answer without a database write. Reusable results
therefore carry a conservative temporal stability interval and are reusable only
when the selected `valid-at` remains inside it.

Separate lookup scope, dependency certificates, and authenticated result data.
A lookup key is computable before evaluation; it includes request context,
query scope, ABI/evaluator identity, and the existing source scope. Definition
and context content belong in dependency validation. Permissionship, residual
conditions, and missing fields belong in the authenticated value, not an
answer-dependent lookup key. Together these include:

- the complete canonical request context relevant to the request;
- `caveat-eid` and the Caveat definition schema generation/content proof;
- `caveat-context-eid` and canonical payload/content proof;
- evaluator compatibility profile and implementation fingerprint;
- conditional/result kind, residual condition, and missing fields where stored;
- the ordinary complete relation dependency proof;
- the temporal stability interval.

Unknown-writer content proofs commit to both complete endpoint values and the
authoritative context payload. Mutating context in place is unsupported; managed
writers replace the immutable context entity and tuple reference. An out-of-band
mutation still changes the content proof.

Cursors authenticate request context and conditional semantics so context cannot
change between pages without a restart or typed mismatch. They also authenticate
a temporal mode. Explicit snapshot continuations remain pinned to their original
valid-at; wall-clock passage cannot expire that temporal certificate. Live
continuations capture a fresh valid-at and may reuse state only inside its full
certified interval. Crossing either interval bound returns a typed restart
requirement and no resumed page. Token TTL/retention is a separate check.
Certify the retained frontier, skipped candidates, negative evidence, residuals,
and lookahead, not only already-emitted results. A final answer certificate does
not automatically certify reusable intermediate state.

### 10. Use a sparse expiration index, not time-leading endpoint tuples

A finite `valid-until` adds one EACL-owned cardinality-many indexed value on
the subject entity, ordered by expiration:

```clojure
;; Attribute :eacl.v9.relationship/expiration
[valid-until-ms subject-type relation-eid resource-type resource-eid
 caveat-eid caveat-context-eid valid-from-ms]
;; Datomic tuple types: [long keyword ref keyword ref ref ref long]
```

Together with the datom owner, this eight-slot permutation reconstructs the
exact forward and reverse values without an extra relationship entity or a
ninth tuple slot. AVET (or the adapter equivalent) bounds collection by finite
expiry. The collector validates the current complete pair and ownership inside
the admitted transaction before retracting anything.
Permanent and start-only relationships add no expiration-index datom.

Authorization never consults the expiration index. Expired endpoint tuples are
absent immediately; collection only reclaims storage. Missing/stale index values
are maintenance faults, not authoritative graph faults. They do not change an
authorization content proof or abort a check. Keep collection/reconciliation
proofs separate from authorization proofs.

No mandatory global Caveat index is added. Caveat definition in-use validation
can enumerate the small set of relation definitions that allow that Caveat and
perform relation-prefix scans over their v9 forward values. A sparse
Caveat-usage index may be added later only if measured schema-write cost
justifies another caveated-relationship datom.

### 11. Keep authoritative history where the backend supports it

The endpoint pair, Caveat definition, and relationship-bound context are
authoritative. They retain normal history semantics where available. The
expiration index is derived and may use no-history/replaceable storage where a
backend supports it.

Transaction time and valid time remain distinct:

```text
database basis T + relationship valid-at V
```

Physical collection does not change present authorization but may limit
reconstruction from a current basis. Historical reconstruction remains subject
to backend retention.

### 12. Reject old relationship data; do not dual-read

v9 never reads the v7 endpoint attributes for authorization. Startup rejects:

- any v7 forward or reverse relationship datom;
- mixed v7/v9 relationship data;
- an incompatible relationship-storage version stamp.

Legacy Datomic attribute definitions may remain installed but inert. Operators
must rebuild or reseed relationship data into the v9 shape before running v9.
Old cache snapshots and cursors are rejected through ABI versioning.

The eight-slot storage version alone cannot distinguish phases. Persist a
semantic capability epoch alongside it. Phase 2 activation fences obsolete
readers and writers: every newly selected basis and every mutation commit
validates the epoch, with speculative parity. Old tokens/cache entries are
rejected. Retained immutable Phase 1 snapshots may be served only as explicitly
pinned historical views, never as a current Phase 2 view. Startup-only checks
are insufficient for clients that remain alive through activation. Activation
must drain or fence old current-serving clients, including clients retaining a
minimize-latency pin. Reading an epoch from that old immutable pin does not
establish the current capability epoch. A deployment unable to establish that
fence must use a coordinated stop/upgrade/activate cutover; mixed-phase current
service is unsupported.

This is intentionally not a migration design. Rebuild guidance and validation
are part of the release, but no conversion code or fallback path is shipped.

## Risks / Trade-offs

- **Eight slots consume Datomic's tuple arity ceiling** → Treat v9 as the final
  inline qualifier budget. Future provenance or independent grants use sparse
  side entities or a new assertion model, not a ninth slot.
- **Permanent relationships carry four nil qualifier values in each half** →
  Benchmark segment density and scan throughput against v7; retain one candidate stream and
  two authoritative endpoint datoms as the layout target. A finite expiration
  adds a third derived datom; two datoms do not mean equal storage bytes.
- **Bound Caveat context adds a point read for active caveated edges** → Keep
  context sparse, immutable, one-datom, and bypass the read when slot six is
  nil; cache decoded context under complete snapshot/content identity.
- **Caveat algebra and caching are security-sensitive** → Preserve residual
  conditions, include all context/evaluator dependencies, add adversarial
  cache tests, and compare a conformance corpus with SpiceDB.
- **Clock skew can change boundary behavior across peers** → Capture one trusted
  time per operation, require synchronized production clocks, document skew,
  and reject an invalid clock. Do not claim that shrinking each edge interval
  is fail-closed: early expiry of an exclusion can grant access. Any future skew
  margin must prove the whole permission invariant over the uncertainty window
  or return a typed failure.
- **Out-of-band duplicate qualifier variants can exist because database set
  uniqueness applies to all eight slots** → Protect EACL attributes where
  possible, serialize admitted mutations by owner-qualified identity, detect duplicates
  on reads, and abort affected operations rather than selecting a winner.
- **No in-place upgrade path** → Fail startup before serving, provide explicit
  export/reseed instructions, and require backups before rebuild.
- **Different CEL runtimes can diverge** → Use one canonical typed IR, a
  versioned compatibility profile, shared test vectors, and runtime
  differential tests before Phase 2 release.

## Delivery Plan

### Phase 1 — v9 storage and native validity

1. Install the fixed eight-slot schema and bump storage/cache/cursor ABIs.
2. Reject populated v7 or mixed stores.
3. Replace endpoint codecs, scans, mutations, cleanup, integrity, and proofs.
4. Require Caveat slots five and six to be nil.
5. Add relationship validity, trusted valid-time snapshots, temporal filtering,
   and temporal stability proofs.
6. Add sparse expiration collection and rebuild/reseed documentation.
7. Benchmark all backends and release only after candidate-stream, total-read,
   inactive-scan, and predeclared regression thresholds are demonstrated.

### Phase 2 — Caveats

1. Add Caveat grammar, canonical schema entities, relation type validation, and
   schema replacement safety.
2. Enable slot five and sparse slot-six context entities in writes and reads.
3. Add immutable context lifecycle and qualifier-aware touch/delete behavior.
4. Add typed CEL compilation/evaluation and SpiceDB-compatible context merge.
5. Add three-state public checks, missing-context reporting, and conditional
   lookup results.
6. Compose residual conditions through every permission operator.
7. Complete cache/cursor/proof/explanation scope and adversarial tests.
8. Run cross-runtime and SpiceDB differential conformance before declaring
   Caveat compatibility.

## Rebuild / Release Plan

1. Publish the v9 storage and API break before release.
2. Require operators to export logical Relationships and application data needed
   to recreate them.
3. Create a fresh database with the v9 physical schema.
4. Re-transact schema and Relationships through the v9 API.
5. Verify no v7 relationship datoms exist, forward/reverse parity holds, and
   the storage stamp is 9.
6. Run application authorization validation before switching traffic.
7. Do not start a v9 client against the old populated database.
8. Rollback uses the old database/backup and old EACL version; there is no
   in-place v9-to-v8 relationship downgrade.

## Open Questions

The endpoint ABI and rebuild policy are fixed. Phase 2 activation remains gated
on a concrete compatibility profile, typed codec, evaluator, and differential
corpus; the tuple layout alone does not resolve those deliverables. Release
latency/space budgets must be recorded before qualification, not invented after
observing candidate measurements.


## Adversarial qualification requirements

A single ordered endpoint stream cannot establish whether its opposite half
exists, nor whether another relationship owns a context. Require a validated
source/basis integrity certificate or perform the necessary validation reads.
A content digest detects change; it does not certify valid structure. On
unknown-writer sources, validate authoritative pair/qualifier integrity for the
selected dependency scope before publishing successful results. Report source
qualification and proof costs separately; do not conceal them in setup or count
only calls to `seek-datoms`. Direct membership must inspect enough of the local
identity group to detect a second variant before granting. Repair has an
explicit work bound and fails atomically when it is exceeded.

Trailing validity preserves ordering but cannot skip an arbitrarily long run
of inactive relationships. A page of 20 can require scanning an entire
million-edge adjacency list. Charge inactive and corrupt candidates, lookahead,
validation reads, payload bytes, and evaluator work to execution limits. If a
page cannot be completed, return the existing typed resource-limit failure;
never return a successful truncated denial, count, or end-of-stream. Measure
permanent, randomly mixed, and adversarially clustered expired/future prefixes
and suffixes in both directions. Total store size and maximum endpoint degree
are separate fixture dimensions. Collection does not solve future-only runs.

Positive recursion with residual conditions requires a bounded, terminating
representation: revisiting a cycle must not grow equivalent formulas forever.
Freeze residual identity, sharing/simplification, fixed-point termination, and
work limits before enabling recursive Caveats; reject unsupported recursive
forms at schema admission. Differential cases must include repeated predicates,
different bound contexts under the same Caveat name, conditional cycles, and
negation/exclusion. Resource exhaustion remains a typed failure.


## Review evidence — 2026-09-04

Reviewed against core commit `031144ceda0925d2cd171c0b72332d609bcc4fcc`.
Experiments ran through nREPL, using in-memory Datomic for small semantics and
a dedicated `:dev` peer on port 7791 for the durable fixture. A focused nREPL
assertion suite finished with **4 tests, 21 assertions, 0 failures, 0 errors**.
These are probes
of the proposed model and current primitives; v9 is not implemented.

### Confirmed counterexamples and constraints

- **Exclusion failure (high confidence):** a public EACL in-memory check of
  `viewer - banned` returned false with both relationships present and true
  when the ban was removed. This models the proposal's instruction to drop a
  corrupt negative edge; it is not a claim that a v9 implementation was tested.
- **Owner identity (high):** identical forward values were stored on two
  different subject entities. They represent different relationships.
- **Physical uniqueness (high):** Datomic accepted three distinct eight-slot
  values with one owner/first-four identity and different trailing qualifiers.
- **Stale replacement (high):** applying two `d/with` touch plans, both retracting
  the same old value and adding different new values, left two variants. The
  second plan needs a current-state guard even though application is serial.
- **Read cost/integrity (high):** retracting the referenced context or opposite
  endpoint left the selected forward values unchanged. A forward-only stream
  cannot certify peer existence or context ownership.
- **Descending bounds (high):** a nil-tailed descending bound returned only the
  permanent variant; a full high-sentinel bound returned all three variants.
  This confirms Datomic behavior; each other adapter still needs qualification.
- **Codec gap (high):** `eacl.secure-format/canonicalize` accepted integer 1 and
  the string `"9223372036854775807"`, rejected double 1.25 as
  `:unsupported-value`, and rejected integer 9007199254740992 as
  `:integer-out-of-range`. Accepting a string does not implement CEL int64.
- **Time certificates (high, executable model):** at valid-at 90, a pinned
  interval ending at 100 remains valid when wall time reaches 110. A permanent
  union answer stayed true at 90 and 100 while its scheduled child changed
  false to true; answer and denotation certificates cannot be interchanged.
- **Context precedence (high, executable model):** shallow bound-map replacement
  removed a request-only nested `admin` key and shadowed a wrong-typed request
  parameter with its valid bound value. SpiceDB differential validation remains
  a Phase 2 gate.

### Durable million-relationship storage probe

Datomic Peer/transactor 1.0.7705, Java 26.0.2, transactor heap 4 GiB, peer maximum
heap 9 GiB, local dev protocol on port 4334. The isolated fixture stored the
same million identities in four-slot and eight-slot attributes for paired raw
access comparison; it was not opened as a v9 EACL database. Both layouts had
1,000,000 forward and 1,000,000 reverse datoms. The v9 layout additionally had
999,980 expiration-index datoms, whose permutation reconstructed exact tuples.
These counts were independently enumerated after seeding.

All identities shared one subject and relation. The first 999,980 v9 identities
had exclusive end 0; the last 20 were unbounded. Evaluation time was 10.
Transactions contained 2,000 identities; 500 batches seeded in 58.83 seconds.
Queries used one fixed basis (`999503`), three untimed warmups per operation,
and sequential timed samples. No concurrent workload or cold-peer result was
measured. Results below include raw candidate processing only, without EACL
request admission, full integrity proofs, Caveat evaluation, or cursor signing.

| Raw operation | Samples | Median | Min–max | Candidates/results |
| --- | ---: | ---: | ---: | --- |
| v7 first stored page | 11 | 0.021083 ms | 0.019958–0.037459 ms | 20 returned |
| v9 first stored page | 11 | 0.024041 ms | 0.022875–0.027916 ms | 20 advanced / 20 returned |
| v9 first effective page | 7 | 111.445917 ms | 109.271958–113.981084 ms | 1,000,000 advanced / 20 returned |
| 1,000 v7 exact raw probes | 11 | 1.817750 ms | 1.697208–2.725416 ms | fixed physical identity |
| 1,000 v9 prefix/group probes | 11 | 4.065000 ms | 3.639708–22.944958 ms | fixed physical identity, up to two candidates |

The v9 effective scan used one `seek-datoms`, checked the selected entity and
attribute, inspected component eight for activity, and stopped after 20 active
results. The prefix probe used a full nil-tailed lower bound, checked the
owner/attribute/first-four group, and inspected up to two values for duplicates.
The exact v7 probe used `datoms :eavt` with the complete four-slot value.

**Conclusion:** one seek does not bound work independently of inactive endpoint
degree. Confidence is high in the counts and this counterexample; moderate in
these warm machine-specific timings. Public v9 latency, cold-cache behavior,
segment density, durable space/write amplification, and cross-backend or CEL
conformance remain unmeasured. These numbers are not release acceptance budgets.

### External contract checks

The official [Datomic tuple specification](https://docs.datomic.com/schema/schema-reference.html)
confirms the 2–8 scalar component limit and nil-low ordering. The official
[AuthZed Caveat contract](https://authzed.com/docs/spicedb/concepts/caveats)
confirms bound-context precedence, qualifier-independent identity, and string
transport for 64-bit integers. The official
[expiration contract](https://authzed.com/docs/spicedb/concepts/expiring-relationships)
confirms that an expired but uncollected relationship conflicts with CREATE.
These checks do not substitute for the pinned Phase 2 reference corpus.
