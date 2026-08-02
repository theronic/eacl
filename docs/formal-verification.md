# EACL formal verification guide

EACL's formal work proves a backend-neutral authorization kernel under named
adapter, runtime, and cryptographic assumptions. It does not verify Clojure,
ClojureScript, storage engines, compilers, cryptographic primitives, or a
customer's policy intent. The current release manifest is deliberately
`not-verified` until production routing, cross-adapter campaigns, performance,
and shadow-rollout gates are complete.

The measured performance consequences and recommended cache-free reference,
consistency, cache, cursor, and backend architecture are recorded in the
[v8 sound cache and cursor redesign](reports/2026-08-02-eacl-v8-sound-cache-redesign.md)
and the normative
[adversarial strategy review](reports/2026-08-02-eacl-v8-strategy-adversarial-review.md).
Their completed-cache scope is superseded by the authoritative
[single-database current-snapshot cache design](reports/2026-08-02-eacl-v8-single-db-current-cache-design.md).

## Local setup

Install the checksum-locked Dafny/Boogie/Z3, Apalache, and TLA+ tools:

```sh
bin/formal bootstrap
```

The bootstrap installs only under `target/formal-tools/`. Tool versions,
platform artifacts, licenses, upstream URLs, and SHA-256 values are committed
in `formal/toolchain.lock.json`.

Run proof and model targets independently:

```sh
bin/formal format
bin/formal verify
bin/formal build-java
bin/formal build-js
bin/formal browser-bundle
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
```

Generated Java classes must be tested in a fresh JVM after every regeneration:

```sh
clojure -M:dev:formal-smoke:formal-cljs-smoke:nrepl --port 0
```

Use the reported port with `clj-nrepl-eval`. All Clojure correctness tests,
including CI, execute through nREPL. `bin/ci-nrepl-eval` is the CI client; it
starts no test JVM and only evaluates a supplied form in an existing server.

## Proof navigation

| Source | Main responsibility |
| --- | --- |
| `Semantics.dfy` | typed rules, normalization, monotone consequence, finite least fixed point |
| `SnapshotOracle.dfy` | abstract immutable adapter contract |
| `AcyclicEngine.dfy` | path compilation, direct checks, acyclic projections and counts |
| `RecursiveEngine.dfy` | SCC reachability, forward/reverse worklists, limits, continuation replay |
| `OrderedMerge.dfy` | ordered union and uniqueness |
| `Pagination.dfy` | frontier and direction laws |
| `PageWindow.dfy` | total page normalization, windows, cursor continuation decisions |
| `CacheKernel.dfy` | dependency closure, cache validation, telemetry CAS laws |
| `CurrentCache.dfy` | exact/current admission, lifecycle isolation, scalar stamps, least-fixed-point dependency frame, selected-snapshot rendering |
| `TemporalSafety.dfy` | unbounded cache/cursor transition predicates |
| `WireFormat.dfy` | strict abstract boundary variants and bounds |

`formal/verification/assurance-matrix.edn` maps public operations to theorems,
adapter assumptions, runtime targets, and CI evidence. A passing proof file is
not by itself a public assurance claim. `formal/verification/manifest.edn` is
the release gate and must continue to refuse verified status while any required
obligation is incomplete.

## Temporal models

`formal/tla/EaclTemporal.tla` is the compact safety model.
`EaclTemporalDetailed.tla` covers hostile cache, cursor, exact-selection,
retention, branch/reset/restore, provider-failure, tampering, and continuation
races. Bounded checks are bug-finding evidence. Separate initiation,
consecution, and safety-implication runs establish the configured inductive
invariants; the final unbounded state predicates are carried in Dafny.

## Adapter certification

The proof assumes adapters provide immutable coherent snapshots, injective
identity conversion, complete schema/scans/proofs, real causal ancestry, and
correct exact selection. Run the shared certification namespaces through a dev
nREPL:

- `eacl.datomic.adapter-certification-test`
- `eacl.datascript.adapter-certification-test` in CLJ and CLJS
- `eacl.datahike.adapter-certification-test`

The machine-readable result is
`formal/verification/adapter-certification.edn`. Optional runtime guards check
locally representable shape, order, uniqueness, bounds, booleans, adapters,
and exact integers. Global completeness, ancestry, and proof truthfulness
remain certification obligations.

## Counterexamples and mutation controls

Every discovered production defect has a directory under
`formal/counterexamples/EACL-FORMAL-NNN/` containing the ledger entry,
minimized fixture, expected result, and reproduction instructions.

Run the complete retained corpus:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay
```

Run all registered deliberately wrong implementations:

```sh
EACL_NREPL_PORT=<dev-port> bin/formal mutation-control
```

The registry is `formal/mutations/registry.edn`; a survivor is a release
blocker. Scheduled CI also runs coherent generated-schema campaigns and uploads
the exact seed, coverage, run metadata, and coherence-preserving minimized
fixture on failure.

## Cryptographic boundary

`formal/verification/cryptographic-assumptions.md` maps authentication,
canonicalization, proof equality, collision resistance, entropy/key management,
and clock axioms to production functions and tests. These remain assumptions,
not proved cryptographic claims.

## Shadow operation and rollback

The internal `:engine-selection` client option now supports
`:legacy-authoritative`, `:verified-shadow`, and `:verified-authoritative`.
Generated Java and JavaScript providers implement the portable
`eacl.verified-kernel/DecisionKernel` boundary. Cursor continuation,
relationship page-window, and decoded cache-entry decisions are routed through
that boundary in verified modes. Shadow mode reports only the operation,
changed field names, and non-sensitive result variants. It deliberately emits
neither raw values nor hashes of low-entropy request/result data; a generated
exception, invalid result, or disagreement cannot alter the legacy decision.

The same boundary now converts complete materialized schema IR, objects,
relationships, traversal limits, all five authorization request variants, and
typed results to generated Java and JavaScript. This is the executable
cache-free reference implementation used by differential tests. It is run
against cached and uncached public-client state traces for Datomic, Datahike,
and DataScript, including unrelated transactions and revocation.

This is still not a full-engine cutover. Materializing an entire database is
not an acceptable hot-path implementation for large EACL graphs. Public
traversal, lookup, count, and permission checks therefore continue to use the
indexed Clojure/CLJS engine as their result source. Complete assurance requires
a proved generated engine that calls certified ordered adapter scans (or an
equivalent non-circular refinement boundary), followed by full shadow and load
gates. The generated providers are reproducible build outputs under
`formal/smoke/`; they are not yet shipped as a supported client option. The
manifest therefore continues to report `not-verified`.

The planned rollout order is:

1. legacy traversal authority with read-only generated decision shadowing;
2. opt-in complete generated authority after zero unexplained divergences and all gates;
3. generated authority by default with the legacy rollback path retained;
4. removal of the legacy decision path only after the compatibility window.

Shadow evaluation may not mutate cache entries, cursor/continuation state, or
backend transactions. Rollback switches authority only; it must retain all
security fixes, formal artifacts, regression fixtures, and authenticated wire
compatibility corrections.

## Interpreting the assurance claim

“Verified” means a mapped generated operation refines the formal least-fixed-
point semantics when its listed adapter and trusted-boundary assumptions hold.
It includes exact successful lookup/count/page behavior and fail-closed limit,
cache, and cursor decisions. It does not mean the entire EACL deployment or
backend is proved correct. Missing coverage, a failed adapter obligation, a
surviving mutant, a timeout, an undocumented axiom, or an unmet performance or
shadow gate withholds the claim.
