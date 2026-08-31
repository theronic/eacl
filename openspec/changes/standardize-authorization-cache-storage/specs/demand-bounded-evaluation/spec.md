# demand-bounded-evaluation Specification

## MODIFIED Requirements

### Requirement: Execution provenance is explicit

Detailed point responses SHALL expose the selected evaluation mode separately
from `:cached?` and `:cache-basis`. Count and page cache/cursor identities SHALL
bind the normalized evaluation mode even though their public response does not
repeat the caller-supplied mode. Cache statistics SHALL distinguish lookup and
publication outcomes. `:cache?` SHALL mean only authorization answer,
denotation, exact rendered-page, and continuation reuse/publication permission; request-independent
derived-schema and cursor-construction infrastructure remains governed by its
own closed identity and validity contracts.

#### Scenario: Cold cache-enabled demand request

- **WHEN** a demand request misses and computes its answer
- **THEN** the response is not labeled a hit
- **AND** point detail reports demand evaluation while cache statistics retain the lookup/publication outcomes

#### Scenario: Cache bypass

- **WHEN** `:cache? false` is supplied
- **THEN** the response is not labeled a hit and authorization answer, denotation, rendered-page, and continuation statistics record no lookup or publication for that request
- **AND** EACL performs no authorization answer/denotation/rendered-page/continuation key, lookup, proof-lifting, admission, publication, or cache-coordination work
- **AND** request-independent derived-schema and cursor-construction caches MAY still serve their internal infrastructure roles

#### Scenario: Default page cursor proof

- **WHEN** a demand page uses content proof mode or disables answer-cache reuse
- **THEN** cursor minting binds the selected immutable snapshot identity without scanning relationship content
- **AND** the cursor mechanism performs no work proportional to the relationship graph merely to make the demanded page resumable
