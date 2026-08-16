# trusted-surface-hygiene

## ADDED Requirements

### Requirement: No dead code on trusted surfaces
Dead code SHALL be removed from production namespaces rather than retained: the unreachable authenticated-envelope completed-cache path (`eacl.cache/resolve!` consumers, `authenticated-store`, the `:shared-cache-store`/`:lookup-cache-store` options written-but-never-read), the superseded `watermark` namespace, the v2 zed-token constructors, the relay `:path-frontiers` branch, and the vestigial `:latest-result` answer kind.

#### Scenario: Dead-path audit
- **WHEN** the production source tree is searched after cleanup
- **THEN** none of the enumerated dead paths exist outside version control history; options that had no effect now either work or are rejected as unknown

### Requirement: Verified decisions receive computed inputs
Every input to a generated verified decision SHALL be a computed value. Call sites SHALL NOT pass literal placeholder values for facts established elsewhere (authentication, scope match, expiry, graph codes); the values SHALL be threaded from the code that establishes them so the proof guards discharge against reality.

#### Scenario: Continuation decision inputs
- **WHEN** a cursor continuation decision is made for an expired or scope-mismatched token
- **THEN** the kernel receives `expired?`/`scope-matches?` values reflecting the actual token state, and a regression test observes the corresponding rejection branch taken inside the kernel decision

### Requirement: Formal models correspond to shipped algorithms
Every Dafny model in the compiled closure or cited as assurance SHALL correspond to a shipped algorithm. `Pagination.dfy` (exclusive-frontier resume that production does not implement) SHALL be retargeted to model the actual frontier/heads continuation mechanism or removed from the assurance story; the assurance matrix SHALL be updated accordingly. Models made moot by the keyset unification (ordinal rebase family, backward-render mode) SHALL be deleted in the cleanup pass.

#### Scenario: Assurance-matrix audit
- **WHEN** the assurance matrix is reviewed against production call sites
- **THEN** every listed model maps to code that runs (or is explicitly labeled spec-only with its consumer named), and no model implies coverage of an algorithm that does not exist

### Requirement: Operational hygiene
The build SHALL enable `*warn-on-reflection*` for JVM compilation with warnings failing CI for the core and backend modules; authorization hot paths SHALL NOT emit unconditional console output (schema-resolution warnings SHALL be rate-limited or routed through an optional reporter); interop in kernel decode loops SHALL be type-hinted.

#### Scenario: Reflection gate
- **WHEN** CI compiles the modules with reflection warnings enabled
- **THEN** zero reflection warnings are reported (the currently-unhinted kernel decode `.nth` sites are hinted)

#### Scenario: Hot-path logging
- **WHEN** a schema with a dangling relation reference serves authorization checks
- **THEN** stderr is not written once per check; the condition is reported at most once per schema generation or through the configured reporter
