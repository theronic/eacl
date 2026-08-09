# EACL v8 formal-assurance audit

Date: 2026-08-06

## Executive decision

EACL v8 has one production authorization engine: the generated indexed
Java/JavaScript kernel. The handwritten v7/v8 traversal engines, shadow
routing, and runtime rollback selector are absent from production source
paths. The former indexed engine and six-function SPI remain only under
`formal/smoke/` as independent test oracles; redundant handwritten v8
authorization code was deleted. Persisted relationship storage migration is
the only retained upgrade mechanism.

The defensible assurance claim is:

> EACL v8's backend-neutral authorization kernel is conditionally formally
> verified against its named least-fixed-point ReBAC semantics, and the
> generated kernel is the production decision authority on Datomic, Datahike,
> and DataScript. The claim depends on explicitly documented adapter, FFI,
> runtime, cryptographic, identity, and resource-limit assumptions.

The release is not entitled to the unqualified claim “the entire EACL system
is formally verified.” The Clojure/ClojureScript orchestration, storage
engines, generated-code compilers, runtime collections, cryptographic
implementations, and adapters are not mechanically proved. They are closed by
strict boundaries, generated authority, source inventory, differential tests,
adapter certification, mutation controls, and runtime resource gates.

The manifest must remain `:conditionally-verified` until an independent
security/formal-methods reviewer audits the theorem statements, trusted
boundary, axioms, FFI conversions, temporal invariants, and release manifest.
That external review is the sole unfinished OpenSpec task.

## What changed in the v8 cutover

- Removed the handwritten production authorization engine and the legacy
  six-function backend SPI.
- Removed shadow execution and every runtime engine-selection/rollback mode.
  Backend clients reject `:engine-selection`; production installs one internal
  generated `:decision-kernel`.
- Moved the former indexed engine, six-function SPI, and lazy merge
  implementation to formal smoke sources, so normal library consumers neither
  package nor load them. Deleted the redundant handwritten v8 authorization
  branches rather than retaining a second implementation.
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
- Replaced obsolete v7-versus-v8 runtime performance gates with generated-only
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
7. typed SCC routing and the proof-carrying routing certificate classify
   recursive roots and their transitive ancestors;
8. ordered merge, pagination windows, cursor bounds, and continuation/replay
   laws preserve complete result sequences without omission or duplication;
9. cursor decisions reject cross-query, cross-operation, cross-result, scope,
   tampering, expiry, and incompatible-history use before the cursor can
   influence authorization;
10. exact/current cache admission, complete managed dependency frames,
    subproblem projection/denotation reuse, lifecycle isolation, and
    validation telemetry cannot turn a rejected or stale candidate into an
    authorization result;
11. public consistency normalization and finite selection decisions implement
    exactly `minimize-latency`, `fully-consistent`,
    `at-least-as-fresh`, and `at-exact-snapshot`;
12. strict boundary datatypes reject unknown variants, malformed fields,
    unsafe integers, and oversized values; and
13. named logical resource counters and cost recurrences establish the
    reviewed Big-O properties. Host time, allocation, retained heap, backend
    seek cost, and RSS are measured separately rather than inferred from
    logical counters.

TLA+/Apalache models additionally search cache, cursor, exact-selection,
retention, branch/history, provider-failure, tampering, publication, and
continuation races. Bounded model checking is bug-finding evidence. The
unbounded safety claims come from the corresponding Dafny transition
predicates and preservation lemmas.

## How closely the implementation matches the model

The match is strong at the decision boundary, but it is not a whole-language
refinement proof.

What closes the gap:

- Production calls generated Dafny Java/JavaScript for authorization state,
  routing certification, pagination, cursor, consistency, cache, scan
  validation, and rendering decisions. The host cannot select the former
  handwritten engine.
- Generated state is opaque across adapter round trips. Clojure supplies
  validated scan responses; it does not recreate traversal transitions.
- Strict converters validate every generated input and output family.
- Source-closure and backend-dispatch ledgers inventory all named public roots,
  reachable definitions, and literal adapter operations so an unclassified
  host branch cannot silently disappear from the assurance map.
- The materialized semantic evaluator, retained former engines, generated
  Java and JavaScript, CLJ and CLJS public APIs, three adapters, generated
  campaigns, and minimized counterexamples are compared on coherent fixtures.
- Adapter certification checks finite order, uniqueness, bound behavior,
  direct-match equivalence, schema coverage, identity round trips, snapshot
  facts, and proof-change coverage.
- Registered mutants must be killed by a named proof, model counterexample, or
  executable regression.

What remains trusted:

- Clojure and ClojureScript language/runtime semantics;
- handwritten conversion code and callback wiring;
- Dafny's generated Java/JavaScript compilers and patched target collections;
- Datomic, Datahike, and DataScript implementation truthfulness outside tested
  fixtures;
- cryptographic primitive implementations, key management, clocks, entropy,
  canonicalization, and collision-resistance assumptions;
- custom object-ID codecs unless consumers supply the required deterministic
  identity/dependency contract;
- configured traversal, cache, token, and continuation limits; and
- application policy intent.

Therefore, saying “the Clojure implementation was proved equivalent to Dafny”
would be false. The accurate statement is that production semantic decisions
are generated from Dafny, while the remaining host and adapter boundary is
strictly inventoried, validated, differentially exercised, and still part of
the trusted computing base. Independent source-refinement review remains
valuable precisely at this boundary.

## Bugs and regressions found

The retained corpus contains 59 minimized findings, all marked fixed. Each
entry under `formal/counterexamples/EACL-FORMAL-NNN/` records its witness,
impact, affected backends/version, root cause, fix, and closing evidence. The
complete corpus is the exact bug ledger; the table below calls out the
highest-value findings.

| Finding | Class | What failed | Fix and value |
| --- | --- | --- | --- |
| 001–013 | cache/cursor/temporal correctness | Early proof/cache and cursor designs admitted incomplete dependency, ancestry, scope, publication, or continuation assumptions. | Minimized traces drove current-cache, authenticated cursor, temporal-safety, and fail-closed designs. |
| 014–018 | concurrency/refinement | Recursive single-flight could join itself, escape active-count limits, cross cache lifecycles, mutate before generated admission, or observe an unrepresented flight. | Unified lifecycle-qualified flight identity, context-aware slots, lock-linearized selection/completion, and generated pre-mutation authority. |
| 019–020 | result correctness | Descending merge dropped the maximum EID; generic merge dropped a legal first `nil` key. | Explicit presence bits plus exhaustive CLJ/CLJS source-control fixtures and mutants. |
| 021 | cross-backend configuration | Datomic rejected/failed to forward shared subproblem-cache configuration. | One validated shared cache configuration path. |
| 022–027 | error/limit/boundary correctness | Generated errors lost fields, stale-cursor shapes diverged, materialized resource counters were substituted for query-local limits, and signed EIDs crossed a natural-number model. | Exact public typed errors, dimensionally correct counters, generated indexed resource authority, and nonnegative exact-integer adapter guards. |
| 028–034 | assurance-harness correctness | Artifact sizes were stale, corpus schema values drifted, typed routing lost resource type, CI lacked Babashka, error comparison discarded fields, graph identity compared locator capabilities, and CLJS test execution poisoned shared executors. | Fail-closed build measurements, strict corpus schema, typed routing certificate, pinned CI dependencies, complete portable error comparison, correct graph identity, and persistent nREPL-safe CLJS execution. |
| 035 | performance | Reflective Java interop allocated and resolved methods on every generated traversal round trip. | Concrete type hints and a complete reflection audit. Recursive page p95 allocation fell from 3,677,688 to 343,576 bytes; continuation fell from 4,283,960 to 706,496 bytes on the recorded fixture. |
| 040–044 | source-model fidelity | Formal merge/leapfrog/arrow/path-fold models omitted source control state, exact seek traces, empty-arrow fast exit, path materialization, or callback order. | Source-shaped Dafny models, exact CLJ/CLJS traces, exhaustive small domains, adapter composition, and production empty-arrow fast exit. |
| 045 | target-runtime complexity | Dafny's abstract immutable Java/JS collections and pairwise ordering validator created quadratic target costs despite correct logical counters. | Persistent target collections, sequence views, and adjacent-order validation. A 15,000-result reverse page improved from about 5.7 seconds to 2.78 ms on the recorded JVM fixture; JS scaling became approximately linear over 1,024–16,384 results. |
| 046 | cache usefulness | Completed-answer keys and root names prevented cross-query reuse of equal subgraphs. | Rule-body denotation identity plus proved equal-body/equal-fixed-point theorems. Across 80 roots sharing a depth-48 chain, layered p50 was 0.181959 ms versus 0.684250 ms and performed zero backend operations. |
| 047–050 | continuation/performance harness | Cursor recovery reused stale frontiers; backward replay was not page-size bounded; routing/rebase gates measured JIT/compiler history; warm `can?` used an unstable single batch. | Rebase-or-restart law, bounded opaque continuation store, fresh bounded JVM gates, valid scaling domains, and stable multi-batch warmup. |
| 051 | hot-path performance | Public `can?` classified a permission root twice. | Reused classification; recorded paired p50 improved 508.292 µs to 483.625 µs and later heavy-suite warm aggregate was 431.8545 µs. |
| 052–054 | public consistency correctness | `false` consistency could default instead of reject; token maps accepted unknown fields; connectionless DataScript falsely advertised `fully-consistent`. | Raw input forwarding, exact descriptor shape, and truthful capability advertisement. |
| 055 | asymptotic authorization performance | Point `can?` searched forward from a broad subject, making one-resource checks grow with unrelated subject fanout. | Resource-anchored reverse generated traversal. The recorded local median fell from about 453 µs to 150 µs; deterministic work remains one command/one value at 16 and 1,040 unrelated resources. |
| 056 | integration correctness | Direct DataScript/Datahike relationship paging could lose the generated kernel between page normalization and physical scan, a bug previously masked by fallback routing. | Resolve one decision kernel once per page and pass it through both phases; the direct relationship query matrix now covers the path. |
| 057 | assurance-harness correctness | The CLJ-to-generated-Java page-window bridge still called a removed six-field datatype constructor after deprecated pagination inputs were removed. | Align the bridge with the four-field v8 datatype and keep deprecated-input rejection at the public host boundary; all 47 generated-runtime bridge tests now load and pass. |
| 058 | assurance-workflow availability | Ordinary parity CI eagerly loaded a former engine namespace after it moved to the formal-only classpath; the broader local classpath masked the failure. | Remove eager formal-oracle loads, resolve closing regressions lazily, and test the entrypoint source against all retained former-engine namespaces. |
| 059 | assurance-harness correctness | A clean generated-JavaScript rebuild exposed obsolete six-field page-request test arguments and a CLJS recursive-page expectation that disagreed with the equivalent JVM fixture. Cached local generated artifacts had masked both. | Align the direct JS bridge with the current four-field datatype, keep removed API rejection at the host boundary, make JVM/JS consume one shared recursive-page vector, and retain a clean-build source regression. |

These findings also expose defects in the verification program itself. Findings
024–025, 028–034, 040–045, 048–050, and 056–059 are especially important: they
showed that a theorem, comparator, counter, fixture, or benchmark could be
correct in isolation while failing to model the actual production boundary or
target cost. The response was not to weaken the claim; it was to narrow or
repair the model, add a minimized witness, and create a fail-closed gate.

## Final local verification run

The final pre-audit run on 2026-08-06 produced the following evidence:

| Gate | Result |
| --- | --- |
| Dafny | 25 modules, 9,795 proof efforts, 0 errors; no admitted lemma or undocumented axiom |
| TLA+/Apalache | all five models type checked; bounded, inductive, mutation-control, and longer scheduled configurations reported `NoError` |
| Generated Java runtime bridges | 47 tests, 15,628 assertions, 0 failures/errors |
| Generated-only JVM public/backend suite | 509 tests, 34,773 assertions, 0 failures/errors across Datomic, Datahike, and DataScript |
| Generated-only DataScript CLJS suite | 155 tests, 4,419 assertions, 0 failures/errors |
| Heavy generated-only backend/performance suite | 17 tests, 4,062 assertions, 0 failures/errors |
| Minimized counterexample replay | 61 tests / 18,207 assertions on the full formal-smoke classpath, 0 failures/errors; the exact ordinary CI classpath additionally runs 44 tests / 2,966 assertions |
| Mutation control | 1 test, 230 assertions, 0 failures/errors; every registered mutant killed |
| Retained-live-heap gate | five complete 4,000-result recursive walks retained 5,335,984–5,344,744 bytes after full GC, below the 8 MiB ceiling, with identical result digests |
| Generated artifact size | browser bundle 578,108 bytes; Java classes/runtime 1,840,181 bytes; Java source 2,079,857 bytes; JavaScript/runtime 924,530 bytes; every ceiling passed |

The JVM suite observed generated decision calls for all required operations on
all three adapters (338 injected Datomic clients, 66 Datahike, 87 DataScript).
The CLJS suite observed 56 injected DataScript clients. These counters prevent
a green suite that accidentally bypasses the generated authority.

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
- Cache-disabled calls branch before cache-key, proof, token, provider, and
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
- Browser engines other than the recorded generated-JavaScript targets.
- Correctness of custom codecs without the declared deterministic contract.
- Policy intent, data hygiene outside EACL APIs, or ghost tuples created by
  direct endpoint retraction.
- A mechanized Clojure/ClojureScript language refinement proof.
- Independent formal/security review.

## Marketing wording

Recommended:

> EACL v8 uses a Dafny-generated authorization kernel in production. Its
> backend-neutral ReBAC semantics, recursive traversal, pagination, cursor,
> consistency, and cache decision laws are mechanically verified under
> documented assumptions. Fifty-nine minimized correctness, assurance-harness,
> and performance defects were found and fixed during the verification
> program. Datomic, Datahike, and DataScript are covered by shared adapter and
> public-contract suites. Independent audit is pending.

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
3. review strict Java/JavaScript converters and generated collection patches;
4. replay proofs, TLA+/Apalache checks, counterexamples, mutants, adapter
   certification, CLJ/CLJS suites, and generated-only performance gates;
5. inspect source-closure exclusions and test-only/production classpaths;
6. attempt adversarial backend responses, malformed storage, history
   divergence, cache races, and cursor confusion; and
7. record reviewed commit, toolchain digests, findings, and any narrowed claim
   in the verification manifest.

Until that record exists, release automation must continue to withhold the
unqualified `:verified` status.
