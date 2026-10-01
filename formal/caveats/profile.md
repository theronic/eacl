# EACL CEL profiles 1 and 2

This is the model contract for Phase 2, selected from the pinned independent
qualification in `exploration/caveats/`. It does not activate serving. The
machine-readable limits and operation inventory are in `profile.edn`.
Profile 2 is profile 1 plus the `exists` and `all` comprehensions described
at the end; everything else below applies to both.

## Values, source, and identity

Scalar types are `bool`, `int`, `string`, and `timestamp`. Source declarations
use `list<T>` and SpiceDB's `map<T>` (string keys), where T is one scalar type.
The portable map type descriptor is `[:map :string T]`. No container nesting
or implicit numeric/string/time coercion is allowed. Integers are exact in
[-9007199254740991, 9007199254740991]. Timestamps and `valid-until-ms` are exact
epoch milliseconds in [-62135596800000, 253402300799999] (UTC years 0001–9999).
The canonical timestamp value is `[:timestamp epoch-ms]`; host Date objects
and textual timestamp parsing are not part of portable persisted values.

Context keys are declared parameter-name strings. Strings contain valid Unicode
scalar values, with no unpaired UTF-16 surrogates and no Unicode normalization.
Canonical contexts are ordered vectors of `[name typed-value]` pairs, sorted
by ASCII parameter name. Canonical values are tagged vectors: `[:bool b]`,
`[:int n]`, `[:string s]`, `[:timestamp n]`, `[:list T values]`, or
`[:map T pairs]`. Map pairs are sorted by Unicode scalar order and have unique
string keys. Containers retain their declared type when empty. Encoding uses
a versioned EDN vector envelope and a portable fixed string-escaping/integer
writer; it never relies on unordered map printing or runtime tagged objects.
Decoding checks bounds and canonical re-encoding; a merely parseable payload
is insufficient. Request and bound input maps are both validated before merge;
bound values win. Unknown keys and wrong supplied types are admission errors,
including when an expression could otherwise short-circuit them away.

Names use ASCII `[A-Za-z_][A-Za-z0-9_]*`, at most 64 bytes. CEL keywords, type
names, and names beginning `__eacl_` are reserved. Parameter declarations are
sorted by name. Definition identity includes its name, canonical parameters,
source with CRLF/CR converted to LF, profile version, and semantic adapter
version. The profile version is the lowest that admits the source, so a
definition without comprehensions is profile 1 and keeps its stored content. Whitespace/source changes may invalidate identity harmlessly. The
evaluator fingerprint additionally pins the candidate and ANTLR artifacts and
the value/literal adapter; a dependency update cannot retain the old identity.

## Accepted expressions

The root must statically be Boolean. Accepted forms are parameters, Boolean
and decimal safe-integer literals, JSON-style double-quoted string literals,
parentheses, `!`, `&&`, `||`, scalar `==`/`!=`, int/timestamp ordering,
scalar membership in a same-typed list, string key membership in a map,
string-key map indexing (`m[k]` or `m.key`), and the string methods
`contains`, `startsWith`, and `endsWith`. `m.key` indexes with the string
literal `"key"`, admitted under the same bound as a quoted literal. Indexing a
supplied map at an absent key is an error. An absent map parameter is missing
context. Timestamp values come from typed parameters; timestamp
constructors/selectors are excluded.

Repeated ungrouped unary operators are rejected. Negative decimal integers
are literals, not general arithmetic negation. Comparisons require equal
scalar types, and ordering is restricted to int/timestamp. Strings compare
for equality without normalization. Container literals, list indexing,
aggregate equality, arithmetic, ternary, macros other than profile 2's
`exists` and `all`, regex, size, string ordering,
conversions, durations, doubles, uints, bytes, null, dyn/any, optional values,
and protobuf/custom functions are rejected. Unsupported syntax is never
forwarded speculatively to the library to decide whether it works.

The portable bounded parser produces EACL plan vectors, independent of ANTLR.
The JVM adapter constructs a fully parenthesized CEL program from that plan.
Literal values become reserved internal bindings, avoiding cel-parser's string
unescape transformations. This lowering is part of the fingerprint and has
independent literal/binding fixtures. The adapter still uses `make-program`
and `eval-for` for complete evaluation; no ANTLR objects are durable. Statically
valid incomplete input is evaluated by EACL's partial evaluator.

## Outcomes and bounded progress

Outcomes are `{:outcome :true}`, `{:outcome :false}`, or
`{:outcome :conditional :missing-fields #{names} :residual plan}`, or
`{:outcome :error :reason keyword}`. Residuals use the same portable plan
format, substitute known values, eliminate Boolean identities, preserve
operand order, and collect only still-relevant missing parameter names.
Repeated evaluation produces identical residuals. This is bounded structural
simplification, not a SAT solver: correlated unknown expressions may remain
conditional. That limitation is explicit, including `a || !a` with a absent.

Logical `&&` has a false absorber and `||` a true absorber on either side,
including runtime map-access errors. Otherwise a known runtime fault is an
error, never a missing parameter; an unknown operand produces a residual only
in the absence of an unmasked fault. Other operations propagate a fault before
unknown. Multiple faults have a fixed reason priority, independent of hash
iteration. Admission, corrupt payload, missing capability, and resource errors
are outside expression short-circuiting and cannot be masked. The treatment of
known faults mixed with unknowns is EACL's explicit fail-closed boundary; no
claim is made to implement every CEL unknown/error extension.

Source bytes, token count, raw grouping depth, plan depth/nodes, parameters,
individual strings, container entries, total context entries, and canonical
context bytes are all bounded before the library receives a program/binding.
Bound and request contexts are each bounded, then their merged context is
bounded again. The work limit is 1,048,576 deterministic units per evaluation,
with saturating arithmetic (limit + 1 means exhausted). It is a conservative
operation estimate, not a wall-clock or retained-heap guarantee.

Each plan node costs one. Scalar comparisons cost one plus both value sizes;
int/bool/timestamp size is one and string size is UTF-8 byte length. List
membership costs one plus every candidate comparison, even if a match occurs
early. Map operations charge all key/value sizes plus the search key size.
Prefix/suffix cost one plus both string sizes; substring costs one plus their
product. Binding conversion costs one per entry plus scalar sizes. A bounded
preflight computes these costs for the whole plan, including both logical
branches, using known operand sizes or their declared maximum if an operand
is unknown. An exhausted preflight returns `:resource-limit` before native
evaluation. Thus expensive work cannot be hidden in a short-circuited branch.
Plan traversal is bounded by 256 nodes/depth 32; no admitted operator creates
unbounded output. Program construction admits at most four concurrent builds
and the process cache retains at most 256 successful programs. Cache misses,
coalescing, and eviction change work only, never semantic identity or outcome.

## Qualifier boundary

The closed semantic qualifier input has optional `:caveat`, `:caveat-context`,
and `:valid-until-ms`. An absent semantic value is omitted. Supplying context
without a Caveat is invalid even if the context map is empty. Empty context
with a Caveat is omitted. An empty qualifier normalizes to nil; a stored entity
containing only a marker is corrupt. All nonempty entities have format 1.
The Caveat reference is one concrete native eid; public names resolve through
the selected schema before planning. No expression source lives on a qualifier.

Relation allowance follows the source branches: `user with c` requires c;
`user | user with c` permits either no Caveat or c. The model records nil/zero
as the plain-branch alternative in the same allowance set. An expiry-only
qualifier still has no Caveat and requires a plain branch. Branch alternatives
are grouped under one Relation identity, never separate logical Relationships.

Prepared qualifiers have no owner and are inert. Publication atomically adds
both symmetric endpoint refs, the Relation stamp, and caller facts. Ownership
is keyed by the subject plus the first four forward tuple slots; qualifier
differences never create another Relationship identity. Replacement allocates
a fresh qualifier, swaps both halves, and retracts the old current entity.
No supported transition mutates or shares a qualifier. Non-nil references to
missing entities are faults. Snapshot integrity detects dangling, shared,
malformed, and asymmetric data; mutation-in-place evidence requires native
history/assertion versions or an explicit before/after proof input. A single
snapshot alone is not claimed to prove immutability history.

## Profile 2: `exists` and `all`

`range.exists(x, p)` and `range.all(x, p)` range over a list's elements or a
map's keys; `range` has a list or string-keyed map type and `p` is Boolean.
The plan is `[:exists range "x" p]` (or `:all`). The parser resolves each name
to the innermost enclosing comprehension variable of that name, as
`[:var "x"]`, or else to a parameter, so a variable hides a parameter or outer
variable only inside its predicate. A variable is a parameter-shaped name
other than `__result__`, and no parameter named `__result__` may be read inside
a comprehension: cel-go, and so SpiceDB, binds that name to the macro's
accumulator. `has`, `exists_one`, `map`, `filter` and two-variable
comprehensions stay excluded.

With a supplied range, exists folds `||` from false and all folds `&&` from
true over the element outcomes, with the four-valued absorption above: a
deciding element (true for exists, false for all) wins over faults and missing
fields; otherwise a fault is the result; otherwise missing fields make it
conditional, missing the union of the undecided elements' fields; otherwise
the unit. The residual is the same comprehension over the undecided elements
only, with supplied parameters and enclosing variables bound as literals.
Because `||` and `&&` are commutative and associative, the outcome does not
depend on element order. An absent range makes the result conditional on the
range alone, as in cel-go, without evaluating the predicate; the residual is
the comprehension with supplied values bound.

A comprehension over a supplied range costs one, plus its range leaf and the
range's size, plus one and the predicate's cost per element, with the
variable's size the largest element's. Every element is charged, even after a
deciding one. Nested comprehensions multiply. An absent range is charged at
its declared maximum size, like any absent operand, plus one per predicate
node for the residual copy, and is not iterated. No bound is added: the
container and work limits bound every comprehension.
