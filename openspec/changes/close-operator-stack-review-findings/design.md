# Design: Close operator-stack review findings

## Context

PR 154 closes the adversarial review of the v8 operator stack and the
same-basis speculative-cache collision. The final design combines the operator
correctness fixes with a public snapshot boundary that does not rely on native
database-value provenance inference.

## Decisions

### D1. EACL owns public snapshot provenance

Public authorization targets are EACL clients and EACL-created immutable
snapshots. A client selects committed state through its configured source.
Prospective state is created explicitly with `eacl/with` or
`eacl/with-schema`.

Each speculative snapshot carries:

- a unique speculative identity;
- its committed root;
- cumulative relationship and schema effects;
- a completeness disposition for all other answer-affecting effects; and
- request-local diagnostics.

Datomic, Datahike, and DataScript create speculative snapshots from native
in-memory transaction reports, including emitted datoms. Datalevin reports the
capability as unsupported.

Speculative reads skip the exact-basis tier and cannot publish into persistent
cache tiers. They may read a committed managed entry only when its authenticated
proof is complete at the committed root and its dependencies are disjoint from
all cumulative effects. Unknown effects evaluate against the speculative value
without cache reuse or publication. Cursor identity includes the speculative
snapshot identity, preventing replay on committed state or a sibling snapshot.

### D2. Permission migration preserves semantics

The v7-to-v8 permission migration converts stored v7 rows to canonical
denotations and compares each existing permission with the supplied replacement
schema. Additive permissions remain valid. A changed or removed existing
permission fails with a typed semantic-change error while v7 storage remains
active. Structural no-ops report the version actually present.

### D3. Datahike kernel selection preserves realization bounds

Datahike selects the density-bounded range kernel only for direct database
values that honor its seek bound. Wrapped temporal values use the exact-probe
kernel. Both paths implement the same scalar membership decisions, and the
selection boundary is covered in both directions at the density threshold.

### D4. Mutation controls execute production consumers

Each registered mutation changes a named production definition and is detected
through a distinct production consumer with an independently derived expected
result. Registry validation resolves the named detector, verifies consumer
coverage, and rejects constant mutants without a distinct consumer. The
manifest records only registered controls killed by executable evidence.

### D5. Formal and replay gates fail the build

Test-bearing formal subcommands throw when their executed suite reports a
failure or error. The gate self-test exercises both the passing and deliberately
failing directions. Counterexample replay, mutation control, generated-boundary
smoke, and the adjacent formal gates therefore have executable failure paths.

### D6. Relationship observations are opt-in

Relationship-observation telemetry is disabled by default. A default client
allocates no observation store and performs no observation-key work on page,
count, or membership paths. When enabled, observations remain bounded and
prefer current-watermark entries during eviction.

### D7. The formal ledger describes the shipped implementation

The demand-clamped, rejection-gated production batch schedule is proved and
exported through the generated policy. Differential tests drive the production
scheduler against that generated decision. The dense membership path is proved
equal to scalar membership inside its realized span.

The enforced digest closure includes the exported kernel and generated operator
policy. The speculative-cache model proves exact-tier exclusion, publication
exclusion, committed-root proof validation, complete disjoint-effect reuse,
unknown-effect fallback, cumulative effects, same-basis collision safety, and
speculative cursor isolation.

The assurance status remains conditional: Dafny and TLA+ are abstractions, and
the Clojure mapping is an internal source-and-test audit rather than a
mechanized source refinement or independent external certification.

## Resulting public behavior

- Public readers never accept caller native database values.
- `eacl/with` composes prospective application and relationship transaction
  data into immutable speculative snapshots.
- `eacl/with-schema` applies the shared schema replacement plan and supports
  `:orphan-policy :error` and speculative-only `:retain-inert`.
- `eacl/tx-relationship` returns validated paired relationship transaction
  data with relation-version stamping.
- Committed clients retain exact and proof-backed managed caching.
- Speculative snapshots retain safe committed managed reads while never
  publishing speculative work.
