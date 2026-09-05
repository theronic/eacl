# Live keyring boundaries and simplicity review

This is the final v9 inventory. The controller and cache additions do not alter
Relationship storage, Caveat evaluation, database selection, or proof identity.

## Ownership and evidence

| Boundary | Owner | Evidence |
|---|---|---|
| Primary/static and dedicated Zed configuration | `eacl.security.configuration/format-scopes`; backend `make-client` APIs | `security.configuration-test`; four backend shared contracts |
| Atomic controller, copied material, closed validation/status/errors | `eacl.security.keyring`; seven `eacl.core` APIs | `security.keyring-test`; constructor and state killed controls |
| One immutable capture, named lookup, domain cache | `security.protocols/KeyringSource`; `secure-format/capture-keyring`, `domain-key` | `security.format-test`; ring-size 1/2/4/16 instrumented work |
| Cursor v6 (`eacl_c6_`) carrying Relay payload v13 | `eacl.cursor`; `eacl.relay` | format tampering, TTL, cached-token, and backend resume contracts |
| Zed-token v4, dedicated scope or explicit primary fallback | `eacl.causal-token`; orchestration issuance and consistency paths | format tests and four backend dedicated-ring tests |
| Authenticated snapshot v1 (`eacl_cache1_`) around snapshot v2 | `eacl.cache`; four backend export/restore APIs | cache archive, malformed input, retired-import, and backend contracts |
| Imported trust and derivative publication | `security.imports`; subproblem cache, continuation, range and rendered-page publication | imported-denotation contract; cache fail-open killed control |
| Continuation and range series; rendered pages | `eacl.continuation`, `client.range-reuse`, `eacl.cache`; orchestration | mismatched key tags, resume contracts, retirement cleanup tests |
| Bounded retired-state cleanup | `security.retention`; cursor codec and orchestration page setup | skipped cleanup, other-controller isolation, racing replacement |
| Lookahead/replay | existing opaque token queue re-enters the public page API | existing lookahead tests and mandatory input-token decoding |

## Public contract

`eacl.core` exports `security-keyring`, `security-keyring?`,
`security-keyring-status`, `replace-security-keyring!`, `add-security-key!`,
`activate-security-key!`, and `retire-security-key!`.

Constructor options are `:keys`, `:active-kid`, and optional lower ceilings
`:max-keys` / `:max-retired-kids`. Hard limits are 64 accepted keys, 65,536
retired IDs, 1,024 encoded bytes per ID, and 4,096 bytes per root. Replacement
requires complete desired keys and active ID plus `:expected-generation`.
Convenience operations retry at most 32 conflicts. Accepted IDs cannot change
material; retired IDs cannot return. Every generation owns a 256-entry derived
cache indexed by generation, ID, domain, and format version.

Status contains exactly generation, active ID, accepted IDs, and retired IDs.
Errors are `:eacl.keyring/invalid` with a closed reason or
`:eacl.keyring/conflict` with safe status. There is no library event callback or
metric label containing application inputs. Applications may emit optional
rotation events from the returned safe status. Raw and encoded secret canaries
are checked in exception chains, printing, status, and diagnostics.

Each primary/dedicated scope accepts either a controller or static key/ring
options. Static configuration creates private controllers. With no dedicated
options, Zed tokens use the primary controller under a distinct domain; any
dedicated options require dedicated material. Mixed sources are rejected.

## Cache and retirement trust

Every externally protected artifact authenticates its key ID. Decoder lookup
selects only that ID and cannot fall back to another root or another scope.
Caller cursors and Zed tokens fail with their existing typed categories and
`:security-key-unavailable`. Optional imported cache data misses and recomputes
against the already selected snapshot.

Private imported values retain a verifying controller/ID wrapper. Local entries
retain their ordinary representation. Consuming an import binds request-local
provenance that prevents derivative answer/denotation/range/continuation/rendered
publication. Export omits imports, preventing re-signing from extending trust.
The host-trusted decoded snapshot API remains compatible.

Retirement changes accepted keys synchronously without inspecting client stores.
The next codec/page use performs bounded targeted cleanup; individual imported
lookups detach unavailable trust. Late publishers can leave unreachable bounded
entries until normal eviction. Cleanup uses expected resident identity, preserves
racing replacements, and never flushes locally computed authorization entries.

## Production simplicity review

The request path reads one controller snapshot and directly selects one key.
Domain derivation and authentication use that same state, including when an
update races the operation. No production code imports the transition oracle,
executes mutation controls, trials the ring, contacts a Peer/secret manager,
reads the database to validate a security key, or performs cluster acknowledgement.
Key addition/activation does not rotate source lifecycle or authorization proof
identity. Controllers do not register client watchers or retain an unbounded
list of clients. Store cleanup remains a bounded optional hygiene operation.

The test-only oracle and mutants live under test roots. The timing fixtures are
explicit nREPL functions under `eacl.bench`, not automatic performance tests.
Source closure certifies 132 public roots with no forbidden policy matches.
The [operator guide](../../docs/security-keyrings.md) documents external entropy,
non-durability, zeroization limits, and indefinite retention for non-expiring cursors.
