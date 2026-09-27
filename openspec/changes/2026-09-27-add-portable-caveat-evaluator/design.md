# Design: portable Caveat evaluator

## Context

`eacl.caveats.evaluator/Evaluator` has two methods, `descriptor` and
`-evaluate`. `require-matching!` admits an implementation whose descriptor names
profile `eacl-cel/1`, the exact profile fingerprint, capability version 1 and a
non-empty implementation fingerprint. The JVM module registers such an
implementation when its namespace loads. For complete contexts it renders the
admitted plan as a fully grouped cel-parser program. Literals and parameters
become bindings, and fixed overloads preserve operand errors. For incomplete
contexts it calls `eacl.caveats.partial/evaluate-prepared`.

## Goals / Non-Goals

**Goals:** identical outcomes, reasons, missing fields and residuals to the JVM
evaluator for every admitted definition, request context and bound context;
ClojureScript and JVM from one source; no new dependency; clean `:advanced`
compilation. The evaluator uses APIs already published in `8.0.0-RC-2026-09-12`,
but the coordinated core includes the context normalization fixes below.

**Non-Goals:** a general CEL interpreter, profile extensions, or changes to the
JVM evaluator.

## Decisions

### 1. A separate optional module, not core

Core deliberately "never loads a platform implementation"; evaluators are
opt-in modules that register themselves. A sibling of `eacl-caveats-jvm` keeps
that boundary. It lets an application add the evaluator as one more dependency,
now through `:local/root` with core pinned, later from Clojars, without
upgrading core. The jar isolation audit keeps each evaluator out of every other
artifact.

### 2. Evaluate with core's plan evaluator in both paths

For typed plans, cel-parser and the plan evaluator differ only in how runtime
faults combine. Profile 1 has one runtime fault: indexing a supplied map at an
absent key (`no such key`, `:missing-map-key`). Every other overload failure is
excluded by static typing and admission. Both apply commutative absorption for
`&&` and `||` and preserve faults through `!` and other operators. They also
agree on list and map membership, string searches in UTF-16 code units, integer
and timestamp order, and exact string equality. The evaluator therefore reuses
`partial/prepare-evaluation` and `partial/evaluate-prepared` unchanged, with the
JVM module's exact validation order and exception classification. Running
cel-parser's semantics a second time would duplicate core.

### 3. Canonical host values

The JVM module converts admitted values to native bindings: `(long n)`, a
`java.lang.Boolean` read through its value, and plain maps. Core's plan
evaluator reads values as given. On the JVM a `(Boolean. false)` object passes
admission and is truthy to Clojure, and a sorted map's comparator can match a
key that differs from the stored one. In both cases the JVM module's complete
path and partial path disagree. The portable evaluator rebuilds admitted values
canonically, by declared type, after admission and before evaluation. It
matches cel-parser for complete contexts and stays consistent for incomplete
ones. Admission, sizes and work are computed before this step and are unchanged.
In ClojureScript, `boolean?` rejects boxed booleans, so only the map case
applies.

Adversarial review found that normalization after merging is too late: a
request map comparator can alias parameter names, and nested map equality can
discard a bound override. Core now rebuilds admitted contexts before merging
and before public parameter projection and cache identity. Boolean encoding
also canonicalizes boxed values, preventing opposite values from sharing an
identity or producing malformed bound payloads. Prepared contexts retain their
original immutable input reference so public operations still admit it once.
This also fixes the JVM evaluator's partial path. Consumers need the updated
core for these guarantees.

### 4. Registration never displaces an earlier evaluator

Requiring the namespace registers its process default only if none is
registered. The JVM module always registers, so with both loaded the certified
JVM evaluator is the default whatever the load order. An application that
registered a custom evaluator keeps it. In ClojureScript, the target runtime,
requiring the namespace registers it as the default. Clients may always pass
`:caveat-evaluator`.

### 5. Bounded plan cache

Decoded plans are retained in `eacl.cache.standard-lru` (Caffeine on the JVM,
cljs-cache in ClojureScript), keyed by the implementation fingerprint and the
complete definition content, capacity 256 or a lower `:max-entries`. Failed
decodes are not retained. Construction is not coalesced: ClojureScript is
single-threaded, and on the JVM a concurrent miss may decode twice, which
changes work only.

### 6. Identity

`implementation` names the adapter, the evaluation strategy and canonical host
values; it pins no library artifact. Its digest is the descriptor's fingerprint,
distinct from the JVM module's, so cached answers never alias across evaluators.

### 7. Conformance evidence

- The shared corpus, inlined at compile time by a macro, runs on the JVM and on
  Node through the DataScript ClojureScript runner.
- A JVM-only test.check property (4,000 cases, fixed seed) generates
  well-typed plans over every operator and type, renders them as source,
  checks that the source parses back to the same plan, and compares outcomes
  with `eacl.caveats.jvm`. Inputs are complete, split, incomplete,
  bound-over-request and wrongly typed contexts. Finite enumerations cover
  four-valued logic with missing keys and absent inputs, boundary integers and
  timestamps, resource limits and malformed definitions.
- End-to-end DataScript tests use the public client: checks, `can?`, detailed
  lookups and counts, expiry, bound-over-request and reported faults, with an
  explicit evaluator and with the process default.

## Risks / Trade-offs

- **Core evolves separately** → the module calls only published core functions;
  the differential test detects semantic drift against the JVM module.
- **The corpus lives in the JVM module's test resources** → the ClojureScript
  test aliases add that directory rather than moving a fixture shared with the
  formal and exploration gates.
- **The DataScript ClojureScript runner now registers an evaluator** → tests
  that require fail-closed behaviour already pass `:caveat-evaluator nil`.
- **Conservative work preflight** → a Caveat with two membership tests against
  an absent `list<string>` exceeds the limit and faults instead of returning
  conditional. That is profile 1 behaviour in both evaluators; the README
  explains it.
