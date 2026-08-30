## Context

See `proposal.md` for motivation and the delta specs for the behavioral contract. The defect is in Core, not in Datomic or the demo HTTP tier.

The live Datomic/DynamoDB/EC2 demo and an equivalent local Datomic in-memory fixture both reproduce the same identity fragmentation on one immutable basis. The demo computes a fresh remaining timeout after admission and snapshot selection, then supplies it as `:timeout-ms`. On Core SHA `858a73a62dfcdf05a5341787f806796d55fd2aff`, six otherwise identical first/next page requests with varying remaining budgets produce six misses and six resident exact entries despite zero evictions and byte-weight usage far below the configured bound. Holding the timeout fixed produces the expected miss/hit sequence. Count operations reproduce the same split, while point permission checks are a negative control because their completed identity is already independent of timeout.

The relevant identity paths are:

- `eacl.relay/page-request-key`, which removes cancellation but retains timeout in the client-private externalized-page key;
- `eacl.cache/lookup-page-query-identity`, which removes cursor transport and several controls but retains timeout in its public component; and
- the public components of `count-resources` and `count-subjects`, which retain timeout while their internal components already remove it.

Cursor query scopes and continuation identities already remove timeout. That is why cursors remain valid while completed-answer and visited-page lookups fragment.

The second mechanism is isolated in `eacl.relay/PageNavigationCache`. Every `put-page-request` filters and rebuilds the complete publication-order vector. Eviction then scans both complete boundary indexes to remove references to a victim. With a full cache, local JVM medians for end-to-end publication rise from approximately 126 microseconds at capacity 64 to 1,954 microseconds at capacity 2,048; exact lookup remains approximately 1.3 microseconds. This is publication/eviction amplification, not lookup cost.

Existing constraints materially shape the design:

- every request establishes one absolute monotonic deadline before cache access;
- cache hits must remain immutable, read-only, and independent of telemetry mutation;
- page-navigation values are process-local and not part of portable cache snapshots;
- completed-answer snapshots bind the semantic key, engine/order/compiler compatibility, and cache-value ABI;
- Relay externalized pages carry consistency-mode-bound cursors, so the visited-page cache must retain normalized consistency mode even though the internal completed answer can be externalized anew; and
- CLJ and CLJS share the portable implementation.

## Goals / Non-Goals

**Goals:**

- Make every successful-result identity use one explicit definition of invocation-only controls.
- Preserve all result-affecting partitions and per-invocation execution checks.
- Make page-cache publication, replacement, boundary removal, and eviction amortized constant-time with capacity-derived bounded metadata.
- Preserve exact adjacent-page alias behavior and its page-size guard.
- Make page-cache structure and publication outcomes observable without mutating the hit path.
- Produce deterministic structural evidence and multi-capacity elapsed-time evidence on the changed portable path.

**Non-Goals:**

- Do not equate forward and reverse pagination generally, rewrite the demo's navigation stack, or weaken cursor authentication.
- Do not cache deadline/cancellation errors or share an in-flight computation between requests.
- Do not add hit-recency mutation; publication order remains the page cache's eviction policy.
- Do not change public request/response shapes, cursor encodings, adapter ABI, result order, resource limits, or consistency selection.
- Do not add an external cache, distributed invalidation, HTTP/CDN caching, or a demo-only key workaround.

## Decisions

### 1. Normalize successful-result inputs through one portable helper

Add a small cycle-free portable namespace that removes exactly `:timeout-ms`, `:cancellation-token`, `:cache?`, and `:populate-cache?` from a request map. Use it at every affected successful-result key boundary, then apply the boundary-specific transformations already required:

- completed lookup pages additionally replace public cursor transport with the authenticated internal boundary and omit consistency from the internal answer key because exact basis identity partitions the data and externalization occurs for the receiving request;
- completed counts omit consistency because the selected exact basis is already in the generation identity; and
- visited externalized pages retain normalized consistency mode because their returned cursor bytes are mode-bound.

The helper does not remove `:evaluation`, aggregate or recursive limits, page direction/size, authenticated boundary, semantic filters, ordering, or any compatibility identity.

Alternatives rejected:

- Removing timeout only in the demo would hide the bug for one caller and leave all other deadline-aware clients fragmented.
- Editing each affected call site without a shared definition would preserve the current drift risk; the existing paths already disagree about the same four controls.
- Removing all pagination controls would be incorrect: direction, page size, and authenticated position affect the returned page.
- Treating the timeout as a cache ABI dimension is incorrect: it limits whether an invocation may finish, not the denotation of a successful finished answer.

### 2. Keep deadline and cancellation enforcement outside compatibility identity

No execution check moves or disappears. Request normalization still validates the supplied timeout/token and creates the current absolute deadline. The existing checks before cache lookup, after resolution, before optional publication, and around externalization remain authoritative. A resident immutable value is merely eligible data; it becomes the current response only while the current execution contract remains live.

Tests use a fake monotonic clock and explicit cancellation token to cover a warm hit that succeeds under a changed live budget and warm lookups that fail before or after acquisition. The producer's timeout and token are never stored in or inherited from the value.

Alternative rejected: retaining timeout in the key as a proxy for deadline safety. It provides no safety—the numeric budget says nothing about whether the current deadline has expired—and destroys reuse.

### 3. Replace the page order vector and reverse index scans with a stamped queue

Represent page-cache state as:

```clojure
{:tick n
 :queue <persistent FIFO of [stamp request-key]>
 :entries {request-key page}
 :stamps {request-key n}
 :boundaries {request-key {:start-boundary boundary-key-or-nil
                           :end-boundary boundary-key-or-nil}}
 :by-start {boundary-key request-key}
 :by-end {boundary-key request-key}
 :metrics {:publications n :replacements n :aliases n
           :evictions n :compactions n}}
```

Each publication increments the stamp, installs one immutable entry, and appends `[stamp request-key]`. Pages remain the direct values of `:entries`, preserving the former single-map exact-hit read; generation stamps and owned boundaries live in side indexes used only by publication/eviction. Replacement leaves the superseded queue record stale. Capacity enforcement pops FIFO records until it finds one whose stamp is still current, then removes that entry. Removing an entry knows its owned start/end boundary keys and conditionally removes each direct index only when it still points to that request key; no index scan is required.

The current page publication owns its boundary indexes. Synthetic opposite-direction aliases are ordinary request entries but do not own duplicate boundary indexes, preserving existing lookup behavior and preventing boundary ownership fan-out. Boundary lookup still validates the requested page size before installing an alias.

Stale queue records are compacted only when they exceed `max(64, 2 * live-entry-count)`. Compaction retains only records whose stamp matches the current entry. Each record is appended once and either popped or discarded once, so publication/eviction/compaction is amortized constant-time. Immediately after a transition the queue is bounded by a small constant or approximately twice live capacity; entries never exceed configured capacity; each owned boundary index has at most one mapping per resident owning entry.

The transition remains a pure function inside one `swap!`; no metric or other side effect occurs outside the atomically selected state. Hits continue to perform one atom dereference and direct map lookup without mutation.

Alternatives rejected:

- A linked mutable LRU would make CLJ implementation easy but would complicate CLJS portability and immutable-read safety.
- Touch-on-hit LRU would serialize the hottest path and violate the read-only hit contract.
- A vector plus a key-to-index map still requires suffix index repair or a more complicated hole/compaction scheme.
- Boundary maps containing sets of aliases are unnecessary for correctness and increase retained metadata. The existing semantics require a safe direct candidate, not maximal alias survival after its owning page is evicted.

### 4. Publish diagnostics from the same immutable state

Add a public Core-internal `page-navigation-cache-stats` reader and include it under `:page-navigation` in client `cache-stats`. Report configured capacity, entries, start/end boundary counts, queued order records, and publication-side counters. Do not add hit or miss counters: accurately maintaining them would mutate the lookup path, contradicting the existing cache contract. Completed-answer hit/miss statistics remain available in their existing location.

### 5. Keep the portable snapshot format and completed-value ABI unchanged

Page-navigation state is never exported. Existing completed snapshots encode full semantic keys. After normalization, an old timeout-bearing key is unreachable and consumes only already bounded snapshot/cache capacity until normal eviction or clear. An old key that already omitted all invocation controls is semantically identical and safe to reuse because engine, order, compiler, value ABI, adapter, lifecycle, and exact basis identities still match.

Therefore neither `basis-snapshot-format` nor `completed-cache-value-abi` needs a version bump. Bumping either would discard unrelated compatible answers without adding correctness. Restore tests will prove both cases: timeout-bearing legacy entries miss; already canonical entries remain usable.

### 6. Qualify identity correctness and structural performance independently

Correctness tests cover every affected public operation (`lookup-resources`, `lookup-subjects`, relationship reads/listing, `count-resources`, and `count-subjects`), fixed/varying timeouts, distinct token instances, cache lookup/publication controls, cancellation/deadline cut points, exact basis partitioning, consistency-mode partitioning for externalized visited pages, semantic query changes, cursor direction/size, and adjacent aliases.

Structural tests execute the portable transition at capacities 64, 512, and 2,048 and assert capacity-derived ceilings plus amortized queue/index operation counts. Both CLJ and CLJS run the same deterministic state-machine trace.

The predeclared mechanism benchmark uses full caches at capacities 64, 512, and 2,048, a replacement/churn workload of at least four times capacity, JVM warmup before sampling, and median nanoseconds per publication/eviction block. The retained implementation must reduce the capacity-2,048 median by at least 50% versus source-matched baseline and must not show a greater than 2.5x per-operation ratio between capacities 64 and 2,048 after subtracting fixed harness cost. Exact-hit median may regress by no more than 10%.

PR 160 already has exactly one frozen public `releaseWin`, `:recursive-star-exact-count`, in `docs/benchmarks/results/2026-08-29-eacl-performance-amplification/release-acceptance.edn`. This change does not manufacture a second release win or edit that candidate-independent threshold. Because the final candidate source changes, the existing release win and all its safety lanes must be reconfirmed from fresh source-isolated samples against the same frozen record.

The new mechanism-specific public lane is a warmed exact-basis `lookup-resources` page request whose only per-block variation is a valid positive timeout. A source-matched legacy-identity arm retains timeout while the candidate arm canonicalizes it. The candidate must improve direction-normalized median response latency by at least 25%; this mechanism gate supplements rather than replaces the single frozen release win. Its safety lanes are cold page miss, fixed-timeout hit, adjacent reverse alias, count hit, deadline failure, cancellation failure, and CLJS wall time; latency may regress by no more than 10% on JVM and 15% on CLJS, while semantic/counter results permit no drift. Pilot measurements are excluded from final confirmation.

Final verification runs the complete affected unit suite, all repository ordinary test aliases, CLJS tests, backend contract/differential suites, formal and mutation/conformance checks, deterministic cache-structure checks, and every predeclared multi-size benchmark lane. A single 4,096-result count benchmark is explicitly insufficient.

## Risks / Trade-offs

- **[Risk] A control is removed that actually changes a successful value** → The helper's key set is closed and named; semantic negative tests mutate every neighboring query/demand/limit/direction/basis dimension and require a miss.
- **[Risk] A warm hit escapes a shorter deadline or cancellation** → Preserve all execution checks and add fake-clock/cancellation cut-point tests around lookup and externalization.
- **[Risk] Stale queue records grow without bound under replacement churn** → Enforce and test the compaction threshold after every transition at multiple capacities and under single-key churn.
- **[Risk] Boundary eviction removes a newer owner's mapping** → Store owned boundary keys on entries and conditionally dissociate only when the index still equals the evicted request key.
- **[Risk] An alias returns a page of the wrong cardinality** → Retain the exact existing page-count guard and add mixed-size boundary collision tests.
- **[Risk] Atomic `swap!` retries overcount diagnostics** → Keep all counters inside returned immutable state; never mutate an external counter in the transition function.
- **[Trade-off] Publication-order eviction is not access LRU** → This is intentional: hits remain read-only and non-serializing. Repeatedly requested pages are protected by the completed-answer cache even if their visited externalized representation ages out.
- **[Trade-off] Old timeout-bearing snapshot entries remain temporarily resident** → They are unreachable and bounded. Rejecting the entire snapshot would sacrifice unrelated safe entries for no authorization benefit.

## Migration Plan

1. Land Core identity normalization, stamped page-cache state, diagnostics, tests, and benchmark evidence together on `codex/eacl-performance-amplification`.
2. Run fresh final qualification after the candidate and thresholds are frozen; commit only the verified Core/OpenSpec inputs and evidence.
3. Push the branch backing PR 160.
4. Resolve the exact new EACL git SHA, update every demo dependency/profile to that SHA, and run all demo tests/build verification.
5. Commit and push the demo repository's deploy branch, deploy every demo through its existing simple deployment commands, and qualify live health, navigation reuse, deadline behavior, and absence of admission-limit responses.
6. Rollback Core by redeploying the prior SHA. Rollback demos by redeploying the prior demo commit; no persistent data migration or cache-snapshot rewrite is required.
