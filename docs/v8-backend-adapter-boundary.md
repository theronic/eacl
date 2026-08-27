# Backend adapter boundary

The backend contract has three separately certified roles:
`eacl.backend.v8` reads one immutable basis, `eacl.backend.source` selects and
releases native bases, and `eacl.backend.writer` plans and submits mutations.
Shared code invokes validated logical operations and never inspects backend
database, datom, attribute-ID, or tuple implementation types.

## Ownership

| Concern | Owner |
| --- | --- |
| Public request normalization, errors, traversal, recursion, de-duplication, batches, authorized candidate windows, Relay windowing, counts | Shared engine |
| Consistency capability validation | Shared selection code |
| Current, authoritative, causal-floor, and exact basis selection | Source |
| Object ID internalization/externalization | Adapter, under a declared round-trip contract |
| Relation and permission definition reads | Adapter returns normalized definitions |
| Forward/reverse ordered adjacency scans, direct match | Adapter |
| Dependency extraction, sealed plan compilation and rank certification | Shared engine |
| Certified schema generation | Adapter operation, memoized once per selected adapter and independent of proof capability |
| Complete relation-generation proof frame | Adapter evidence validated by shared proof code |
| Exact/proof-backed completed answers and subproblem caching | Shared client-private cache |
| Schema and relationship transaction planning/execution | Writer |
| Safe object relationship deletion | Writer transaction mechanics with shared public semantics |

## Immutable snapshot adapter

An adapter is constructed from one immutable backend value plus conversion
configuration. It contains no connection, source, writer, or selection
callback. It provides:

- snapshot ID, basis kind, native revision, order, and exact-locator identity;
- external/internal object conversion;
- normalized relation and permission definitions;
- ordered forward/reverse adjacency, direct match, and permission-node
  operations;
- one `:schema-generation` operation returning EACL's certified schema stamp
  or nil; and
- when advertised, one `:proof-frame` operation returning only the complete
  canonical vector `[[relation-id generation] ...]` requested by core.

The adapter capability map declares only facts the immutable value can
establish. Source consistency and writer transaction capabilities live on
their respective roles. The adapter wrapper memoizes `:schema-generation`;
its implementation may perform at most one index probe. A backend advertising
`:cache-proofs #{:ordered-generations}` must implement `:proof-frame`. An
adapter without that capability is still a correct exact-basis adapter and
may still certify schema generation for derived-plan reuse.

`:direct-match?` is a certified semantic operation, not an optimization hint.
For every schema-valid triple it must return exactly whether that one direct
relationship exists on the selected snapshot, without widening through a
permission or userset traversal. The shared enumerate route relies on this
certificate to decide each authorized candidate with exactly one probe and
does not perform a second permission evaluation. Construction/certification
must fail with `:eacl/unsupported-capability` or an adapter-contract error when
this denotation cannot be supplied.

The runtime proof validator distinguishes absence from defect. Missing
generations, an unsupported operation, bounded closure overflow, or transient
provider failure make proof unavailable for that request. Malformed shape,
wrong cardinality, duplicate/non-canonical ids, non-integer generations, or a
generation above the selected revision are contract violations: exact
authorization continues, but managed lifting is disabled for that client
lifecycle. The adapter does not choose a coherence or proof mode.

## Basis source

A source is constructed from a connection or store plus configuration. Its
static profile—capabilities, topology, ownership, execution constraints,
scope, and lifecycle—must be readable without acquiring a basis. It implements
current, authoritative, at-least, and exact-by-locator acquisition and returns
exactly `{:adapter ... :ownership ... :release-token ...}`. Unsupported modes
fail through the closed capability contract.

The source scope must identify one database history. A durable backend persists
and reuses its source id across reopen. A backend that cannot persist identity
must mint a fresh id once per live source, regardless of a caller-supplied
configuration id. Combined with `:source-lifecycle`, this scope is the lineage
prefix for every proof-backed artifact.

Exact-by-locator acquisition must not acquire current first. Owned sources must
close rejected causal candidates and release on success, typed or foreign
failure, timeout, cancellation, proof resolution, cache publication, and
cursor/token construction. Shared selected-basis state makes repeated release
idempotent and enforces declared platform-thread constraints.

## Writer

A writer is the only role allowed to retain a connection or transaction
service for mutation. It supplies transaction submission, schema submission,
relationship and deletion planning, affected-relation derivation, retraction
counting, and truthful contention classification. It declares positive
`:max-attempts` and `:max-transaction-size` bounds. The shared write pipeline
acquires one planning basis, plans, submits, derives the response token from the
committed value, releases, and retries only writer-classified contention.
Object deletion is split only at the writer's transaction-size bound.

Construction validates every declared operation of each role. Missing
operations fail at `make-client` with `:eacl/invalid-backend-role`, `:role`, and
`:operation`; they cannot survive until a request as `AbstractMethodError` or a
map lookup failure.

## Certified schema generation

The schema-generation operation is a separate, cheap certification seam. It
returns a portable non-negative generation maintained atomically by EACL's
schema writer, stays unchanged across relationship-only and unrelated writes,
and advances after a managed schema change. It must be bound to the selected
immutable snapshot and must not derive identity from a physical-schema
fingerprint.

The runtime keys its bounded derived-state registry by the engine ABI,
backend/adapter identity, source scope, lifecycle, and this generation. Parsed
validation catalogs, permission paths, dependency closures, routing analysis,
direct-grant relations, cycle guards, and sealed plans are owned by that
generation. Nil disables cross-request reuse and installs a request-local
floor instead; native revision is never a fallback key. Ordered proof frames do
not repeat the schema value; core reads this independent operation once and
combines it with the relation frame after validating both against the selected
revision.

## Ordered-generation certification

Shared cache proofs assume that:

- snapshots are immutable and ordered inside one source lifecycle;
- schema and every declared relation have initialized generations;
- native revision, schema generation, and relation generations use one
  portable exact-natural numeric domain;
- every generation visible at a selected value is at or below that value's
  native revision;
- every supported authorization mutation atomically commits data changes and
  stamps each affected relation with its native committed transaction;
- that transaction is later than every generation visible before commit; and
- equal schema semantics plus equal normalized request produce one complete,
  deterministic dependency closure.

Dafny proves the scalar-frontier cache theorem from those assumptions. The
database engines and adapter implementations are not mechanized; bundled
adapter certification and randomized cache-versus-bypass tests establish the
runtime trusted boundary. The certification suite executes a supported
relationship mutation, asserts that every affected relation equals the
committed revision, checks all generation ceilings, and opens distinct or
reopened sources to certify the source-id durability rule. A third-party
adapter must run the same contract and certification suites before advertising
ordered generations.

## Capability policy

Unsupported consistency guarantees fail with
`:eacl/unsupported-capability` before authorization work or acquisition.
DataScript's source exposes a serialized current head and no arbitrary
exact-history capability. Datomic's source supports authenticated exact
reconstruction through `d/as-of`. Datahike advertises authoritative-head and
exact-history selection only when its active writer and retained-history
configuration can establish them.

Native revision tokens are independent of completed-answer caching. At-least
selection establishes a native revision floor only inside the authenticated
backend/source/lifecycle scope; exact selection additionally establishes the
exact locator. Source replacement requires lifecycle rotation before cached or
token-bearing traffic resumes.

Datalevin demonstrates why a capability claim must be backed by executable
storage invariants. Persistent Datalevin datoms do not expose their original
transaction in EACL's proof order, so the adapter never interprets datom `:tx`.
Instead, the maintained fork materializes scalar generation values from the
committing `max-tx`, enforces complete schema/relation stamping after transaction
expansion, and rejects discontinuous or unadmitted protected writes. The
adapter advertises `:ordered-generations`; its `:proof-frame` performs one exact
EAV probe per requested relation and its independent `:schema-generation`
operation performs one probe. Owned-snapshot acquisition reads only the fork's
revision bounds; it does not enumerate or fingerprint physical schema. Each
reader is owned by the acquiring platform thread, cannot escape or cross
threads, and is closed exactly once after the complete response (including
cursor/cache publication) or after any failure.

## Assurance ownership

`formal/verification/adapter-certification.edn` records separate runtime
obligation maps. `SnapshotOracle.dfy` describes the immutable basis-adapter
contract; `ConsistencyDecision.dfy` models source selection decisions; and the
cache/scalar-frontier models state writer stamping assumptions. A proof of one
role is not evidence for another role's native effects.

## Aggregate extension obligations

Third-party adapters do not add backend-private implementations of
`check-permissions`, permission-filtered relationship pages, or relationship-
filtered lookups. They supply the ordinary certified snapshot operations and
let shared orchestration hold one request context across the aggregate. The
shared certification harness verifies ordered batch equivalence, scan and
enumerate set equivalence, one direct probe per enumerate candidate, bounded
window progress, cursor scope, one acquisition/release, and lifecycle balance
under typed and foreign failures.
