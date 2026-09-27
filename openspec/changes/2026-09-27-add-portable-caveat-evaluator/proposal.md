# Add a portable Caveat evaluator

## Why

EACL on DataScript runs in ClojureScript, but the only Caveat evaluator,
`eacl-caveats-jvm`, wraps cel-parser and ANTLR and runs only on the JVM. A
ClojureScript application can serve expiring relationships, yet a schema whose
relations name a Caveat fails with `:eacl.caveat/evaluator-unavailable`. The
existing requirement anticipates "a separately supplied evaluator [with]
matching identity and conformance evidence"; none exists.

Core already publishes every portable part: the bounded parser and type checker
(`eacl.caveats.plan`), admission and canonical values (`eacl.caveats.values`),
definition decoding (`eacl.caveats.definition`) and a four-valued plan
evaluator (`eacl.caveats.partial`). The JVM module already uses that evaluator
for incomplete contexts.

## What Changes

- Add the optional module `dev.eacl/eacl-caveats-portable`, one `.cljc`
  namespace `eacl.caveats.portable`. It depends only on `dev.eacl/eacl`, uses no
  host interop, and calls only core functions present in `8.0.0-RC-2026-09-12`,
  with coordinated core fixes for exact context keys and canonical Boolean identities.
- Evaluate complete and incomplete contexts with core's plan evaluator, after the
  same definition validation, context admission, bound-over-request merge and
  work preflight as the JVM module. Rebuild admitted host values in canonical
  form first, as the JVM module does for cel-parser bindings.
- Mirror the JVM module's API: `evaluator` creates an instance for a client's
  `:caveat-evaluator` option, `cache-stats` reports its bounded plan cache, and
  the descriptor advertises profile 1 with this implementation's fingerprint.
  Requiring the namespace registers the process default unless one is already
  registered.
- Certify it with the shared corpus on the JVM and on Node, a generated
  differential test against `eacl.caveats.jvm`, and end-to-end DataScript tests
  in the ClojureScript runner.
- Add the module to the coordinated release set, CI's isolated-module matrix and
  release guard, the jar isolation audit, the public source closure and the
  reflection gate. Document installation and use.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `relationship-caveats`: a portable evaluator module serves profile 1 in
  ClojureScript with the JVM evaluator's outcomes.
- `clojars-release-pipeline`: the coordinated release set includes
  `dev.eacl/eacl-caveats-portable`.

## Impact

New: `modules/eacl-caveats-portable/` (source, tests, `deps.edn`, `build.clj`,
README). Changed: root `deps.edn` aliases, `eacl-datascript`'s `:cljs-test`
alias and ClojureScript runner, `src-build` module registry, release entry
points, guard and jar isolation audit, `.github/workflows/test.yml`,
`bin/public-source-closure.mjs`, `bin/reflection-gate`, `AGENTS.md`, and short
pointers in `docs/caveats.md`, `docs/publishing.md`, `README.md` and the
DataScript README.

Core source is unchanged. One core test assertion expected no registered
evaluator in the DataScript ClojureScript build; that build now explicitly
requires this module, so the assertion is removed. The JVM battery never made
it, because it loads `eacl.caveats.jvm`. Fail-closed behaviour without an
evaluator remains covered by clients configured with `:caveat-evaluator nil`.

Repository settings must require the new `isolated-modules
(eacl-caveats-portable)` check before a release.

## Non-goals

Do not widen profile 1: macros such as `exists` and `all`, arithmetic, regex,
conditional expressions and the other exclusions remain unsupported. Do not
change the JVM evaluator, core semantics or the canonical encoding. Do not
publish, tag or deploy as part of this change.
