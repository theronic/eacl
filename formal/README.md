# EACL formal verification

This tree contains the source of EACL's verification evidence. It does not
claim that an operation is verified merely because a tool ran successfully.
The coverage claim is controlled by
[`verification/assurance-matrix.edn`](verification/assurance-matrix.edn) and
the generated release manifest.

## Layout

- `dafny/` contains the executable mathematical semantics, verified kernels,
  and proof lemmas.
- `tla/` contains bounded temporal models used to discover hostile cache,
  cursor, snapshot, continuation, subproblem-publication, proof-frame, and
  source-switch histories.
- `counterexamples/` retains minimized witnesses and their bug ledger.
- `verification/` records the decision inventory, trusted boundary, assurance
  matrix, tool-selection research, baselines, and release-manifest inputs.
- `verification/temporal-model.md` records the detailed transition scope,
  bounded configurations, induction obligations, and claim boundary.
- `verification/adapter-certification.edn` is the machine-readable record of
  static snapshot and adversarial history checks for each backend/runtime.
- `verification/performance-gates.edn` records quantitative build, runtime,
  memory, token, and staged shadow-rollout gates.
- `smoke/` contains handwritten boundary programs that exercise generated
  Java and JavaScript.

Generated sources, binaries, solver output, and downloaded tools live under
ignored `target/`. They are reproducible build output and must never be edited
as source.

## Commands

Run `bin/formal bootstrap` once, then the individual verification/build/model
commands listed by `bin/formal`. `bin/formal all` also runs the release
manifest gate and therefore intentionally exits nonzero while complete-engine
verified status is withheld. The larger scheduled temporal bound is available
as `bin/formal apalache-scheduled`.

`dafny/NativeGenerationCoherence.dfy` supersedes mutation-graph ancestry as
the managed-cache coherence argument. It proves the forward-history frame from
physical schema/relation generations, including empty dependency closures,
component-safe deletion, stale endpoint guards, and source-lifecycle
isolation. Older graph-oriented temporal artifacts remain bounded legacy
regression models; they are not authority for the v4 cache or token protocol.

`dafny/ScalarFrontierCoherence.dfy` refines that complete relation-generation
frame to a scalar dependency frontier. It retains the independently monotone
maximum collision as a checked counterexample, then proves soundness under the
stronger supported-writer obligation that every affected relation is stamped
atomically with the globally later committed transaction. The proof also binds
the frontier to deterministic complete dependency extraction, immutable
adapter/lifecycle-scoped proof frames, normalized demand, completed-only
publication, exact fallback for incomplete evidence, and selected-snapshot
identity. Backend certification remains the trusted boundary for native
transaction ordering and atomic stamp publication.

The operational guide, theorem navigation, adapter certification,
counterexample workflow, generated-engine cutover policy, and assurance wording are in
[`../docs/formal-verification.md`](../docs/formal-verification.md). Behavior
changes discovered by this work are listed in
[`../docs/formal-verification-corrections.md`](../docs/formal-verification-corrections.md).

The tool bootstrap reads `toolchain.lock.json`, accepts only supported platform
artifacts, validates SHA-256 before extraction, and fails rather than silently
replacing an existing mismatched download. The same lock fixes the Dafny
assertion-batch time and deterministic Z3 resource ceilings. A successful
`bin/formal verify` emits `target/formal/dafny-verification.json` and the
per-module CSV inputs from which it was derived; these measure proof search,
not EACL runtime resources.

`bin/formal artifact-size` runs after the Java, JavaScript, and browser builds.
It measures each generated representation from the current build against
`verification/generated-artifact-size.edn` and fails above its reviewed
full-kernel growth bound. A source or class byte count is not a proxy for
allocation, retained heap, solver effort, or latency.

`bin/formal source-closure` checks the locked CLJ/CLJS static call-closure
ledger for 63 named shared, generated-provider, and backend roots. The ledger
is deliberately marked verification-incomplete: enumerating 1,380 reachable
definitions in 57 source files (including source-span attribution for inline
`defrecord` methods) prevents silent omissions but does not establish source
refinement or adapter semantics. `backend-dispatch.edn` separately checks that
every CLJ and CLJS dispatch site uses one of exactly the 21 required literal
operation keys.

## Assurance status

The release status is **conditionally verified**, with unqualified verified
status withheld until independent review. A theorem becomes releasable only
when:

1. its Dafny obligation passes without an admitted lemma;
2. its boundary and differential checks pass;
3. every adapter assumption named by the operation is certified;
4. the cross-runtime and mutation gates named in the assurance matrix pass;
5. the generated verification manifest records exact source and tool digests.
