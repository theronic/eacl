# formally-verified-authorization-engine Specification

## REMOVED Requirements

### Requirement: Differential cutover evidence without a production rollback engine
**Reason**: The generated kernel is not the single production authorization engine; the stable engine is hand-written and verified separately. Its cutover evidence is the cross-engine differential and frozen baselines recorded by `adopt-stable-discovery-enumeration`.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
