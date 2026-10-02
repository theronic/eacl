## 1. Establish the reference semantics
- [x] 1.1 Read `eacl.caveats.jvm`, cel-parser 0.1.8's visitor and overloads, and core's plan, values, definition and partial namespaces; record which runtime faults profile 1 can reach and how each evaluator combines them.
- [x] 1.2 Confirm that the published `8.0.0-RC-2026-09-12` core source equals the mainline core the module is built against.
- [x] 1.3 Probe non-canonical JVM host values against both paths of the JVM evaluator; record the divergence for separate follow-up.

## 2. Implement the module
- [x] 2.1 Add `modules/eacl-caveats-portable` with `eacl.caveats.portable`: capability, canonical host values, bounded plan cache, JVM-order validation and exception classification, `evaluator`, `cache-stats`, and registration that keeps an earlier default.
- [x] 2.2 Add `deps.edn`, `build.clj` and a README with installation, use, semantics, identity and conformance.

## 3. Certify it
- [x] 3.1 Run the shared corpus on the JVM and on Node.
- [x] 3.2 Add a seeded test.check differential test against `eacl.caveats.jvm`, plus finite logic, comparison, resource and malformed-definition comparisons.
- [x] 3.3 Add end-to-end DataScript tests to `eacl.datascript.cljs-test-runner`: checks, `can?`, detailed lookups and counts, expiry, bound-over-request and faults.
- [x] 3.4 Compile a consumer with the published RC jars and `cljs.main :advanced`, with no warnings, and run it on Node.

## 4. Integrate
- [x] 4.1 Register the module in the build configuration, release entry points, release guard, jar isolation audit and CI isolated-module matrix; add a jar isolation test.
- [x] 4.2 Add its source root and entry points to the public source closure and reflection gate; update root and DataScript aliases and `AGENTS.md`.
- [x] 4.3 Update `docs/caveats.md`, `docs/publishing.md`, `README.md` and the DataScript README.

## 5. Verify
- [x] 5.1 Run the CI-equivalent nREPL battery, then the DataScript ClojureScript build last; also run the runner's `:advanced` build with warnings as errors.
- [x] 5.2 Run `bin/formal source-closure`, the reflection gate, the module's isolated test and jar build, and the cold Maven release-set smoke.
- [x] 5.3 Validate this change with `openspec validate --strict`.
