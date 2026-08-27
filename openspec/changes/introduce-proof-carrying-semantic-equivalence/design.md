## Context

See `proposal.md`. The state this design starts from, after `add-authorization-views` lands:

- A **basis** is one immutable database value with identity `{backend source-scope source-lifecycle revision exact-locator basis-kind}`; `source-scope` is `{:source-id :branch}` (Datomic database UUID; Datahike `{:store-backend :store-id}` plus branch; Datalevin persisted source UUID; DataScript a per-connection random id from a weak map). The lifecycle defaults to the constant `"eacl/initial"` on Datomic, Datahike, and DataScript and is required, persisted configuration on Datalevin.
- `eacl.proof-frame/request-frame` lazily reads `:schema-generation` (certified, memoized per adapter) and `:proof-frame` (relation stamps for the canonical closure, ≤ 4,096 relations), validates shape and order, cross-checks the frame's schema stamp against the certified generation (`:eacl/backend-integrity-error` on disagreement), and derives `descriptor` = `{:schema-stamp :dependency-stamp}` with the frontier as `max` over stamps or `0`. Every other defect is `unavailable` with a reason histogram.
- Managed completed answers live in one `ManagedGeneration` per schema stamp with an install-order guard; entries are keyed `[semantic-key kind dependency-stamp]`; `semantic-key` carries operation, normalized query, evaluation, demand, engine version, order ABI, source lifecycle, adapter fingerprint, and limits — not source scope. Managed subproblems and continuation scopes do carry source scope. No direction check exists anywhere: reuse is equality.
- Bundled adapters stamp relations with the committing transaction: Datomic `[:db/add relation :eacl/relation-version "datomic.tx"]` and read `:tx` (a transaction entity id); Datahike and DataScript `:db/current-tx` and read `:tx` (in the `max-tx` domain). Datomic's revision is `basis-t`.
- `ScalarFrontierCoherence.dfy` proves `EqualScalarProofPreservesEveryDeterministicDenotation` over a history `h[0..n]` of `OrderedSupportedStep`s within one `lifecycle`: equal frontier and schema generation at `h[0]` and `h[n]` imply equal evaluator output at both. `OrderedSupportedStep` already assumes every generation visible before a commit is strictly below that commit (the ceiling premise) and that stamped relations receive exactly the committing transaction.
- Caches are client-private in-process atoms. The cross-process authenticated completed-cache path was deleted by `trusted-surface-hygiene`; cursors and revision tokens are the only portable artifacts and are authenticated by the shared keyring.
- `DecideCurrentCache` (generated) is a stage machine over `{:stage :available?}`; the frame comparison itself is key equality in `eacl.cache`.

## Goals / Non-Goals

**Goals:**

- One reuse rule, stated in data: lineage + frame equality, used identically by every reusable artifact.
- Every premise of the proved theorem is either represented in the runtime key or discharged by an executable adapter obligation.
- A defective adapter cannot feed the cache silently; absence and defect are distinguished.
- Lifting is direction-agnostic, so retained older snapshots benefit from newer computations.
- Read-without-populate for measurement and one-off queries.
- No new trust machinery: no serialized or in-process "sealed" authority objects, no proof epochs, no cross-process registries, no second generated kernel.

**Non-Goals:**

- Changing how any backend stamps relations (Datalevin's storage-level stamping is `certify-datalevin-ordered-generation-proofs`).
- Cross-process completed-answer sharing. Completed answers stay client-private; nothing here reintroduces portable cache entries.
- Detecting unstamped out-of-band mutations after the fact. The supported-writer contract, lifecycle rotation, and backend-level enforcement where available remain the boundary.
- Identity or "external input" stamps. Identity mutation is already neutralized by re-resolving query anchors and re-rendering results on the selected basis; custom codecs are governed by the existing deterministic-fingerprint contract.
- Managed lifting when a basis cannot provide a complete, contract-valid frame; those bases remain exact-only regardless of basis kind.

## Decisions

### 1. The reuse rule

For a request at basis `S` with canonical relation closure `D`:

```clojure
lineage  = {:source-scope (:source-scope S) :source-lifecycle (:source-lifecycle S)}
frame    = {:schema-generation g(S) :dependency-stamp (max 0 (stamps S D))}
key      = [lineage g(S) semantic-key kind (:dependency-stamp frame)]
```

An artifact published from basis `E` is reusable at `S` iff its key equals the key computed at `S`. Nothing compares `revision(E)` with `revision(S)`.

**Why direction does not matter.** `EqualScalarProofPreservesEveryDeterministicDenotation` takes a history whose first element is the earlier basis and whose last is the later one, and concludes that the evaluator agrees at *both*. Whether the cached value was produced at the first or the last element is irrelevant to an equality, and the file already states the older-selected case explicitly as `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` (line 858), which nothing cites. Operationally this case already occurs today: two concurrent requests select bases 1040 and 1041, the later one publishes first, and the earlier one hits. The implementation is correct; the documentation and `add-authorization-views` design §8 are stricter than the proof for no benefit. The formal task makes the assurance matrix cite the existing corollary.

**Why lineage is source scope plus lifecycle.** Two bases lie on one linear history exactly when they come from the same database history and no replacement occurred between them. The first is the persisted source identity (or, for a source that cannot persist one, an identity minted once per live source); the second is the operator-rotated lifecycle. This is the existing data; the change makes it the stated premise, puts it in the one key that lacked it, and obliges non-durable sources to mint a fresh identity per live source rather than per configuration (Datahike memory stores with a caller-supplied fixed id currently do not).

### 2. Frame contract

`:proof-frame` returns `[[relation-id generation] ...]` for exactly the requested canonical relation ids, in the same numeric domain as `(:revision (:native-revision adapter))`. Core:

1. takes the schema generation from the certified `:schema-generation` operation only (the second read and the cross-check are removed; both operations read the same datom on every bundled backend);
2. validates the vector is canonical — relation ids equal the request, generations are exact non-negative integers;
3. asserts `g <= revision` and every generation `<= revision`;
4. derives `dependency-stamp`.

Datomic's adapter returns `(d/tx->t (:tx datom))`; Datahike, DataScript, and Datalevin already use the `max-tx` domain. Datomic also drops `:db/noHistory` from `:eacl/relation-version` (altered in place by `write-schema!`'s additive install): with history retained, `d/as-of` at any `t` reads the stamp that was current at `t`, so frame readability no longer depends on whether an index job has run. The attribute's own docstring justified `noHistory` by "only the current stamp is ever read"; that stopped being true when exact-by-locator bases became ordinary request targets. The cost is one retained datom per affected relation per write, beside the two tuple datoms (forward and reverse) every relationship write already retains. The ceiling assertion can only fire on an adapter defect for the bundled backends — a datom visible in an immutable value cannot postdate it — and that is the point: it turns an unchecked premise of the theorem into an executable one for third-party adapters and for Datalevin's scalar stamps.

A missing generation for a relation (uninitialized store, no `prepare-cache-coherence!`), a missing `:proof-frame` operation, a closure above the bound, or an adapter exception that is not a contract violation is **unavailable**: exact evaluation, no managed publication, `:proof-unavailable-reasons` counted, nothing sticky.

### 2a. Readability gates lifting, not basis class

`add-authorization-views` makes historical-class (as-of) bases exact-only. With the frame contract above that exclusion is no longer necessary: an as-of basis at `t` *is* the basis at `t`, its lineage is the same, its stamps read at `t` are exactly those visible at `t`, and the theorem applies. The rule becomes: an admissible basis of any kind uses managed lifting when its frame is readable (integer revision in the stamp domain, every generation present and `<= revision`); otherwise it uses the exact tier only. Datahike `AsOfDB` over a `:keep-history? true` store and Datomic `as-of` (after the `noHistory` change) both read; a Datomic as-of basis that still meets a purged stamp, or a Datahike temporal view with a non-integer time point, is simply unavailable. The practical beneficiary is the reader Peer: a session snapshot selected by exact token keeps the managed entries of earlier sessions across unrelated writes instead of starting cold on every token.

### 3. Contract violation

A frame that is malformed, has duplicate or out-of-order relation ids, wrong cardinality, a non-integer generation, or a generation above the revision is a **contract violation** (`:eacl/backend-contract-violation`, `:operation :proof-frame`, `:reason`). Response, in order: the request evaluates exactly on its already-selected basis and returns normally; the runtime sets `managed-lifting-disabled? true` (sticky until `expire-cache!`); `cache-stats` reports `:proof-contract-violations` by reason; the client's optional diagnostic reporter is invoked once per reason per lifecycle. Cursor continuation treats a violated frame as proof-unavailable and proceeds to exact fallback or the typed stale outcome. Exact-basis caching is unaffected: it is sound by identity.

Why not fail the request: the frame is an optimization input and the selected basis is authoritative. Why not keep treating it as a miss: a violated invariant means the adapter's evidence is not evidence; continuing to consult it on every request is noise, not safety. Why no epoch or registry: caches are process-local, so local disablement is complete for caches; cursors are revalidated against a fresh frame wherever they are presented; cluster-wide invalidation, if an operator wants it, is lifecycle rotation with a shared value — the mechanism that already exists.

### 4. Managed tier shape

The managed completed-answer tier becomes a bounded map `(lineage, schema-generation) → store`, entries keyed `[semantic-key kind dependency-stamp]`, with the same weight/LRU budget as today. The single installed generation and its `installed-order` guard are deleted: they existed to stop a delayed older request from rolling back "the" generation, which has no meaning once several bases are live (`add-authorization-views` retained bases) and lifting is direction-agnostic. `semantic-key` drops `:source-lifecycle`; lineage is a key prefix instead. The managed subproblem key already carries `*managed-scope*` (source scope); it gains nothing but consistency.

### 5. One frame per request, shared by every artifact

`eacl.request.context` already memoizes dependency proofs per closure. Completed answers, managed subproblems, cursors (`enable-proof-equivalent-cursor-streams`), and checkpoints (`enable-proof-equivalent-checkpoints`) all consume the same `frame` value and the same `lineage` value from the context; none derives a second digest of the same data. Purpose is expressed by key shape — answer keys, subproblem keys, cursor envelopes, and checkpoint keys are structurally distinct — not by certificates or domain tags.

One artifact is deliberately *not* frame-keyed: the visited-page cache stores externalized public pages, whose rendering depends on public identity, which the frame does not cover. It stays keyed by exact basis. The projection tier likewise stays per basis.

### 6. `:populate-cache?`

Request option, `true` by default, validated as `nil`/boolean. `false` suppresses every cross-request publication the request would otherwise perform — completed answers, managed subproblems, checkpoints, visited pages — and changes nothing else: lookups, proof acquisition needed for a lookup or a cursor, and request-local memoization proceed. With `:cache? false` the option is accepted and irrelevant. No write-only mode: a request that reads nothing and publishes evaluates exactly like one that reads and misses, so the fourth combination has no distinct meaning worth an API surface.

### 7. Formal and certification

- `ScalarFrontierCoherence.dfy`: document that `lifecycle` models the runtime lineage (source scope plus lifecycle); cite the existing `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` from the assurance matrix and `docs/cache.md`; keep the existing counterexample for independent counters.
- `NativeGenerationCoherence.dfy` lifting-direction statement and the `add-authorization-views` assurance-obligation mapping are updated to the lineage rule.
- Adapter certification v2 gains executable obligations: generations and revision share a domain; after every supported mutation, every affected relation's generation equals the committed revision and every generation at any selected basis is `<= revision`; non-durable sources mint distinct source ids across reconnects. Mutation controls for these obligations must execute against the adapter (the registry's literal-only detectors do not count).
- The trust manifest lists the domain, ceiling, lineage, and supported-writer premises as adapter assumptions, not theorems.

## Rejected alternatives

- **Sealed in-process certificate objects that adapters "cannot construct."** Nothing in one JVM or JS process is unforgeable against code in that process; a private `deftype` is a convention with a reflection-sized hole. The discipline it would buy — only core builds the frame — already holds by code structure and is pinned by the production-decision inventory and source-closure ledger.
- **Proof epochs with a coordinated durable registry.** Solves invalidation for a cross-process cache that does not exist. For cursors, revalidation at presentation time and lifecycle rotation already give the two available responses.
- **Exact-vector profile beside the scalar frontier.** Equivalent power under the bundled stamp discipline (global commit ordering), `O(n)` storage per entry, and no backend that would need it. The frontier's extra premise — global ordering — is the one every bundled backend satisfies by construction.
- **Exact / provider-session / durable lineage profiles as negotiated capabilities.** Lineage is a fact about the source (can it persist an identity or not) and is already represented in `source-scope`. A capability enum would add negotiation to express one boolean that the source id already encodes.
- **Identity and external-input components with their own generations.** No bundled backend can stamp application writes to `:eacl/id` (Datomic has no write interception), and none needs to: anchors are internalized and results externalized on the selected basis, so identity mutation changes keys and renderings rather than reusing stale ones. Declared external inputs reduce to the existing custom-codec fingerprint contract.
- **A second generated acceptance kernel with a reject-by-default source audit.** The acceptance decision is map lookup by a complete key; generating `=` adds artifact weight without assurance. `DecideCurrentCache` and `DecideContinuation` remain the generated decisions they are.
- **Order dominance (`order(E) <= order(S)`).** Not a premise of the proof; costs hits on retained older snapshots.
- **Keeping `:db/noHistory true` on the Datomic stamp.** Saves one retained datom per affected relation per write and makes frame readability at as-of bases depend on indexing timing, which is the wrong property for a cache key and the reason two special cases exist.
- **Compatibility constructors, legacy-entry handling, migration plans, rollback stories.** v8 is unreleased.

## Risks / Trade-offs

- **[Datomic `dependency-stamp` changes domain]** → cursors, tokens, and caches are regenerated; there are no released artifacts. Certification pins the domain so it cannot drift again.
- **[Retained stamp history grows the Datomic history index]** → proportional to relationship writes, already dominated by retained tuple datoms; measured by the Datomic write benchmark and recorded.
- **[Sticky disablement hides a transient bug until restart]** → disablement is visible in `cache-stats` and the reporter; `expire-cache!` clears it; exact evaluation continues unchanged.
- **[Non-durable Datahike stores with fixed ids today share lineage across restarts]** → the source mints a per-connection id for memory stores regardless of configured id; the conformance suite pins restart rejection for every non-durable source.
- **[Direction-agnostic lifting surprises readers of the old documentation]** → the rule, the corollary, and the concurrency example are documented together in `docs/cache.md`.
