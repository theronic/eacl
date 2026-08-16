# EACL-FORMAL-055 — point checks traversed from the broad endpoint

Generated-authoritative `can?` always used the forward indexed driver. That is
semantically correct, but it is the wrong direction for a point query. Starting
from a subject can enumerate an arbitrarily broad set of reachable resources
before the Boolean renderer encounters one concrete target.

The release-default change made this cost visible. On the minimized Datomic
multipath benchmark, the generated forward check measured about 453 µs versus
83 µs for the legacy target-local specialization on the same warmed JVM. The
CI runner measured three stable generated medians from 1.187 to 1.193 ms.

Every `can?` is now anchored at the concrete resource. Recursive roots
initialize the generated reverse driver and ask its Boolean renderer for the
requested subject EID. Certified acyclic roots use an inclusive exact-EID bound
probe in the source-specialized host engine and never enter the recursive
state machine. The assurance manifest keeps those two implementation classes
separate.

The local multipath median fell to about 150 µs. More importantly, the
regression gate compares exact logical and backend work with 16 and 1,040
resources reachable from the subject. Both cases use one backend command,
fetch and consume one value, derive one grant, apply one rule, and render one
result. The gate is therefore about scaling behavior, not host timing.

For recursive roots, the Datomic-compatible tuple representation is
intentionally denormalized.
When a consumer bypasses EACL and retracts the resource entity directly, only
the subject-owned direct tuple can survive. A reverse miss therefore probes
that exact tuple before invoking the generated forward Boolean driver. This
does not make ordinary misses scale with subject fan-out, and it preserves the
accepted raw-EID ghost behavior.
