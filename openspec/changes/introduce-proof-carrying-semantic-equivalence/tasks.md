## 1. Frame Contract

- [x] 1.1 Change `:proof-frame` to return `[[relation-id generation] ...]` only; remove the frame's schema read and the schema-generation cross-check; take the schema generation from the certified `:schema-generation` operation in `eacl.proof-frame`.
- [x] 1.2 Add the numeric-domain obligation (generations share the `:native-revision` domain) to the adapter obligations in `eacl.backend.v8`; convert Datomic's adapter to `d/tx->t`; confirm Datahike, DataScript, and Datalevin already return `max-tx`-domain values.
- [x] 1.3 Assert `schema-generation <= revision` and every relation generation `<= revision` in the frame validator.
- [x] 1.4 Split frame outcomes into complete, unavailable (absent generation, missing operation, closure bound, transient adapter failure), and contract violation (malformed shape, duplicate or non-canonical ids, non-integer or above-ceiling generation); add `eacl.proof-frame` tests for every class.
- [x] 1.5 Alter Datomic `:eacl/relation-version` to `:db/noHistory false` in the additive schema install (new and existing databases), update its docstring, and add a test that a frame read through `d/as-of` at an older `t` returns the stamps current at `t` after an index job; record the history-index growth in the Datomic write benchmark.
- [x] 1.6 Gate managed lifting on frame readability instead of basis kind: an as-of basis with a readable frame lifts; an unavailable frame is exact-only; add Datomic `as-of` and Datahike `AsOfDB` (keep-history) tests for both outcomes and a reader-Peer session test that entries survive a token change across unrelated writes.

## 2. Lineage and the Reuse Key

- [x] 2.1 Define `lineage` = `{:source-scope :source-lifecycle}` on the request context, derived from basis identity, and expose `frame` and `lineage` as the single values every cache, cursor, and checkpoint consumer reads.
- [x] 2.2 Replace the single installed `ManagedGeneration` and its order guard with a bounded map keyed by `(lineage, schema-generation)`; keep entry keys `[semantic-key kind dependency-stamp]`; drop `:source-lifecycle` from `semantic-key`.
- [x] 2.3 Remove every remaining direction comparison or documentation claim that lifting is forward-only; add a test in which a retained older snapshot hits an entry computed at a newer basis with an equal frame, and a test in which the same pattern misses when the frame differs.
- [x] 2.4 Oblige basis sources to mint a fresh source id per live source when the backend cannot persist one (DataScript, Datahike memory stores with any configured id, Datomic `mem` databases by their generated database id); add a cross-process rejection test per non-durable source under the constant default lifecycle and a shared keyring.

## 3. Contract Violation Handling

- [x] 3.1 On a contract violation: evaluate exactly, set the runtime's sticky `managed-lifting-disabled?`, count `:proof-contract-violations` by reason in `cache-stats`, invoke the optional diagnostic reporter once per reason per lifecycle; clear the flag in `expire-cache!`.
- [x] 3.2 Make cursor continuation treat a violated frame as proof-unavailable (exact fallback or typed stale outcome) and never as equality.
- [x] 3.3 Verify exact-basis caching, revision tokens, and authorization availability are unaffected by disablement.

## 4. `:populate-cache?`

- [x] 4.1 Validate `:populate-cache?` (`nil`/boolean) on every cache-capable public read; strip it from every cache, cursor, and continuation identity like `:cache?`.
- [x] 4.2 Suppress completed-answer, managed-subproblem, checkpoint, and visited-page publication when `false`; leave lookups, cursor proof acquisition, and request-local memoization unchanged.
- [x] 4.3 Add tests: read-only request hits an existing entry and publishes nothing; miss evaluates exactly and publishes nothing; `:cache? false` with either value performs no lookup and no publication; a paginated read-only request still validates its cursor.

## 5. Formal, Certification, and Documentation

- [x] 5.1 Cite the existing `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` from the assurance matrix and `docs/cache.md`, document `lifecycle` in `ScalarFrontierCoherence.dfy` as runtime lineage (source scope plus lifecycle), update the `NativeGenerationCoherence.dfy` lifting-direction statement, and re-pin the manifest.
- [x] 5.2 Extend adapter certification v2 with executable domain, ceiling, and per-live-source identity obligations for every bundled adapter; replace literal-only registry detectors for these obligations with detectors that run against the adapter.
- [x] 5.3 Extend the randomized cached-versus-bypass differential with retained older bases and concurrent publication order.
- [x] 5.4 Rewrite the cache sections of `docs/cache.md`, `docs/v8-backend-adapter-boundary.md`, and `docs/v8-consistency-cache-operations.md`: the reuse rule, lineage, domain and ceiling obligations, violation handling, `:populate-cache?`; remove "forward-only" wording.
- [x] 5.5 Regenerate `public-source-closure.json`, run the CI-equivalent battery, CLJS build last, and `openspec validate --strict`.
