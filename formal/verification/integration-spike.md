# Dafny Java/JavaScript integration spike

Date: 2026-08-02  
Host exercised: macOS arm64, OpenJDK 24.0.1, Node 22.23.1

## Result

One Dafny value/collection/error function was proved, translated, compiled,
and invoked through all current EACL runtime shapes:

| Boundary | Invocation | Result |
| --- | --- | --- |
| Clojure/JVM | generated Java classes, called by `eacl.formal.java-round-trip` | 4 assertions passed through nREPL |
| Clojure/JVM semantics | generated Java fixed-point evaluator compared with `eacl.authorization-oracle` | acyclic and recursive fixtures; 4 assertions passed through nREPL |
| ClojureScript/Node | CLJS compiled through nREPL, requiring the generated loader | 5 assertions passed |
| Node JavaScript | generated bundle evaluated by the checked-in smoke loader | accepted/error variants passed |
| Browser | esbuild 0.28.1 bundle served locally and run in the in-app Chromium browser | `data-status="passed"`; no browser warnings/errors |

The smoke boundary rejects an unknown version tag, an oversized collection,
and a negative value; it returns the input sequence exactly for accepted data.

## Target restrictions discovered

Dafny 4.11.0's Java target emits packages named after Dafny modules
(`EaclKernel`, `Semantics`, and `TemporalSafety`). Its runtime must be present
on the consumer classpath; EACL therefore translates with `--include-runtime`
and compiles the runtime into the generated class directory.

The JavaScript target:

- emits a script with lexical module variables rather than ESM/CommonJS
  exports;
- requires `bignumber.js` even for this small boundary;
- uses CommonJS `require` in the generated runtime and therefore is not a raw
  browser script;
- works on Node through `generated_loader.cjs`;
- works in browsers after bundling with the pinned esbuild and exposing only
  the generated module objects at the bundle boundary.

Raw, unbundled browser loading is unsupported. The supported JavaScript shapes
for this verification work are Node/CommonJS loading and an esbuild-produced
browser IIFE. Additional consumer bundlers must pass the same smoke before
being added to the assurance matrix.

Generated JVM classes must be exercised in a fresh nREPL after regeneration.
The JVM cannot replace already-loaded generated classes, and repeated
`:reload-all` of namespaces defining Clojure records can likewise leave
same-named record classes with different identities. The documented smoke
starts one clean `:formal-smoke` nREPL per generated build; a long-lived dev
REPL is not evidence for generated-class reproducibility.

## Generated artifact policy

Generated Java/JavaScript remains reproducible build output under `target/`;
it is not checked-in source and must never be hand edited. Release packaging
will publish compiled Java classes/runtime and the browser/Node JavaScript
artifacts so consumers do not install Dafny. CI regenerates these artifacts
from locked tools, compares their digests, and fails on nondeterminism before
publishing them.

This keeps Dafny source as the single implementation authority while allowing
ordinary Maven/npm consumers to use release artifacts without a verifier.

## Initial artifact sizes

These are spike measurements, not cutover thresholds:

- generated Java source: 776 KiB;
- generated Java classes including Dafny runtime: 844 KiB;
- generated JavaScript including Dafny runtime: 104 KiB;
- esbuild browser bundle: 160 KiB uncompressed.

The full kernel will be larger. Quantitative release thresholds are derived
from the completed legacy benchmark and full generated kernel, not from this
round-trip spike.
