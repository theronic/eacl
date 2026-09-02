# Implementation notes

Branch `agent/scan-response-cache-and-lookahead`, stacked on
`codex/simplify-v8-core-second-pass` (PR #165). Everything below was
measured on this tree on 2026-09-02 (Temurin 26, one workstation); numbers
are medians of paired same-process runs unless stated otherwise.

## Baseline (before any change on this branch)

Sparse high-sharing fixture (300 users, 600 groups, 10 groups per user,
60 percent of groups own no doc; `doc.view = group->member`).

| Backend | Cache-off page `:first 20` | `can?` cache-off | Adapter scans per page | Adapter cost per scan |
|---|---|---|---|---|
| Datomic in-memory | 769 µs (earlier run 452 µs after JIT) | 85 µs | 35 | 1.7 µs |
| DataScript | 337 µs | 49 µs | 52 (reducer level 11) | 1.1 µs |
| Datahike `:memory` | 357 µs | 50 µs | 52 | 1.8 µs |
| Datahike `:file` | 289 µs | 58 µs | 35 | 2.5 µs |
| Datalevin | 1,391 µs | 380 µs | 35 | 11.7 µs |

Other Datomic baselines: a `:first 1` page 247 µs; a page on a retained
snapshot 520 µs; snapshot acquire and release alone 20 µs; exact rendered
hit 40 µs; `can?` hit 65 µs; exact count 126 µs; a 50-check batch 357 µs
versus 584 µs for 50 scalar checks; a continuation page 383 µs cache-off and
74 µs after a background publication of the same continuation.

JFR profile of the cache-off Datomic page (15 s, 1,263 samples): the largest
single share sat under `secure-format/portable-render` and
`canonical-comparator` (string rendering per key comparison), ahead of the
adapter reads. Attribution by instrumenting the outermost renders per page:
800 from the cursor codec cache's store lookups and 544 from the cursor
token-entry check, both through equality and lookup on canonical sorted maps
whose comparator renders both keys on every comparison; 53 from the relay
lineage comparison; one from the causal-token bound check per acquisition.

## Findings and decisions carried into the design

- The prototype scan cache from the earlier review reproduced on this tree:
  reducer time 75 → 42 µs per page with 91 percent of adapter scans elided
  and identical results for every user, but the client page shell (about
  600 µs on Datomic) dominated the traversal, which is why the comparator
  work was taken first.
- `Caffeine`-backed `standard-lru` hit path measured at about 70 ns and a
  concurrent map at about 18 ns for the same keys; both are two orders of
  magnitude below any backend's scan cost, so the shared tier reuses the
  standard store rather than a bespoke concurrent map.
- The keyword fast path in the canonical comparator measured page 452 → 288
  µs, `can?` 82 → 48 µs, exact hit 61 → 28 µs on Datomic with identical
  results; a 96-keyword pseudo-random corpus plus edge cases agrees in sign
  with the rendering comparator pairwise.
- The user's rule that `:cache? false` switches off only what reads or
  writes the shared store made the request-local memo unconditional; an
  internal dynamic seam disables it for the command-multiset oracle.
- The per-relation validity scope reads the generation from a proof the
  request already resolved (`proof-frame/resolved-generation`); the
  managed-answer path resolves the plan closure before evaluating, so no
  acquisition is added. When nothing is resolved (no managed reuse), the
  shared tier is bypassed for that scan and the memo still applies.
- Range reuse required retaining per-result edges: the plain page routes
  compute one cursor per item and then dropped all but the first and last.
  The completed-page validator now admits the two optional fields, and the
  range key strips the page size from the public and internal queries and
  from the execution demand (`:demand {:size n}` was the last size-bearing
  component). Composition of a longer page from a resident shorter one is
  recorded as a follow-up: it needs the page pipeline to re-enter with an
  internal boundary.
- A lookahead pool with a discard policy silently swallowed rejected tasks
  and left their in-flight claim behind; the pool now aborts and the
  submitter releases the claim, so a dropped continuation can be resubmitted.
- The three CI-only flakes of the previous pass were understood before this
  work: they are not cache-semantic and stay fixed.

## Two defects the certification battery found before the PR

- **Cursor recovery poisoned the shared tier.** A continuation whose cursor
  predates a relevant write is recovered on the older basis: the request's
  proofs carry the current generations while the recovery scans a detached
  adapter over the older slice. The first version of the scan-cache context
  was bound per request and applied to every routed fetch, so those replies
  were deposited under post-write scopes and a later fresh enumeration on
  the new basis missed the new tuple (Datomic and Datahike recursive
  contract tests, the recursive cursor-fallback test, and two counterexample
  replay entries). The context now names the adapter it was built for and
  the engine applies it only to scans against that adapter; a regression
  test drives the same sequence on the sparse fixture.
- **Range derivation defeated checkpoint reuse on recursive plans.** A page
  derived from a longer resident page hands out a cursor at an ordinal with
  no stored checkpoint, so the continuation replayed from scratch (the
  checkpoint-reuse test). Only least-path pages now carry the reuse marker;
  first-discovery pages keep their checkpoints.
- A third, found by inspection: the observer's operation name was first put
  under `:request-operation`, which batch endpoints use to key scalar
  decisions; it moved to a private key.

## Paired measurements (shared scan tier, sparse fixture, final tree)

Three interleaved trials per mode after one warm-up sweep, fresh page sizes
per sweep so the answer tiers miss, range reuse disabled through its seam so
only scan reuse is measured.

| Backend | Tier disabled p50 page | Tier enabled p50 page | Change | Commands per 300-page sweep |
|---|---|---|---|---|
| Datomic in-memory | 270.0 µs | 220.4 µs | −18 % | 7,816 → 0 |
| DataScript | 228.9 µs | 193.7 µs | −15 % | 7,816 → 0 |
| Datahike `:memory` | 237.9 µs | 208.2 µs | −12 % | 7,816 → 0 |
| Datahike `:file` | 279.1 µs | 230.5 µs | −17 % | 7,816 → 0 |
| Datalevin | 585.7 µs | 379.2 µs | −35 % | 7,816 → 0 |

Every backend passes the adoption gate (oracle equality by the integration
and neutrality tests, 100 percent of commands elided after warm-up, at least
5 percent lower p50), so the tier ships enabled by default on all five
stores. Datomic first sweep with an empty tier versus disabled (four
alternating pairs, fresh clients): disabled 426/396/393/436 µs, enabled
376/374/366/362 µs; the tier fills within the sweep and there is no
measurable regression.

## Deviations from the task list

- 2.3 (hoisting the relay lineage comparison and the acquisition-time bound
  check): not done as a separate step; both fell under the keyword
  comparator fix, which removed the rendering cost at its source. Measured
  outermost renders per page after the fix are the same call sites, now on
  cached keyword strings.
- 4b.1 did not bump the completed-answer envelope format: the two optional
  fields are validated by the existing validator and older values without
  them simply cannot derive, which is a miss, not an incompatibility.
- 4b.4 composition is a recorded follow-up (see above).
- 6.4 (temporal history for a background publication racing a newer basis):
  not added as a TLA+ history; the exact-basis key already makes a stale
  publication unreachable for a newer basis, and the Datomic lookahead test
  checks the basis-moved case executably.

## Recorded follow-ups (not in this change)

- **Request shell cost for derived pages**: a page served from a segment
  still decodes its cursor, resolves the proof frame, builds keys, and mints
  two cursors (about 150 µs on the JVM against a 30–55 µs rendered exact
  hit). A rendered-page tier keyed by the public query with the *decoded*
  boundary, or cheaper cursor minting, would bring derived pages near the
  exact-hit floor.
- **Operator-expression routes in range reuse**: intersection and exclusion
  covers keep their own checkpoint contract and are not marked reusable;
  they can join once their cursor edges are validated by the neutrality
  differential.
- **Order-insensitive exact counts**: the exhaustive reducer keeps an
  admitted set and one consumer scan per discovered grant; a merge or
  bitmap count saves CPU and memory but not remote reads, because every
  intermediate must still be scanned once. Needs a denotation-equivalence
  proof the route docstring already demands.
- **Compact reducer checkpoints**: recursive-plan checkpoints grow about 96
  bytes per admitted result; a compressed bitmap would let them survive
  eviction and avoid the quadratic replay measured on the demo.
- **Endpoint-scoped dependency stamps**: proof-managed reuse and the scan
  tier invalidate per relation; per-endpoint stamps would keep entries
  valid across writes to other resources, at a write-path cost on every
  backend.
- **Materialized recursive closure**: only pays for deep hierarchies; needs
  a maintenance invariant proof and stamps on the closure relation.
- **Datalevin native scan cost**: 11.7 µs per scan against 1.1 to 2.5 µs
  elsewhere; the scan tier hides it for repeat traffic, the cursor-level
  cost remains.
- **DynamoDB storage-backend statistics**: the S3 konserve backend carries
  opt-in I/O counters; the DynamoDB backend does not, so demos on it cannot
  attribute storage reads through the Datahike helper yet.
- **Operator-engine scan seams**: seekable and recursive operator scans and
  batched membership probes bypass the routed fetch seam and therefore the
  scan tiers.

## Any-window range reuse, composition, and recursive-plan participation (2026-09-02, D12–D14)

Asked why range reuse stopped at acyclic plans and told to do whatever is
most performant for the caller, the range tier was rebuilt around walk
segments (D12), composition of a window that runs past a segment with one
continuation (D13), and participation of the stable first-discovery route
with page-size-independent checkpoints (D14).

What changed in the code:

- `eacl.client.range-reuse`: walk key (semantic key minus size and
  boundary), window extraction from the authenticated internal boundary,
  segments with an edge index, lookup from any retained boundary in both
  window kinds (complete page, or partial page plus continuation request),
  composition, publication with adjacent-segment merging (append, prepend,
  covered), and per-walk caps. Stats gain `:partial-hits` and `:extensions`.
- `eacl.client.orchestration`: the evaluation bindings are factored into
  `evaluate-with`, so the composition remainder runs under the same
  bindings as any evaluation; page callers supply the continuation compute
  (internal query with the window replaced) through a private option key;
  `:range-reuse` is a validated client option (`:max-entries`,
  `:max-results-per-walk`, `:max-segments-per-walk`, or `false`).
- `eacl.engine.v8`: first-discovery pages carry the reuse marker; the
  checkpoint key keeps the page size as the series identity, and a
  continuation names the series it resumes through the internal query's
  `:checkpoint-size` (set by range reuse for windows that leave a retained
  segment).
- Counter `:range-compositions`; docs and README describe the segments.

Segments are scoped like managed answers (the complete proof descriptor
over the walk's relation closure when proof-managed reuse applies, else the
exact basis). The first cut keyed them by exact basis; the backend
contract's proof-equivalent-write section then found that a derived page
had never traversed and so had published no checkpoint, and after an
unrelated write neither a segment (new basis) nor a checkpoint (never
written) could serve its continuation. Under the descriptor scope the
segment survives the unrelated write and serves the continuation directly.

The first cut of D14 dropped the page size from the checkpoint key. The
backend contract tests then failed: the continuation store keeps one latest
boundary per key, so a long oracle page and a short page series over one
walk shared a slot and the series replayed after any longer page. The size
is back in the key as the series identity; instead, every retained segment
remembers the series that produced its end, and a continuation that starts
at a segment's end (or the remainder of a composition) names that series
(`:checkpoint-size`), so a page-size change resumes the frontier whenever
the segment is retained and falls back to its own series key otherwise. Two
isolation tests seed their oracle with `:populate-cache? false` and the
three checkpoint-mechanism tests opt out of range reuse (`{:range-reuse
false}`) because the segment tier would otherwise answer their
continuations without touching the store; the shared contract's
"resumes the private checkpoint" assertions accept a segment hit, and its
bounded-store section now competes with a second walk.

Measured on the same JVM (Datomic in-memory and DataScript; `:populate-cache?
false` on the measured requests so the exact tier never serves a repeat;
medians of 200 requests; a 54-result segment retained from one page):

| Window | Backend | Exact hit | Served from segment | Traversal | Composition (half from the segment) | Full traversal |
|---|---|---|---|---|---|---|
| 20 results | Datomic | 56.5 µs | 185.2 µs | 434.5 µs | 267.2 µs | 357.0 µs |
| 20 results | DataScript | 42.6 µs | 166.7 µs | 302.3 µs | 228.4 µs | 262.6 µs |
| 50 results | Datomic | 37.4 µs | 149.3 µs | 520.9 µs | 197.0 µs | 365.2 µs |
| 50 results | DataScript | 28.2 µs | 136.8 µs | 362.3 µs | 161.8 µs | 256.8 µs |

A window served from a segment costs 2.3–3.5× less than its traversal at
these sizes; composition saves in proportion to the share the segment holds.
At a 6-result window (a 31-result walk) the served page was 190.6 µs against
268.3 µs and composition was neutral (249.5 against 247.1 µs): below about ten
results the request shell (cursor decode, proof frame, two cursor mints,
keys) dominates both paths. That shell is the next target and is recorded
under follow-ups. Recursive plans, DataScript, quiet machine. On the 12-result
`:folder-chain` fixture a `:first 7` continuation from a `:first 5` page's
end cursor costs the same with the series hint as with range reuse off
(180.3 against 179.2 µs; a range lookup that serves nothing adds about
10 µs), because replaying five results is as cheap as the lookup. On a
300-folder parent chain at ordinal 200: the same-size continuation resumes
its checkpoint in 267.5 µs; a `:first 7` continuation resumes the segment's
series through the hint in 213.6 µs; without the hint (range reuse off, the
seven-series has no checkpoint) it replays in 768.2 µs, and with the store
cleared 776.1 µs. Replay grows with the prefix, the resumed cost does not.

Certification of this pass: `RangeAnswerReuse.dfy` gained
`WindowInsideSegmentIsThePage` and `WindowIsTailPlusContinuation` (10
obligations; fast verifier pin 669 → 673); three executed mutation controls
(`range-window-position-off-by-one`, `range-window-past-segment-served-as-complete`,
`range-composition-order`; registry 149 → 152); the DataScript neutrality
differential gained random forward and reverse windows from arbitrary
retained boundaries; Datomic integration tests cover inside-window service,
composition (fewer adapter commands than the uncached page), and windows from
a boundary computed elsewhere; DataScript continuation tests cover a derived
window on a recursive plan continuing from the checkpoint and a page-size
change resuming the frontier. Gate results are in the certification table.

## Demo deployment (eacl-demo, 2026-09-02)

The live demos were repinned twice with `npm run upgrade:eacl`: to
`4139bb0d` (eacl-demo PR #71, production run 33657419165, five of five
profiles deployed and smoked) and to `340b3559` (PR #72, run 33663690461,
five of five). Before each merge the four server profiles' handler,
boundary, operations, profile, and reader suites and the node policy and
contract suites passed locally against the pin. Fifteen sequential POSTs
per endpoint from one client, median / p90 in milliseconds:

| Endpoint | Before (9e0105f2 pin) | After 4139bb0d | After 340b3559 |
|---|---|---|---|
| datomic.demo.eacl.dev lookup | 567.8 / 616.2 | 561.1 / 576.3 | 577.1 / 635.0 |
| datomic.demo.eacl.dev count | 541.4 / 589.4 | 669.6 / 784.5 | 567.4 / 690.2 |
| datalevin.demo.eacl.dev lookup | 569.5 / 608.9 | 573.1 / 707.6 | 571.4 / 644.0 |
| datalevin.demo.eacl.dev count | 804.5 / 1081.9 | 570.5 / 656.7 | 549.9 / 573.3 |

The live figures are dominated by network and Lambda invocation (about
half a second per call from this client) and do not resolve the
sub-millisecond engine gains; they show no regression. The demos keep the
default client options; wiring the I/O observer and the storage statistics
into the explorer is a demo-side follow-up.

## Certification (fresh JVMs, final tree)

| Gate | Result |
|---|---|
| CI battery (`modules/eacl`, `eacl-datomic`, `eacl-datascript`, `eacl-datahike`, `src-build`) | 1,206 tests, 57,071 assertions, 0 failures, 0 errors |
| DataScript ClojureScript suite (clean build, node) | 588 tests, 29,539 assertions, 0 failures, 0 errors |
| Datalevin suite (`:datalevin-test`, module and shared roots) | 442 tests, 23,411 assertions, 0 failures |
| Generators + adversarial + mutation controls (strict-replay JVM) | 13 tests, 901 assertions, 0 failures (ten new controls killed) |
| Counterexample replay, strict (smoke-alias JVM) | 71 tests, 18,228 assertions, 0 failures |
| Eight generated-differential suites | 52 tests, 18,280 assertions, 0 failures |
| Consistency-boundary gate | passed (median p95 1,113 ns on the smoke-alias JVM; ceiling 15,000 ns) |
| Routing-certificate gate | passed |
| Stable-discovery fast verifier | 673 Dafny obligations, 0 errors (pin updated from 651: `ScanResponseCache.dfy` 12, `RangeAnswerReuse.dfy` 10); scan-response-cache bridge 4,000 serve and 4,000 extend cases, 4 controls killed |
| `clj-kondo` over the five source roots | 0 errors |
| `bin/formal source-closure` | passed (96 roots, 2,438 reachable definitions) |
| `bin/reflection-gate` | clean |
| `bin/formal verify` (whole `formal/dafny` tree) | 48 modules, 0 errors |
| `bin/formal manifest` | generated; exits 3 by design (assurance withheld by the authored contract) |

`ScalarFrontierCoherence.dfy` gained one lemma (below); the module verifies alone (84 obligations, 0 errors) and the whole-tree `bin/formal verify` runs in CI's formal workflow.

## Certification pass against the formal models (2026-09-02, after the PR opened)

The implementation was checked against the models a second time, looking for claims made by one side and not the other. Three gaps were found and closed; no code change was needed.

1. **Task 6.1 promised a singleton-frontier lemma that was never written.** The scan-response cache scopes a stored prefix by one relation's generation, which is `DependencyFrontier(snapshot, [relation])` in the scalar-frontier model. `SingletonFrontierIsRelationGeneration` now proves that frontier equals `RelationAt(snapshot, relation).generation`, so the existing monotonicity and stamping lemmas cover the cache's invalidation claim.
2. **Range answer reuse had no model.** `RangeAnswerReuse.dfy` (over `StablePagination.Page`) proves the derived page is the prefix of the resident page of its own length, that a resident page which reached the end answers every larger request unchanged, and the derived next-page flag. Two executed mutation controls (`range-derivation-end-edge-off-by-one`, `range-derivation-ignores-next-page`) pin `eacl.client.range-reuse/derive-page` to it.
3. **`ScanResponseCache.dfy` had no source bridge.** `scan_response_cache_refinement_bridge.clj` runs the production `serve` and `extend-entry` against a transcription of the model's `Serve`, `Extend`, and `Chunk` over randomized sequences, bounds, and limits in both directions (1,632 of 4,000 serve cases served, 2,017 of 4,000 extend cases extended, the rest correctly declined), and checks the proved properties directly on every reply.

The stable-discovery README, `docs/formal-verification.md`, and the production decision inventory now describe the two leaves and the bridge; the leaf and obligation counts those docs used to carry are gone (the verifier pins the count).
