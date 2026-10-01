# EACL JVM Caveat evaluator

`dev.eacl/eacl-caveats-jvm` is an optional JVM module. Add it alongside an EACL
backend, using the same EACL artifact version. Requiring `eacl.caveats.jvm`
registers the bounded process default. It does not activate qualified serving.
Core and DataScript CLJS do not depend on this module, CEL, or ANTLR.

```clojure
(require '[eacl.caveats.definition]
         '[eacl.caveats.evaluator]
         '[eacl.caveats.jvm])

(def check-region (eacl.caveats.definition/entity "region_match"
                                                  {"request_region" :string "required_region" :string}
                                                  "request_region == required_region"))
(eacl.caveats.evaluator/evaluate (eacl.caveats.evaluator/default-evaluator) check-region
                                 {"request_region" "za"} {"required_region" "za"})
;; => {:outcome :true}
```

The API takes a canonical named definition entity and optional request/bound
maps (`nil` means absent). Both supplied maps are validated before bound values
override request values. Complete contexts use cel-parser. Incomplete contexts
use the portable partial evaluator, which preserves definite short-circuit
results and returns canonical residuals when missing fields still matter.

The profile fingerprint identifies EACL CEL profile 2 and its locked resource
bounds. The implementation fingerprint also includes the pinned evaluator
artifacts and the literal, value and comprehension lowering versions.
Dependency overrides have not been qualified. A separately supplied evaluator
must pass the same conformance suite and advertise the matching profile;
registration is not certification.

Every source literal is lowered to a reserved internal binding, avoiding the
candidate library's string-unescaping divergences. Caller parameter names are
also lowered. Operand errors are preserved by a fixed overload adapter, and
unary `!` lowers to a reserved internal call because the native unary visitor
otherwise changes missing-map-key errors into overload errors. Bindings and timestamp wrappers are constructed per invocation;
only successful portable plans and parsed programs are shared. Native error values are detected
before Boolean extraction. Error messages and library objects never appear in
portable outcomes.

`exists` and `all` do not use cel-parser's macros. Those reparse the predicate
for every element, from token text without whitespace, which turns
`x in xs` into the unknown name `xinxs`. The adapter instead parses each
comprehension's predicate once, as its own program with the variable as a
reserved binding, and folds it over the range: the first deciding element
wins, then the first fault, then `false` for `exists` or `true` for `all`.
The fold's result is a reserved binding of the enclosing program, whose `&&`
and `||` absorb a faulted one as they absorb any faulted operand. Map keys are
visited in canonical order. Warm evaluation parses nothing, however many
elements there are. Bindings are typed once per evaluation, so an element
does not translate the context again.

The default retains at most 256 compiled artifacts and builds at most four
distinct artifacts concurrently. Portable plans and native programs share that
capacity; a fully compiled definition occupies two entries. Partial inputs
retain only the portable plan and never construct a native program. Same-key misses share construction, failures wake all
waiters and are not retained, and distinct misses wait for capacity. Cache
entries include canonical name, typed parameters, source, and implementation
fingerprint; database entity IDs and request values are excluded. Schema edits
cannot reuse an old program. `eacl.caveats.jvm/evaluator` creates a separate cache; optional
`:max-entries` and `:max-builds` may lower the profile limits.

The independent 59-case corpus lives in `test/eacl/caveats/corpus.edn` and is
shared with the formal and exploration gates. Forty-seven cases have exact
admitted outcomes, 27 of them with `exists` or `all`: true, false, empty
lists, faults inside predicates, nesting, shadowing, map keys, missing ranges
and predicate fields, and the work limit. Twelve are rejected, among them
Boolean ordering, regex, repeated ungrouped unary `!`, arithmetic,
`exists_one`, `map`, a non-list range, a non-Boolean predicate, an escaped or
malformed variable, and `__result__`. The profile also excludes other macros,
conditional expressions, source container literals, nested containers, null,
floats, unsigned integers, bytes, durations, conversions, list indexing,
timestamp selectors, string ordering/size, and list concatenation. SpiceDB
v1.56.0's answer to every case is recorded in
[`formal/fixtures/caveat-comprehensions`](../../formal/fixtures/caveat-comprehensions/README.md),
with the differences listed there; no full CEL or full SpiceDB compatibility
is claimed.

Run module tests via an nREPL started with `clojure -M:test:nrepl --port 7793`:

```sh
clj-nrepl-eval -p 7793 '(do (require (quote eacl.caveats.jvm.evaluator-test) :reload) (require (quote eacl.caveats.jvm.program-cache-test) :reload) (clojure.test/run-tests (quote eacl.caveats.jvm.evaluator-test) (quote eacl.caveats.jvm.program-cache-test)))'
```

The module JAR includes dependency license notices in `META-INF`. Dependencies
remain separate artifacts; no CEL, ANTLR, or backend implementation is shaded.
