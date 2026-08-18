# Adversarial review: `hierarchical-demand-segment-cache` (2026-08-18)

> Consolidated report: [`docs/reports/2026-08-19-hierarchical-cache-investigation.md`](../../../../docs/reports/2026-08-19-hierarchical-cache-investigation.md).

Reviewed against the code on `docs/improve-readme` (v8 stable-discovery engine,
head `9f23109`). The original artifacts are preserved in this folder as
`original-*.md`; the rewritten `proposal.md`, `design.md`, `specs/` and
`tasks.md` one level up supersede them. The REPL harness that produced every
number below is `bench/seg_bench.clj` (REPL-only, not on any classpath).

## Verdict

**Reject the design as written; keep one sound kernel of it; put a
prerequisite fix ahead of it.**

1. The proposal's premise — "repeated multi-hop walks remain the dominant
   cost" — is unmeasured in the repo and false on Datomic. No benchmark
   compares super-user and normal-user visibility. The one artifact that
   showed "walks dominate" was a deleted benchmark on the retired
   merge/indexed engine under opt-in `:evaluation :complete-denotation`.
   Measured here: on an in-memory Datomic peer the entire reducer walk of a
   `:first 20` page is **90–120 µs** and the adapter scans **~11 µs each**
   (3–5 per page), inside a cache-miss page that costs **1.4–2.8 ms**.
   The dominant cost is a client-layer bug: the sealed-plan cache misses on
   every request (Finding F1), followed by per-request schema reads.
2. The "level-k composition" and "start-set fingerprint" mechanisms are
   unsound for the stable reducer. The sequence a plan node emits depends on
   the request's global admission set, not on its start set; that is exactly
   the Dafny counterexample that killed the previous denotation tier
   (`formal/stable-discovery/CacheBoundary.dfy`,
   `ContextFreeDenotationIsNotAStableTrace`). "Demand" is not a per-node
   quantity in the reducer either (Findings F2–F4).
3. What survives is the level-1 case, restated correctly: an **exact
   scan-response prefix cache at the `fetch-fn` seam**, keyed by the read
   descriptor plus a per-relation validity stamp, that only ever *elides* an
   adapter command it can reproduce exactly and never issues one the
   uncached run would not. That is a physical accelerator (a cross-request
   sidecar), covered by the already-proved width/retention invariance and
   by the scalar-frontier theorem with a singleton dependency vector. It is
   also, essentially, the retired relationship-projection tier — which the
   stable-discovery change cut as "purely accelerative"; the numbers below
   say when that judgement holds (Datomic-mem, shallow schemas) and when it
   does not (Datahike, sparse high-sharing schemas, `can?` on hot resources).
4. The existing weighted-LRU atom store cannot host a per-scan hot path
   (1.4 µs per hit single-threaded, serialising at 0.5 M ops/s under eight
   threads). A per-scan tier needs a lock-free structure (Finding F8).

Confidence: high on F1–F4, F6–F8 (measured or read from source/Dafny);
moderate on the Datahike absolute numbers (small fixture, warm konserve
cache; only reducer-level timings taken there); low on how the wins
translate to production hardware and remote stores.

## Findings

### F1 — The Datomic client re-seals the plan on every request (existing bug, largest cost)

`eacl.engine.v8/stable-plan` keys its global 128-entry FIFO by
`[backend-id source-scope source-lifecycle native-revision root]`.
`eacl.datomic.impl/with-request-engine` builds the engine adapter with
`(backend/snapshot-adapter db)` and **no options**, so
`eacl.datomic.backend/snapshot-adapter` mints a fresh random
`source-lifecycle` per call. After 50 pages the plan cache held 128 entries
for one database, one revision, one root and **128 distinct lifecycles**.
`seal-plan` costs ~1 ms on this fixture (schema-definition reads +
canonical encoding + digest) and runs on every `lookup-resources`,
`can?`, `count-*` call through that path — including exact-hit paths that
need the fingerprint for the answer key.

Effect, measured with a REPL-only `with-redefs` returning a pre-sealed plan:

| Datomic-mem, Fixture A | thrashing (median) | plan pre-sealed | Δ |
|---|---|---|---|
| `lookup-resources :first 20`, `:cache? false` (owner) | 1,428–2,046 µs | 592 µs | 2.4–3.5× |
| same, super-user | 2,826 µs | 427 µs | 6.6× |
| exact answer hit | ~600 µs | 328 µs | 1.8× |
| `can?` bypass | 985 µs | 172 µs | 5.7× |

The Datahike client path passes its lifecycle correctly and does not thrash
(0 new plan entries over 30 requests). Even a correct key of
`[… lifecycle native-revision root]` re-seals after every transaction; the
plan is a pure function of schema definitions and should be keyed by schema
generation. This is a separate, prerequisite change.

### F2 — Level-k composition is unsound; the reducer's emissions are context-dependent

The reducer (`eacl.engine.stable-reducer`) is a right-edge-stack DFS with a
**global** admission set (`work-id`, merge points keyed by target node +
entity) and width-one release. The results emitted "under" a plan node are
the node's scan values *filtered by everything already admitted earlier in
this request*, interleaved with the DFS of every consumer pushed by each
grant. Two requests with equal start sets and different admitted sets emit
different subsequences. The proposal's `compose()` ignores admission,
assumes products at non-root nodes consume demand (only root grants do),
and assumes a fixed "level" that recursion (`:recursive?` plans) does not
have. `bounded-physical-execution` already forbids substituting a flat
subproblem sequence "without a proof that substitution preserves the
canonical discovery sequence"; the proposal offers no such proof and one
cannot exist for context-free node segments (`CacheBoundary.dfy`).

### F3 — "Demand" is not a per-node quantity

`target` is a global count of root emissions (`page-size + 1` for a page,
`+∞` for exhaustive routes). Intermediate scans have no demand; the only
bound they see is the physical chunk width. "A segment populated under
demand D" is therefore ill-defined except at the raw-scan level, where the
natural quantity is *the prefix physically fetched so far*, and the prefix
rule is automatic.

### F4 — Exact start-set fingerprints defeat the sharing the proposal wants

Super-user and normal-user walks share nothing above single-intermediate
scans (their start sets differ at every level). The DFS also means a
super-user `:first 20` page touches *one* account's servers, so it warms
almost nothing for other users. The sharing that exists is (a) peers who
reach the same intermediate (same account/group scan), (b) `can?` on a hot
resource — reverse scans from the resource are identical for every subject
checking it, and (c) empty scans in sparse graphs (70 % of group→doc scans
in Fixture B). All three are captured by keying on the single read
descriptor; none by plan-node + start-set.

### F5 — The proposal re-invents the retired projection tier without confronting why it was cut

`openspec/changes/adopt-stable-discovery-enumeration/design.md:119`: the
projection tier "is order-safe but purely accelerative … duplicates bytes
the storage layer already caches … production occupancy measured trivial".
The measurements below are the confrontation. Storage-layer byte/node
caching does not remove the seek + realise + classify + retry cost per
scan (~7–30 µs on Datomic-mem/Datahike-file, more on remote stores), and
sparse schemas issue 20–40 scans per page.

### F6 — The fetch contract "chunk shorter than `:limit` ⇒ exhausted" is load-bearing and unwritten

`stable_reducer.cljc:239` derives `more?` from `(= (count values)
physical-chunk-size)`; a short chunk drops the scan frame. No spec states
this. Any cache that serves a partial prefix without topping up silently
truncates results. The improved design makes the rule normative and makes
the cache elide-only.

### F7 — Formal fit

The proved invariances (`ChunkedScan.dfy`,
`OneValueScanNormalization.dfy`) model a fetch *as* `Chunk(values, offset,
limit)` over one fixed sequence. A cache that returns exact slices of that
sequence is inside the model; a cache that returns anything else is not.
Validity follows from `ScalarFrontierCoherence` with `dependencies = [r]`
(a singleton frontier is that relation's generation), plus the adapter
obligation that a scan is a function of one relation's tuple slice.
`proof-frame/subset-descriptor` already derives a single relation's stamp
from the complete request proof, fail-closed outside the closure.
`SubproblemCanReuse` in the Dafny model demands equal full dependency
vectors, stricter than needed; a new small lemma is required.

### F8 — The subproblem store is unfit for a per-scan hot path

| operation | single-thread median | 8 threads × 20 k ops |
|---|---|---|
| `subproblem/lookup!` hit | 1.4 µs | 1.9 µs/op amortised, **0.52 M ops/s total** |
| `ConcurrentHashMap.get` | 0.08 µs | 0.34 µs/op, 2.9 M ops/s |

Every hit does two or three `swap!`s on the metrics atom and a `touch-entry!`
`swap!` (LRU vector conj + compaction) on the state atom. Twenty-three hits
per page at ~2 µs of serialised time each caps the whole process near
20 k pages/s from cache bookkeeping alone. The store also creates a fresh
exact store per snapshot; per-scan entries must live in a schema-generation
scoped, lock-free structure with sampled/CLOCK eviction.

### F9 — Limits and telemetry

A tier that returns results without reducer transitions breaks
`:max-derived-grants`/`:max-advanced-datoms` accounting and the observer
counters. A fetch-fn-level cache preserves both exactly (served values still
flow through `fetch-values`).

### F10 — Smaller defects in the original text

`:continuation {… :reducer-state-hash}` cannot resume anything (the
checkpoint store already retains real resumable state); `:summaries
{:roaring :bloom}` are premature and only sound if stamped exactly like the
payload; "plan nodes the schema marks as high-sharing" — no such marker
exists; reusing `:denotation-max-weight` keeps alive the key that should be
removed; page size ≠ demand (`target = size + 1`); "super-user warm-up then
normal-user pages" as a success criterion measures a case DFS almost never
produces.

## Measurements

All on this machine, JVM warm, in-memory stores; medians of 100–300
samples unless stated. Fixtures: **A** = proposal's motivating case
(platform → 60 accounts × 40 servers, `server.view = account->admin +
shared_admin`, `account.admin = owner + platform->super_admin`);
**B** = high-sharing sparse case (300 users, 400 groups, 60 memberships
per user, 70 % of groups own no doc, `doc.view = group->member`).

### Where a Datomic-mem cache-miss page spends its time (Fixture A)

| component | cost |
|---|---|
| raw adapter scan, 64 values realised | 10.8 µs |
| whole reducer walk, `:first 20` (5 commands, 47 transitions) | 91–121 µs |
| `seal-plan` | ~1,050 µs (min 558) |
| bypass page, plan thrashing | 1,428–2,826 µs |
| bypass page, plan pre-sealed | 427–592 µs |
| exact answer hit, plan thrashing / pre-sealed | ~600 / 328 µs |

Stack samples with the plan pre-sealed: `capture-result-context` ≈ 50 %
inclusive (of which `eacl.datomic.schema/read-schema` from `prepare` —
all relations and permissions read on every request — ≈ 21 %),
`edge-page` (reducer + fetch) ≈ 26 %, adapter construction ≈ 6 %,
`verified-kernel/kernel?` (3 calls per request) ≈ 6 %.

### Best case for a scan cache (Fixture B, reducer level, Datomic-mem)

Per page: 37 adapter commands, 103 transitions on average.

| sweep over all 300 users, `:first 20` | µs/page | adapter calls |
|---|---|---|
| no cache | 331 | 11,183 |
| scan-prefix cache, first sweep | 235 | 692 (94 % elided) |
| scan-prefix cache, second sweep | 178 | 0 new |

Result sequences identical with and without the cache for every user
(oracle equality). The 178 µs floor is the reducer's own transition cost
(~1.7 µs per transition), which no scan cache can touch. Full client bypass
page on this fixture: 734 µs; so the cache is worth ~150 µs of ~700 µs
(with the plan bug fixed) and ~7 % of today's page.

### Datahike (Fixture B, 100 users, 200 groups; 34 commands per page)

| store | reducer, no cache | reducer, cache warm | client bypass page | client exact hit |
|---|---|---|---|---|
| `:memory` | 1,083 µs | 354–433 µs | 1,281 µs | 145 µs |
| `:file` (warm konserve) | 282 µs | 166–191 µs | 1,057 µs | 128 µs |

On Datahike the client shell is lean and the walk is a large share of a
miss; the scan cache is worth 100–700 µs per page here (moderate
confidence: small fixture; the `:memory` backend's high cold cost was not
investigated).

### `can?` (Datomic-mem, Fixture A)

Reverse walk from the resource: 2–5 commands, ~30 µs raw. Bypass 985 µs
thrashing → 172 µs pre-sealed; exact hit 152 µs. All reverse scans for one
resource are shared across every subject that checks it.

### Page-size sharing today

`:first 50` then `:first 20` on the same subject: exact miss, then miss,
then hit only on the identical repeat (bounds are in the answer key and
page size in the checkpoint key). Under a scan cache the second request
hits every scan and pays only ~50 reducer transitions (~85 µs); no separate
"prefix answer" tier is justified.

## What the rewrite keeps, drops, adds

Keeps: client-private, best-effort, weight-bounded, frontier-stamped reuse;
completed-answer/cursor identities untouched; page sizes share underlying
work; formal statement before default-on.

Drops: hierarchy/levels, plan-node keys, start-set fingerprints, `:via`
trails, composition, continuation stubs, summaries, demand as a key
dimension, reuse of `:denotation-max-weight`.

Adds: prerequisite plan-cache fix; elide-only exact-slice semantics; the
short-chunk rule as a spec obligation; lock-free store requirement; `can?`
and reverse pages in scope; the cleanup of retired cache remnants; a
benchmark gate that must be cleared before default-on.

## Reproduction

```clojure
;; nREPL started with: clojure -M:dev:nrepl --port 7799
(load-file "openspec/changes/hierarchical-demand-segment-cache/review/bench/seg_bench.clj")
(in-ns 'seg-bench)
(def conn-a (mem-conn!))  (def acl-a (seed-a! conn-a {:accounts 60 :servers-per-account 40}))
(bench "bypass" #(page acl-a (->user "owner-7") :server :view 20 :cache? false))
(count (:entries @@#'eacl.engine.v8/stable-plan-cache))          ; F1
(def conn-b (mem-conn!))  (def acl-b (seed-b! conn-b {:users 300 :groups 400 :groups-per-user 60 :empty-fraction 0.7}))
;; see `sweep`, `scan-prefix-fetch-fn`, `store-microbench` in the harness
```

---

# Round 2 (2026-08-18, later): loophole hunt, live-app profiling, implementation

Directive: find every loophole in the round-1 strategy, fix, verify against
the real workload (`eacl-datomic-solidjs`, 110k servers, nREPL 55891), and
implement once confident. Outcome: two of the three items were implemented
and verified; the scan-response cache stays proposed and gated.

## Loopholes found in the round-1 strategy (and what was done)

| # | Loophole | Resolution |
|---|---|---|
| R2-1 | The strategy ranked the scan-response cache first; the live app showed `seal-plan` in **80 %** of `can?` samples and `read-schema` in ~35 % of the rest — cache work was ~5 %. | Re-prioritised: `fix-datomic-request-overhead` (implemented) → `membership-probe-point-check` (implemented) → scan-response cache (gated, not implemented). |
| R2-2 | `can?` was O(subjects holding the permission): denied check on a 5,000-owner account **16.3 ms**; the strategy did not address it. | Membership-probe check: 24 µs; oracle-equal on 4,860 live pairs + 8,680 fixture pairs; new `eacl.engine.point-check-test`. |
| R2-3 | Scan-response cache "helps pages": on the warm Datomic peer forward-page scans cost 2–10 µs, and my prototype's hit path cost about the same → **no measurable gain for lookup pages** (156 vs 159 µs/page at 74 % hits). It helped only the many-tiny-scan reverse walk (55 → 25 µs), which the probe check now makes moot. | Proposal demoted to "conditional, backend-specific (Datahike/remote), gate-decided"; hit path must be sub-µs to matter on Datomic at all. |
| R2-4 | Plan key by schema stamp via `:proof-frame []` would issue a proof op per request — `:raw-can` op envelope pins zero and exact hits promise no generation reads. | Key uses the identity the caller already knows (client generation cache; raw facade's direct read as `:schema-identity`); no proof op. |
| R2-5 | Dropping the lifecycle from the plan key let test adapters aliasing `{:source-id :test}` share plans across tests (global cache). | Lifecycle kept; the raw facade mints one process-stable lifecycle (the thrash was the *random per-call* lifecycle). |
| R2-6 | A parsed-schema memo keyed by `basis-t` is unsafe for `as-of` snapshots (their basis-t is the current one). | Validation moved to the miss path and reads the per-generation cache; unstamped databases keep reading directly (`schema-basis-test` pins that). |
| R2-7 | `seal-plan` itself: 368 canonical encodings for 16 rules (`sort-by encode-canonical` re-encodes per comparison), ~4.3 ms. | Encode-once; fingerprint/plan byte-identical (asserted). |
| R2-8 | `kernel?` = `satisfies?` on an `extend`ed class, ~10 µs × 3–4 per request. | Positive-class memo. |
| R2-9 | Scan purity, closure completeness, `:limit nil`, `(locking` prohibition, boxed-Long memory, CLJS store, `count` flooding — for the scan cache. | Verified/recorded in the design (scans read only the tuple attribute; plan relations = closure on the live schema; the rest are design rules). |

## Live-app measurements (warm `datomic:dev://` peer, medians)

| operation | before | after all fixes |
|---|---|---|
| `check-permission` miss, denied | 6.0 ms | **142 µs** |
| `check-permission` miss, allowed (super) | 6.4 ms | 134 µs |
| `check-permission` hit | 383 µs | 77–104 µs |
| `lookup-resources :first 20` miss | 5.6 ms | ~490 µs |
| `lookup-resources` hit | 614 µs | 243–335 µs |
| `count-resources` super-user (110k) miss | 1.3–2.0 s | unchanged (661k reducer transitions ≈ 3 µs each; engine item) |
| `count-resources` hit | — | 51 µs |

## Remaining engine items (measured, not addressed here)

- Reducer constant factor: ~3 µs per transition (`retain-buffer` rebuilds
  the sidecar order vector on every release; `schedule` map churn;
  `AdmissionKey` allocation) — an exhaustive 110k count spends 661k
  transitions (6 per result through the union arms).
- Union-arm subsumption in the sealed plan (`account->view` where
  `account.view = admin` re-scans every account's servers) — changes the
  order ABI (new fingerprint), so it is a deliberate plan-version change.
- `secure-format` canonical encode/digest cost (24 µs per small map;
  2–4 digests per page in cursor/proof contexts).
