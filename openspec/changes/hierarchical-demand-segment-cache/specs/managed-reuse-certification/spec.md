# managed-reuse-certification Specification

## MODIFIED Requirements

### Requirement: Documentation matches shipped reuse
Cache documentation SHALL state exactly which artifacts managed cross-revision
reuse applies to — completed answers under the schema/dependency frontier and
exact scan-response prefixes under the singleton relation frontier — the
exact writer contract each depends on, and the status of its formal proof.
Documentation MUST state that no denotation, projection-tier, or
plan-node-segment reuse exists, and MUST NOT describe such reuse as merely
disabled.

#### Scenario: Doc/behavior audit
- **WHEN** the cache documentation is compared against the implemented resolution and fetch layers
- **THEN** every documented reuse rule matches an implemented rule, every implemented cross-revision reuse path is documented, and no retired tier is described as present or as disabled-but-available
