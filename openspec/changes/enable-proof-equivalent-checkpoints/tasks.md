## 1. Key and Scope

- [x] 1.1 Build the checkpoint key from the request context's `lineage` and `frame` plus plan fingerprint, traversal, subject type, anchor, and page size in `eacl.engine.v8`; remove `native-revision` from it.
- [x] 1.2 Drop `:snapshot-identity` from the continuation scope digest; delete the Datomic `:proof-equivalent` construction and its `:cursor-proof` field; remove the unused `:evict! :get-page :put-page! :get-heads :put-heads!` private-context functions.
- [x] 1.3 Update the `execution-binding`/`checkpoint-key` docstrings in `eacl.engine.stable-page` to state the frame rule and why a changed-slice hazard is excluded; leave the standalone token path exact.

## 2. State Closure and Counters

- [x] 2.1 Add the structural test over `history-free` output: exact semantic key set, closed data only, no function, database value, reader, lazy sequence, or delivered result.
- [x] 2.2 Add the cumulative-limit test: a checkpoint near a configured ceiling resumes and the ceiling is enforced exactly as replay would enforce it.
- [x] 2.3 Add a `:populate-cache? false` test: the page is correct, no checkpoint is published, and the next page replays.

## 3. Pipeline, Telemetry, and Parity

- [x] 3.1 Assert in tests that an invalid, stale, wrong-lineage, or changed-frame cursor performs no checkpoint store access, and that a valid cursor with a missing checkpoint replays from the boundary without restarting.
- [x] 3.2 Add miss-reason telemetry (`:absent :evicted :boundary-mismatch :overweight :plan-mismatch`) to `cache-stats` alongside hits, publications, and replacements; publication-disabled requests perform no retained-state or tombstone mutation.
- [x] 3.3 Port `continuation_reuse_test`, `stable_page_test`, and `stable_reducer_test` to CLJC and add them to the CLJS test runner; confirm `AdmissionKey` parity through publication and resume.

## 4. Conformance, Formal, and Documentation

- [x] 4.1 Add the shared conformance cases: checkpoint hit after an unrelated write equals replay (forward and reverse, same and retained older bases); after a relevant write an exact-capable source resumes the checkpoint on the accepted original basis while a current-only source produces no hit; durable-source restart and non-durable-source recreation; eviction and over-weight drop replay correctly.
- [x] 4.2 Cite `ReducerCheckpoint.dfy`, `RuntimeCheckpointComposition.dfy`, `ReducerReadScope.dfy`, and the scalar-frontier theorem in the assurance matrix entry for frame-keyed checkpoints; add a mutation control that restores `native-revision` to the key and requires a conformance failure on the unrelated-write case.
- [x] 4.3 Update `docs/stable-discovery-engine.md` and `docs/cache.md` (checkpoint row) for frame-keyed checkpoints, the visited-page exception, and `:populate-cache?`.
- [x] 4.4 Regenerate `public-source-closure.json`, run the CI-equivalent battery with the CLJS build last, and `openspec validate --strict`.
