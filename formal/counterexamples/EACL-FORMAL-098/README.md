# EACL-FORMAL-098 — a folded intersection delegated to a union plan that cannot exist

Found by the eacl-rust differential harness (`eacl-diff clojure-campaign`,
seed 20261001, scenario s20261001-72) and minimized to:

```
definition user {}
definition project {
  relation viewer: user
  permission access = manage
  permission manage = (viewer & viewer) + access
}
```

The semantic DAG of an operator plan normalizes `viewer & viewer` to
`viewer`, so `manage` has the DAG of `viewer + access` and delegation took it
and `access` for union-only. The union engine seals the stored expression,
which keeps the intersection, and refuses both. Delegated operands were then
decided through the operator plan itself, so a check denied a viewer, and
lookups and counts threw a host `ClassCastException` while sealing a
flattened generator whose root was delegated.

This is a regression of operator delegation (PR #199): main a698b3bd and the
eacl-rust port answer the same scenario with a viewer grant correctly
(`eacl-diff port-replay`, parity 5/5).

Sealing now records `:folded-operator?` where a stored operator was folded,
and `operator-plan/operator-permission?` counts it, so a permission is
delegated only when the union engine can seal it. The regression pairs each
folded shape with its unfolded twin and requires every check, lookup, count
and reverse lookup to equal the twin's.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.operator.folded-operator-test :reload) (clojure.test/run-tests 'eacl.operator.folded-operator-test)"
```
