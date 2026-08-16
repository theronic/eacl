# EACL-FORMAL-051 — generated `can?` classified the root twice

The corrected multi-batch warm gate measured 1,006.083, 1,006.3265, and
1,004.884 µs on GitHub Actions. That stable plateau disproved the narrower
hypothesis that EACL-FORMAL-050 was merely a one-batch HotSpot transition.

The public `can?` entry point already checked whether the permission root
existed so shadow and generated routing could be selected. It then called
`can*`, whose `indexed-authoritative-root?` repeated the same schema-generation
lookup before dispatching to generated indexed traversal. A same-process local
profile measured two lookups and a 508.292 µs median.

The fixed public dispatch reuses the first classification. A defined root
enters generated traversal directly; an undefined root returns the established
`false` result without compiling an invalid plan. Dafny proves the hoist
preserves the Boolean result and reduces a generated-authoritative public check
to one root lookup. The same local process measured 483.625 µs after the
change, a 0.951465 before/after ratio. A subsequent complete local heavy run
measured three warm batch medians of 433.542, 431.8545, and 431.6665 µs,
434.79 µs cold, and 14.42 µs for a completed-cache hit; all 17 tests and 4,057
assertions passed. The 1,000 µs CI ceiling is unchanged.
