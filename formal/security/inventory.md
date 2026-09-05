# Live keyring integration inventory

- Primary options: `eacl.client.orchestration/base-client-opt-keys`, `normalize-security-root-keyring`, `make-client` (~4440–4815). Static `:security-key`, `:security-keyring`, `:security-kid` become `:format-options {:current-kid ... :keyring ...}` captured at construction. Errors currently include invalid ring `:value` and chained causes: redact at the configuration boundary.
- Dedicated Zed options are documented at README ~1689 but are NOT accepted by the current runtime. Existing Datomic test named `zed-token-keyring-is-shared-and-rotatable-across-clients-test` actually uses the primary `:security-keyring`. Phase 4 should implement the documented dedicated static names and the new controller option; primary fallback already exists.
- `eacl.secure-format/signing-context`, `encode-authenticated`, `decode-authenticated`: HMAC envelopes include authenticated `kid`; direct map selection, no trial loop. Only production caller is `eacl.causal-token`. Canonical key normalization lives here. Avoid a secure-format <-> controller implementation cycle (a narrow snapshot/derived-key protocol is one option).
- `eacl.causal-token/issue`, `token-data`: version 4, domain `eacl/zed-token/envelope/v4`, kid already authenticated. Orchestration uses primary format opts for basis-token, write responses, and consistency decode (~1731,2929); dedicated scope must cover every one.
- `eacl.cursor`: confidential envelope v13, kid authenticated with nonce/ciphertext. `encode-context`, `decode-aead`, `codec-identity`, `cache-policy-identity`, `cursor->token`, `token->authenticated-cursor` all currently consume static rings. Client-private codec cache can skip crypto for locally minted tokens, so snapshot/key-presence acceptance must precede hits. Decoder must return authenticated kid alongside payload. Preserve deterministic cached cursor reuse across unrelated rotation when the issuing key remains accepted; new encodes must use current active key.
- `eacl.continuation` owns bounded stores for continuation contexts. Stable-page checkpoints, visited/rendered page stores, lookahead/replay stores are selected by orchestration. Tag and partition by authenticated series kid, and carry it from decoded cursor through publication. Current selected-basis/evaluator/time/certificate checks remain independent.
- `eacl.cache`: public export/restore is currently trusted, already-decoded flat `basis-snapshot-v2`, not a cryptographic provider protocol. README explicitly assigns byte authentication and bounds to the host. No production authenticated cache envelope currently exists. Keep the trusted low-level shape compatible, add protected snapshot APIs/boundaries with kid metadata and import trust scope, and preserve local answer-cache entries on retirement.
- `cache/restore-basis-snapshot!` builds off-side subproblem stores and swaps lifecycle. `export-basis-snapshot` exports only answer/denotation entries. Rendered pages are process-local and omitted. Imported trust must remain distinguishable from later locally computed entries; misses should recompute selected snapshot without invalid grants.
- Existing tests: secure_format_test, cursor_test, causal_token_test, continuation_test, cache_test; backend config tests and consistency_cache_test; shared contract_support; advanced DataScript cljs runner. New controller/oracle and rotation contracts should be shared across CLJ/CLJS and four backends.

Public controller APIs are in `eacl.core`: `security-keyring`, `security-keyring?`,
`security-keyring-status`, `replace-security-keyring!`, `add-security-key!`,
`activate-security-key!`, and `retire-security-key!`. `eacl.security.keyring`
owns validation and atomic updates; `eacl.security.protocols/KeyringSource`
allows codecs to capture state without depending on controller construction.
Errors use `:eacl.keyring/invalid` with a closed reason or
`:eacl.keyring/conflict` with safe status. Transport preserves the existing
`:eacl.pagination/invalid-cursor` and `:eacl/invalid-zed-token` categories,
adding reason `:security-key-unavailable` when the named key is absent.

Constructor options are `:keys`, `:active-kid`, and optional lower ceilings
`:max-keys` / `:max-retired-kids`. Hard limits are 64 accepted keys, 65,536
retired ids, 1,024 encoded bytes per id, and 4,096 bytes per root key. Replacement
requires the full desired `:keys` and `:active-kid` plus `:expected-generation`.
Accepted identifiers cannot change key material; removed identifiers cannot
return. Repeated add/activate/retire convenience operations are idempotent when
they request an already established state. Full replacement advances once even
when the requested keys match, and convenience operations retry at most 32 CAS
conflicts. Each generation owns a bounded 256-entry derived-key cache; its keys
will include kid, derivation domain, and format version.

Configuration contract: each primary or dedicated scope accepts either its
controller or its static key/keyring plus optional active id, never both. No
primary material creates a private controller around the existing process-local
key. No dedicated options select primary fallback. Any dedicated options select
an independent controller and require dedicated key material; the dedicated id
alone does not silently select primary material. Static key and keyring together
are invalid, as are a controller plus even an explicit static id. Equivalent
construction cases will run through the shared backend contract.

## Implemented boundaries

The baseline inventory above records the pre-change gaps. Static/controller
normalization now lives in `eacl.security.configuration/format-scopes`; primary
and dedicated scopes are installed in orchestration runtime options separately.
`eacl.secure-format/capture-keyring` and the cursor codec consume a single
captured generation. The cursor transport envelope is v6 (`eacl_c6_`), carrying
Relay payload v13; these two versions must not be conflated.

Authenticated snapshot APIs are available through all four backend modules.
`eacl.cache` authenticates the `eacl_cache1_` envelope before normal closed
snapshot validation. `eacl.security.imports` retains the verifying controller
and ID in non-serializable private wrappers. Completed-answer and denotation
lookups consult current key acceptance. A consumed import suppresses derivative
publication into answers, denotations, range segments, continuations and rendered
pages. Ordinary locally computed entries retain their existing representation.
Exports omit imported entries to prevent re-signing from extending their trust.
