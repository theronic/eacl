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
| Relationship pagination | `eacl.relationships.relay` query scope, offsets, current/exact graph selection | relationship list APIs |
| Authenticated token scope and continuation decision | cursor decode/validate and graph/proof selection in `eacl.relay` and `eacl.relationships.relay` | lookup/count/relationship continuation |
| Semantic cache key and entry eligibility | `eacl.cache` request keys, execution/source identity, exact/causal/proof validation | `can?`, lookup, count cache-enabled responses |
| Cache provider failure/tamper handling | `eacl.cache` read, authentication, proof-provider, and validation paths | whether a cached authorization result may be returned |
| Backend snapshot and scan contract | `eacl.backend.v8` protocol operations | every engine result, through adapter-provided facts and identities |

## Public operation coverage

The decisions above flow into these externally observable families:

- boolean permission checks (`can?`);
- forward resource lookup and reverse subject lookup;
- exact and bounded count;
- lookup cursor continuation in both supported page directions;
- relationship pagination and cursor continuation;
- cache-enabled variants of checks, lookup, and count.

No production decision may be omitted from the assurance matrix when it can
alter allow/deny, membership, ordering, page flags, typed errors, selected
snapshot, or cache provenance.
