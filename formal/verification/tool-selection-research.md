# Formal-method selection for EACL

Date: 2026-08-04

## Recommendation

Use a layered assurance argument rather than attempting to verify Clojure or a
database implementation:

1. Define EACL authorization as the least fixed point of a finite, monotone
   ReBAC consequence operator.
2. Implement the decision kernel in Dafny and prove executable algorithms refine
   that semantics. Compile the same source to Java and JavaScript.
3. Model cache, cursor, retention, and concurrent snapshot histories in TLA+.
   Use Apalache first for bounded counterexamples and then for separate
   initiation, consecution, and safety-implication obligations.
4. Treat each backend as an abstract snapshot oracle. Certify its assumptions
   with executable contract and adversarial tests; do not claim to prove the
   backend.
5. Differentially test generated kernels, the independent semantics, current
   engines, public clients, adapters, and CLJ/CLJS. Preserve minimized
   counterexamples.
6. Maintain an explicit refinement map from each formal input and result to the
   exact production expression, typed error, and adapter obligation. A theorem
   about the wrong abstraction is not EACL correctness.

This split matches the problem's trust boundary: Dafny proves functional
algorithms and termination; TLA+/Apalache explores state histories and races;
contract tests discharge observable adapter obligations; differential testing
checks the handwritten boundaries and legacy implementation.

## Why Dafny

Dafny combines mathematical datatypes, specifications, termination measures,
lemmas, an SMT-backed verifier, and executable Java/JavaScript compilation.
That makes it possible to run the verified algorithm in both EACL target
runtimes instead of maintaining a proof-only oracle forever.

Dafny ghost state and explicit work counters are also useful for proofs over
algorithmic steps, queue cardinality, scans, probes, and other logical units.
They do not establish JVM/JavaScript allocation, retained live heap, GC peaks,
scheduler concurrency, cryptographic cost, backend I/O cost, CPU time, or wall
time unless a separate checked refinement gives those host concepts formal
meaning. Those dimensions therefore remain source-instrumented or measured
regression gates.

## Model-fidelity discipline

Dafny verification is relevant to EACL only when production obtains the same
inputs and consumes the same result. Each routed decision therefore requires
all of the following evidence:

1. a field-by-field map from every Dafny input to the exact Clojure expression
   that observes it;
2. generated Java and JavaScript execution of the verified Dafny function,
   rather than a second handwritten implementation presented as verified;
3. exhaustive generated-boundary tests when the finite input domain permits
   them;
4. CLJ and CLJS tests over the production fact-extraction paths, including
   reachable absent, malformed, identity, same-scope, and cross-scope states;
5. strict wire/input/result validation and fail-closed unknown variants;
6. differential, mutation, and counterexample controls for the handwritten
   boundary; and
7. an explicit residual-trust list for facts supplied by adapters, token
   authentication, host exceptions, and platform resource behavior.

These checks are executable refinement evidence, not a formal proof of the
Clojure runtime or backend. A release-level source-refinement claim remains
withheld until the relevant production algorithm is generated from Dafny or
has an independently checked refinement proof.

Primary references:

- https://dafny.org/latest/
- https://docs.dafny.org/

## Why TLA+ and Apalache

Authorization evaluation at one immutable snapshot is a functional problem;
cache validation, causal proof lifting, cursor continuation, retention, and
provider races are temporal problems. TLA+ expresses those histories above the
storage-engine implementation. Apalache provides typed symbolic bounded model
checking and direct inductive-invariant checks through SMT.

Primary references:

- https://lamport.azurewebsites.net/tla/tla.html
- https://apalache-mc.org/
- https://apalache-mc.org/docs/apalache/running.html

## Industry precedent

Cedar uses an executable formal authorization model plus property-based and
differential testing against production code. AWS's AuthV2 work additionally
demonstrates compiling a verified Dafny implementation to Java. EACL adopts the
stronger production-kernel route where practical while retaining Cedar-style
differential testing to validate handwritten FFI and adapter boundaries.

Primary references:

- https://github.com/cedar-policy/cedar-spec
- https://docs.cedarpolicy.com/other/security.html
- https://www.amazon.science/publications/formally-verified-cloud-scale-authorization

## Rejected as the primary method

- Testing alone cannot establish unbounded traversal, de-duplication,
  pagination, or cache-safety theorems.
- Bounded model checking alone cannot prove behavior outside its chosen scope.
- A proof-only Lean or Rocq oracle would still leave both production runtimes
  as handwritten implementations. Either remains useful as an independent
  semantics review, but changing proof assistants does not discharge the
  Clojure/adapter refinement gap or create a host-resource model.
- Direct verification of Clojure/JVM bytecode would pull the language runtime,
  persistent/lazy collection implementations, and dynamic dispatch into the
  trusted problem and lacks a maintainable EACL-specific toolchain.
- Proving Datomic, DataScript, or Datahike violates the requested scope.

## Current conclusion

The selected stack is suitable, but the current repository is only an initial
verified foundation. A passing small model or semantics file is not a proof of
the complete EACL engine. Release status remains `:not-verified` until every
public operation has a generated-kernel theorem, required adapter obligations
are certified, and production authority is routed through that kernel.

Lore's historical analyser is explicitly excluded from this assurance stack.
Its useful contribution is an accounting discipline—keep logical weight,
represented state, coordinator state, running host work, backend operations,
heap, and elapsed time separate—not a trusted analysis result. The EACL
repository implements that discipline directly with Dafny counters, exact
source-call instrumentation, and host-specific performance gates.
