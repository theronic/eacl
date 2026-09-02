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

- **Range composition** (spec MAY clause): answering a longer page as a
  resident shorter page plus its ordinary continuation needs the page
  pipeline to re-enter with an internal boundary and concatenate; today
  every entry point internalizes a public cursor. Worth doing once
  continuation pages are common in the demos.
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

## Certification (fresh JVMs, final tree)

| Gate | Result |
|---|---|
| CI battery (`modules/eacl`, `eacl-datomic`, `eacl-datascript`, `eacl-datahike`, `src-build`) | 1,178 tests, 55,564 assertions, 0 failures, 0 errors |
| DataScript ClojureScript suite (clean build, node) | 581 tests, 28,750 assertions, 0 failures, 0 errors |
| Datalevin suite (`:datalevin-test`, module and shared roots) | 438 tests, 23,374 assertions, 0 failures |
| Generators + adversarial + mutation controls (strict-replay JVM) | 13 tests, 896 assertions, 0 failures (five new controls killed) |
| Counterexample replay, strict | 71 tests, 18,228 assertions, 0 failures |
| Eight generated-differential suites | 52 tests, 18,280 assertions, 0 failures |
| Consistency-boundary gate | passed (median p95 1,349 ns under concurrent suites; ceiling 15,000 ns) |
| Routing-certificate gate | passed |
| Stable-discovery fast verifier | 663 Dafny obligations, 0 errors (pin updated from 651) |
| `clj-kondo` over the five source roots | 0 errors, 0 warnings |
| `bin/formal source-closure` | passed (96 roots, 2,420 reachable definitions) |
| `bin/reflection-gate` | clean |
| `bin/formal manifest` | generated; exits 3 by design (assurance withheld by the authored contract) |

The `formal/dafny` tree is unchanged, so `bin/formal verify` was not rerun.
