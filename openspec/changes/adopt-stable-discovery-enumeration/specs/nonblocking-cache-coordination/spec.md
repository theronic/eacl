# nonblocking-cache-coordination Specification

## REMOVED Requirements

### Requirement: Projection cache stores exact command responses
**Reason**: The projection tier is not a cache artifact of the stable engine; per-request chunk buffers are disposable and evicted to the logical bound.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
