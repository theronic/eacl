## Context

Phase 1 supplies one v9 endpoint pair with an optional trailing `qualifier-eid`, but only `nil` is legal in current-serving data. This phase defines the data and evaluator behind that reference. Phase 3 will be the first change allowed to route a non-`nil` qualifier through authorization.

The selected JVM library, `com.exoscale/cel-parser` 0.1.8, provides `make-program` and `eval-for`; its evaluator returns error objects by default, has documented CEL divergences, and is not a ClojureScript library. EACL therefore needs a narrow compatibility profile and cannot equate “the library evaluated something” with SpiceDB-compatible Caveat semantics.

## Goals / Non-Goals

**Goals:** sparse qualifiers, one stored Caveat expression per named definition, typed and bounded schema admission, canonical bound context, correct partial outcome semantics, an explicit JVM evaluator capability, formal models before production code, and post-implementation certification without runtime model checks.

**Non-Goals:** traversal integration, changing final authorization outcomes, cache/cursor time coherence, `valid-until` enforcement, physical expiry collection, multiple Caveats per Relationship, multiple parallel Relationships differing by Caveat, shared qualifier instances, a general CEL implementation, or a bundled ClojureScript CEL interpreter.

## Decisions

### 1. Enforce a model-before-production gate

After a read-only dependency/profile qualification pass establishes the exact supported CEL subset and known library divergences, the first implementation gate is an abstract, finite Caveat/qualifier model independent of database and ANTLR classes. It defines:

- one logical Relationship with zero or one qualifier;
- immutable qualifier replacement and single ownership;
- named shared Caveat definitions and typed parameters;
- canonical merge where Relationship-bound values override request values;
- true, false, conditional/residual, and evaluator-fault outcomes;
- short-circuit/partial behavior for the supported Boolean expression profile;
- qualifier schema validity, including context without Caveat being invalid;
- no duplicate Relationship identity caused by qualifier differences.

All affected fast formal commands and mutation controls must be green before production source under `modules/` is edited for this phase. Read-only library inspection, corpus execution in isolated exploration code, parser experiments, and model/oracle work are allowed before the gate; none may create a production authorization path.

After implementation, a refinement/differential bridge compares the production normalizer, context merge, profile checker, and evaluator wrapper with the model over exhaustive finite domains and generated larger cases. The model is never called by production authorization, schema writes, or qualifier reads.

### 2. Store one sparse immutable qualifier entity per qualified Relationship

A qualifier entity has no public `:eacl/id` and at least one semantic field:

```clojure
{:db/id q
 :eacl.relationship-qualifier/format-version 1
 :eacl.relationship-qualifier/caveat          caveat-eid        ;; optional ref
 :eacl.relationship-qualifier/caveat-context  canonical-payload ;; optional string
 :eacl.relationship-qualifier/valid-until-ms  epoch-ms}         ;; optional long
```

The format marker is mandatory and all semantic attributes are asserted in one qualifier-creation transaction. Each adapter exposes an immutable `qualifier-version` for future caching, preferably the assertion `t` of that marker. A backend whose read API cannot expose a trustworthy creation `t` may persist one immutable creation-generation value or restrict qualifier reuse to the selected exact snapshot. EACL does not add a universal generation datom merely to make every backend look identical.

An empty map creates no entity and normalizes the endpoint component to `nil`. Context without a Caveat is invalid. `valid-until-ms` may exist without a Caveat. A Caveat may exist with empty/no bound context.

A qualifier is referenced by exactly the forward and reverse halves of one logical Relationship. It is never deduplicated across Relationships. An admitted update creates a new entity; it does not mutate the old one. This avoids shared invalidation, reference counting, and ambiguous deletion.

### 3. Make qualifier-reference publication an explicit backend capability

EACL must not assume that every database resolves a newly allocated entity id, string tempid, or lookup ref when it appears inside a heterogeneous tuple/vector value. Phase 2 therefore adds a conformance-tested writer capability with two allowed strategies:

1. **Inline allocation:** the backend proves with its real transaction API that one transaction can create the qualifier and store its resolved eid in both endpoint values.
2. **Prepared reference:** EACL first creates an immutable, unreferenced qualifier, obtains its concrete eid from the transaction report, and then publishes or swaps both endpoint values plus the Relation mutation stamp in one atomic Relationship transaction.

A prepared qualifier is inert: no endpoint tuple points to it, so a failure before publication leaves storage garbage, never an authorization edge. A bounded orphan cleanup operation may remove such entities later. The committed convenience writer may hide the prepare/publish sequence. A consumer composing `eacl/tx-relationship` with application datoms on a backend that needs preparation must first obtain an opaque prepared-qualifier handle; the final application transaction still atomically publishes both Relationship halves and its domain facts.

The publication transaction never exposes one qualified half without the other. It must use a concrete resolved eid, not an unresolved nested lookup ref or tempid. A backend supporting neither strategy cannot advertise qualified Relationship writes and Phase 3 cannot activate them there.

Deleting a qualifier entity out of band does not change a stored non-`nil` tuple component into `nil`. Regardless of a backend's native dangling-ref representation, EACL's decoder must preserve the distinction: non-`nil` plus missing target is an authoritative fault, never an unconditional Relationship.

### 4. Keep Caveat definitions shared and schema-owned

One named Caveat definition is a schema entity addressed by the existing schema identity convention. It contains:

```clojure
:eacl.caveat/name
:eacl.caveat/parameters-payload
:eacl.caveat/expression-source
:eacl.caveat/profile-version
```

The parameter payload is canonical, ordered, and typed. The expression source is stored once per Caveat name. Every Relationship qualifier points to this entity. Two Relationships using the same named Caveat therefore share expression storage and the client program cache.

This phase does not add expression interning across differently named Caveats. A named definition already removes per-Relationship duplication, while cross-name interning introduces another entity lifecycle, uniqueness protocol, and diagnostic indirection without evidence that expression text dominates storage.

Schema replacement may update a named Caveat only through `write-schema!`, atomically advancing schema generation and validating all Relation references. Historical backends retain the old definition in historical views according to their normal schema-history contract.

### 5. Extend the SpiceDB schema surface deliberately

The parser accepts typed top-level declarations and Relation branch references in the supported subset, for example:

```zed
caveat region_match(required_region string, request_region string) {
  request_region == required_region
}

definition device {
  relation viewer: user with region_match
}
```

Exact types and functions are limited to a versioned EACL Caveat profile. Schema admission rejects unknown Caveats, duplicate declarations, undeclared variables, non-Boolean root expressions, unsupported CEL constructs/functions/types, parameter-name collisions, oversized source/AST/context bounds, and incompatible Relation branches before durable replacement.

Relation alternatives retain SpiceDB's required/optional distinction. `user with region_match` requires that Caveat; `user | user with region_match` permits either plain or Caveated input. Alternatives sharing one subject type are grouped into one Relation identity with an explicit allowance set. An expiry-only qualifier has no Caveat and therefore requires a plain alternative. The finite model includes the plain alternative explicitly rather than admitting `nil` for every Relation.

The profile version is part of schema identity, evaluator fingerprint, cache/cursor identity in Phase 3, and conformance fixtures. Expanding the profile is a compatibility change, not an accidental consequence of upgrading a dependency.

### 6. Use cel-parser through a narrow JVM adapter

Pin `com.exoscale/cel-parser` 0.1.8 and its resolved transitive versions. The adapter:

- calls `make-program` for syntax/program construction;
- calls `eval-for` only through bounded EACL-owned bindings and options;
- detects the library's returned error values and never relies on Clojure truthiness;
- converts supported EACL values explicitly to/from the selected CEL profile;
- rejects documented library divergences outside the profile;
- records a deterministic evaluator/profile fingerprint;
- caches successful immutable programs by canonical definition identity;
- never persists ANTLR parse trees, visitors, closures, or library-specific values.

Program construction is lazy-capable for Phase 3 but schema admission still performs enough parsing/static validation to reject invalid durable definitions. The program cache is bounded, process-local, and an optimization only.

### 7. Own partial and conditional semantics in EACL

A Caveat check cannot collapse every missing parameter to false. CEL short-circuiting can produce a definite result even with unavailable variables, and SpiceDB exposes conditional permissionship when missing values may matter.

EACL derives a bounded canonical Caveat plan/residual model from the parsed source. Complete contexts may use the cel-parser program as the execution engine. Incomplete contexts use EACL's formally specified partial evaluator, which returns:

```clojure
{:outcome :true}
{:outcome :false}
{:outcome :conditional
 :missing-fields #{...}
 :residual canonical-residual}
{:outcome :error
 :reason ...}
```

An exception, overload failure, unsupported construct, wrong type, malformed stored context, or budget exhaustion is `:error`, not `:conditional`. The production wrapper is certified against the abstract outcome model and a SpiceDB-derived compatibility corpus.

### 8. Store Relationship-bound context once and merge with SpiceDB precedence

The qualifier's context attribute contains one bounded canonical portable map. Request context is not persisted. During future evaluation:

```text
effective-context = request-context overlaid by relationship-bound context
```

so a bound value wins on duplicate parameter names. Both maps are validated against the Caveat's declared parameter types; unknown keys and malformed values fail at their respective admission boundaries.

The context payload is one datom on the sparse qualifier entity, not copied into both endpoint tuples. Empty bound context is omitted.

### 9. Keep `valid-until` inert but correctly typed

`valid-until-ms` is an optional exact epoch-millisecond integer in the shared safe domain. This phase validates its type/range and includes it in immutable qualifier normalization, equality, integrity, and portable diagnostics. It has no authorization meaning until Phase 3.

No global expiry index or owner sidecar is added. Physical collection is not needed for authorization correctness and cannot be designed independently of the Phase 3 cache/write semantics.

### 10. Stage storage without enabling unsafe serving

Production public Relationship writers continue to reject non-`nil` qualifiers with a typed “qualified relationships not activated” error. Internal planners, migrations for test fixtures, and speculative model harnesses may exercise qualifier construction only through explicitly non-serving test/internal boundaries.

A serving client encountering non-`nil` q data before Phase 3 still fails closed. Caveat definitions may be persisted because they have no effect without qualified Relationships, but a Relation declaration requiring Caveats cannot be activated for serving until the evaluator capability and Phase 3 semantic epoch are enabled.

This prevents a release where the database contains a Caveat but the graph silently ignores it.

### 11. Keep the core and CLJS dependency boundary clean

The portable schema model, qualifier format, evaluator protocol, outcome values, and formal model live in backend-neutral code. The cel-parser dependency and ANTLR classes live in a JVM evaluator module or JVM-only source path.

JVM bundled adapters may install the default evaluator. DataScript CLJS may:

- use ordinary Relationships and portable qualifier/schema tooling that does not load the JVM dependency;
- use expiry-only evaluation in Phase 3;
- activate Caveats only with a separately supplied evaluator whose profile fingerprint and conformance suite match the schema.

Absent or mismatched evaluator capability is detected before serving a schema that can reach Caveated Relationships. It never degrades to unconditional access.

## Risks / Trade-offs

- **cel-parser is small but not a full SpiceDB Caveat implementation** → pin a narrow profile, own partial semantics, and differentially test every supported type/operator.
- **A canonical partial evaluator adds code** → keep it limited to the admitted profile and prove it before routing; do not mirror all CEL.
- **JVM-only default evaluator reduces CLJS parity** → advertise capability explicitly and fail before activation rather than ship divergent semantics.
- **Sparse entities add reads for qualified edges** → only 5–10% are expected qualified; Phase 3 benchmarks request-local/bounded caches before activation.
- **Qualifier immutability creates abandoned historical entities** → admitted `:touch` retracts current ownership/entity facts; history retention follows backend policy.
- **Nested ref allocation differs across backends** → require an observed writer capability and use safe prepare-then-publish where needed; an unattached qualifier is inert and never justifies a dual-read or half-pair state.
- **A missing qualifier target could be confused with `nil`** → preserve the tuple's non-`nil` component and classify failed resolution as an authoritative fault.
- **Staged definitions without serving can confuse operators** → document the phase boundary and keep non-`nil` Relationship writes disabled.

## Migration Plan

1. Apply Phase 1 and confirm storage ABI 9 before beginning this change.
2. Complete and lock the abstract qualifier/Caveat formal model and mutation controls; do not edit production qualifier/Caveat source before the gate is green.
3. Add schemas and portable codecs additively; no existing ordinary Relationship is rewritten.
4. Qualify each bundled backend's concrete-eid publication behavior and implement either certified inline allocation or safe prepare-then-publish; leave unsupported backends incapable of qualified writes.
5. Add Caveat schema parsing, validation, JVM evaluator adapter, partial evaluator, qualifier planner, and integrity tooling behind the disabled activation boundary.
6. Complete production-to-model refinement, cel-parser differential tests, publication-capability conformance, SpiceDB compatibility fixtures, isolated module builds, and source-closure review.
7. Release only as a foundation for Phase 3; keep current-serving qualified Relationship writes and traversal disabled.
