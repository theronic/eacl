# Design: `exists` and `all` in EACL's Caveat language

## Context

Core parses a Caveat's source into a typed plan (`eacl.caveats.plan`),
admits contexts and preflights work (`eacl.caveats.partial`), and evaluates
plans four-valued: true, false, conditional with missing fields and a
residual, or a fault. The JVM module evaluates complete contexts with
cel-parser 0.1.8 through reserved bindings and error-preserving overloads, and
incomplete ones with core's evaluator; the portable module (#205) uses core's
evaluator for both. The definition entity stores its source and profile
version (`eacl-cel/1`); evaluators advertise a profile and a profile
fingerprint that clients, schema admission and qualified caches and cursors
check.

The Phase 2 qualification excluded macros because cel-parser's `exists` and
`all` reparse their predicate for every element. Probing them again shows a
second defect: the predicate is reread from ANTLR's token text, which drops
whitespace, so `item in sensitive` becomes the unknown name `iteminsensitive`.

SpiceDB v1.56.0 was probed through its HTTP gateway on 2026-09-27. It accepts
`exists` and `all` over lists and maps, lets the variable hide a parameter,
lets a true element decide `exists` despite another element's `no such key`
error (and a false one `all`), reports only the range when the range is
missing, and reports per element the fields that still matter when it is
supplied. It rejects a variable that is not a simple name, a reserved name,
`__result__`, a non-list range and a non-Boolean predicate.

## Goals / Non-Goals

**Goals:** SpiceDB's meaning for `exists` and `all` on both evaluators,
including faults and partial evaluation; bounded, predictable work within
the existing limits; unchanged stored content and behavior for Caveats that
do not use the macros; evidence from the corpus, the formal model, Dafny and
SpiceDB.

**Non-Goals:** other macros, two-variable comprehensions, list indexing,
`size` or container literals; changing how profile 1 charges absent operands.

## Decisions

### D1. Plans: a comprehension node and bound variables

`xs.exists(x, p)` parses to `[:exists xs "x" p]` (and `:all`); `xs` is any
expression of list or string-keyed map type, which in the profile is a
parameter, or a literal in a residual. The parser keeps a scope of enclosing
variables and resolves each name to the innermost variable of that name,
`[:var "x"]`, or else to a parameter. Shadowing is therefore settled when the
plan is built: evaluation, residuals and the JVM lowering never compare names
across scopes. A variable must be a parameter-shaped name other than
`__result__`, and a parameter named `__result__` cannot be read inside a
comprehension, since cel-go binds that name to its accumulator and SpiceDB
would read something else. The node counts as one plan node; its variable is
not a node.

Map keys are included. They fit without new machinery: the variable is a
string, a map literal is already a residual value, and keys are visited in
canonical order. SpiceDB supports them.

### D2. Semantics: a four-valued fold

`exists` folds EACL's `||` from false and `all` folds `&&` from true over the
elements' outcomes. So a deciding element wins over faults and missing fields;
otherwise a fault wins (EACL's existing fail-closed order, where cel-go
prefers the unknown); otherwise the result is conditional on the union of the
undecided elements' missing fields; otherwise false for `exists`, true for
`all`, as for an empty range. Evaluation stops at the first deciding element,
which gives the same outcome. The residual is the same comprehension over the
undecided elements only, with supplied parameters and enclosing variables
bound as literals and the variable left free. The Dafny model proves the fold
laws and that this residual preserves the outcome and its missing fields.

A missing range is conditional on the range's missing fields alone, and the
predicate is not evaluated, as in cel-go. Reporting the predicate's absent
parameters too would ask for fields that an empty range never needs, and
would differ from SpiceDB.

### D3. Work: every element, within the existing limit

The preflight charges a comprehension over a supplied range one unit, its
range leaf and size, then one unit plus the predicate's cost for every
element, with the variable's size that of the largest element. Every element
is charged even when an early one decides the result, so admission does not
depend on element order or predicate values, like both branches of `&&`.
Nested comprehensions multiply through the predicate's cost, and each level
saturates. The preflight itself stays linear in the plan: it computes each
predicate's cost once, not per element.

An absent range is charged at its declared maximum size, like every absent
operand, plus one unit per predicate node for the residual copy, and is not
iterated. Charging its 128 possible elements would reject the acceptance case
(an absent `inventory`), whose evaluation does no per-element work.

No limit is added. Ranges hold at most 128 entries, each iteration costs at
least two units, and the existing 1,048,576-unit limit bounds the total. An
absent operand inside the predicate is still charged at its maximum size for
every element, so `xs.exists(x, x in ys)` with `ys` absent exceeds the limit
from two elements on. That is profile 1's absent-operand rule, which this
change does not alter.

### D4. Versioning: per-definition profile, one evaluator profile

`values/profile-id` becomes `eacl-cel/2`, and evaluators advertise it with a
new profile fingerprint. A stored definition records the lowest profile that
admits its source: `eacl-cel/1` without the macros, `eacl-cel/2` with them.
Readers accept both and require the recorded profile to be that lowest one.

- Existing definitions, and new ones without the macros, are byte-identical,
  so the first schema write after an upgrade rewrites nothing and earlier
  Peers keep serving them.
- An earlier Peer rejects a profile 2 definition with `:unsupported-profile`
  before compiling it: fail-closed evaluation faults, and no schema reads or
  writes on that Peer.
- The fingerprint change refuses an evaluator module from an earlier release
  (`:eacl.caveat/evaluator-unavailable`) and keeps qualified cache entries and
  cursors from aliasing across releases.

Rollout follows the wildcard change: upgrade every serving Peer before
writing a schema that uses `exists` or `all`. Writing every definition as
profile 2 was rejected, because an upgraded Peer's next schema write would
break every Caveat on Peers not yet upgraded. Keeping profile 1 as the only
profile id was rejected, because profile identity is part of schema identity
and a profile 1 definition would silently mean more on newer Peers.

### D5. The JVM evaluator folds itself

The adapter lowers the plan to cel-parser programs parsed once, when the
definition is first compiled. Each comprehension becomes a reserved binding
(`__eacl_cN`) in the enclosing program, and its predicate a program of its own
with the variable as another reserved binding (`__eacl_vN`). Evaluation fills
each comprehension's binding by running its predicate program per element and
folding CEL's way: first deciding element, else first fault, else the unit.
The enclosing program's `&&` and `||` absorb a faulted comprehension as they
absorb any faulted operand. Profile 2 still has one runtime fault, a missing
map key, so which fault is kept cannot differ from core's rule. Bindings are
built as typed cel-parser values once per evaluation, so an element does not
translate the context again. The adapter version becomes
`eacl.caveats.jvm/4`.

Using cel-parser's macros was rejected for their per-element reparse and
whitespace-free rereading. Routing comprehension plans to core's evaluator was
rejected because the JVM module is the independent reference that the
portable evaluator is certified against.

### D6. Evidence

- The shared corpus gains 35 cases: true, false, empty lists, faults inside
  predicates, nesting, shadowing, map keys, missing ranges and predicate
  fields, bound contexts, the work limit and rejections. Both evaluators and
  the model must give each case's outcome; the exploration gate records
  cel-parser's own (often wrong) macro answers as `:native`.
- `formal/caveats/model.clj` evaluates comprehensions independently;
  bridges compare it with the production evaluator and the JVM adapter on
  exhaustive element-outcome sequences and scopes; three registered mutation
  controls cover fold absorption, absent-range missing fields and
  per-element work, each killed in the model and in production.
- `CaveatOutcomes.dfy` proves the fold laws; `CaveatProfile.dfy` proves the
  comprehension work bound and its multiplication through nesting.
- The portable module's generated differential test (4,000 definitions)
  generates comprehensions, including shadowing variables.
- `formal/fixtures/caveat-comprehensions/` records SpiceDB v1.56.0's answer to
  every corpus case; `eacl.caveats.portable.spicedb-test` compares both
  evaluators and fails on any difference not listed with its reason.

## Risks / Trade-offs

- **Fault before missing field** → where cel-go reports a missing field,
  EACL reports a fault, as it already does for `&&` and `||`. Recorded as a
  listed divergence.
- **Absent operands inside predicates** → charged per element at their
  maximum size, so a comprehension over two or more elements that tests an
  absent list is rejected with `:resource-limit` where SpiceDB is
  conditional. Changing the absent-operand rule is left to the owner.
- **SpiceDB defects** → with a parameter missing, cel-go treats a
  comprehension variable of the same name as unknown, and indexing a missing
  map with a variable is an evaluation error. EACL gives the CEL answer and
  the fixture records the difference.
- **Wall-clock cost** → the work limit bounds admitted iterations, not
  latency. On the JVM each element costs a cel-parser program run.
- **Rollout** → a schema using the macros written before every Peer is
  upgraded makes earlier Peers fail closed on that Caveat.
