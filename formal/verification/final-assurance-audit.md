# EACL v8 formal-assurance audit

Date: 2026-08-07

Supersession note (2026-08-08): the cursor-rebase/restart conclusions and
measurements below describe an intermediate v8 candidate, not the final
contract. Final production behavior is equal-proof continuation on current,
verified exact-snapshot fallback on immutable history-capable backends, or a
typed stale/conflict error. DataScript is current-basis-only. No current model,
production branch, or active performance gate authorizes cursor rebase or
restart. The current release claim is governed by the generated-boundary,
pagination-kernel, execution-contract, and assurance-matrix ledgers.

## Executive decision

EACL v8 has one production authorization authority per target: the generated
indexed Java kernel on the JVM and the portable CLJC kernel on ClojureScript.
The latter is differentially certified against the generated JavaScript
oracle, the independent fixed-point oracle, cross-runtime vectors, randomized
fixtures, counterexamples, and mutations. Shadow routing and the runtime
rollback selector are absent from production source paths. The older indexed
engine and six-function SPI remain only under `formal/smoke/` as independent
test oracles. Persisted relationship storage migration is the only retained
upgrade mechanism.

The defensible assurance claim is:

> EACL v8's backend-neutral authorization kernel is conditionally formally
> verified against its named least-fixed-point ReBAC semantics, and the
> generated kernel is the production decision authority on the JVM. The
> portable ClojureScript authority is differentially certified against it and
> is advisory pending a server re-check. The claim depends on explicitly
> documented adapter, FFI, runtime, cryptographic, identity, and resource-limit
> assumptions.

The release is not entitled to the unqualified claim “the entire EACL system
is formally verified.” The Clojure/ClojureScript orchestration, storage
engines, generated-code compilers, runtime collections, cryptographic
implementations, and adapters are not mechanically proved. They are closed by
strict boundaries, generated authority, source inventory, differential tests,
adapter certification, mutation controls, and runtime resource gates.

The manifest must remain `:conditionally-verified` until an independent
security/formal-methods reviewer audits the theorem statements, trusted
boundary, axioms, FFI conversions, temporal invariants, and release manifest.
That external review remains an unfinished release-assurance obligation. The
Dafny dead-model cleanup and triggered wave-batching engineering are complete;
they do not narrow the independent-review requirement.

## What changed in the v8 cutover

- Removed the handwritten production authorization engine and the legacy
  six-function backend SPI.
- Removed shadow execution and every runtime engine-selection/rollback mode.
  Backend clients reject `:engine-selection`; production installs one internal
  target-specific internal `:decision-kernel`.
- Moved the former indexed engine, six-function SPI, and lazy merge
  implementation to formal smoke sources. The new production CLJC kernel is a
  focused implementation of the current decision and indexed-traversal
  protocols; it does not restore the removed engine-selection surface.
- Removed pre-release and deprecated API contracts that complicated source and
  proof boundaries: `:limit`/`:cursor` list pagination, the
  `:entity->object-id` alias, the `:page-token-keys` alias, migration rollback
  options, a parser wrapper, a Relay compatibility arity, and unused Datomic
  helpers.
- Kept the forward v6-relationship-entity to tuple-storage migration. It is
  idempotent, verifies both tuple halves before retracting v6 entities, and has
  no reverse library migration.
- Centralized the persisted forward and reverse tuple attribute identities in
  `eacl.relationships.storage`. Datomic, Datahike, DataScript, schema code, and
  migration code now reuse those values.
- Replaced obsolete v7-versus-v8 runtime performance gates with authoritative
  absolute, deterministic-work, scaling, allocation, and retained-live-heap
  gates. Historical before/after measurements remain evidence, not executable
  engine modes.
- Retained authenticated storage/cursor formats only where they are current v8
  contracts. The `eacl.v7.relationship` keyword namespace is a storage ABI,
  not an executable v7 engine.

The tuple consolidation removes redundant Vars and the possibility of
cross-backend keyword drift. It is not a large heap optimization: Clojure
keywords are interned, so the repeated literals did not allocate independent
keyword objects. The material memory reduction comes from removing production
engine code and its reachable implementation structures.

## What is mechanically proved

The Dafny development proves, for finite well-formed inputs and the named
abstract snapshot-oracle contract:

1. schema/rule normalization preserves all well-formed definitions;
2. the immediate-consequence operator is monotone;
3. the finite authorization relation exists as a unique least fixed point;
4. recursion without a direct derivation cannot grant merely by cycling;
5. direct, acyclic, recursive-forward, and recursive-reverse results refine
   the same authorization relation;
6. generated forward/reverse worklists are sound, complete, duplicate-free,
   terminating within configured limits, and fail closed on limit exhaustion;
7. pending scans are exposed as bounded, request-ordered waves; responses fold
   in that same order; a fuel cut publishes every nonempty pending wave from
   current state without rollback or request loss; and unsplit independent
   streams use `2 × ceil(scans / batch-size) + 1` crossings;
8. typed SCC routing and the proof-carrying routing certificate classify
   recursive roots and their transitive ancestors;
9. ordered merge, pagination windows, keyset page decisions, and
   continuation/replay laws preserve complete result sequences without
   omission or duplication;
10. cursor decisions reject cross-query, cross-operation, cross-result, scope,
   tampering, expiry, and incompatible-history use before the cursor can
   influence authorization;
11. exact/current cache admission, complete managed dependency frames,
    subproblem projection/denotation reuse, lifecycle isolation, and
    validation telemetry cannot turn a rejected or stale candidate into an
    authorization result;
12. public consistency normalization and finite selection decisions implement
    exactly `minimize-latency`, `fully-consistent`,
    `at-least-as-fresh`, and `at-exact-snapshot`;
13. strict boundary datatypes reject unknown variants, malformed fields,
    unsafe integers, and oversized values; and
14. named logical resource counters and cost recurrences establish the
    reviewed Big-O properties. Host time, allocation, retained heap, backend
    seek cost, and RSS are measured separately rather than inferred from
    logical counters.

TLA+/Apalache models additionally search cache, cursor, exact-selection,
retention, branch/history, abstract local-read/proof failure, tampering,
publication, and continuation races. They do not model a shipped external
cache provider, because v8 rejects one. Bounded model checking is bug-finding evidence. The
unbounded safety claims come from the corresponding Dafny transition
predicates and preservation lemmas.

## How closely the implementation matches the model

The match is strong at the decision boundary, but it is not a whole-language
refinement proof.

What closes the gap:

- JVM production calls generated Dafny Java for authorization state, routing
  certification, pagination, cursor, consistency, cache, scan validation, and
  rendering decisions. ClojureScript production calls the portable CLJC
  implementation through the same protocols. The host cannot select another
  engine.
- Authority state is opaque across adapter round trips. Host orchestration
  supplies validated ordered response waves and cannot replace traversal
  transitions or discard, synthesize, or reorder a fuel-cut wave.
- Strict converters validate every generated input and output family.
- Source-closure and backend-dispatch ledgers inventory all named public roots,
  reachable definitions, and literal adapter operations so an unclassified
  host branch cannot silently disappear from the assurance map.
- The materialized semantic evaluator, retained former engine, generated Java
  and JavaScript, portable CLJC kernel, CLJ and CLJS public APIs, three
  adapters, generated campaigns, and minimized counterexamples are compared on
  coherent fixtures.
- Adapter certification checks finite order, uniqueness, bound behavior,
  direct-match equivalence, schema coverage, identity round trips, snapshot
  facts, and proof-change coverage.
- Registered mutants must be killed by a named proof, model counterexample, or
  executable regression.

What remains trusted:

- Clojure and ClojureScript language/runtime semantics;
- handwritten conversion code and callback wiring;
- Dafny's generated Java/JavaScript compilers and patched target collections;
- the handwritten portable CLJC decision and traversal implementation;
- Datomic, Datahike, and DataScript implementation truthfulness outside tested
  fixtures;
- cryptographic primitive implementations, key management, clocks, entropy,
  canonicalization, and collision-resistance assumptions;
- custom object-ID codecs unless consumers supply the required deterministic
  identity/dependency contract;
- configured traversal, cache, token, and continuation limits; and
- application policy intent.

Therefore, saying “the ClojureScript implementation was proved equivalent to
Dafny” would be false. The accurate statement is that JVM semantic decisions
are generated from Dafny, while the portable CLJS authority and remaining host
and adapter boundary are strictly inventoried, validated, differentially
exercised, and still part of the trusted computing base. Independent
source-refinement review remains
valuable precisely at this boundary.

## Bugs and regressions found

The retained corpus contains 63 minimized findings, all marked fixed. Each
entry under `formal/counterexamples/EACL-FORMAL-NNN/` records its witness,
impact, affected backends/version, root cause, fix, and closing evidence. The
complete corpus is the exact bug ledger; the table below calls out the
highest-value findings.

| Finding | Class | What failed | Fix and value |
| --- | --- | --- | --- |
| 001–013 | cache/cursor/temporal correctness | Early proof/cache and cursor designs admitted incomplete dependency, ancestry, scope, publication, or continuation assumptions. | Minimized traces drove current-cache, authenticated cursor, temporal-safety, and fail-closed designs. |
| 014–018 | superseded cache coordination | Recursive single-flight could join itself, cross cache lifecycles, and couple callers to another request's latency or failure. | v8 deletes flight state and cache semaphores: misses compute independently, lifecycle replacement detaches generations atomically, and bounded CAS publication either retains a compatible winner or discards the candidate. |
| 019–020 | result correctness | Descending merge dropped the maximum EID; generic merge dropped a legal first `nil` key. | Explicit presence bits plus exhaustive CLJ/CLJS source-control fixtures and mutants. |
| 021 | cross-backend configuration | Datomic rejected/failed to forward shared subproblem-cache configuration. | One validated shared cache configuration path. |
| 022–027 | error/limit/boundary correctness | Generated errors lost fields, stale-cursor shapes diverged, materialized resource counters were substituted for query-local limits, and signed EIDs crossed a natural-number model. | Exact public typed errors, dimensionally correct counters, generated indexed resource authority, and nonnegative exact-integer adapter guards. |
| 028–034 | assurance-harness correctness | Artifact sizes were stale, corpus schema values drifted, typed routing lost resource type, CI lacked Babashka, error comparison discarded fields, graph identity compared locator capabilities, and CLJS test execution poisoned shared executors. | Fail-closed build measurements, strict corpus schema, typed routing certificate, pinned CI dependencies, complete portable error comparison, correct graph identity, and persistent nREPL-safe CLJS execution. |
| 035 | performance | Reflective Java interop allocated and resolved methods on every generated traversal round trip. | Concrete type hints and a complete reflection audit. Recursive page p95 allocation fell from 3,677,688 to 343,576 bytes; continuation fell from 4,283,960 to 706,496 bytes on the recorded fixture. |
| 040–044 | source-model fidelity | Formal merge/leapfrog/arrow/path-fold models omitted source control state, exact seek traces, empty-arrow fast exit, path materialization, or callback order. | Source-shaped Dafny models, exact CLJ/CLJS traces, exhaustive small domains, adapter composition, and production empty-arrow fast exit. |
| 045 | target-runtime complexity | Dafny's abstract immutable Java/JS collections and pairwise ordering validator created quadratic target costs despite correct logical counters. | Persistent target collections, sequence views, and adjacent-order validation. A 15,000-result reverse page improved from about 5.7 seconds to 2.78 ms on the recorded JVM fixture; JS scaling became approximately linear over 1,024–16,384 results. |
| 046 | cache usefulness | Completed-answer keys and root names prevented cross-query reuse of equal subgraphs. | Rule-body denotation identity plus proved equal-body/equal-fixed-point theorems. Across 80 roots sharing a depth-48 chain, layered p50 was 0.181959 ms versus 0.684250 ms and performed zero backend operations. |
| 047–050 | continuation/performance harness (historical candidate) | Cursor recovery reused stale frontiers; backward replay was not page-size bounded; routing/rebase gates measured JIT/compiler history; warm `can?` used an unstable single batch. | The final contract removed rebase/restart; retained fixes are bounded opaque continuation state, exact-snapshot fallback or stale rejection, isolated JVM gates for current operations, valid scaling domains, and stable multi-batch warmup. |
| 051 | hot-path performance | Public `can?` classified a permission root twice. | Reused classification; recorded paired p50 improved 508.292 µs to 483.625 µs and later heavy-suite warm aggregate was 431.8545 µs. |
| 052–054 | public consistency correctness | `false` consistency could default instead of reject; token maps accepted unknown fields; connectionless DataScript falsely advertised `fully-consistent`. | Raw input forwarding, exact descriptor shape, and truthful capability advertisement. |
| 055 | asymptotic authorization performance | Point `can?` searched forward from a broad subject, making one-resource checks grow with unrelated subject fanout. | Resource-anchored reverse generated traversal. The recorded local median fell from about 453 µs to 150 µs; deterministic work remains one command/one value at 16 and 1,040 unrelated resources. |
| 056 | integration correctness | Direct DataScript/Datahike relationship paging could lose the generated kernel between page normalization and physical scan, a bug previously masked by fallback routing. | Resolve one decision kernel once per page and pass it through both phases; the direct relationship query matrix now covers the path. |
| 057 | assurance-harness correctness | The CLJ-to-generated-Java page-window bridge still called a removed six-field datatype constructor after deprecated pagination inputs were removed. | Align the bridge with the four-field v8 datatype and keep deprecated-input rejection at the public host boundary; all 47 generated-runtime bridge tests now load and pass. |
| 058 | assurance-workflow availability | Ordinary parity CI eagerly loaded a former engine namespace after it moved to the formal-only classpath; the broader local classpath masked the failure. | Remove eager formal-oracle loads, resolve closing regressions lazily, and test the entrypoint source against all retained former-engine namespaces. |
| 059 | assurance-harness correctness | A clean generated-JavaScript rebuild exposed obsolete six-field page-request test arguments and a CLJS recursive-page expectation that disagreed with the equivalent JVM fixture. Cached local generated artifacts had masked both. | Align the direct JS bridge with the current four-field datatype, keep removed API rejection at the host boundary, make JVM/JS consume one shared recursive-page vector, and retain a clean-build source regression. |
| 060–063 | routing, continuation, and execution-contract fidelity | Production either routed all enumeration recursively, dropped DataScript/Datahike private continuation state, treated inactive recursive syntax as active recursion, or ignored explicit completion on acyclic roots. The last defect also exposed a completed-artifact ordering/cache-key mismatch. | Route from the generated certificate plus snapshot-local cycle guards, wire bounded private continuation through shared core, override every defined root to fixed-point evaluation only for explicit completion, preserve the certified public order/cursor ABI, bind that order in version-5 artifact keys, and kill the regressions in CLJ/CLJS plus model/source mutation controls. |

These findings also expose defects in the verification program itself. Findings
024–025, 028–034, 040–045, 048–050, and 056–059 are especially important: they
showed that a theorem, comparator, counter, fixture, or benchmark could be
correct in isolation while failing to model the actual production boundary or
target cost. The response was not to weaken the claim; it was to narrow or
repair the model, add a minimized witness, and create a fail-closed gate.

## Final local verification run

The final pre-audit run on 2026-08-08 produced the following evidence:

| Gate | Result |
| --- | --- |
| Dafny | 30 modules, 8,785 proof efforts, 0 errors; no admitted lemma or undocumented axiom |
| TLA+/Apalache | all five models type checked; bounded, inductive, mutation-control, and longer scheduled configurations reported `NoError` |
| Generated Java runtime bridges | 51 tests, 16,176 assertions, 0 failures/errors |
| Generated-authority-injected JVM public/backend suite | 523 tests, 39,462 assertions, 0 failures/errors; recursive operations execute generated indexed authority while acyclic operations execute generated decisions plus documented host source specializations |
| Portable-authority-injected DataScript CLJS suite | 172 tests, 4,682 assertions, 0 failures/errors; 79 client constructions injected and every required portable authority operation observed |
| Portable CLJS formal/oracle suite | 46 tests, 9,983 assertions, 0 failures/errors |
| Portable CLJS full DataScript/core suite | 176 tests, 9,693 assertions, 0 failures/errors under `:advanced` |
| Portable CLJS performance/payload | 5,335 ns/result three-process median at 16,384 (15,000 ceiling); 15,335 raw / 3,409 compressed incremental bytes |
| Heavy generated-only backend/performance suite | 17 tests, 4,058 assertions, 0 failures/errors |
| Minimized counterexample replay | 69 tests / 18,516 assertions on the full formal-smoke classpath, 0 failures/errors; any recorded test var missing from an available namespace is a hard failure |
| Mutation control | 2 tests, 217 assertions, 0 failures/errors; all 95 registered mutants killed |
| Retained-live-heap gate | five complete 4,000-result recursive walks retained 5,335,984–5,344,744 bytes after full GC, below the 8 MiB ceiling, with identical result digests |
| Generated artifact size | browser bundle 594,693 bytes; Java classes/runtime 1,917,082 bytes; Java source 2,151,864 bytes; JavaScript/runtime 957,820 bytes; every ceiling passed |

The JVM suite observed generated decision calls for all required operations on
all three adapters (366 injected Datomic clients, 71 Datahike, 127 DataScript).
The latest portable CLJS suite observed 79 injected DataScript clients. These
counters prevent a green suite that accidentally bypasses the selected
authority.

## Performance conclusions

Formal proof did not make EACL fast automatically. It did make several
performance mistakes conspicuous:

- Expensive proofs often signaled an expensive executable representation.
  The clearest example is finding 045: abstract collection operations were
  logically reasonable but translated into copying and quadratic validation.
- Logical operations, backend calls, allocation, retained heap, and wall time
  are different currencies. EACL now gates them independently.
- Point queries must anchor at the known endpoint. Query shape, not only graph
  size, determines the correct index direction.
- Schema work should be compiled once per schema generation. Routing
  certificates, SCCs, dependency closures, and denotation identities are
  generation-scoped; request paths consume the compiled result.
- Cache usefulness comes from reusable subproblems and denotations, not merely
  completed answers. Equal rule bodies can share fixed-point results across
  different permission names without weakening semantic identity.
- Cache-disabled calls branch before cache-key, proof, token, local lookup, and
  envelope work. “Disabled” therefore means no hidden proof tax.
- Generated target artifacts require their own complexity audit. A Dafny
  theorem over sequences or sets does not establish the complexity of the
  generated runtime representation.

The most credible current performance story is deterministic: page work is
bounded by page/continuation structure, point checks are resource-local,
schema compilation is generation-scoped, managed proofs are dependency-local,
and shared subgraphs can produce zero-backend-operation hits. Host latency
numbers are supporting evidence from named fixtures, not universal SLAs.

Lore's historical resource analyzer contributed two useful techniques:
separate resource dimensions and adversarial lifecycle schedules. Its pinned
revision is outdated, did not accept current EACL source into its strict core,
and is not assurance evidence. Current semantic/logical-resource claims were
reimplemented in Dafny; runtime allocation, retained heap, and latency remain
measured host properties.

## What was not tested or proved

- Exhaustive production graphs or arbitrary customer schemas.
- Arbitrary third-party backend implementations.
- Linearizability or internal correctness of Datomic, Datahike, or DataScript.
- Cryptographic security of the chosen primitives or operational key custody.
- Peak RSS, GC pause maxima, CPU time, backend billing, network latency, or
  worst-case wall time.
- Browser engines other than the recorded Node/V8 ClojureScript target.
- Correctness of custom codecs without the declared deterministic contract.
- Policy intent, data hygiene outside EACL APIs, or ghost tuples created by
  direct endpoint retraction.
- A mechanized Clojure/ClojureScript language refinement proof.
- Independent formal/security review.

## Marketing wording

Recommended:

> EACL v8 uses a Dafny-generated recursive authorization kernel and generated
> decision components on the JVM, with documented host source specializations
> for certified acyclic roots; ClojureScript uses a differentially certified
> portable recursive kernel with the same source-specialized acyclic path. Its
> backend-neutral ReBAC semantics, recursive traversal, pagination, cursor,
> consistency, and cache decision laws are mechanically verified under
> documented assumptions; browser answers remain advisory and require a
> server re-check. Sixty-two minimized correctness, assurance-harness, and
> performance defects were found and fixed during the verification program.
> Datomic, Datahike, and DataScript are covered by shared adapter and
> public-contract suites. Mechanized host-control, cache-transition, portable
> CLJS-authority, and adapter-conversion source refinements remain open;
> independent audit is also pending.

Do not say:

- “All EACL Clojure code is formally verified.”
- “Datomic/Datahike/DataScript are formally verified.”
- “No authorization bug is possible.”
- “The formal proof guarantees latency, heap, or backend cost.”
- “Independent audit passed” until a named external reviewer records it.

## Independent audit handoff

The external reviewer should:

1. check every public assurance-matrix row against the exact theorem
   preconditions and production call path;
2. challenge snapshot-oracle, adapter, identity, cryptographic, and limit
   assumptions;
3. review strict Java/JavaScript converters, generated collection patches,
   and the portable CLJC authority;
4. replay proofs, TLA+/Apalache checks, counterexamples, mutants, adapter
   certification, CLJ/CLJS suites, and target-authority performance gates;
5. inspect source-closure exclusions and test-only/production classpaths;
6. attempt adversarial backend responses, malformed storage, history
   divergence, cache races, and cursor confusion; and
7. record reviewed commit, toolchain digests, findings, and any narrowed claim
   in the verification manifest.

Until the named mechanized source refinements and that review record exist,
release automation must continue to withhold the unqualified `:verified`
status.
