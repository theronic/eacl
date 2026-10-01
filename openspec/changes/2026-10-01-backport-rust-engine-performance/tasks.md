# Tasks

Each numbered section is one pull request, stacked in order on #210 after
#213 (endpoint existence), #217 (write-schema reuse) and #219 (Kleene fault
semantics). Order: 1, 2, 4, 5, 6, 7, 8, 9, 10; section 3 is dropped. Each
pull request reports the gate's cases before and after it against the
baseline recorded in design.md. Every PR ends with: the CI-equivalent battery, the
DataScript ClojureScript suite in both modes (last in its JVM),
`bin/formal fast`, `bin/formal verify` when a model changed,
`bin/formal source-closure`, the mutation-control suite,
`bin/formal counterexample-replay`, `bin/formal manifest`, the reflection
gate, `node bin/public-source-closure.mjs write`, and the gate cases it
targets within budget on a fresh JVM.

## 1. Benchmark gate (PR 1)

- [x] 1.1 Port the eacl-rust generator (`crates/eacl-bench/clj/eacl_bench/bench.clj`) into `eacl.bench.drive-parity-fixture` and write `eacl.bench.drive-parity-test` (DataScript, `^:benchmark`) with the spec's budgets before any sampling. Assert counts and checksums at 10⁴ and 10⁵ and every workload's answer.
- [x] 1.2 Add the non-linear chain fixture (`reach`, `reach_union`) and work-counter assertions (adapter commands, fetched values, probes, candidates).
- [x] 1.3 Record a baseline on #217 (#210 plus the two write-path PRs) under `target/benchmarks/drive-parity/`. Verify `openspec validate 2026-10-01-backport-rust-engine-performance --strict`.

## 2. Cost-ordered operand evaluation (PR 2, design D2)

- [x] 2.1 Derive the evaluation order per `:any-true`/`:all-true` node at sealing, outside the fingerprint (`operator-plan/operand-order`, `:operand-orders`): relation leaves, arrows to relations, references to union-only permissions on no cycle, recursive ones, then operator permissions and nested operator nodes; ties canonical. `plan-test/unions-decide-their-operands-in-static-cost-order-test`.
- [x] 2.2 Iterate children in that order in `vector-evaluator/check-many-normalized` and `evaluator/check-eids`; `evidence/combine` keeps the later deadline when both operands decide. `compiler-plan-compatibility` gains `:operand-order` and `:certificate-rule`.
- [x] 2.3 Prove the order and certificate lemmas in `QualifiedTemporal.dfy` (`OrderedShortCircuitIsSound`, `BothDecisiveTakeTheLaterDeadline`, `CertificateFollowsTheOrder`; with `QualifiedEvidence.FoldsIgnoreOrder`, no separate `OperandOrder.dfy`) and list the theorems in the contract.
- [x] 2.4 `eacl.engine.operand-order-refinement-test`: a strong-Kleene transcription with widest-witness deadlines against every route, in the sealed order and in random permutations of it; lookup items equal checks exactly, residuals included.
- [x] 2.5 Register mutation controls `:operand-order-drops-a-child` and `:operand-order-takes-a-fault-as-decisive`; both runtimes. The earlier-deadline mutant is equivalent on today's evaluators (each stops at the first decisive operand, a wildcard probe is demanded only when the subject's own probe does not grant, and the reducer keeps a candidate's first emitted certificate), so the later-deadline rule is pinned by `evidence-test/both-decisive-operands-keep-the-later-deadline` and `QualifiedTemporal.BothDecisiveTakeTheLaterDeadline`; its executed control lands with the tabled evaluator (section 8), where table and memo values are both decisive.
- [x] 2.6 Gate: check cases within budget, direct grant at most 4 adapter commands. The gate claims `check, direct grant`, its work bound `:direct-grant-commands` and `count, narrow subject, limit 1`.
- [x] 2.7 A conditional lookup item of a union-only permission is re-decided by the point check, so its residual equals the check's (on #219 the union search and the point search composed different, both sound, certificates).

## 3. Set-at-a-time arrows (dropped, design D3)

- [x] 3.1 Dropped by the owner on 2026-10-01: the tabled evaluator (section 8) decides each arrow target once per request; any remaining batching is part of section 8.

## 4. Structural certainty from the generator (PR 4, design D4)

- [ ] 4.1 Track first-discovery plainness per emitted candidate in the cover reducer (qualifier slot of each compact edge).
- [ ] 4.2 Pass plain candidates' generator node as an evidence witness to the vector evaluator; decide only the remaining operands.
- [ ] 4.3 Prove the plain-witness lemma in `CandidateCover.dfy`; campaign: every plain-flagged candidate's generator node is plainly true by exact evaluation.
- [ ] 4.4 Mutation controls: plainness ignores the qualifier slot; plainness kept across a qualified edge.
- [ ] 4.5 Gate: broad count of `view` within 1.5 times `view_any`; first page within 2 times.

## 5. Order-free counts (PR 5, design D5)

- [ ] 5.1 Implement the structural S/M evaluator per subject (semi-naive per component, dependency order) over forward scans, in portable CLJC.
- [ ] 5.2 Route exact and bounded counts through it; decide `M∖S` with one batched table; keep `:count-limit` semantics (stop after `l + 1`) and `:detailed` categories.
- [ ] 5.3 Prove `StructuralBounds.dfy` (sandwich, semi-naive equals least fixed point, count decomposition); add the `:structural-operator-bounds` operation and theorem policy.
- [ ] 5.4 Transcription campaign beside production; independent bound check on random programs with plain, expiring and caveated relationships, JVM and ClojureScript.
- [ ] 5.5 Mutation controls: qualified edge sure; exclusion `Sa − Sb`; delta lost through an arrow.
- [ ] 5.6 Gate: union-only count at most 1 µs per result at 10⁵.

## 6. One table per batch (PR 6, design D6)

- [ ] 6.1 Group demands by subject, permission, resource type and context; decide each group with one batched evaluation; emit in demand order; fall back to sequential evaluation after a thrown error.
- [ ] 6.2 Differential: batch against individual checks over random batches with duplicates, conditional and faulting demands and limits.
- [ ] 6.3 Mutation controls: memo shared across contexts; failing demand index shifted.
- [ ] 6.4 Gate: 256-document batch within 32 times a single check.

## 7. Bidirectional recursive membership (PR 7, design D7)

- [ ] 7.1 Compute the subject's upward closure once per request; interleave it with the top-down search in doubling steps; apply the leveled rule to expiring edges.
- [ ] 7.2 Prove `BidirectionalMembership.dfy`; refinement against the memoized search on random group forests with qualified edges.
- [ ] 7.3 Mutation controls: upward closure skips a level; side choice ignored (gate command bound).
- [ ] 7.4 Gate: denied-through-groups check at most 64 adapter commands at 10⁵; lookup-subjects case measured.

## 8. Tabled evaluator (PR 8, design D8)

- [ ] 8.1 Implement the tabled evaluator (integer goal ids, restart on demand, leader completion, accumulation, completed-stratum exclusion, doubling arrow batches, request probe memo, one table per request) and route Route C's callers and the guarded fallbacks to it. Bump `checkpoint-version`; an old checkpoint or cursor gets the typed stale-cursor error.
- [ ] 8.6 Retire delegation, the leveled search and the guarded search once the tabled evaluator meets their budgets on this gate and on `eacl.bench.operator-shapes-test`; keep them as test oracles only.
- [ ] 8.2 Prove `TabledFixpoint.dfy` and refine it to `PermissionSetAlgebra.dfy` and `SignedDependencyStratification.dfy`; move `:operator-engine-phase-b`'s entry points; keep the generated-policy models as historical coverage.
- [ ] 8.3 Transcription campaign; differentials against the retained Route C (test-only namespace), the stratified fixed point, and the eacl-rust port campaign (`crates/eacl-diff`, 10⁶+ steps on a dedicated nREPL of the PR worktree); counterexample replay.
- [ ] 8.4 Mutation controls: early completion, overwrite instead of accumulate, unchecked short-circuit, early exclusion read, goal-id collision.
- [ ] 8.5 Gate: non-linear chain within 4 times its union twin.

## 9. Continuation without replay (PR 9, design D9)

- [ ] 9.1 Keep a continuation checkpoint for cache-off walks of cyclic covers and resume each page from it; acyclic covers keep their keyset cursors. A lost checkpoint falls back to replay. Bump the cursor version if its payload changes.
- [ ] 9.2 Walk differential over page sizes, cache on and off, writes between pages; mutation controls for off-by-one resumes.
- [ ] 9.3 Gate: cache-off walk within 1.25 times the cache-miss walk.

## 10. Constant factors (PR 10, design D10)

- [ ] 10.1 Byte-array paths in `secure-format` on the JVM with byte-identical output; differential against the previous implementation and pinned vectors.
- [ ] 10.2 Per-generation request scaffolding; verify the unknown-subject and cache-hit cases improve and every cache test passes.
