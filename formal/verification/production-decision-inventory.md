# Production decision inventory

> Revised 2026-08-15 for the routed stable-discovery engine. Sections that still describe the retired routing certificate, ordered merge, acyclic evaluator or generated indexed traversal are historical and are marked as such.

This inventory identifies code that can change an externally observable
authorization result. Pure encoding, I/O, and backend implementation details
are included only where they can cause a decision to be accepted, resumed, or
reused.

| Decision area | Production source | Decision consumed by |
| --- | --- | --- |
| Sealed plan compilation and rank certification | `eacl.engine.sealed-plan/seal-plan` (four-kind rules from adapter definitions, dense canonical ordinals, 0/1 read-rank certificate checked by `valid-certificate?`, composite fingerprint), cached per source/basis/root by `eacl.engine.v8/stable-plan` | every `can?`, lookup, and count; cursor fingerprint validation |
| Permission dependency closure | `eacl.engine.v8/calc-permission-paths`, `get-permission-paths`, `permission-relationship-eids`, `permission-schema-nodes` | lookup/count cache dependency sets and Relay cursor dependency proofs |
| Execution normalization and deadline | `eacl.execution/normalize`, deadline/cancellation checks in orchestration, relay, and at every reducer transition (`eacl.engine.physical/execution-cut-point`) | evaluation mode, bounded demand, timeout stage/error, whether another command may begin |
| Stable-discovery reducer: admission, order, emission, limits | `eacl.engine.stable-reducer` (`schedule`, `step`, `release-one`, `run-forward`, `run-reverse`, `resume`), public limits mapped by `eacl.engine.v8/stable-limits` | forward/reverse enumeration order and uniqueness, exact and bounded counts, anchored `can?` (`eacl.engine.stable-route/check-eids`), typed limit errors |
| Page composition, checkpoints and replay | `eacl.engine.stable-page/edge-page`, `deliver-page`, `state-at-boundary`, `checkpoint-put!`/`checkpoint-hit`; boundary validation by `eacl.engine.v8/validate-stable-bound!` | lookup page data, one-based `:stable-edge` cursors, page flags, stale-cursor rejection, continuation reuse |
| Public pagination normalization | `eacl.relay` pagination argument and cursor handling | all lookup/count Relay entry points |
| Relationship and authorized pagination | `eacl.engine.relationships` physical keyset pages and bounded authorization windows; `eacl.engine.v8/execute-filtered-lookup-window` over stable discovery; route orchestration in `eacl.client.orchestration` and the Datomic historical facade | relationship pages, authorization scan pages, enumerate-route pages, progress anchors, `:bounded?`, and exact lookahead |
| Authenticated token scope and continuation decision | cursor decode/validate and current/exact graph selection in `eacl.relay` | lookup/count/relationship continuation |
| Consistency plan and selected-snapshot postconditions | `eacl.consistency/selection-plan`, `select-from-source`, `select` | snapshot chosen for every Datomic, Datahike, DataScript, and Datalevin authorization request through an `Acl`; retained snapshots use the separate assertion boundary and perform no source selection |
| Semantic cache key and entry eligibility | complete basis identity plus generated exact-only and managed entry decisions and completed typed artifact validation | `can?`, lookup, count, relationship-read, and permission-tree cache-enabled responses; eligibility has zero backend-command authority |
| Cache miss ownership and publication | `eacl.subproblem-cache/resolve-independent!`, generation-qualified bounded `publish!` | misses compute independently; compatible winners are retained and losing/late candidates are discarded without changing authorization |
| Local cache failure and invalid-entry handling | `eacl.cache` exact-basis/managed decisions plus `eacl.subproblem-cache` lookup/validation/publication | whether a client-private cached authorization result may be returned |
| Backend snapshot and scan contract | `eacl.backend.v8` protocol operations | every engine result, through adapter-provided facts and identities |

### Retired-engine boundaries (historical, pending the task 9.2 formal cut)

The following paragraphs describe decision boundaries of the interim v8 routing, merge and acyclic engines that the stable-discovery engine replaced; none of the named production vars (`traversal-permission?`, `eacl.lazy-merge-sort` in `src`, `can-uncached*`, the routing certificate consumers) exist on the routed path any more.

Recursive routing now has a typed semantic oracle:
`RecursiveEngine.DecideTypedTraversalPermission` consumes complete
`[resource-type permission]` dependency edges and proves that a root is routed
exactly when it transitively reaches a multi-node SCC or a singleton self-loop.
Generated Java and JavaScript agree with production's shared iterative
Kosaraju/reverse-reachability analysis on seven adversarial shapes and all 512
labeled directed graphs over three typed nodes. EACL-FORMAL-030 records why
the older permission-name-only arrow abstraction cannot support this exact
claim. The proof-carrying production boundary also verifies exact ordered
materialized-path-descriptor to dependency-edge derivation before accepting an
SCC certificate, with exact `P+2V+E` accepted certified loop iterations. Adapter path
materialization, host map-to-descriptor translation, and runtime resource peaks
remain open source/platform refinements and are not implied by the
differential campaign.

(Still live.) Consistency selection has a separate generated decision boundary.
`ConsistencyDecision.dfy` distinguishes capability failure, absent exact
history, a present malformed adapter, cross-source selection, and failed
native-revision/exact-locator postconditions. The
16 plan states and 48 well-formed validation states are exhaustively compared
through generated Java and JavaScript. Datomic, Datahike, DataScript, and
Datalevin pass their configured engine selection into this boundary. The
plan-only cost vector covers the minimize-latency decision before source
acquisition. Every successful `Acl` selection then acquires and validates one
selected basis. A retained snapshot does not call this source-selection
boundary: its descriptor is asserted against its already closed basis identity
before evaluation and without a source operation.

This verifies the finite decision over observed facts. It does not prove that
an adapter's source scope, native revision, exact reconstruction, or
authoritative barrier is truthful, and it does not prove token cryptography.
Those remain explicit adapter and cryptographic refinement obligations.

The generated cache decision has one exact-only entry stage for every admitted
basis: availability selects `UseExactEntry`; absence selects
`ComputeExactValue` and can never fall through to managed proof reuse.
The finite decision proves only that a declared available entry controls the
corresponding hit. It does not prove that the host's composite key truthfully
identifies an ordinary immutable snapshot. Backend I/O effects (including
Datomic targeted sync and `d/as-of`), cancellation of provider futures,
Datahike full-history retention, and canonical cache-key fields remain named
adapter/host assumptions covered by deterministic effect tests and real-store
integration evidence.

The acyclic ordered-EID merge now has an exact production control model rather
than only a canonical sorted-union oracle. `OrderedMerge.dfy` represents the
explicit last-value presence bit, exhausted-tail `drop-while`, empty-stream
filtering, and adjacent pairwise fold rounds used by
`eacl.lazy-merge-sort`. It proves that the source recursion equals the
canonical ascending/descending merge for finite strictly ordered streams, that
the complete fold is strictly ordered with the exact input union, and that one
two-stream merge performs at most `|left|+|right|` comparison iterations.
It also proves that strict order plus set equality determines one exact
sequence and therefore that the modeled production fold equals the canonical
balanced fold, rather than relying on that implication informally.
Generated Java and JavaScript execute that source model against the CLJ/CLJS
implementation. The final Clojure-language/sequence-semantics correspondence is
digest-locked trusted refinement pending independent review, not a Dafny proof
of the Clojure runtime.

The acyclic arrow intersection fast path has the same source-specialization
discipline. `AcyclicEngine.dfy` models the 16-element linear probe, inclusive
reseek, and recursive stream selection used by
`eacl.engine.v8/sorted-eids-intersect?`. It proves set-intersection semantics,
linear outer-iteration and reseek-count bounds, and records the exact ordered
reseek side/target trace. Generated Java and JavaScript compare the Boolean
result, aggregate counters, and that trace against callbacks from the actual
CLJ/CLJS source on 4,100 fixtures per runtime. This closes the narrower
wrong-side/wrong-target loophole but does not prove Clojure language semantics,
backend seek complexity, or the inclusive adapter contract.

The next source-shaped boundary covers the high-level arrow decision inside
`can-uncached*`. Dafny proves that direct-intersection positives are sound when
they are a subset of complete far-side evaluation, exhaustive misses are
complete when the sets are equal, and non-exhaustive misses fall back to full
candidate evaluation. It also proves zero or one intermediate skips the direct
intersection, wide arrows perform one such phase, and complete fallback checks
at most the intermediate count. The first differential exposed
EACL-FORMAL-042: production sent an empty arrow through wide-path setup.
Production now returns false immediately, avoiding direct-grant calculation and
subject-side scan setup. Generated Java and JavaScript compare eight exact
source-control traces with the CLJ/CLJS function. Path materialization,
recursive callback meaning, direct-subset/exhaustiveness facts, and Clojure
language semantics remain separate obligations.

The raw-schema boundary feeding that decision is now modeled separately.
`AcyclicEngine.dfy` expands raw typed relation and permission definitions into
the four production path-map variants, drops missing source/target definitions,
and applies the exact relation/alias/arrow-relation/arrow-permission cost
partition. It derives direct relation EIDs only from relation paths matching the
query subject type and proves that a direct positive is sound; if every path is
a relation, the direct result is complete. Generated Java and JavaScript match
the actual CLJ/CLJS materializer and direct summary on 99 fixtures each.
Adapter certification v4 checks the composed path maps against real relation
IDs on Datomic, Datahike, and DataScript. Clojure language semantics and
arbitrary backend implementation correctness remain explicit trusted
obligations.

The enclosing `some` fold in `can-uncached*` is also modeled rather than
collapsed into an unordered Boolean union. The recursion guard, materialized
path realization order, direct subject-type gate, first-positive
short-circuit, and direct/self/arrow callback-kind trace are exact. Generated
Java and JavaScript compare 407 source executions per runtime. This closes the
outer control and linear logical-work boundary, but not complete callback
argument vectors or the semantic correctness of nested callback results.

## Public operation coverage

The decisions above flow into these externally observable families:

- boolean permission checks (`can?`);
- forward resource lookup and reverse subject lookup;
- exact and bounded count;
- lookup cursor continuation in both supported page directions;
- relationship pagination and cursor continuation;
- authorization-filtered relationship scans and relationship-filtered resource/subject enumeration;
- ordered batch permission checks under one selected snapshot and aggregate budget;
- cache-enabled variants of checks, lookup, and count.

No production decision may be omitted from the assurance matrix when it can
alter allow/deny, membership, the stable per-query pagination sequence, page
flags, typed errors, selected snapshot, or cache provenance. “Ordering” here
does not imply a global, lexical, domain, or cross-backend order.

## Machine-enforced source closure

`public-source-closure.json` is generated from both CLJ and CLJS analysis of
shared and backend EACL source files. It closes the cross-namespace call graph
from engine, relationship-pagination, relay, cursor, cache, subproblem-cache,
consistency, causal-token, authority-provider, and named
Datomic/Datahike/DataScript roots. Exact counts and digests are release-artifact
data and MUST be regenerated after this change; prose does not pin stale counts.
Unattributed clj-kondo usages inside exact `defrecord` spans are assigned to
their containing protocol implementation, so those public client and generated
kernel methods are included. CI checks the exact
analyzer version, source digests, definition locations, reachable sets, and
external call sets. Any source change therefore forces review of the decision
closure instead of silently adding a branch.

This is a completeness ledger, not a source-refinement proof. Its explicit
remaining scopes are adapter-operation semantic refinement and theorem
classification for every reachable definition. `backend-dispatch.edn`
separately proves the static closure fact that every CLJ and CLJS
`backend/invoke` site uses a literal key from the pinned operation set (the
required snapshot operations plus `:schema-generation` and `:proof-frame`; the committed ledger holds
the exact site and key counts); this does not prove what an adapter
implementation does. The release claim remains withheld until those semantic
classifications are complete.
