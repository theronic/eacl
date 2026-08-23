# Formal-verification behavior corrections

> Note (2026-08-15): entries EACL-FORMAL-055, -066 and -067 describe the retired generated indexed traversal (reverse indexed state machine, 64-command speculative scan waves, `IndexedBatching.RenderScanBatchSize`). The stable-discovery engine replaced those mechanisms on 2026-08-14 — one released value per reducer transition, no scan waves — and retains the three counterexamples only as replayed regressions against the new engine (`formal/counterexamples/`, `counterexample_replay_test`).

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
- **Migration:** v8 release-candidate portable cursors are replaced by the
  compact authenticated-encryption `eacl_c5_` format; no compatibility guarantee applies before the
  first stable v8 release. Clients may see lower work and fewer limit errors.

## EACL-FORMAL-003 — authenticated cache loses logical admission kind

- **Affected:** Datomic authenticated cache provider.
- **Impact:** cache admission/weight policies could be bypassed, increasing
  retained memory; authenticated values were not forged.
- **Correction:** the provider boundary preserves the semantic cache kind.
- **Migration:** custom providers should accept the logical kind rather than
  assuming the legacy `:authenticated-v3` bucket.

## EACL-FORMAL-004 — proofless cursor silently lifts across graphs

- **Affected:** shared Relay cursor handling with proof mode disabled; Datomic
  regression witness.
- **Impact:** a page walk could change graphs without an explicit recovery
  decision or client-visible recovery marker, producing unexplained omissions
  or new items.
- **Correction:** every cursor is authenticated to its complete semantic query
  before its resume state can influence traversal. Current continuation
  requires an equal complete dependency/order proof. A changed proof requires
  verified exact-snapshot reconstruction and never selects current.
- **Migration:** rebase/restart recovery markers are removed. A changed proof
  returns an exact historical page on history-capable backends, or a typed
  stale-cursor/snapshot-expired/consistency-conflict error. DataScript is
  current-basis-only and therefore fails a relevant changed-proof continuation.

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

### EACL-FORMAL-052 — map `can?` false-consistency weakening

- **Affected:** Datomic, Datahike, and DataScript public map-form `can?`.
- **Impact:** an explicit malformed `false` consistency value silently ran as
  the then-current default mode, while the positional arity and shared
  descriptor rejected the same value.
- **Correction:** public map arities forward the raw value to the descriptor.
  Only omission and nil default; false yields
  `:eacl/unsupported-consistency`. Dafny models the public input distinction,
  and one regression per backend checks the production boundary.

### EACL-FORMAL-053 — unknown consistency descriptor fields

- **Affected:** Datomic, Datahike, and DataScript token consistency requests on
  both Clojure and ClojureScript.
- **Impact:** a descriptor with a valid at-least-as-fresh or exact mode and
  token plus unknown fields was accepted even though the formal public-input
  model classified malformed descriptors as rejected and the boundary
  documentation promised unknown-field rejection.
- **Correction:** token descriptors must contain exactly
  `:consistency/mode` and `:zed/token`. The implementation checks map
  cardinality and membership without allocating a key set, rejects unknown
  fields with `:eacl/unsupported-consistency`, and is closed by
  `ConsistencyDecision.MalformedConsistencyCannotBeAccepted` plus the shared
  CLJ/CLJS descriptor regression.

### EACL-FORMAL-054 — immutable DataScript authoritative-head capability

- **Affected:** DataScript snapshot adapters created without a live
  connection.
- **Impact:** `:fully-consistent` could silently select the captured immutable
  DB rather than a connection head.
- **Correction:** connectionless adapters no longer advertise
  `:fully-consistent`; managed clients with a live connection retain it.

### EACL-FORMAL-055 — generated point checks traversed from the broad endpoint

- **Affected:** generated-authoritative `can?` on Datomic, Datahike, and
  DataScript.
- **Impact:** point-check work could grow with every resource reachable from a
  broad subject, even though the request named one concrete resource. The
  release-default multipath check was 5.44× the target-local legacy
  specialization in a same-JVM comparison.
- **Correction:** `can?` now initializes the proved reverse indexed traversal
  at the concrete resource and uses the Boolean renderer to find the requested
  subject. A deterministic gate holds backend/logical work constant across 16
  and 1,040 subject-reachable resources; wall-time remains a separate qualified
  gate. A reverse miss performs an exact direct-tuple probe before a verified
  forward recovery so the accepted raw-EID ghost behavior remains compatible
  when a consumer bypasses the EACL deletion API. Shadow mode compares the
  Boolean result, not the non-equivalent directional work counters.

### EACL-FORMAL-066 — partial fuel-wave rollback could livelock

- **Affected:** generated JVM and portable ClojureScript forward/reverse
  recursive traversal on every backend.
- **Impact:** availability. A broad recursive fan-out could repeat one fuel
  quantum forever, so bounded page/count work failed to reach its sentinel and
  instead ended only at an outer request timeout.
- **Root cause:** fuel exhaustion with a nonempty wave below the 64-command
  maximum returned the quantum's original state and discarded every pending
  scan. The next quantum deterministically recreated the same discarded wave.
- **Correction:** forward and reverse authorities publish every nonempty
  fuel-cut wave from current verified state and yield current state only when
  no scan is pending. Direct low-fuel regressions cover generated Java,
  generated JavaScript, and portable CLJS; the public DataScript regression
  covers broad fan-out with cache enabled and disabled.

### EACL-FORMAL-067 — speculative scan waves changed recursive page order

- **Affected:** generated JVM and portable ClojureScript recursive resource
  and subject pagination on every backend.
- **Impact:** wrong order. Complete result sets remained correct, but changing
  page size could move a resource to another ordinal and make a valid
  authenticated continuation fail as stale.
- **Root cause:** page rendering shared the 64-command speculative scan policy
  used by order-insensitive operations. A short page reached lookahead and
  folded the wave at a different FIFO position than a larger page.
- **Correction:** `IndexedBatching.RenderScanBatchSize` is generated executable
  authority. Every `RenderPage` uses batch size one independent of requested
  size, and the production driver no longer accepts a host batch argument.
  Boolean/count rendering retains batch size 64. Dafny, generated JVM/JS,
  portable CLJS, mutation, and reduced cached/cacheless Datomic controls cover
  the policy and public continuation sequence.

The authoritative minimized fixtures and closing evidence are under
`formal/counterexamples/`. Run them with
`EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`.
