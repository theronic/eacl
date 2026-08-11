# Backend adapter boundary

`eacl.backend.v8` is the only production boundary between the shared
authorization engine and Datomic, Datahike, or DataScript mechanics. Shared
code invokes validated logical operations and never inspects backend database,
datom, attribute-id, or tuple implementation types.

## Ownership

| Concern | Owner |
| --- | --- |
| Public request normalization, errors, traversal, recursion, de-duplication, Relay windowing, counts | Shared engine |
| Consistency capability validation | Shared selection code |
| Current, authoritative, causal-floor, and exact snapshot selection | Adapter |
| Object ID internalization/externalization | Adapter, under a declared round-trip contract |
| Relation and permission definition reads | Adapter returns normalized definitions |
| Forward/reverse adjacency, direct match, populated relation checks | Adapter |
| Dependency extraction, schema plans, strongly connected components | Shared engine |
| Schema generation and complete relation-generation proof frame | Adapter evidence validated by shared proof code |
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
- ordered forward/reverse adjacency, direct match, relation-populated, and
  permission-node operations; and
- when advertised, one `:proof-frame` operation returning schema generation
  plus a complete canonical vector of requested relation generations.

The capability map separately declares consistency, snapshot, source, cursor,
transaction, cache-proof, and runtime guarantees. A backend advertising
`:cache-proofs #{:ordered-generations}` must implement `:proof-frame`. An
adapter without that capability is still a correct exact-current adapter.

The runtime proof validator rejects missing, malformed, duplicate,
non-canonical, oversized, or partial evidence. The adapter does not choose a
coherence or proof mode.

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
