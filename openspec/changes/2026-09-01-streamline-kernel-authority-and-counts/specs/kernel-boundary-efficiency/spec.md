## ADDED Requirements

### Requirement: Request-invariant decisions cross no runtime boundary
Consistency selection and consistency validation are total functions of
already-validated host input. Production SHALL decide them with the
differentially certified portable decision procedure on every platform,
inside the host runtime, with no generated-runtime crossing and no
per-decision kernel-selection dispatch. The certified input and result
validation vocabulary SHALL continue to guard these decisions.

#### Scenario: Consistency decision performs zero crossings
- **WHEN** a request selects or validates a snapshot consistency plan
- **THEN** no host↔generated-kernel crossing is recorded for the decision
- **AND** the decision value is identical to the generated kernel's (proven
  offline by the differential suites, which retain the generated kernel as
  the oracle)
- **AND** input and result validation still reject malformed values with
  the established typed errors

### Requirement: Counting does not pay page-presentation work
A bounded or exact count SHALL NOT construct per-page presentation
artifacts — outer cursor edges, semantic-scope digests, page-info
envelopes, or externalized result nodes — for pages it only tallies.
Count continuation SHALL resume on the internal cover boundary directly,
while preserving the per-page execution budget semantics of the paged
path (each page run holds its own reducer budgets, and request-level
deadline and cancellation checks continue per count page).

#### Scenario: Multi-page recursive count
- **WHEN** an operator-routed recursive count spans multiple internal pages
- **THEN** no outer recursive cursor edge or semantic-scope digest is
  computed for any intermediate page
- **AND** the count, truncation flag, and limit behavior are identical to
  the previous page-looped implementation on the same data
