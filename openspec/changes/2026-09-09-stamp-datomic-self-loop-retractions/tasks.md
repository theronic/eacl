## 1. Establish the failing invariant
- [x] Run the supplied isolated-self-loop native regression on the pinned commit.
- [x] Assert the `d/invoke` stamp set, actual generation advance, removed entity/halves, and unchanged unrelated generation.
- [x] Add a component-child self-loop case and a mixed closure case that cannot mask the missing stamp.

## 2. Correct the embedded projection
- [x] Keep all local/repair records in `halves`.
- [x] Derive peer retractions using `keep :op` and Relation ids independently from all records.
- [x] Preserve exact qualifier-bearing tuple values and one idempotent stamp per affected Relation.
- [x] Coordinate installed-function replacement with `2026-09-09-protect-qualified-control-plane-from-safe-retraction`.

## 3. Strengthen implementation/model correspondence
- [x] Compare native stamp sets against an independent affected-edge oracle over small generated graphs and closures.
- [x] Cover self-loops in both tuple halves, repeated calls, missing numeric targets, and ordinary non-self cases.
- [x] Add a production mutation that restores the premature nil-op filter and confirm a native test kills it.
- [x] Keep the formal affected set inclusive of self-loops; do not weaken the writer obligation to match the bug.

## 4. Validate and release
- [x] Run native safe-retraction, generation/cache, and backend contract suites in nREPL.
- [x] Run relevant Dafny/refinement gates and `bin/formal source-closure`.
- [x] Validate this OpenSpec change and document the explicit installed-function upgrade.
- [x] Keep storage ABI v8; do not commit generated solver output or source-hash refresh pins.
