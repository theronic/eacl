## ADDED Requirements

### Requirement: Certified permission-root classification

EACL SHALL classify every enumerated permission root from the normalized schema's generated routing certificate. A permission root that cannot transitively reach a recursive strongly connected component SHALL be classified as acyclic, and a root that can reach one SHALL be classified as recursive.

#### Scenario: Explorer permission is acyclic

- **WHEN** the EACL Explorer schema contains no recursive SCC reachable from the requested server permission
- **THEN** forward listing, reverse listing, and exact counting for that permission use the certified acyclic route

#### Scenario: Permission depends on a recursive SCC

- **WHEN** a requested permission transitively depends on a recursive strongly connected component
- **THEN** EACL uses the recursive fixed-point route for every enumeration operation on that permission

#### Scenario: Routing certificate does not match the schema

- **WHEN** the routing certificate is missing, stale, malformed, or bound to a different normalized schema
- **THEN** EACL fails closed before enumeration and does not guess an acyclic route

### Requirement: Acyclic and recursive route equivalence

Each certified route MUST return the same authorization set as the denotational semantics for its schema class. Routing SHALL NOT change stable ordering, duplicate suppression, forward or reverse page boundaries, exact count results, constraints, or snapshot behavior.

#### Scenario: Overlapping acyclic grants

- **WHEN** one resource is reachable through multiple acyclic grant paths
- **THEN** list enumeration emits it once and exact count includes it once

#### Scenario: Differential recursive workload

- **WHEN** a recursive schema is evaluated by the routed engine and the denotational oracle
- **THEN** both return the same authorization set or the routed engine returns the documented bounded failure

### Requirement: Recursive limit isolation

Certified acyclic enumeration MUST NOT consume recursive traversal budgets, increment recursive traversal counters, or throw `:eacl.recursive-traversal/limit-exceeded`. Certified recursive enumeration MUST continue to enforce all configured recursive safety limits.

#### Scenario: Fifty-thousand-server acyclic count

- **WHEN** the super-user's exact server count is evaluated against 50,000 seeded servers in the acyclic Explorer schema
- **THEN** the operation completes without recursive counter activity or a recursive traversal limit failure

#### Scenario: Recursive workload exceeds its limit

- **WHEN** a certified recursive traversal exceeds a configured recursive safety limit
- **THEN** EACL fails closed with the corresponding structured recursive-limit error

### Requirement: Cross-backend routing consistency

Datomic, DataScript, and Datahike SHALL apply the same generated classification and operation routing for the same normalized schema and request.

#### Scenario: Equivalent backend snapshots

- **WHEN** equivalent facts and schema are loaded into Datomic, DataScript, and Datahike
- **THEN** each backend selects the same route and returns equivalent list and count results

### Requirement: Empty recursive guards remain page-bounded

For a permission root that reaches a recursive SCC, EACL MUST inspect the selected snapshot's complete set of in-SCC arrow relationship prefixes. When every such prefix is empty, the generated route SHALL use the bounded acyclic evaluator and SHALL produce the same denotation as the recursive fixed-point semantics. When any such prefix is populated, EACL SHALL use the recursive route.

#### Scenario: Recursive Explorer schema has no parent relationships

- **WHEN** the Recursive Explorer schema is written and neither the account nor server `parent` relation has any relationships
- **THEN** server list and exact count operations use the acyclic route, return exact results, and consume zero recursive traversal work

#### Scenario: A cycle guard becomes populated

- **WHEN** a server `parent` relationship is created in the same schema
- **THEN** server permissions that reach the recursive SCC use the recursive fixed-point route

#### Scenario: Adapter reports relation-prefix activity

- **WHEN** equivalent relationship prefixes are present or absent in Datomic, DataScript, and Datahike snapshots
- **THEN** each adapter's population probe returns the same exact Boolean as forward index non-emptiness

### Requirement: Explorer exposes matched schema presets

The EACL Explorer schema editor SHALL expose pre-populated Non-recursive and Recursive tabs. Selecting a tab MUST replace only the editable draft and MUST NOT write the schema until the user presses Write Schema.

#### Scenario: User selects Recursive

- **WHEN** the user selects the Recursive schema tab
- **THEN** the editor contains the account and server parent relations and recursive permissions while the committed schema remains unchanged
