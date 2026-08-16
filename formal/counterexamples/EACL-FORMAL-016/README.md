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

The final v8 simplification makes lookup read-only and removes flight
installation. A miss never mutates cache state or starts work; publication is
a separate bounded compare-and-set transition after independent computation.
The generated cache decision remains authoritative over the state it models.
