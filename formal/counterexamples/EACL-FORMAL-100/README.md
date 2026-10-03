# EACL-FORMAL-100 — an intersection generated from the operand a wildcard grants to everyone

Found by a consumer's scale review (0tx on Datahike, 8.0.0-RC-2026-10-02) and
minimized to:

```
definition user {}
definition subscription {
  relation everyone: user:*
}
definition ledger {
  relation owner: user
  relation subscription: subscription
  permission view = owner
  permission subscribed = subscription->everyone
  permission open = view & subscribed
}
```

Every ledger has a subscription that holds `everyone@user:*`; one user owns
five of them. `lookup-resources` of `open` for that user returned the right
five ledgers, but read 68 values per ledger of the platform (`68N - 2`
fetched values: 33,998 at 500 ledgers, 99,958 at 1,470) and failed with
`:eacl.recursive-traversal/limit-exceeded` (`:advanced-datoms`, limit
100,000) from 1,471 ledgers. `count-resources` failed the same way, and with
a `:count-limit` below the count its work still grew with the platform.
Checks were unaffected.

A lookup over an intersection generates its candidates from one operand, the
anchor, and decides the others per candidate. The anchor was the operand with
the smallest structural cost. A reference to a permission costs the same
whatever the permission expands to, so `view` and `subscribed` tied and the
canonical node id, which follows the operand's name, chose `subscribed`. A
relation always won, so a wildcard relation on the ledger itself
(`relation subscriber: user:*`, `view & subscriber`) was chosen too. A
wildcard relationship belongs to no subject: generating from it enumerates,
for every subject, every resource the wildcard reaches.

Writing the arrow in the intersection (`view & subscription->everyone`) or
renaming the operand so that it sorts after `view` (`zsubscribed`) read five
values at every size, which is how the defect was isolated.

Sealing now marks each expression node whose candidate cover reads a wildcard
branch, and an intersection takes such an operand as its anchor only when
every operand is one. The fixture's plans generate from `view`, whatever the
other operand is called, wherever it stands and however many arrows lie
between it and the wildcard relation.

Reproduce through a dev nREPL:

```
clj-nrepl-eval -p <port> "(require 'eacl.operator.wildcard-anchor-test :reload) (clojure.test/run-tests 'eacl.operator.wildcard-anchor-test)"
```
