# Final assurance-claim audit

Date: 2026-08-02

## Decision

EACL v8.0 is **not formally verified as a complete public authorization
engine**. The release manifest must remain `:not-verified`, and the validator
must return nonzero.

The implemented proof and runtime evidence supports narrower claims:

1. the Dafny semantics, direct/acyclic algorithms, recursive algorithms,
   pagination window, cache-decision, cursor-decision, strict wire-format, and
   temporal predicates satisfy the theorems named in the manifest;
2. the generated Java and JavaScript kernels agree with the executable
   semantics on the recorded fixtures and generated campaigns;
3. Datomic, DataScript, and Datahike passed the recorded adapter certification
   tests for the exercised finite fixtures;
4. decoded relationship-page, cursor-continuation, and authenticated
   cache-validation decisions are routed through the generated decision
   kernels in the internal verified modes; and
5. strict generated Java and JavaScript boundaries evaluate complete
   materialized `can?`, lookup, and count requests, and the Java reference
   agrees with cached and uncached public state traces on all three backends.

These claims remain conditional on the trusted toolchain, generated-code
compilers, runtimes, FFI conversion, adapter contracts, canonicalization,
cryptography, collision resistance, entropy/key management, clocks, and
configured resource limits listed in the manifest and trusted-boundary
documents.

## Claims that are deliberately withheld

- Complete materialized CLJ and CLJS schema/query/result conversion is
  implemented, but an indexed generated-to-adapter callback boundary is not.
- The generated direct, acyclic, and recursive traversal kernels do not yet
  drive every public `can?`, lookup, or count operation.
- The current-generation cache, exact/arbitrary-DB bypass, scalar stamp law,
  least-fixed-point managed frame, and selected-snapshot rendering are proved
  and integrated for all three adapters. This is a conditional cache
  refinement claim, not complete public-engine verification.
- Production shadow comparison does not yet cover full traversal results,
  ordering, counts, typed errors, limits, and provenance at the required
  rollout volumes. Existing shadow diagnostics are fail-open with respect to
  legacy authority and redact request/result values without emitting
  guessable hashes.
- No independent security/formal-methods review has been obtained.
- The current-cache performance gate passes. The verified-authoritative
  cutover remains pending complete public generated-kernel routing and shadow
  volume, not cache-hit cost.

## Public wording audit

The current formal-verification guide defines “verified” only for a mapped
generated operation under its listed assumptions, explicitly says that full
traversal remains legacy, and requires the manifest to remain
`not-verified`. The generated providers are formal smoke/integration artifacts,
not a shipped supported public engine.

Historical reports use “verified” to mean empirically reproduced or
test-confirmed. They are dated engineering records and are not release-level
formal-verification claims. No README or release-level public statement claims
that the complete v8.0 engine is formally verified.

## Gate evidence

- Clean checksum-locked Dafny cache: 254 obligations across 12 files, zero
  errors, warnings, timeouts, admitted lemmas, `assume`, `axiom`,
  `{:verify false}`, opaque, or extern declarations.
- TLA+/Apalache: both models typechecked; compact length 12, detailed length 6,
  scheduled detailed length 3, and all six initiation/consecution/implication
  obligations passed.
- Counterexample corpus: 10 tests, 107 assertions, zero failures/errors.
- Mutation controls: 1 test, 22 assertions, all 9 registered mutants killed.
- Public non-benchmark CLJ suite: 371 tests, 13,115 assertions, zero failures/errors.
- DataScript CLJS suite: 84 tests, 1,265 assertions, zero failures/errors.
- Generated Java suite: 18 tests, 7,141 assertions, zero failures/errors.
- Generated JavaScript suite: 12 tests, 609 assertions, zero failures/errors.
- OpenSpec strict validation: passed.
- Heavy benchmark: 9 tests, 3,403 assertions, zero failures/errors; the
  current-cache measurements are recorded in
  `formal/verification/performance-gates.edn`.

## Manifest audit result

The generated manifest is correct only if it:

- records `:assurance-status :not-verified`;
- records `:complete-public-engine` as incomplete;
- records the current-cache performance gate as passed while keeping the full
  verified-authoritative cutover pending;
- refuses `:verified-status-allowed?`;
- names complete-public-engine and rollout/performance work among the unmet
  release conditions;
- contains digests for all named source and report inputs and any generated
  artifacts that are present.
