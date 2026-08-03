# Formal-method selection for EACL

Date: 2026-08-02

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

This split matches the problem's trust boundary: Dafny proves functional
algorithms and termination; TLA+/Apalache explores state histories and races;
contract tests discharge observable adapter obligations; differential testing
checks the handwritten boundaries and legacy implementation.

## Why Dafny

Dafny combines mathematical datatypes, specifications, termination measures,
lemmas, an SMT-backed verifier, and executable Java/JavaScript compilation.
That makes it possible to run the verified algorithm in both EACL target
runtimes instead of maintaining a proof-only oracle forever.

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
- A proof-only Lean/Coq oracle would still leave both production runtimes as
  handwritten implementations. It remains a valid independent-review option,
  but not the shortest path to a shared executable JVM/JavaScript kernel.
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
