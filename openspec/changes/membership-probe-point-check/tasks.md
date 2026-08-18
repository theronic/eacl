# Tasks: Membership-Probe Point Check

## 1. Engine
- [x] 1.1 `probe-check-eids` in `eacl.engine.stable-route`; `check-eids` routes to it; nil ids never hold
- [x] 1.2 Reducer budgets (`:max-admissions/:max-transitions/:max-commands/:max-values/:max-stack`), typed `:eacl.reducer/limit-exceeded` with the budget key, cut-point per visit, observer counters
- [x] 1.3 Reads through the routed fetch-fn (classified/retried/telemetry), chunked intermediate enumeration
- [x] 1.4 Keep `enumeration-check-eids` as the oracle

## 2. Tests
- [x] 2.1 `eacl.engine.point-check-test`: probe = enumeration on six frozen baselines (all principals × up to 200 resources + frozen point samples)
- [x] 2.2 O(intermediates) property: 1,000,000 direct subjects, deny/allow cost one probe each
- [x] 2.3 Limits, cut-point, observer stats, nil ids
- [x] 2.4 Existing suites (`physical_route_test/anchored-point-check-test`, differential and contract suites) pass in a fresh JVM
- [ ] 2.5 Add a randomized cache-vs-bypass differential that includes popular resources (thousands of direct subjects) on all three backends

## 3. Formal
- [x] 3.1 Dafny leaf `formal/stable-discovery/MembershipProbeCheck.dfy` (`ProbeAnswerEqualsReachability`, `ProbeCheckEqualsEnumerationCheck`); registered in `verify-fast.sh` (528 obligations, batch three), `ASSURANCE_COVERAGE.md`, `execution-contract.edn` executable evidence
- [x] 3.2 `docs/stable-discovery-engine.md` and `docs/formal-verification.md` describe the point-check route and its proof

## 4. Docs
- [x] 4.1 `stable-route` namespace docstring
- [ ] 4.2 Release notes: `can?` cost no longer scales with subject count
