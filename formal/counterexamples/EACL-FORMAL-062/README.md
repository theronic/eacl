# EACL-FORMAL-062 — inactive recursive syntax selected fixed-point traversal

The v8 routing certificate correctly identifies permission roots whose schema
can reach a recursive strongly connected component. Production enumeration
originally treated that schema fact as sufficient to enter the recursive
fixed-point engine.

In EACL Explorer, writing the Recursive preset adds `account.parent` and
`server.parent` rules but does not add any parent relationships. Those empty
relationship prefixes make cycle traversal impossible in the selected
snapshot. Nevertheless, pagination and counts consumed recursive work limits
and could fail before returning ordinary acyclic grants.

The corrected dispatcher derives the relationship prefixes that guard
in-component permission arrows and probes their exact population in the
selected immutable snapshot. An accepted recursive schema with no populated
cycle guard uses the bounded acyclic evaluator with zero recursive work. Adding
a parent relationship makes the guard active and restores recursive routing.
