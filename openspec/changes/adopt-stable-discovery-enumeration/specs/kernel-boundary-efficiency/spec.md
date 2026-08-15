# kernel-boundary-efficiency Specification

## REMOVED Requirements

### Requirement: Amortized per-crossing marshalling
**Reason**: Traversal no longer crosses the host/generated-kernel boundary; the generated kernel serves only pure decisions.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Single validation authority per response
**Reason**: Same reason: no indexed-traversal responses cross a kernel boundary.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Crossing law enforced by gates
**Reason**: Same reason: the crossing law had nothing left to bound after traversal moved into the CLJC reducer.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Batched scan protocol under measured need
**Reason**: Physical chunking is a pure acceleration knob of the reducer (`:physical-chunk-size`); logical release width is fixed at one value per transition and is not a protocol.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
