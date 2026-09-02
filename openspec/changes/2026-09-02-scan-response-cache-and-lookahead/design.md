## Context

See proposal.md for motivation and the measurements that rank the levers.
Facts that shape the approach (all verified on the current tree):

- The stable reducer, the least-path evaluator, and the probe route issue
  every physical read through one routed fetch function built in the engine
  facade: classification and retry wrap an adapter seam that maps a read
  descriptor `{:operation :subject->resources | :resource->subjects,
  anchor type and id, :relation-eid, target type, :bound-eid, :limit,
  :direction}` to one adapter scan. The reducer's descriptor is ascending
  only; the least-path evaluator adds `:direction :desc` for reverse windows.
- A fetch reply is exactly `min(limit, remaining)` values strictly beyond the
  bound, fewer only when the scan is exhausted; physical width and buffer
  retention provably cannot change released sequences (`ChunkedScan.dfy`,
  `OneValueScanNormalization.dfy`).
- `:max-commands` and `:max-values` are charged in `fetch-values` and the
  probe route's `fetch!` on the values returned by the routed fetch function,
  so a layer below them that returns the same values leaves limit accounting
  untouched.
- Every request already binds a proof frame; `proof-frame/resolve!` returns
  per-relation generations for a canonical relation-id vector and memoizes
  equal closures inside the request. Relation stamps are the granularity of
  managed reuse today.
- `eacl.cache.standard-lru` is the cross-runtime bounded store (Caffeine on
  the JVM with synchronous maintenance, the pinned LRU fork on
  ClojureScript). Its hit path costs about 70 ns against 1.1 to 12 µs per
  adapter scan on the bundled backends.
- `secure-format/canonicalize` returns maps and sets sorted by a comparator
  that renders both operands to strings; those values are stored as cursor
  memo keys and compared with `=`, so each equality or lookup re-renders keys.
  A 20-result page performs about 1,400 renderings this way.
- Public page operations publish a complete rendered page under an
  exact-basis key when caching is enabled; a later request for the same
  normalized query on the same basis is an exact hit before any cursor decode.
- The S3 storage backend used by the demos carries its own opt-in I/O
  statistics (`*io-stats*`, `global-io-stats`, `with-global-io-stats`); the
  DynamoDB backend does not.

## Goals / Non-Goals

**Goals:**

- Remove repeated adapter scans within a request and across requests on
  every backend without changing any answer, order, cursor, limit outcome,
  error, or deadline behavior, and without any command the uncached run
  would not issue.
- Let a deployment trade background reads for foreground latency explicitly
  and boundedly, with attribution.
- Make per-request I/O observable at zero cost when unobserved.
- Remove the canonical-comparator rendering tax with byte-identical output.
- Keep the executable oracles: cache-off execution for the shared tier, a
  test seam for the request-local memo, and generated differentials.

**Non-Goals:**

- Any change to traversal algorithms, admission, order, or counting.
- Endpoint-scoped stamps, compact checkpoints, order-insensitive counts,
  materialized closure, Datalevin native scan cost, DynamoDB storage
  statistics. Each is recorded in tasks §10 with the corrected analysis.
- Speculative prefetch across alternatives or beyond the continuation.
- The operator engine's own scan seams (seekable and recursive operator
  scans through the scan invoker, batched membership probes) and the
  relationship-index scans of `read-relationships`: separate seams, recorded
  as a follow-up once this seam's gate results are in.
- Removing the `:denotation` tier: it now has live operator-engine
  publishers, unlike when the earlier draft proposed deleting it.

## Decisions

### D1 — Cache adapter responses, never reducer emissions

The unit of reuse is the response to one read descriptor. Emissions under a
plan node depend on the request's global admitted set, so context-free node
segments are not substitutable (`CacheBoundary.dfy`). Adapter responses are
functions of (descriptor, relation slice at the snapshot) only.
Alternative rejected: hierarchical segment caches (unsound for stable order).

### D2 — Elide-only, top-up by re-issuing the original command

Serve iff the stored prefix contains at least `L` values beyond `b` or is
exhausted; otherwise forward `(descriptor, b, L)` unchanged and, when the
reply is contiguous with the stored prefix, extend it. Never fetch from a
different bound, never with a larger limit, never ahead of demand. This makes
the cached run's command multiset a subset of the uncached run's with equal
replies: trace refinement by construction, which is what
`demand-bounded-evaluation` requires.
Alternative rejected: fetch-ahead on miss (violates demand bounding; spends
remote reads on values the request may never consume).

### D3 — Two tiers: an unconditional request-local memo and a scoped shared tier

The request-local memo lives in the request context, keyed by descriptor
identity minus bound, limit, and direction-derived fields, and needs no
validity scope because every read of one request observes one immutable
basis. It is part of ordinary execution and is not switched by `:cache?` or
client cache configuration, which govern only reads and writes of the shared
store; an internal dynamic test seam disables it to establish the command
oracle. The shared tier is keyed by scope + descriptor where scope is
`{backend-id, source-scope, source-lifecycle, adapter-fingerprint,
identity-contract, order-abi, plan-domain, schema-generation, relation-eid,
relation-generation}`; the relation generation is read from the request's
proof frame after resolving the sealed plan's complete dependency closure
once per request (memoized in the frame; a caching request resolves that
same closure anyway for its answer publication or cursor context, so this
moves one acquisition earlier and adds none). The resolver never acquires a
per-relation singleton proof: a relation outside the resolved closure, an
unavailable or incomplete proof, or a non-ordinary snapshot bypasses the
shared tier for that scan while the memo still applies. The memo itself is
bounded by a descriptor count (default 4,096) and the per-entry prefix cap, so
an exhaustive traversal over millions of descriptors retains at most that
bound and simply stops memoizing beyond it.
Alternative rejected: keying the shared tier by exact snapshot only (loses
cross-write sharing, the point of the tier).

### D4 — Placement between the reducer's limit accounting and retry/classification

The caching fetch function wraps the retrying/classified fetch function and
is installed only when orchestration binds a scan-cache context for the
request (memo, optional shared store, scope resolver); raw facade paths that
build engine adapters outside a request context keep the plain routed fetch
function unchanged. Hits skip retry and classification; misses see
classified, retried replies and deposit only complete replies. The shared
tier honors the execution contract's cache-stage availability exactly as the
answer tiers do. `fetch-values` and the probe `fetch!`
still count every served value, so limits, deadline checks, and typed errors
are unchanged. The least-path seam preserves `:direction`; descending scans
use a separate key and prefix (the descriptor includes direction) so the
ascending and descending sequences of one endpoint never mix.

### D5 — Reuse the standard cross-runtime store; no bespoke concurrent map

The shared tier is one more `standard-lru` store owned by the client
(`:scan-cache {:max-entries n :max-prefix m}`, defaults 2,048 entries and a
512-value prefix cap, so the worst case is 8 MiB of longs per client), keyed
by canonical scope + descriptor vectors built without canonical rendering
(plain vectors of keywords and integers, hashed once). The tier is excluded
from cache snapshot export and restore: its values are internal ids. Measured hit cost is about 70 ns; Caffeine
maintenance is synchronous, so admission is deterministic. Entry weight is
bounded by `max-entries × max-prefix`. `replace-if!` publishes the longer
prefix under compare-and-set on the value, never on the hit path.
Alternative rejected: the earlier design's dedicated `ConcurrentHashMap`
store with sampled eviction (a second store type, a second eviction policy,
and no ClojureScript twin).

### D6 — Default enablement per backend is decided by a paired gate

Cost model: benefit = (scan cost − hit cost) × elided scans − miss tax ×
scans. Hit cost is about 70 ns and the miss tax (key construction, one
lookup, one deposit) is below 300 ns; scan cost is 1.1 µs (DataScript),
1.7 µs (Datomic in-memory), 1.8 to 2.5 µs (Datahike memory/file), 11.7 µs
(Datalevin), and one index path per node-cache miss on S3. The paired gate
in tasks §7 decides each backend's default; measured elision on the sparse
fixture is 91 percent with identical results.

### D7 — Lookahead is an ordinary client operation on a background executor

After `render-and-cache-page` returns a page whose page info reports a next
page and whose publication succeeded, orchestration submits
`(operation client (assoc normalized-query :after end-cursor))` marked with an
internal lookahead provenance to a per-client executor bounded by
`:max-inflight` (virtual threads when available, otherwise a small cached
pool; ClojureScript accepts the option and does nothing). The background
call selects its own snapshot, creates its own execution contract and
counter ledger, bypasses the foreground service-admission slot (it holds a
lookahead slot instead), swallows every failure into the observer, and, when
`:pages` > 1, chains once per page from inside the completed lookahead. A
resident continuation returns as an exact hit in tens of microseconds, so
residency is checked by running the operation; an in-flight set keyed by
[operation normalized-query basis-key] prevents duplicate submissions. Only
pages invoked on the client trigger lookahead: a page served on a retained
snapshot pins an older basis that a client-selected continuation would not
match. The lookahead yields an exact rendered-page hit when the rendered-page
tier is active (cursor expiry disabled, the default); with cursor expiry it
still warms the internal answer tier, so the continuation skips traversal
and pays only rendering.
Alternative rejected: prefetching index nodes or scan chunks below the
engine (needs new backend seams and, on S3, spends GETs that the
continuation may not need); prefetching from inside the engine (would run
under the foreground deadline and thread affinity constraints).

### D8 — Observer at the request boundary, meters from the mandatory ledger

`call-with-ledger` already brackets every public read. When
`(:io-observer opts)` is non-nil, the request records a monotonic start,
snapshots the ledger delta at completion (success or typed error), and calls
the observer with `{:operation :provenance :elapsed-nanos :meters}`; the
scan-cache tier records its per-request hits, misses, and elided commands in
the same ledger under new counter keys. Absent an observer the only added
work is the nil test. The Datahike helper `with-storage-io-stats` resolves
`konserve-s3.core/set-global-io-stats!` and `io-stats-summary` at call time
through `requiring-resolve`; the module gains no dependency.

### D9 — Allocation-free keyword ordering with a byte-identical proof

`canonical-comparator` compares keywords by their runtime string form, which
equals the canonical rendering `":" + namespace + "/" + name` and is cached
on the keyword object; every other operand pair keeps the rendering path.
A generative differential test compares the old and new comparator signs
over keyword pairs drawn from every keyword the secure-format validator
accepts, and the canonical-encoding fixtures stay byte-identical. The two
remaining per-page canonical re-renderings (`relay/build-dependency-context`
comparing lineages, `causal-token/bounded-canonical-value?` per acquisition)
are hoisted to per-basis memos in the request context.
Alternative rejected: caching rendered strings for arbitrary values (an
unbounded, attacker-influenced memo).

### D11 — Range answer reuse derives pages from retained edges

Both public orders are deterministic functions of plan, snapshot, and start
boundary: least-derivation-path order is history-free by construction and
the stable first-discovery order replays deterministically from its
boundary. The first `M` results of a page of `N ≥ M` from one start boundary
are therefore the page of `M`, and a page of `N > M` is the page of `M`
followed by the continuation from its `M`-th edge (`LeastPathResume.dfy`,
`PaginationComposition.dfy`). Today the internal page keeps only the start
and end cursor edges (`page-info`) and drops the per-item cursors the routes
already computed, so the completed-answer value gains an `:edges` vector
parallel to `:data` (format `completed-answer-v3`; older envelopes are
ordinary misses). Lookup adds one range key per completed page: the exact
semantic key minus page size, plus direction and start edge. On an exact miss
the range entry is consulted; a shorter request slices data and edges and
rebuilds page info; a longer request runs the ordinary continuation from the
retained end edge and concatenates. Derived pages go through the same
render-and-publish path, so their rendered pages and exact answers are
published as if computed. Bounded candidate-window routes are excluded
because their content depends on the window size, and `:cache? false`
bypasses range reuse like every other shared-store read. Retention: the
range key keeps the longest completed page (replace-if longer); exact keys
keep what demand produced. Cost of a derived hit is one answer-tier hit plus
rendering of `M` identities and two cursor mints, far below a traversal.
Alternative rejected: storing every page size separately (the status quo),
and deriving from rendered transport pages (they carry no per-item edges and
would require re-authenticating cursors).

### D10 — Formal obligations

- New Dafny leaf `ScanResponseCache.dfy` (stable-discovery tree): for a
  sequence `values`, prefix `p = values[0..k]` and `exhausted ⇒ k = |values|`,
  `Serve(p, exhausted, b, L) = Some(c) ⇒ c = Chunk(values, pos(b), L)`, and
  `Extend(p, Chunk(values, k', L))` for `k' ≤ k` is again a prefix; hence a
  cached fetch is one of the fetches already quantified over by the
  chunk-width invariance lemmas.
- Validity: instantiate `ScalarFrontierCoherence` with the singleton
  dependency `[r]` (lemma `SingletonFrontierIsRelationGeneration`).
- Executable: cache-neutrality differential (commands subset, replies
  equal, outcomes identical) on randomized graphs with interleaved supported
  writes on all four backends and both runtimes; five mutation controls that
  must fail the gate: serve a short non-exhausted prefix, serve values not
  beyond the bound, reuse across a stale relation generation, widen a limit
  or move a bound on miss, deposit a fragment that does not start at the
  scan's first value.
- Lookahead: no new proof; a temporal history in the cache TLA+ model for a
  background publication racing a newer basis (exact-basis keys make the
  stale publication unreachable for the new basis).

## Risks / Trade-offs

- [Shared-tier key touches an attacker-influenced value] → Keys contain only
  internal ids, keywords, and generations from the adapter; no request
  strings enter the key.
- [Memory growth from many descriptors during exhaustive counts] → Entry
  bound plus per-entry prefix cap; exhaustive routes deposit only prefixes
  from the scan start, and eviction is the store's policy.
- [Lookahead spends remote reads on pages nobody requests] → Off by default;
  `:pages` bounds depth; observer attribution makes the spend visible; the
  demo enables it deliberately.
- [Lookahead thread affinity on backends that pin snapshots to the acquiring
  thread] → The background operation acquires and releases its own snapshot
  on its own thread through the ordinary public path.
- [Comparator change silently reorders] → Sign-equality differential over the
  accepted keyword grammar plus unchanged canonical fixtures; strings and
  mixed pairs keep the rendering path.
- [Request-local memo hides a real regression in cache-off oracles] → The
  internal seam disables it for the command-multiset test; public outcomes
  are asserted identical in all three modes.
- [Store shared across tenants of one client] → Same trust boundary as every
  client-private cache tier; keys pin source scope and lifecycle.

## Migration Plan

Additive: new client options default to off (`:lookahead`, `:io-observer`)
or gate-decided (`:scan-cache` default per backend); the request-local memo
and the comparator change need no configuration. Rollback is disabling the
options; no persisted format changes. Docs replace hardcoded proof counts
with a pointer to the CI report.

## Open Questions

None that change the specs or the task breakdown. Whether Datalevin's native
scan cost should be attacked at the LMDB cursor level, and whether the
DynamoDB storage backend should gain the same statistics section as the S3
backend, are recorded as follow-ups.
