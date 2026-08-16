## 1. Commit-time create decision

- [x] 1.1 DataScript: `create-relationship-at-commit` transaction function; `tx-update-relationship` emits it for `:create` when the relationship is absent at plan time (CLJ and CLJS).
- [x] 1.2 Datahike: same transaction function, emitted only for a direct writer (`eacl.datahike.db/direct-writer?`); remote writers keep the plan-time check.
- [x] 1.3 Datahike: recover the typed conflict from the writer's wrapped exception chain in the client's transaction wrapper (`eacl.datahike.core/typed-transaction-error`).

## 2. Verification and documentation

- [x] 2.1 Deterministic interleaving tests: plan two `:create`s against one pre-write value, commit both, assert one success, one `:eacl/relationship-conflict`, one relationship (DataScript storage suite; Datahike storage suite in both attribute representations).
- [x] 2.2 Existing stale-plan identity-guard tests still reject a plan whose endpoint was retracted.
- [x] 2.3 README relationship-maintenance semantics, module READMEs, audit report §2.14/§2.19.
