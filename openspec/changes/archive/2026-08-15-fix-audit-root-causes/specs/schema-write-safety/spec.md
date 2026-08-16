# schema-write-safety

Parsing, validating, and transacting SpiceDB schema strings without silent data loss. Covers `eacl.spicedb.parser` and `eacl.datomic.schema/write-schema!`.

## ADDED Requirements

### Requirement: Unparseable schema is rejected without side effects
`write-schema!` SHALL throw an `ex-info` with `:type :eacl.schema/parse-error` (including the instaparse failure detail) when the schema string does not parse, and SHALL NOT transact any changes. `->eacl-schema` SHALL throw when handed an instaparse failure object and SHALL never coerce a failed parse into an empty schema.

#### Scenario: Syntax error leaves existing schema untouched
- **WHEN** a schema with relations and permissions is stored, and `write-schema!` is called with a schema string missing a closing brace
- **THEN** an `ex-info` with `:type :eacl.schema/parse-error` is thrown, and `read-schema` afterwards returns the same relations and permissions as before

#### Scenario: Parse failure reports position detail
- **WHEN** `write-schema!` is called with `"definition user { relation owner user }"` (missing `:`)
- **THEN** the thrown error's `ex-data` contains the instaparse failure (line/column/expected information)

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
Permission expressions using parentheses around union operands (e.g. `permission manage = (owner + editor)`) SHALL be flattened to their union components. A parenthesized expression used as an arrow base or target SHALL be rejected with a typed validation error, not an assertion failure.

#### Scenario: Parenthesized union flattens
- **WHEN** `write-schema!` is called with `permission manage = (owner + editor)` where both relations exist
- **THEN** the schema is accepted and `manage` behaves identically to `permission manage = owner + editor`

#### Scenario: Parenthesized arrow base is rejected clearly
- **WHEN** a schema contains `permission p = (a + b)->c`
- **THEN** a typed validation error explains parenthesized arrow bases are unsupported, and no `AssertionError` escapes
