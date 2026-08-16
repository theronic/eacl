# cursor-dependency-validity Specification

## REMOVED Requirements

### Requirement: One authenticated-and-confidential token codec
**Reason**: Stable-discovery edge tokens are integrity-only (HMAC); confidentiality is not required because the boundary carries no traversal state.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
