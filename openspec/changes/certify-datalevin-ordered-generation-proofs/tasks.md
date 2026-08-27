## 1. Fork: Commit Generation and Continuity

- [x] 1.1 Substitute `:db/current-tx` in the value position of `:db.type/long` attributes listed as commit-generation attributes without calling `allocate-eid`; reject the keyword for other value types.
- [x] 1.2 Verify at commit that every commit-generation datom carries the generation the store allocates; abort with `:datalevin/stale-generation` otherwise.
- [x] 1.3 Read the persisted `:max-tx` inside the write transaction before advancing; abort with `:datalevin/max-tx-continuity-violation` on mismatch and mark the connection write-unusable until reopen.
- [x] 1.4 Add fork contract tests: materialized generation equals committed `max-tx`; `:max-eid` unchanged by stamping; a second process committing between prepare and commit is rejected; the read snapshot reports the same ceiling as the committed generation.

## 2. Fork: Write Policy

- [x] 2.1 Add a persisted write policy (guarded, frozen, commit-generation attributes; stamp rules) in the meta DBI with `install-write-policy!`, `write-policy`, and a per-open random admission token accepted through `tx-meta`.
- [x] 2.2 Enforce the policy at the single post-expansion, pre-commit point that every committing entry reaches (`transact!`, `transact-async`, `transact`, `with-transaction`, `transact-tx-data`, and the blind-write fast path); speculative `with` results are not committed and are exempt; guarded writes without the token, missing stamps, and stale generations are rejected atomically.
- [x] 2.3 Reject `update-schema`, `clear-dbi`, `drop-dbi`, and `re-index` for frozen attributes without the token.
- [x] 2.4 Add fork contract tests: unadmitted tuple write, application `retractEntity` of an entity holding tuples, tuple change without its relation stamp, definition change without the schema stamp, stamp with the wrong generation, frozen schema change, admitted transaction with complete stamps, and a transaction touching no protected attribute; document the policy in `doc/write-policy.md`.

## 3. Module: Schema and Writer

- [x] 3.1 Replace the ref-typed stamp attributes with the three `:db.type/long` attributes; remove the ref attributes from the physical schema; reseed demo stores.
- [x] 3.2 Register the write policy in `ensure-physical-schema!` (guarded: every `:eacl.*` and relationship storage attribute except `:eacl/id`; frozen: the same; commit-generation: the three stamp attributes; stamp rules for both tuple attributes at position 1 and for definition and schema-string attributes).
- [x] 3.3 Implement the Datalevin writer role: hold the admission token, pass it in `tx-meta` on every submission, plan stamps to the scalar attributes, keep the write-fence CAS and commit-time functions unchanged.
- [x] 3.4 Remove `:datalevin-topology`; keep unsafe-flag, WAL, and HA rejection and add `:nolock` to the rejected flags; validate fork capabilities and the native profile, reject watermark regression before bootstrap mutation, then validate/install policy, complete generations, and source identity before readiness.

## 4. Module: Frame and Capabilities

- [x] 4.1 Implement `:schema-generation` over `:eacl.datalevin/schema-generation` and `:proof-frame` as one EAVT probe per requested relation on the owned snapshot, eagerly realized, `nil` for a missing generation.
- [x] 4.2 Advertise `:ordered-generations`; keep `:at-exact-snapshot` absent; confirm generations and `:native-revision` share the `max-tx` domain under the shared ceiling assertion.
- [x] 4.3 Add tests: identical-basis exact hit with zero frame probes; unrelated forward commit reuses answers and managed subproblems; relevant commit invalidates; missing generation is unavailable, not a violation; an injected above-ceiling generation is a contract violation.

## 5. Certification, Differentials, and Performance

- [x] 5.1 Extend adapter certification v2 to Datalevin: domain, ceiling, atomic stamping after every writer operation (add, retract, touch, batch, compare-and-set, delete-object batches, schema replacement and relation removal, safe retraction), and durable lineage across provider restart.
- [x] 5.2 Extend the randomized cached-versus-bypass differential and the mutation-race suite to the ordered-generation configuration, including writes from a second connection in the same process and restart between pages.
- [x] 5.3 Add restart tests: cursors, tokens, and managed lifting remain valid after close and reopen; a watermark regression still fails construction.
- [x] 5.4 Benchmark frame acquisition (zero, typical, maximum closures), exact hits, managed hits, and write commit latency with the policy active; record results and set a regression budget for the write path.

## 6. Documentation and Gates

- [x] 6.1 Update the README capability table, module README, PORTING.md, `docs/v8-backend-adapter-boundary.md`, and `docs/cache.md` for Datalevin ordered generations, the write policy, the rejected `retractEntity` path, and the trusted boundary.
- [x] 6.2 Regenerate `public-source-closure.json`, update `backend-dispatch.edn` and the adapter-certification ledger, run the Datalevin module suite, fork contract tests, the CI-equivalent battery, and `openspec validate --strict`.
