## 1. Establish the reference semantics
- [x] 1.1 Read profile 1 in core, both evaluator modules, the formal Caveat gate, the Dafny models, the corpus and the CI workflows.
- [x] 1.2 Probe cel-parser 0.1.8's `exists` and `all` (per-element reparse, whitespace-free token text) and measure their cost.
- [x] 1.3 Probe SpiceDB v1.56.0 for results, error absorption, shadowing, map keys, missing fields and rejections; remove the probe container.

## 2. Implement profile 2 in core
- [x] 2.1 Parse `exists` and `all` with lexical scoping to `[:var]` nodes; reject malformed and reserved variables and `__result__`; type-check list and map ranges.
- [x] 2.2 Validate, encode and decode comprehension plans and residuals.
- [x] 2.3 Evaluate comprehensions four-valued with CEL's absorption and partial evaluation; build residuals over undecided elements.
- [x] 2.4 Charge comprehensions in the work preflight: every element of a supplied range, nesting multiplied, an absent range not iterated.
- [x] 2.5 Record each definition's lowest profile; accept both profiles; advertise profile 2.

## 3. Evaluate on the JVM
- [x] 3.1 Lower each comprehension to a reserved binding and a predicate program parsed once; fold in the adapter; type bindings once per evaluation.

## 4. Certify
- [x] 4.1 Extend the shared corpus and the exploration gate's `:native` answers.
- [x] 4.2 Extend the finite model, its tests, the plan and evaluator bridges, the refinement bridge and three mutation controls; prove the fold and work laws in Dafny.
- [x] 4.3 Generate comprehensions in the portable module's differential test.
- [x] 4.4 Record SpiceDB v1.56.0's answers to every corpus case and compare both evaluators, listing each difference.
- [x] 4.5 Add the `nothing_sensitive` acceptance test on DataScript in ClojureScript and on the JVM.

## 5. Document
- [x] 5.1 Update the Caveats guide, both module READMEs, the release notes, the formal guides and the fixture README.

## 6. Verify
- [x] 6.1 Run the CI-equivalent nREPL battery, then the DataScript ClojureScript build and its `:advanced` build.
- [x] 6.2 Run `bin/formal` source-closure, format, verify, manifest and fast, and the reflection gate.
- [x] 6.3 Validate this change with `openspec validate --strict`.
