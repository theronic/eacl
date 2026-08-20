# Tasks

- [x] 1.1 Dafny leaf `formal/stable-discovery/BidirectionalArrowIntersection.dfy`
      (13 obligations: `StrategiesAgree`, `DecideSound`, `DecideComplete`,
      `DecideEqualsArmAnswer`, `RoundsBoundedByShorterSide`,
      `TwoLayerArmIsIntersection`, `SeqSetMembership`); registered in
      `verify-fast.sh` batch two; obligation pin 528 → 541; gate green.
- [x] 2.1 `intersect-arm?` interleaved bidirectional decision in
      `eacl.engine.stable-route/probe-check-eids`; `:arrow-relation` and
      relation-only `:arrow-permission` arms routed through it.
- [x] 2.2 Cut-point enforcement per adapter command in `fetch!`.
- [x] 2.3 Fail-closed dispatch on unrecognized sealed rule kinds
      (`:eacl.plan/unknown-rule-kind`).
- [x] 3.1 Raw-facade sealed-plan view isolation: `ordinary-view?` public;
      stable lifecycle + plan schema identity only for ordinary stamped
      views; `stable-plan` skips FIFO insertion for request-local contexts
      with no plan identity.
- [x] 4.1 Evidence: `bidirectional-arrow-cost-is-bounded-by-smaller-side-test`,
      `bidirectional-arrow-equals-enumeration-oracle-test` (randomized
      differential vs `enumeration-check-eids`),
      `eacl.datomic.raw-plan-isolation-test` (fails on pre-change code in
      both aliasing directions); registered in `execution-contract.edn`.
- [x] 4.2 Docs: `docs/formal-verification.md`,
      `formal/stable-discovery/README.md`, `ASSURANCE_COVERAGE.md`,
      namespace docstrings.
- [ ] 5.1 Follow-up: chunk-lazy recursive-arrow descent (eager
      materialization still pays the full via-set before descending and
      can trip `:max-values` on provable grants).
- [ ] 5.2 Follow-up: multi-subject-type frozen fixture exercising the
      probe's subject-type discrimination branches.
