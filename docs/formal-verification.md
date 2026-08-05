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

The generated-artifact gate additionally requires Babashka 1.12.213. Formal
CI installs that exact gate runtime before rebuilding and measuring artifacts;
the committed gate configuration and regression test reject version or
workflow drift.

The bootstrap installs only under `target/formal-tools/`. Tool versions,
platform artifacts, licenses, upstream URLs, and SHA-256 values are committed
in `formal/toolchain.lock.json`. That lock also carries the Dafny
per-assertion-batch time ceiling and deterministic Z3 resource limit. `verify`
writes one CSV per Dafny module plus
`target/formal/dafny-verification.json`; any failed effort, timeout, or effort
over the locked resource limit fails the command. The solver resource count is
a proof-pipeline measure, not evidence about EACL request latency, heap, or
backend work.

Run proof and model targets independently:

```sh
bin/formal source-closure
bin/formal format
bin/formal verify
bin/formal build-java
bin/formal build-js
bin/formal browser-bundle
bin/formal artifact-size
bin/formal tla-typecheck
bin/formal apalache-check
bin/formal apalache-invariant
```

`artifact-size` must run after all generated forms are rebuilt. It measures
uncompressed Java source bytes, Java class bytes, JavaScript-with-runtime
bytes, and browser-bundle bytes separately against the reviewed full-kernel
ledger in `formal/verification/generated-artifact-size.edn`; it does not
substitute one representation, solver effort, allocation, heap, or latency
for another.

`source-closure` checks the committed
`formal/verification/public-source-closure.json` ledger with the exact
clj-kondo version in the toolchain lock. The ledger closes 60 named shared and
backend roots over 1,288 definitions in 51 source files, including unattributed
usages assigned to their exact containing `defrecord` spans. It is static
completeness evidence only: it does not prove Clojure source or adapter
semantics. `backend-dispatch.edn` additionally closes every CLJ/CLJS
`backend/invoke` site to the exact 21 required literal operation keys; the
meaning of each adapter implementation remains a named obligation.

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
| `RecursiveEngine.dfy` | typed SCC routing, recursive reachability, forward/reverse worklists, limits, continuation replay |
| `OrderedMerge.dfy` | ordered union and uniqueness |
| `Pagination.dfy` | frontier and direction laws |
| `PageWindow.dfy` | total page normalization, windows, keyset page decisions, cursor continuation decisions |
| `CacheKernel.dfy` | dependency closure, cache validation, telemetry CAS laws |
| `CurrentCache.dfy` | exact/current admission, lifecycle isolation, scalar stamps, least-fixed-point dependency frame, selected-snapshot rendering |
| `SchemaPlanCost.dfy` | one recursive-plan compilation per permission root/schema generation and bounded page-sensitive stream batches |
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
and nonnegative exact-integer internal EIDs. Global completeness, ancestry,
and proof truthfulness remain certification obligations.

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
relationship request normalization, relationship keyset page flags/window
size, and decoded cache-entry decisions are routed through that boundary in
verified modes. The indexed relationship engine retains only an authenticated
physical edge and consumes at most one page plus lookahead; executable
forward/backward walk tests establish stable, complete, duplicate-free
composition over certified adapter scans. This is deliberately not a theorem
of a global or cross-backend result order. Shadow mode reports only the operation,
changed field names, and non-sensitive result variants. It deliberately emits
neither raw values nor hashes of low-entropy request/result data; a generated
exception, invalid result, or disagreement cannot alter the legacy decision.

The same boundary now converts complete materialized schema IR, objects,
relationships, traversal limits, all five authorization request variants, and
typed results to generated Java and JavaScript. This is the executable
cache-free semantic reference used by differential tests. Its completed
authorization values are compared with completed indexed results. Its work
counters and typed limit outcomes are not production resource refinements:
the reference closes the whole finite fixture, while production is
query-local. Production limits and dimensionally matching counters are instead
shadowed against the generated indexed state machine. Cached and uncached
public-client state traces cover Datomic, Datahike, and DataScript, including
unrelated transactions and revocation.

The public `can?` dispatch and acyclic hot path also have source-shaped
submodels. The public model proves that reusing the already-computed
permission-root classification preserves the Boolean result under the
established undefined-root-denies contract and reduces a
generated-authoritative call to one root lookup. A public JVM fixture observes
that exact lookup count; the shared CLJC result path remains covered on CLJ and
CLJS. The acyclic models cover ordered EID merge, leapfrog intersection, and
arrow empty/singleton/wide selection. Dafny
proves their Boolean/set behavior and named logical bounds; generated
Java/JavaScript compare the exact source-control results and traces with
CLJ/CLJS. EACL-FORMAL-042 records the resulting production fix: an empty arrow
now returns false before direct-grant/intersection setup. These submodels do not
prove path materialization, nested callback meaning, storage-engine seek cost,
Clojure language semantics, allocation, retained heap, or wall time.

Permission-path materialization now has its own source-shaped boundary rather
than being assumed by the arrow theorem. Dafny models expansion of typed
relation definitions into direct, alias, arrow-relation, and arrow-permission
paths, missing-definition behavior, static cost ranking, subject-type filtering
for direct grants, and the exact meaning of `:exhaustive?`. Generated Java and
JavaScript match `calc-permission-paths` and `calc-direct-grant-relations` on 99
CLJ/CLJS fixtures each. Adapter certification v2 composes the same calculation
with actual Datomic, Datahike, and DataScript definition IDs. That is finite
executable refinement evidence; host-language semantics and arbitrary storage
engine states remain trusted.

The outer acyclic union fold is source-shaped as well. Dafny proves the
recursion guard performs zero path/callback work, direct paths with a
nonmatching declared subject type do not invoke the backend probe, evaluation
stops at the first effective positive, and path/callback checks are linear in
the materialized path count. Generated Java and JavaScript match the actual
CLJ/CLJS value, realized-path count, per-kind callback counts, and ordered
`[path-kind, path-index]` trace on 407 fixtures each. Complete callback argument
vectors and the meaning of nested callback results remain separate refinement
obligations.

Recursive engine selection has a narrower generated oracle. Production first
materializes complete permission dependencies as
`[resource-type permission] -> [resource-type permission]` edges, then uses
iterative Kosaraju SCC detection and reverse reachability to route SCC members
and all transitive acyclic ancestors. The generated
`DecideTypedTraversalPermission` method proves the equivalent typed
least-closure predicate and is differentially checked in Java and JavaScript.
The older `AcyclicEngine.PathDependencies` name-only arrow abstraction remains
a conservative cache-scope model; it is not used as the exact routing claim.
EACL-FORMAL-030 retains the same-permission-name counterexample.

That semantic comparison is not a resource refinement. Production's stated
O(V+E) routing cost begins after permission paths are materialized, while the
generated oracle uses repeated finite closure scans. Lore commit
`dabb5634b0d44e196e2b6ec63003917b3d445bec` historically prompted part of the
structural-risk inventory, but its outdated analyser is untrusted and the
immutable result does not cover current source. It proves no production time,
allocation, heap, or backend-work bound.

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
