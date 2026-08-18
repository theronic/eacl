# The hierarchical demand-segment cache investigation (2026-08-18/19)

Status: closed. What started as an adversarial review of the
`hierarchical-demand-segment-cache` proposal ended with that design rejected,
two unrelated but far larger performance defects found and fixed on all
three backends, the point-check route replaced, a new Dafny leaf, and the
`8.0.0-SNAPSHOT` deployed to Clojars (build 6, 2026-08-18T22:23Z). This
report is the durable record for future optimization work: what was
measured, what was decided, what shipped, and what is still on the table.

## Artefacts

| Kind | Where |
|---|---|
| The reviewed change (rewritten) | [`openspec/changes/hierarchical-demand-segment-cache/`](../../openspec/changes/hierarchical-demand-segment-cache/) — [proposal](../../openspec/changes/hierarchical-demand-segment-cache/proposal.md), [design](../../openspec/changes/hierarchical-demand-segment-cache/design.md), [tasks](../../openspec/changes/hierarchical-demand-segment-cache/tasks.md), delta specs under [`specs/`](../../openspec/changes/hierarchical-demand-segment-cache/specs/) (`exact-scan-response-cache`, `bounded-physical-execution`, `verified-subproblem-cache`, `demand-bounded-evaluation`, `managed-reuse-certification`) |
| Review record + REPL harness | [`review/REVIEW.md`](../../openspec/changes/hierarchical-demand-segment-cache/review/REVIEW.md), [`review/bench/seg_bench.clj`](../../openspec/changes/hierarchical-demand-segment-cache/review/bench/seg_bench.clj), the original proposal/design/tasks under `review/original-*.md` |
| Shipped changes | [`fix-datomic-request-overhead`](../../openspec/changes/fix-datomic-request-overhead/) (PRs [#131](https://github.com/theronic/eacl/pull/131), [#135](https://github.com/theronic/eacl/pull/135)), [`membership-probe-point-check`](../../openspec/changes/membership-probe-point-check/) (PRs [#131](https://github.com/theronic/eacl/pull/131), [#132](https://github.com/theronic/eacl/pull/132), [#133](https://github.com/theronic/eacl/pull/133)) and its delta spec [`specs/stable-discovery-enumeration/spec.md`](../../openspec/changes/membership-probe-point-check/specs/stable-discovery-enumeration/spec.md); deploy PR [#134](https://github.com/theronic/eacl/pull/134) |
| Formal | [`formal/stable-discovery/MembershipProbeCheck.dfy`](../../formal/stable-discovery/MembershipProbeCheck.dfy) (in `verify-fast.sh`, 528 obligations); [`docs/formal-verification.md`](../formal-verification.md) |
| Engine docs | [`docs/stable-discovery-engine.md`](../stable-discovery-engine.md) (point checks), [`docs/cache.md`](../cache.md) (cache layers table) |

## 1. What the proposal claimed and why it was rejected

The proposal ([original](../../openspec/changes/hierarchical-demand-segment-cache/review/original-proposal.md))
asserted that "repeated multi-hop walks remain the dominant cost" and
proposed a hierarchical tier of per-plan-node "segments" keyed by start-set
fingerprint and demand, composed across levels.

Findings (details and citations in
[REVIEW.md](../../openspec/changes/hierarchical-demand-segment-cache/review/REVIEW.md)):

1. **The premise was unmeasured and, on Datomic, false.** No benchmark in
   the repo compared super-user and normal-user visibility; the one artefact
   showing "walks dominate" was a deleted benchmark on the retired engine
   under opt-in `:complete-denotation`. Measured on an in-memory Datomic
   peer, the whole reducer walk of a `:first 20` page was 90–120 µs and the
   3–5 adapter scans ~11 µs each, inside a 1.4–2.8 ms miss page.
2. **Level-k composition is unsound for the stable reducer.** Emissions
   under a plan node depend on the request's *global* admission set, so
   context-free node segments cannot be substituted into stable enumeration
   — precisely the counterexample that retired the previous denotation tier
   (`CacheBoundary.dfy`, `ContextFreeDenotationIsNotAStableTrace`).
   "Demand" is not a per-node quantity (only root grants consume `target`),
   and exact start-set fingerprints defeat the sharing the proposal wanted.
3. **The sound kernel is an exact scan-response prefix cache** at the
   `fetch-fn` seam (elide-only, keyed by read descriptor + the scanned
   relation's generation) — essentially the retired projection tier, which
   the stable-discovery change had cut as "purely accelerative". Measured
   honestly it removes 94–100 % of adapter commands on sparse high-sharing
   shapes with oracle-identical results, but on a warm Datomic peer forward
   scans cost 2–10 µs, and the prototype's hit path cost about the same:
   **no measurable gain for lookup pages** (156 vs 159 µs/page at 74 %
   hits). It helped only tiny-scan reverse walks (55 → 25 µs), which the
   probe check (below) made moot. It stays proposed and gate-decided,
   primarily for Datahike/remote stores. Two constraints for anyone building
   it: the fetch contract "chunk shorter than `:limit` ⇒ exhausted" is
   load-bearing (`stable_reducer.cljc/fetch-values`) and was written in no
   spec until this change's delta; and the weighted-LRU
   `eacl.subproblem-cache` store cannot host a per-scan hot path (1.4 µs per
   hit single-threaded, ~0.5 M ops/s under eight threads).

## 2. What was actually slow (live demo, 110k servers, warm `datomic:dev://` peer)

Profiling the running `eacl-datomic-solidjs` demo (not a fixture) found the
real costs, none of them traversal:

| cause | share of a `check-permission` miss | fix |
|---|---|---|
| Sealed-plan cache thrash: `stable-plan` keyed by lifecycle + native revision; the Datomic raw facade minted a random lifecycle per call, so `seal-plan` (~4.3 ms on the 16-rule demo schema) reran on **every request** | ~80 % | key by schema generation identity; process-stable facade lifecycle; `expire-plans!` |
| `seal-plan` itself: `sort-by encode-canonical` re-encoded and re-validated rules on every comparison (368 encodings for 16 rules) | most of the seal | encode once |
| `read-schema` on every request (all relations + permissions via `d/q` + `pull`) to validate three keywords | ~35–40 % of what remained | validate on the miss path from a per-generation parsed schema; hits read nothing (Datomic client in #131, Datahike/DataScript client in #135) |
| `verified-kernel/kernel?` = `satisfies?` on an `extend`ed class, 3–4× per request | ~14 % of a hit | positive-class memo |
| **`can?` was O(#subjects holding the permission)**: reverse enumeration with early exit; a denied check on a 5,000-owner account cost **16 ms** | traversal share of a fixed check | membership-probe search, O(intermediates) |

Live results (medians): `check-permission` miss **6.0 ms → 142 µs**, hit
383 → ~80–100 µs; `lookup-resources :first 20` miss **5.6 ms → ~490 µs**,
hit 614 → ~250–330 µs; popular-resource denied check 16.3 ms → 24 µs
(fixture). Shared client: DataScript `can?` hit 200 → 133 µs, Datahike
187 → 104 µs after #135. Through the demo's HTTP layer: check ~0.7–1.0 ms
(was 9.1 ms average), page ~2.5–3.9 ms (was 13.2 ms).

## 3. Correctness and formal status

- The probe check equals the reverse-enumeration oracle on six frozen
  baselines, 8,680 fixture pairs and 4,860 live pairs (0 disagreements);
  `eacl.engine.point-check-test` is the executable evidence, registered in
  `formal/verification/execution-contract.edn`.
- `MembershipProbeCheck.dfy` proves, over the `StableReducer` program model
  with leaf result nodes, that the probe answer equals reverse-denotation
  membership (`ProbeAnswerEqualsReachability`,
  `ProbeCheckEqualsEnumerationCheck`); the fast gate is pinned at 528
  obligations. The plan-cache key rests on "a sealed plan is a pure
  function of the schema definitions" (documented and tested); `seal-plan`
  encode-once was asserted fingerprint-identical; validation-on-miss rests
  on "every cache tier keys by an identity that fixes the schema
  generation" (argued in the change designs, exercised by the suites).
- The release manifest still withholds verified status for the same five
  standing obligations as before; nothing here changed that.

## 4. Loopholes met on the way (worth remembering)

- Keying the global plan cache without the lifecycle let test adapters
  that alias `{:source-id :test}` share plans across tests; the lifecycle
  stays in the key, the *random per-call* lifecycle was the bug.
- A parsed-schema memo keyed by `basis-t` is unsafe under `d/as-of`
  (the as-of value keeps the current basis); the per-generation memo is
  correct by construction. Unstamped databases must not latch anything
  (`schema-basis-test`).
- The `:raw-can` op envelope pins zero `:proof-frame` reads for raw checks
  and exact hits promise no generation reads — the plan key must use an
  identity the caller already knows.
- `(identical? ::kw …)` is false in ClojureScript (#132); the CLJS suite
  must be built clean (`-d` to a fresh output dir) to see new code.
- After **any** public-source edit, regenerate the source-closure ledger
  (`node bin/public-source-closure.mjs write`); a stale ledger fails
  `bin/formal source-closure` in CI (it did, on the deploy branch).
- The Explorer latency gate (`eacl.bench.explorer-enumeration-test`) is
  host-matched to the recording machine and only meaningful when idle; on a
  loaded box both old and new code miss it by ~7× (A/B showed no
  regression, pages faster).
- Deploying: PR from `main` into `v8.0.0-SNAPSHOT` (branch-local
  SNAPSHOT-aware release guard), branch protection wants an approving
  review, and the `clojars` environment needs a manual approval that also
  holds the release concurrency group — a stale waiting run blocks new ones.

## 5. Remaining opportunities (measured, in priority order)

1. **Reducer constant factor** — ~3 µs per transition on the warm demo
   (`retain-buffer` rebuilds the sidecar order vector on every release,
   `schedule` persistent-map churn, `AdmissionKey` allocation). An
   exhaustive super-user count of 110k servers spends 661k transitions
   (six per result through the union arms) ≈ 2 s cold; the answer cache
   serves repeats in ~50 µs. This is the largest remaining engine cost.
2. **Union-arm subsumption in the sealed plan** — `server.view = admin +
   account->view + …` with `account.view = admin` re-scans every account's
   servers through a second arm; pruning subsumed arms halves the walk but
   changes the order ABI (a deliberate plan-version change with a new
   fingerprint).
3. **`secure-format` canonical encode/digest** — ~24 µs per small map;
   pages spend 2–4 digests in cursor/proof contexts (~27 % of a fixed miss
   page), cursors are ~1.8 KB.
4. **Adapter scan realisation** — the lazy `take-while`/`map` chain over
   `d/seek-datoms` costs several µs per chunk; a tighter chunk realiser
   would help exhaustive walks (44 % of a cold count).
5. **Exact scan-response cache** — gate-decided; worth it on Datahike/remote
   stores and sparse high-sharing schemas, not on a warm Datomic peer; needs
   a sub-µs hit path (lock-free, `long[]` prefixes) to matter at all.
6. Demo app: request/response work outside EACL (JSON, cursor tokens)
   dominates its remaining latency; its redundant EACL semaphore was
   removed (Jetty bounds concurrency).

## 6. Reproduction

```clojure
;; from an nREPL started with: clojure -M:dev:nrepl
(load-file "openspec/changes/hierarchical-demand-segment-cache/review/bench/seg_bench.clj")
(in-ns 'seg-bench)
(def conn-a (mem-conn!)) (def acl-a (seed-a! conn-a {:accounts 60 :servers-per-account 40}))
(bench "can? bypass" #(eacl/can? acl-a {:subject (->user "owner-7") :permission :view :resource (->server "srv-7-3") :cache? false}))
;; live demo: eacl-solidjs.system/!system on the demo's nREPL; profile with the sampler in the review
```
