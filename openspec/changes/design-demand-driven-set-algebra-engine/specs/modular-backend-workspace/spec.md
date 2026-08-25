## ADDED Requirements

### Requirement: Operator semantics remain in the shared engine
Built-in backend modules SHALL provide only certified expression storage, identity conversion, ordered scans, scalar membership, optional batched membership, snapshot selection, proof frames, and mutation operations. They MUST NOT implement backend-specific whole-expression authorization semantics.

#### Scenario: Backend supports a native query language
- **WHEN** a backend could evaluate intersection or exclusion through a native query
- **THEN** public EACL authorization still composes certified backend primitives through the shared semantic engine

### Requirement: Optional operation registration is closed and validated
The adapter constructor SHALL distinguish required expression operations from optional batched membership, validate every advertised operation and capability pair, reject unknown operator capabilities, and include the validated capability identity in plan compatibility.

#### Scenario: Capability without operation
- **WHEN** an adapter advertises batched membership but supplies no callable implementation
- **THEN** adapter construction fails before a request selects it

### Requirement: Backend batch tests certify direction duality
Each built-in batched implementation SHALL be differentially certified for forward and reverse direction, exact and missing values, smallest and largest supported identifiers, batch limits, cancellation, and selected-basis stability.

#### Scenario: Reverse candidate vector
- **WHEN** reverse lookup batches candidate subjects for one resource descriptor
- **THEN** its aligned result equals independent reverse scalar membership and cannot accidentally consult the forward endpoint

