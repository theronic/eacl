# verified-subproblem-cache Specification

## REMOVED Requirements

### Requirement: Projection chunks preserve backend scan semantics
**Reason**: The engine keeps exactly two cache artifacts (latest checkpoint and completed answer); projection and denotation tiers no longer serve traversal.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Generated indexed traversal owns authorization state
**Reason**: Traversal state is owned by the CLJC stable reducer.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Formal semantics govern every public engine decision
**Reason**: Generated decisions remain authoritative only for `:consistency-plan`, `:current-cache-decision`, `:cursor-continuation` and `:relationship-page`; enumeration is verified by the stable-discovery assurance tree instead.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
