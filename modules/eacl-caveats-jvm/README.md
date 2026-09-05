# EACL JVM Caveat evaluator

`dev.eacl/eacl-caveats-jvm` is an optional JVM module. Add it alongside an EACL
backend, using the same EACL artifact version. Requiring `eacl.caveats.jvm`
registers the bounded process default. It does not activate qualified serving.
Core and DataScript CLJS do not depend on this module, CEL, or ANTLR.

```clojure
(require '[eacl.caveats.definition :as definition]
         '[eacl.caveats.evaluator :as evaluator]
         '[eacl.caveats.jvm :as jvm])

(def check-region
  (definition/entity "region_match"
                     {"request_region" :string "required_region" :string}
                     "request_region == required_region"))
(evaluator/evaluate (evaluator/default-evaluator) check-region
                    {"request_region" "za"} {"required_region" "za"})
;; => {:outcome :true}
```

The API takes a canonical named definition entity and optional request/bound
maps (`nil` means absent). Both supplied maps are validated before bound values
override request values. Complete contexts use cel-parser. Incomplete contexts
use the portable partial evaluator, which preserves definite short-circuit
results and returns canonical residuals when missing fields still matter.

The profile fingerprint identifies EACL CEL profile 1 and its locked resource
bounds. The implementation fingerprint also includes the pinned evaluator
artifacts and literal-lowering version. Dependency overrides have not been
qualified. A separately supplied evaluator must pass the same conformance
suite and advertise the matching profile; registration is not certification.

Every source literal is lowered to a reserved internal binding, avoiding the
candidate library's string-unescaping divergences. Caller parameter names are
also lowered. Operand errors are preserved by a fixed overload adapter, and
unary `!` lowers to a reserved internal call because the native unary visitor
otherwise changes missing-map-key errors into overload errors. Bindings and timestamp wrappers are constructed per invocation;
only successful portable plans and parsed programs are shared. Native error values are detected
before Boolean extraction. Error messages and library objects never appear in
portable outcomes.

The default retains at most 256 compiled artifacts and builds at most four
distinct artifacts concurrently. Portable plans and native programs share that
capacity; a fully compiled definition occupies two entries. Partial inputs
retain only the portable plan and never construct a native program. Same-key misses share construction, failures wake all
waiters and are not retained, and distinct misses wait for capacity. Cache
entries include canonical name, typed parameters, source, and implementation
fingerprint; database entity IDs and request values are excluded. Schema edits
cannot reuse an old program. `jvm/evaluator` creates a separate cache; optional
`:max-entries` and `:max-builds` may lower the profile limits.

The independent 24-case corpus lives in `test/eacl/caveats/corpus.edn` and is
shared with the formal and exploration gates. Twenty cases have exact admitted
outcomes. Four reject Boolean ordering, regex, repeated ungrouped unary `!`, and
arithmetic. Profile 1 also excludes macros, conditional expressions, source
container literals, nested containers, null, floats, unsigned integers, bytes,
durations, conversions, timestamp selectors, string ordering/size, and list
concatenation. The qualification inventory records their divergences; no full
CEL or full SpiceDB compatibility is claimed.

Run module tests via an nREPL started with `clojure -M:test:nrepl --port 7793`:

```sh
clj-nrepl-eval -p 7793 '(do (require (quote eacl.caveats.jvm.evaluator-test) :reload) (require (quote eacl.caveats.jvm.program-cache-test) :reload) (clojure.test/run-tests (quote eacl.caveats.jvm.evaluator-test) (quote eacl.caveats.jvm.program-cache-test)))'
```

The module JAR includes dependency license notices in `META-INF`. Dependencies
remain separate artifacts; no CEL, ANTLR, or backend implementation is shaded.
