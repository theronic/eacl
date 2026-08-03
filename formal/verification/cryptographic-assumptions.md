# Cryptographic and canonicalization assumption map

The Dafny theorems treat authentication, canonical encodings, and dependency
proofs as axioms. This document maps each axiom to the production boundary that
operationally enforces it and to executable evidence. None of the tests below
is a mathematical proof of HMAC, SHA-256, entropy, a host runtime, or a backend.

## A1. Authenticated decoding binds value, domain, and key

**Formal assumption.** A successful decode returns the value encoded for the
same authentication domain and key. A value not produced for that domain/key
does not authenticate.

**Production boundary.**

- `eacl.secure-format/derive-key` domain-separates keys using
  `eacl/secure-format/key/v1` and the caller's format domain.
- `eacl.secure-format/encode-authenticated` signs the canonical version, key
  identifier, and encoded payload.
- `eacl.secure-format/decode-authenticated` validates envelope fields, selects
  the named key, recomputes the domain-separated tag, compares it before
  decoding the payload, and rejects unknown payload fields.
- `eacl.cursor` applies the same derived HMAC boundary to its compact
  `kid.payload.tag` frame, verifies the tag before parsing the payload, and
  supplies cursor-specific bounds, domain, and prefix.
- Cache-entry and causal-token namespaces use the generic authenticated
  envelope with distinct domains and prefixes.

**Evidence.**

- `domain-separation-and-key-rotation-test` covers correct rotation, same-prefix
  domain confusion, key substitution, payload-field confusion, and tampering.
- `authenticated-portable-cursor-test` and cache corruption tests cover
  authenticator failure at public boundaries.
- `authenticated-cross-runtime-vectors-test` replays literal compact cursor
  frames and cache envelopes in CLJ and CLJS.

**Residual trust.** HMAC-SHA-256, platform byte conversion, key secrecy, and
forgery resistance remain trusted.

## A2. Canonicalization is deterministic and injective on accepted values

**Formal assumption.** Equal accepted EACL values have one encoded byte
representation, and distinct accepted values do not share that representation.

**Production boundary.**

- `eacl.secure-format/validate-value` admits only nil, booleans, strings,
  keywords, safe integers, maps, sets, and sequential values within configured
  size, depth, and entry bounds.
- `eacl.secure-format/portable-render` explicitly renders scalar and collection
  syntax, fully qualified keyword keys, ordering, delimiters, and string
  escapes without using a host collection printer.
- `eacl.secure-format/canonical-comparator` orders maps and sets by that same
  portable representation.
- `eacl.secure-format/decode-canonical` normalizes host-reader failures and
  rejects unsupported values, duplicate fields, unknown tags, unknown
  top-level fields, unsafe integers, and hostile bounds.

**Evidence.**

- `canonical-portable-format-test` covers nested ordering, qualified keys,
  string escapes, exact numeric boundaries, duplicate fields, tagged forms,
  depth, encoded size, and field allowlists in CLJ and CLJS.
- `canonical-records-digest-test` covers record order and content stability.
- `authenticated-cross-runtime-vectors-test` covers byte-identical CLJ/CLJS
  cursor and cache outputs and legacy JVM cursor readability.
- Counterexamples `EACL-FORMAL-006` and `EACL-FORMAL-007` retain the two
  host-runtime discrepancies found while testing this assumption.

**Residual trust.** Correctness of the explicit CLJC renderer, EDN readers,
UTF-8 conversion, and runtimes is supported by differential tests, not proved.

## A3. Equal complete dependency proofs imply equal relevant inputs

**Formal assumption.** For a declared complete dependency scope, equal schema
and relationship proofs imply equality of every answer-affecting schema rule
and relationship.

**Production boundary.**

- `eacl.engine.v8/permission-schema-nodes` and
  `eacl.engine.v8/permission-relationship-eids` compute reachable
  permission-node and relation dependencies;
  `formal/dafny/CacheKernel.dfy` proves completeness for the normalized formal
  schema.
- Each adapter's `schema-proof` and `relation-proof` operation computes either
  scoped content digests, mutation proofs, or no proof. No proof forces cache
  bypass.
- `eacl.cache` authenticates the stored scope/proofs and requires adapter,
  source, causal, scope, and proof agreement before returning a value.
- `eacl.backend.v8` names completeness and change-coverage as adapter
  obligations; certification is required for a composed assurance claim.

**Evidence.**

- Adapter certification suites exercise relevant/irrelevant graph and schema
  changes, exact selection, ancestry, branch/reset/restore behavior, and proof
  scope for Datomic, DataScript CLJ/CLJS, and Datahike.
- Cache differential, recursive-cache, consistency-cache, and
  cache-review-regression suites compare enabled and disabled behavior.
- Generated `CacheKernel` decision fixtures cover exact, causal, future,
  sibling, incomplete-scope, no-proof, unauthenticated, provider-failure, and
  mismatch decisions.

**Residual trust.** Digest collision resistance and the truthfulness and
completeness of adapter proof providers remain explicit assumptions. Structural
proof adversarial campaigns are still a release gate.

## A4. Hashes and authentication tags resist collisions and forgery

**Production boundary.** `hmac-sha-256`, `canonical-records-digest`, and adapter
content-proof functions use platform SHA-256/HMAC implementations. The formal
model compares abstract proof/authentication values and never expands these
algorithms.

**Evidence.** Deterministic digest vectors, domain vectors, tampering tests, and
collision-as-test-double scenarios test call-site behavior. They do not prove
the primitives.

**Residual trust.** SHA-256 collision resistance and HMAC unforgeability.

## A5. Keys and entropy are selected securely

**Production boundary.** `random-bytes` uses `SecureRandom` on the JVM and
WebCrypto `getRandomValues` in JavaScript; `normalize-key` enforces at least 32
bytes; keyrings bind explicit key identifiers and support rotation.

**Evidence.** Weak/invalid key tests, rotation tests, and deterministic public
test vectors validate boundary behavior.

**Residual trust.** Operating-system entropy, deployment secret storage,
rotation policy, and absence of key disclosure.

## A6. Expiry time is trustworthy and has one boundary

**Production boundary.** Cursor and causal-token issuance records integer
`issued-at`/`expires-at` values. Decoding uses the injected production/test
clock and rejects at `now >= expires-at`, after authentication.

**Evidence.** `portable-cursor-expiry-boundary-test`, causal-token expiry tests,
and Dafny continuation decision fixtures agree on the inclusive expiry
boundary. `EACL-FORMAL-005` retains the former cross-backend discrepancy.

**Residual trust.** Host clock integrity and deployment clock synchronization.

## Assurance wording

The verification manifest must describe all six items as residual assumptions.
Passing their evidence suites permits a conditional kernel claim; it must never
be described as a formal proof of cryptography, canonical EDN, backend content
proofs, clocks, or key management.
