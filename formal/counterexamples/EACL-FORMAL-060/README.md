# EACL-FORMAL-060 — acyclic enumeration was routed through recursion

The v8 cutover removed the bounded ordered acyclic list/count implementation
but retained the generated certificate that distinguished acyclic roots from
roots reaching recursive strongly connected components. Public enumeration
ignored that distinction and selected the generated recursive fixed-point
driver for every declared root.

The EACL Explorer schema has no recursive permission dependency. Nevertheless,
the 50,000-server super-user count crossed the recursive advanced-datom safety
ceiling and failed. Smaller owner counts completed only after repeated
multipath fixed-point work and were substantially slower than v7.

The corrected dispatcher asks the generated authority to bind the accepted
classification to the selected normalized schema identity. Acyclic list,
reverse-list, and count operations use ordered indexed merge/deduplication and
record separate work. Recursive roots still use the fixed-point route and its
limits. The 50k acceptance requires an exact count with no recursive counter
activity.
