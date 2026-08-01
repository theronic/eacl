## 1. Executable Correctness Model

- [x] 1.1 Implement a backend-neutral reference authorization evaluator and canonical full-content schema/relation proof oracle for shared tests
- [x] 1.2 Define generated state-machine commands for graph writes, unrelated writes, schema changes, cache operations, reads, cursor pages, clone/reset/restore, branch/force-head, and retention expiry
- [x] 1.3 Define the primary differential property that every returned cached result equals uncached evaluation on the response token's selected graph
- [x] 1.4 Define the cursor property that concatenated pages equal the original exact graph or a graph with an equal complete dependency proof
- [x] 1.5 Add regression fixtures for equal transaction numbers with different contents, future/sibling cache candidates, missing mutation anchors, and relevant negative-result dependencies

## 2. Shared Cryptographic Formats

- [x] 2.1 Implement portable canonical serialization with explicit field allowlists, size/depth bounds, exact CLJ/CLJS numeric validation, and malformed-input normalization
- [x] 2.2 Implement domain-separated key derivation and key-id/keyring rotation for Zed-token signing, mandatory cursor authentication, optional cursor encryption, and authorization-cache-entry signing
- [x] 2.3 Implement the version-3 causal Zed-token envelope with source/branch scope, graph anchor, order hint, exact locator, issue time, and authenticated expiry
- [x] 2.4 Replace the base64 portable cursor format with versioned authentication, preserve Datomic encrypted cursors, advertise confidentiality separately, and reject unauthenticated legacy cursors
- [x] 2.5 Implement completed-cache-entry authentication over the canonical complete key, causal metadata, proof, and value
- [x] 2.6 Add CLJ and CLJS tests for signed and encrypted round-trip where supported, synchronous browser behavior, key rotation, wrong domain/key, tampering, unknown fields, excessive nesting/size, integer-range rejection, and constant-time tag paths

## 3. Mutation Journal and Dependency Identities

- [x] 3.1 Define portable metadata constants and backend schemas for append-only mutation id, graph head id/order, schema mutation id, relation mutation id, and retention metadata
- [x] 3.2 Implement idempotent migration that atomically creates a migration mutation, graph head, schema identity, and identities for every existing relation
- [x] 3.3 Implement cryptographically random mutation-id generation and one transaction-data builder that publishes journal/head/schema/relation identities atomically
- [x] 3.4 Update managed schema writes in all backends to use the v3 mutation builder and mint their token from committed `db-after`
- [x] 3.5 Update relationship create/delete helpers to deduplicate affected relations, publish one mutation identity transactionally, and mint committed causal tokens
- [x] 3.6 Update object-cascade deletion helpers to stamp every affected relation in the same committed transaction
- [x] 3.7 Implement token-lifetime-aware mutation-journal retention and conservative grace handling without deleting anchors for any accepted token
- [x] 3.8 Add tests for no-op/retry/batched writes, multiple affected relations, cross-connection writers, migration races, expired anchors, and graph-head/token agreement
- [x] 3.9 Extend causal-writer participation to mutable object identity, caveat inputs, and declared custom adapter dependencies, and disable read token issuance when authority is incomplete
- [x] 3.10 Make mutation ids idempotency keys for ambiguous transaction outcomes and reject reuse with different canonical mutation data

## 4. Adapter Version 3 and Snapshot Selection

- [x] 4.1 Add validated adapter operations/capabilities for source scope, graph head, mutation-anchor membership, order hint, authoritative/current selection, bounded causal selection, exact locator, and optional exact reconstruction
- [x] 4.2 Limit six-function legacy adapters to uncached explicitly supplied snapshots and reject every unsupported v3 guarantee before evaluation
- [x] 4.3 Implement shared consistency orchestration that authenticates the request, selects one snapshot, verifies causal/exact postconditions, builds one immutable adapter, and mints the response token
- [x] 4.4 Define `:fully-consistent` as authoritative-barrier selection, `:minimize-latency` as local complete selection, `:at-least-as-fresh` as causal-anchor dominance, and `:at-exact-snapshot` as verified exact reconstruction
- [x] 4.5 Add typed errors for unsupported head barrier, token expiry, history divergence, freshness timeout, incomparable scope, unavailable exact snapshot, and cursor consistency conflict
- [x] 4.6 Add shared capability-matrix tests proving every configuration either meets its postcondition or fails before authorization

## 5. Complete Dependency Proofs

- [x] 5.1 Make the engine compute a static schema-derived transitive relation closure independent of short-circuit paths, current data, positive/negative result, recursion, cycles, or page position
- [x] 5.2 Implement fast canonical proof maps from schema/relation mutation identities and prohibit transaction-number equality as the proof
- [x] 5.3 Retain canonical full-content proof as a runtime-safe mode and differential oracle
- [x] 5.4 Add explicit proof-mode/coherence-authority configuration so unknown custom writers default to content proof or no completed-answer cache
- [x] 5.5 Key schema catalogs, permission paths, dependency closures, and direct-grant memos by selected source/schema proof instead of listener counts or client-lifetime latching
- [x] 5.6 Define and validate adapter determinism/configuration fingerprints plus mutable identity/caveat/custom-data dependency proofs
- [x] 5.7 Add generated mutation tests covering direct, arrow, nested permission, recursive, cyclic, wildcard, forward/reverse lookup, count, positive, and negative dependency completeness
- [x] 5.8 Include canonical public and selected internal query identities plus result/ordering identity-boundary proofs, or validate an adapter's immutable-identity contract

## 6. Completed-Answer Cache

- [x] 6.1 Version the entry format and full semantic lookup key with source/branch scope, operation, complete internal query, engine/adapter/configuration fingerprints, pagination state, and result kind
- [x] 6.2 Store authenticated computation anchor/locator, validation telemetry, dependency scope/proof, and portable result value
- [x] 6.3 Enforce forward-only proof lifting by requiring the selected snapshot to contain the computation anchor before comparing complete proofs
- [x] 6.4 Revalidate every cross-revision hit and prevent `validated-at` from acting as a lease
- [x] 6.5 Reject provider-forged values, future/sibling entries, stale/malformed pointers, old formats, scope collisions, and proof/key mismatches as misses
- [x] 6.6 Make provider/proof failures evaluate uncached on the already selected snapshot while consistency failures remain request errors
- [x] 6.7 Add monotonic/CAS validation-metadata updates where supported and prove older provider races cannot affect correctness
- [x] 6.8 Add cache metrics for exact hit, causal proof lift, content proof, mutation proof, proof mismatch, future-history rejection, unauthenticated entry, no-proof bypass, and provider failure
- [x] 6.9 Authenticate and fully scope shared recursive-continuation, frontier, schema/path, and pointer entries, and make every cache miss recomputable from cursor plus selected snapshot

## 7. Datomic Integration

- [x] 7.1 Adapt native database identity, basis order hints/locators, mutation-anchor lookup, and committed graph-head extraction to adapter v3
- [x] 7.2 Implement bounded zero-argument `d/sync` for authoritative `:fully-consistent` selection
- [x] 7.3 Implement bounded two-argument `d/sync` for at-least order waiting followed by mandatory mutation-anchor verification
- [x] 7.4 Implement verified exact `d/as-of` selection and reject restored/divergent basis collisions whose graph identity does not match
- [x] 7.5 Migrate existing transaction-ref watermarks to v3 mutation identities while retaining old stamps only for diagnostics/linear-history optimization
- [x] 7.6 Add Datomic tests for zero-arg head barriers, peer lag, cross-connection writes, restore-style missing anchors, exact identity, relevant/unrelated churn, and timeout paths

## 8. DataScript Integration

- [x] 8.1 Remove listener counters from tokens, freshness, schema correctness, and completed-answer validity
- [x] 8.2 Implement durable causal-family scope, `:max-tx` order hints, mutation-anchor lookup, committed graph head, and current local selection
- [x] 8.3 Implement bounded at-least waiting on the supplied connection followed by mandatory anchor verification and no replication claim
- [x] 8.4 Detect `reset-conn!`/cloned-value numeric collisions through missing anchors and unique dependency identities
- [x] 8.5 Implement optional bounded exact immutable-DB registry handles and reject numeric equality as exact identity
- [x] 8.6 Add equivalent CLJ/CLJS tests for process restart, independent same-base divergence, pre/post-token cloning, reset, anchor expiry, listener independence, cache lifting, and exact-registry eviction

## 9. Datahike Integration

- [x] 9.1 Remove listener counters from tokens, freshness, schema correctness, and completed-answer validity
- [x] 9.2 Implement source scope from stable store identity and branch, `:max-tx` polling hint, mutation-anchor lookup, graph head, commit locator, and parent metadata
- [x] 9.3 Implement authoritative branch-head capability detection for direct stores and reject fully-consistent on lagging replicated/streaming sources without a barrier
- [x] 9.4 Implement bounded at-least branch refresh with mutation-anchor postcondition and no reliance on numeric `:max-tx` dominance
- [x] 9.5 Implement verified `commit-as-db` exact reconstruction, capability-gated temporal fallback, and expiry after commit/anchor collection
- [x] 9.6 Add tests for normal writer/reader flow, remote/lagging source capability, branch creation, merge, `force-branch!` rewind, equal-max-tx divergence, commit graph on/off, history on/off, and garbage collection

## 10. Proof-Equivalent Pagination

- [x] 10.1 Bind authenticated cursors to source/branch scope, graph anchor, exact locator, complete query/config fingerprints, dependency scope/proof digest, total-order position, and expiry, with optional advertised encryption
- [x] 10.2 Define and test a backend-neutral stable total result order with complete tie-breakers
- [x] 10.3 Continue on a newly selected snapshot when its complete dependency proof equals the cursor proof
- [x] 10.4 Fall back to the verified original exact snapshot only after proof mismatch and only when no newer freshness floor conflicts
- [x] 10.5 Permit a newer at-least token when its qualifying snapshot proof equals the cursor proof; otherwise return a typed restart conflict
- [x] 10.6 Distinguish invalid, expired, stale-proof, snapshot-expired, and freshness-conflict cursor failures
- [x] 10.7 Add generated insert/delete/revoke/unrelated-mutation tests before and after page boundaries and compare concatenated pages with the oracle
- [x] 10.8 Bound cursor proof size using domain-separated canonical digests, rederive full dependency closure on continuation, and prohibit truncation

## 11. Verification, Performance, and Migration

- [x] 11.1 Run all non-benchmark core, Datomic, Datahike, and DataScript tests through nREPL and resolve failures without weakening advertised postconditions
- [x] 11.2 Run fault-injection tests for cache corruption/poisoning, malformed tokens, key rotation, provider races, journal expiry, future candidates, writer retries, and backend head movement
- [x] 11.3 Benchmark mutation-identity proof, full-content proof, global invalidation, and no-cache under unrelated/relevant churn without using performance results to weaken correctness
- [x] 11.4 Document causal-token lifetime/retention, proof modes, custom-writer authority, authoritative-head configurations, DataScript limitations, and Datahike branch/commit behavior
- [x] 11.5 Document breaking token/cursor/cache formats, writer-authority requirements, retention, key rotation, failure containment, and operational diagnostics
- [x] 11.6 Verify isolated module dependencies, CLJ/CLJS portable formats, final capability matrix, strict OpenSpec validation, and the complete reference-model property suite
