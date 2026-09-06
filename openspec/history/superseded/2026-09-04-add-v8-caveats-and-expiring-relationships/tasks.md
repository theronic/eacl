## 1. Freeze implementation contracts and qualification fixtures

- [ ] 1.1 Record the seven-slot attribute definitions, format fingerprint, storage version, and Phase 1/2 capability epochs; verify a reviewed ABI manifest matches the design and all six delta specs.
- [ ] 1.2 Freeze public expiry/read-mode/clock/collector options and typed error names against existing API conventions; verify examples cover absent expiry, exact boundary, rejected valid-from, delayed already-expired creation, renewal, and stored versus expiry-active reads.
- [ ] 1.3 Define numeric latency, allocation/space, and collection-throughput budgets before candidate qualification; verify the benchmark plan distinguishes raw primitives from complete public operations and records 0%, 1%, 5%, and clustered-expiry workloads.
- [ ] 1.4 Establish isolated nREPL fixtures and assertion helpers for seven-slot storage on every backend; verify independent logical-identity and forward/reverse counts rather than assuming seed completion means correct contents.

## 2. Install the seven-slot storage boundary — Phase 1

- [ ] 2.1 Add the Datomic Pro schema and storage-format admission checks; verify through an in-memory REPL that nil qualifiers, finite expiry, and all seven declared component types transact and incompatible populated layouts fail startup.
- [ ] 2.2 Add equivalent Datahike and Datalevin schemas and format checks; verify their native round trips and rejection of old/mixed representations through nREPL.
- [ ] 2.3 Add DataScript's full seven-element representation and validation; verify JVM and CLJS round trips retain explicit nils and reject shortened/malformed values.
- [ ] 2.4 Require nil Caveat slots in Phase 1 and fingerprint cache/cursor/engine semantics; verify unsupported Caveats and old four/eight/differently-defined-seven-slot artifacts cannot influence authorization.
- [ ] 2.5 Implement capability epoch validation and document the coordinated cutover or enforceable deployment fence; verify a long-lived Phase 1 client retaining a minimize-latency pin cannot serve or write current Phase 2 data.

## 3. Update the shared codec and ordered access

- [ ] 3.1 Implement construction, decoding, inversion, exact retractions, owner-qualified identity, and expiry normalization; verify round trips for permanent/expiring tuples and distinct owners with identical forward values.
- [ ] 3.2 Replace forward/reverse adjacency and logical-identity probes with guarded seven-slot access; verify no v7 fallback or second candidate stream appears and expiry is available in either direction without a schedule lookup.
- [ ] 3.3 Implement full-arity lower/upper bounds and whole-identity pagination boundaries for every backend; verify ascending/descending inclusion and exclusion with nil, negative, minimum, and maximum timestamps and adjacent prefixes.
- [ ] 3.4 Extend Relationship filtering, relation-in-use reads, and stored read projections to qualifiers; verify raw stored reads retain expired assertions and normalized status while expiry-active reads omit them.
- [ ] 3.5 Charge skipped candidates, lookahead, validation reads, and payload work to execution limits; verify a long expired prefix causes a typed limit failure rather than a false exhaustion marker, denial, or exact count.

## 4. Preserve atomic logical identity and writer coherence

- [ ] 4.1 Guard create/touch/delete against current owner-qualified identity state at commit for Datomic; verify racing creates and stale competing touches through in-memory nREPL transactions leave one canonical pair or a typed conflict.
- [ ] 4.2 Establish equivalent commit-time guards for Datahike, Datalevin, and DataScript writer topologies; verify unsupported remote/other topologies reject writes instead of relying on stale plan-time checks.
- [ ] 4.3 Implement full qualifier replacement and identity-only deletion; verify create conflicts before collection, touch renews retained expired data, different deadlines batch together, and renewal has no committed absence gap.
- [ ] 4.4 Resolve aliases before batch deduplication and reject conflicting updates of one identity; verify same-owner duplicates collapse, different owners remain distinct, and wildcard/subject-relation forms preserve identity.
- [ ] 4.5 Stamp every affected Relation once per actual mutation transaction and preserve the existing CAS protocol; verify unchanged touches avoid unnecessary state churn while renewal, deletion, and multi-Relationship batches update the right Relations.
- [ ] 4.6 Update public transaction helpers, speculative execution, object deletion, and bounded repair; verify committed/speculative parity, complete peer cleanup, atomic repair-limit failure, and no deletion of ambiguously owned context.

## 5. Add trusted expiry-time evaluation

- [ ] 5.1 Capture one trusted exact time per top-level client operation and per batch, independently of a reused database pin; verify deterministic clocks crossing expiry change fresh requests but not an in-flight operation.
- [ ] 5.2 Freeze time on explicit EACL snapshots and preserve it through with/with-schema; verify historical/speculative evaluation and database consistency tokens do not silently change or substitute the temporal mode.
- [ ] 5.3 Reject invalid/regressing configured-clock values and precision-losing expiry inputs; verify portable numeric extrema and non-integer cases on CLJ/CLJS without sleeps.
- [ ] 5.4 Filter expiry at every forward/reverse leaf before context evaluation and permission algebra; verify direct grants, intersections, exclusion, arrows, recursion, subject relations, wildcard paths, lookups, counts, and explanations at the exact deadline.
- [ ] 5.5 Enforce consumer-owned future creation with explicit unsupported-start errors and no implicit deadline extension; verify delayed writes remain stored/inactive when their absolute expiry has passed.

## 6. Certify caches and continuations under expiry

- [ ] 6.1 Add evaluation-time start and exclusive deadline to authenticated reusable value formats; verify every accepted hit checks both time and ordinary dependency proofs even if provider eviction is delayed.
- [ ] 6.2 Implement conservative proof derivation for completed grants, denials, counts, and pages with exact-time/no-publication fallback; verify viewer-minus-banned denial at 90 cannot survive banned expiry at 100 on an unchanged database basis.
- [ ] 6.3 Give denotations, projections, recursive state, and frontiers their own certificates; verify a permanent union answer cannot widen the certificate of its expiring child.
- [ ] 6.4 Preserve complete static Relation closure and source/branch/causal rules independently of temporal witnesses; verify unrelated writes can retain reuse, relevant writes invalidate it, and forward certificates never lift to an earlier frozen time.
- [ ] 6.5 Implement authenticated pinned/live cursor modes and explicit deadline restart; verify pinned evaluation at 90 survives wall time 110 while a live cursor at its deadline returns a restart error and no page.
- [ ] 6.6 Certify skipped negative evidence and lookahead for both pagination directions; verify expiry of a ban on an identity before the cursor forces a restart rather than silently omitting the newly authorized identity.
- [ ] 6.7 Extend the formal/refinement model for expiry-only graph removal, exclusion gains, artifact-local certificates, and renewal; verify the repository's applicable formal checks and nREPL counterexamples agree before Phase 1 qualification.

## 7. Implement optional bounded collection and integrity

- [ ] 7.1 Add explicitly invoked forward-scan collection with trusted cutoff, retention, scan/delete budgets, and progress scoped to its scan basis; verify no expiration index is required and permanent data is never selected.
- [ ] 7.2 Revalidate exact pair/context at commit and reuse normal guarded deletion/Relation stamping; verify renewal/collection races in both commit orders cannot delete a replacement and failed batches make no partial changes.
- [ ] 7.3 Define no-op and concurrent-insertion progress behavior; verify scans without deletions do not bump Relations and completion never claims to cover concurrent rows behind the scan cursor.
- [ ] 7.4 Update offline integrity and unknown-writer proofs for full seven-slot values and context ownership; verify detected faults abort affected authorization while qualified managed traversal avoids redundant peer reads solely to distrust atomicity.
- [ ] 7.5 Verify cache/cursor behavior after collection and retained-history reads; confirm affected Relation invalidation, unrelated Relation reuse, CREATE conflict removal, and absence of any claim that ordinary retraction excises Datomic history.

## 8. Qualify and document Phase 1

- [ ] 8.1 Run all backend/JVM expiry, mutation, ordering, proof, and public API suites through nREPL; verify no unexpected failures and retain reproducible outputs with versions and fixture counts.
- [ ] 8.2 Run DataScript CLJS conformance after JVM work that needs the session's agents; verify equivalent tuple bounds, expiry, snapshot, cache, cursor, and writer outcomes under Node.
- [ ] 8.3 Benchmark actual seven-slot storage in an isolated local :dev Datomic database with at least one million independently enumerated identities; verify the predeclared matrix covers proportion versus local concentration, endpoint degree, both directions, cold/warm behavior, and concurrent renewal/collection.
- [ ] 8.4 Measure full public checks, pages, counts, proof costs, and scan-based collector throughput separately from primitive reads; verify the numerical budgets from task 1.3 and disclose unsupported/unmeasured scenarios rather than substituting the archived eight-slot timings.
- [ ] 8.5 Publish rebuild/reseed, rollback, consumer scheduling, trusted-clock, snapshot/live-cursor, retention, and collection guidance; verify examples use only valid-until and exercise a fresh rebuilt database before Phase 1 release.

## 9. Freeze the Caveat profile and schema — Phase 2

- [ ] 9.1 Pin an exact `com.exoscale/cel-parser` version or immutable source build, concrete SpiceDB reference release/commit, and bounded supported syntax/type/coercion/function profile; verify the dependency/adapter/options/extension matrix identifies every supported and rejected construct, including documented upstream differences, without claiming full CEL compatibility.
- [ ] 9.2 Define the canonical typed representation, EACL name/type validation over the selected parsed form, and injective CLJ/CLJS value encoding within the portable envelope; verify byte-identical typed fixtures for admitted types and explicit rejection otherwise, without treating a parsed ANTLR tree as a checked CEL AST.
- [ ] 9.3 Add Caveat declaration parsing and bounded CEL-body syntax/name/type/profile validation before schema commit, with canonical storage, reads, comparison, and replacement; verify round trips and typed rejection of undefined names, non-Boolean expressions, unsupported constructs, and ambient mutable-input functions while client runtime program construction remains lazy.
- [ ] 9.4 Validate allowed/required Caveats per exact subject/wildcard/subject-relation branch; verify optional bare-plus-Caveated branches, required-only branches, and rejection of multiple attachments.
- [ ] 9.5 Serialize Caveat/schema in-use guards with Relationship writes; verify definition removal or incompatible parameter/branch changes fail for active, expired, and speculatively inert retained references.

- [ ] 9.6 Add the pinned cel-parser dependency behind the JVM adapter boundary and qualify its JDK/ANTLR/transitive dependency compatibility; verify through nREPL that `make-program` and `eval-for` load and evaluate supported definitions, characterize returned versus thrown errors, and keep JVM/ANTLR classes out of CLJS builds.
- [ ] 9.7 Specify and qualify the CLJS execution path against the same portable representation/profile before Phase 2 activation; verify a cross-runtime prototype/corpus agrees with the selected JVM cel-parser integration without implicit remote evaluation or removing CLJS support.
- [ ] 9.8 Freeze expression-entity attributes, canonical payload encoding, digest identity, and named-definition references; verify equal source/parameter/profile payloads share identity across Caveat names, different types/profiles differ, and exact-text identity makes no claim of logical formula equivalence.
- [ ] 9.9 Implement guarded immutable expression interning and named-reference replacement on every backend, including speculative views; verify concurrent equal admissions converge, unequal-payload digest collisions reject atomically, edits update schema generation without mutating shared expressions, and current/retained views resolve their own content.
- [ ] 9.10 Preserve shared-expression lifecycle independently of Relationship-owned context; verify deletion/collection retains named definitions and expression entities, in-use schema guards remain enforced, and initially unused expression entities are retained for reuse.

## 10. Add bound context and deterministic evaluation

- [ ] 10.1 Store one immutable canonical payload only for non-empty bound context; verify empty context allocates nothing and both tuple halves share the same singly-owned context ref.
- [ ] 10.2 Implement context replacement/retraction with touch, delete, object cleanup, and collection; verify expiry-only touch can retain unchanged context while changed context atomically replaces both refs without orphaning shared definitions or deleting shared expressions.
- [ ] 10.3 Enforce structural size limits and bound parameter admission before allocation; verify unknown bound keys, wrong types, oversized/deep values, and malformed payloads fail without partial writes.
- [ ] 10.4 Capture canonical request context and apply shallow bound-over-request precedence; verify a bound map replaces an entire request map, shadowed values are type-checked only after precedence, unknown request keys stay outside the expression environment, and input is bounded before hashing.
- [ ] 10.5 Implement the JVM cel-parser adapter with explicit EACL partial evaluation, typed value conversion, and result/error mapping; verify in nREPL that missing declared parameters yield reproducible conditional residuals, decisive Boolean branches remain definite, returned error objects cannot become truthy grants, and reported errors abort an affected exclusion rather than becoming false or conditional operands.
- [ ] 10.6 Implement canonical residual composition for every operator and supported recursion; verify repeated predicates, different bound values under one Caveat name, conditional cycles, negation/exclusion, and termination/resource-limit behavior.

- [ ] 10.7 Implement enforceable parse/tree, collection, regex, input, and aggregate work limits in the qualified integration, including cold compilation and extensions; verify through nREPL that actual underlying work terminates with the specified typed failure rather than relying on an unverified timeout or nonexistent library budget API.
- [ ] 10.8 Build the bounded client-local program cache with complete expression/evaluator identity and selected-view eid resolution; verify first active traversal compiles lazily, repeated identical expressions across names reuse a resident program and qualified handle without source rereads/rehashing/comparison per edge, ordinary schema reads and expired/unvisited paths do not compile, and source/branch eid collisions cannot alias unequal programs.
- [ ] 10.9 Coalesce concurrent program-cache misses and bound resident/in-flight work; verify one successful build serves a same-key cohort, different contexts remain isolated under concurrent `eval-for`, failures release in-flight state, eviction allows equivalent rebuild, and cancellation does not corrupt other callers.
- [ ] 10.10 Qualify reusable macro-body plans for every admitted collection macro, including nested bodies, or exclude unqualified constructs at schema admission; verify parser instrumentation detects the inspected upstream per-element reparsing and that the selected integration performs none during warm evaluation while preserving bindings and work limits.

## 11. Expose Caveat results and complete reuse proofs

- [ ] 11.1 Add three-state permission checks with missing-field diagnostics and Boolean can? compatibility; verify true only for has-permission, false for successful no/conditional states, and error propagation without fallback checks.
- [ ] 11.2 Add definite/conditional lookup and count semantics with identity deduplication; verify compatibility numeric counts exclude conditional identities and structured counts classify each identity once after permission algebra.
- [ ] 11.3 Extend lookup keys, dependency certificates, and authenticated values separately for complete request context, named definitions and expression content, bound payloads, cel-parser build and adapter/options/extensions identity, permissionship, residuals, and expiry certificates; verify configuration-only changes invalidate incompatible state, a program hit never substitutes for answer proof, and no answer-dependent cache lookup key is required.
- [ ] 11.4 Authenticate Caveat scope and retained partial state in cursors; verify changed request context/profile forces mismatch/restart and boundary deduplication never promotes a conditional result to a definite grant.
- [ ] 11.5 Extend source qualification, unknown-writer content proofs, and formal/refinement obligations to Caveats; verify context/expression payload or definition-ref mutation without a Relation/schema stamp invalidates affected reuse and eid resolution, missing expression state is not hidden by a cached program, and detected failed subtracting evidence cannot become an ordinary denial or grant.

## 12. Qualify Phase 2 and complete release artifacts

- [ ] 12.1 Run the pinned SpiceDB differential corpus against the selected cel-parser JVM integration and qualified CLJS path; verify definite, conditional, missing-field, type conversion, bounded recursion, and error behavior for every admitted profile construct, including known upstream divergences and EACL extensions.
- [ ] 12.2 Benchmark permanent, expiring, empty-context Caveated, non-empty-context Caveated, and mixed workloads using budgets fixed before qualification; verify separate reports for cold schema/expression reads and program construction, warm evaluation, parser invocation counts including macro bodies, partial/residual work, context/proof reads, cache hit/miss/eviction and memory behavior, repeated versus distinct expressions, concurrent cold requests, and evaluator limits.
- [ ] 12.3 Exercise fenced Phase 2 activation, retained historical Phase 1 snapshots, and old portable cache/cursor rejection; verify an obsolete pinned current-serving client cannot bypass the activation boundary.
- [ ] 12.4 Publish the supported Caveat profile, request/write/read/check/lookup/count examples, rollout constraints, performance evidence, and known limits; verify examples against the implementation through nREPL.
- [ ] 12.5 Run the repository's applicable formal/conformance and public-source closure gates after implementation, then strict OpenSpec validation; verify all required implementation tasks are complete before syncing/archiving this replacement and leave the deprecated predecessor untouched as history.
