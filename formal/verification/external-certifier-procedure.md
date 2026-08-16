# External certification procedure

EACL's checked-in manifest is `:conditionally-verified`. A proof run, generated
artifact build, differential suite, or maintainer review cannot by itself change
that status to `:externally-certified`.

## Required review basis

The certifier MUST start from one named Git commit in a clean checkout and MUST
record:

- the 40-hex source commit and 64-hex certification-bundle digest;
- reviewer name, organization, UTC review date, and reviewed scope;
- the exact `formal/toolchain.lock.json` digest and host/runtime versions;
- source-closure, Dafny report, generated Java/JavaScript/browser artifact,
  mutation-registry, temporal-model, performance-evidence, and OpenSpec digests;
- every residual assumption, proof-only exclusion, unmechanized refinement,
  and property outside certified scope; and
- the result of an independent source/model/control-flow review, not merely a
  rerun of maintainer-authored tests.

`CacheKernel.dfy` is proof-only and exposes no runtime decision operation. The
v8 target rejects caller-supplied cache providers. A reviewer MUST fail the
review if either fact is represented otherwise in generated-boundary,
assurance-matrix, manifest, or public claim text.

## Clean-checkout execution

From the repository root, use fresh tool/output directories and run the pinned
commands in this order:

```sh
export EACL_FORMAL_CACHE="$(mktemp -d)"
export EACL_FORMAL_OUTPUT="$(mktemp -d)"
bin/formal bootstrap
bin/formal format
bin/formal verify
bin/formal build-java
bin/formal build-js
bin/formal browser-bundle
bin/formal artifact-size
bin/formal source-closure
bin/formal tla-typecheck
bin/formal apalache-invariant
bin/formal apalache-check
bin/formal apalache-mutation-control
openspec validate demand-bounded-authorization-execution --strict
bin/formal manifest
```

Clojure, ClojureScript, adapter, mutation, counterexample, concurrency, and
performance suites MUST be run through a fresh test-classpath nREPL as required
by `AGENTS.md`; their machine-readable reports and raw samples belong in the
bundle. A nonzero `bin/formal manifest` result is expected while any required
obligation remains open and MUST NOT be waived.

## Sign-off evidence

The candidate manifest's `:external-certification` map MUST contain:

```clojure
{:status :signed
 :reviewer "<independent reviewer>"
 :organization "<independent organization>"
 :reviewed-at "YYYY-MM-DD"
 :source-commit "<40 lowercase hex>"
 :bundle-sha256 "<64 lowercase hex>"
 :scope "<precise certified operations, backends, runtimes, and exclusions>"}
```

The manifest validator rejects `:assurance-status :externally-certified` unless
that shape is valid, every theorem and generated artifact is complete, adapter
certification passes, the release gate allows certification, and the unmet
obligation list is empty. Sign-off is evidence of review, not a substitute for
those gates.
