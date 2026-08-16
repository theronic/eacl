# schema-aware-traversal-routing Specification

## REMOVED Requirements

### Requirement: Certified permission-root classification
**Reason**: The stable-discovery engine routes every permission root through one sealed plan and one generic width-one reducer; there is no acyclic route, routing certificate, entity-ID merge, generated indexed traversal, fuel quantum, or route-specific public order. Public order is the stable first-discovery order carried by `:stable-edge` cursors (see `stable-discovery-enumeration`).

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Acyclic and recursive route equivalence
**Reason**: The stable-discovery engine routes every permission root through one sealed plan and one generic width-one reducer; there is no acyclic route, routing certificate, entity-ID merge, generated indexed traversal, fuel quantum, or route-specific public order. Public order is the stable first-discovery order carried by `:stable-edge` cursors (see `stable-discovery-enumeration`).

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Recursive limit isolation
**Reason**: One engine and one public limit family (`:recursive-traversal-limits` mapped onto reducer budgets) apply to every root; there is no separate acyclic route whose limits could be isolated.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.

### Requirement: Empty recursive guards remain page-bounded
**Reason**: In-SCC empty-guard detection belonged to the retired routing analysis; the reducer's exact per-kind admission bounds every page directly.

**Migration**: None. The requirement described a mechanism the stable-discovery engine removed; consumers see the `:stable-edge` contract in `stable-discovery-enumeration`.
