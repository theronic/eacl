# Add `exists` and `all` to EACL's Caveat language

## Why

EACL evaluates named Caveats in EACL CEL profile 1, which excludes CEL's
macros. A Caveat therefore cannot relate two lists supplied by the caller. The
owner's blog demo needs one that takes two lists from the caller and denies
when they intersect. In the owner's words: "The list of sensitive items should
not be encoded in the schema, just a list of sensitive items and user's
inventory, i.e. the caller passes in what's sensitive." That needs:

```
caveat nothing_sensitive(inventory list<string>, sensitive list<string>) {
  !inventory.exists(item, item in sensitive)
}
```

SpiceDB, through cel-go, accepts this Caveat. Profile 1 rejects it when the
schema is written.

## What Changes

- Add EACL CEL profile 2: profile 1 plus `range.exists(x, p)` and
  `range.all(x, p)` over a list's elements or a map's keys, with CEL's and
  SpiceDB's meaning, on both evaluators. The variable is lexically scoped and
  hides a parameter or outer variable of the same name. `has`, `exists_one`,
  `map` and `filter` stay excluded, as do a variable named `__result__` and a
  parameter of that name read inside a comprehension (cel-go binds it to the
  macro's accumulator).
- Evaluate the macros with CEL's error absorption (a deciding element wins over
  another element's fault), EACL's existing fault-before-missing order, and
  partial evaluation: a missing range is conditional on that range alone, as
  in SpiceDB; a supplied range with undecided elements is conditional on their
  missing fields, with a residual over those elements.
- Charge comprehensions in the existing work preflight: every element of a
  supplied range, multiplied through nesting; an absent range at its declared
  maximum size, without iterating. No limit is added.
- Record in each stored definition the lowest profile that admits it:
  `eacl-cel/1` without the macros, `eacl-cel/2` with them. Evaluators
  implement and advertise profile 2, whose profile fingerprint differs.
- The JVM evaluator parses each comprehension's predicate once and folds it
  over the range, instead of cel-parser's macros, which reparse the predicate
  for every element from whitespace-free token text.
- Extend the shared corpus, the formal model and Dafny proofs, the model and
  production bridges and mutation controls, the generated differential test,
  and record SpiceDB v1.56.0's answers to every corpus case.
- Document the language, semantics, cost, rollout and differences from SpiceDB.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `relationship-caveats`: Caveat expressions may use `exists` and `all`;
  definitions record the profile they need; the JVM evaluator does not reparse
  predicates.

## Impact

Core: `eacl.caveats.values`, `eacl.caveats.plan`, `eacl.caveats.partial`,
`eacl.caveats.definition` (profile id, parser, type checker, plan codec,
partial evaluator, work preflight, definition profile). JVM module:
`eacl.caveats.jvm` (comprehension lowering, typed bindings; adapter
`eacl.caveats.jvm/4`). The portable module's source is unchanged; it serves
core's profile. Tests in core, both evaluator modules and `formal/caveats/`;
Dafny `CaveatOutcomes.dfy` and `CaveatProfile.dfy`; the SpiceDB fixture
`formal/fixtures/caveat-comprehensions/`; the exploration corpus count; docs.

Stored definitions without the macros are unchanged, so an upgrade rewrites no
schema. The profile fingerprint changes, so evaluator modules must be the same
release as core, and cached qualified answers and qualified cursors are not
reused across the upgrade. Every serving Peer must run this release before a
schema that uses `exists` or `all` is written; an earlier Peer fails closed on
such a Caveat with `:unsupported-profile`.

## Non-goals

Other macros, arithmetic, list indexing, `size`, container literals or any
other widening of the profile; changing profile 1's work charges for absent
operands; publishing, tagging or deploying.
