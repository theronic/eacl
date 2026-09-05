# Caveat and qualifier foundation

This foundation follows the v8 qualifier-eid storage change (storage ABI 9).
It persists named Caveat definitions and provides portable validation, partial
evaluation, an optional JVM evaluator, and explicitly staged qualifier writers.
Qualified authorization and expiration take effect only in the subsequent
Phase 3 serving change. A Phase 2 client rejects non-nil qualifier references;
never seed them into a serving Phase 2 database.

## Named definitions and schema admission

A definition is stored once and referenced by native entity ID:

```zed
caveat in_region(region string, accepted list<string>) {
  region in accepted
}

definition user {}
definition doc {
  relation viewer: user
  permission view = viewer
}
```

This complete schema is accepted by Phase 2 `write-schema!`; the unused named
Caveat does not affect ordinary authorization. The parser also understands
`relation viewer: user with in_region`, but public schema admission rejects
that branch until Phase 3. `user with in_region` requires the Caveat;
`user | user with in_region` permits either plain or Caveated input. An
expiry-only qualifier requires a plain alternative.

Names and typed parameter names use ASCII identifiers, up to 64 bytes. CEL
keywords/type names and the `__eacl_` prefix are reserved. Definitions have
unique names, at most 32 parameters, canonical typed parameter payloads,
LF-normalized expression source, and profile ID `eacl-cel/1`. Source spacing
and comments otherwise remain part of definition identity. A non-Boolean root,
unknown parameter, duplicate name, unresolved Caveat, unsupported overload or
operation, malformed source, or excessive bound fails before schema replacement.
Errors retain the Caveat name, source span and expression-local offset where
available.

Updating a definition retains its native entity ID and advances schema
generation. Removal is rejected while any retained qualifier references it,
including an unattached preparation. A parameter change must remain compatible
with every retained bound context. Native schema/reference fences reject
competing writes; historical schema reads retain the selected definition.

## Profile 1 values and operations

| Type | Portable Clojure/CLJS value |
| --- | --- |
| `bool` | `true` or `false` |
| `int` | Exact integer from -9007199254740991 through 9007199254740991 |
| `string` | Valid Unicode text; no unpaired surrogates or normalization |
| `timestamp` | `[:timestamp epoch-ms]`, UTC years 0001 through 9999 |
| `list<T>` | Vector of one scalar type |
| `map<string,T>` | String-keyed map of one scalar type |

Supported operations are `!`, `&&`, `||`, scalar `==`/`!=`, integer/timestamp
`<`/`<=`/`>`/`>=`, list/map membership with `in`, string-keyed map indexing
with `m[key]` or `m.member`, and string `contains`, `startsWith`, `endsWith`.
Source literals are Boolean, exact integer, and JSON-style double-quoted
strings. Grouping and `//` comments are supported. Relational operators follow
CEL's shared precedence; use parentheses when mixing comparisons.

Nested containers, source container literals, macros, arithmetic, regex,
conditional expressions, null, floats, unsigned integers, bytes, duration,
conversions, timestamp selectors, and string ordering/size are excluded.
Repeated ungrouped unary operators are rejected; `!(!a)` is supported.
These are explicit profile limits, not full CEL or SpiceDB compatibility.

| Bound | Maximum |
| --- | ---: |
| Source UTF-8 bytes / tokens / grouping depth | 8192 / 1024 / 32 |
| Plan nodes / depth | 256 / 32 |
| String UTF-8 bytes | 4096 |
| Entries in each list or map | 128 |
| Total context entries / canonical payload bytes | 1024 / 16384 |
| Conservative evaluation work units | 1048576 |
| Cached programs / simultaneous program builds | 256 / 4 |

Time values use exact epoch milliseconds from -62135596800000 through
253402300799999. Input, encoded payload and plan limits are checked before
expensive parsing or evaluation. Work preflight includes both logical branches;
an oversized branch is rejected even behind an absorbing `true` or `false`.
Limits bound admitted work and retained programs, not wall-clock latency or
all JVM heap allocations.

## Context, outcomes and implementation capability

Request and Relationship-bound contexts have declared string keys. Both maps
are independently validated, then bound values override request values. A bad
request value cannot be hidden by a valid bound value. Empty bound context is
omitted from storage. Canonical versioned payloads sort keys by Unicode scalar
order and tag values explicitly; they contain no parser or evaluator objects.

The portable evaluator returns:

```clojure
{:outcome :true}
{:outcome :false}
{:outcome :conditional :missing-fields #{"region"}
 :residual [:in [:param "region"] [:literal [:list :string] ["za"]]]}
{:outcome :error :reason :missing-map-key}
```

Missing parameters can yield a conditional result. A missing key in a supplied
map is a fault. Logical absorbers work on either side: `false && fault` is
false, and `fault || true` is true. Otherwise faults are preserved rather than
reported as missing input. Typed failures also cover invalid context, profile
or payload, unsupported operations/overloads, resource limits, non-Boolean
native results, evaluator exceptions, and interruption.

Use `dev.eacl/eacl-caveats-jvm` for the optional JVM implementation; requiring
`eacl.caveats.jvm` registers its process default. See the [module guide](../modules/eacl-caveats-jvm/README.md)
for an executable example. The adapter explicitly handles cel-parser's returned
error objects, lowers literals to bindings, and preserves operand faults through
its qualified overload/unary adapter. Complete contexts execute once through
CEL; incomplete contexts execute once through EACL's portable partial evaluator.
No runtime model, oracle comparison, or second decision is involved.

Core and DataScript CLJS contain the value/plan tools and evaluator protocol,
with no CEL or ANTLR dependency. An independently certified supplied evaluator
must advertise the exact profile fingerprint; absent or mismatched capability
fails admission. Registration alone does not activate serving. Implementation
fingerprints include pinned artifacts and adapter semantics. The bounded JVM
program cache coalesces construction, retains successes only, and includes the
canonical definition and fingerprint in its key. Request values are not cached.

## Sparse qualifier storage and staged publication

The fifth endpoint tuple component is a native qualifier entity ID or nil.
The owner entity and first four components remain Relationship identity.
A nonempty qualifier has the mandatory format marker plus only its present
Caveat reference, canonical bound-context payload, and/or `valid-until-ms`.
There is no public `:eacl/id`, owner sidecar, global generation, or expiry index.
Empty qualifiers normalize to nil and allocate nothing. Context without a
Caveat, unknown fields, unresolved refs, and malformed times are rejected.

A qualifier is immutable and singly owned. A semantic replacement allocates a
fresh qid and atomically replaces both halves, removes the old qualifier,
advances the Relation stamp, and commits caller-composed application datoms.
Plain-to-plain replacement retains the existing pair while still fencing,
stamping, and committing application datoms. Deletion removes both halves and
the owned qualifier. A missing non-nil target is corruption, never nil.

| Backend | Certified strategy | Snapshot evidence |
| --- | --- | --- |
| Datomic | Inline allocation | Native assertion transaction |
| DataScript JVM and CLJS | Prepared reference | Native assertion transaction |
| Datahike, including attribute refs | Prepared reference | Native assertion transaction |
| Datalevin maintained EACL fork | Inline allocation | Exact snapshot; no creation-version claim |

`eacl.<backend>.qualifiers/writer` constructs an internal staged writer.
`eacl.relationships.staged/prepare!` creates an inert qualifier and opaque handle
bound to one writer, source, Relationship identity, schema generation and exact
qualifier facts. `plan-current` returns composable fenced transaction data while
releasing its owned snapshot before commit. `write!` prepares automatically on
backends that require it. Caller datoms cannot bypass protected EACL attributes
or execute arbitrary transaction functions. These APIs are for non-serving
fixtures and staged preparation only in Phase 2.

An unattached preparation cannot authorize. Publishing it must use its concrete
native qid and atomically attach both halves. Native commit-time facts and
schema/Relation fences reject stale, mutated, reused or misdirected handles.
Datomic additionally serializes Caveat-reference creation through its existing
schema UUID fence. Datalevin planners use one native read snapshot and close it
before committing. Native transaction rejection rolls back the pair and caller
application datoms together.

## Integrity and cleanup

Backend integrity namespaces expose `qualifier-proof-input` and
`qualifier-report` for explicit offline inspection. Datalevin requires an owned
native read snapshot. Reports distinguish missing, shared, malformed, asymmetric
and observed-mutated qualifiers, duplicate Relationship identities, invalid
Relation proofs, and valid unattached preparations. Proof inputs include source,
schema generation, owning Relation stamps and available assertion versions.
Before/after comparison must use the same source. A single snapshot does not
prove historical immutability, especially on an exact-only backend.

`staged/cleanup!` removes an unchanged unattached preparation through its handle.
After restart, `eacl.relationships.qualifier-integrity/cleanup-orphans!` can
collect one bounded batch (default 100, maximum 1000). This is an explicit
whole-store offline scan with bounded report samples, not a bounded scan or an
authorization hot path. Corrupt data blocks collection. An exact native head
guard rejects any write after the scan, including a concurrent attachment;
retry by taking a fresh snapshot. Collection never supplies expiration semantics.

Install the additive Caveat and qualifier attributes through each backend's
explicit schema installation/preparation API before using staged persistence.
Existing storage migration remains explicit; startup does not scan or migrate
all Relationships. Datalevin requires its maintained fork, external source
lifecycle/security/watermark configuration, and an admitted write token from
physical schema preparation. Reuse the normal backend upgrade procedure when
opening an existing protected store; do not bypass its write policy.

`valid-until-ms` is typed and immutable in this phase but entirely inert for
authorization. Time-aware checks, conditional permissionship, request context,
qualified schema activation, and cache/cursor validity enter together in Phase 3.
