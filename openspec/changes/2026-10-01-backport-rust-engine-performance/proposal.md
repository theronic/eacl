# Backport the Rust engine's performance to the Clojure engine

## Why

The eacl-rust benchmark (`crates/eacl-bench`, REPORT.md) runs one
deterministic Google-Drive-style graph on both implementations: groups with a
4-ary subgroup tree, a 3-ary folder forest, documents with
`view = (viewer + owner + parent->view) - banned` and
`review = parent->view & reviewer`, plain, expiring and caveated
relationships. On `main` (v8.0.0, `a698b3bd`) at 10⁶ relationships a denied
check of a typical document took 47 s, a cache-miss walk of a broad
subject's 162,679 documents 92 s, and the cache-off walk did not finish in
120 s. Rust answers the same questions in 0.35 ms and 18 ms.

The open stack #199–#210 already removes most of that: delegation (#199),
leveled and guarded membership (#200–#202) and the tabled evaluator's
constant factors (#202) take every benchmark permission off the global
re-solving evaluator ("Route C"). Measured on the stack head (#210) at 10⁶:

| Workload (10⁶) | main | #210 | Rust |
|---|---:|---:|---:|
| check, denied, typical document | 47.4 s | 14.1 ms | 0.35 ms |
| check, denied through 3,000 nested groups | > 60 s | 35.2 ms | 0.75 ms |
| check-permissions, 256 documents | > 60 s | 757 ms | 1.4 ms |
| lookup-subjects, first 50 of ~40k | 5.83 s | 257 ms | 1.08 ms |
| count, broad subject (162,679) | > 60 s | 9.68 s | 6.3 ms |
| walk, broad subject, cache miss | 92.2 s | 13.4 s | 18 ms |
| walk, broad subject, `:cache? false` | > 121 s | 93.4 s | 1.05 s |
| count of a union-only permission (164,302) | 698 ms | 850 ms | 6.4 ms |

Five mechanisms remain, and the stack added two regressions:

1. **Operands evaluated in canonical node order.** The operator evaluators
   take union children in node-id order, which puts `parent->view` before
   `viewer` and `owner`. A check that a direct grant decides scans the whole
   folder ancestry: 3 adapter commands on `main` against 189 on #210 at 10⁵
   and 1,932 at 10⁶; 0.25 ms on `main` and 8.1 ms on #210 at 10⁶.
2. **Candidates re-proven one at a time.** Operator lookups and counts
   enumerate a cover and then decide every candidate with the full operator
   expression. The vector evaluator decides an arrow leaf with one scalar
   evaluation per candidate (`operator.evaluator/check-eids`, 58% of the
   broad count), and nothing records that the cover already proved the
   positive operand through plain relationships.
3. **Ordered machinery where no order is observed.** Union-only counts run
   the first-discovery reducer, which releases one value per transition:
   about 4 µs per result against Rust's 31 ns.
4. **Per-demand work in batches and top-down group membership.**
   `check-permissions` runs its demands one after another; membership in a
   recursive group explores the group's subtree even when the subject
   belongs to no group.
5. **Cache-off continuation replays the cover** from its first candidate on
   every page (O(N²/page)): 93 s against a 13 s cache-miss walk.

Regressions on #210 against `main`: the canonical operand order above, and
#209's fault-freedom metadata certificate, which walks the closure a
batched membership search could visit and so made narrow-subject reads scale
with the data (`count` with `:count-limit 1` for a 44-document subject:
0.41 ms on `main`, 25.8 ms on #210 at 10⁶, 89% of it in
`stable-route/fault-free-checker`). The approved Kleene fault semantics
remove the need for that certificate; the Kleene change removes it.

Route C itself still serves plans that recurse through an operator
non-linearly (an intersection with two recursive operands, a guard that is
an operator permission) and the exact fallbacks of the guarded search. On a
256-folder chain of `reach = viewer + (parent->reach & link->reach)` it takes
38–44 ms on #210 (612 ms to 1 s on `main`), two thirds of it in per-round
condensation and bound solving.

## What Changes

A linear stack of pull requests on top of #210, after the two write-path
fixes (#213 and the schema-reuse PR) and after the Kleene fault-semantics
PR, which every evaluator change below relies on:

1. **Benchmark gate.** Port the eacl-bench graph generator into a
   `^:benchmark` DataScript gate with budgets written before sampling,
   relative to #210 and to `main`.
2. **Cost-ordered operand evaluation.** The operator evaluators decide
   union and intersection children cheapest first: relations, arrows to
   relations, non-recursive permissions, recursive permissions. Under Kleene
   semantics the permissionship is order-independent; certificates stay
   sound and checks and lookups keep using the same order.
3. **Set-at-a-time arrows** (dropped: the tabled evaluator of step 8 decides
   each arrow target once per request, and steps 4 and 5 remove most exact
   decisions).
4. **Structural certainty from the generator.** A candidate the cover found
   through plain relationships only holds the cover's operand without an
   exact proof; only the remaining operands are decided.
5. **Order-free set evaluation for counts.** Counts compute structural sure
   and maybe sets (S ⊆ Has ⊆ non-F ⊆ M) bottom-up from the subject, semi-
   naively per component, and decide only M∖S exactly.
6. **One table per batch.** `check-permissions` decides the demands it can
   group (same subject, permission, context) with one batched oracle and
   serves them in demand order.
7. **Bidirectional recursive membership.** An arrow into a recursive
   union-only permission is decided from the subject's side when the
   subject's closure is smaller than the resource's.
8. **Tabled evaluator.** Replace Route C with a top-down tabled fixpoint
   (restart on demand, leader-based component completion, accumulation over
   the Kleene evidence lattice, integer goal ids, one table per request), and
   converge on it: once it meets their budgets, delegation and the guarded
   search retire to test oracles.
9. **Continuation without replay.** Resume cache-off walks from a
   continuation checkpoint instead of replaying the cover, keeping the
   cover's deterministic order.
10. **Constant factors.** Token and cursor encoding on byte arrays on the JVM
    (34% of a write on #210 is the response token), and per-generation
    request scaffolding.

No authorization answer changes. Result order stays the cover's,
undefined but deterministic. A decision's deadline is a sound,
implementation-defined bound: its first decisive witness in a static cost
order, independent of cache state and request history. Cursor and checkpoint
formats change only where a PR says so; an older cursor then gets the typed
stale-cursor error.

## Capabilities

### New Capabilities

- `operator-engine-performance`: budgets, on the benchmark graph, for
  checks, batches, lookups, counts and walks of operator and union-only
  permissions, with the semantic guarantees each optimization must keep.

### Modified Capabilities

- `demand-bounded-evaluation`: operand order, set-at-a-time arrows and
  structural certainty are demand-bounded evaluation strategies.
- `formally-verified-authorization-engine`, `formal-implementation-conformance`:
  new Dafny models (operand order, structural bounds, bidirectional
  membership, tabled fixpoint) and their executable refinements.
- `keyset-recursive-pagination`, `cursor-dependency-validity`: continuation
  without replay, and the cursor identities that change with it.

## Impact

`eacl.operator.vector-evaluator`, `eacl.operator.evaluator`,
`eacl.operator.plan`, `eacl.operator.recursive` (replaced),
`eacl.engine.stable-route`, `eacl.engine.stable-reducer` (counts),
`eacl.engine.v8`, `eacl.client.orchestration` (`check-permissions`),
`eacl.secure-format`; new Dafny models under `formal/dafny/`, their refinement
campaigns and mutation controls, `formal/assurance_contract.clj`,
`docs/permission-set-algebra.md`, `docs/formal-verification.md`. No new
dependency, no storage change, no cross-client state.
