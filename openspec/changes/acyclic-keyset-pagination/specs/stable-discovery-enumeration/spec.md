# stable-discovery-enumeration Specification

> Delta narrowing the single-order requirement; the recursive-plan
> contract is unchanged.

## MODIFIED Requirements

### Requirement: Public order is selected per sealed plan and sealed into its fingerprint

Every sealed plan MUST declare exactly one public order mode, statically derived from its reachable rule graph: `:least-path` for acyclic root plans (governed by the `acyclic-keyset-pagination` capability) and `:first-discovery` for recursive root plans, which retain stable first-discovery order, history-free checkpoints, governed deterministic replay, and the `:complete-denotation` requirement for bare `:last` windows exactly as previously specified. The order mode and the recursiveness classification MUST be folded into the plan's composite fingerprint under order-contract ABI version 2, so a cursor minted under one regime can never be interpreted under another; a cursor whose fingerprint does not match MUST fail typed. An acyclic root's reachable program is acyclic by construction, so no single request may mix order regimes. Routing MUST dispatch on the sealed mode only — never on runtime state.

#### Scenario: Recursive plans unchanged

- **WHEN** a recursive root plan serves pages, counts, or checks
- **THEN** behavior, order, checkpoints, replay, and cursor semantics are identical to the previous contract

#### Scenario: Cross-regime cursor rejected

- **WHEN** a cursor minted under one order mode is presented after the plan's mode or ABI changes
- **THEN** the request fails typed as an invalid cursor rather than silently re-anchoring
