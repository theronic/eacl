## ADDED Requirements

### Requirement: Caveat expressions may use exists and all
EACL CEL profile 2 SHALL admit `range.exists(x, p)` and `range.all(x, p)` when
`range` has a list or string-keyed map type and `p` is Boolean, ranging over
the list's elements or the map's keys. Both evaluators SHALL give them CEL's
and SpiceDB's meaning: `exists` folds `||` from false and `all` folds `&&` from
true over the elements' outcomes, with EACL's four-valued absorption. The
variable SHALL be visible only inside `p`, where it hides a parameter or outer
variable of the same name. Other macros, and a variable or a parameter read
inside a comprehension named `__result__`, SHALL be rejected at schema write.

#### Scenario: A deciding element absorbs another element's fault
- **WHEN** one element's predicate is true and another's indexes a supplied map at an absent key
- **THEN** `exists` is true, and `all` over a false and a faulted element is false

#### Scenario: Faults and missing fields without a deciding element
- **WHEN** no element decides the result and one element faults
- **THEN** the result is an error, even if another element needs a missing field
- **AND** without a fault, it is conditional on the union of the undecided elements' missing fields, with a residual over those elements only

#### Scenario: Empty range
- **WHEN** the range is supplied and empty
- **THEN** `exists` is false and `all` is true without evaluating the predicate

#### Scenario: Missing range
- **WHEN** the range parameter is absent
- **THEN** the result is conditional on the range alone, whatever the predicate reads

#### Scenario: Shadowing
- **WHEN** a comprehension's variable has the name of a parameter or of an enclosing comprehension's variable
- **THEN** the name means the innermost variable inside the predicate and the parameter outside it

#### Scenario: Rejected forms
- **WHEN** a Caveat uses `has`, `exists_one`, `map` or `filter`, a variable that is not a simple unreserved name, `__result__`, a range that is not a list or map, or a non-Boolean predicate
- **THEN** schema validation rejects it with a typed profile error

### Requirement: Comprehension work is preflighted per element
The work preflight SHALL charge a comprehension over a supplied range for the
range once and for one unit plus the predicate's cost for every element, with
the variable at the size of the largest element, even when an early element
decides the result. Nested comprehensions SHALL multiply. An absent range
SHALL be charged at its declared maximum size and SHALL NOT be iterated. The
existing work limit SHALL bound the total; no new limit is required.

#### Scenario: Supplied range
- **WHEN** a comprehension's predicate work times its range's element count exceeds the work limit
- **THEN** evaluation fails with `:resource-limit` before any predicate is evaluated

#### Scenario: Absent range
- **WHEN** the range of `!inventory.exists(item, item in sensitive)` is absent and `sensitive` is supplied
- **THEN** the preflight admits it and the result is conditional on `inventory`

### Requirement: Definitions record the lowest profile they need
A stored Caveat definition SHALL record `eacl-cel/1` when its expression uses no
comprehension and `eacl-cel/2` when it does. Readers SHALL accept both and
SHALL reject a definition whose recorded profile is not the lowest that admits
its source. Evaluators SHALL advertise profile `eacl-cel/2` with a profile
fingerprint distinct from profile 1's.

#### Scenario: Existing definitions are unchanged
- **WHEN** a schema whose Caveats use no comprehension is written after the upgrade
- **THEN** its definitions are byte-identical to those written before it and no definition is replaced

#### Scenario: Earlier Peer reads a profile 2 definition
- **WHEN** a Peer from an earlier release evaluates a Caveat recorded as `eacl-cel/2`
- **THEN** it fails closed with `:unsupported-profile` rather than evaluating it

#### Scenario: Evaluator from an earlier release
- **WHEN** a client is given an evaluator that advertises profile 1's fingerprint
- **THEN** it is refused with `:eacl.caveat/evaluator-unavailable`

### Requirement: The JVM evaluator parses comprehension predicates once
For complete contexts, the JVM evaluator SHALL evaluate a comprehension by
running its predicate, parsed once with the definition's programs, for each
element and folding the results as CEL does. It SHALL NOT use cel-parser's
macros, which reparse the predicate per element from whitespace-free text.

#### Scenario: Warm evaluation
- **WHEN** a definition with nested comprehensions is evaluated repeatedly over ranges of any length
- **THEN** no CEL source is parsed after the definition's programs are built, and the outcome equals the portable evaluator's
