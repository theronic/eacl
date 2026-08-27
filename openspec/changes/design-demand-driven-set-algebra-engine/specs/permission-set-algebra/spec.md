## Purpose

Defines the accepted EACL v8 permission set-algebra language and its exact meaning for checks, lookups, counts, recursion, ordering, and failure.

## ADDED Requirements

### Requirement: Permission expressions preserve operator structure
EACL SHALL accept nested union (`+`), intersection (`&`), exclusion (`-`), parentheses, named permissions, relations, and existing supported one-hop arrows. Union SHALL bind more tightly than intersection, intersection SHALL bind more tightly than exclusion, repeated exclusion SHALL associate from the left, and parentheses SHALL override those rules.

#### Scenario: Mixed precedence
- **WHEN** a schema declares `permission p = a + b & c - d`
- **THEN** EACL interprets it as `((a + b) & c) - d`

#### Scenario: Left-associated exclusion
- **WHEN** a schema declares `permission p = a - b - c`
- **THEN** EACL interprets it as `(a - b) - c`

### Requirement: Set operators have one denotation across operations
For one immutable snapshot and one concrete result type, union SHALL denote set union, intersection SHALL denote membership in every operand, and exclusion SHALL denote membership in its left operand and non-membership in its right operand. Point checks, detailed checks, forward and reverse lookups, filters, bounded and exact counts, completed answers, and cache hits MUST agree with that denotation.

#### Scenario: Nested operator agreement
- **WHEN** a nested operator permission is evaluated through every public authorization operation
- **THEN** every operation agrees on the same authorized set and exact membership decisions

#### Scenario: Empty intersection
- **WHEN** no typed entity belongs to every intersection operand
- **THEN** the permission denotes the empty set rather than an operational failure

### Requirement: Positive recursion uses the least fixed point
Every recursive component containing only positive union, intersection, permission, relation, and arrow dependencies SHALL denote the least fixed point reached from the empty derived relation over the finite selected snapshot. An active evaluation marker, unfinished join, missing cache entry, or first unsuccessful visit MUST NOT be treated as a completed false result.

#### Scenario: Later intersection premise
- **WHEN** one recursive intersection premise is derived in a later fixed-point wave
- **THEN** the intersection grant is derived once all premises hold

#### Scenario: Unseeded positive cycle
- **WHEN** a positive recursive component has no base derivation
- **THEN** it derives no grant

### Requirement: Exclusion is strictly stratified
The complete expression dependency graph SHALL mark an exclusion-right dependency as negative. A valid schema MUST have no strongly connected component containing a negative edge, and every negative dependency SHALL target a strictly lower stratum whose answer is complete before absence is consumed.

#### Scenario: Acyclic exclusion over recursion
- **WHEN** an exclusion right operand reaches a completed lower positive-recursive component and no dependency returns to the excluding component
- **THEN** the schema is accepted and exact lower-stratum membership governs exclusion

#### Scenario: Negative cycle
- **WHEN** any permission can reach itself through one or more dependencies including an exclusion-right edge
- **THEN** schema writing fails atomically with a deterministic typed unstratified-exclusion error

### Requirement: Operator result order is deterministic and resumable
An operator lookup SHALL return the exact permission denotation in the least-derivation-path order of its sealed candidate-cover plan, filtered without reordering. The selected snapshot, semantic expression, candidate-cover identity, and order ABI SHALL determine that order independently of cache contents, timing, backend iteration accidents, and host map order. Union-only plans SHALL retain their existing order and plan domain.

#### Scenario: Cache parity
- **WHEN** the same operator lookup runs with a cold cache, a warm cache, and `:cache? false` on the same snapshot
- **THEN** the result order and page boundaries are identical

#### Scenario: Union-only compatibility
- **WHEN** a previously accepted union-only permission is evaluated after operator support is installed
- **THEN** its result sequence, cursor interpretation, and denotation remain unchanged

### Requirement: Existing unsupported constructs remain rejected
Adding set algebra SHALL NOT accept direct chained arrows, `.all()` intersection arrows, caveats, wildcard subjects, subject relations, `nil`, or `self`. A permission reached through one supported arrow MAY itself contain accepted operators and named permission references.

#### Scenario: Chained arrow remains unsupported
- **WHEN** a schema directly declares `relation->subrelation->permission`
- **THEN** schema validation returns the existing unsupported-arrow error class
