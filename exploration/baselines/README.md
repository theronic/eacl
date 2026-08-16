# Frozen current-engine baselines

Reproducible public-API baselines for the `adopt-stable-discovery-enumeration`
change (tasks 2.2–2.5). They freeze the CURRENT engines (global entity-ID
merge + generated fixed-point machine) as differential oracles before the
stable-discovery engine replaces them, and they survive old-engine deletion
as the captured comparison record.

## What is authoritative

- **Denotations** (`:denotation` — sorted result sets), **counts**, **point
  checks**, pagination invariants (`:page-composition-equals-one-shot?`,
  `:duplicate-free?`, `:count-matches-denotation?`), behavioral outcome
  classes (cursor idempotence/fork, typed cancellation, timeout validation,
  stale-basis continuation), and the perf file's `:first-run` logical
  scan/command counters. These are environment-independent.
- **Informational only**: `:order` vectors (legacy page order is explicitly
  not an oracle for the replacement engine — design Decision 1) and all
  latency/allocation numbers (environment-specific; the `:env` stamp in
  `perf-clj-datascript.edn` records the capture hardware/JVM).

## Files

- `<fixture>.edn` — behavior snapshots for the seven fixtures:
  `explorer-acyclic` (direct, union-overlap, deep arrow, dense principal),
  `explorer-recursive` (account parent chains), `folder-chain` (20-level
  recursive chain), `group-star` (30-leaf recursive star), `mutual-mixed`
  (mutual recursion across two definitions with a data cycle), `cyclic-data`
  (pure parent cycle), `broad-union` (8-way union, late-productive
  principal).
- `perf-clj-datascript.edn` — warm-repeat latency/allocation medians plus
  first-execution logical scan counts, DataScript CLJ, answer caching
  disabled.

## Reproducing

All fixtures are fully deterministic (no randomness; fixed identifiers and
shapes defined in `eacl.baseline.capture` and `eacl.baseline.perf` under
`modules/eacl/test/eacl/baseline/`). JVM settings: the repository's default
`:dev`/`:nrepl` aliases, no extra JVM flags; perf hardware is recorded in the
snapshot's `:env`.

Start an nREPL (see `AGENTS.md`), then:

```bash
clj-nrepl-eval -p <port> "(require 'eacl.baseline.capture) (eacl.baseline.capture/capture-all!)"
```

```bash
clj-nrepl-eval -p <port> "(require 'eacl.baseline.perf) (eacl.baseline.perf/capture-perf!)"
```

Verify without regenerating:

```bash
clj-nrepl-eval -p <port> "(require 'eacl.baseline.baseline-test) (clojure.test/run-tests 'eacl.baseline.baseline-test)"
```

Warm/cold definitions: `capture-all!` and the perf warm medians run repeated
queries against one long-lived client (client-side caches other than the
disabled answer tier may assist repeats — that is today's public repeat
behavior and is frozen as such); each perf `:first-run` executes once against
a freshly seeded client, which is the authoritative logical-work condition.

## Open items (task 2.4 remainder)

- **CLJS latency/allocation baselines**: capture via the CLJS build pipeline
  (`clojure -M:dev:formal-smoke:formal-cljs-smoke:nrepl`, compile with
  `cljs.build.api` as in `formal/smoke/cljs/run`, then run under Node). Not
  yet captured.
- **Controlled MinIO/JDBC/DynamoDB-local operation baselines**: run the
  archived physical probe project (`exploration/stable-discovery/
  backend-probes/`) against fresh containers; the exploration-era numbers it
  produced are recorded in `exploration/stable-discovery/
  PHYSICAL_BACKEND_AUDIT.md`. Not yet re-captured from this repository state.
