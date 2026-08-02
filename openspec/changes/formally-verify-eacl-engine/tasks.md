## 1. Baseline and Assurance Boundary

- [x] 1.1 Inventory every production decision path in `eacl.engine.v8`, `eacl.cache`, `eacl.relay`, and `eacl.relationships.relay`, and map it to the public API operations that can consume its result.
- [x] 1.2 Write the initial assurance matrix mapping `can?`, lookup, count, relationship pagination, cursor continuation, and cache reuse to planned Dafny theorems, adapter assumptions, runtime targets, and CI commands.
- [x] 1.3 Write the trusted-computing-base document covering Dafny/Boogie/Z3, generated-code compilers, CLJ/CLJS runtimes, FFI boundaries, adapters, canonicalization, authentication, hashes, entropy, and configured limits.
- [x] 1.4 Add versioned characterization fixtures for current direct, alias, arrow-to-relation, arrow-to-permission, multi-path, recursive-SCC, lookup/count, cursor, cache, and typed-error behavior.
- [x] 1.5 Run the complete non-benchmark EACL suite through the current worktree's nREPL, record the baseline command/results, and resolve any pre-existing failure before using the baseline.
- [x] 1.6 Run the existing heavy pagination/load suite through nREPL, record latency, heap/work counters, generated token sizes, and CLJS bundle baselines for later cutover gates.
- [x] 1.7 Create the counterexample corpus layout and bug-ledger schema with fields for seed/trace, minimized fixture, impact, affected versions/backends, root cause, fix, and closing proof/test.

## 2. Reproducible Formal Toolchain and Integration Spike

- [x] 2.1 Select tested Dafny, Boogie/Z3, TLA+ tools, and Apalache releases; record upstream URLs, licenses, platform artifacts, and SHA-256 checksums in a committed toolchain lock.
- [x] 2.2 Implement a repository-local/containerized bootstrap that installs only locked formal tools into an ignored build cache and rejects a missing or mismatched checksum.
- [x] 2.3 Add deterministic entry points for Dafny format, verify, Java translation/build, JavaScript translation/build, TLA+ typecheck, Apalache bounded checks, and Apalache inductive-invariant checks.
- [x] 2.4 Create the `formal/dafny`, `formal/tla`, `formal/counterexamples`, and verification-manifest source layouts without committing generated binaries or local tool caches.
- [x] 2.5 Prove, compile, and invoke a minimal Dafny value/collection/error round trip from Clojure through generated Java.
- [x] 2.6 Prove, compile, and invoke the same minimal Dafny boundary from Node-based ClojureScript through generated JavaScript.
- [x] 2.7 Exercise the generated JavaScript module in every supported browser/bundler shape and record any target restrictions.
- [x] 2.8 Decide and document whether generated Java/JavaScript is published as reproducible build output or checked-in generated source, including consumer builds that do not install Dafny.
- [x] 2.9 Derive quantitative verification-time, shadow-sampling, Java/JavaScript artifact-size, latency, throughput, and heap thresholds from the recorded baselines.

## 3. Formal ReBAC Semantics

- [x] 3.1 Define Dafny datatypes for runtime-safe object types/IDs, relation IDs, permission nodes, normalized rules, relationships, queries, results, and typed errors.
- [x] 3.2 Define schema well-formedness for unique identities, valid source relations, type-correct arrow targets, accepted unions, and explicit malformed-schema results.
- [x] 3.3 Implement total normalization from EACL relation/permission definitions into direct-relation, self-permission, arrow-relation, and arrow-permission rules.
- [x] 3.4 Prove that normalization preserves every well-formed schema rule and cannot silently drop a well-formed rule.
- [x] 3.5 Define the immediate-consequence operator over typed grants and prove it is monotone.
- [x] 3.6 Define the finite least-fixed-point authorization relation and prove existence, uniqueness, and termination of executable iteration.
- [x] 3.7 Prove that a recursive component without a direct derivation contributes no grant merely by cycling.
- [x] 3.8 Implement the small executable semantic evaluator used as the independent ground truth for differential tests.
- [x] 3.9 Serialize formal inputs/outputs in a strict versioned wire format and reject unknown tags, duplicate fields, invalid ranges, and oversized values.
- [x] 3.10 Translate the existing authorization-oracle fixtures into formal fixtures and prove/execute exact agreement before adding optimized algorithms.

## 4. Temporal Models and Early Counterexample Search

- [x] 4.1 Port the causal snapshot graph, managed/unmanaged writes, clone/reset/restore/branch/force-head, and retention transitions from `eacl.causal-model` to typed TLA+.
- [x] 4.2 Model selected/computation/exact snapshots, causal ancestry, complete dependency scopes, structural proofs, and proof-lifting eligibility.
- [x] 4.3 Model cache lookup/store/validation races, provider failure, tampering, future/sibling entries, and validation-telemetry compare-and-set updates.
- [x] 4.4 Model lookup and relationship cursors across query reuse, direction changes, graph/schema writes, proof-equivalent writes, exact fallback, conflict modes, divergence, and expiry.
- [x] 4.5 Model recursive continuation/page-cache publication, retry, eviction, and missing-continuation replay transitions.
- [x] 4.6 State type invariants and safety properties for selected-snapshot equality, forward-only proof lifting, single-graph page walks, fail-closed errors, and cache/continuation race transparency.
- [x] 4.7 Add small exhaustive Apalache configurations covering multiple histories, relations, pages, writers, caches, and retention outcomes, plus larger scheduled bounds.
- [x] 4.8 Run all bounded searches; for every counterexample, minimize it, create the bug-ledger entry, and add a failing executable regression before changing production or formal logic.
- [x] 4.9 Strengthen the temporal invariants until Apalache passes separate initiation, consecution, and safety-implication checks.
- [x] 4.10 Re-express the stabilized cache/cursor transition invariants as Dafny state predicates and prove their preservation without a finite trace bound.

## 5. Adapter Proof Contract and Certification

- [x] 5.1 Define the Dafny abstract snapshot-oracle interface and pre/postconditions for immutable selection, identities, definitions, scans, direct match, permission-node enumeration, causal anchors, exact selection, and proofs.
- [x] 5.2 Document every abstract operation as an adapter obligation in `eacl.backend.v8` without claiming the verifier established backend internals.
- [x] 5.3 Add shared coherent generators for relation/permission definitions, typed relationships, bounds, and object identities used by adapter certification.
- [x] 5.4 Add contract checks that forward/reverse scans are finite, strictly ordered, unique, complete against a materialized fixture, and honor inclusive/exclusive bounds in both directions.
- [x] 5.5 Add contract checks that direct match equals scan membership and that external/internal object conversion is injective and round-trips for visible objects.
- [x] 5.6 Add contract checks that relation/permission definition reads and `all-permission-nodes` cover the seeded schema exactly.
- [x] 5.7 Add backend-specific adversarial checks for causal ancestry, reset/restore/branch behavior, exact selection, dependency-proof change coverage, and source/fingerprint identity.
- [x] 5.8 Run the Datomic certification suite through nREPL and resolve every violated assumption or mark the affected operation outside the assurance matrix.
- [x] 5.9 Run the DataScript CLJ and CLJS certification suites through nREPL-driven workflows and resolve every violated assumption or narrow the coverage claim.
- [x] 5.10 Run the Datahike certification suite through nREPL and resolve every violated assumption or narrow the coverage claim.
- [x] 5.11 Add optional runtime guards for adapter output shape, order, uniqueness, bound behavior, and exact integer representation, with typed fail-closed errors.
- [x] 5.12 Generate a machine-readable adapter certification report and make the verification manifest refuse verified status for unmet required obligations.

## 6. Verified Direct and Acyclic Engine

- [x] 6.1 Implement verified permission-path compilation for direct relations, self-permission aliases, arrows, multiple definitions, dependency closure, and deterministic path identity.
- [x] 6.2 Prove compiled paths denote exactly the normalized semantic rules reachable from the permission root.
- [x] 6.3 Implement direct `can?` evaluation over the abstract snapshot oracle and prove soundness and completeness against fixed-point membership.
- [x] 6.4 Implement acyclic forward traversal and prove its result set equals the subject projection of the authorization relation.
- [x] 6.5 Implement acyclic reverse traversal and prove its result set equals the resource projection of the authorization relation.
- [x] 6.6 Prove forward/reverse ordered merge and de-duplication preserve set equality and emit every internal ID at most once.
- [x] 6.7 Implement count and bounded count for acyclic results and prove cardinality/truncation laws.
- [x] 6.8 Implement resumable per-path frontiers and prove exclusive/inclusive boundary conversion, exhaustion, and direction laws.
- [x] 6.9 Compile the acyclic kernel to Java and JavaScript and connect it to a pure in-memory adapter fixture.
- [x] 6.10 Differentially compare formal semantics, generated kernels, `eacl.engine.v8`, and `eacl.engine.indexed` across coherent acyclic fixtures; minimize and retain every mismatch.

## 7. Verified Recursive Engine

- [x] 7.1 Implement reachable permission-node and strongly connected component analysis and prove it detects exactly the recursive components reachable from a root.
- [x] 7.2 Compile recursive rules and prove their denotation equals the normalized rules for every reachable node.
- [x] 7.3 Implement the forward worklist state, stream chunks, consumers, grants, queue, and de-duplication with explicit termination measures.
- [x] 7.4 Prove every forward derived grant is semantically justified and every semantic root grant is eventually emitted exactly once within limits.
- [x] 7.5 Implement the reverse goal/consumer worklist with explicit termination measures.
- [x] 7.6 Prove every reverse derived grant is semantically justified and every semantic root subject is eventually emitted exactly once within limits.
- [x] 7.7 Prove direct, acyclic, recursive-forward, and recursive-reverse operations refine the same formal authorization relation.
- [x] 7.8 Implement derived-grant, advanced-datom, and queued-work limits so crossing any limit returns one typed failure for the whole operation.
- [x] 7.9 Prove no limit path can convert partial work into a complete page, count, deny, or allow.
- [x] 7.10 Implement verified recursive traversal ordinals and retained continuation state, including deterministic replay when a continuation is unavailable.
- [x] 7.11 Prove resumed and replayed recursive traversals produce the same remaining deterministic sequence as uninterrupted traversal.
- [x] 7.12 Compile recursive operations to Java/JavaScript and differential-test cyclic, diamond, deep, wide, duplicate-path, empty-seed, and limit-exceeded fixtures against the semantics and legacy engine.

## 8. Verified Pagination and Cursor Decisions

- [x] 8.1 Define total normalization for list pagination arguments and prove invalid combinations, nil bounds, non-positive sizes, and oversize pages return typed errors.
- [x] 8.2 Define deterministic acyclic, recursive, and relationship result sequences independently of page size and prove sequence uniqueness.
- [x] 8.3 Implement forward and backward window functions and prove start/end boundaries, ordering, page flags, and empty-page laws.
- [x] 8.4 Prove that concatenating a complete valid forward walk reproduces the full sequence without omission or duplication.
- [x] 8.5 Prove every supported backward page is the exact preceding window of the same sequence.
- [x] 8.6 Define and validate cursor scope, operation, query, direction, result kind, engine/semantics version, execution identity, dependency-scope digest, proof digest, and graph anchor.
- [x] 8.7 Prove cross-operation/query/direction/result cursor reuse is rejected before the cursor influences traversal.
- [x] 8.8 Implement and prove current-proof continuation, at-least conflict, authenticated exact-snapshot fallback, divergence, stale, and expiry decisions.
- [x] 8.9 Implement relationship offset pagination in the verified kernel and prove its authenticated exact-snapshot proof and exact-fallback behavior against the relationship sequence without hashing the result set per page.
- [x] 8.10 Route decoded lookup and relationship cursor decisions through the generated kernel while retaining existing authenticated token formats at the boundary.
- [x] 8.11 Add cross-runtime cursor vectors and property tests for complete walks, random jumps, backward windows, tampering, scope confusion, proof changes, expiry, empty pages, and unavailable continuations.

## 9. Verified Cache Decisions

- [x] 9.1 Define semantic request keys, adapter/source identity, computation/selected graph points, dependency scopes, proof values, and cache entry/value validity in Dafny.
- [x] 9.2 Implement dependency-closure calculation for all relation and permission nodes that can affect each public authorization result.
- [x] 9.3 Prove the calculated dependency closure is complete with respect to the formal semantics.
- [x] 9.4 Implement exact-hit, causal-proof-lift, future/sibling rejection, proof mismatch, no-proof bypass, unauthenticated entry, and provider-failure decisions.
- [x] 9.5 Prove every accepted cache value is observationally equal to recomputation on the selected snapshot under the named proof/authentication axioms.
- [x] 9.6 Prove equal dependency content without causal ancestry cannot authorize reverse proof lifting.
- [x] 9.7 Model validation telemetry updates separately and prove compare-and-set races cannot change the value chosen for authorization.
- [x] 9.8 Route authenticated decoded cache-entry validation through the generated kernel and prevent Clojure/CLJS orchestration from returning an entry the kernel rejected.
- [x] 9.9 Add structural-proof test providers and dishonest/failing providers to exercise incomplete scopes, unrelated writes, revocations, schema changes, restores, branches, collisions-as-test-doubles, tampering, and concurrent replacement.
- [x] 9.10 Differentially compare cache-enabled results and provenance with cache-disabled formal evaluation across generated state traces and all three adapters.
- [x] 9.11 Derive the managed-cache frame theorem from the least-fixed-point ReBAC semantics: under the certified default identity contract, prove that equal schema generation, normalized internal query, and complete relevant relationship projections imply equal internal semantic results, and that rendering against the selected snapshot refines the public result, rather than assuming that implication in `CompleteProofContract`; custom codecs and future attribute/caveat semantics remain exact-only until they supply their own dependency frame.
- [x] 9.12 Replace history-aware completed-answer caching with one client-bound exact-current generation per backend; arbitrary `db`, `:at-exact-snapshot`, and historical cursor replay must bypass completed-answer lookup and publication.
- [x] 9.13 Implement and prove managed current-snapshot relation stamps for Datomic, Datahike, and DataScript using the transaction components of existing schema/relation mutation datoms, with Datahike as the primary performance target and exact-current caching as the unknown-writer fallback.

## 10. Runtime Boundary, Cryptographic Assumptions, and Cross-Target Parity

- [ ] 10.1 Implement strict CLJ-to-generated-Java conversions for schema IR, relationships, queries, adapter callbacks, cache/cursor inputs, results, and typed errors.
- [ ] 10.2 Implement the equivalent CLJS-to-generated-JavaScript conversions without duplicating authorization decisions.
- [x] 10.3 Reject unknown tags/fields, duplicate map fields, invalid object identities, unsafe integer ranges, oversized values, and invalid generated result variants at both boundaries.
- [x] 10.4 Add runtime contract checks around every unproved extern callback and tests proving a callback violation fails closed.
- [x] 10.5 Expand secure-format tests for domain/key/field confusion, tampering, canonical ordering, numeric extremes, size/depth limits, expiry boundaries, and constant-time tag comparison hooks.
- [x] 10.6 Generate and replay identical authenticated token/cache vectors in CLJ and CLJS.
- [x] 10.7 Differentially compare CLJ and CLJS generated kernels for portable schemas, graphs, page walks, cache states, and typed errors.
- [x] 10.8 Document each cryptographic/canonicalization axiom, the exact production code that discharges it operationally, and the tests that provide evidence without calling it a proof.

## 11. Generators, Shrinking, Bug Corpus, and Mutation Controls

- [x] 11.1 Add coherent schema generators for aliases, arrows, recursive SCCs, multiple subject types, duplicate semantic paths, disconnected nodes, and malformed variants.
- [x] 11.2 Add coherent finite graph generators for cycles, diamonds, empty relations, fan-in/fan-out, unknown IDs, extreme IDs, and irrelevant data.
- [x] 11.3 Add request/page generators covering direct checks, forward/reverse lookup, counts, all valid directions/sizes, invalid combinations, replay, and random cursor jumps.
- [x] 11.4 Add state-command generators covering graph/schema/unrelated writes, cache operations, cursor walks, clone/reset/restore/branch, exact retention/expiry, provider failures, and tampering.
- [x] 11.5 Add shrinkers that preserve schema/graph/request coherence while minimizing objects, rules, relationships, page sizes, and command traces.
- [x] 11.6 Implement one differential runner that compares semantic evaluator, verified kernel, legacy engines, cache modes, public clients, adapters, and runtimes with reproducible seeds.
- [x] 11.7 Store minimized counterexamples as versioned fixtures and make regression replay a fast deterministic CI target.
- [x] 11.8 Register wrong-arrow, premature-cycle-cut, missing-de-duplication, wrong-frontier, incomplete-dependency, numeric-ancestry, cursor-scope, cache-fail-open, and continuation-race mutants.
- [x] 11.9 Add a mutation-control target proving every registered mutant is killed by a named proof, model invariant, or differential test.
- [x] 11.10 Run sustained bug-finding campaigns until coverage and new-counterexample rates meet the recorded exit criteria; resolve or explicitly block rollout on every finding.

## 12. Production Integration and Shadow Migration

- [x] 12.1 Add an internal engine-selection option with legacy-authoritative, verified-shadow, and verified-authoritative modes while preserving public request/response shapes.
- [x] 12.2 Invoke the verified kernel in shadow mode without allowing it to alter legacy responses, cache state, cursors, or backend transactions.
- [ ] 12.3 Compare shadow results, ordering, page flags, counts, typed errors, cache provenance, graph identity, and limit behavior with redacted structured diagnostics.
- [ ] 12.4 Classify every shadow divergence against the formal semantics; add a minimized regression and bug-ledger entry before fixing either implementation.
- [x] 12.5 Fix every demonstrated false grant, stale reuse, mixed-snapshot page, omission, duplicate, or fail-open path and attach its closing Dafny lemma/model invariant/test.
- [ ] 12.6 Run representative direct, acyclic, recursive, cursor, and cache load benchmarks in legacy and verified modes and meet the recorded cutover thresholds.
- [ ] 12.7 Make verified-authoritative mode opt-in after all proof, adapter, cross-target, mutation, regression, and performance gates pass.
- [ ] 12.8 Run the full non-benchmark and heavy suites through nREPL with verified authority on Datomic, DataScript CLJ/CLJS, and Datahike.
- [ ] 12.9 Make verified authority the default while retaining the legacy rollback path for the documented compatibility window.
- [ ] 12.10 Remove the legacy decision path after the window closes, preserving characterization fixtures, counterexamples, and security fixes.

## 13. CI, Verification Manifest, and Documentation

- [x] 13.1 Add CI jobs for locked-tool bootstrap, Dafny verification, deterministic Java/JavaScript generation, and boundary builds.
- [x] 13.2 Add CI jobs for TLA+ type checking, bounded counterexample configurations, and inductive-invariant obligations.
- [x] 13.3 Add fast differential/property, counterexample-replay, mutation-control, adapter-certification, and CLJ/CLJS parity jobs.
- [x] 13.4 Add scheduled longer fuzz/model campaigns that retain failing seeds, minimized artifacts, coverage, and run metadata.
- [x] 13.5 Ensure every Clojure and ClojureScript correctness suite is launched through nREPL-compatible project workflows rather than cold `clojure` CLI test execution.
- [x] 13.6 Generate the verification manifest from machine-readable theorem results, source/tool/generated digests, adapter reports, runtime targets, corpus revision, assumptions, and performance gates.
- [x] 13.7 Fail CI and withhold verified status when a theorem is admitted, missing, times out, depends on an undocumented assumption, or lacks required adapter coverage.
- [x] 13.8 Document local setup, proof structure, theorem navigation, counterexample reproduction, adapter certification, shadow operations, rollback, and how to interpret the assurance claim.
- [x] 13.9 Add release notes for every behavior corrected by a counterexample, including affected versions/backends, security impact, and migration guidance.
- [ ] 13.10 Obtain a security/formal-methods review of the theorem statements, trusted boundary, axioms, FFI validation, temporal invariants, and verification manifest.

## 14. Final Verification

- [x] 14.1 Run all Dafny proofs from a clean locked tool cache and confirm no admitted lemmas, undocumented axioms, warnings, or timeouts.
- [x] 14.2 Run all TLA+/Apalache bounded and inductive checks and replay the complete minimized counterexample corpus.
- [x] 14.3 Run all adapter certification, CLJ/CLJS parity, fast/long differential, mutation-control, and public API suites through the documented workflows.
- [x] 14.4 Run heavy performance/heap benchmarks and confirm every recorded cutover threshold or document a blocking regression.
- [x] 14.5 Generate the final verification manifest and manually audit that every public “verified” claim is supported by a theorem plus satisfied adapter assumptions.
- [x] 14.6 Run `openspec validate formally-verify-eacl-engine --strict` and resolve every validation error before implementation is declared complete.
