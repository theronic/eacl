# Security-keyring performance checks

Run `eacl.bench.security-keyring/run!` through nREPL with an output path under
ignored `target/benchmarks/security-keyring/`. The fixture checks mint/decode
at 1, 2, 4, and 16 accepted keys. Instrumented operations must each capture one
controller snapshot and perform one named lookup; the key map supports lookup
only, so a ring traversal cannot satisfy the check.

The same fixture measures activation and retirement with 0, 64, and 512 entries
per cursor/import store. Rotation must leave the populated cursor store
physically unchanged until its next use. Cleanup removes retired cursor entries,
retired imports miss, and independently computed local answers remain eligible.
Acceptance must stay safe when physical cleanup is skipped.

For paired pre-integration/current timings, load the current
`eacl.bench.security-keyring-baseline` fixture by absolute path in each nREPL and
call `certify!` with an ignored output filename; pass `:live` for the candidate.
Use identical heap/runtime settings, warmup and batches, and run sequentially
without competing test/proof work. Keep pilots, all batch samples, and source/
environment identity with the local or CI report. These timings are diagnostics;
the fixed work assertions above enforce the ring-size independence contract.
Do not commit timing tables, samples, or report archives.
