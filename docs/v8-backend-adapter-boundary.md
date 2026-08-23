# Backend adapter boundary

`eacl.backend.v8` is the only production boundary between the shared
authorization engine and Datomic, Datahike, DataScript, or Datalevin mechanics. Shared
code invokes validated logical operations and never inspects backend database,
datom, attribute-id, or tuple implementation types.

## Ownership

| Concern | Owner |
| --- | --- |
| Public request normalization, errors, traversal, recursion, de-duplication, batches, authorized candidate windows, Relay windowing, counts | Shared engine |
| Consistency capability validation | Shared selection code |
| Current, authoritative, causal-floor, and exact snapshot selection | Adapter |
| Object ID internalization/externalization | Adapter, under a declared round-trip contract |
| Relation and permission definition reads | Adapter returns normalized definitions |
| Forward/reverse ordered adjacency scans, direct match | Adapter |
| Dependency extraction, sealed plan compilation and rank certification | Shared engine |
| Certified schema generation | Adapter operation, memoized once per selected adapter and independent of proof capability |
| Complete relation-generation proof frame | Adapter evidence validated by shared proof code |
| Exact/proof-backed completed answers and subproblem caching | Shared client-private cache |
| Schema and relationship transaction planning/execution | Adapter |
| Safe object relationship deletion | Adapter transaction mechanics with shared public semantics |

## Immutable snapshot adapter

An adapter is bound to one immutable backend value and provides:

- snapshot, stable source, lifecycle, native revision, order, and exact-locator
  identity;
- current, authoritative, at-least, and exact selection operations, with
  unsupported modes rejected through capabilities;
- external/internal object conversion;
- normalized relation and permission definitions;
- ordered forward/reverse adjacency, direct match, and permission-node
  operations;
- one `:schema-generation` operation returning EACL's certified schema stamp
  or nil; and
- when advertised, one `:proof-frame` operation returning schema generation
  plus a complete canonical vector of requested relation generations.

The capability map separately declares consistency, snapshot, source, cursor,
transaction, cache-proof, and runtime guarantees. The adapter wrapper
memoizes `:schema-generation`; its implementation may perform at most one
index probe. A backend advertising
`:cache-proofs #{:ordered-generations}` must implement `:proof-frame`. An
adapter without that capability is still a correct exact-current adapter and
may still certify schema generation for derived-plan reuse.

`:direct-match?` is a certified semantic operation, not an optimization hint.
For every schema-valid triple it must return exactly whether that one direct
relationship exists on the selected snapshot, without widening through a
permission or userset traversal. The shared enumerate route relies on this
certificate to decide each authorized candidate with exactly one probe and
does not perform a second permission evaluation. Construction/certification
must fail with `:eacl/unsupported-capability` or an adapter-contract error when
this denotation cannot be supplied.

The runtime proof validator rejects missing, malformed, duplicate,
non-canonical, oversized, or partial evidence. The adapter does not choose a
coherence or proof mode.

Snapshot acquisition and ownership are separate from the immutable adapter.
`eacl.backend.snapshot-provider` selects borrowed or owned snapshots for the
complete request lifetime. Owned providers must close rejected causal
candidates and release on success, typed/foreign failure, timeout,
cancellation, proof resolution, cache publication, and cursor/token
construction. See [the provider migration guide](v8-snapshot-provider-migration.md).

## Certified schema generation

The schema-generation operation is a separate, cheap certification seam. It
returns a portable non-negative generation maintained atomically by EACL's
schema writer, stays unchanged across relationship-only and unrelated writes,
and advances after a managed schema change. It must be bound to the selected
immutable snapshot and must not derive identity from a physical-schema
fingerprint.

Shared code keys its bounded derived-state registry by the engine ABI,
backend/adapter identity, source scope, lifecycle, and this generation. Parsed
validation catalogs, permission paths, dependency closures, routing analysis,
direct-grant relations, cycle guards, and sealed plans are owned by that
generation. Nil disables cross-request reuse and installs a request-local
floor instead; native revision is never a fallback key. When an ordered proof
frame is also available, its schema stamp must agree with this independent
value or the adapter fails with a backend-integrity error.

## Ordered-generation certification

Shared cache proofs assume that:

- snapshots are immutable and ordered inside one source lifecycle;
- schema and every declared relation have initialized generations;
- every supported authorization mutation atomically commits data changes and
  stamps each affected relation with its native committed transaction;
- that transaction is later than every generation visible before commit; and
- equal schema semantics plus equal normalized request produce one complete,
  deterministic dependency closure.

Dafny proves the scalar-frontier cache theorem from those assumptions. The
database engines and adapter implementations are not mechanized; bundled
adapter certification and randomized cache-versus-bypass tests establish the
runtime trusted boundary. A third-party adapter must run the same contract and
certification suites before advertising ordered generations.

## Capability policy

Unsupported consistency guarantees fail with
`:eacl/unsupported-capability` before authorization work. DataScript exposes a
serialized current head and no arbitrary exact-history capability. Datomic
supports authenticated exact reconstruction through `d/as-of`. Datahike
advertises authoritative-head and exact-history selection only when its active
writer and retained-history configuration can establish them.

Native revision tokens are independent of completed-answer caching. At-least
selection establishes a native revision floor only inside the authenticated
backend/source/lifecycle scope; exact selection additionally establishes the
exact locator. Source replacement requires lifecycle rotation before cached or
token-bearing traffic resumes.

Datalevin demonstrates the conservative capability policy: its persistent
datoms do not expose the original transaction in a form certified for EACL's
proof order. The adapter therefore omits `:ordered-generations` and the
`:proof-frame` operation. It may reuse completed answers only for the same
certified semantic snapshot identity and never interprets a datom `:tx` as a
relation generation. Independently, its one-probe `:schema-generation`
operation lets schema-derived plans survive relationship-only revisions. Its
owned-snapshot acquisition reads only the maintained fork's revision bounds;
it does not enumerate or fingerprint physical schema. Each reader is owned by
the acquiring platform thread, cannot escape or cross threads, and is closed
exactly once after the complete response (including cursor/cache publication)
or after any failure. Datalevin makes no ordered-generation claim.

## Aggregate extension obligations

Third-party adapters do not add backend-private implementations of
`check-permissions`, permission-filtered relationship pages, or relationship-
filtered lookups. They supply the ordinary certified snapshot operations and
let shared orchestration hold one request context across the aggregate. The
shared certification harness verifies ordered batch equivalence, scan and
enumerate set equivalence, one direct probe per enumerate candidate, bounded
window progress, cursor scope, one acquisition/release, and lifecycle balance
under typed and foreign failures.
