# Tasks: Per-Request Overhead Fixes

- [x] 1.1 `stable-plan-key` by schema identity (client generation cache / raw `:schema-identity`), lifecycle kept, revision dropped; capacity 256; `expire-plans!`
- [x] 1.2 `impl/with-request-engine`: process-stable lifecycle + `:schema-identity` (direct read, no proof op)
- [x] 1.3 `expire-cache!` (Datomic + orchestration) calls `expire-plans!`
- [x] 1.4 `seal-plan` encode-once (`sort-by-canonical`); fingerprint/plan invariance checked
- [x] 1.5 `request-schema` + validation on the miss path at all seven client sites; unstamped databases read directly
- [x] 1.6 `kernel?` positive-class memo (JVM)
- [x] 1.7 `eacl.backend.v8-test` plan-sharing expectations aligned; full battery green; source-closure ledger regenerated
- [ ] 1.8 Docs: `docs/cache.md` cache-layers table ("Sealed plan | same source scope, lifecycle, schema generation and permission root"), `docs/v8-consistency-cache-operations.md` if it names the plan key
- [ ] 1.9 Re-baseline `docs/benchmarks` for `can?`/lookup miss and hit on all backends
- [ ] 1.10 Follow-ups recorded: reducer constant factor (`retain-buffer` O(cap) rebuild per release, `schedule` map churn), union-arm subsumption in the sealed plan, `secure-format` canonical encode/digest cost (24 µs per small map; 2–4 digests per page)
