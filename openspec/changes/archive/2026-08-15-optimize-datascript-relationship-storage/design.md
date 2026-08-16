## Context

PR #92 changes Datahike from a relationship entity with component and derived
index datoms to the Datomic Pro endpoint-pair layout. DataScript remains on the
older shape: one entity contributes five relationship components plus a unique
full key and four scan tuples. DataScript 1.7.8 supports only derived
`:db/tupleAttrs`, not heterogeneous `:db/tupleTypes`, so it cannot declare the
Datomic schema literally.

That schema limitation does not prevent the logical layout. An ordinary
cardinality-many DataScript attribute can contain a vector and participate in
EAVT and AVET when indexed. DataScript's CLJC comparator orders sequential
values by length and then component values. A JVM probe confirmed exact,
forward, and reverse traversal of four-component values using `datoms`,
`seek-datoms`, and `rseek-datoms`.

The implementation is stacked on PR #92. V8 is unreleased, DataScript is mainly
used for the explorer and demonstrations, and no compatibility machinery is
required for the prerelease relationship-entity layout.

## Goals / Non-Goals

**Goals:**

- Reduce one DataScript relationship from ten relationship-specific datoms to
  exactly two endpoint datoms.
- Give DataScript, Datahike, and Datomic Pro one logical relationship encoding
  and one pair-integrity contract.
- Use native ordered indexes for direct matching, adjacency, filtering,
  pagination, relation-in-use checks, deletion, and proofs.
- Share pure endpoint encoding and decoding logic without leaking a backend
  dependency into `eacl`.
- Preserve shared public API, cursor, mutation-journal, cache-proof, and
  recursive-authorization behavior on the JVM and ClojureScript.
- Measure storage and latency changes rather than inferring speed from datom
  count alone.

**Non-Goals:**

- Add heterogeneous tuple support to DataScript.
- Make embedded peer eids behave as DataScript refs.
- Preserve, migrate, or dual-read the unreleased DataScript relationship-entity
  representation.
- Eliminate the accepted ghost-half risk after consumers bypass EACL.
- Force backend-specific temporal or index APIs behind one artificial database
  abstraction.

## Decisions

### Store ordinary four-component vectors on endpoint entities

The DataScript schema will define the Datomic/Datahike forward and reverse
relationship attributes as cardinality-many and indexed, without
`:db/valueType :db.type/tuple`:

- forward on the subject entity:
  `[subject-type relation-eid resource-type resource-eid]`
- reverse on the resource entity:
  `[resource-type relation-eid subject-type subject-eid]`

The attribute idents will match PR #92's Datahike/Datomic idents:
`:eacl.v7.relationship/subject-type+relation+resource-type+resource` and
`:eacl.v7.relationship/resource-type+relation+subject-type+subject`.

The endpoint eid is represented by the datom's `:e`, so including it again in
the value would recreate redundant storage. DataScript set semantics make each
`[e a v]` half naturally idempotent.

Alternatives rejected:

- Keep relationship entities: preserves unnecessary component and derived
  datoms and leaves DataScript structurally divergent.
- Use EDN maps: maps have no useful component ordering for bounded index
  traversal.
- Encode sortable strings or bytes: duplicates a comparator and coercion layer,
  expands stored values, and weakens eid/type fidelity.
- Wait for heterogeneous tuples: there is no need; the required ordering
  already exists for ordinary vectors.

### Use EAVT locally and AVET for cross-entity scans

Known-subject and known-resource adjacency will seek within the endpoint
entity's EAVT range. Relation/type scans, relation-in-use checks, integrity
enumeration, and content proofs will use AEVT or AVET as appropriate.

Every seek start will be a four-element vector. DataScript compares vector
length before contents, so a short prefix does not position at the start of
equal-length stored values. Prefix scans will pad the remaining components with
`nil`, `0`, or `max-entid` according to the known component types and then
terminate with explicit entity, attribute, and component checks. Code will not
invent a "maximum keyword" sentinel.

`rseek-datoms` will be used for descending traversal where the requested prefix
fixes the keyword components and the cursor supplies the varying eid. Exact
membership will use a full EAVT value.

### Treat a relationship as a physical pair

Existence requires both halves. `:touch` writes both values when the pair is
incomplete, `:create` conflicts with a complete pair, and `:delete` retracts
both values unconditionally. Adding an already present DataScript datom and
retracting an absent datom are safe, so the same transaction repairs incomplete
pairs without a special migration entity.

Both halves and relation mutation identities are written in one EACL
transaction. This prevents EACL-created half pairs while retaining recovery
from out-of-band damage.

### Preserve explicit ghost-half handling

An ordinary vector's numeric peer eid is not a ref. Retracting an entity removes
its local relationship values but cannot cascade through values on peer
entities. This matches the risk explicitly accepted for the Datomic and
Datahike endpoint tuples.

`delete-object!` will enumerate all touching values before mutation, retract
both halves explicitly, preserve the existing DataScript object-retention
semantics, and stamp every affected relation. A read-only
`eacl.datascript.integrity/dangling-relationship-report` will mirror the
Datahike diagnostic and report the precise missing peer half.

### Hash the database-visible pair, not a logical reconstruction alone

Content proofs will enumerate forward and reverse values independently,
normalize each physical half, and include endpoint public identities. A missing
or changed half must change the digest even if the surviving half still
describes a plausible logical relationship.

Managed mutation proofs remain relation-identity based and therefore
dependency-sized. This storage change does not alter consistency token
semantics.

### Share pure representation logic, retain native data access

A small backend-neutral CLJC namespace will own resolved relationship value
construction, reverse construction, decoding, prefix predicates, and peer-half
identity. DataScript, Datahike, and Datomic will adopt those functions where
their logical values are identical.

Database operations remain adapter-specific:

- Datomic uses Datomic indexes and historical database values.
- Datahike retains its wrapper-sensitive exact historical fallback and native
  attribute representation handling.
- DataScript uses its CLJC `datoms`/`seek-datoms`/`rseek-datoms` APIs and
  immutable exact snapshots.

This is the narrowest abstraction that removes real duplication without hiding
material backend semantics.

### Validate cost explicitly

Tests will assert exactly two relationship datoms and the absence of active
relationship entities/derived indexes. A reproducible benchmark will compare
the old and new DataScript implementations for direct authorization,
forward/reverse adjacency, relationship pagination, create/delete batches, and
content-proof construction at representative graph sizes on the JVM. CLJS will
receive correctness and ordering tests; timing claims will not be inferred
across runtimes.

## Risks / Trade-offs

- [Ordinary vectors receive no tuple component type validation] → Construct
  values only from resolved internal relationships and validate vector arity
  and component kinds at adapter boundaries and in integrity tooling.
- [Vector length-first ordering makes naive prefix seeks incorrect] → Centralize
  full-arity bound construction and require boundary regression tests for
  adjacent types, relations, entities, and both directions.
- [Direct endpoint retraction leaves peer values] → Preserve the mandatory EACL
  deletion contract, explicitly clean both halves, and provide offline
  detection.
- [Two physical halves can be damaged independently by out-of-band writers] →
  Make touch/delete repairable and include both halves in content proofs.
- [AVET scans could accidentally cross into another attribute or prefix] →
  Guard every scan by attribute and exact known components; do not rely only on
  the starting key.
- [Refactoring all three adapters could broaden review scope] → Share only pure
  encoding/decoding logic, keep database access local, and run each isolated
  module plus the full shared contract.
- [Fewer datoms do not guarantee lower latency] → Record old/new benchmarks and
  report regressions rather than claiming improvement from storage count.

## Migration Plan

1. Branch from PR #92's head.
2. Introduce and test the shared pure endpoint-pair representation.
3. Replace the active DataScript relationship schema and adapter paths.
4. Add integrity, proof, deletion, cursor, and CLJS boundary coverage.
5. Run isolated module, full JVM, and DataScript ClojureScript suites through
   the project nREPL/CI workflow.
6. Benchmark the old PR #92 head against the optimized branch and publish the
   results in the new stacked PR.
7. Recreate explorer/demo databases or reload their relationships through EACL.

There is no rollback or dual-read path. Before merge, rollback is branch
reversion. After adoption, consumers of unreleased builds must recreate their
DataScript database if they return to the relationship-entity implementation.

## Open Questions

None. Feasibility of indexed ordinary vectors and bidirectional seeks is
verified against DataScript 1.7.8; implementation benchmarks will determine the
size of the performance benefit, not the storage design.
