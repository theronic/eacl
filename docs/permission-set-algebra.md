# Permission set algebra

EACL v8 accepts union (`+`), intersection (`&`), exclusion (`-`), nested
parentheses, relations, named permissions, and supported one-hop arrows in
permission expressions. The same denotation is used by point checks, detailed
checks, both lookup directions, filters, bounded and exact counts, permission
trees, cursor continuation, and cache hits.

## Syntax and denotation

```zed
definition user {}

definition document {
  relation reader: user
  relation writer: user
  relation banned: user

  permission contributor = reader & writer
  permission view = (reader + writer) - banned
}
```

For one selected immutable snapshot:

| Expression | Authorized set |
| --- | --- |
| `a + b` | subjects in `a` or `b` |
| `a & b` | subjects in both `a` and `b` |
| `a - b` | subjects in `a` and not in `b` |

Union binds more tightly than intersection, and intersection binds more
tightly than exclusion. Repeated exclusion associates from the left.
Therefore:

```zed
a + b & c - d   // ((a + b) & c) - d
a - b - c       // (a - b) - c
```

Parentheses override these rules. Union and intersection are canonicalized as
commutative n-ary nodes; exclusion remains an ordered binary node.

## Recursion and exclusion

Positive recursive union/intersection graphs use the least fixed point over
the selected finite snapshot. An unfinished recursive visit is not treated as
false. A positive cycle without a base derivation grants nothing.

Exclusion is strictly stratified. Every exclusion-right dependency must be in
a completed lower stratum. Any dependency cycle containing an exclusion-right
edge is rejected atomically by `write-schema!` with
`:eacl.schema/unstratified-exclusion`; the previous schema remains selected.

Direct chained arrows, `.all()` intersection arrows, caveats, wildcard
subjects, subject relations, `nil`, and `self` remain rejected. A target
permission reached through a supported one-hop arrow may itself contain named
permissions, supported arrows, union, intersection, and exclusion.

## Order, pagination, and cursors

Operator lookups filter the sealed candidate-cover plan without reordering it.
The order is deterministic for the same semantic plan and selected snapshot,
but it is not a global lexical or cross-backend ordering promise. Cache state,
batch completion order, and host map order do not change page membership or
boundaries. Union-only plans retain their existing result sequence and cursor
interpretation.

An authenticated operator cursor binds the expression and signed-stratum
certificate, cover and selected anchors, predicate and specialization policy,
order ABI, direction, selected snapshot/proof, and logical progress coordinate.
Physical batch over-read may populate compatible completed cache entries but
does not advance the cursor beyond logically consumed candidates. Concatenated
pages equal uninterrupted enumeration without duplicates or omissions.

## Cache behavior

Reusable operator results carry the complete static signed relation dependency
closure, including every intersection operand and both exclusion operands.
Short-circuiting and current absence do not narrow that proof. A newly present
right-side relationship therefore invalidates an old cached exclusion grant.

Only completed exact negative results are reusable. Cancellation, timeout,
provider failure, an unfinished fixed point, a bounded prefix, or a partially
evaluated vector publishes no negative authorization. `:cache? false` bypasses
operator cache lookup and publication while retaining the same plan, logical
demand, result, order, and cursor boundary.

## Limits

The versioned expression policy rejects oversized input before persistence or
plan construction. Defaults are:

| Scope | Limits |
| --- | --- |
| Schema source | 1,048,576 bytes |
| Per permission | 512 source nodes; depth 64; direct fan-in 128; 256 type partitions; 131,072 encoded bytes; 512 normalized nodes; 1,024 child slots; 1,024 words; checkpoint weight 131,072 |
| Whole schema | 1,024 permissions; 16,384 source nodes; 16,384 normalized nodes; 32,768 child slots; 32,768 words; checkpoint weight 8,388,608; 16,777,216 encoded expression bytes |

Portable vector batches grow deterministically to at most 256 candidates.
Recursive operator work uses the existing positive
`:recursive-traversal-limits` client configuration; the public derived-grant,
advanced-datom, and queued-work ceilings also bound operator questions/facts,
values, and queue state. Limit failures are typed and publish no partial
answer.

## Performance verification

Run the current implementation against the [operator benchmark budgets](benchmarks/operator-engine-budgets.edn)
and [reproduction guide](benchmarks/operator-engine.md). Keep latency,
allocation, and remote-I/O samples under ignored `target/benchmarks/` or as CI
artifacts. Bounded pages and exhaustive counts have separate budgets; report
cold, resident-warm, and adjacent-page behavior separately.

The Datahike physical policy uses density multiplier 2 and maximum batch width
256. Dense candidates use an endpoint-local bounded prefix; sparse candidates
use sorted exact seeks. The direct-membership tests verify bounded realization
and result equality on the current implementation.
