## 1. Baseline and API contract

- [x] 1.1 Inventory every current primary and dedicated Zed-token key option, fallback rule, normalized keyring value, cursor/Zed/cache envelope, derived-key cache, continuation store, cache export/import path, and public documentation location; verify the inventory names the owning source and tests for each path.
- [x] 1.2 Define the public `SecurityKeyring` controller, safe status shape, typed conflict/error taxonomy, full-state replacement API, and add/activate/retire convenience APIs; verify API review covers CLJ/CLJS portability and secret redaction.
- [x] 1.3 Specify compatibility between primary static/controller options and dedicated Zed-token static/controller options, including primary fallback and rejection of mixed sources within one scope; verify construction tests enumerate every accepted and rejected combination.

## 2. Pure keyring state machine

- [x] 2.1 Implement a pure validated keyring-state constructor with non-empty ring, active-key presence, bounded key/id collections, canonical key ids, existing key-strength rules, and no secret-bearing errors; verify unit tests kill each omitted validation.
- [x] 2.2 Implement generation-guarded complete-state replacement over an atomic immutable state and verify sequential tests prove all-or-nothing visibility and exactly-one generation advancement.
- [x] 2.3 Implement install, activate, and retire as bounded CAS operations over the common replacement primitive; verify racing operations are linearizable and stale updates return the typed conflict.
- [x] 2.4 Track retired key ids/fingerprints privately and reject every retired-id reintroduction without exposing fingerprints; verify currently accepted same-key installation is idempotent and no retired id can revive old artifacts.
- [x] 2.5 Add a pure test-only transition oracle and generated operation traces; verify production transitions equal the oracle without importing the oracle into public/runtime source.

## 3. Runtime integration

- [x] 3.1 Let static primary/Zed options construct private controllers, preserve Zed-to-primary fallback, and let explicit controllers be shared by multiple clients; verify updates reach every sharing client while separate controller scopes remain isolated.
- [ ] 3.2 Capture exactly one controller snapshot per protected encode/decode and select keys by direct id lookup; verify instrumentation detects no second mutable-state read and no ring-wide key trial.
- [x] 3.3 Cache domain-separated derived keys by controller generation, key id, domain, and format version and remove retired/unreachable entries; verify rotation never pairs one state's id with another state's key.
- [x] 3.4 Add safe keyring status and optional rotation events with closed non-secret fields; verify log, metric, event, exception, and status redaction tests find no raw/encoded key fragments.

## 4. Protected formats and cursor state

- [x] 4.1 Audit and upgrade every cursor, Zed-token, and portable authenticated-cache envelope to carry one authenticated key id selected from its primary or dedicated controller scope; verify format tests reject id tampering, wrong-scope keys, and implicit-key fallback.
- [x] 4.2 Tag continuation, visited-page, rendered-page, replay, and equivalent cursor-cache entries with the authenticated series key id; verify mismatched entries cannot influence resume.
- [x] 4.3 Add synchronous ineligibility plus targeted cleanup on retirement for cursor-related and externally authenticated/imported cache records, without flushing locally computed answer caches; verify a deliberately skipped physical cleanup still errors/misses correctly through key presence or trust epoch.
- [x] 4.4 Make unknown/retired cursor and causal-token keys return the specified typed request errors with no restart/rebase fallback; verify first-page and consistency fallbacks are not invoked.
- [x] 4.5 Make unavailable portable cache-entry keys produce authenticated cache misses followed by selected-snapshot evaluation; verify cached and uncached answers remain equal and request success is unaffected.
- [x] 4.6 Update cache snapshot import/export so key ids remain metadata but secret keys never serialize; verify archive inspection contains no key material and retired-key entries are rejected as misses on restore.

## 5. Concurrency and rollout conformance

- [x] 5.1 Add deterministic barriers around encode/decode versus activation/retirement and verify each result linearizes wholly before or after the update.
- [x] 5.2 Add a two-Peer rollout fixture covering distribute, acknowledgement, activation skew, overlap, and retirement; verify both directions decode throughout a correct rollout and the missing-distribution case fails with the expected key-unavailable error.
- [x] 5.3 Exercise non-expiring and finite-TTL cursors across activation and retirement; verify age expiry and key retirement retain distinct error categories.
- [x] 5.4 Run cross-backend shared conformance for controller updates and protected formats, including DataScript CLJS capability boundaries; verify equivalent supported configurations have identical state/error semantics.
- [x] 5.5 Add killed controls for active-key removal, stale generation acceptance, ring-wide fallback, unauthenticated kid, cursor silent restart, cache fail-open, and secret-bearing diagnostics; verify each control is detected by a focused test.

## 6. Performance and simplicity gates

- [ ] 6.1 Benchmark steady-state mint/decode before and after live-ring integration at ring sizes 1, 2, 4, and 16; verify the hot operation performs one atomic read and one direct map lookup with no work proportional to ring size.
- [ ] 6.2 Measure activation and retirement with populated cursor/imported-cache stores; verify synchronous trust detachment is bounded, physical cleanup may be deferred safely, and locally computed authorization caches are not globally flushed.
- [ ] 6.3 Review production source against the simplicity inventory and remove runtime oracle execution, distributed acknowledgements, database reads, network secret fetching, redundant key checks, and global cache flushes introduced only for testing; verify the final request path matches Design D8.

## 7. Documentation and release

- [x] 7.1 Publish a security-key guide explaining entropy, byte handling, unique key ids, static versus live configuration, secret-manager ownership, non-durability, and realistic JVM zeroization limits; verify examples contain placeholders rather than usable secrets.
- [x] 7.2 Publish the multi-Peer distribute/observe/activate/overlap/retire runbook with rollback and partial-rollout recovery; verify a reviewer can execute a two-Peer drill using only documented public APIs.
- [x] 7.3 Document that default non-expiring cursors require indefinite old-key retention for lossless resume, and show finite TTL configuration for bounded retirement; verify the warning appears beside every rotation example.
- [x] 7.4 Update API docs, release notes, backend/module guides, cryptographic assumptions, and error references; verify key addition/activation does not claim to rotate source lifecycle or authorization proofs.
- [ ] 7.5 Run the combined nREPL test battery, CLJS suite, source-closure gate, secure-format/adversarial tests, and OpenSpec strict validation; verify all pass before release.
