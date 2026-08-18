# kernel-boundary-efficiency Specification

## ADDED Requirements

### Requirement: Request shells add no per-request schema or plan work

The public request shells SHALL reuse one sealed plan per source, lifecycle,
schema generation and permission root across requests and unrelated
transactions, and MUST NOT re-seal it per request or per revision. Request
validation MUST read the schema at most once per schema generation on the
miss path and MUST NOT read it on a cache hit; an unstamped database keeps
reading directly. Kernel-boundary predicates MUST NOT walk protocol
hierarchies on every request.

#### Scenario: Repeated requests on one schema generation

- **WHEN** many `can?` or lookup requests run against one source across unrelated transactions
- **THEN** the sealed plan is compiled once for that source, lifecycle, schema generation and root

#### Scenario: Cache hit

- **WHEN** a request is served from a completed-answer tier
- **THEN** no schema enumeration and no plan sealing occur for that request

#### Scenario: Schema change

- **WHEN** `write-schema!` advances the schema generation
- **THEN** the next request seals the plan for the new generation and validates against the newly parsed schema
