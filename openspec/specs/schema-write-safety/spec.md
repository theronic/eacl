# schema-write-safety Specification

## Purpose
Defines how `write-schema!` admits and replaces a schema without side effects on failure: parse and declaration errors, reference validation, admission limits, and guards against destructive or orphaning changes.
## Requirements
### Requirement: Unparseable schema is rejected without side effects
`write-schema!` SHALL throw an `ex-info` with `:type :eacl.schema/parse-error` (with `:reason`, `:line`, `:column`, `:expected` and `:found`) when the schema string does not parse, and SHALL NOT transact any changes. `->eacl-schema` SHALL throw when handed anything other than a parse tree and SHALL never coerce a failed parse into an empty schema.

#### Scenario: Syntax error leaves existing schema untouched
- **WHEN** a schema with relations and permissions is stored, and `write-schema!` is called with a schema string missing a closing brace
- **THEN** an `ex-info` with `:type :eacl.schema/parse-error` is thrown, and `read-schema` afterwards returns the same relations and permissions as before

#### Scenario: Parse failure reports position detail
- **WHEN** `write-schema!` is called with `"definition user { relation owner user }"` (missing `:`)
- **THEN** the thrown error's `ex-data` has `:reason :syntax`, the 1-based `:line` and `:column` of the offending token, and what was `:expected` and `:found`

### Requirement: Schemas follow the SpiceDB v1.56.0 schema language
Schema admission SHALL accept every schema SpiceDB v1.56.0's WriteSchema accepts, or reject it only with `:eacl.schema/unsupported-feature` naming the construct EACL cannot serve, and SHALL reject every schema SpiceDB rejects. SpiceDB's validation SHALL run before EACL's restrictions, so `:eacl.schema/unsupported-feature` names a feature of a valid SpiceDB schema; the one exception is a Caveat body whose CEL EACL can neither evaluate nor type-check. The compatibility corpus (`modules/eacl/test/eacl/spicedb/fixtures/spicedb-1.56-corpus.edn`) records SpiceDB's verdict for each of its schemas, and every entry SHALL hold on the JVM and in ClojureScript. Resource limits (`:expression-limits`, including the validation work bounds derived from `:maximum-schema-source-bytes`, and nesting depth) are outside this requirement. As a deliberate difference, EACL SHALL apply SpiceDB's transitive-wildcard rule per definition and relation, and SHALL reject with `:eacl.schema/expression-resolution-failed` the schemas SpiceDB v1.56.0 accepts only because it caches that check by relation name (the corpus's `:spicedb-defects`).

#### Scenario: SpiceDB statement terminators
- **WHEN** a schema ends statements with `;`, or continues a permission expression on the next line after a binary operator
- **THEN** the schema is accepted as SpiceDB accepts it

#### Scenario: Glued keyword
- **WHEN** a definition contains `relationviewer: user`
- **THEN** schema admission throws `:eacl.schema/parse-error`, as SpiceDB rejects it

#### Scenario: SpiceDB name rules
- **WHEN** a relation is named `ab`, `Viewer` or `viewer_`
- **THEN** schema admission throws `:eacl.schema/invalid-name` naming the relation

#### Scenario: Valid SpiceDB feature EACL does not serve
- **WHEN** a valid SpiceDB schema declares `relation viewer: user:*`
- **THEN** schema admission throws `:eacl.schema/unsupported-feature`

#### Scenario: Invalid SpiceDB schema with an unsupported feature
- **WHEN** a schema declares `relation viewer: user:*` and references a relation that does not exist
- **THEN** schema admission throws the reference error, not `:eacl.schema/unsupported-feature`

#### Scenario: Transitive wildcard behind SpiceDB's relation-name cache
- **WHEN** `team#member` allows only `user`, `group#member` allows `user:*`, and a later definition declares `relation ggg: group#member` after another declares `relation ttt: team#member`
- **THEN** schema admission throws `:eacl.schema/expression-resolution-failed` with a `:transitive-wildcard` issue, although SpiceDB v1.56.0 accepts the schema

### Requirement: Schema comments are supported
The parser SHALL accept `//` line comments and `/* */` block comments anywhere whitespace is legal, matching the SpiceDB DSL.

#### Scenario: Line comment before a definition
- **WHEN** `write-schema!` is called with `"// users\ndefinition user {}"`
- **THEN** the schema is written successfully with the `user` definition

#### Scenario: Comments inside a definition body
- **WHEN** a schema contains `/* block */` between relations and `// trailing` after a permission expression
- **THEN** parsing succeeds and the extracted relations and permissions are identical to the comment-free equivalent

### Requirement: Duplicate declarations are rejected
Schema extraction SHALL throw a typed error when the same definition name appears twice, when the same relation name is declared twice within a definition, or when a permission shares a name with a relation on the same definition. Multi-type relations declared once with `|` (e.g. `relation owner: user | group`) SHALL NOT be treated as duplicates.

#### Scenario: Duplicate definition blocks
- **WHEN** a schema contains two `definition account { ... }` blocks
- **THEN** `->eacl-schema` throws `ex-info` with `:type :eacl.schema/duplicate-definition` naming `account`, and no block is silently dropped

#### Scenario: Duplicate relation declaration
- **WHEN** a definition contains `relation owner: user` twice
- **THEN** a typed duplicate-relation error names the definition and relation

#### Scenario: Multi-type relation is not a duplicate
- **WHEN** a definition contains `relation owner: user | group` once
- **THEN** the schema is accepted and expands to two Relation entities

### Requirement: Declaration errors follow source order
Schema extraction SHALL read top-level definitions and Caveats once, in source order, building each declaration and checking it against the earlier ones before reading the next. The first failing declaration SHALL determine the error, whatever its kind and however many declarations precede it. Within one declaration, its own errors SHALL precede its duplicate-name check.

#### Scenario: Duplicate before an invalid declaration
- **WHEN** a schema declares `caveat c`, then `caveat c` again, then a `caveat d` whose expression does not parse
- **THEN** validation throws `:eacl.schema/duplicate-caveat` naming `c`, however many definitions precede the three Caveats

#### Scenario: Invalid declaration before a duplicate
- **WHEN** a Caveat whose expression does not parse precedes two `definition doc` blocks
- **THEN** validation throws `:eacl.caveat/invalid` for that Caveat rather than `:eacl.schema/duplicate-definition`

### Requirement: Full-schema retraction requires explicit opt-in
`write-schema!` SHALL throw `ex-info` with `:type :eacl.schema/empty-schema-guard` when the new schema contains zero definitions while the stored schema is non-empty, unless called with `{:allow-empty-schema? true}`.

#### Scenario: Empty parse result cannot wipe schema
- **WHEN** the stored schema is non-empty and `write-schema!` is called with a schema string yielding zero definitions
- **THEN** the guard error is thrown and no retractions are transacted

#### Scenario: Explicit opt-in allows wiping
- **WHEN** the same call is made as `(write-schema! conn schema-string {:allow-empty-schema? true})` and no relationships would be orphaned
- **THEN** the retraction proceeds

### Requirement: Arrow validation is order-independent and covers all subject types
`validate-schema-references` SHALL validate an arrow `rel->target` against **every** subject type of `rel`. The schema SHALL be rejected if any subject type lacks `target`, or if `target` resolves to a relation on some subject types and a permission on others. Acceptance SHALL NOT depend on the declaration order of subject types.

#### Scenario: Order does not change the verdict
- **WHEN** `permission mgmt` exists on `user` but not on `group`, and two schemas differ only in `relation owner: user | group` vs `relation owner: group | user`, each with `permission admin = owner->mgmt`
- **THEN** both schemas are rejected with an error listing `group` as lacking `mgmt`

#### Scenario: Target present on all subject types is accepted
- **WHEN** `mgmt` is defined on both `user` and `group`
- **THEN** the schema is accepted regardless of subject-type declaration order

### Requirement: Parenthesized union expressions are supported
Permission expressions using parentheses around union operands (e.g. `permission manage = (owner + editor)`) SHALL be flattened to their union components. A parenthesized expression used as an arrow base or target, which SpiceDB rejects, SHALL be rejected with a typed parse error, not an assertion failure.

#### Scenario: Parenthesized union flattens
- **WHEN** `write-schema!` is called with `permission manage = (owner + editor)` where both relations exist
- **THEN** the schema is accepted and `manage` behaves identically to `permission manage = owner + editor`

#### Scenario: Parenthesized arrow base is rejected clearly
- **WHEN** a schema contains `permission p = (a + b)->c`
- **THEN** `:eacl.schema/parse-error` names the `->` that cannot follow `)`, and no `AssertionError` escapes

### Requirement: Permission expression size is a typed admission limit
Schema admission SHALL measure each permission's canonical expression payload exactly, without applying the canonical codec's own size and entry ceilings, and SHALL reject a payload larger than `:maximum-expression-bytes` with `:eacl.schema/expression-limit` carrying `:dimension :encoded-byte-size`, `:maximum`, and the exact `:actual` byte count.

#### Scenario: Payload beyond the codec ceiling under default limits
- **WHEN** a permission's arrows resolve over 256 subject types, so that its canonical payload exceeds 1 MiB or 262,144 codec entries
- **THEN** validation throws `:eacl.schema/expression-limit` with `:dimension :encoded-byte-size` and `:maximum 131072`, not `:eacl.format/invalid`

### Requirement: Schema validation work is bounded by the source-byte limit
Schema admission SHALL bound the work of validating a schema by the client's `:maximum-schema-source-bytes`. Translating partials and expanding definitions SHALL visit at most a quarter of that many statements, and `use typechecking` annotations SHALL visit at most that many relations and permissions in total; beyond either bound admission SHALL throw `:eacl.schema/expression-limit` with `:dimension :partial-expansion` or `:dimension :typechecking`, `:maximum`, `:actual-at-least` and `:limit :maximum-schema-source-bytes`. Unused partials SHALL NOT be expanded. These are resource limits, outside the SpiceDB compatibility requirement.

#### Scenario: Doubling partials
- **WHEN** a schema's partials each reference the previous one twice, forty levels deep, and a definition uses the last
- **THEN** schema admission throws `:eacl.schema/expression-limit` with `:dimension :partial-expansion` instead of expanding 2^39 members

#### Scenario: Unused doubling partials
- **WHEN** the same partials are declared and no definition uses them
- **THEN** schema admission accepts the schema

### Requirement: A Relation that holds Relationships cannot be removed
A schema replacement under the default `:error` orphan policy SHALL reject removing a Relation identity that holds any stored Relationship, whether plain, expiring, expired, or Caveated, with `:eacl.schema/relation-in-use` carrying the Relation and the number of retained Relationships, on every backend. Speculative `:retain-inert` planning SHALL report such a Relation whatever qualifier its first indexed Relationship carries.

#### Scenario: Qualified Relationships hold a Relation
- **WHEN** a Relation holds only expiring, expired, or Caveated Relationships and `write-schema!` removes it
- **THEN** it throws `:eacl.schema/relation-in-use` with the full count, never `:eacl/unsupported-qualifier`
- **AND** the stored schema is unchanged

#### Scenario: Speculative removal over a qualified first row
- **WHEN** `with-schema` removes that Relation with `{:orphan-policy :retain-inert}`
- **THEN** the speculative snapshot reports `:eacl.speculative/retained-orphan-relationships` for the Relation
