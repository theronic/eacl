# Formal-verification behavior corrections

These changes were found while building the formal semantics, temporal models,
generated differential boundaries, and hostile runtime tests. “Current
worktree at discovery” is the affected development version for all entries;
no verified-release claim existed.

## EACL-FORMAL-001 — benchmark schema initialization

- **Affected:** Datomic benchmark harness.
- **Impact:** availability of verification evidence; the heavy suite failed
  before exercising the engine.
- **Correction:** both benchmark seeders install the EACL schema before client
  construction initializes the mutation journal.
- **Migration:** none for production consumers.

## EACL-FORMAL-002 — unreachable recursive continuation cache

- **Affected:** shared recursive traversal as used by Datomic continuation
  tests.
- **Impact:** excessive replay work and possible limit-exceeded false denial on
  later valid pages; no false grant.
- **Correction:** bounded client-private continuation state is keyed by the
  authenticated complete snapshot/proof identity, with deterministic exact
  replay on miss or eviction.
- **Migration:** existing public cursor format remains valid; clients may see
  lower work and fewer limit errors.

## EACL-FORMAL-003 — authenticated cache loses logical admission kind

- **Affected:** Datomic authenticated cache provider.
- **Impact:** cache admission/weight policies could be bypassed, increasing
  retained memory; authenticated values were not forged.
- **Correction:** the provider boundary preserves the semantic cache kind.
- **Migration:** custom providers should accept the logical kind rather than
  assuming the legacy `:authenticated-v3` bucket.

## EACL-FORMAL-004 — proofless cursor mixes snapshots

- **Affected:** shared Relay cursor handling with proof mode disabled; Datomic
  regression witness.
- **Impact:** a page walk could combine results from different graphs,
  producing omissions or unexpected new items.
- **Correction:** every cursor binds the exact snapshot id and graph head.
  Continuation never rebases onto a newer merely proof-equivalent graph.
- **Migration:** continuation may use retained exact fallback or return a typed
  retention/conflict error instead of silently rebasing.

## EACL-FORMAL-005 — inconsistent cursor expiry boundary

- **Affected:** portable DataScript/Datahike cursors and shared CLJ/CLJS tests.
- **Impact:** one runtime accepted a cursor at its expiration second while
  Datomic rejected it.
- **Correction:** all paths reject at `now >= expires-at` and honor an injected
  deterministic clock after authenticating the token.
- **Migration:** callers must treat `expires-at` as the first invalid second.

## EACL-FORMAL-006 — host-dependent canonical authentication

- **Affected:** shared CLJ/CLJS cursor, cache-entry, and causal-token encoding.
- **Impact:** a token emitted in one runtime could fail authentication in the
  other because JVM namespace-map shorthand changed the signed bytes.
- **Correction:** an explicit portable EDN renderer now controls qualified
  keys, ordering, delimiters, escapes, and collection syntax in both targets.
- **Migration:** newly issued tokens are byte-identical across runtimes.
  Previously issued JVM namespace-map cursor vectors remain readable.

## EACL-FORMAL-007 — CLJS reader errors escape the typed boundary

- **Affected:** shared ClojureScript secure-format decoder.
- **Impact:** hostile duplicate fields and unknown tags failed closed but
  leaked raw reader errors instead of the portable EACL malformed-format type.
- **Correction:** only EACL-authored `ExceptionInfo` values are rethrown;
  host-reader failures normalize to `:eacl.format/invalid` / `:malformed`.
- **Migration:** callers may now reliably handle the same typed error in CLJ
  and CLJS.

## EACL-FORMAL-008 — proof-provider exception aborts cache resolution

- **Affected:** shared cache validation and every adapter/third-party provider
  capable of throwing from schema or relationship proof callbacks.
- **Impact:** authorization availability; a cache-proof outage prevented
  independent recomputation. No cached false grant was returned.
- **Correction:** both proof callbacks run through a fail-closed provider
  wrapper. Failure records telemetry, bypasses cache reuse/admission, and
  returns a freshly computed result.
- **Migration:** proof providers may continue to signal unavailability with nil
  or an exception; both now degrade to uncached evaluation.

## Additional v8 current-cache audit findings

### Datomic stamped-writer mismatch

- **Affected:** first current-cache implementation under managed authority.
- **Impact:** a relationship written with the documented low-level
  `eacl.datomic.impl/tx-relationship` helper could leave an affected managed
  answer reusable.
- **Root cause:** validation read only
  `:eacl.relation/mutation-id`, while the helper updates
  `:eacl/relation-version`.
- **Correction:** Datomic managed stamps use the current relation-version
  datom transaction, with the schema-created mutation datom only as the
  never-written fallback.

### Reusable native cache object

- **Affected:** Datahike/DataScript client option normalization.
- **Impact:** an internal current-generation cache could be deliberately
  supplied to two clients, violating the one-client/one-database ownership
  premise.
- **Correction:** `current-cache-for-option` rejects an existing native cache
  with `:reason :client-private-cache-reuse`; each normalization constructs a
  fresh object. A CLJ/CLJS regression fixes this boundary.

### Disabled-cache work was not eliminated

- **Affected:** Datomic, Datahike, and DataScript authorization paths; public
  Datahike/DataScript operation dispatch.
- **Impact:** globally disabled and request-bypassed evaluation could still
  construct semantic cache keys, capture dependency stamps, calculate snapshot
  identities, invoke the cache resolver, canonicalize results, and construct
  cache envelopes. Datahike/DataScript also discarded request-local
  `:cache? false` on several public operations.
- **Correction:** all three adapters branch directly to engine evaluation
  before cache-strategy work when caching is absent or bypassed. Public
  Datahike/DataScript `can?`, lookup/count, and relationship-read methods now
  validate and honor `:cache?`. Regressions replace the native resolver with a
  throwing function and prove the disabled paths never enter it.

### Formal arrow-rule resource domain

- **Affected:** Dafny semantics only; production traversal already scoped
  permission evaluation by resource type.
- **Impact:** the formal model admitted cross-resource-type arrow grants and
  blocked derivation of the intended dependency frame.
- **Correction:** arrow-relation and arrow-permission rules require the grant
  resource type to equal the rule head resource type. The corrected semantics
  verifies the managed least-fixed-point frame.

The authoritative minimized fixtures and closing evidence are under
`formal/counterexamples/`. Run them with
`EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`.
