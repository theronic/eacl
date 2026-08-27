## MODIFIED Requirements

### Requirement: Proven cache observational equivalence
The verified kernel SHALL mechanically prove that every returned cache hit equals fresh evaluation of the same semantic request on the selected immutable basis. Exact-basis entries SHALL be accepted only from the identical complete basis identity. Managed entries SHALL additionally require equal lineage, equal certified schema generation, and an equal scalar dependency frontier over the complete closure under the explicit stamped-writer contract, in either revision direction. A basis whose frame is unavailable SHALL use only exact-basis entries; engine-facade and inadmissible database values SHALL bypass completed-answer lookup and publication.

#### Scenario: Exact cache hit
- **WHEN** a valid cache entry was computed for the identical basis identity and semantic key
- **THEN** EACL may return its value and the value equals fresh evaluation

#### Scenario: Managed reuse in either direction
- **WHEN** a valid entry and the selected basis share one lineage and have equal frames
- **THEN** EACL may return the entry and the scalar-frontier theorem establishes equality with selected-basis recomputation, whichever basis is older

#### Scenario: Frame unavailable or inadmissible value
- **WHEN** a basis's frame is unavailable or a value reaches the engine facade
- **THEN** EACL uses only exact-basis entries or bypasses completed-answer caching respectively

#### Scenario: Incomplete managed stamp
- **WHEN** EACL cannot obtain one valid generation for every relation in the closure
- **THEN** it rejects managed reuse and computes from the selected basis, while exact-basis reuse remains independently sound

#### Scenario: Lifecycle expiry race
- **WHEN** an in-flight computation publishes after explicit client cache expiry
- **THEN** publication can reach only the captured old lifecycle and cannot repopulate the new lifecycle

## ADDED Requirements

### Requirement: Lineage premise and direction-agnostic lifting are stated in the model
The formal cache model SHALL state that its history is one lineage — equal source scope and source lifecycle in the runtime — and its existing corollary `EqualScalarProofAlsoPreservesAnOlderSelectedSnapshot` SHALL be the cited evidence that equal frames at two bases of one lineage imply equal protected semantics whichever basis holds the cached value. The assurance matrix SHALL cite that corollary for managed reuse at retained older bases, and the trust manifest SHALL list the numeric-domain, ceiling, atomic-stamping, lineage, and supported-writer premises as adapter assumptions rather than proved properties.

#### Scenario: Corollary is cited
- **WHEN** a reviewer inspects the assurance matrix entry for managed reuse at a retained older basis
- **THEN** it names the direction-agnostic corollary and its premises

#### Scenario: Premise is listed, not claimed
- **WHEN** the trust manifest is generated
- **THEN** the ceiling and domain obligations appear as executable adapter obligations and not as theorems
