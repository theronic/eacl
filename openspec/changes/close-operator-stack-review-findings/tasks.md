# Tasks

Ordered by severity. Sections 1 and 2 are merge blockers.

## 1. Isolate public snapshots from caller database values (critical)

- [x] 1.1 Preserve the same-`t`, same-`:db/txInstant`, different-content poisoning regression with a no-capture control.
- [x] 1.2 Remove public raw-database snapshot constructors and accept only EACL clients or EACL-created snapshots as public authorization targets.
- [x] 1.3 Add explicit speculative provenance through composable `eacl/with` and `eacl/with-schema` snapshots.
- [x] 1.4 Disable speculative exact-tier lookup and every persistent publication path while retaining validated committed managed-proof reuse for complete disjoint effects.
- [x] 1.5 Classify effects from native transaction-report datoms, fail unknown dimensions closed to uncached evaluation, and make unsupported adapter capabilities explicit.
- [x] 1.6 Run collision, cache-publication, schema, cursor-provenance, and cross-backend conformance suites.

## 2. Make the assurance gates able to fail (critical)

- [x] 2.1 Wrap `bin/formal counterexample-replay`'s eval form in `(when (pos? (+ (:fail result 0) (:error result 0))) (throw (ex-info ...)))`, matching the neighbouring CI steps.
- [x] 2.2 Audit every other `bin/formal` subcommand and `formal.yml` step for the same shape; fix any that report failure as a return value.
- [x] 2.3 Add a self-test that a deliberately failing replay fails the step, so the gate's failure path is itself exercised.

## 3. Restore mutation-control integrity

- [x] 3.1 Rewrite `:operator-partial-negative`, `:operator-overread-cursor-advance`, `:operator-any-child-allocation`, and `:operator-cache-selected-generator` on the `:operator-vector-misalignment` pattern: mutate the real definition, detect through a production consumer, expectation derived independently.
- [x] 3.2 Strengthen `:operator-wrong-precedence`, `:operator-swapped-exclusion`, and `:operator-duplicate-satisfaction-count` so the kill conjunct depends on production output rather than holding by construction.
- [x] 3.3 Add the missing D13 class: `active-recursion-as-false`, detected through a production execution path that reaches the `:eacl.operator/active-recursion` guard.
- [x] 3.4 Teach the manifest validator to reject a control whose kill assertion is decidable without the mutated definition, and to require that `:killed-by` names an existing test.
- [x] 3.5 Correct or remove the ten fabricated `:killed-by` entries; re-cut registered/killed counts and manifest pins.

## 4. Migration semantic equivalence

- [x] 4.1 Retain the stored-rows conversion already computed in `migrate-v7-permissions!` and compare canonical expressions per permission present in both sides; permit additive permissions.
- [x] 4.2 Reject non-equivalence with a typed error naming the divergent permission, leaving v7 rows and stamp active.
- [x] 4.3 Add the regression: identical relations, `view = reader` stored vs `view = writer` supplied, must reject.
- [x] 4.4 Fix the no-op report so it names the no-op outcome and the version actually stamped.
- [x] 4.5 Update the million-resource qualification fixture to supply an equivalent replacement schema, and re-record `datomic-v7-to-v8-million-qualification.edn`.
  - Verified instead of changed: the fixture was already additive-only (`candidate_view`/`selective_view` preserved verbatim, operator permissions added), which the new equivalence check permits. Harness `source-sha256` still matches the recorded pin, the additive shape is regression-covered at mem scale, and the recorded 1M evidence therefore stands.

## 5. Datahike as-of batch bound

- [x] 5.1 Add the failing case: dense batch on an as-of snapshot with many tuples below the first candidate; assert realized values stay within the certified bound.
- [x] 5.2 Honor `cursor-tail` in `eavt-tuple-prefix`'s non-direct-DB fallback, or select the exact-probe kernel when `direct-db?` is false.
- [x] 5.3 Add the span == 2k boundary cases (2k, 2k+1, k=1, k=0) in both directions and pin the selected mode against the certified policy identity.
- [x] 5.4 Re-record the Datahike physical-policy evidence if the selected kernel changes for any basis kind.

## 6. Request-path telemetry

- [x] 6.1 Make the relationship-observation store opt-in via client configuration or lazily constructed on first consumer use.
- [x] 6.2 Skip all recording work — including key construction — when disabled.
- [x] 6.3 Add a gate asserting zero observation allocation on a default-constructed client's page, count, and membership paths.

## 7. Formal ledger honesty

- [x] 7.1 Replace `AdaptiveBatching.GrownWidth` with the demand-clamped, rejection-gated rule the engine runs, or add it alongside and prove the same envelope over it.
- [x] 7.2 Bind `eacl.operator.batch-schedule/advance` differentially to the generated decision, so the smoke test stops comparing the kernel against a copy of its own formula.
- [x] 7.3 Add `EaclKernel.dfy` to the enforced phase-b digest closure and refresh the stale phase-a pin.
- [x] 7.4 Add the dense-path exactness lemma to `DensityBoundedBatch.dfy` (aligned dense decisions equal `MembershipDecisions`).
- [x] 7.5 Align `operator-phase-b.edn`'s binding claims with `trusted-boundary.md`'s wording for the batch-growth decision.

## 8. Coverage gaps

- [x] 8.1 Add an operator pagination test that replays a resume cursor after a write inside the dependency closure, asserting the typed `:frame-changed` stale-cursor outcome (behaviour already verified by probe).
- [x] 8.2 Extend the randomized generator so `:intersection`/`:exclusion` are valid variants, and include operator schemas in the scheduled 200-seed campaign.
- [x] 8.3 Promote the exclusion, intersection, and recursive-chain invalidation probes from this review into the in-tree suite.

## 9. Recorded but unscheduled (decide before merge)

- [x] 9.1 Decide on F7 (wrong-size page from the navigation alias) — guard the alias with an item-count check or include size in the key.
  - Fixed: the alias registers only when the stored adjacent page holds exactly the aliased size, with an in-tree regression proving a `:last 3` request now returns the true three-item window instead of a smaller cached page.
- [x] 9.2 Decide on F8 (Datalevin at-least-as-fresh busy-wait) — add backoff in the shared loop or a blocking watermark wait.
  - Fixed in the Datalevin backend: an insufficient head releases its snapshot and waits 2 ms before re-acquiring, matching the Datahike/DataScript freshness polls; the shared loop keeps compare/retry/deadline authority. Verification rides the hand-run Datalevin suite (not in the CI matrix).
- [x] 9.3 Triage F12–F16 (dead consistency guard, exception-caching `stable-plan` delay, cursor TTL delivery, Datalevin error typing, raw-facade decode amortization).
  - F12 fixed: the exact-selection guard now checks the reachable divergence (synchronized head below the requested locator) instead of comparing the as-of view against its own input.
  - F13 fixed: `memoized-derived!` clears its slot on a thrown build so one transient adapter failure no longer poisons the schema generation; regression added.
  - F14 accepted as documented: no token outlives its authenticated expiry; delivered TTL in `(0, ttl]` on encode-side reuse stays a recorded trade-off.
  - F15 deferred to the Datalevin hand-run follow-up: reclassifying `:write-policy-drift` vs `:generation-unprepared` needs the Datalevin store harness to validate.
  - F16 recorded as follow-up: binding the structural expression cache on the raw facade is a scoped performance change on a compatibility surface with no correctness impact.

## 10. Benchmarks vs `main` (not started)

- [x] 10.1 Copy `union_performance.clj` into `core-main` (its deps exist there) and run alternating frozen/current campaigns, ≥3 each, medians of campaign medians. `core-main` already has `bin/formal build-java` done.
  - Done, with one correction the first run exposed: `origin/main`'s own perf seeder keeps non-answer cache tiers enabled, so the copied harness was patched to construct its client with `cache/no-cache` — both sides now measure the cold path, matching the recorded frozen-versus-head protocol. 3 campaigns per side, alternating fresh JVMs.
- [x] 10.2 Report head vs `main` for point check, first page, empty page, bounded page, exact count, and full enumeration, with work counters alongside latency and allocation.
  - Recorded in `exploration/operator-engine/main-comparison.edn`: first pages 18–57% faster at roughly half the allocation, empty pages 42–50% faster, exhaustive count and enumeration at parity within 6%, point checks 31–35% slower; work counters differ between the engines (equal only on empty pages), as expected across the stack rewrite.
- [x] 10.3 For the Datomic `version_comparison` walk, either raise `:recursive-traversal-limits`, sample pages instead of walking to exhaustion, or shrink the fixture — the final-page `:advanced-datoms` limit failure reproduces on `8dc3b16` and is **not** a #153 regression, but it must be characterized before it is dismissed.
  - Characterized on mem-Datomic at 30k with a frozen-base control: the 600th (final) page must prove exhaustion, consuming the remaining recursive closure past the last result and exceeding the 100k consumed-values guardrail (pages 1–599 flat at 1.5–2.0 ms). Raising `:recursive-traversal-limits` completes the walk on both trees (final page 683 ms head / 740 ms base). Recorded in `main-comparison.edn`.
- [x] 10.4 Record results next to the existing evidence and state plainly whether the recorded −65 %/−13 % union-only gains reproduce against `main` on this host.
  - Stated plainly in the record: the empty-page gain direction reproduces against `main` (−42 %/−50 % here vs −66 % recorded frozen-vs-head); the point-check gain does not — head point checks are 31–35 % slower than `main`'s engine on this host, despite being faster than the frozen stack base.
