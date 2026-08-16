# verified-enumeration-performance Specification

## REMOVED Requirements

### Requirement: Exact deduplicated acyclic enumeration
**Reason**: The stable-discovery engine routes every permission root through one sealed plan and one generic width-one reducer; there is no acyclic route, routing certificate, entity-ID merge, generated indexed traversal, fuel quantum, or route-specific public order. Public order is the stable first-discovery order carried by `:stable-edge` cursors (see `stable-discovery-enumeration`).

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Deterministic acyclic work bounds
**Reason**: The stable-discovery engine routes every permission root through one sealed plan and one generic width-one reducer; there is no acyclic route, routing certificate, entity-ID merge, generated indexed traversal, fuel quantum, or route-specific public order. Public order is the stable first-discovery order carried by `:stable-edge` cursors (see `stable-discovery-enumeration`).

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Formal and generated authority gates
**Reason**: The stable engine is hand-written CLJC verified by the `formal/stable-discovery/` release-assurance tree (Dafny leaves, TLC families, refinement bridges, mutation controls); generated regeneration is not its authority.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: V7-relative latency gates
**Reason**: Recorded as `:not-applicable` by the Explorer gate rebase (`formal/verification/explorer-v7-performance.edn`); the accepted trade-off (pages faster, uncached exhaustive counts slower pending width>1) is documented in the change's tasks.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
