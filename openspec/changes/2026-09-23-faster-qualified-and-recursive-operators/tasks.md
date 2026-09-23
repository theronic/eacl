# Tasks

## 1. Change set-up and budget gate (PR 1)

- [x] 1.1 Author `eacl.bench.operator-shapes-test` and `eacl.bench.operator-shapes-fixture` (Datahike, `^:benchmark`) with the spec's budgets before any sampling of the implementation. Verify the gate loads, asserts correctness on every case, and records a baseline under `target/benchmarks/operator-shapes/`.
- [x] 1.2 Validate the change and verify `openspec validate 2026-09-23-faster-qualified-and-recursive-operators --strict` passes.

## 2. Expiring evidence in the memoized membership search (PR 1)

- [x] 2.1 Classify the evidence of every edge and witness `check-many-eids` meets as plain, expiring with a deadline, conditional, or fault. Verify with unit tests in `eacl.engine.membership-test`.
- [x] 2.2 Implement the leveled search (design D1):
  - one memo per level, where negatives keep their closure's latest skipped deadline;
  - the next level is the latest skipped deadline;
  - certificates are `Evidence{true, v, complete}`, or plain `true` at `+∞`;
  - conditional evidence is skipped and flagged, and faults fall back.

  Verify that permissionship always equals `check-eids`, and that every expiring grant's certificate equals an independently computed widest witness.
- [x] 2.3 Recompute conditional combined operator decisions for batched candidates on the `:point` oracle. Verify that detailed lookup items still equal `check-permission` decisions in the qualified lookup tests.
- [x] 2.4 Extend the Dafny membership model with levels, proving three things: the level sequence finds the widest witness, per-level memos are sound, and certificates are sound. Verify `dafny format` and `bin/formal verify`.
- [x] 2.5 Add a leveled refinement campaign on DataScript with random expiring tuples and cycles (CLJC, registered in the ClojureScript runner). It runs a transcription of the leveled search beside production and compares decisions, per-level memos and certificates. Verify it passes on the JVM and in ClojureScript.
- [x] 2.6 Register mutation controls for the leveled search: the next level set one below the latest skipped deadline, the first level's deadline reported as the certificate, and a conditional-only resource answered false. Verify `eacl.formal.mutation-control-test`.
- [x] 2.7 Update the `:delegated-operator-recursion` assurance contract, `docs/permission-set-algebra.md` and `docs/formal-verification.md`. Verify `bin/formal source-closure` and `bin/formal manifest`, which should report its theorems passed.
- [x] 2.8 Verify the gate's expiring cases are within budget. Run the CI-equivalent battery and the ClojureScript suite, then open PR 1 stacked on #199.

## 3. Flattened generators (PR 2)

- [x] 3.1 Derive flattened generator rows along covers (design D2), with one synthetic node per non-delegated permission reached. Verify plan tests for a union root over an intersection and over an exclusion, a union anchor, a relation anchor, an arrow to an operator permission, and single-operand plans that keep their operand's plan.
- [x] 3.2 Seal the synthetic generator through a wrapper adapter. It needs its own memo key, a read-scope check against the plan's relation closure, and a new `stable-cover-plan` branch. Bump `:recursive-generator` to `:flattened-union-generator-v1`. Verify that recursive operator cursors authenticate the new cover fingerprint and that a cursor minted under the per-node cover is rejected as invalid.
- [x] 3.3 Decide the relation leaves of batched delegated evaluations from the subject's holdings (design D2): one bounded forward scan per subject and relation slice per request, and probes when the scan is incomplete. Verify that detailed lookup items equal `check-permission` for expiring and caveated grants, with complete and with incomplete holdings.
- [x] 3.4 Extend `eacl.operator.delegation-refinement-test`:
  - flattened generators' rows, evaluated by the campaign's own semantics, cover their roots;
  - answers match the stratified semantics and the tabled route;
  - walk orders are independent of page size;
  - forward orders under a delegated operand's plan match the tabled route.

  Verify the campaign and a 200-case sweep.
- [x] 3.5 Register mutation controls: a union term dropped from the flattened generator, and truncated holdings taken as complete. Verify each is killed on both runtimes.
- [x] 3.6 Verify the gate's union-root cases are within budget. Run the battery, the ClojureScript suite and the formal gates, then open PR 2 stacked on PR 1.

## 4. Guarded recursion through operators (PR 3)

- [ ] 4.1 Classify linearly guarded components and their members (design D3), derived outside the plan fingerprint. Verify plan tests:
  - `inherited` is guarded;
  - an intersection with two recursive children is not;
  - an exclusion with a recursive right operand is not;
  - a guard that is itself an operator is not.
- [ ] 4.2 Implement the guarded memoized search over member expression states:
  - guards are decided exactly from holdings, the oracle and intermediates;
  - positive evidence follows D1;
  - a subtracted guard opens only when plainly false;
  - anything uncertain falls back to the tabled point evaluation.

  Verify unit tests on cyclic `inherited`-style fixtures with plain, expiring and caveated guards.
- [ ] 4.3 Route guarded plans through a delegated view whose guarded members use batched and point guarded oracles, for lookups, counts, checks and reverse operations. Candidates come from the D2 generator. Verify the delegated-recursion and differential tests.
- [ ] 4.4 Prove in Dafny that reachability in the guarded graph equals the stratified least fixed point of a linearly guarded component. Verify `dafny format` and `bin/formal verify`.
- [ ] 4.5 Add a guarded refinement campaign: random guarded programs, with a transcription of the guarded search run beside production. Extend the delegation campaign with guarded roots, checked against the stratified semantics and the tabled route. Verify on the JVM and in ClojureScript.
- [ ] 4.6 Register mutation controls: an ignored guard, a flipped subtracted guard, and a non-linear component classified as guarded. Verify each is killed.
- [ ] 4.7 Apply the tabled evaluator's constant-factor changes (design D4): memoized printed question keys, decorated sorts, unsorted per-round condensation, attachment of pending probes only, and lazy command identity. Keep every decision, certificate, checkpoint, digest and counter identical. Verify the recursive evaluator tests, counters and mutation controls, and profile the gain on a non-linear fixture.
- [ ] 4.8 Verify the gate's guarded cases are within budget. Update docs and the assurance contract, run the battery, the ClojureScript suite and the formal gates, then open PR 3 stacked on PR 2.
