## Why

On the S3-backed Datahike demos the cost that matters is index-node fetches, and EACL still re-issues identical adapter scans across requests that traverse the same edges: on the sparse high-sharing fixture a page issues 35 scans of which 91 percent recur across users (measured 2026-09-02 on the current tree, results identical with the prototype cache). On every in-memory backend the traversal is no longer the bottleneck either: a cache-miss page costs 289 to 1,391 µs while its adapter reads cost 60 to 410 µs, and a JFR profile attributes the largest single share of the remainder to canonical-map comparisons that render both keys to strings on every comparison (a 20-result page performs about 1,400 such renderings; an allocation-free keyword comparator measured page 452 → 288 µs, `can?` 82 → 48 µs, exact hit 61 → 28 µs with byte-identical output). A page continuation published in the background turns the next page into a 74 µs exact hit instead of a 383 µs miss, and no production-safe way exists today to observe adapter or storage I/O per request.

## What Changes

- Add an **exact scan-response cache** at the engine's single physical read seam, on every backend and runtime: a request-local memo that is always on, and a client-private cross-request tier scoped by source lineage, schema generation, and the scanned relation's generation from the request's proof frame. It serves a reply only when it can reproduce the adapter's reply exactly, forwards the identical command otherwise, never issues a command the uncached run would not, and is invisible to order, limits, cursors, checkpoints, answers, and errors. Storage is the existing bounded cross-runtime cache store.
- Add **asynchronous page lookahead** (JVM): after a page with a next page is served and published, the client may publish the deterministic continuation on a bounded background executor so the caller's next request is an exact hit. Off by default, tunable per client (`:lookahead {:pages :max-inflight}`), never on the foreground path, never speculative beyond the continuation, separately accounted, and a no-op on ClojureScript.
- Add **opt-in request I/O observation**: a per-client observer receives each request's exact adapter meters (commands, fetched values, identity conversions, elided scans, elapsed time) when configured and costs one nil check otherwise; the Datahike module gains a helper that exposes the S3 storage backend's built-in GET/PUT statistics for demos without a hard dependency.
- Add **range answer reuse**: a completed page on a plain route (least-path or stable first-discovery) retains each result's cursor edge, so every completed page is a contiguous segment of the walk's fixed result sequence. Any window inside retained segments (a shorter page, a continuation inside a longer page, any boundary the segment holds) is served without traversal; a window that runs past a segment is the segment's tail plus one continuation for the remainder; adjacent segments merge; retention is bounded per walk and per tier. Recursive-plan checkpoints become page-size independent so a continuation past a segment resumes the stored frontier whatever page size produced it.
- Make the canonical secure-format comparator **allocation-free for keyword keys** using the runtime's cached keyword string, with a byte-identical ordering proof by differential test; hoist the remaining per-request canonical re-rendering on the page path.
- Replace hardcoded proof-effort counts in documentation with references to the CI verification report; the machine-checked assurance contract remains the only ratchet.
- Record, with the corrected analysis, the follow-ups this change deliberately excludes: order-insensitive counts (CPU and memory, not remote reads), compact reducer checkpoints, endpoint-scoped dependency stamps, materialized recursive closure, the Datalevin native scan cost, and the DynamoDB storage backend's missing I/O statistics.

## Capabilities

### New Capabilities

- `exact-scan-response-cache`: Elide-only reuse of exact adapter scan-response prefixes at the physical read seam; request-local memo plus a bounded cross-request tier with a relation-generation validity scope; invisible to every semantic identity.
- `asynchronous-page-lookahead`: Optional background publication of a served page's deterministic continuation, tunable per client, with no effect on the foreground request's result, latency, deadline, or admission.
- `request-io-observation`: Opt-in per-request adapter I/O meters delivered to a client observer, zero-cost when absent, plus storage-backend statistics integration for Datahike deployments.
- `range-answer-reuse`: Serving any page window of a walk from retained completed segments on the same exact basis (shorter pages, continuations inside a segment, any retained boundary), composing a window that runs past a segment from the segment's tail plus its continuation, merging adjacent segments, with bounded retention and no public content change.

### Modified Capabilities

- `enumeration-continuation-reuse`: "Adjacent pages resume private traversal state" identifies a saved frontier by walk and delivered boundary alone, so continuations resume it across page-size changes.
- `demand-bounded-evaluation`: "Cache retains only demanded work" names exact scan-response prefixes and the request-local memo as retained artifacts and states the elide-only rule.
- `verified-subproblem-cache`: "Cache values denote complete immutable subproblems" admits exact scan-response prefixes as a value class alongside completed subproblems.
- `authorization-request-efficiency`: "Mandatory resource meters are exact and observation is optional" defines the optional per-request observer and its zero-cost absence.

## Impact

- Core (`eacl`): a new scan-cache namespace at the read seam, the routed fetch wiring in the engine facade, client options and the lookahead executor in orchestration, the secure-format comparator, request counters, cache docs.
- `eacl-datahike`: a small storage-statistics helper; no adapter behavior change.
- Formal: one new Dafny leaf for exact-slice serving and prefix extension, five executed mutation controls, the cache-neutrality differential in the parity suites, and gate/manifest bookkeeping.
- Tests and benchmarks: cross-backend and cross-runtime cache-neutrality tests, paired same-process benchmarks on all four backends, lookahead determinism and isolation tests, observer cost tests.
- Documentation: `docs/cache.md`, `docs/v8-subproblem-cache.md`, `docs/stable-discovery-engine.md`, module READMEs, `docs/formal-verification.md`, `formal/README.md`.
