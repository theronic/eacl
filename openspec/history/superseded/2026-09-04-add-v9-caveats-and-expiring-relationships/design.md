## Context

See [proposal.md](proposal.md) for scope and the [historical review](../2026-09-03-add-v9-qualified-relationship-tuples/review-2026-09-04.md) for measured evidence and rejected alternatives. The v8 engine currently uses four-component v7 Relationship tuples. Its endpoint-local ordering, logical identity, paired mutation, Relation stamps, proofs, and cursors all depend on that representation.

The previous eight-slot proposal was not implemented. This change replaces it directly; it is not an upgrade from a released eight-slot v9. The permission representation remains separate from the Relationship storage version.

## Goals / Non-Goals

**Goals:** retain covering forward/reverse adjacency, keep qualifiers out of logical identity, make expiry independent of timely writes, and preserve sound authorization/cache/pagination behavior across all supported adapters. Reserve Caveat positions in the first release and deliver a qualified Caveat evaluator subsequently.

**Non-Goals:** scheduled activation, recurring schedules, transaction-metadata validity, schedule-ref entities, multiple independent grants per logical edge, multiple Caveats per edge, general bitemporal storage, an automatic migration, or a mandatory global expiration index. The remaining eighth Datomic tuple position is not assigned by this proposal.

## Decisions

### 1. Freeze one seven-component endpoint ABI

```clojure
;; Stored on subject-eid:
[subject-type relation-eid resource-type resource-eid
 caveat-eid caveat-context-eid valid-until-ms]

;; Stored on resource-eid:
[resource-type relation-eid subject-type subject-eid
 caveat-eid caveat-context-eid valid-until-ms]
```

Persisted attributes:

```clojure
:eacl.v9.relationship/subject-type+relation+resource-type+resource+caveat+caveat-context+valid-until
:eacl.v9.relationship/resource-type+relation+subject-type+subject+caveat+caveat-context+valid-until
```

Datomic Pro, Datahike, and Datalevin tuple declarations use:

```clojure
[:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref
 :db.type/ref :db.type/ref :db.type/long]
```

DataScript stores the same fixed-length ordinary vector with equivalent validation. All optional slots remain present. A permanent uncaveated Relationship has three trailing nils. Slot six without slot five is invalid. There is exactly one authoritative tuple on each endpoint; no separate expiration datom is required.

Owner plus components one through four identifies an endpoint-local Relationship. The logical cross-direction identity includes both resolved endpoints, Relation, and the existing wildcard/subject-relation codec state. Never use the first four tuple components without the owner as a global key. Qualifiers can change; they cannot create a second assertion identity.

Two endpoint values serve two adjacency access patterns. Putting all qualifiers in a single value is possible and is exactly what each half does. Removing the reverse half would remove the existing resource-owned covering index. Both halves carry the deadline so reverse traversal needs no forward lookup just to determine expiry.

A two-slot Caveat representation avoids a binding entity for Caveats with empty bound context. Seven components suffice for the requested semantics. Neither storing a start bound that is always nil nor reserving an eighth semantic field is needed.

### 2. Keep qualifiers after the opposite endpoint

Within an endpoint's attribute, the first three components define the typed Relation prefix and component four orders the opposite endpoints. A hop uses one authoritative candidate stream. Caveat/context/expiry fields arrive with that candidate; non-caveated expiry testing adds a scalar comparison, not a separate entity lookup.

Logical-identity probes now use prefix/group access rather than assuming callers know the full tuple. Seek/rseek bounds use the full seven-component shape and backend-certified low/high qualifier sentinels. Nil is a low sentinel, not a descending upper sentinel. Test negative timestamps, extrema, and inclusive/exclusive continuation over an entire identity group. Stop on entity, attribute, arity, and prefix boundaries.

Let `D` be the stored degree of the selected adjacency and `k` the candidates advanced to obtain a page. Cost includes index positioning plus processing `k` candidates and any proof/context/evaluator work. It is not bounded by the output page size. A trailing expiry field cannot skip an arbitrary expired prefix. Nor does a low global expiring percentage bound the local percentage within a queried prefix.

The previous million-edge 111.445917 ms probe deliberately advanced a million candidates to return twenty. The evenly distributed 5% probe instead saw small warm pages around 9–15 microseconds for cached/inline variants, with materially different full-scan costs. These establish different workloads, not a contradiction or a seven-slot service-level target.

Leading expiry would order adjacency by time instead of opposite endpoint and break existing pagination. A separate time-leading maintenance index could support global expiry ranges, but it would add maintained data. Initial collection uses bounded scans; a later measured need can justify an independently versioned derived index without changing this ABI.

### 3. Define expiry as one exclusive end

A normalized finite deadline is an exact UTC epoch-millisecond integer in `[-9007199254740991, 9007199254740991]`. Nil is unbounded; maximum integer is still a finite exclusive deadline. Reject fractional, out-of-range, or precision-losing input rather than round it.

For a well-formed stored edge in the selected database:

```clojure
(or (nil? valid-until-ms)
    (< evaluated-at-ms valid-until-ms))
```

There is no `valid-from` field or hidden activation derived from assertion transaction time. Reject start-bound/schedule inputs explicitly. A Relationship is eligible immediately when present in the selected committed or speculative database, subject to expiry, Caveats, and the permission expression. Consumer scheduling means deciding when to transact its creation; ordinary consistency determines when a reader selects a basis containing it.

An absolute expiry is not extended when a consumer's delayed creation job finally runs. Admitted already-expired Relationships remain stored and immediately inactive; they retain ordinary identity/conflict semantics until replaced or deleted. Applications wanting to renew use `:touch` with an explicit new deadline. This makes import, replay, and delayed submission deterministic and avoids an implicit lease-from-commit interpretation.

Filter expiry at the edge boundary in both directions, before context loading/CEL evaluation and before the edge enters union, intersection, exclusion, arrows, recursion, lookup, or count. “Expired” means absent as evidence, not “authorization must deny”: removing negative evidence may grant.

### 4. Preserve guarded identity mutation

`:create` conflicts with any stored state of the identity, including an expired assertion. `:touch` creates when absent or replaces the exact old pair and changed bound context atomically. `:delete` requires identity only. A semantically unchanged touch can be a no-op and must not allocate a new context just to force an assertion transaction.

Resolve aliases before batch deduplication. Identical repeated updates collapse; conflicting updates of one resolved identity fail admission. Different identities can have different expiry deadlines and Caveats in the same transaction.

Guard current identity/pair/context state at commit, using the backend's transaction-time guard or a Relation-generation CAS with replanning. A serialized stale plan is not safe: two touches retracting the same old tuple and adding different replacements can create two qualifier variants. Datomic set uniqueness is on the entire value, not its prefix. Unsupported writer topologies reject the write; they do not weaken to plan-time checking. The same guard contract applies to public transaction helpers and speculative execution.

Each actual Relationship mutation stamps every affected Relation once per transaction. The Datomic persisted attribute is `:eacl/relation-version`; its existing writer turns ordinary stamps into CAS guards. Renewal changes the tuple, so no redundant-assertion override is needed. A guarded exact replacement is one transaction and has no committed absence gap.

### 5. Share immutable expressions and keep bound context sparse

Separate a named Caveat definition from the expression it executes. The named definition is the schema identity used by `with <caveat>` and tuple slot five. It references an immutable, interned expression entity. Multiple names with identical expression content can therefore share a program without conflating their schema permissions or lifecycle. The conceptual representation is:

```clojure
;; Named definition; expression-eid is an ordinary entity reference.
{:db/id caveat-eid
 :eacl.caveat/name canonical-name
 :eacl.caveat/expression expression-eid}

;; Shared, immutable expression; payload is a bounded canonical encoding.
{:db/id expression-eid
 :eacl.caveat-expression/key expression-key
 :eacl.caveat-expression/payload canonical-expression-payload}

;; Decoded payload shape, not a native nested-map datom:
{:format :eacl.caveat-expression/v1
 :source exact-cel-source
 :parameters canonical-typed-parameter-declarations
 :profile semantic-profile-id}
```

Freeze physical attribute types and portable encoding before implementation. The expression key is a versioned digest of the entire canonical payload, not source text alone. Canonical identity preserves exact source text and parameter names/types under a deterministic declaration order; it includes the semantic profile. Different whitespace or logically equivalent formulas need not deduplicate. Caveat name, Relationship identity, expiry, and bound/request values do not enter expression identity. No semantic-equivalence solver is required.

Schema writes validate first, then atomically intern the full payload and reference its eid. Concurrent admissions of identical content must resolve to one expression entity in the selected database. A digest is an index key: compare full canonical content before reuse and reject a collision instead of merging unequal definitions. Implement equivalent interning guards on every backend, including speculative views. An eid is database-local and is not sufficient to identify content across sources, recreated databases, or divergent speculative branches.

A supported definition edit references a new or existing immutable expression and changes the schema generation in the same guarded transaction. It never edits a published expression payload in place. The named-definition reference and complete expression content participate in the selected snapshot's schema proof. Unknown-writer qualification must detect out-of-band reference/payload changes; an old content key or claimed immutability cannot replace that proof. Retained old database views resolve their own definitions and expressions.

Initially retain unreferenced expression entities for reuse; do not add shared-expression garbage collection to Relationship collection. Removing a named definition remains subject to existing in-use guards. An eventual expression collector would need a separate guarded proof of no remaining schema references. A program cache entry is disposable process state and does not establish database ownership.

A non-empty bound context uses one internal entity:

```clojure
{:db/id context-eid
 :eacl.caveat-context/payload canonical-typed-context}
```

The entity has no public object identity, belongs to exactly one logical Relationship, and is immutable after publication. Both endpoint halves reference it. Empty bound context uses nil and allocates no entity. Changed context creates a replacement and retracts the old owned payload atomically with both refs. Expiry-only changes can retain the unchanged context ref. Context is not deduplicated across Relationships, avoiding shared deletion/refcount semantics.

Managed object deletion and collector deletion remove both endpoint halves and owned context. Do not rely on automatic cascading or inverse-reference indexing of refs embedded in tuple values. Shared Caveat definitions and expression entities are never owned by a Relationship or collected with it.

A trusted managed source does not need a new opposite-half read for every traversal candidate simply because the tuple is wider. The admitted-writer and source-certification contracts establish structural invariants. Unknown-writer content proofs still cover both complete halves, endpoint public identity, and referenced context payload; a digest detects changes but does not establish structural validity. A source that cannot establish required integrity fails qualification or performs the necessary scope validation. Count those reads explicitly.

An actually encountered invalid shape, duplicate variant, missing required authoritative payload, or evaluator fault aborts the operation. Do not erase failed evidence and continue through exclusion. Bounded repair can remove discoverable variants; ambiguous context ownership is reported/quarantined, never guessed.

### 6. Deliver Caveats with a qualified portable value model

Phase 2 supports zero or one allowed Caveat per Relationship, validated against the exact subject-type/wildcard/subject-relation branch. Preserve top-level `caveat` definitions and `with <caveat>` grammar. Both bare and Caveated branches allow optional attachment; only a matching Caveated branch makes attachment required.

Capture structurally bounded request context once. Effective context is a shallow parameter-name merge where bound values replace request values. Type conversion applies to the resulting effective parameter, so a shadowed wrong-typed request value is not treated as the parameter. Bound maps replace whole request maps; do not recursively merge them. Unknown bound keys are invalid. Unknown request keys are not expression variables and remain in conservative request identity.

Use **[exoscale/cel-parser](https://github.com/exoscale/cel-parser)**, Clojure coordinate `com.exoscale/cel-parser`, for JVM CEL parsing and interpretation. EACL owns the Caveat/schema grammar, admitted type/profile validation, context conversion, partial-result adapter, permission algebra, and result/error mapping. Preserve a canonical portable typed representation and deterministic bounded evaluation. No ambient clock, I/O, mutable globals, or hidden network calls belong in the evaluator. Any caller-supplied time used by a Caveat is explicit request context and does not replace the trusted expiry clock.

Successful results are has-permission, no-permission, or conditional-permission with missing fields/residuals. Errors and limits are separate typed failures. Boolean `can?` returns true only for has-permission, false for the other two successful states, and propagates errors. Internal algebra never turns conditional or failed subtracting evidence into false prematurely.

Residual identity preserves Caveat definitions and bound values, supports bounded simplification/sharing, and terminates under supported recursion. Equivalent conditional cycles cannot grow formulas without bound. Reject unsupported recursive constructs at schema admission.

The existing secure-format envelope is not a complete CEL value model. Freeze a typed encoding and explicit type/coercion/function profile before Phase 2 activation. Any admitted int64, uint64, double, timestamp, bytes, map/list, or other value must have exact declared semantics on CLJ and CLJS; reject unsupported types rather than quietly narrow them. Record a concrete SpiceDB release/commit with the differential corpus. Reference compatibility remains limited to the tested published profile.

Committed schema changes reject removal of Caveats still referenced by stored Relationships, including expired ones. Reject parameter/type/branch changes that invalidate stored context. Serialize schema guards with Relationship writes. Existing speculative Relation `:retain-inert` behavior does not authorize orphaning a Caveat: retained Relationship data must keep any referenced Caveat definition valid until explicitly removed. This is an intentional stricter rule for Caveat lifecycle.

#### Lazy compilation and client program reuse

Resolve `tuple caveat-eid -> named definition -> expression-eid/content` through the selected snapshot's validated schema catalog. Once traversal reaches a well-formed, expiry-active Caveated edge that actually needs evaluation, obtain or build its client-local program. Client construction, ordinary schema reads, stored Relationship enumeration, expired candidates, and unvisited paths do not eagerly compile their expressions. Schema-write admission still performs bounded syntax/name/type/profile validation before commit; this is separate from populating a client's runtime program cache.

Use `exoscale.cel.parser/make-program` for the reusable parsed program and `eval-for` with fresh effective bindings for each evaluation. Here compilation means constructing the parsed executable representation and EACL's validated evaluation plan, not JVM bytecode generation. `parse-eval` is not the repeated traversal path. Programs and any reusable subexpression plans contain no request/Relationship bindings, residual state, or authorization results.

The bounded client cache key is the complete canonical expression identity plus an evaluator fingerprint covering library/build version, adapter/plan format, numeric conversion, partial/error semantics, admitted options/macros/functions, and extension implementations. A digest lookup verifies canonical content equality. A snapshot-scoped eid-to-content mapping makes repeated lookup cheap, but a bare eid or Caveat name cannot be the program key. Equal content may reuse a program across names or snapshots once each snapshot independently resolves and validates that content. Unrelated schema-generation changes need not discard a pure expression program; answer/plan/cursor proof validation still observes the schema change.

Establish canonical content identity and any digest-collision comparison when admitting or refreshing the validated catalog/cache mapping, then retain a client-local handle to that identity. Warm traversal can index by this qualified handle without recanonicalizing, rehashing, or comparing the entire source for every edge. Revalidate the mapping under the ordinary selected-view proof rules; compact handle lookup cannot hide a changed definition or payload.

Coalesce concurrent misses per key into one in-flight build. Publish only successfully built immutable programs, remove failed in-flight entries, bound resident program count/weight and concurrent build work, and allow eviction followed by equivalent recompilation. Per-caller cancellation must not corrupt shared state or cancel another caller's usable build. Verify concurrent evaluations with different bindings; fresh visitors in upstream source are evidence about construction, not a concurrency certification. Cache misses and compile work count against execution limits and produce typed failures, never a denial.

The extra expression entity is schema-scale indirection, not a new per-Relationship entity or an extra adjacency seek. A cold catalog/program path can need definition and expression reads plus compilation. With the selected schema catalog and program resident, repeated candidates require an in-memory lookup and evaluation with their own bindings; non-empty bound context can still need its own payload read. Qualify those conditions and count reads explicitly. Expression interning reduces repeated compilation and stored duplicate source; it does not eliminate candidate scanning, evaluator work, proof validation, or context reads.

#### cel-parser integration and qualification

Pin an exact `com.exoscale/cel-parser` artifact/version or immutable source build and resolved dependency set before qualification. The source review used commit `66ac2edbe9c196498d9f925ea4bf664ea0c166a2`; its [VERSION](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/VERSION) contains `0.1.8`. This identifies inspected source, not an installed or qualified release. Verify JDK/transitive compatibility in nREPL. A smaller library is not evidence of lower end-to-end latency or integration effort.

The inspected [parser API](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/src/exoscale/cel/parser.clj) separates program construction and evaluation. Its [ANTLR implementation](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/src/exoscale/cel/antlr.clj) constructs a Java parse tree; parsing is not a static CEL type checker. The EACL adapter must validate names, admitted overloads, and Boolean result typing before schema commit and provide the portable typed representation. Do not weaken admission to “parses successfully” or delay malformed-schema discovery until traversal.

By default `eval-for` returns errors as values; [error unwrapping](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/src/exoscale/cel/expr.clj) produces an exception object. EACL must recognize typed error results explicitly or use the verified throwing option and map exceptions. Never interpret arbitrary truthy Clojure values as grants. Only a typed Boolean true is definite true. EACL's missing-context layer must preserve checked expression/binding identity, decisive Boolean branches, residuals, and missing fields; it cannot classify all missing-variable errors as conditional. The inspected API supplies no established unknown/residual protocol, and its [unknowns test file](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/test/exoscale/cel/generated/unknowns_test.clj) contains no cases. Partial evaluation is an implementation gate, not an assumed library feature.

The inspected [visitor](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/src/exoscale/cel/visitor.clj) reparses macro bodies inside collection iterations for `map`, `all`, `exists`, `exists_one`, and `filter`. Caching only the outer `make-program` result therefore does not establish parse-free warm evaluation. For admitted macros, qualify reusable body plans through the library integration; otherwise reject those constructs in the published profile before activation. Instrument parser invocations for nested macros as well as top-level builds. No per-element source reparsing may be hidden in a warm-cache benchmark.

Freeze explicit parse/tree, input, collection, regex, and aggregate request-work limits, with verified enforcement in the chosen library integration. Do not invent a library budget API or assume a future timeout stops underlying work. Extend the bounded integration or reject a construct whose required limits cannot be enforced. The [documented differences](https://github.com/exoscale/cel-parser/blob/66ac2edbe9c196498d9f925ea4bf664ea0c166a2/README.md) and value conversion behavior require differential tests, including namespaces/map keys, numeric conversion, overload errors, and any SpiceDB-specific extension. Unqualified behavior is rejected explicitly.

**ClojureScript remains a release requirement.** This implementation uses JVM/ANTLR classes. Its source is not directly a CLJS evaluator. Keep those imports and programs behind the JVM boundary; persist only the portable canonical representation. Specify and qualify the CLJS path against the same profile, JVM adapter, and pinned SpiceDB corpus before Phase 2 activation. Do not silently drop CLJS Caveats or introduce remote evaluation. No CEL runtime benchmark or conformance result is established by this source review.

### 7. Capture one trusted evaluation time

The selected authorization view is `(immutable database basis, evaluated-at-ms)`. A client-targeted operation captures one fresh trusted time even when consistency reuses a database pin. All subproblems and checks within one batch use that same time. An explicitly retained EACL snapshot freezes both values; `eacl/with` and `eacl/with-schema` preserve its time while deriving a new database value.

A causal consistency token constrains database selection; it does not alone prove a current clock instant. Native `d/filter`/`d/with` values remain outside the public source boundary. This change does not introduce a caller-controlled time override on ordinary authorization. Deterministic client clocks support tests; retained historical EACL snapshots expose their fixed evaluation time and remain subject to backend retention.

Validate clock values and the configured nondecreasing client-clock contract. A regression fails current snapshot acquisition rather than reviving expired Relationships silently. Multiple peers need the documented trusted-clock/skew contract. Expiring each edge early is not a generic safe skew policy because early removal of a ban grants. No new uncertainty-window evaluator is required in this release; if one is offered later, it must prove whole-permission invariance or fail explicitly.

Snapshot results describe the captured time, even if wall time crosses expiry during execution. They are not a promise that a grant remains valid at some later application side effect.

### 8. Add deadlines to reusable artifacts, not timers to the graph

Expiry-only removes edges over increasing time at a fixed basis and fixed Caveat input. Positive-only expressions therefore cannot gain members, but EACL's exclusion breaks that conclusion for general permissions:

```text
read = viewer - banned
viewer: permanent
banned: valid-until 100
read at 90 = no-permission
read at 100 = has-permission
```

No Relation write occurs at 100. Cache coherence must check time as well as database dependencies.

The initial conservative certificate is:

```clojure
{:evaluated-at-ms t0
 :reusable-before-ms deadline-or-nil}
```

Reuse requires `t0 <= selected-time` and either nil deadline or `selected-time < deadline`, plus all existing source/branch, causal, schema, Relation, object-identity, context, and authenticated-entry checks. Earlier frozen snapshots need exact-time identity or a separately proved interval; never reuse a forward certificate backwards. Provider TTL and asynchronous eviction are optimizations only; compare the deadline before every accepted hit.

Each certificate proves the stability of its own artifact: Boolean/three-state answer, denotation, projection, residual, count, page, or retained frontier. Derive a conservative deadline from all expiry-sensitive witnesses/counter-witnesses needed to establish that artifact. The minimum future expiry over a complete relevant evidence set is safe; using only returned edges is not. Static Relation dependency closure remains complete even when a smaller temporal witness is proven sufficient. Already-expired edges cannot reactivate through time alone. Missing completeness proof means exact-time reuse or no publication, never a fabricated nil deadline.

A permanent owner can keep `owner + viewer` true while the `viewer` child expires. An unbounded certificate for the final Boolean must not widen the child's deadline. Cached denials under exclusion and conditional missing-field sets need the same reasoning. A proven positive-only denial optimization is allowed later, but first-release correctness cannot depend on inferring monotonicity from the absence of a direct `-` in the top-level expression.

Keep three concepts distinct: pre-evaluation lookup scope contains complete canonical request context and evaluator/ABI identity; dependency proofs cover named definitions, referenced expression content, bound context, and Relation state; the authenticated value contains permissionship, residual/missing fields, and temporal certificate. Do not require knowing the answer to calculate a lookup key. The separate program cache stores executable structure only: a program hit is not proof that any authorization result or residual can be reused.

### 9. Pin snapshot cursors; restart live cursors at unsafe expiry

A cursor authenticates temporal mode, captured evaluation time, its own deadline, source/schema/Relation proofs, result policy, ordering, context, and evaluator/ABI identity.

- An explicit snapshot cursor preserves the same database basis and time. Wall time beyond a Relationship expiry does not change its frozen graph. Token lifetime and history availability remain independent checks.
- A live client cursor captures a fresh time and validates its complete retained-state certificate. Crossing the deadline or moving before the certificate start returns a typed restart requirement and no resumed page. The caller starts a new query.

This remains necessary without scheduled activation. If an object before the cursor position was excluded by an expiring ban, it can become authorized after that ban expires. Continuing from the old position would omit it. Certify skipped candidates, negative evidence, lookahead, residuals, and traversal frontier, not merely emitted results. If proof is incomplete, a pinned query or explicit restart is the safe route.

### 10. Make collection optional bounded maintenance

The initial maintenance operation scans authoritative forward tuples with a stable keyset over a selected database basis. It accepts a trusted cutoff and retention policy, charges scanned candidates as well as deletions, and supports bounded batches/progress. Select finite expiries at or before the cutoff; cutoff cannot be later than the trusted collection time. Permanent Relationships are never selected.

For each candidate, reconstruct the exact reverse value and revalidate the current pair/owned context in the admitted transaction. A changed or renewed identity causes retry/skip with an explicit race outcome, not deletion of its replacement. Each actual deletion retracts both halves plus singly-owned context and stamps every affected Relation once. No stamp is needed for scanning or a no-op batch. The collector must use the normal writer concurrency protocol, not raw stale retractions.

A run over a frozen scan basis does not promise to collect rows concurrently added behind its cursor; later passes cover them. Mark completion relative to that scan basis, not “there are no expired Relationships now.” Define limits/progress separately from authorization pagination. Recovery from an unavailable scan basis restarts a maintenance pass.

No background job is required for authorization correctness. Collection improves stored scan density and bounds retained current facts according to operational policy. It can change stored reads and CREATE conflicts even when effective authorization at/after cutoff is unchanged. It therefore conservatively invalidates the affected Relation proof. Special GC-preserving answer proofs are deferred.

A derived expiry index is deferred until measured maintenance demand justifies it. It could later use a subject-owned time-leading permutation of the seven fields and AVET range access; it would be non-authoritative, independently rebuildable, and absent from authorization content certificates. This release does not add its schema, writer maintenance, reconciliation, or configuration surface.

Retraction is not excision. Datomic normally retains retracted facts in history; this operation does not promise durable byte reclamation. A post-collection current basis evaluated at an earlier time does not restore the deleted Relationship. Historical answers require an appropriate retained old basis and authoritative context/schema history; no stronger cross-backend history contract is introduced.

### 11. Freeze compatibility and phase activation

Relationship storage version 9 means the seven-slot layout and its specific format fingerprint. Tuple arity alone is insufficient: reject old four-slot and discarded eight-slot layouts, and any experimental seven-slot format with different component meanings. Inert legacy schema definitions may remain installed, but populated incompatible attributes/mixed data reject startup. No dual-read or automatic conversion is shipped.

Phase 1 enables expiry and requires both Caveat slots nil. Phase 2 enables the qualified Caveat profile under a persisted semantic capability epoch without changing tuple positions. Cache/cursor/engine fingerprints include phase semantics.

All active current-serving readers and writers must be fenced at activation. A long-lived client cannot check a new epoch by reading its old minimize-latency pin. Use a coordinated stop/upgrade/activate cutover unless an independently enforceable deployment fence exists. Retained Phase 1 snapshots can remain explicit historical views, never current Phase 2 service. Check capability compatibility on selected views and mutation commits in addition to startup.

## Risks / Trade-offs

- Wider permanent tuples → qualify segment density, durable bytes, allocation, and scan throughput; two datoms do not imply unchanged space.
- Expired clusters → charge all candidate work, return typed resource-limit errors, and measure cleanup lag. A global 5% estimate is not a bound on a particular endpoint.
- Scan-based collection at large scale → expose bounded progress and measure throughput; a derived index remains a later optimization.
- Conservative deadlines reducing cache reuse → measure completed answers and intermediates separately; optimize only with complete temporal proofs.
- Exclusion, recursion, and Caveat partials → differential/model tests must cover negative and conditional paths, not only expiring positive grants.
- Unknown writers or schema races → use explicit source qualification and commit-time guards; never paper over failed evidence as false membership.
- Cross-runtime CEL divergence → admission rejects unqualified constructs and Phase 2 stays disabled until its pinned corpus passes.
- Minimal CEL library surface → qualify static checking, partial evaluation, macro-body reuse, and enforceable budgets; program caching alone supplies none of these contracts.
- Shared expressions → guard interning and schema references, retain unused immutable entities initially, and bound the client program cache independently of database retention.
- No migration path → publish rebuild instructions and validate a fresh database before switching traffic.

## Migration Plan

1. Keep the preceding proposal deprecated; do not apply its deltas as an intermediate release.
2. Publish the Relationship storage/API and cache/cursor compatibility breaks.
3. Export logical Relationships and required application/schema data; create a fresh v9 database and load through the supported writer. This is operator rebuild guidance, not conversion tooling.
4. Verify the seven-slot format, one canonical pair per identity, context ownership, storage/capability markers, and absence of populated incompatible attributes.
5. Qualify Phase 1 on every backend/runtime and switch traffic only after application authorization checks pass.
6. Implement/qualify Caveats and perform the fenced Phase 2 activation described above.
7. Rollback uses the previous database/backup with the matching previous EACL version. There is no in-place tuple downgrade.

## Qualification and remaining implementation gates

The proposal fixes the ABI, expression ownership and lazy program reuse, semantic boundaries, collection strategy, and cache/cursor correctness obligations. The exact cel-parser build and adapter configuration, canonical expression encoding and interning guards, bounded compatibility profile, type/partial/budget integration and qualified CLJS execution path, public option/error naming consistent with current APIs, and numerical release budgets must be recorded during their explicit implementation tasks before the corresponding feature is activated or benchmark accepted. They do not justify claiming implementation or compatibility today.

Use nREPL for Clojure semantic checks, in-memory Datomic for mutation/certificate counterexamples, and an isolated local `:dev` database with at least one million independently verified logical identities for real latency. Benchmark the actual seven-slot implementation at 0%, 1%, and 5% finite-expiry distributions, distinguish active/unexpired from expired fractions, vary expired clustering and endpoint degree independently, include one-transaction-per-Relationship metadata alternatives only if compared, and measure both directions, cold/warm states, concurrent renewal/GC, and complete public authorization paths. Do not transplant the old four/eight-slot numbers as acceptance thresholds.
