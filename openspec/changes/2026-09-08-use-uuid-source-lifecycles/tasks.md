## 1. Review the contract and capture the baseline

- [x] 1.1 Perform the requested adversarial review of this proposal, including UUID-only upgrade errors, the initial sentinel, Datalevin's persisted UUID and coordinated rollout; record the correctness/performance review disposition before implementation.
- [x] 1.2 Re-read current lifecycle, artifact and backend source plus overlapping OpenSpec changes; deliver a symbol-to-requirement inventory identifying lifecycle inputs, default/rotation owners, public basis views and private runtime identities without altering unrelated work.
- [x] 1.3 Inventory every authenticated format, canonical-version/KDF consumer, source/proof/cache identity ABI and external caller; allocate unused upgraded versions/domains and exact error mappings, and verify every affected writer/reader has a named integration test.
- [x] 1.4 Author the benchmark protocol and missing fixed/ratio/byte budgets before candidate sampling; capture UUID-string, structured-lifecycle and short-default baselines under matched CLJ/CLJS workloads, retaining raw samples only under ignored `target/benchmarks/`.

## 2. Establish the representation and lifecycle proof obligations

- [x] 2.1 Add or extend the formal UUID domain and canonical representation model; verify injectivity, exact round trips, UUID/string type separation and full 128-bit equality against the existing formal toolchain.
- [x] 2.2 Connect UUID equality to the existing native-generation and scalar-frontier source/lifecycle isolation claims; verify equal UUID/different source and equal source/different UUID cannot grant reuse, with explicit freshness/non-reuse assumptions.
- [x] 2.3 Extend the bounded lifecycle transition campaign for default sharing, ordinary commits, narrow clear, full rotation, retained snapshots and restore/publication races; verify the positive model and failing omitted-scope/lifecycle and detached-publication controls.
- [x] 2.4 Document the production refinement map and trusted boundaries for RNG, host persistence, host UUID values, parser and crypto; verify the map names actual call sites and does not claim arbitrary host code is generated or UUID uniqueness is proved.

## 3. Implement native UUID and canonical encoding

- [x] 3.1 Implement one UUID lifecycle validator for explicit options and identities, distinguishing omission from nil/false and checking the concrete CLJS UUID value rather than protocol membership alone; verify native acceptance, malformed/impostor rejection, legacy upgrade errors, frozen owned identity with initialized hash despite mutation of input, exposed basis, or restored values and unchanged runtime on failure.
- [x] 3.2 Add the allowlisted canonical UUID scalar and explicit deterministic rendering; verify CLJ/CLJS cross-encoded golden fixtures, independently constructed equal values, UUID/string keys, high-bit cases and unchanged canonical bytes for existing non-UUID values apart from version framing.
- [x] 3.3 Enforce canonical UUID decoding and existing resource bounds before host UUID text normalization can hide invalid spellings; verify malformed, uppercase, abbreviated, unknown-tag, oversized, deeply nested and truncated inputs reject before use in both runtimes.
- [x] 3.4 Add executable representation mutation controls for type coercion, comparator collision, a dropped half, JS Number truncation and noncanonical wire aliases; verify each control fails its named test/refinement gate rather than timing out or being skipped.

## 4. Integrate backend and runtime lifecycle ownership

- [x] 4.1 Replace the short initial string with the documented reserved UUID for supported omitted defaults; verify two clients of one durable source share a lifecycle and two sources sharing the sentinel remain isolated.
- [x] 4.2 Update Datomic, Datahike and DataScript source/basis construction to carry native UUIDs without broadening other identity fields; verify causal/exact/current-only conformance, cache-disabled paths and required fresh identity on connection-local source recreation.
- [x] 4.3 Update Datalevin lifecycle input and persisted-configuration examples while preserving watermark/topology and local-expiry restrictions; verify missing/initial/legacy lifecycle rejection and successful reopen with a shared explicit UUID.
- [x] 4.4 Generate native UUIDs for supported local full expiry and preserve same-UUID local full reset and reject a return from noninitial to initial; verify failures leave installed state intact and ordinary commits/narrow clears preserve public UUID and proof health.
- [x] 4.5 Integrate UUIDs with coherent runtime capture and retained snapshots without replacing private incarnations; verify old requests cannot repopulate new stores, retained evaluation keeps its captured lifecycle and restore-race failure is atomic.

## 5. Upgrade authenticated artifacts and cache provenance

- [x] 5.1 Apply the reviewed canonical-version/domain matrix to causal tokens and public consistency descriptors; verify new issue/verify parity, complete-scope rejection, retired keys, tampering and explicit old-format upgrade errors before source acquisition.
- [x] 5.2 Upgrade cursor envelope and construction/cache identity versions; verify nil means first page, supported continued pages remain exact, raw maps remain rejected before authentication/selection and obsolete/tampered cursors never restart silently.
- [x] 5.3 Remove pre-fingerprint warning-only cursor acceptance without changing current dependency/exact-recovery semantics; verify old artifacts are rejected and unrelated schema changes, exact fallback, query mismatch and expiry retain their specified outcomes.
- [x] 5.4 Upgrade cache snapshot/export/restore, checkpoint and proof/provenance formats identified in the inventory; verify explicit legacy restore fails without installation, opportunistic rejection can become a safe miss and compatible cross-process restoration preserves UUID type.
- [x] 5.5 Validate indirect canonical-version/KDF consumers and all active/retired keyring cases; verify no affected writer keeps an old version/domain and no valid-looking legacy UUID string acquires new-format authority.
- [x] 5.6 Remove obsolete lifecycle normalizers/defaults and update repository-local examples; verify a source inventory finds no UUID-to-string lifecycle workaround or alternate legacy execution/restore path, with external demo/0tx call sites listed for their own upgrade session.

## 6. Qualify correctness, performance and release readiness

- [x] 6.1 Run the current applicable formal proof, generated-runtime, source-closure and bounded temporal gates, including every UUID mutation control; verify current source correspondence and keep generated logs/results only under ignored `target/formal/`.
- [x] 6.2 Run the relevant core and all public-backend conformance/regression suites through an isolated nREPL with the required aliases; verify cache hit/miss/bypass, read/write token exchange, lifecycle rotation, restart, history replacement and host-coordination failure witnesses without touching shared live stores.
- [x] 6.3 Run the CLJS production and cross-runtime UUID/token/cursor suites last in their designated JVM, following the repository's shutdown-agents constraint; verify no unexplained host equality/hash/encoding difference remains.
- [x] 6.4 Run the preregistered matched-host performance matrix for lifecycle operations and full checks/counts/pages/batches/snapshots; verify binding JVM, work and byte budgets, zero added I/O/per-hit parse work and unchanged authorization/backend traces. Retain all latency/heap/wire trade-offs and original failed CLJS comparisons separately from the operator's 2026-09-08 acceptance of those costs for browser demos.
- [x] 6.5 Produce an authored qualification disposition linking the reproducible harness and private/CI evidence, listing assumptions and unqualified environments; verify it distinguishes simplification, demonstrated speedup or tie, and every failed acceptance gate without committing observed reports or source hashes.
- [x] 6.6 Write coordinated upgrade/rollback instructions and the consumer/version matrix; verify a fixture rehearsal obtains fresh artifacts, preserves stored relationships and Datalevin safety state, rejects mixed-version artifacts and never recycles retired lifecycle authority.
- [x] 6.7 Reconcile this change's delta specs and checklist with the reviewed implementation and run strict OpenSpec validation; verify no implementation, proof or performance item is marked complete solely because planning artifacts exist.
