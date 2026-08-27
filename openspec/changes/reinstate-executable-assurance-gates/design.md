## Context

`bin/formal mutation-control` runs the four Apalache controls mechanically. The Clojure controls are one test namespace: `every-registered-mutant-is-killed-test` requires the registry's Clojure ids to equal the detector map's keys and every detector to return `true`. A detector is any zero-argument function; nothing constrains it to touch the implementation. `ledger-matches-registry-test` keeps `mutation-control.edn` equal to the registry, but the manifest's `:mutation-controls` row is generated separately and has drifted by eleven mutants. `assurance-matrix.edn` carries hand-maintained closure counts. `formal/stable-discovery/verify-fast.sh` pins 631 obligations and the repository prose says 528.

## Goals / Non-Goals

**Goals:**

- A "killed" Clojure mutant means the mutation was applied to production code, a generated artifact, or a formal model, and a required gate failed.
- Every count the manifest and matrix report is validated against the ledger that owns it.
- The corpus contains no proof-only model whose ledger row says "awaiting consumer".

**Non-Goals:**

- Automatic mutant generation. The registry stays hand-curated; only the execution discipline changes.
- New mutants for the frame-reuse changes; those changes register their own.
- The retired-engine formal cut (`adopt-stable-discovery-enumeration` 9.2).

## Decisions

### 1. Execution mechanisms

Each registry entry declares one `:mechanism`:

- `:executed-production` — the detector rebinds or wraps the production function named in the entry with the mutant and runs a named test, fixture, or differential that must fail;
- `:executed-generated` — the detector runs the generated Java/JavaScript boundary with a mutant input or a patched artifact and observes the pinned failure;
- `:executed-model` — an Apalache or Dafny control: a weakened configuration that must produce a counterexample, or a lemma variant that must fail verification;
- `:source-text` — permitted only for structural facts (a namespace is not required, a flag is absent, a file is not on a classpath), recorded with the exact pattern.

A detector whose body never references the implementation, artifact, or model it names is rejected by a registry test that inspects the detector's source form for the referenced symbol.

### 2. Rewriting the existing detectors

Each literal-only detector is classified: if a production or generated decision exists for its bug class (`cache-fail-open`, `numeric-ancestry`, `cursor-scope`, `current-cache-missing-entry-hit`, `snapshot-exact-key-omits-lifecycle`, and the rest), the detector is rewritten to mutate that decision (with `with-redefs` on the JVM, a binding seam on CLJS, or a kernel override through `eacl.verified-kernel`) and run the differential or conformance test that covers it; if no such decision exists because the bug class is modeled only in Dafny, the entry becomes `:executed-model` with a lemma variant under `formal/mutations/`; if neither applies, the entry is deleted with its reason recorded. The score requirement stays 1.0 over the surviving entries.

### 3. Count validation

`bin/validate-verification-manifest` reads `registry.edn`, `mutation-control.edn`, `public-source-closure.json`, and `verify-fast.sh`'s pinned obligation total, and fails when the manifest's mutation row, the matrix's closure row, or the prose counts in `formal/README.md` and `docs/formal-verification.md` disagree. The generator writes those counts rather than a human.

### 4. `CacheKernel.dfy`

Its only unsatisfied ledger item is a consumer. It models graph ancestry that the scalar-frontier model replaced; no runtime decision can consume it. Remove it and its `EaclKernel.dfy` include, delete `cache-kernel.edn`, and re-pin the manifest. If a consumer is preferred, the ledger must name it; "remaining" is not a state a release manifest should carry indefinitely.

## Risks / Trade-offs

- **[Rewriting ~90 detectors is tedious]** → it is mechanical per entry and most share three shapes (redef a decision, weaken a config, run a differential); entries with no real target are deleted rather than faked.
- **[Count validation adds a CI failure mode]** → that is its purpose; the generator keeps the counts current.
- **[Removing `CacheKernel.dfy` shrinks the obligation total]** → the manifest re-pin records the reason, as the retired-engine cut will.
