# EACL-FORMAL-017 — lookup ignored an unrepresented registered flight

Flight ownership is deliberately separate from tier admission. When an exact
cache tier reaches its represented-candidate limit, a computation may own a
valid lifecycle-qualified coordinator flight without appearing in the tier's
entry map.

After EACL-FORMAL-016, the generated lookup action ran before mutation, but its
candidate input still came only from the tier entry map. A second resolver for
that unadmitted key therefore received `start-computation`; the host's later
flight compare-and-set discovered the existing flight and silently changed the
outcome to `join-computation`. The generated transition remained observational
rather than fully authoritative for that state.

Resolution selection now observes the represented entry and coordinator flight
under the same lifecycle-stable store lock. A registered flight is
`computing` whether or not tier admission represented it, so generated
`join-computation` is selected before the host joins it. The collision path
also re-dispatches the generated `computing` decision defensively.
