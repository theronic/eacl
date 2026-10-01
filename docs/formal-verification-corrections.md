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
- **Correction:** `basis-cache-for-option` rejects an existing native cache
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
  The generic reader and snapshot wrappers also validate the descriptor before
  protocol dispatch. Only omission and nil default; false yields
  `:eacl/unsupported-consistency`. Dafny models the public input distinction,
  and regressions cover the bundled backends plus the shared extension
  boundary.

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

### EACL-FORMAL-068 — host-equal public IDs crossed semantic identity boundaries

- **Affected:** ordered batch checks, relationship writes, and public object
  cleanup with custom external-ID codecs; the implicit native-EID fallback also
  affected numeric public IDs.
- **Impact:** a representation-distinct object could reuse another object's
  memoized allow/deny decision, a relationship update could be discarded during
  pre-resolution coalescing, public cleanup could target the wrong native
  entity, and resolver infrastructure failure could be hidden as not-found.
- **Root cause:** the formal abstraction began with typed semantic identity and
  omitted unresolved host values. Clojure equality can equate values such as a
  list and vector that a deterministic injective codec resolves differently.
- **Correction:** public batch memoization admits only canonical identity
  representations under the immutable/injective adapter contract; relationship
  updates resolve before coalescing; public and native-EID deletion are separate
  entry points; resolver failures propagate. `PublicIdentityBoundary.dfy`, two
  executed mutants, and the shared backend regressions close the omitted
  boundary.

### EACL-FORMAL-069 — public request shapes could fail open

- **Affected:** scalar reads, counts, schema reads, relationship revocation and
  transaction planning, plus object cleanup on the shared client path.
- **Impact:** a consistency typo could silently use the default consistency; a
  reserved live-page request could be accepted but ignored; the backend-only
  empty-schema escape hatch crossed the public boundary; a
  misspelled expiry could create a permanent relationship; passing a bare
  relationship record rather than the valid singleton collection
  `[relationship]`, or passing a malformed plan, could report success with no
  updates; and a nil-ID cleanup could report success with no work while a mixed
  public/native object envelope could delete another entity's relationships.
- **Root cause:** the formal abstraction began after open Clojure maps had
  already been destructured. Unknown keys, collection shape, and mutually
  exclusive identity selectors were therefore outside the model.
- **Correction:** public requests and endpoints are closed before dispatch;
  unsupported page-basis values and the backend-only empty-schema option are
  rejected;
  mutation batches must be explicit sequential collections; the single-write
  helper rejects unknown fields; public and native object deletion cannot
  share an envelope; shared client protocol methods
  repeat mutation validation. `PublicRequestBoundary.dfy`, six executed
  mutants, and public plus real-backend regressions close the boundary.

### EACL-FORMAL-070 — snapshot protocol options could replace trusted identity resolution

- **Affected:** retained snapshots on every shared-orchestration backend.
- **Impact:** a direct protocol caller could replace the public-ID resolver and
  make one user's authorization check run as another user. A real DataScript
  witness changed Alice's denied check into Bob's grant.
- **Root cause:** the public wrapper supplied an empty internal options map,
  but the protocol method accepted arbitrary options and merged them over the
  client runtime before snapshot selection.
- **Correction:** the shared snapshot protocol accepts only an exactly empty
  options map; consistency remains the sole caller choice. Trusted codecs,
  clocks, caches, and security configuration are captured from the client.
  `SnapshotOptionBoundary.dfy`, an executed mutant, and the real-backend
  regression close the omitted boundary.

### EACL-FORMAL-071 — boolean false was confused with an omitted value

- **Affected:** relationship inspection and cursor resume for custom public-ID
  codecs that admit boolean `false`; request evaluation, timeout, and
  cancellation controls supplied as explicit `false`.
- **Impact:** a relationship that continued to grant access could disappear
  from a subject-filtered audit or a paginated inspection, interfering with
  discovery and revocation. False execution controls could silently select
  defaults or disable cancellation rather than being rejected.
- **Root cause:** public validation used non-nil value presence, but later host
  branches used Clojure truthiness. The formal identity and request models had
  not represented this distinction.
- **Correction:** every public identity branch now uses non-nil presence;
  malformed false controls receive typed rejection. `PublicIdentityBoundary.dfy`
  and `PublicRequestBoundary.dfy` model the counterexamples and corrected laws;
  five executed mutants plus a real DataScript regression cover authorization,
  inspection, relationship continuation, and authorization-result continuation.

### EACL-FORMAL-072 — numeric public IDs crossed into the native-EID domain

- **Affected:** applications whose custom identity codec admits numeric public
  IDs, when using paginated relationship or authorization lookup APIs or
  permission-tree expansion. String/UUID-only public-ID applications are not
  affected.
- **Impact:** an attacker able to choose public account ID `0` could make an
  administrator's otherwise valid paginated relationship audit repeat earlier
  pages and never reach later grants. That can obstruct discovery and
  revocation. A numeric permission-tree root could also resolve as the wrong
  database identity. The cursor is authenticated; the attacker does not forge
  it—the chosen public ID reaches the vulnerable path during normal paging.
- **Root cause:** one adapter callback served both public identities and
  already-resolved native entity IDs. Its numeric fast path had no way to know
  which trust domain supplied the value, and the formal identity model did not
  represent that distinction.
- **Correction:** the existing `:object-id->internal` operation now has one
  meaning: apply the configured application-ID codec. EACL invokes it once at
  request or cursor ingress, then sends the resolved EID through explicit
  internal engine entry points without another conversion. No new adapter or
  client option is required. `PublicIdentityBoundary.dfy` types the two stages
  separately and proves both the old counterexample and the conversion-once
  law; an executed mutant and a real DataScript three-page regression bind the
  model to production.

### EACL-FORMAL-073 — unsupported `subject#relation` input became a base object

- **Affected:** applications that accept SpiceDB-shaped object references from
  requests, migration code, or another policy service and pass those maps to
  EACL. Ordinary documented EACL objects, whose `:relation` is nil, are not
  affected.
- **Impact:** EACL could grant a request for an unsupported userset such as
  `user:grandmother-caregiver#member` whenever the different base object
  `user:grandmother-caregiver` was authorized. The caller asked about semantics
  EACL does not implement, but received the base object's answer instead of an
  error. A malicious caller can exploit this when the surrounding application
  lets them supply the forwarded object reference.
- **Root cause:** public object validation admitted keyword `:relation` values,
  while endpoint resolution deliberately used only `:type` and `:id`. The
  documented unsupported feature was therefore erased at the trust boundary.
- **Correction:** every public scalar, batch, lookup, scan, permission-tree,
  mutation, and deletion boundary rejects a non-nil endpoint `:relation` before
  dispatch. `PublicRequestBoundary.dfy` models both the old downgrade and the
  fail-closed rule; an executed mutant and real DataScript regression bind it
  to production.

### EACL-FORMAL-074 — incomplete authorization demands reached readers

- **Affected:** scalar permission checks, lookups, counts, permission-tree
  expansion, relationship scans, and the generic batch wrapper. Bundled clients
  usually rejected or denied these requests later, but third-party or remote
  protocol readers received incomplete or partially validated shapes.
- **Impact:** an application that builds a request map from attacker-controlled
  optional fields could omit `:subject`, `:resource`, or `:permission`. EACL's
  public wrapper would dispatch the incomplete demand. A reader that assigns a
  default principal or wildcard meaning to the missing value could grant it;
  bundled backends instead failed closed, but only after avoidable snapshot or
  schema work.
- **Root cause:** the closed-map validation modeled unknown keys but did not
  require the operation's mandatory keys. The formal request model made the
  same omission and therefore called an empty or incomplete known-key set
  accepted.
- **Correction:** each public read now requires its complete endpoint and
  schema-name fields before reader dispatch. Batch entries, relationship-scan
  anchors and authorization clauses, and nested lookup relationship clauses
  receive their full shared validation at the same boundary.
  `PublicRequestBoundary.dfy` proves incomplete known-key maps are rejected; an
  executed mutant and protocol-level regressions bind that rule to production.

### EACL-FORMAL-075 — nested relationship updates bypassed the shared wrapper

- **Affected:** third-party or remote `IAuthorizationWriter` and
  `IRelationshipPlanning` implementations reached through plural relationship
  write or transaction-planning helpers. Bundled shared clients already
  repeated nested validation.
- **Impact:** a payload such as `:valid-until-mss` could cross the public wrapper
  inside a nested relationship. A writer that ignored the unknown field could
  create permanent access instead of the caller's intended expiry. Malformed
  endpoints or unsupported subject relations could likewise reach that
  implementation. Exploitation requires an application to expose relationship
  mutation input to an attacker and use such an extension writer; the bundled
  backends failed closed.
- **Root cause:** the generic wrapper validated only that `:updates` was
  sequential. Backend-neutral nested update validation happened later only in
  the shared client implementation, while the formal mutation model treated
  every sequential collection as valid.
- **Correction:** relationship writes, preparation, and snapshot transaction
  planning validate every nested operation, required endpoint, relation, and
  qualifier before protocol dispatch. `PublicRequestBoundary.dfy` both requires
  every mandatory nested field and distinguishes a valid sequential batch from
  one containing malformed updates; an executed mutant and protocol-level
  regression bind the rule to production.

### EACL-FORMAL-076 — issued tokens and cursors had several accepted spellings

- **Affected:** `eacl.secure-format/decode-canonical` and `b64url-decode` on CLJ
  and CLJS, and through them Zed tokens, cache-entry envelopes, `eacl_c7_`
  cursors, standalone `eacl_sd2.` page tokens, and stored canonical values, on
  every backend.
- **Impact:** an issued token or cursor could be respelled and still
  authenticate, because authentication covers decoded values. The payload could
  not be forged, but token and cursor strings were not unique. In the canonical
  EDN layer, text appended inside a Zed token's envelope after an early `]` was
  never read; commas, extra whitespace, another member order, list syntax,
  `1N`, `+1`, `010`, `0x10`, string escapes, and metadata were also accepted,
  and a character literal outside a string desynchronised the hidden-input
  scanner, which then admitted a following discard, comment, or namespaced map.
  In the Base64URL layer, `=` padding and nonzero unused bits in the last
  character were accepted, and in JavaScript also whitespace. Cursors, which
  the eacl-rust report recorded as unaffected, were malleable in their tag
  segment.
- **Correction:** each layer accepts only the canonical spelling. The EDN input
  must equal the canonical rendering of the decoded value, or decoding fails
  with `:noncanonical`, and the hidden-input scanner rejects metadata and
  character literals outside strings. `b64url-decode` accepts only the
  unpadded spelling `b64url-encode` emits and fails with `:malformed-base64`
  otherwise.
- **Migration:** none. EACL emits only canonical text and this change leaves
  the renderer's output unchanged, so every value v8.0.0 stored and every token
  or cursor it issued still decodes.

### EACL-FORMAL-077 — page-request errors echoed the decrypted cursor

- **Affected:** public `lookup-resources` and `lookup-subjects` on every
  backend.
- **Impact:** a request combining a valid cursor with a malformed page shape,
  such as `{:first 1 :before cursor}`, failed with
  `:eacl.pagination/invalid-page-request` whose data held the decrypted cursor
  edge: internal coordinates, plan fingerprints and, on operator routes, cover
  fingerprints and semantic scope. A lookup whose anchor does not resolve also
  returned an empty page before its page keys were checked, so `{:first 0}`
  was accepted for it.
- **Correction:** both lookups validate the page keys of the caller's query,
  with the same generated page decision, before snapshot selection and cursor
  decoding. Error data echoes the caller's cursor strings.
  `PageWindow.BoundaryValuesDoNotAffectNormalization` proves that the decision
  depends only on boundary presence, so the engine's later check of the decoded
  query agrees.
- **Migration:** a request with both a malformed page shape and an invalid
  cursor now reports the page shape first. A malformed page request for an
  unknown anchor now fails instead of returning an empty page.

### EACL-FORMAL-078 — `maximum-entries` did not bound validation work

- **Affected:** `encode-canonical`, `canonicalize`, `capture-portable`, and the
  digests built on them, on CLJ and CLJS.
- **Impact:** availability. A collection larger than `:maximum-entries` was
  walked completely before it was rejected, and an unbounded lazy sequence in
  an application-supplied value never returned.
- **Correction:** validation carries one running entry count through the whole
  value and checks it before each value is examined, so it visits at most
  `:maximum-entries` + 1 values.
- **Migration:** a value that violates several bounds may now report
  `:too-many-entries` where it previously reported the later violation.

### EACL-FORMAL-079 — canonicalization merged members that render alike

- **Affected:** `canonicalize` and `encode-canonical` on CLJ and CLJS.
- **Impact:** a map or set holding a record and a map with the same fields
  lost one member in `canonicalize`, while `encode-canonical` rendered both and
  produced text that does not decode.
- **Correction:** such a collection fails with `:duplicate-key` or
  `:duplicate-member`. A record on its own still encodes as its field map;
  that projection is intended, and identity boundaries that need the
  distinction already reject records.
- **Migration:** none for portable values.

### EACL-FORMAL-080 — partial relationship scans ignored the qualifier component

- **Affected:** `read-relationships` without `:subject/id` and `:resource/id`
  over a Relation holding a caveated or expiring row, on every backend:
  ordinary pages and the `:expiry-active` and `:authorization` windows.
  Anchored reads and permission evaluation were unaffected.
- **Impact:** omission, duplication, and availability. A continued page could
  drop a row it had not returned or repeat one it had. A filtered window could
  re-examine the same rows until its candidate window was exhausted and then
  resume from the same position on every later page, so a walk never ended.
- **Root cause:** partial scans read AVET values
  `[p0 p1 p2 primary qualifier]`, which order one primary endpoint's rows by
  qualifier (plain rows first) before owner. The cursor edge and its comparator
  used only the primary and owner eids, and the seek positioned only by
  primary.
- **Correction:** `physical-compare` follows the index order (primary,
  qualifier, owner); a qualified row's cursor edge records its qualifier eid;
  each backend resumes at the boundary row's exact position
  (`endpoint-pair/resume-bound`); and a scan whose rows do not advance strictly
  in that order fails closed with `:eacl/backend-contract-violation`
  `:strict-order`. Datalevin applies an AVE seek's entity component to every
  datom it returns, not only to the first value, so its resumed partial scans
  also dropped plain rows of later primary groups; it now positions inside the
  boundary row's value group and continues from the adjacent value. Found by
  the eacl-rust port (EACL-RS-004). A shared contract replays the minimized
  walks, a plain cross-group walk, and a seeded sweep on every backend, and two
  qualified production mutation controls cover the cursor and the comparator.

### EACL-FORMAL-081 — permission trees failed on qualified relationships

- **Affected:** `expand-permission-tree` on every backend whenever the
  traversal scanned a Relation holding a caveated or expiring Relationship,
  live or expired, in a leaf or in an arrow's source Relation.
- **Impact:** availability. The request failed with
  `:eacl.permission-tree/adapter-contract-violation`
  `{:reason :adapter-operation-failed}`.
- **Root cause:** the tree was the one v8 serving scan that did not request
  qualified results. Each backend's endpoint scan rejects a stored value
  carrying a qualifier reference with `:eacl/unsupported-qualifier`, which
  `adapter-call!` redacted into a contract violation.
- **Correction:** the tree scans compact qualified edges and lists every
  stored Relationship. The leaf subject or arrow child node reached through a
  qualified Relationship carries its `:caveat`, `:caveat-context` (omitted
  when empty), and `:valid-until-ms`, decoded by the `read-relationships`
  inspector. Nothing is evaluated, so the tree stays a function of the
  selected basis, as its answer key already assumes. Decode faults are typed
  and redacted. Known as PR206-F1 for Caveats; the eacl-rust port found the
  expiring case (EACL-RS-005).

### EACL-FORMAL-085 — Datalevin Relation streams dropped rows after the first batch

- **Affected:** Datalevin schema replacement that removes a Caveat or the
  unqualified alternative from a Relation with more than 1,024 stored rows.
- **Impact:** fail-open schema guard. `write-schema!` could admit the change
  while stored Relationships still used the removed alternative. Afterwards
  `can?` denied those Relationships, `read-relationships` on their resources
  failed with `:eacl.qualifier/invalid`, and restoring the alternative failed
  the same way.
- **Root cause:** `qualified-relation-datoms` started each batch after the
  first with a seek carrying the previous batch's last value and owner.
  Datalevin applies a seek's entity component to every datom it returns, not
  only to the first value, so the stream dropped every later row whose owner
  eid was smaller than the boundary owner.
- **Correction:** later batches position by owner only inside the boundary
  row's value group, then continue from the adjacent value with no entity
  component. Datalevin module tests compare the stream with the unpaged AVE
  index across three batch boundary shapes and reject the schema replacement
  in the minimized layout. Datomic, DataScript and Datahike were unaffected.

The authoritative minimized fixtures and closing evidence are under
`formal/counterexamples/`. Run them with
`EACL_NREPL_PORT=<dev-port> bin/formal counterexample-replay`.
