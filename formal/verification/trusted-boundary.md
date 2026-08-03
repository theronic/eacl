# Trusted computing base and proof assumptions

EACL's target theorem is conditional: the generated kernel refines the formal
semantics when its validated input and adapter obligations hold. It is not a
proof of the whole deployed system.

## Verification and compilation tools

The following are trusted to implement their documented behavior:

- Dafny, Boogie, and the bundled Z3 solver;
- Dafny's Java and JavaScript compilers and runtime libraries;
- Java and JavaScript compilers, bundlers, and runtimes used by consumers;
- Clojure, ClojureScript, their host interop, and generated boundary code.

Versions and artifact hashes are pinned in `formal/toolchain.lock.json`.
Reproducibility reduces supply-chain drift; it does not prove these tools.

## Runtime boundary assumptions

Handwritten CLJ/CLJS conversion code must:

- reject unknown variants, fields, and result tags;
- preserve exact object/type/relation identities and the adapter's
  fixed-snapshot cursor-relative sequence positions, without presenting that
  internal sequence as a global, lexical, domain, or cross-adapter order;
- reject integers outside the target's exact representable range;
- bound collection size, nesting, and encoded input size;
- turn every malformed adapter callback or generated result into a typed,
  fail-closed error.

These obligations are tested and runtime-guarded, not proved as Clojure facts.

## Backend adapter obligations

For an operation to inherit a kernel theorem, its adapter must establish:

1. every read in the operation observes one immutable selected snapshot;
2. external/internal object conversion is injective and round-trips for every
   visible object;
3. relation and permission definitions are complete for the requested schema;
4. forward and reverse scans are finite, duplicate-free, complete,
   directionally equivalent, strictly ordered within the adapter's internal
   fixed-snapshot index sequence, and honor inclusive/exclusive bounds; this is
   a pagination obligation, not a public global-order guarantee;
5. direct match agrees exactly with membership in the corresponding scan;
6. `all-permission-nodes` is complete;
7. schema and relationship proofs cover the declared dependency scope;
8. causal-anchor membership denotes ancestry, never numeric transaction order;
9. exact selection returns the compatible immutable graph requested or fails;
10. source scope and adapter fingerprint change whenever an
    assumption-affecting implementation identity changes.

Backend certification provides evidence for these assumptions. It does not
verify DataScript, Datomic, Datahike, their storage engines, or host databases.

## Cryptographic and canonicalization axioms

The formal model assumes:

- authenticated decoding returns only the value encoded with the same key and
  domain;
- canonicalization is deterministic and injective over accepted values;
- equal complete dependency proofs imply equal answer-affecting inputs for
  that declared scope;
- production hashes and authentication tags provide their intended
  collision/forgery resistance;
- secret keys and entropy are generated, stored, and selected correctly;
- expiry time supplied to the kernel is trustworthy.

Production HMAC/hash implementations, constant-time comparison, canonical
encoding, clocks, and entropy remain in the TCB. Secure-format and structural
proof tests are evidence, not mathematical proofs of cryptography.

## Operational limits

Configured maximum input sizes, recursion work, queued work, derived grants,
cursor age, retained snapshots, and continuation/cache capacity are trusted
configuration inputs after range validation. The kernel proves that crossing a
modeled traversal limit fails the entire operation; it does not prove that a
chosen limit meets latency or availability objectives.

## Excluded claims

The verification does not establish that a customer's policy expresses their
intent, that an adapter meets its assumptions without certification, that
toolchain/runtime defects are impossible, or that performance targets hold.
The release manifest must list these exclusions and must never label an
unmapped operation “formally verified.”
