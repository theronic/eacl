# Spec Delta

## MODIFIED Requirements

### Requirement: Semantic keys separate every answer-affecting input
Every subproblem key SHALL commit to the source, selected graph or validated
proof generation, schema identity, engine and key version, identity contract,
direction, internal endpoint identities, types, relation or permission node,
bounds, and all contextual inputs that can alter its denotation.

One input is handled differently: a qualified point decision MAY leave the
evaluation time out of its key when its value records the interval its
evidence certifies, namely the time it was computed, the time its evidence
stops holding, and whether that certificate is complete. Such an entry SHALL
be eligible only at a time at or after it was computed and before its evidence
stops holding, and only with a complete certificate. Otherwise it SHALL be
eligible only at its own time.

#### Scenario: Distinct principals share an atomic projection
- **WHEN** two distinct top-level queries on the same selected graph require the identical query-independent relationship projection
- **THEN** they resolve the same projection key even though their completed-answer keys differ

#### Scenario: Context changes
- **WHEN** an identity codec, caveat context, source, branch, schema, endpoint, direction, or bound changes
- **THEN** an entry created under the prior semantic input is not eligible under the new key

#### Scenario: Time changes within a certificate
- **WHEN** a complete qualified point decision computed at t0 and certified until T is looked up at t1 with t0 ≤ t1 < T, under otherwise identical inputs
- **THEN** the entry is eligible, and outside that interval it is not
