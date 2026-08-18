# Proposal: Fix Per-Request Overheads (sealed-plan thrash, seal cost, schema reads, kernel checks)

## Why

Profiling the live `eacl-datomic-solidjs` demo (110k servers, 59 accounts,
`datomic:dev://`, warm peer) on 2026-08-18 showed `check-permission` at
**6.0 ms** and `lookup-resources :first 20` at **5.6 ms** per cache miss,
while the traversal itself cost 30–400 µs. Stack sampling attributed the
rest to four per-request overheads in the client and engine shells:

1. **Sealed-plan cache thrash (~80 % of a `can?` miss).**
   `eacl.engine.v8/stable-plan` keyed its global cache by source lifecycle
   and native revision; `eacl.datomic.impl/with-request-engine` built its
   engine adapter with no options, so `snapshot-adapter` minted a random
   lifecycle per call — every request re-sealed the plan (128 cache entries:
   one database, one revision, one root, 128 lifecycles).
2. **`seal-plan` cost.** `assign-ordinals` sorted rules with
   `sort-by secure-format/encode-canonical`, re-encoding and re-validating
   both operands on every comparison: 368 canonical encodings (≈24 µs each)
   for a 16-rule plan — ~4.3 ms per seal.
3. **`read-schema` on every request (~35–40 % of what remained).** Every
   `check-permission`, lookup, count and expansion enumerated all relation
   and permission entities (`d/q` + `pull`) merely to validate three
   keywords, on hits and misses alike.
4. **`verified-kernel/kernel?`** ran `satisfies?` on an `extend`ed class
   (~10 µs) three or four times per request.

## What Changes

- **Plan cache key** = `[backend-id source-scope source-lifecycle
  (schema-identity | exact-basis) root]`: the schema-generation identity the
  bound schema cache already knows replaces the native revision (a plan is a
  pure function of the schema definitions; every supported schema write
  advances the generation in the same transaction). No proof-frame
  operation is added — the client's generation cache carries the identity,
  and the Datomic raw facade passes its own direct schema-version read as
  `:schema-identity` to `request-schema-cache`. The lifecycle stays in the
  key (distinct test stores may alias a source scope); the raw facade mints
  its engine adapters with one process-stable lifecycle instead of a random
  one per call. `expire-plans!` is called by every client's `expire-cache!`.
- **`seal-plan` encodes each rule/node once** (`sort-by-canonical`); the
  composite fingerprint and plan are byte-identical (asserted).
- **Schema validation on the miss path**: request validation reads the
  parsed schema from the client's per-generation schema cache
  (`:parsed-schema`, read once per generation) inside the cached
  computation; the unknown-object short-circuits validate the same way; a
  cache hit reads nothing. Unstamped databases (no generation) keep reading
  directly and latch nothing. Every typed error is unchanged.
- **`kernel?`** memoizes positive answers per class (JVM).

## Evidence (live demo, warm peer, medians)

| operation | before | after |
|---|---|---|
| `check-permission` miss (denied) | 6.0 ms | 142 µs |
| `check-permission` hit | 383 µs | ~80–100 µs |
| `lookup-resources :first 20` miss | 5.6 ms | ~490 µs |
| `lookup-resources` hit | 614 µs | ~250–330 µs |
| `seal-plan` (16 rules) | 4.3 ms | ~1 ms (once per schema generation) |

In-memory fixture: `can?` miss 985 → 97 µs, page miss 2,046 → 388 µs.
Full test battery (663 tests / 26,879 assertions) green after the plan-key
test (`eacl.backend.v8-test`) was aligned with the new keying rule.

## Non-Goals

- Reducer constant factor (≈3 µs per transition; an exhaustive 110k count
  spends 661k transitions ≈ 2 s) and union-arm subsumption in the sealed
  plan — separate engine work.
- Any change to the answer cache, cursors, or proof frames.
