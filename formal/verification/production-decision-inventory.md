# Production decision inventory

This inventory identifies code that can change an externally observable
authorization result. Pure encoding, I/O, and backend implementation details
are included only where they can cause a decision to be accepted, resumed, or
reused.

| Decision area | Production source | Decision consumed by |
| --- | --- | --- |
| Schema path normalization and compilation | `eacl.engine.v8/calc-permission-paths`, `get-permission-paths`, `frontier-permission-paths` | `can?`, forward/reverse lookup, count |
| Permission dependency closure and recursion routing | `permission-relationship-eids`, `permission-schema-nodes`, `permission-schema-components`, `traversal-permission?` | lookup/count cache keys, cursor proofs, acyclic vs recursive engine selection |
| Direct and arrow path matching | `direct-match-datoms-in-relationship-index`, `arrow-via-intermediates`, path evaluators in `eacl.engine.v8` | `can?`, lookup, count |
| Recursive rule compilation and worklists | `compile-recursive-rules`, recursive forward/reverse traversal functions | recursive `can?`, forward/reverse lookup, count |
| Recursive work limits | `normalize-recursive-traversal-limits`, `increment-counter`, `enqueue-work` | all recursive operations; limit errors must abort the whole result |
| Recursive continuation and page reuse | `cached-continuation`, `store-continuation!`, `cached-recursive-request-page`, `cached-recursive-previous-page`, `store-recursive-page!` | recursive lookup/count pagination |
| Page-bound validation and window assembly | cursor-bound validators and page constructors in `eacl.engine.v8` | lookup/count page data, order, flags, start/end cursors |
| Public pagination normalization | `eacl.relay` pagination argument and cursor handling | all lookup/count Relay entry points |
| Relationship pagination | `eacl.engine.relationships` scan planning, physical keyset edges, bounded lookahead, and generated page-window decision | relationship list APIs |
| Authenticated token scope and continuation decision | cursor decode/validate and current/exact graph selection in `eacl.relay` | lookup/count/relationship continuation |
| Consistency plan and selected-snapshot postconditions | `eacl.consistency/selection-plan`, `captured-current-selection`, `select` | snapshot chosen for every Datomic, Datahike, and DataScript authorization request |
| Semantic cache key and entry eligibility | `eacl.cache` request keys, execution/source identity, exact/causal/proof validation | `can?`, lookup, count cache-enabled responses |
| Cache provider failure/tamper handling | `eacl.cache` read, authentication, proof-provider, and validation paths | whether a cached authorization result may be returned |
| Backend snapshot and scan contract | `eacl.backend.v8` protocol operations | every engine result, through adapter-provided facts and identities |

Recursive routing now has a typed semantic oracle:
`RecursiveEngine.DecideTypedTraversalPermission` consumes complete
`[resource-type permission]` dependency edges and proves that a root is routed
exactly when it transitively reaches a multi-node SCC or a singleton self-loop.
Generated Java and JavaScript agree with production's shared iterative
Kosaraju/reverse-reachability analysis on seven adversarial shapes and all 512
labeled directed graphs over three typed nodes. EACL-FORMAL-030 records why
the older permission-name-only arrow abstraction cannot support this exact
claim. The host-source refinement and production O(V+E) resource proof remain
open and are not implied by the differential campaign.

Consistency selection now has a separate generated decision boundary.
`ConsistencyDecision.dfy` distinguishes capability failure, missing managed
writer authority, absent exact history, a present malformed adapter,
cross-source selection, and failed causal/exact anchor postconditions. The
24 plan states and 48 well-formed validation states are exhaustively compared
through generated Java and JavaScript. Datomic, Datahike, and DataScript pass
their configured engine selection into this boundary. The zero-coordination
captured-current path makes one plan decision and returns the identical
already-captured immutable adapter; scope equality is reflexive and therefore
does not justify a second FFI call or backend scope read.

This verifies the finite decision over observed facts. It does not prove that
an adapter's source scope, ancestry predicate, exact reconstruction, or
authoritative barrier is truthful, and it does not prove token cryptography.
Those remain explicit adapter and cryptographic refinement obligations.

## Public operation coverage

The decisions above flow into these externally observable families:

- boolean permission checks (`can?`);
- forward resource lookup and reverse subject lookup;
- exact and bounded count;
- lookup cursor continuation in both supported page directions;
- relationship pagination and cursor continuation;
- cache-enabled variants of checks, lookup, and count.

No production decision may be omitted from the assurance matrix when it can
alter allow/deny, membership, the stable per-query pagination sequence, page
flags, typed errors, selected snapshot, or cache provenance. “Ordering” here
does not imply a global, lexical, domain, or cross-backend order.

## Machine-enforced source closure

`public-source-closure.json` is generated from both CLJ and CLJS analysis of
51 shared and backend EACL source files. It currently closes the
cross-namespace call graph from 60 engine, relationship-pagination, relay,
cursor, cache, subproblem-cache, consistency, causal-token, and named
Datomic/Datahike/DataScript roots over 1,319 definitions. Unattributed
clj-kondo usages inside exact `defrecord` spans are assigned to their
containing protocol implementation, so those public client methods are
included. CI checks the exact
analyzer version, source digests, definition locations, reachable sets, and
external call sets. Any source change therefore forces review of the decision
closure instead of silently adding a branch.

This is a completeness ledger, not a source-refinement proof. Its explicit
remaining scopes are adapter-operation semantic refinement and theorem
classification for every reachable definition. `backend-dispatch.edn`
separately proves the static closure fact that all 56 CLJ and 56 CLJS
`backend/invoke` sites use literal keys and that their 21-key set equals
`required-snapshot-operations`; this does not prove what an adapter
implementation does. The release claim remains withheld until those semantic
classifications are complete.
