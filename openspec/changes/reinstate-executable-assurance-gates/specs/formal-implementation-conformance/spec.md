## MODIFIED Requirements

### Requirement: Drift and negative controls fail closed
The certification gate SHALL compare modeled and production fields, branches, errors, limits, transition coverage, source closure, theorem preconditions, trusted adapters, generated artifacts, public claim text, and every count the release manifest or assurance matrix reports — theorem obligations, registered and killed mutants, source-closure files, roots, and definitions, and assurance-tree leaves and obligations — against the ledger that owns each count. It SHALL include negative controls demonstrating that mismatches in each layer are detected.

#### Scenario: Stale generated artifact
- **WHEN** verified source or a target-runtime patch changes without regenerating and reviewing the shipped artifact
- **THEN** digest and conformance validation fail

#### Scenario: Stale theorem count
- **WHEN** a manifest theorem row's recorded obligation count differs from the exact locked Dafny report for its source set
- **THEN** manifest validation fails with the claim, recorded count, actual count, and source set

#### Scenario: Stale mutant or closure count
- **WHEN** the manifest's mutation row or the assurance matrix's closure row differs from the mutation ledger or the committed source-closure ledger
- **THEN** manifest validation fails with the claim, recorded count, and actual count

#### Scenario: Production/model mutation control
- **WHEN** a controlled mutation removes or reverses one production or model decision
- **THEN** at least one required refinement, trace, property, or manifest gate fails for the intended reason

#### Scenario: Claim text overstates evidence
- **WHEN** release documentation labels differential or proof-only evidence as implementation proof
- **THEN** the assurance-claim validator rejects the release manifest
