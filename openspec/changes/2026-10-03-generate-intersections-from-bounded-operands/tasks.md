## 1. Reproduction

- [x] 1.1 Backend contract `eacl.operator-engine.wildcard-anchor-contract`: a subject's listing and counts of `view & <wildcard operand>` read the same relationships at 50 and 500 ledgers and answer at 1,500 under the default limits, for a named operand in both name orders and both operand orders, an inline arrow, a wildcard relation on the resource, a mixed relation, and two arrows to a plan's wildcard; red on 43f15050 on DataScript, Datahike and Datomic
- [x] 1.2 Record EACL-FORMAL-100

## 2. Planner

- [x] 2.1 `wildcard-covers`: the least fixed point of the nodes whose cover reads a wildcard branch
- [x] 2.2 Seal `:wildcard-cover?` in the costs of marked nodes only; `select-intersection-anchor` orders by `[wildcard-cover? tuple]`
- [x] 2.3 `compiler-plan-compatibility` gains `:intersection-anchor`
- [x] 2.4 `operator-lookup/specialization-node` selects a direct specialization only for a subject type it serves (`seekable/operand-relation-ids`); record EACL-FORMAL-101

## 3. Verification

- [x] 3.1 `eacl.operator.plan-test`: anchors by shape, the mark against the sealed cover's wildcard rules, unchanged costs and anchors for plans without wildcards
- [x] 3.2 Seeded differential `eacl.operator-engine.wildcard-anchor-differential` against an independent reference, with expiring relationships, on DataScript (CLJ and CLJS) and Datahike
- [x] 3.3 `eacl.datascript.wildcard-operator-routes-test` over every subset of wildcard declarations
- [x] 3.4 `WildcardSubjects.dfy`: a cover without a wildcard lists every granted subject and owes no `*` entry; theorems listed in the assurance contract
- [x] 3.5 Mutation control `:operator-anchor-ignores-wildcard-cover`
- [x] 3.6 A cursor of the plan another anchor seals is refused
- [x] 3.7 CI-equivalent nREPL battery, DataScript ClojureScript build, `bin/formal source-closure`, mutation-control suite, counterexample replay
- [x] 3.8 `eacl.operator.lookup-test`: a direct intersection or exclusion answers for a subject type one operand does not declare and for `a - a`, with the specialization running exactly where it serves the subject type; the backend contract answers for both subject types when the anchor is such an exclusion
- [x] 3.9 Seeded differential `eacl.operator-engine.subject-type-differential`: random schemas whose relations declare users, agents, both or a wildcard of either, against an independent reference for both subject types, on DataScript (CLJ and CLJS) and Datahike

## 4. Documentation

- [x] 4.1 Permission set algebra, README wildcard section, release notes, formal verification
