# CEL dependency qualification

This exploration basis has no EACL production source paths. Phase 1 landed in
PR #173 before Phase 2 implementation began. The Phase 2 formal gate must pass
before adding these dependencies or Caveat behavior to production modules.

## Reproduction

Run `clojure -Srepro -Stree` from this directory to inspect the isolated graph.
Start `clojure -Srepro -M:nrepl --port 7791` here (or use a free port), then run
the tests through nREPL:

```sh
clj-nrepl-eval -p 7791 '(do (load-file "candidate_tests.clj") (clojure.test/run-tests (quote eacl.exploration.caveats.candidate-tests)))'
```

`candidate_tests.clj` checks 2,801 independently calculated assertions. It is
an inventory of candidate operations; the admitted subset is locked separately
in `formal/caveats/profile.edn`. Load `resource_probes.clj` through the same REPL
and call `eacl.exploration.caveats.resource-probes/run-probes!` to repeat the
resource measurements. Allocation is thread allocation, not retained heap.
The raw result's `string-source-8006-bytes` label is an old typo: its recorded
source length is 8,008 bytes, and the reproducer now uses that correct label.

`inputs.edn` pins the JAR hashes, source revisions, and independent references.
`results/source-match.json` records byte equality for all six cel-parser source
files between the published JAR and the pinned source tag. ANTLR versions are
explicit even though the candidate's transitive graph currently resolves them.
The exploration uses Clojure 1.11.4, matching EACL's basis.

## Evidence and exclusions

The independent comparison, membership, string search, Boolean, and timestamp
probes pass. The dependency returns `ErrorType` objects by default; their
unwrapped exception objects are also truthy in Clojure. The adapter must inspect
`expr/error?`, then require an actual Boolean result, with result translation
disabled. Missing parameters are native errors; EACL must own residuals.

Direct probes show repeated `!!true` returns false, `--2 == 2` returns false,
and regex matching uses full Java matches. Supplementary Unicode characters
expose UTF-16 string ordering/size differences. List concatenation does not
update the element field. Several duration and timestamp selector/conversion
cases differ. These operations are excluded from profile 1, including repeated
unary operators; grouped `!(!a)` remains valid. Macros reparse their bodies per
element and are excluded. Conditional operators and container literals are
also outside the initial profile, even where individual probes pass.

The upstream assertion helper returns a typed Boolean object, allowing false
objects to pass `clojure.test/is`. Replaying its corpus with strict Boolean
extraction gives 666 tests, 698 assertions, 43 failures, and zero errors; the
log is retained. Some failures concern fixture translation of type names, so
43 is not a count of evaluator defects. That suite is diagnostic evidence,
not EACL's conformance gate. To reproduce, check out the pinned cel-parser tag,
add its `test` path to this isolated REPL, load its test namespaces, and bind
`exoscale.cel.test-helper/equal?` to:

```clojure
(fn [x y]
  (and (= (expr/typeof x) (expr/typeof y))
       (expr/true? (expr/equal? x y))))
```

The resource probes cover 255 Boolean plan nodes, grouping depth 32, 8,008
source bytes, 128-entry containers, and worst-shaped substring inputs. On
Java 25.0.3 / Apple M4 Max, the 4,096-by-2,048 substring probe takes about 1 ms
per evaluation, far above the small comparisons. This motivates an explicit
work bound in addition to source and context size bounds. This is a measured
sample, not a hard wall-time guarantee. The large container probe may exceed
profile 1's total context byte limit and is intentionally an exploration case.

## Licenses

The actual notices are retained in `notices/`. cel-parser's source license is
ISC, whereas its POM lists MIT and ISC. antlr-cel's source license and vendored
Google grammar are Apache-2.0, whereas its POM lists MIT/ISC. The ANTLR runtime
is BSD-3-Clause. Preserve the source notices when packaging; do not infer one
license for the entire dependency graph. CEL-spec fixture adaptations retain
its Apache-2.0 notice. These metadata discrepancies do not change the pinned
code, and no production dependency has been added by this exploration.

## Native qualifier publication

`native_publication.clj` runs only against newly created disposable stores,
using the current bundled EACL schemas plus an in-memory qualifier marker.
Load it through the main project nREPL with all four backend aliases, then
call `eacl.exploration.caveats.native-publication/run-probes!`. The isolated
CEL dependency REPL does not contain the database dependencies.

The initial native results are in `results/native-publication.edn`:

| Backend | New qid inside tuple | Prepared concrete qid | Retract qualifier entity |
| --- | --- | --- | --- |
| Datomic 1.0.7705 | String tempid resolves in both halves | Works | Tuple ref remains non-nil |
| DataScript 1.7.8 JVM and CLJS | String tempid remains unresolved | Works | Tuple ref remains non-nil |
| Datahike 0.8.1759 | Negative tempid remains unresolved | Works | Tuple ref remains non-nil |
| Datalevin local fork a7e29c25 | Negative tempid resolves in both halves | Works | Tuple ref remains non-nil |

These probes establish native nested-ref behavior, not the complete writer
certification. The latter must also exercise Relation stamps, application
datoms, current write policies, failed publication, schema races, Datahike
attribute-ref mode, and DataScript CLJS. The strategy remains unsupported until
those staged-writer conformance tests pass. None of these probes activates
public qualified Relationship writes.

The CLJS counterpart is `cljs/eacl/exploration/caveats/publication_probe.cljs`;
its Node run passes 12 assertions (`results/cljs-publication.log`). Build the
`exploration/caveats/cljs` input directory through the project's CLJS-enabled
nREPL with `cljs.build.api/build`, target `:nodejs`, main
`eacl.exploration.caveats.publication-probe`, then run its output with Node.
