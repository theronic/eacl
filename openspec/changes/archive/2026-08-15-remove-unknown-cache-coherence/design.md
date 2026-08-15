## Context

See `proposal.md` for motivation. `simplify-cache-coherence` established immutable request snapshots, lifecycle isolation, schema generations, initialized relation generations, atomic supported writers, and exact plus managed cache tiers. Its managed identity contains the complete canonically ordered relation-generation vector, and public configuration still chooses writer authority and proof algorithm.

The exact tier is already the safest and cheapest first lookup: its generation is the selected immutable snapshot, so a same-snapshot hit needs no dependency proof. Cross-snapshot reuse needs evidence that authorization schema and every relationship slice in the complete dependency closure are unchanged. EACL does not promise managed-cache availability for arbitrary `as-of`, `since`, filtered, speculative, or caller-constructed database values.

The bundled backends expose globally ordered native committed transactions. Supported EACL writers atomically stamp every affected relation with that transaction. This is stronger than independent per-relation monotonicity and permits a scalar maximum to replace the full generation vector as a cache identity, provided the stronger premise is formally modeled and certified at every backend boundary.

## Goals / Non-Goals

**Goals:**

- Prove the scalar-frontier strategy before changing runtime behavior.
- Make proof-backed reuse automatic for every eligible completed current result, including default demand evaluation.
- Collapse duplicate proof acquisition into one immutable request-scoped frame.
- Remove unsafe or obsolete authority and proof-mode configuration.
- Reduce proof-key comparison, hashing, allocation, retention, and cursor payload while preserving exact-first performance.
- Define exactly when proof is available and ensure every failure is exact-only.
- Leave public documentation containing only the current consumer contract.

**Non-Goals:**

- Coherence after unsupported raw authorization mutations.
- Transaction listeners, transaction-log scans, content proofs, mutation journals, graph heads, or database-global cache CAS.
- Managed-cache availability for arbitrary time travel.
- Formal verification of the Datomic, Datahike, or DataScript database engines themselves; adapter certification tests cover that trusted boundary.
- Broadly unifying Datomic execution with the shared orchestration implementation in this change.
- A compatibility or migration mode for removed options.

## Decisions

### 1. Establish the scalar-frontier theorem first

For a snapshot `S`, schema generation `Schema(S)`, and complete dependency set `D`, define:

```text
Frontier(S, D) = max({Initial} union {Generation(S, r) | r in D})
```

The formal model SHALL state these assumptions:

1. Snapshots in one lifecycle are immutable and totally ordered by committed transaction.
2. Every declared relation has a valid initialized generation.
3. Every supported relationship mutation at transaction `t` changes relationship tuples and assigns `t` to every affected relation atomically.
4. `t` is strictly greater than every relation generation visible before the transaction, not merely greater than the previous generation of the affected relation.
5. The dependency extractor returns the complete canonical set of relationship relations whose slices can affect the normalized authorization result, and equal schema semantics plus equal normalized request deterministically produce the same set.
6. A schema change advances `Schema`; relation removal/recreation is therefore not hidden by a frontier.
7. Restore or history replacement changes the source lifecycle before cache reuse.

The central theorem is:

```text
same lifecycle
and same semantic request/result shape
and Schema(S) = Schema(T)
and Frontier(S, D) = Frontier(T, D)
and S <= T
implies the authorization denotation at S equals the denotation at T
```

Proof is by contradiction. If any dependency relation changed between `S` and `T`, take its first supported mutation transaction `u`. Global native ordering gives `u > Frontier(S, D)`. Atomic stamping makes `Frontier(T, D) >= u`, contradicting equal frontiers. Equal schema generation keeps the schema and dependency interpretation fixed. Complete dependency extraction then preserves every relationship slice consulted by the operation.

The formal phase SHALL first encode the known counterexample showing that independent per-relation monotonicity is insufficient (`{A 10, B 5}` to `{A 10, B 7}`), then prove it becomes impossible under the global-current-transaction obligation. It SHALL also cover empty dependencies, same-transaction multi-relation changes, repeated writes, no-op over-invalidation, schema replacement, late publication, lifecycle replacement, and a future unproved dependency class. Runtime work does not begin until the complete local Dafny suite passes.

Alternatives rejected:

- Retain the full vector forever. It is sound but retains O(`d`) equality, hashing, key allocation, and cursor material for `d` dependencies after the stronger native ordering already makes one maximum sufficient.
- Use only per-relation monotonicity. The counterexample above makes the maximum unsound.
- Maintain one database-global relationship head. It would invalidate every relation after any relationship write and recreate avoidable coordination.

### 2. Use a single immutable dependency proof frame

After an exact miss, the selected adapter lazily creates one request-local proof frame containing:

- adapter identity and source lifecycle;
- immutable snapshot identity;
- schema assertion generation;
- the complete canonical dependency relation set;
- one validated native generation per dependency relation;
- the derived scalar frontier and any derived subset frontiers;
- proof capability and diagnostic status.

Completed-answer lookup/publication, schema planning, managed subproblems, and cursor validation share this frame. A consumer asking for a relation outside the frame's proved closure receives no managed proof; the engine never silently extends a frame using evidence from another adapter or snapshot. Historical cursor recovery creates a new frame scoped to the adapter and retained value it selects.

The frame may retain the per-relation values for validation and subset frontier derivation, but completed cache identities and cursor proof payloads contain only schema generation and the scalar frontier. Dependency-set equality is derived from the formally modeled deterministic extractor over equal schema semantics and normalized request; it is not replaced by a collision-prone digest. If an adapter cannot certify that extractor contract, proof is unavailable. This removes duplicate backend `schema-proof`, `relation-proof`, and managed-descriptor acquisition paths without weakening proof completeness.

### 3. Make every completed deterministic current response eligible

Managed eligibility is based on cache availability, ordinary current-snapshot selection, complete normalized semantic identity, deterministic rendering, completed result shape, and a complete proof frame. It is not restricted to `:complete-denotation` evaluation.

A demand-bounded boolean, count, page, or limited response is a complete answer to its normalized demand. Its cache key includes evaluation mode, normalized demand, query, subject, resource type, bounds, and all other semantic inputs. Only the completed response is published. Traversal worklists, partially filled pages, transient continuation state, and incomplete subproblem responses never enter the completed-answer managed tier.

Exact lookup remains first. Thus a same-snapshot hot hit performs zero proof reads, while the first eligible request after an unrelated transaction pays O(`d`) proof acquisition and can avoid authorization reevaluation.

### 4. Remove authority and alternate proof configuration

`:coherence-authority` and `:proof-mode` are removed from every accepted option set, fingerprint, descriptor, formal input, generated boundary, test fixture, and public example. Supplying either key follows the stable unknown-configuration error path.

Native mutation generations are the only cross-snapshot proof source. Content scans are removed because supported raw writers are not a design goal and content proof is more expensive. No-proof mode is removed because exact fallback already expresses proof unavailability without exposing an unsafe alternate configuration. Historical formal counterexamples may remain as evidence, but active runtime and public documentation do not offer these modes.

### 5. Make proof availability typed and fail closed

Proof is not needed after an exact hit and is not attempted when caching is disabled or the request is historical, filtered, speculative, caller-constructed, nondeterministic, or otherwise ineligible.

For an eligible ordinary current request, proof is unavailable when schema or relation generations are missing or malformed, dependency extraction fails, the closure exceeds a hard proof bound, evidence is partial or non-canonical, the adapter throws, the adapter lacks certified ordered generations, or a custom codec lacks its stable deterministic contract. A changed but valid proof is a managed miss, not proof unavailability. An empty dependency set is a valid proof with `Initial` frontier.

Every unavailable case falls back to exact-snapshot evaluation and emits typed diagnostic/telemetry information. The runtime never hashes or maximizes a partial set and never treats absence as an initial generation.

### 6. Certify rather than assume backend generation ordering

The generic adapter contract advertises an ordered-generation proof capability. Datomic, Datahike, and DataScript certification tests SHALL observe supported writes and establish that:

- each affected stored stamp resolves to the committed transaction generation;
- the transaction generation exceeds all stamps visible before commit;
- tuple changes and stamps become visible atomically;
- multi-invocation and multi-relation transactions use the same committed generation;
- initial relation generations are present after supported schema writing.

Adapters that cannot certify these facts remain valid exact-current adapters. This is the explicit trusted boundary left after Dafny proves the abstract model.

### 7. Bind custom identity and cursors safely

Built-in identity handling remains eligible. A custom codec gains managed reuse only with an explicit stable adapter fingerprint, a deterministic declaration, and property-tested injective round trips between public and internal identity. Semantic managed entries retain internal identities and re-render public identities from the current selected snapshot.

An unfingerprinted codec receives an opaque client-local identity rather than a shared constant. Its cursors and cached state fail closed across another client or restart even if signing keys are shared. Shared cursor deployments using a custom codec must configure the same codec and explicit stable fingerprint in every process.

### 8. Keep unsupported-mutation recovery operationally explicit

The EACL mutation contract covers authorization schema, relationships, permissioned identity/liveness, and safe entity deletion. It does not cover unrelated application datoms. Documented EACL-produced transaction data must be transacted intact; splitting tuple operations from generation operations is unsupported.

After unsupported mutation, operators quiesce or drain affected authorization calls, repair invalid data, and expire or recreate every affected client in every process. `prepare-cache-coherence!` initializes missing state but cannot discover a prior unstamped relationship change. `write-schema!` restores a complete desired schema contract but an identical write may be a no-op, so it is not a cache flush. Cache rotation never repairs ghost tuples.

### 9. Remove dead generated cache-validation runtime surface

The generated `:cache-validation` operation has no production consumer. Remove its generated JVM, JavaScript, browser, validator, fixture, and inventory surface while retaining formal cache lemmas used to verify the live exact/managed algorithm. This reduces the trusted and generated runtime boundary without changing observable behavior.

### 10. Require evidence before performance claims

The existing managed-proof microbenchmark is sensitive to warm-up order and measures unrelated graph cardinality rather than dependency cardinality; its large case currently appears faster than its small case and cannot compare the proposed design.

The revised benchmark SHALL use randomized or interleaved A/B order, adequate warm-up, multiple samples, p50/p95, allocation/key-size measurements, and backend operation counters. It SHALL separately measure exact hits, managed hits after unrelated commits, relevant-proof misses, and proof acquisition over increasing dependency counts. Correctness or safety is never traded for a favorable benchmark.

## Risks / Trade-offs

- [A backend transaction identity is not globally ordered as assumed] → Keep the adapter exact-only until certification establishes the ordered-generation capability.
- [A dependency extractor omits a relation or returns different closures for equal inputs] → Treat completeness and determinism as formal and adapter contracts, retain randomized cached-versus-bypassed oracles, and disable managed reuse for inconsistent or unproved dependency classes.
- [A scalar maximum hides a write under a previously larger stamp] → Require and prove that each supported commit is globally later than every prior stamp; the known monotonic-only counterexample remains a regression test.
- [A partially built frame is reused] → Make frames immutable and valid only after all canonical dependencies and generations validate successfully.
- [Default demand reuse caches partial work] → Publish only final normalized responses and test interruption, pagination, limit, count, and recursive traversal boundaries.
- [Old work publishes after lifecycle rotation] → Capture lifecycle/store identity per request and make prior stores unreachable; quiescence prevents old responses from escaping during unsupported-mutation recovery.
- [Custom codecs collide across clients] → Use client-local opaque identities unless an explicit stable deterministic fingerprint contract is supplied.
- [Proof acquisition adds cold-request work] → Preserve exact-first lookup, share one proof frame, derive a constant-size frontier identity, and benchmark the complete request path.
- [Absolute assurance is confused with database-engine verification] → State the trusted boundary explicitly: Dafny proves the algorithm under adapter obligations; backend certification, randomized oracles, and local suites validate each implementation of those obligations.
