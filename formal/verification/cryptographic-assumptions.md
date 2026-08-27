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
  cursor and cache outputs and current-format JVM cursor readability.
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
  permission-node and relation dependencies. `formal/dafny/CurrentCache.dfy`
  proves the normalized-rule dependency frame consumed by the basis-first
  cache; `ScalarFrontierCoherence.dfy` proves the supported scalar-frontier
  lifting condition.
- Each certified adapter's independent `schema-generation` operation reads the
  schema assertion generation, while `proof-frame` reads one native committed
  generation for every relation in the complete canonical dependency closure.
  Missing evidence forces exact-only evaluation; malformed or above-revision
  evidence disables managed lifting for the runtime lifecycle.
- `eacl.cache` keeps completed entries client-private and requires lifecycle,
  semantic request, schema generation, and scalar dependency-frontier
  agreement before returning a managed value.
- `eacl.backend.v8` names completeness and change-coverage as adapter
  obligations; certification is required for a composed assurance claim.

**Evidence.**

- Adapter certification suites exercise relevant/irrelevant relationship and
  schema changes, exact selection, revision floors, source replacement, and proof
  scope for Datomic, DataScript CLJ/CLJS, and Datahike.
- Cache differential, recursive-cache, consistency-cache, and
  cache-review-regression suites compare enabled and disabled behavior.
- `CurrentCache` proves exact-basis isolation and the normalized-rule
  dependency frame; `ScalarFrontierCoherence` proves equal complete scalar
  proofs preserve deterministic denotation. The removed `CacheKernel` graph
  ancestry model had no production consumer and contributes no release claim.

**Residual trust.** Deterministic complete dependency extraction, adapter proof
truthfulness, globally ordered transaction generations, atomic mutation
stamping, and the database engines remain explicit assumptions. Structural
proof adversarial campaigns are executable evidence, not proofs of those
systems.

## A4. Hashes and authentication tags resist collisions and forgery

**Production boundary.** `hmac-sha-256` and canonical authenticated token
digests use platform SHA-256/HMAC implementations. Ordered-generation cache
proofs use exact integer comparisons and do not depend on hash collision
resistance. The formal model treats authentication values abstractly and never
expands the cryptographic algorithms.

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

## A7. Snapshot selection consumes authenticated token facts

**Formal assumption.** `ConsistencyDecision.dfy` starts after token processing.
Its `revisionSatisfied` and `sameSourceScope` Booleans are observations, not a
model of HMAC verification, expiry, scope decoding, or exact-snapshot
reconstruction.

**Production boundary.**

- `eacl.consistency/authenticate` calls `eacl.causal-token/token-data` before
  any causal backend selection and translates expiry and scope failures into
  their public typed errors.
- `eacl.consistency/selected-adapter!` distinguishes an absent selection from
  a present malformed value, validates source/branch scope, and only then
  evaluates the native-revision floor or exact revision-and-locator
  postcondition.
- At-least freshness compares the selected adapter's validated native revision
  with the authenticated floor. Exact selection compares both validated native
  revision and exact locator with their authenticated values.

**Evidence.**

- The generated Java and JavaScript boundaries exhaust all 16 plan states and
  all 48 well-formed post-selection observation states.
- Shared CLJ/CLJS consistency tests cover expired and wrong-scope tokens,
  insufficient revisions, divergent exact locators, exact-snapshot absence,
  present malformed selections, and capability ordering.
- Adapter certification checks revision floors, source replacement, authoritative
  barriers, and exact selection for Datomic, Datahike, and DataScript.

**Residual trust.** The token decoder and cryptographic primitives described
by A1–A6, the Clojure fact-extraction code, and the truthfulness of backend
scope, native-revision, and exact-selection operations remain in the TCB.

## Assurance wording

The verification manifest must describe all seven items as residual
assumptions.
Passing their evidence suites permits a conditional kernel claim; it must never
be described as a formal proof of cryptography, canonical EDN, database
engines, clocks, key management, or backend snapshot-selection facts.
