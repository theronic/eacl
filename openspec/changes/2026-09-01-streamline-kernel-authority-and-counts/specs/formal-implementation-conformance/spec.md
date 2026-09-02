## ADDED Requirements

### Requirement: Consistency authority class is host-native on every platform
Production consistency selection and validation SHALL be mapped to the
differentially certified portable decision procedure on both the JVM and
CLJS. The generated consistency model SHALL remain in the compiled proof
closure as the offline differential oracle, and the conformance suites
SHALL exercise generated-versus-portable agreement for these operations.
The cutover accounting SHALL NOT require production generated-kernel
crossings for consistency operations.

#### Scenario: Authority accounting after the consistency cutover
- **WHEN** the verified-authority accounting suite runs the full battery
  under a counting kernel
- **THEN** it requires generated crossings for cursor continuation and
  relationship paging on every backend, and requires none for consistency
  operations
- **AND** the assurance contract's consistency operation names the portable
  decision procedure as its production entry point with the generated model
  as its oracle
