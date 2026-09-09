## 1. Characterize the current work
- [x] Load `regressions/eacl/cleanup_characterization.clj` in a test-enabled nREPL and use disposable native fixtures.
- [x] Seed controlled orphan counts through preparation APIs and measure complete scans, rows realized, entity/fact reads, and committed batch sizes.
- [x] Compare several Q values at fixed B, with and without a stable active relationship background.
- [x] Record baseline counts separately from latency; author timing/allocation budgets before sampling.

## 2. Add the sweep proof boundary
- [x] Choose a synchronous runner or opaque session without changing existing single-batch semantics.
- [x] Capture candidate/fact/source/revision evidence once under an owned valid snapshot.
- [x] Define and enforce capture memory/candidate budgets before exceeding them; state retained-memory complexity explicitly.
- [x] Add or certify authoritative own-commit revision evidence for each participating backend.
- [x] Preserve exact head guards, candidate-fact guards, and configured transaction-size limits for every chunk.
- [x] Advance the certificate only through successful own cleanup commits and invalidate it on any unproved transition.

## 3. Add hostile transition and work contracts
- [x] Race attachment of a candidate between batches and prove no attached qualifier is deleted.
- [x] Cover unrelated writes, source/lifecycle changes, lost commit results, cancellation, partial success, and stale-session restart.
- [x] Assert one global capture for a quiescent sweep within budgets, rather than one capture per batch.
- [x] Compare final state and collected ids with an independent single-snapshot orphan oracle.
- [x] Add a mutation restoring per-batch full capture and a mutation accepting an unproved current revision; both must fail their respective tests.

## 4. Validate and document
- [x] Run actual native backend contracts and applicable formal/refinement gates.
- [x] Run `bin/formal source-closure` and the installed OpenSpec validator.
- [x] Document single-batch versus sweep complexity, memory bounds, restart semantics, and backend eligibility.
- [x] Keep benchmark data and generated verification reports under ignored target; do not advertise unmeasured latency improvements.
