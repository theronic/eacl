## Context

The selected database now has one v9 endpoint stream. An edge carries an opposite endpoint eid and a trailing qualifier eid. Phase 2 supplies immutable qualifier entities and a certified Caveat subsystem, but public writers/readers and traversal still reject non-`nil` qualifiers.

The existing engine is demand-driven, proof-backed, and highly sensitive to unnecessary allocation and I/O. This design therefore adds one qualification boundary rather than duplicating permission operators or wrapping the full engine in a temporal database filter.

## Goals / Non-Goals

**Goals:** zero qualifier reads for ordinary edges, one bounded resolution per distinct qualified edge, certified atomic qualifier publication, SpiceDB-like conditional Caveat results, exclusive expiry, trusted time capture, sound managed/exact caching, stable cursors, formal-first implementation, and measured hot-path simplicity.

**Non-Goals:** `valid-from`, recurring schedules, multiple independent grants, multiple Caveats per edge, physical expiry GC, globally shared qualifier entities, transaction-metadata validity, a second Relationship stream, or runtime execution of the formal model.

## Decisions

### 1. Prove traversal and cache semantics before changing production routing

Before any production engine/operator/cache source is edited, extend the abstract models with:

- edge values `(opposite-eid, optional qualifier)`;
- immutable qualifier resolution and authoritative faults;
- Caveat true/false/conditional outcomes and residual/missing fields;
- result composition for union, intersection, exclusion, arrows, and positive recursion;
- exclusive expiry at one captured evaluation time;
- witness/certificate intervals that bound when a result may change from time alone;
- cache reuse requiring both database proof and temporal/context compatibility;
- cursor continuation with explicit pinned/live temporal mode, authenticated context, and certified reuse intervals.

Proofs and mutation controls must be green within locked resource budgets before implementation. The production bridge and killed-mutation certification are completed after implementation and before routing activation. Models stay in verification/test tooling and do not execute in request paths.

### 2. Add one compact edge-qualification seam

Backend scans must make slot five available to shared evaluation. The backend-neutral scan contract evolves from “opposite eids only” to a compact aligned representation of opposite eid plus optional qualifier eid. The implementation may use pairs, parallel primitive-friendly vectors, or an equivalent measured shape; it must not require one persistent map allocation per ordinary edge.

One shared function conceptually performs:

```clojure
(qualify-edge request edge)
```

- `qualifier-eid == nil` returns definite active membership immediately;
- non-`nil` resolves/validates the qualifier;
- expiry is checked before Caveat program work;
- Caveat evaluation returns definite, absent, or conditional evidence;
- faults remain distinguishable from ordinary false membership.

Permission operators consume annotated evidence, not a second traversal engine. Existing order, demand bounds, cancellation, deadlines, and cursor position continue to be defined by the one endpoint stream.

### 3. Capture trusted evaluation inputs once

Each top-level client operation captures one request context containing:

```clojure
{:basis selected-immutable-snapshot
 :evaluation-time-ms trusted-clock-sample
 :caveat-context canonical-request-context
 :evaluator-fingerprint ...}
```

A public request context may contain inputs for several named Caveats. EACL canonicalizes and bounds the complete map once, then supplies each demanded Caveat only its declared parameter names. Type validation applies to that projection and to the strict Relationship-bound map, with bound-value precedence. The Phase 2 direct evaluator remains strict about unknown keys in its per-Caveat inputs. Unused public fields remain part of the complete authenticated cache/cursor identity; projection does not permit context aliases.

The public `:caveat-context` map uses valid parameter names and the profile's portable scalar, homogeneous scalar-vector, or string-keyed scalar-map values. It retains the 16 KiB and 1,024 total-entry ceilings across the complete map, plus the existing string and container bounds. Its top-level field count is independent of one Caveat's 32-parameter declaration limit. Admission precedes snapshot acquisition and cache lookup, including empty batches; per-item batch context overrides are invalid. An unchanged prepared context is shared through transient snapshot delegation, while a changed context is validated anew.

An explicit EACL snapshot freezes the database basis and evaluation time; Caveat request context remains an explicit operation input unless the public snapshot API intentionally binds it. A batch shares one captured time. The filter/evaluator never calls the wall clock per datom or per recursive step.

The clock is an injected client dependency for tests and operations. EACL samples it only at the top-level request boundary and applies a process-local non-decreasing high-water mark: a raw backward step cannot produce an accepted evaluation time below the last accepted value. Production documentation still requires synchronized clocks across Peers and explains uncertainty/restart limits. A backward clock step can delay time progression or fail a request under a configured strict policy, but it never silently revives an already expired grant inside one client lifetime.

### 4. Resolve and cache immutable qualifiers safely

Every request owns a bounded map keyed by qualifier eid. A distinct qid is fetched/decoded at most once for the complete operation, including recursive revisits. The nil path does not touch this map.

A longer-lived qualifier decode cache may reuse immutable content under a source-scoped key such as:

```clojure
[source-lifecycle qualifier-eid qualifier-version format-version]
```

The adapter normalizes `qualifier-version` from the mandatory format marker's assertion `t` or the Phase 2 certified persisted equivalent. Supported writers never mutate or reuse a qualifier eid; `:touch` creates a new qid and changes both endpoint tuples, which advances the owning Relation version. An implementation may omit a repeated qualifier-entity read on a managed cache hit only when that supported-writer/non-reuse contract and the owning Relation proof are present; otherwise it validates exact content.

Safety tiers:

- exact immutable snapshots may cache by exact source/basis and qid;
- managed supported-writer sources may reuse by lifecycle/qid/qualifier-version because entity ids are not reused inside the lifecycle and immutability is enforced;
- unknown-writer sources require an exact basis or a database-visible content proof covering every qualifier field and definition dependency;
- a qid-only process cache is forbidden.

Qualifier cache entries contain decoded data only. Every request still compares its captured time with `valid-until` and evaluates Caveat bindings; cached data is not cached authorization.

### 5. Enforce qualifier validity before semantics

A non-`nil` ref must resolve to one supported, internally consistent qualifier whose Caveat is allowed by the selected Relation branch. Missing entity, wrong format, context without Caveat, detected ownership/immutability violation, invalid generation, unknown Caveat, malformed context, or out-of-range expiry produces a typed authoritative fault.

Single ownership and immutability are admission/proof invariants, not reasons to perform a reverse graph scan on every edge. Supported writers allocate a fresh qid and update both tuple halves; offline integrity and certification detect sharing; unknown-writer cache paths use exact/content proof. The ordinary resolver reads only the referenced qualifier and directly required Caveat/schema data.

For a positive grant edge, fault cannot grant. For a subtracting/deny edge, silently treating fault as absent could grant, so the full authorization operation returns an error/indeterminate failure rather than erasing the negative evidence. `can?` fails closed to false only at its compatibility boundary while diagnostic APIs preserve the error category; server integrations should not conflate faults with ordinary denial.

### 6. Compose Caveat permissionship through the existing algebra

The internal value is an evidence object capable of representing:

```text
true
false
conditional(residual, missing-fields)
fault(reason)
```

Composition follows the formal model:

- union is true if any child is true; otherwise conditional if any residual can become true; otherwise false;
- intersection is false if any child is definitely false; true only if all true; otherwise conditional;
- exclusion `L - R` is true only when L is true and R false; false when L false or R true; otherwise conditional according to residual Boolean `L && !R`;
- arrows qualify each edge and downstream result without changing endpoint order;
- recursion uses bounded canonical residual/evidence merging and terminates under existing recursion limits;
- any authoritative fault propagates rather than becoming Boolean absence.

Public lookups default to definite-only SpiceObject results. `:result-policy :detailed` returns items such as `{:object <SpiceObject>, :allowed? false, :permissionship :conditional-permission, :missing-fields ["flag"], :residual <canonical-evidence>}`. The object retains its closed reusable endpoint shape. The complete root result controls filtering; conditional interior paths remain eligible to combine into a definite root grant. Qualified direct Relationship filters intersect their own evidence with the permission result. Filtered inclusive lookahead recovers complete root evidence at the selected basis when the cursor retains only a structural sentinel. Detailed results survive rendering and range reuse, and both internal and rendered cache ingress reject inconsistent permissionship, missing fields, residuals, or result policy.

Public counts default to `:result-policy :definite`. Explicit `:detailed` counts return `:count`, `:definite-count`, and `:conditional-count`; the categories sum to the reported count and exclude the capped lookahead. Missing anchors retain both zero categories. Invalid policies are rejected before capturing time or acquiring a basis, and exact count cache ingress validates the closed category shape against the request policy.

Recursive operator candidate enumeration projects compact qualified scans onto structural endpoint identities after the existing scan-response cache. This positive cover deliberately retains expired and conditional candidates; the bounded aligned recursive evaluator owns the complete authorization decision. It receives the request qualification context, preserves faults, and applies the public result policy only after the complete root decision. Ordinary scan chunks keep their existing vector. Qualified rows remain in the scan cache, so structural projection cannot replace evidence-bearing cached data with endpoints alone.

Relationship-bound context overlays request context before evaluation. Final detailed checks expose SpiceDB-style permissionship and missing context. `can?` returns true only for definite true.

For recursive first-discovery enumeration, the existing admission identities and stack remain authoritative. Ordinary admitted work continues to use the existing set. A sparse evidence table records only admissions whose incoming value is not timeless true. A changed union of incoming evidence schedules further propagation; updates to already queued work coalesce at its existing stack position. Weighted stack slots retain only admission identities; the pending table owns the latest work item, so an obsolete stack entry cannot retain unaccounted old evidence. A scan whose incoming evidence changes must revisit its prefix with a new buffer revision, so a physical buffer from an earlier prefix cannot skip newly possible derivations. Admission, stack, transition, and evidence limits apply before committing these updates. The combined retained admission/result evidence pool defaults to 16,777,216 conservative storage units, including atom strings; window compaction removes result annotations with their backing vector, and count sinks retain no emitted evidence history.

An emitted conditional path is a lower bound on the complete root result. The existing point evaluator completes that result before public projection, sharing the request's qualifier memo; a definite witness needs no completion probe. Each endpoint is emitted or filtered once. Checkpoints retain the evidence needed for queued work and the complete qualified request scope, and discard physical buffers as before. This extends the current discovery machine while preserving its ordinary path and physical chunk independence.

First-discovery pages retain only sparse delivered evidence and explicit evidence for the single pending lookahead, including timeless true. Incomplete or faulty retained lookahead and mismatched checkpoint scope cause a replay miss. Qualified checkpoint identities include exact request scope and result policy. The standalone page token binds a compact digest of that scope; public Relay live-time intervals remain the separate cursor contract below.

### 7. Implement exclusive `valid-until` as native qualifier semantics

A qualifier with no bound is time-unbounded. A finite bound is active exactly when:

```text
evaluation-time-ms < valid-until-ms
```

At equality the edge is expired. Passing time does not mutate the database or Relation version. Expiry is evaluated before Caveat work; an already expired Caveated edge does not compile/evaluate its Caveat for that request.

Expiry applies to every edge role. An expiring grant may remove permission; an expiring ban/subtracting edge may add permission; an expiring intermediate arrow may change reachability. Therefore cache and cursor logic cannot assume time only narrows access.

### 8. Carry a temporal stability certificate with reusable evidence

Each evaluated subproblem/result has a half-open time interval within which its value remains equal if schema/Relation/qualifier proofs and request context remain equal. With no `valid-from`, the lower bound is normally the captured time and the relevant upper bound is a future expiry or infinity.

The model proves witness-aware operator rules so demand-driven evaluation need not scan every possible edge solely for a global minimum expiry:

- true union may use one true witness certificate;
- false union combines complete false child certificates;
- true intersection combines all true child certificates;
- false intersection may use one decisive false child certificate;
- exclusion combines the decisive evidence for its actual outcome;
- recursive certificates follow the certified fixed-point evidence graph.

The conservative composition is interval intersection/minimum upper bound over the evidence required by that outcome. Missing completeness means no managed temporal publication, not an extra full traversal.

A reusable cache entry is accepted only when:

1. source, schema, Relation, identity, evaluator, and qualifier proofs match;
2. canonical request Caveat context scope matches;
3. requested evaluation time lies inside the certified interval; and
4. the entry kind preserves definite/conditional/fault distinctions.

Timers and eager eviction may improve memory/latency but are never authorization correctness inputs.

### 9. Keep Caveat/result cache identities complete but lean

Final and subproblem keys include a bounded digest plus collision-checked canonical identity of request Caveat context and evaluator/profile. Relationship-bound context is covered by qualifier identity/content proof; Caveat source/parameters/profile are covered by schema proof.

The program cache remains separate and is keyed only by definition/profile identity. A program hit can be reused across request contexts but cannot authorize on its own. Conditional residuals and missing-field sets are part of the cached value and cannot alias definite results.

### 10. Give cursors explicit pinned or live temporal semantics

A qualified cursor authenticates:

- pinned-versus-live temporal mode;
- selected source/basis or recoverable dependency proof;
- storage/ordering/evaluator ABI;
- original evaluation time and the cursor state’s certified temporal interval;
- canonical request Caveat context identity;
- detailed/Boolean result policy;
- ordinary traversal boundary and continuation proof.

An explicit EACL snapshot produces a **pinned** cursor. Every page retains that snapshot’s database basis and evaluation time; later wall time does not reinterpret it. Exact-basis availability, ordinary cursor TTL, key availability, and dependency checks still apply.

A client-targeted lookup produces a **live** cursor. On every resume EACL captures one fresh trusted time and may reuse the retained frontier only when that time is at or after the certificate start and strictly before every finite deadline, with equal ordinary dependencies and Caveat context. Reaching or leaving the certified interval returns a typed restart requirement and no resumed page. The caller starts a new lookup to obtain the current view; EACL never silently applies the old boundary to a new temporal graph.

The cursor certificate covers every examined/emitted/skipped candidate before the public boundary plus retained frontier, lookahead, conditional residual, and subtracting evidence. This is sufficient to prevent an expired ban from making an earlier identity newly visible behind the boundary. Unexamined candidates after the boundary may be evaluated at the fresh live time. If the evaluator cannot produce a complete certificate for the retained state, it does not permit cross-time continuation; it does not perform an extra unbounded traversal solely to manufacture one.

Request Caveat context is frozen/authenticated for both modes. Changing it starts a new lookup. Cursor token TTL and security-key retirement remain separate operational constraints.

### 11. Preserve logical Relationship mutation semantics

Public write input may now include one optional Caveat/bound context and one optional `valid-until`. Normalization creates `nil` or one fresh qualifier entity. `:create` conflicts on first-four identity regardless of qualifiers. `:delete` uses identity only and removes the current owned qualifier.

Qualified writes are enabled only when the adapter advertises one Phase 2 publication strategy. An inline-capable backend may create and attach the qualifier in one transaction. A prepared-reference backend may first create an unreferenced inert qualifier, then atomically publish or swap both endpoint values, the Relation stamp, the old qualifier retraction, and any caller-composed application datoms using its concrete eid. The publication transaction is the semantic commit point; a failed prepare/attach can leave only an orphan qualifier, never a visible half-edge. `eacl/tx-relationship` requires a prepared handle on such a backend rather than hiding an extra transaction inside returned tx-data.

No two Relationships differing only by Caveat, context, or expiry may coexist, matching SpiceDB's Relationship identity behavior. If independent grants are required later, that is a grant-assertion entity feature, not tuple duplication.

The public Relationship map uses `:caveat` (one declared string name), `:caveat-context` (bound portable context), and `:valid-until-ms` (exclusive UTC milliseconds). Identical batch intents coalesce before native allocation; different qualifiers on the same identity conflict. `write-relationships!` also accepts `{:updates [...] :tx-data [...]}` to commit application datoms with the final endpoint publication. Application datoms cannot alter EACL state or allocated qualifier entities. Both endpoint identity guards, one schema fence, and one generation fence per affected Relation protect the final batch.

For caller-managed transaction composition, `prepare-relationship!` creates an inert qualifier and returns an opaque handle. The caller obtains a snapshot after preparation and supplies the handle as `:prepared-qualifier` on the update passed to `tx-relationship`. The pure snapshot planner validates source, Relationship identity, schema generation, exact immutable facts, and equality with the requested qualifier value. Snapshots retain pure planning functions and never a connection or writer. `discard-prepared-relationship!` removes an unchanged, unattached handle through its original writer; attached or altered entities cannot be removed through this API. An ordinary Relationship needs no preparation and returns `nil`.

`tx-relationships` plans multiple updates together against one snapshot, coalesces identical intents, and emits one fence per schema/Relation alongside all endpoint guards and optional application datoms. Callers use this batch planner when composing multiple mutations. Inline backends accept semantic qualifier values directly; prepared backends use the individual preparation handles. Datalevin uses its native unique temporary-ID allocator so independent pure plans cannot accidentally share an entity.

### 12. Keep physical collection out of correctness and out of this phase

Expired Relationships remain stored and visible to administrative stored-state reads, create conflicts, deletion, history, and integrity inspection. Authorization treats them inactive at the captured time.

No derived expiry index, scheduler, background job, or GC API is required here. A later maintenance change can add a bounded collector with exact current-pair revalidation and Relation stamping after real retained-data measurements justify it.

### 13. Gate activation on refinement and performance evidence

After implementation but before the route/capability epoch is enabled:

- production qualifier resolution, Caveat algebra, expiry boundary, temporal certificate, cache acceptance, and cursor scope must match generated/refinement oracles;
- mutation controls must kill omitted qualifier reads, wrong boundary (`<=`), context precedence inversion, conditional-as-true, fault-as-absence, missing Relation stamp, unsafe q-cache key, and expired-ban cache reuse;
- public cached results must equal uncached evaluation on the selected temporal/context view;
- 0%, 5%, and 10% qualifier workloads must satisfy recorded read/allocation/latency budgets, including concentrated qualified prefixes and negative/exclusion paths.

The nil fast path is inspected and measured directly. No runtime model evaluation, shadow engine, duplicate full permission check, or per-edge proof recomputation may be introduced to satisfy certification.

## Risks / Trade-offs

- **Qualifier indirection is slower when most edges are qualified** → optimize for stated 5–10% sparsity and measure concentrated endpoints; revisit inline data only with evidence.
- **Partial Caveat algebra is complex** → keep one canonical evidence type and formally prove operator composition before implementation.
- **Temporal cache certificates can reduce reuse** → witness-aware rules retain safe demand-driven reuse; exact evaluation remains fallback.
- **Clock skew affects boundary behavior across peers** → capture once, use a process-local non-decreasing high-water mark, require synchronized clocks, document uncertainty/restart limits, and keep explicit snapshot semantics.
- **An explicit pinned snapshot can preserve a past grant after wall-clock expiry** → make client-targeted live mode the ordinary access-control default and document pinned temporal snapshots as intentional historical/simulation semantics, not a live-session lease.
- **Pinned cursors are historical views and live cursors can cross a temporal boundary** → make modes explicit and require a typed restart/new query whenever live certification ends.
- **Fault propagation can surface more errors than old Boolean checks** → preserve Boolean fail-closed compatibility while exposing structured diagnostics and preventing negative-evidence erasure.
- **CLJS Caveats require an external evaluator** → capability-check before activation; expiry-only qualifiers remain portable.
- **Some backends cannot resolve newly allocated refs inside tuple values** → activate only a certified inline or prepared-reference writer; keep capability selection out of per-edge reads and treat prepared orphans as non-authoritative storage.

## Migration Plan

1. Complete Phases 1 and 2 and verify no serving client can yet accept non-`nil` qualifiers.
2. Complete the extended formal models and mutation controls; do not edit production engine/cache/cursor code before the formal gate is green.
3. Implement edge scan qualification, request context, qualifier resolver/cache, Caveat algebra, expiry, certified inline/prepared writes, reads, temporal cache certificates, and pinned/live cursor scope behind a disabled semantic capability epoch.
4. Complete production refinement, differential uncached/cached tests, SpiceDB Caveat fixtures, cross-backend conformance, and performance gates.
5. Upgrade all serving peers to code capable of the new epoch, verify every enabled backend advertises a certified qualifier-publication strategy and evaluator where required, then activate qualified writes/authorization in a coordinated schema/capability change.
6. Rollback disables qualified writes and returns to the pre-activation database/schema. A database containing qualified Relationships cannot be safely served by Phase 1/2 readers.

### Schema admission and replacement

The disabled qualified epoch now admits named Caveat alternatives through the
public committed and speculative schema paths. A matching evaluator is required
when any Relation names a Caveat, even when the requested Relation is ordinary
or the store has no Relationships. Unused named definitions and expiry-only
schemas do not require an evaluator. Bundled adapters advertise exactly one
certified inline or prepared-reference publication contract; request context
construction checks it before answer-cache lookup. Datahike remote writers do
not advertise the prepared-reference contract.

A typed Relation keeps its eid when its alternatives change. Replacement checks
both of its stored endpoint streams, validates exact pair symmetry and qualifier
contents, and rejects alternatives that invalidate retained data, including
expired data. Only removed identities are entity retractions. Optional Caveat
facts are retracted separately, and every changed Relation has a native commit
guard. Datalevin captures validation, qualifier reads, and commit generations
inside one owned native read snapshot and releases it before transaction
submission. Its schema scan consumes bounded AVET batches across the complete
Relation prefix, preserving rows with equal endpoint tails at batch boundaries.

### Implemented physical inspection and work-bound contract

`read-relationships` accepts `:relationship-state :stored` (default) or
`:relationship-state :expiry-active`. Qualified responses retain canonical public
qualifier metadata and label their mode and captured `:evaluation-time-ms`.
Native scan rows carry private aligned Relation/qid fields only until the shared
bounded decoder externalizes them. Expiry-active acceptance uses the existing
candidate window and continuation machinery; expired candidates spend that
window even when no row is emitted. Physical inspection never compiles or
evaluates a Caveat and therefore does not require an evaluator unless an
`:authorization` filter also requests a permission decision.

All four native backends retain forward/reverse/partial/exact scan semantics.
The ordinary Datalevin scan arity retains its numeric native bound. Qualified
cache ingress validates canonical metadata without narrowing the pre-existing
ordinary boxed-value contract. Datomic schema publication resolves newly created
Caveats by their transaction tempids when constructing Relation alternatives.
Shared tests cover expiry equality, expired identity conflicts, renewal,
shortening, and expiry removal through immutable touch. Existing compact scan
cache, weighted reducer, checkpoint/head, dimensional-budget and candidate-window
conformance is complemented by stopping after a native qualifier read when the
absolute execution deadline or cancellation token fires.

### Implemented decode reuse proof strength

The optional client decode cache shares one bounded LRU capacity between exact
and complete-content indices. Exact identity retains the full native basis;
content identity retains source lifecycle/branch, Relation eid and complete
content, qid, marker assertion version when available, qualifier format and
complete content, and the named Caveat entity. Raw native writers remain possible
on the bundled backends: atomic publication capability therefore does not imply
a source-wide non-reuse contract. Cross-basis decode reuse currently takes the
stronger complete-content fallback and performs bounded entity reads. It never
skips those reads based only on a qid, assertion marker, or Relation stamp.

Both indices retain decoded data only. Per-request context and expiry evaluation,
request-local shared fetch memoization, request cache controls, source rotation,
and speculative no-publication rules remain independent. A single capacity
counts both indices, and each points to the same immutable decoded value.
Portable and native traces compare warm and uncached outcomes after lifecycle
reset, deletion, unchanged-marker mutation, Caveat replacement, and Relation
allowance change. No global ownership scan or authorization result is introduced
into this structural tier.

### Implemented point-answer temporal acceptance

Point-answer cache keys retain exact source/basis and canonical context/evaluator
scope but remove request time. Values use the versioned temporal-point envelope:
original time, exclusive deadline, completeness, permissionship, and canonical
encoded evidence. Ingress checks the interval and classification against that
evidence. Resident acceptance checks the captured request time independently of
retention; missing completeness permits only the original exact basis/time.
Operator evidence retains its proven witness-aware deadline rather than a blanket
minimum over every observed qualifier.

A later miss can replace an expired or incomplete interval through a conditional
expected-value replacement. Validation and replacement eligibility run outside
atomic storage operations; a concurrent publisher changes the expected identity
and wins. Older pinned computations do not replace newer intervals. This keeps
expired-but-resident entries harmless and allows an expired ban to grant on a
later request without a write. Qualified managed answers remain disabled, and
page/count/range/checkpoint reuse remains partitioned by exact time. Public
continuations use the certificate rules described below.

### Implemented public continuation certificates

Qualified cursor scopes bind canonical whole-context and evaluator/profile
identity, pinned/live mode, result policy and adapter ABI. An authenticated
closed temporal envelope carries original and current interval start times,
exclusive deadline and completeness. It is checked before traversal and exact
basis recovery cannot rebase the captured live time. Ordinary cursor scope and
encoding stay unchanged; qualified tokens without the certificate are rejected.

A sparse request ledger records active qualifier deadlines during existing
bounded fetch/evaluation work. Expired qualifiers are stable going forward.
Accepted denotation evidence and complete recursive replay decisions import
their witness certificates. Stable first-discovery checkpoints store and validate
a certificate for their whole retained state before importing it. Page/count
answers use a versioned certificate-bearing cache shape, and range slices,
compositions and continuation-only hits retain the combined certificate. The
existing exact-time cache scope remains conservative while live cursor resumption
can cross time within the certified interval. Incoming prefix certificates are
intersected with each resumed page and never extended. No extra graph traversal
or all-qualifier pass constructs these proofs.

Stored physical inspection has an unbounded temporal interval. Other producers
without a complete proof explicitly emit an incomplete certificate; future-time
resume returns a typed restart rather than silently restarting. Public results
strip internal certificate fields. Tests cover both directions, forward/backward
pages, demand/complete evaluation, expired bans before a boundary, changed
context/evaluator/policy/mode, incomplete proof, range and checkpoint retention.

### Completed cache coherence scope

The active cache paths use complete exact-basis qualification identity rather
than promoting incomplete ordinary Relation proofs. This exact fallback is the
implemented managed policy for qualified answers on the current bundled native
adapters: external writers prevent a source-wide immutable-qualifier guarantee.
Publication capability alone never enables cross-basis authorization reuse.
Point intervals permit certified time reuse; other semantic tiers retain exact
time as a conservative guard and carry their evidence/continuation certificates.
Raw aligned scan rows and structural schema plans remain independent of time.

One portable native conformance trace compares cold, warm, repeated and
read-only-cache outcomes through all four backends (both Datahike modes). Eight
states cover two ban expirations, positive expiry, touch, bound context, Caveat
replacement and source lifecycle rotation. Every state exercises three request
contexts, five permissions, both lookup directions and result policies, and
limited/unlimited counts. Separate DataScript traces cover unknown native
qualifier mutation/deletion faults and restored entity identities after explicit
lifecycle rotation. Datalevin persists a lifecycle replacement and recreates its
client. Controls omitting time from shared denotation identity or context from
answer identity fail these same public traces.

### Qualified deletion and existing integrity dependencies

Qualified object deletion now consumes exact native half-retractions and fits
whole pairs plus owned qualifier cleanup into the final transaction limit.
Selected-basis validation, schema and Relation fences, and exact qualifier facts
precede all removals. Conflicting or duplicate identities and missing/malformed
qualifiers fail before submission; an already missing endpoint or peer can be
cleaned from the surviving exact half. Unattached preparations are preserved.

Datalevin's ordinary deletion operation is a commit-time rescan function, so it
cannot be treated as a half-retraction stream. Its qualified data adapter exposes
selected-snapshot pair retractions and unwraps that same owned selection for the
shared native cleanup planner. It does not select a second basis. Submission
occurs after releasing the read, and the native basis assertion rejects drift.
Transaction sizing includes all guards, expanded peers and qualifier cleanup.
Native adapters that already materialize object retractions continue to do so;
this change does not claim a new bounded-memory scan implementation for them.

The existing integrity proof captures qualifier facts/version, Caveat definition
content, Relation definition/generation and source/schema identity. Cache traces
exercise the corresponding exact/content fallback against unknown native writers.
The new deletion contract checks integrity after every bounded native commit on
all backends, including both Datahike modes, preserves inert orphans and rejects
stale plans after unstamped identity changes and qualifier replacement. Additional
DataScript coverage removes an endpoint out of band, cleans its surviving peer,
and verifies that referenced expired qualifiers block Caveat removal until their
owned Relationship cleanup completes. Subtracting qualifier faults remain covered
by the cross-cache conformance traces.
