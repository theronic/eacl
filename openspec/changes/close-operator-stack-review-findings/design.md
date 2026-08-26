## Context

Adversarial review of PR stack `#144 → #153` on 2026-08-26, target `main`.

| Item | Value |
| --- | --- |
| PR under review | `theronic/eacl#153` "Add demand-driven intersection and exclusion engine" |
| Head | `7faac92` (`agent/design-operator-engine-performance`) |
| Stack | #144 datalevin → #145 amplification → #146 authorization-views → #147 assurance-gates → #148 proof-carrying → #149 datalevin-generations → #150 cursor-streams → #151 checkpoints → #152 cursor-tokens → #153 operator engine |
| Merge base | `9900b8a` — the stack roots **directly on current `main`**, so the stack diff is the merge diff: 464 files, +77,809/−15,061 |
| Worktrees used | `core2` = PR head (clean), `core2-baseline` = `8dc3b16` (#152 head, the frozen A/B base), `core-main` = `origin/main` |
| Not part of any PR | `./core` itself carries **uncommitted separate work** (`certify-formal-model-relevance-and-backends`, `support-permission-intersection-and-exclusion`, `model-inventory.edn`, attestation scripts). It must not be discarded when acting on this change. |

`main` is already v8 union-only; v7 lives on `eacl/v7`. GitHub reports **no CI checks** on the branch, so every gate below was executed locally.

### What was executed, and the result

All green unless noted. Runs were per-CI-shape (nREPL for the JVM battery, per-module isolated matrix, fresh JVMs where CLJS builds are involved).

| Suite / gate | Result |
| --- | --- |
| Main JVM battery (CI dirs, `:excludes [:benchmark :formal-artifact]`) | 994 tests / 32,466 assertions / 0F 0E |
| Isolated modules: eacl / datomic / datascript / datahike | 291·4,674 / 582·9,348 / 526·11,612 / 357·7,569 — all 0F 0E |
| `eacl-datalevin` (**excluded from the CI matrix**; run by hand) | 340 / 9,064 / 0F 0E |
| DataScript CLJS (`:none`) | 436 / 9,329 / 0F 0E |
| CLJS advanced-optimized battery + production-bundle gate + indexed-traversal scaling gate | 0F 0E; scaling ratio 0.767 (≤1.5), 3,993 ns/result (≤15,000) |
| Formal generated-boundary smoke (incl. operator + recursive decision suites) | 55 / 17,659 / 0F 0E |
| Verified-authority suites (heavy, non-benchmark, CLJS) | 9·776, 594·37,967, CLJS all 0F 0E |
| Counterexample replay (fresh JVM) | 71 / 18,766 / 0F 0E |
| Generators + cache-adversarial + mutation controls | 14 / 860 / 0F 0E; registry 55 registered / 55 killed |
| Dafny `bin/formal verify` | 48 modules, 9,361 obligations, 0 errors |
| TLA+ typecheck, Apalache bounded + inductive, 10 temporal mutants | all pass; every mutant produces the required counterexample |
| Perf gates: routing-certificate, consistency-boundary, recursive, explorer | all passed (explorer latency gate `:not-applicable` — recorded host is M3 Pro/JDK 24, current is M4 Max/JDK 26) |
| Release build-install-smoke, reflection gate, source-closure, `openspec validate --strict` | all pass |
| `bin/formal manifest` | exits **3 by design** — assurance `:conditionally-verified`, verified status withheld; unmet: 4 mechanized source refinements, independent security review, `[:adapter-certification :datalevin]` |

### Live probes written for this review (DataScript + Datomic, via nREPL)

Behaviour confirmed **correct**, and worth keeping as regression material:

- Exclusion, intersection, and recursive-chain (`(member + parent->access) - banned`) write-invalidation: add and delete both flip checks, pages, and counts correctly, including full restore after deleting the ban.
- A captured snapshot retains its old basis while the live client advances.
- Resuming a cursor after a write inside its dependency closure fails closed with `:eacl.pagination/stale-cursor` `:frame-changed`.
- Operator precedence truth table (`reader + writer & approved - banned`, 7 cases) matches the digest-pinned SpiceDB 1.56 oracle: `+` binds tighter than `&`, which binds tighter than `-`.
- `permission-relationship-eids` on an operator plan returns the positive ∪ negative closure, so negative dependencies invalidate answers.

## Goals / Non-Goals

**Goals**

- Record the review's findings and their evidence durably, ranked by severity.
- Fix the two critical defects before this stack merges to `main`.
- Restore integrity to the assurance ledger: no control counted that cannot fail, no gate that cannot fail the build, no claim of production binding that the artifacts do not support.
- Leave the operator engine's semantics, order ABI, and cursor formats untouched — they held up under review.

**Non-Goals**

- Re-litigating the operator design. D1–D15 were reviewed against the implementation and match.
- Extending formal coverage to the whole implementation. The two-tier split (abstract algorithms proved; implementation bound by differentials, mutants, and digests) is a deliberate, disclosed posture; this change only stops the ledger from overstating it.
- Completing the benchmark comparison against `main` (see Open Work).

## Decisions

### D1. Refuse what cannot be proven committed, rather than enumerate speculative shapes

**Finding (critical).** `eacl.client.orchestration/direct-snapshot` classifies an application-supplied value through the backend's `basis-kind` before touching runtime state — the ordering is right — but the Datomic classifier inspects only `isFiltered` / `isHistory` / `sinceT` / `asOfT`. A `d/with` value answers all four the same way an ordinary committed value does, carries the same database id, and takes a `basis-t` equal to the *next* commit's `t`. It is therefore classified `:ordinary` and admitted. `:speculative` exists in the kind enum (`eacl.backend.v8`) but no classifier ever returns it, while the `authorization-snapshots` delta spec requires refusal.

Reproduced with a control, on a `reader - banned` schema:

| | speculative capture | head snapshot answer | live client answer |
| --- | --- | --- | --- |
| control (no capture) | — | `false` ✅ | `false` ✅ |
| treatment | `true` | **`true` ❌** | **`true` ❌** |

Both runs had `with-t == head-t == 1012`. The speculative read publishes into the exact-basis tier under `{source-scope, revision}`, which the genuine committed head then hits — so a banned subject is authorized. Datahike and DataScript have the same shape (`db-with` → `:ordinary`, max-tx collision); Datalevin is safe because it gates on `read-snapshot?`.

**Decision.** Invert the classifier's obligation: an adapter must *prove* admissibility, not fail to detect inadmissibility. Preferred order — (a) witness committedness at admission (for Datomic, the transaction at `basis-t` exists in the log); failing that (b) confine unwitnessable values to request-local cache context so they cannot publish into shared tiers; failing that (c) refuse. Adding a content witness to `:backend-snapshot-id` is a weaker fallback: it prevents collision but still admits an uncommitted value into shared state.

Rejected: enumerating speculative shapes per backend. The peer API exposes no value-only predicate, so an enumeration is exactly the fail-open pattern that produced this defect.

### D2. Compare denotations, not relation identities, at the migration boundary

**Finding (major).** `migrate-v7-permissions!` computes the stored-rows conversion and then discards it — it is used only to detect corrupt rows — and the sole equivalence check is on relation identities. A replacement schema that redefines an existing permission migrates silently and reports `:relationships-touched 0`, flipping authorization under a banner of storage-only upgrade. The function's own docstring says only "relation identities must be unchanged", so the design/spec text is what is wrong; the code is doing what it says.

The zero-relationship-rewrite claim itself is **proven**, and well: the qualification suite instruments `d/datoms`/`d/index-range` on relationship attributes and asserts zero calls plus `[e a v]` content equality and a domain-separated SHA-256 digest, with injected preflight failures, an injected commit rejection, and a CAS race; the 1M-resource probe repeats it (72.6 s, digest identical before and after).

**Decision.** Compare the canonical expression derived from stored v7 rows against the supplied schema for every permission present in both; additive permissions stay permitted. Reuse the conversion already computed.

### D3. Select the exact-probe kernel where the range kernel cannot honor its bound

**Finding (major, performance).** Datahike's dense kernel passes `first-eid` as a seek bound, but `eavt-tuple-prefix`'s non-direct-DB fallback ignores the bound entirely: it realizes every datom of the endpoint attribute, filters, and sorts. `direct-db?` is false for `AsOfDB`, and as-of bases are reachable in production through `:acquire-exact!`. Measured on an identical 4-candidate window: ordinary DB realized 2 values, as-of realized 12 (the whole prefix). At 50k tuples per endpoint this is a full realize-and-sort per batch, breaking D9's ≤2k bound, inflating telemetry, and risking deadline expiry where the scalar path would have succeeded. Decisions stay correct — 800 randomized batches across both directions, both modes, and both basis kinds matched repeated scalar membership exactly.

**Decision.** Either honor `cursor-tail` in the fallback, or force sparse mode when the basis is not a direct DB. The exact-probe kernel is already the temporally safe primitive the scalar path uses.

### D4. A control that cannot fail is not a control

**Finding (major).** Of the ten new operator mutation controls, three are genuinely executable (`vector-misalignment`, `unsigned-dependency`, `missing-join-slot` — the first routes a reversed real batch through production scatter and is a model of how to write these), three execute production but their kill conjunct is true by construction, and **four are constant-function mutants whose kill compares two literals** (`operator-partial-negative`, `operator-overread-cursor-advance`, `operator-any-child-allocation`, `operator-cache-selected-generator`). The manifest gate cannot catch this: it checks that a detector's source text mentions the target symbol and that the detector returns true.

Separately: every new entry's `:killed-by` names a test that exists nowhere in the repository; and D13's required `active-recursion-as-false` class has no registry entry at all — its stand-in suite replays fixture data through a test-local naive evaluator, so the production guard is never executed by any test.

**Decision.** Rewrite the four tautological controls on the `vector-misalignment` pattern; add the missing class; make the validator reject a control whose kill assertion is decidable without the mutated definition; correct or remove `:killed-by`.

### D5. A gate that cannot fail is not a gate

**Finding (critical, infrastructure).** `bin/formal counterexample-replay` returns `clojure.test/run-tests`' summary map; `bin/ci-nrepl-eval` exits non-zero only when the eval **throws**. The CI step "Replay minimized counterexample corpus" therefore passes regardless of failures. Demonstrated: a run with 4 errors printed the summary and exited 0. Every neighbouring step wraps its result in `(when (pos? ...) (throw ...))`; this one does not.

(The 4 errors were environmental — `cljs.main/-main` calls `shutdown-agents`, poisoning later `future`-based tests on a shared nREPL. On a fresh JVM the corpus is 71/18,766 green. The gate defect is independent of that.)

**Decision.** Wrap the replay form the way the other steps are wrapped.

### D6. Stop recording telemetry nothing reads

**Finding (performance).** The relationship-observation store is constructed unconditionally per client and written on every realized scan chunk, every membership batch, and every count. `eacl.metrics` has **no production lookup consumer** — only `record-*`, `refresh`, and `stats`. Keys embed the basis-t high-watermark, so on a write-active database every request mints keys that can never be reused, churning a 4,096-entry map through arbitrary eviction while all request threads CAS one shared atom per chunk.

The eviction rewrite in `7faac92` (sort-based full-cache → bounded arbitrary-victim) is itself sound for an advisory cache; the problem is that the cache is on at all.

**Decision.** Make recording opt-in or lazily constructed; land the consumer before paying per-chunk cost.

### D7. Say what the formal artifacts actually bind

The models are **true, non-vacuous, and assumption-free** — zero `assume`, `{:axiom}`, `{:verify false}`, or `expect` across the tree; pinned obligation counts and digests check out. The strong results are strong: k-way leapfrog output is proven *sequence-equal* to generic anchor filtering with exact reseek bounds matching the implementation's loop decision-for-decision; anchor-gated retained state is proven equal to ordinary least-fixed-point propagation for every arrival order with state bounded by anchor facts; cover containment and the scalar predicate iff denotation are closed over a constructively built semantic table; the signed-graph certificate characterizes negative cycles down to finite-path semantics. Where models and implementation both take a position on a subtle point — all-negative exclusion-right subtrees, reserve-on-anchor join allocation initialized from admitted facts, union-fires-on-any-child, fail-closed unresolved lower strata — **they agree**.

What they do not do is certify the implementation, and three specific gaps should be recorded rather than papered over:

1. **The proven batch-growth rule is not the one that ships.** `GrownWidth = min(2·prev, cap, window)` doubles unconditionally; production is rejection-gated and demand-clamped. Worked example: demand 300, window 4096, first batch 256 all accepted → production asks for **44**, the model asks for **256**. Production is the better algorithm and satisfies the proved envelope, but the *proven* machine performs exactly the overread D7 forbids, and no differential binds the two (the smoke test compares the generated kernel against a local re-copy of the same formula).
2. **Unmodeled production surfaces** where an exclusion-soundness bug would actually live: stratum construction (longest-weighted-path), signed-edge extraction, question-graph discovery, and the entire lower/upper interval-bound mechanism for incomplete arrows. `StratifiedExclusion.dfy` is a fail-closed evaluator restated as lemmas; the recursive generated-policy "refinement" is bookkeeping-thin and defines the negative denotation from the scheduler's own facts.
3. **`EaclKernel.dfy` is stale in the phase-a pin and absent from phase-b's enforced digest list** — the one file defining the exported generated boundary is not covered by the closure CI enforces.

**Decision.** Replace or differentially bind the growth rule; pin `EaclKernel.dfy`; leave the two-tier posture as-is but keep `trusted-boundary.md`'s honest wording as the authority and align the phase-b claims to it.

### D8. Findings recorded but not scheduled here

Carried for the record; none blocks the merge on its own.

| # | Severity | Finding |
| --- | --- | --- |
| F7 | major (pre-existing on `main`) | Page-navigation alias can serve a wrong-size page: `remember-visited-page!` keys `{:last N :before token}` without checking the remembered page's item count, and the boundary index drops `:first`/`:last`. Authorized pages are protected by `:page-demand` in the cursor scope; non-authorized relationship pages are not. |
| F8 | perf (from #144) | Datalevin `:at-least-as-fresh` selection busy-waits: `select-source-at-least!` loops acquire→compare→release with no backoff and the backend returns the head immediately, spinning LMDB read transactions at full CPU for up to the 30 s timeout. Datomic blocks; Datahike/DataScript sleep 2 ms. |
| F11 | minor | No-op migration reports `:status :migrated` and `:permission-storage-version 8` although no transaction stamped it (covered by the `schema-write-safety` delta). |
| F12 | minor | `:eacl.consistency/history-divergence` guard is unreachable — after `d/as-of`, `db-revision` returns the locator verbatim. |
| F13 | minor | `stable-plan` installs a `delay`, and Clojure delays cache exceptions permanently: one transient adapter read failure poisons a permission root for the whole schema generation. Pre-dates this stack but sealing now also decodes payloads. |
| F14 | minor | Encode-side cursor-token reuse returns the originally minted token, so delivered TTL degrades to `(0, ttl]`. Documented; no token outlives its authenticated expiry. |
| F15 | minor | Datalevin reports a protected store missing its schema singleton as `:write-policy-drift` rather than `:generation-unprepared`. |
| F16 | perf | Raw-facade calls do not bind the structural expression cache, so payloads are re-decoded per root per call; `effective-expression-limits` re-normalizes a 17-key profile on every decode. |

Coverage gaps worth closing opportunistically: no operator test replays a resume cursor **after a write** (behaviour verified correct by live probe, but untested in-tree); the scheduled 200-seed campaign is union-only because the generator classifies `:intersection` as malformed, so operator randomization is 16 + 32 fixed seeds with no shrinking; the span == 2k density boundary is untested (covered by the `cross-backend-conformance` delta); SpiceDB parity is a manual external run, reproducible only outside CI.

## Risks / Trade-offs

- **[Tightening snapshot admission breaks an application that captures `d/with` values]** → That capability was never sound: such a snapshot can answer from, and publish into, committed cache state. The refusal is typed and names the basis kind. If speculative evaluation is genuinely wanted, it needs its own request-local design.
- **[Committedness witnesses cost a read at admission]** → Only on the direct-snapshot path, once per captured value, against the log. Where that cost is unacceptable, option (b) — request-local cache confinement — keeps correctness without the read.
- **[Migration equivalence checking rejects upgrades that used to pass]** → That is the point; a rejected upgrade leaves v7 active and usable. The million-scale probe currently relies on the unchecked behavior and will need its fixture updated to supply an equivalent schema.
- **[Rewriting mutation controls lowers the reported score]** → A score computed over controls that cannot fail is not a measurement. Expect the registered/killed counts to move and the manifest pins to be re-cut.

## Open Work (next session)

**Benchmarks against `main` are not done.** Status and traps, so the next session does not rediscover them:

- The PR's own union-only A/B harness (`modules/eacl-datascript/test/eacl/operator_engine/union_performance.clj`) runs **one campaign in whatever tree invokes it** and the caller alternates JVMs — this is the right instrument, it is DataScript-based (no Datomic peer or transactor), and its recorded results are in `exploration/operator-engine/performance-qualification.edn`. It does **not exist on `main`**; its only dependencies (`eacl.baseline.perf`, `eacl.bench.explorer-fixture`) do, so it can be copied into `core-main` to run the frozen side.
- The recorded A/B (frozen `8dc3b16` vs head) shows 16 operations, deterministic work counters **exactly equal**, 15 faster / 1 within envelope, worst latency +0.67 %, allocation ≤ +3.82 %, largest gains on empty pages (−66 %) and point checks (−13 %).
- `core-main` needed `bin/formal bootstrap && bin/formal build-java` before any JVM run (a fresh worktree lacks the generated kernel classes; without them Datomic runs die with `ClassNotFoundException`). **This has now been done.**
- The Datomic `version_comparison` walk at 30k resources dies on its **final** page with `:eacl.recursive-traversal/limit-exceeded` `:advanced-datoms 100000` (~788 ms for that page; pages 1–575 are flat at 1.5–2.6 ms). **Head and base `8dc3b16` both hit it**, so it is not a regression introduced by #153 — but it blocks that harness at this fixture size. Either raise `:recursive-traversal-limits` via client opts, sample pages instead of walking to exhaustion, or shrink the fixture.
- Use `-M:dev` (not `-M:dev:test`, whose main-opts launch the whole runner). macOS has no `timeout`. Datomic peer JVMs need `(System/exit 0)` or scripts hang on non-daemon threads.

**Harness traps that cost time in this session** (all environmental, not defects): `status` is read-only in zsh and aborts a loop that assigns it; `/dev/tcp` port probes do not work in zsh (use `nc -z`); `pkill -f` can match the running shell's own command line; a stale nREPL on a reused port silently reruns the previous module, so per-module test counts must be checked.
