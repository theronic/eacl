## Context

EACL v8 has one backend-neutral authorization engine and three storage adapters. The shared engine compiles schema definitions into direct-relation, same-resource permission, arrow-to-relation, and arrow-to-permission paths; detects recursive permission components; evaluates forward and reverse traversals; de-duplicates and paginates results; resumes recursive continuations; and decides whether authenticated cache/cursor state is reusable on a selected immutable snapshot. The adapters resolve identities, select snapshots, scan ordered indexes, and produce causal/dependency proofs.

The repository already contains two useful but non-formal reference artifacts:

- `eacl.authorization-oracle` computes a least fixed point over an in-memory relationship set.
- `eacl.causal-model` models snapshots, cache hits, and cursor walks independently of production cache/cursor code.

They are a strong seed for a formal semantics, but tests against another Clojure implementation are not a mathematical proof and finite fixtures cannot cover all graphs or state traces.

The research result is to use a verification-guided replacement, not attempt to verify Clojure source or JVM bytecode directly:

- AWS reports that replacing a legacy authorization engine with a Dafny implementation compiled to Java was more effective than proving the existing Java implementation, and that differential/shadow testing connected the specification to production behavior ([Formally verified cloud-scale authorization](https://www.amazon.science/publications/formally-verified-cloud-scale-authorization)).
- Dafny provides contracts, termination measures, inductive datatypes, sets/sequences, automated proofs, Java and JavaScript compilation, and target-language foreign interfaces ([Dafny reference](https://dafny.org/dafny/DafnyRef/DafnyRef), [Java integration](https://dafny.org/latest/DafnyRef/integration-java/IntegrationJava)).
- Cedar's verification-guided development found four proof-time validator defects and 21 additional differential/property-testing defects, which is direct evidence that proof plus coherent input generation finds authorization bugs missed by either technique alone ([Cedar VGD](https://www.amazon.science/publications/how-we-built-cedar-a-verification-guided-approach)).
- Zanzibar defines recursive userset evaluation as graph reachability and requires one snapshot for all reads in an authorization check; it also shows why causal freshness is part of authorization correctness rather than a backend performance detail ([Zanzibar](https://www.usenix.org/conference/atc19/presentation/pang)).
- Apalache can search all bounded TLA+ traces and separately check the initiation, consecution, and safety obligations of an inductive invariant, making it suitable for hostile cache/cursor histories while Dafny remains the final unbounded proof vehicle ([Apalache](https://apalache-mc.org/docs/apalache/running.html)).

The required assurance claim is deliberately narrower than whole-system verification. EACL will prove the authorization kernel assuming each adapter satisfies a precisely stated snapshot/scan/proof contract. Correctness of Clojure, ClojureScript, Dafny's compilers, JavaScript/Java runtimes, storage engines, cryptographic primitives, and hash collision resistance remains in the trusted computing base.

## Goals / Non-Goals

**Goals:**

- Give EACL schema and relationship data an executable mathematical semantics based on a least fixed point.
- Mechanically prove soundness, completeness, termination, forward/reverse equivalence, de-duplication, count equivalence, and fail-closed resource-limit behavior.
- Mechanically prove cursor window and cache-reuse theorems, including snapshot and causal-history conditions.
- Run the verified implementation in the production decision path on both Clojure/JVM and supported ClojureScript targets.
- Find current implementation and design defects through proof failures, model-checker counterexamples, differential random testing, and mutation controls.
- Preserve EACL's public API and backend independence while making all assumptions and residual risks auditable.
- Make every proof, model check, generator seed, and minimized counterexample reproducible in CI.

**Non-Goals:**

- Prove Clojure/ClojureScript, Dafny, Boogie, Z3, compilers, JVM/JavaScript runtimes, or storage-engine implementations correct.
- Prove cryptographic algorithms, entropy sources, or collision resistance; the formal model treats valid authentication and proof equality according to explicit axioms.
- Prove a customer's EACL schema expresses the customer's intended business policy.
- Prove latency, heap use, or index complexity; those remain benchmarked non-functional properties.
- Add intersection, exclusion, caveats, subject-relation filters, or other policy-language features.
- Make invalid, non-deterministic, or incomplete third-party adapters appear verified.

## Decisions

### 1. State the assurance theorem and trusted boundary before porting code

For a finite valid schema `S`, immutable snapshot relationship set `R`, and request `q`, define `Auth(S,R)` as the least fixed point of a monotone immediate-consequence operator over typed `(subject, permission, resource)` grants.

The verified kernel SHALL establish:

1. `can?(q)` is true if and only if `q ∈ Auth(S,R)`.
2. Forward lookup enumerates exactly the resources granted to its subject; reverse lookup enumerates exactly the subjects granted on its resource.
3. Each lookup sequence contains every semantic result once, follows its specified deterministic order, and its count operation equals the sequence cardinality unless a caller-supplied count limit reports truncation.
4. A traversal safety limit produces a typed failure before returning an incomplete result as complete; it never converts exhausted proof work into `allow`.
5. Page windows are subsequences of one semantic result sequence, and a valid complete page walk concatenates to that sequence without duplication or omission.
6. A cache hit is returned only when its semantic key, execution identity, causal relation, complete dependency scope, and selected-snapshot proofs establish equality with recomputation.

The proof assumes adapter operations satisfy the contract in Decision 4 and authenticated/proof digests satisfy the axioms in Decision 6. The claim will be published as a matrix mapping each theorem to its Dafny lemma, runtime entry point, adapter assumptions, and CI command. “Formally verified” must never be used for an operation absent from that matrix.

Alternative considered: define correctness only as “no false allow.” Rejected because lookup/count/cursor omissions can silently deny legitimate access or corrupt batch authorization even when every returned item is authorized. Soundness is the minimum security theorem; the implementation target is exact refinement for successful finite evaluations.

### 2. Use Dafny as both abstract specification and executable verified kernel

Create a backend-neutral `formal/dafny` project with separate layers:

- `Semantics`: typed objects, relation tuples, permission rules, immediate consequence, and least-fixed-point definitions.
- `Traversal`: schema compilation, SCC routing, direct/recursive forward and reverse algorithms, visited/frontier state, and termination measures.
- `Pagination`: deterministic result sequences, cursor bounds, forward/backward windows, and continuation validation.
- `Cache`: semantic cache keys, dependency closure, causal eligibility, proof comparison, and hit/miss decisions.
- `Kernel`: narrow executable operations and serializable result/error datatypes.
- `Proofs`: refinement, termination, duality, pagination, cache, and composition lemmas.

Executable code is compiled from the verified Dafny source to Java for Clojure and to JavaScript for ClojureScript. A small handwritten boundary converts EACL values and adapter callbacks to generated types, then converts results back. Boundary code validates every discriminant, integer range, collection bound, and error variant. Public client orchestration may perform I/O and token encoding, but it may not make a grant, page-window, stale-cursor, or cache-hit decision without the kernel.

The build records the Dafny version, solver version, compiler targets, source digest, and generated-artifact digest. Generated target code is reproducible build output, not an independently edited source of truth.

Alternatives considered:

- A Lean model plus differential tests follows Cedar's current architecture and would be an excellent formal oracle, but it leaves the production Clojure algorithm outside the mechanical proof. Dafny's Java/JavaScript targets allow the verified algorithm itself to execute in EACL.
- Direct verification of Clojure/JVM bytecode has no mature, maintainable toolchain for EACL's persistent collections, lazy sequences, dynamic vars, protocols, and CLJC targets.
- A bounded Alloy model is useful for small counterexamples but failure to find one is not an unbounded proof. TLA+/Apalache and Dafny cover the needed stateful discovery and theorem obligations with less duplicated modeling.

### 3. Model recursive permissions as a monotone finite fixed point

The formal schema normalizes every permission definition into a union of these rule forms:

- direct typed relation;
- same-resource permission;
- arrow whose far side is a typed relation;
- arrow whose far side is a permission.

Each rule only adds grants, so the consequence operator is monotone. On a finite object/permission domain, iteration from the empty grant set terminates at the unique least fixed point. Recursive permission SCCs are therefore semantics, not cycles to cut.

The optimized worklist traversal is proved to preserve three invariants:

- every queued or derived grant is justified by a semantic rule;
- every semantic grant reachable from seeded direct relations is eventually processed unless a typed safety limit aborts the whole call;
- de-duplication changes work cardinality, never the derived set.

The proof treats acyclic lazy-merge traversal and recursive worklist traversal as two refinements of the same relation. Forward/reverse engines are proved against projections of that relation rather than only against each other, preventing paired mirror bugs.

Schema compilation must reject or explicitly represent malformed definitions. Missing definitions, ambiguous types, or unsupported constructs cannot silently erase a proof branch and then be certified as a complete result.

Alternative considered: encode operational recursion directly as depth-limited graph search. Rejected because depth is not part of EACL's permission semantics and a finite depth can create false denies; the least fixed point exactly captures recursive ReBAC reachability.

### 4. Turn the backend SPI into explicit proof assumptions with executable contracts

The formal kernel receives an abstract snapshot oracle with these obligations:

- all calls during one operation observe one immutable snapshot;
- external/internal identity conversion is injective within the declared identity contract and round-trips for visible objects;
- relation and permission definitions are complete for the requested schema scope;
- forward and reverse scans return the same finite relationship set projected in opposite directions;
- scan output is strictly ordered, unique, and honors exclusive/inclusive bounds in both directions;
- direct-match agrees with scan membership;
- `all-permission-nodes` is complete;
- schema/relation proof values cover the declared dependency scope;
- `contains-anchor?` represents causal ancestry, not numeric transaction ordering;
- exact selection either returns the requested compatible immutable graph or fails;
- the adapter fingerprint and source scope change whenever an assumption-affecting implementation identity changes.

Shared generative contract tests instantiate these laws for Datomic, DataScript, and Datahike. Test failures mean the composed system is not covered by the proof even if the kernel verifies. Third-party adapters can run the same certification suite and receive a machine-readable coverage report; EACL does not label an untested adapter verified.

Runtime checks cover shapes, ordering, bounds, and round trips where practical. Global completeness and causal/proof claims cannot be established by local checks, so they remain named adapter obligations backed by backend-specific tests and documentation.

Alternative considered: model each database inside the proof. Rejected by scope and by the user's explicit instruction; it would couple EACL's theorem to backend internals and still leave runtime/configuration assumptions.

### 5. Use TLA+/Apalache for adversarial temporal exploration, then prove the surviving invariants in Dafny

Port and extend `eacl.causal-model` into typed TLA+ modules for:

- managed and out-of-band relationship/schema writes;
- selected, computation, exact, restored, reset, branched, and expired snapshots;
- exact/managed current-cache put/read/expiry races, relation stamps, provider failures, tampering, lifecycle ABA, and late publication;
- cursor mint/resume, query reuse, direction changes, current advancement, exact fallback, retention expiry, and consistency-mode conflicts;
- recursive continuation and page-cache publication/eviction races.

Apalache first searches bounded traces with small graph/history scopes and emits concrete counterexamples. After the safety predicates stabilize, inductive invariants are checked using separate initiation, consecution, and implication runs. The invariant set includes:

- cached answers equal evaluation on the selected snapshot;
- exact-current candidates are returned only for the identical immutable selected generation;
- managed-current candidates have equal complete relevant projections under the forward stamped-writer contract;
- every successful continued page belongs to the cursor's authenticated exact graph;
- a page/continuation cache race changes only performance, never page contents or cursor validity;
- any unavailable assumption results in miss, rejection, conflict, or expiry, never a grant.

The corresponding transition predicates and invariants are re-expressed and proved in the Dafny cache/pagination modules. TLA+ is the fast counterexample laboratory; the release claim does not rest on a finite model-checking bound.

### 6. Treat cryptography and canonical proofs as explicit axioms, and test their implementation boundary

The formal model uses uninterpreted authenticated encoding and proof functions with these assumptions:

- a successfully authenticated token/entry decodes to the value that was encoded for the same domain and key;
- values not produced for that domain/key do not authenticate;
- canonicalization is deterministic and injective over accepted EACL values;
- equal complete dependency proofs imply equal answer-affecting schema/relationship inputs for the declared scope.

These are not presented as proved facts. Existing secure-format tests are expanded with cross-runtime vectors, field/domain/key confusion, tampering, numeric limits, duplicate/canonical ordering cases, expiry boundaries, and parser size/depth limits. Cache/cursor proofs consume only successfully decoded, fully validated values.

Digest equality remains a computational-security assumption. Where a backend can return structural content proofs in tests, the differential harness compares structural and digest modes so scope omissions are discoverable without relying on collisions.

Alternative considered: formally verify HMAC, SHA-256, canonical EDN, and platform crypto. Rejected as outside the requested engine scope and unnecessary if the residual assumption is explicit.

### 7. Build a coherent differential and stateful counterexample pipeline

Add generators that create internally coherent:

- typed schemas with aliases, multi-definition relations, arrows, recursive SCCs, duplicate semantic paths, disconnected components, and invalid variants;
- finite object graphs with cycles, diamonds, empty relations, fan-in/fan-out, unknown IDs, and extreme internal identifiers;
- pagination requests with every direction, size, boundary, replay, and stale-snapshot transition;
- cache/cursor histories with graph/schema/unrelated writes, restores, branches, proof collisions injected as test doubles, provider exceptions, tampering, and retention expiry.

For each generated case, compare:

1. the direct mathematical/Dafny semantics;
2. the verified executable kernel;
3. the current Clojure engine in cache-disabled and cache-enabled modes;
4. public API behavior on every applicable backend; and
5. CLJ and CLJS outputs for portable cases.

Comparisons include results, order, page flags, concatenated walks, counts, typed errors, cache provenance, and selected graph identity. Stateful cases are shrunk to the smallest schema, graph, and command trace. Each mismatch creates a checked-in regression fixture and a bug-ledger entry recording impact, root cause, affected versions/backends, fix, and proof/test that closes it.

Before trusting the harness, seed representative mutants: wrong arrow direction, premature cycle cut, incomplete dependency closure, inclusive/exclusive frontier error, missing de-duplication, numeric-only ancestry, cursor scope omission, cache fail-open, and continuation publication race. CI must demonstrate that the relevant proof, model check, or differential target kills every registered mutant.

Alternative considered: millions of uniform random EDN values. Rejected because Cedar's experience shows that uncorrelated policy/data/request generation overexercises rejection paths and misses core evaluation logic.

### 8. Make proof and conformance status release artifacts

CI gains separate, pinned jobs for:

- Dafny format/typecheck/verification with no admitted lemmas, unresolved assumptions beyond the documented boundary, verification timeouts, or warnings;
- deterministic Java and JavaScript compilation plus boundary smoke tests;
- TLA+ type checking, bounded counterexample searches, and inductive-invariant checks;
- fast deterministic differential/property tests on each change;
- longer scheduled fuzz/model campaigns with saved seeds and coverage reports;
- Clojure tests invoked only through nREPL, per repository rules;
- DataScript ClojureScript conformance and cross-target vectors;
- mutation controls and counterexample-regression replay;
- performance/heap benchmarks kept distinct from correctness gates.

A generated `verification-manifest` records theorem status, source/tool digests, adapter contract results, tested targets, known assumptions, counterexample corpus revision, and benchmark comparison. Release notes link this manifest; no handwritten badge substitutes for it.

Tool installation is repository-local or containerized and locked. The bootstrap verifies downloaded checksums and never depends on an unpinned globally installed verifier.

### 9. Migrate by shadowing before authority

The first integration preserves the current Clojure engine as the response source and invokes the verified kernel in shadow mode on sampled or test traffic. A disagreement is a release blocker and is classified against the independent semantics rather than assuming either implementation is correct.

After all theorem, adapter, cross-target, counterexample, and performance gates pass:

1. make the verified kernel authoritative behind an opt-in client option;
2. run the full backend suites and representative load tests in both modes;
3. make the verified kernel the default while retaining the old path for one rollback window;
4. remove the old decision path only after the compatibility window and counterexample corpus show no unexplained divergence.

Unsound legacy behavior is never preserved merely to make differential tests green. Its minimized witness, security impact, correction, and any migration note are documented.

## Risks / Trade-offs

- [The verified kernel is a substantial second implementation before cutover] → Start from the small existing oracle and causal model, keep one normalized schema IR, port in theorem-sized increments, and delete the legacy decision path after the rollback window.
- [Dafny verification may become solver-fragile or slow] → Pin tool/solver versions, isolate lemmas, use explicit induction and bounded resource settings, record verification times, and reject proofs that depend on unstable global timeouts.
- [Generated JavaScript may not fit every current browser/bundler target] → Make cross-target compilation and a browser/Node integration spike the first implementation milestone; keep the verified semantic oracle plus DRT bridge until the generated target meets packaging and performance gates.
- [Extern adapter contracts can be violated by a backend] → Publish assumptions, add runtime shape/order checks, run generative adapter certification, and make coverage claims adapter-specific.
- [Differential agreement can preserve a shared misunderstanding] → Use the declarative least-fixed-point semantics as a third independent source of truth and prove both optimized directions against it.
- [Model checking can create false confidence outside its bound] → Use bounded checks for discovery, require inductive-invariant checks, and carry final safety theorems in Dafny.
- [Proof axioms hide a dependency-scope or crypto bug] → Minimize axioms, list every use, use structural proofs in tests, inject dishonest proof providers, and fuzz the real codec boundary.
- [The verified implementation may regress latency or memory] → Preserve ordered index callbacks and streaming/worklist state, benchmark before authority, shadow in production-like workloads, and keep performance as a cutover gate without weakening correctness.
- [A discovered bug requires externally visible behavior changes] → Fail closed, retain the minimized counterexample, document affected versions and migration, and version only the smallest public contract that must change.

## Migration Plan

1. Pin the formal toolchain and prove/build a Java and JavaScript hello-world boundary before committing to the integration shape.
2. Freeze current public behavior in characterization fixtures and enumerate the trusted boundary and assurance matrix.
3. Implement the declarative semantics and prove finite fixed-point foundations.
4. Model-check cache/cursor histories early; convert every counterexample into a regression before optimizing or porting behavior.
5. Implement and prove direct traversal, recursive traversal, lookup/count, pagination, and cache modules in dependency order.
6. Certify adapter contracts and complete CLJ/CLJS differential testing.
7. Run shadow mode, fix every unexplained divergence, and meet correctness/performance gates.
8. Roll out opt-in authority, then default authority, then remove the legacy decision path.

Rollback before legacy removal switches the client option back to the current engine. The formal artifacts, regressions, and any security fixes remain; rollback never re-enables a demonstrated false-grant path.

## Open Questions

No product decision blocks implementation. The initial integration spike will record, in the design evidence:

- whether generated Java/JavaScript is packaged as reproducible build output or checked in for consumer builds that cannot run Dafny; and
- quantitative shadow sampling, verification-time, bundle-size, and performance thresholds based on the existing benchmark baselines.
