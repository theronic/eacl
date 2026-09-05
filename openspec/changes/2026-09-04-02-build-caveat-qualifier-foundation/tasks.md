## 1. Qualify the dependency and target profile without production edits

- [x] 1.1 Pin the candidate `com.exoscale/cel-parser` 0.1.8 revision and resolved ANTLR dependencies in an exploration basis; verify the recorded dependency graph and licences without adding it to production modules yet.
- [x] 1.2 Inventory cel-parser types, operators, returned-error behavior, divergences, macros, timestamp handling, and resource characteristics against the target SpiceDB corpus; verify the accepted/rejected profile is explicit and reproducible.
- [x] 1.3 Define canonical EACL Caveat values, parameter types, source/profile identity, size/depth/entry/work bounds, and typed outcomes from the qualification evidence; verify hostile and boundary fixtures have deterministic expected results.
- [x] 1.4 Build independent CEL-spec and SpiceDB fixtures for complete, partial, short-circuit, type-error, and overload-error cases; verify the fixtures do not derive their expected outcomes from the future production adapter.

## 2. Complete the formal foundation before production code

- [x] 2.1 Define the finite qualifier lifecycle/publication model with `nil`/single immutable owner, prepared-but-unattached qualifiers, atomic pair attachment, replacement, deletion, dangling refs, and first-four Relationship identity; verify orphan preparation has no authorization effect and all mutation controls are green.
- [x] 2.2 Define typed named Caveat definitions, Relation allowance, canonical bound/request context merge, and bound-over-request precedence in the model; verify exhaustive finite context cases.
- [x] 2.3 Define true, false, conditional residual, missing-field, and evaluator-error outcomes including short-circuit behavior; verify the model distinguishes missing data from faults.
- [x] 2.4 Define the supported finite CEL-profile semantics selected in section 1, rather than ANTLR/library internals; verify determinism, total outcome classification, and bounded progress.
- [x] 2.5 Register proof modules, mutation controls, locked resource bounds, and assurance-matrix entries; verify `bin/formal fast` and every affected formal gate are green before any Phase 2 production source edit.

## 3. Add qualifier and Caveat persistence

- [x] 3.1 Add qualifier format, Caveat ref, canonical context payload, and `valid-until-ms` attributes to bundled backend schemas, plus only the backend-specific creation-version field needed where assertion `t` is unavailable; verify sparse physical shape and backend module isolation.
- [x] 3.2 Implement canonical qualifier normalization and validation, including empty-to-`nil`, context-without-Caveat rejection, exact time bounds, and unknown-field rejection; verify property tests against the pre-green formal model.
- [x] 3.3 Exercise each bundled backend's real transaction API with newly allocated qualifier refs inside slot five, including DataScript/CLJS and Datalevin stores; verify and record whether inline allocation resolves to a concrete eid or must be rejected.
- [x] 3.4 Implement an explicit qualified-writer capability with certified inline-allocation and prepared-reference strategies; verify the latter precreates only an inert qualifier and the final transaction atomically publishes both halves, Relation stamp, and caller-composed datoms.
- [x] 3.5 Implement singly owned immutable qualifier create/replace/delete planners behind internal staged APIs; verify every semantic update uses a fresh qid, never embeds an unresolved lookup ref/tempid, and leaves no one-sided serving state.
- [x] 3.6 Extend integrity, orphan cleanup, and proof-input tooling to report missing, shared, mutable, malformed, asymmetrically referenced, or unattached qualifiers; verify a missing target never aliases `nil` and each corruption fixture fails closed.
- [x] 3.7 Add Caveat definition schema entities, canonical parameter payloads, source/profile fields, and schema-generation integration; verify identical Relationships share one named definition without expression-source duplication.

## 4. Extend schema parsing and admission

- [x] 4.1 Parse top-level typed Caveat declarations and Relation `with caveat` branches while preserving existing grammar/error positions; verify valid, duplicate, unresolved, and malformed fixtures.
- [x] 4.2 Implement static parameter resolution, Boolean-root checking, selected-profile validation, and schema limits; verify invalid schemas fail before durable replacement.
- [x] 4.3 Integrate Caveat additions, updates, and removals with `write-schema!`, schema CAS, historical reads, orphan/reference checks, and speculative schema planning; verify concurrent replacements and retained-reference failures.
- [x] 4.4 Keep serving activation disabled for Caveated Relation branches and non-`nil` public Relationship writes; verify Phase 2 cannot silently store or serve a qualified edge through current public APIs.

## 5. Implement the bounded evaluator subsystem

- [x] 5.1 Add `com.exoscale/cel-parser` 0.1.8 to the JVM implementation module only and implement a narrow adapter for program construction, complete-context evaluation, value conversion, returned-error detection, and profile fingerprinting; verify isolated core, adapter, and CLJS builds contain no dependency leak.
- [x] 5.2 Build the canonical bounded Caveat plan/residual representation needed for static checking and partial evaluation; verify encode/decode and equality are portable and library-object free.
- [x] 5.3 Implement EACL partial evaluation for incomplete context, preserving short-circuit definite results and canonical conditional residuals; verify exhaustive finite cases match the pre-green model.
- [x] 5.4 Implement a bounded process-local program cache keyed by canonical definition/profile identity with concurrent miss coalescing; verify cache hits change only work counters, never outcomes.
- [x] 5.5 Define the evaluator capability/fingerprint protocol and JVM default registration; verify CLJS and clients without a matching evaluator fail before Caveated serving activation.

## 6. Certify implementation against the models

- [x] 6.1 Add generated refinement bridges for qualifier normalization/lifecycle/publication, context merge, profile checking, and partial outcomes; verify exhaustive finite and randomized production-vs-model differentials are green.
- [x] 6.2 Add mutation controls that kill bound-context precedence, error-value detection, short-circuit residuals, qualifier immutability, atomic pair publication, non-`nil`-missing-to-fault behavior, and schema generation; verify each mutant is detected by a mapped gate.
- [x] 6.3 Run the SpiceDB/CEL compatibility corpus through the EACL profile and record every intentional exclusion/divergence; verify no unsupported case is advertised as compatible.
- [x] 6.4 Audit production hot paths to confirm Phase 2 adds zero qualifier reads, clock reads, Caveat evaluation, model calls, or shadow decisions to ordinary authorization; verify instrumentation and source review.

## 7. Document and release the foundation

- [x] 7.1 Document Caveat syntax, supported types/operators, bound context, profile limits, JVM/CLJS capability boundary, and returned error categories; verify examples parse and model-evaluate as shown.
- [x] 7.2 Document the sparse qualifier schema, immutability/single ownership, inline-versus-prepared publication capability, inert `valid-until`, and Phase 3 activation dependency; verify operators are warned not to seed non-`nil` qids for serving Phase 2 clients.
- [x] 7.3 Update dependency/POM/license notices and isolated module build tests for cel-parser; verify published core and CLJS artifacts remain free of the JVM dependency.
- [x] 7.4 Run CI-equivalent nREPL tests, CLJS suite, all bundled-backend qualifier-publication conformance tests, formal fast/full applicable gates, source-closure, dependency audit, and strict OpenSpec validation; verify all are green before this foundation is marked ready for Phase 3.
