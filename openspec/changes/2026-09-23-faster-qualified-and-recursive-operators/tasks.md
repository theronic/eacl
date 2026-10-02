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

- [x] 4.1 Classify linearly guarded components and their members (design D3), derived outside the plan fingerprint. Verify plan tests:
  - `inherited` is guarded, with its guard on the recursive edge;
  - an exclusion's right operand becomes a subtracted guard;
  - a guard may reach a union-only permission through an arrow, or be a member of a lower guarded component;
  - an operator above a guarded component delegates to it;
  - an intersection with two recursive children is not guarded, nor is a guard that is itself an operator permission. (Recursion through an exclusion's right operand is rejected by stratification.)
- [x] 4.2 Implement the guarded memoized search over member rules:
  - guards are decided exactly from holdings, the oracle and intermediates;
  - positive evidence follows D1;
  - a subtracted guard opens only when plainly absent;
  - anything uncertain falls back to the tabled point evaluation.

  Decide truncated holdings up to their bound and extend the scan on demand. Verify that guarded checks and detailed lookup items equal the tabled evaluator's with plain, expiring and caveated guards and subtracted guards, and that truncated holdings decide every resource exactly.
- [x] 4.3 Route guarded plans through a delegated view whose guarded members use the guarded oracle, for lookups, counts, checks and reverse operations. Candidates come from the D2 generator. Verify the delegated-recursion and differential tests.
- [x] 4.4 Prove in Dafny that flattening preserves the denotation, that reachability in the guarded graph equals the least fixed point of a linearly guarded component, and that a guarded rule's deadline is the earliest of its evidence. Verify `dafny format` and `bin/formal verify`.
- [x] 4.5 Add `eacl.engine.guarded-membership-refinement-test`. It generates random guarded programs with qualified relationships and runs a transcription of the guarded search beside production. It checks decisions, memos, skips and deferrals, certificates against an independent widest witness, and permissionship against the tabled evaluator. Extend the delegation campaign with guarded roots, checked against an independent guardedness oracle, the stratified semantics and the tabled route. Verify on the JVM, in ClojureScript and with a 1,000-seed sweep.
- [x] 4.6 Register mutation controls: an ignored guard, a flipped subtracted guard, a non-linear component classified as guarded, and truncated holdings decided beyond their bound. Verify each is killed.
- [x] 4.7 Apply the tabled evaluator's constant-factor changes (design D4): memoized printed question keys, a decorated component sort, attachment of pending probes only, and lazy command identity. Keep every decision, certificate, checkpoint, digest and counter identical. Verify the recursive evaluator tests, and profile the gain on the tabled route.
- [x] 4.8 Verify the gate's guarded cases are within budget. Update docs and the assurance contract, run the battery, the ClojureScript suite and the formal gates, then open PR 3 stacked on PR 2.
