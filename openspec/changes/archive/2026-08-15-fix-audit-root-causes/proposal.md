# Proposal: fix-audit-root-causes

## Why

The 2026-07-06 full source audit ([docs/reports/2026-07-06-eacl-full-source-audit.md](../../../docs/reports/2026-07-06-eacl-full-source-audit.md)) verified 15 bugs against a live REPL, none covered by the (green) test suite. Two are critical data-loss/correctness bugs (`write-schema!` silently wipes the schema on parse failure; permissions silently fail for legal type names), and four are high severity (stale authorization from cache, a relationship data leak, silently ignored client config, unbounded cursors). The engines themselves verified sound — the defects are root-caused in five edge layers: schema-write pipeline trust, one bounded index scan, cache keying, nil-tolerant ID resolution, and nil-on-failure cursor decoding. This change fixes those root causes rather than patching symptoms.

## What Changes

- **Schema-write safety**: `write-schema!` rejects unparseable schema (instaparse failure objects currently flow through as an *empty* schema and retract everything), supports `//` and `/* */` comments, rejects duplicate definitions/relations instead of last-wins, validates arrows against *all* subject types of a relation (currently declaration-order-dependent), flattens parenthesized union expressions instead of crashing, and refuses full-schema retraction without explicit opt-in.
- **Permission-path resolution**: replace the `:a`–`:z` bounded `d/index-range` in `relation-datoms` with a prefix scan so relations with any legal subject-type keyword (uppercase, `z`-prefixed, namespaced) participate in permission evaluation; make cache invalidation *derived* instead of signaled — cache keys carry a digest of the schema's visible history read from the db value being queried, so every schema mutation (write-schema!, programmatic transaction, retraction, excision) invalidates automatically on every peer, for as-of views, and for speculative `d/with` dbs (currently the caches are keyed by the immutable `(.id db)` and only evicted by same-JVM `write-schema!` — revoked permissions keep granting access on other peers and after programmatic schema writes).
- **Strict object-ID resolution**: **BREAKING** — reads (`read-relationships`, `lookup-resources`, `lookup-subjects`, counts) with a nonexistent object ID return *empty* results (SpiceDB-compatible, consistent with `can?` → `false`) instead of today's mix of return-all-relationships (the leak), `AssertionError`s, and `false`; relationship writes validate both endpoints resolve and throw typed errors naming the missing object instead of raw Datomic `not-an-entity` errors; `impl/tx-relationship` requires explicit opt-in (`:allow-tempids? true`) to treat unresolvable strings as tempids instead of silently minting ghost entities; `make-client` accepts the README-documented `:entid->object-id` key and **BREAKING** — throws on unrecognized option keys (previously silently ignored, so typo'd ID config fell back to `:eacl/id`).
- **Cursor token handling**: **BREAKING** — expired or undecodable cursor tokens throw a typed error instead of decoding to `nil` and silently restarting pagination at page 1; cursor TTL becomes configurable via `make-client` opts (the existing `ttl-seconds` parameter is currently dead code); cursors embed a permission-path fingerprint so a schema change mid-pagination fails loudly instead of silently mis-skipping.
- **API error contract**: implement the declared-but-missing `write-relationship!`/`delete-relationship!` protocol methods (currently `AbstractMethodError`); replace `assert`-based input validation in the client layer with typed `ex-info` errors (asserts vanish under `*assert*` false); remove the vacuous type assertion in `count-resources`.
- **Housekeeping** (no spec impact): delete dead v6 namespaces (`eacl.datomic.rules*`, `eacl.datomic.impl.datalog`, `base/Relationship`) and commented-out root scripts; fix the README ID-configuration and relationship-transaction examples; rename `eacl.datomic.parser_test` → `eacl.datomic.parser-test` so it runs under `clj -X:test`; fix test typos (`[:eacl/id "user2"]`, empty `testing` block); add a differential property test (lookup-resources set == can? ground truth == paginated union == count) codifying the audit's cross-check.

**Explicitly out of scope**: the v3 recursive-cursor growth/eid-leak redesign (audit §6). Its root cause is architectural (the cursor *is* the recursion state) and interacts with SpiceDB-compat goals; it needs its own design change. This change only adds the cursor-fingerprint guard and documents the growth characteristic.

## Capabilities

### New Capabilities

- `schema-write-safety`: parsing, validating, and transacting SpiceDB schema strings without silent data loss — parse-failure rejection, comment support, duplicate rejection, order-independent arrow validation, paren-expression support, full-retraction guard.
- `permission-path-resolution`: resolving schema edges (relations/permissions) for permission evaluation — correct for all legal keyword type names, and cache-fresh across schema changes on every peer.
- `object-id-resolution`: the external-ID ↔ internal-eid boundary of the Datomic client — strict resolution with typed errors, opt-in tempids, validated client configuration.
- `cursor-token-handling`: opaque pagination cursor encoding/decoding — typed failures, configurable TTL, schema-change detection.
- `api-error-contract`: `IAuthorization` protocol completeness and typed error behavior for invalid inputs on the Datomic client.

### Modified Capabilities

<!-- none — openspec/specs/ is empty; all capabilities above are introduced by this change -->

## Impact

- **Code**: `src/eacl/spicedb/parser.clj` (parse pipeline, grammar, validation), `src/eacl/datomic/schema.clj` (write-schema!, reference validation), `src/eacl/datomic/impl/indexed.clj` (relation-datoms, cache keys/eviction), `src/eacl/datomic/impl.clj` (tx-relationship, read-relationships), `src/eacl/datomic/core.clj` (make-client, cursor tokens, error contract, protocol methods), `src/eacl/core.clj` (protocol docstrings), deletions of `src/eacl/datomic/rules*.clj`, `src/eacl/datomic/impl/datalog.clj`, parts of `src/eacl/datomic/impl/base.clj`.
- **APIs**: behavioral changes marked **BREAKING** above are all silent-failure → loud-failure conversions; wire formats (cursor token prefix `eacl1_`, relationship tuples, schema entities) are unchanged. Existing valid configurations and schemas continue to work unmodified.
- **Consumers**: CloudAfrica production and side projects must review: (1) any code depending on `read-relationships` returning results for unknown IDs, (2) `make-client` opts maps for unknown/typo'd keys, (3) pagination loops for expired-cursor handling, (4) direct `impl/tx-relationship` callers relying on tempid pass-through (test fixtures in this repo are updated as part of the change).
- **Tests**: new coverage for every fixed path (parse failure, adversarial type names, cache invalidation, nonexistent IDs, cursor expiry/tamper), plus the differential property test; existing suite must stay green.
- **Docs**: README ID-configuration and quickstart sections corrected; audit report cross-referenced from fixes.
