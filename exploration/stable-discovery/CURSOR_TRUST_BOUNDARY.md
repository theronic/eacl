# Cursor and checkpoint trust boundary

`RelayEdgePagination.dfy` models the public Relay edge arithmetic;
`EdgeBoundaryAuthentication.dfy` binds that edge to the exact canonical result
at its one-based ordinal; and `RelayCheckpointExecution.dfy` refines the public
edge to a history-free forward checkpoint. `StablePagination.dfy` is only the
private delivered-count boundary. Deterministic replay of the sealed reducer at
the exact basis determines the unique result sequence. A rolling prefix digest
does not establish that premise: two sequences can have the same prefix at the
cursor and different next pages. It would only detect some violations of a
determinism contract that must already hold.

The minimum design therefore performs one authentication operation per cursor,
not one cryptographic update per discovered result.

## Canonical payload

The authenticated cursor payload is a versioned, length-delimited canonical
encoding of:

```text
cursor-format ABI
semantic/reducer implementation ABI
discovery-order ABI
backend + logical source identity
exact selected basis identity (or complete dependency proof identity)
schema generation + sealed plan fingerprint
normalized authorization operation, propagation direction, principal, root,
filters, and result type
fixed positive page size
represented edge's one-based result ordinal
represented edge's canonical external boundary identity
expiry and key id
```

Canonical encoding must be injective over every accepted field value and
identical in Clojure and ClojureScript. Decoding is total, size bounded,
version checked, and rejects duplicate/noncanonical representations before
semantic use. Unsupported fields, invalid numeric ranges, excessive ordinals,
and trailing bytes fail closed.

Page-navigation mode is deliberately absent. The same edge token is valid as
`after` or `before`; authorization operation and propagation direction remain
semantic context.

No public checkpoint reference is needed. After MAC/AEAD, context, range, and
edge validation, the server derives a bounded private lookup key from the exact
context and the request's normalized forward resume boundary:

- `after` resumes at the edge ordinal; an exact checkpoint at that boundary
  retains the constant-size last-result identity and must match the token's
  edge identity;
- `before` computes exclusive end `ordinal - 1`, resumes at
  `max(0, end - page-size)`, runs forward through at most `page-size + 1`
  results, validates the supplied edge as the final lookahead, and returns the
  preceding prefix in canonical order;
- bare `last` first requires completion of the finite sequence or an exact
  completed answer, under the ordinary logical-work and deadline limits.

The private entry full-compares decoded context, normalized resume boundary,
and exact checkpoint metadata. Eviction is a normal miss: deterministic replay
reconstructs the resume boundary from the start. Private hash collisions can
only cause a miss after full comparison, never select another state.

## Authentication

One suitable construction is:

```text
tag = HMAC(Kcursor, "eacl-cursor/v1" || payload-bytes)
```

An existing misuse-resistant authenticated-encryption format can fill the same
role when cursor confidentiality is desired. The construction and domain label
are frozen with the cursor ABI. Verification uses constant-time tag comparison
after bounded outer decoding. A key identifier selects an explicitly bounded
verification ring; unknown or retired keys reject.

## Assumptions needed for refinement

The ordinal proof transfers to production only under all of these assumptions:

1. canonical encoding is injective and domain/length separation is
   unambiguous;
2. the cursor MAC/AEAD is unforgeable for the accepted query volume and key
   lifetime;
3. keys remain secret, are generated with adequate entropy, and key-id
   selection cannot downgrade verification;
4. verification occurs before trusting context, ordinal, or expiry;
5. equality of a basis/proof identifier means the adapter can select the same
   immutable semantics; an unavailable basis is a stale-cursor failure;
6. the semantic implementation ABI, sealed plan, producer vectors, adapter
   scan-order ABI, chunk integration, join admission, and canonical-head rule
   deterministically reproduce the complete result sequence;
7. any change that can alter discovery order increments an authenticated ABI
   or fingerprint field; deployments do not reuse an ABI after such a change;
8. replay or an exact checkpoint reaches the normalized forward resume boundary
   under normal logical and service admission limits; an `after` checkpoint
   matches the represented last result, and a `before` traversal validates its
   represented edge as the one bounded lookahead before returning data;
9. Relay arithmetic uses the authenticated positive page size; a request
   cannot reinterpret an existing cursor under a different size, and both new
   and resumed requests revalidate it against the current configured maximum
   before allocation or replay;
10. derived checkpoint lookup compares the complete decoded context, normalized
    resume boundary, and exact checkpoint metadata, not a hash, ID, or cache
    namespace alone.

These are source-review, executable-refinement, cryptographic, and adapter
qualification obligations. The solver does not establish the HMAC or backend
assumptions.

## Security and correctness failures

- A token for another source, principal, query, authorization operation,
  propagation direction, plan, result type, basis, ABI, or filter set is a
  context mismatch. Using the same valid edge as `after` or `before` is not.
- A valid old-key token outside the verification window is expired/unsupported.
- A private checkpoint miss never changes semantics; it triggers exact replay to the
  ordinal.
- A replay that exhausts before the required resume/validation boundary, or
  reaches a different external identity at the represented ordinal, fails
  closed.
- A deployment that changes order without changing the order/implementation
  ABI violates the release contract. A prefix digest would detect only some
  manifestations after paying per-result cost; it would not make that engine
  correct.
- A new database head cannot “re-mint at the same position.” It may continue
  only after a certified complete dependency proof establishes semantic
  equality for the exact query; the new proof identity belongs to a new
  context.
- Public payloads contain no checkpoint pointer, frontier, grant set,
  relationship values, cached response, internal exception, or credential.
- Parse/MAC/checkpoint lookup work is bounded and charged before replay, so
  attacker-supplied cursors cannot bypass admission control.

## Required executable controls

Current exploration checkpoint: an independent minimum cursor prototype uses
the existing bounded canonical/HMAC service with the payload above. Its
1,364-byte fixture passed 15 exact-context mutations and 14 focused controls:
same token for `after` and `before`, tag tampering, expiry, domain separation,
page/ordinal bounds, unknown fields, old-key rotation, retired-key rejection,
absence of private state, and forced private checkpoint-key collisions with
full-context comparison. The complete CLJS suite separately passes the fixed
portable secure-format digest. This is `checked-executable prototype`, not
production Relay source refinement or operational key qualification.

Before adoption, production tests must reject:

- one-bit changes to every payload field and the tag;
- field deletion, duplication, reordering, noncanonical integer/string forms,
  unknown versions, oversized tokens, invalid base64, and trailing data;
- cross-principal/query/source/basis/plan/authorization-operation/propagation-
  direction/result-type/filter reuse; separately require the same edge token
  to work as both `after` and `before`;
- zero/oversized page size and cross-page-size cursor reuse;
- old/revoked/unknown key IDs and incorrect domain labels;
- a private lookup-key collision with a different exact context or ordinal;
- replay exhaustion before the resume/validation boundary, wrong identity at
  the represented ordinal, an `after` checkpoint with a different last-result
  identity, and a `before` implementation that resumes at exclusive end and
  therefore cannot recover the preceding page;
- CLJ/CLJS canonical-byte disagreement;
- order drift across process restarts, map insertion/hash permutations,
  adapter chunk sizes, worker completion schedules, cache hit/miss paths, and
  checkpoint eviction;
- reuse of an order/implementation ABI after a deliberately order-changing
  mutation.

The release claim is conditional on these controls, exact-basis selection, and
the source-refinement bridge. There is no separate per-result cryptographic
state to implement, benchmark, rotate, or keep in reducer checkpoints.
