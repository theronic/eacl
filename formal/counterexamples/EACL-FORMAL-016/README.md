# EACL-FORMAL-016 — generated lookup was observational, not authoritative

The subproblem cache previously selected or installed a host flight first and
only then called the generated lookup decision. Although the pure generated
function was correct, the production transition did not use it to decide
whether a flight could be created.

The minimized control observes the store when the correct generated
`start-computation` action is invoked for a missing candidate. Before the fix,
the kernel observed the flight and incomplete entry already installed. A
contradictory generated action could therefore leave state that its action
forbade, which contradicted the recorded authoritative-transition claim.

Lookup action selection now occurs from the lifecycle-stable candidate state
before mutation. Only `start-computation` may reach flight installation.
Resolve and read-only lookup also use the selected action to distinguish
completed hits from joined computations. The strict generated boundary rejects
any action inconsistent with its validated input state.
