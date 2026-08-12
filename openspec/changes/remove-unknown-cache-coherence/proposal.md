## Why

EACL has required authorization mutations to use its supported writers since v6, yet the public cache contract still exposes an `:unknown` authority mode and several alternate proof paths. Making the supported-writer invariant unconditional lets EACL use one formally justified, native-generation coherence algorithm for every eligible current request while failing closed to exact-snapshot evaluation whenever proof is unavailable.

## What Changes

- **BREAKING** Remove the `:coherence-authority` client option and both former values; supplying the key is invalid configuration.
- **BREAKING** Remove the `:proof-mode` client option, including `:auto`, `:mutation`, `:content`, and `:none`; native mutation generations become the only cross-snapshot proof source.
- Make proof-backed managed reuse automatic for every deterministic, cacheable, ordinary current request, including the default demand-bounded evaluation, while retaining exact-first lookup and exact-snapshot fallback.
- Replace full relation-generation cache identities with a schema generation and scalar dependency frontier derived from the complete dependency closure. Formally require the closure to be a deterministic function of equal schema semantics and normalized request, and require every supported mutation to stamp every affected relation with the globally ordered committed transaction.
- Compute one immutable, adapter-scoped dependency proof frame per request and share it across completed answers, managed subproblems, schema planning, and cursor validation instead of maintaining duplicate backend and orchestration proof paths.
- Require proof-capable adapters to certify complete dependency discovery and globally ordered native relation generations. Missing, malformed, oversized, or uncertified proof data makes only that request exact-only.
- Treat managed entries as completed semantic answers. Never publish partial traversal, incomplete continuation, or partially proved state.
- Fail closed for custom identity codecs unless they declare deterministic behavior and a stable fingerprint; isolate unfingerprinted codecs to one client lifecycle so cursors cannot be accepted across incompatible clients.
- Remove the unused generated runtime cache-validation operation while retaining useful formal lemmas.
- State the supported mutation boundary unambiguously: authorization schema, relationship, identity, and safe entity-deletion mutations MUST use EACL APIs or documented EACL transaction data/functions that atomically publish the required generations.
- Document only the current API and operational contract. Unsupported raw mutation requires data repair where necessary, request quiescence, and lifecycle rotation in every affected process before cached traffic resumes.
- Add formal counterexample searches, cross-backend certification, randomized oracle tests, and dependency-cardinality benchmarks before claiming correctness or performance.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `forward-history-cache-coherence`: Replace authority selection and vector proof identities with unconditional managed coherence backed by a formally certified scalar dependency frontier, complete proof availability rules, and precise unsupported-mutation recovery.
- `backend-native-revision-consistency`: Preserve native revision behavior independently of answer caching while requiring proof and cursor identities to remain adapter- and lifecycle-scoped.
- `modular-backend-workspace`: Replace duplicate proof operations with one backend-neutral proof-frame capability and remove authority and proof-mode configuration from every bundled adapter.

## Impact

- Affects shared cache orchestration, demand-result publication, dependency extraction, cursor validation, custom identity configuration, adapter proof capabilities, configuration validation, generated formal boundaries, all bundled backend constructors, tests, benchmarks, and public documentation.
- Supersedes the authority and full-vector proof portions of `simplify-cache-coherence` while preserving its immutable-request-snapshot, lifecycle-rotation, initial relation-generation, atomic writer, endpoint-liveness, schema-fencing, and safe-retraction requirements.
- Introduces no persisted schema migration, transaction listener, transaction-log scan, mutation journal, graph head, or database-global cache CAS.
- Provides no compatibility or migration mode for removed client options.
