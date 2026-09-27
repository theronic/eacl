# EACL portable Caveat evaluator

`dev.eacl/eacl-caveats-portable` evaluates named Caveats (EACL CEL profile 1)
in portable Clojure. Use it with EACL on DataScript in ClojureScript, where the
cel-parser-based [JVM evaluator](../eacl-caveats-jvm/README.md) cannot run. It
also runs on the JVM. It depends only on `dev.eacl/eacl`, uses no host interop,
and calls only core functions that `8.0.0-RC-2026-09-12` already publishes.
Expiring relationships need no evaluator; named Caveats do.

## Install

Once a release includes this module, add it at the same version as your other
EACL modules. A ClojureScript build also needs EACL's cache fork, as the
[DataScript guide](../eacl-datascript/README.md) describes:

```clojure
{:deps {dev.eacl/eacl-datascript       {:mvn/version "VERSION"}
        dev.eacl/eacl-caveats-portable {:mvn/version "VERSION"}
        com.github.theronic/cljs-cache
        {:git/url "https://github.com/theronic/cljs-cache.git"
         :git/sha "4143cc036446a47f0c6dfd9f8dde90363835051c"}}}
```

Before then, use a checkout. Its `deps.edn` points core at the checkout, so pin
core at the top level; otherwise tools.deps cannot choose between the local and
Maven versions. The module works with the published `8.0.0-RC-2026-09-12` core:

```clojure
{:deps {dev.eacl/eacl                  {:mvn/version "8.0.0-RC-2026-09-12"}
        dev.eacl/eacl-datascript       {:mvn/version "8.0.0-RC-2026-09-12"}
        dev.eacl/eacl-caveats-portable {:local/root "/path/to/eacl/modules/eacl-caveats-portable"}
        com.github.theronic/cljs-cache
        {:git/url "https://github.com/theronic/cljs-cache.git"
         :git/sha "4143cc036446a47f0c6dfd9f8dde90363835051c"}}}
```

It compiles without warnings under `:advanced` and adds about 9 KB (3 KB
gzipped) to an EACL DataScript bundle.

## Use

Require `eacl.caveats.portable` before writing a schema that names a Caveat.
Requiring it registers the process default, which clients use when you pass no
`:caveat-evaluator`:

```clojure
(ns app.acl
  (:require [datascript.core :as d]
            [eacl.caveats.portable :as caveats]
            [eacl.core :as eacl]
            [eacl.datascript.core :as eacl.datascript]))

(def conn (eacl.datascript/create-conn))
(def acl (eacl.datascript/make-client conn {}))
;; Or give one client its own instance and plan cache:
;; (eacl.datascript/make-client conn {:caveat-evaluator (caveats/evaluator)})

(eacl/write-schema! acl
  "caveat on_days(weekday int, days list<int>) { weekday in days }
   definition user {}
   definition building {
     relation cleaner: user with on_days
     permission enter = cleaner
   }")
(d/transact! conn [{:eacl/id "cleaner"} {:eacl/id "hq"}])

(def cleaner (eacl/spice-object :user "cleaner"))
(def hq (eacl/spice-object :building "hq"))
(eacl/write-relationship! acl
  (assoc (eacl/->Relationship cleaner :cleaner hq)
         :operation :touch :caveat "on_days" :caveat-context {"days" [1 2 3 4 5]}))

(eacl/check-permission acl {:subject cleaner :permission :enter :resource hq
                            :caveat-context {"weekday" 3}})
;; includes {:allowed? true :permissionship :has-permission}
(eacl/check-permission acl {:subject cleaner :permission :enter :resource hq})
;; includes {:allowed? false :permissionship :conditional-permission
;;           :missing-fields ["weekday"]}
```

`can?` is true only for `:has-permission`. Lookups and counts with
`:result-policy :detailed` also return conditional results. Values saved on the
relationship, here `days`, override request values. See
[Caveats and expiring relationships](../../docs/caveats.md) for schemas,
writes, errors and the supported expressions.

A process keeps an evaluator registered earlier. With both evaluator modules on
the JVM, `eacl.caveats.jvm` is the default whatever the load order. Pass
`:caveat-evaluator` to choose explicitly. `(caveats/evaluator {:max-entries n})`
lowers the plan cache from 256 entries, and `caveats/cache-stats` reports it.

## Semantics and identity

Evaluation follows the JVM evaluator step for step: definition validation,
admission of the request and saved contexts, saved values overriding request
values, and the work preflight. It then runs core's portable plan evaluator
(`eacl.caveats.partial`) for complete and incomplete contexts. The JVM module
uses cel-parser for complete contexts and the same portable evaluator
otherwise. Before evaluating, admitted values are rebuilt in canonical form, as
the JVM module does for cel-parser. A JVM `Boolean` object is its primitive
value, and a sorted map's comparator cannot make different keys match.

The descriptor advertises profile 1 and a fingerprint of its own, so cached
answers never mix evaluators. Plans are cached by complete definition content,
never by database ID. ClojureScript is single-threaded; on the JVM, concurrent
misses may decode the same plan twice, which changes work only.

The work preflight charges an absent parameter at its declared maximum size. An
absent `list<string>` costs about half the work limit per membership test, so
a Caveat with two such tests fails with `:resource-limit` unless the request
supplies the list, even as `[]`. The JVM evaluator behaves the same.

The profile is unchanged. Macros such as `exists` and `all`, arithmetic, regex
and the other exclusions listed in the Caveats guide remain unsupported.

## Conformance

The tests certify this module against the JVM evaluator:

- `evaluator_test.cljc` runs the shared 24-case corpus
  (`modules/eacl-caveats-jvm/test/eacl/caveats/corpus.edn`), profile semantics,
  identity, plan reuse and bounds on the JVM and in ClojureScript.
- `differential_test.clj` compares outcomes, reasons, missing fields and
  residuals with `eacl.caveats.jvm` for generated definitions using every
  operator and type, with complete, incomplete, bound-over-request and
  wrongly typed contexts, plus finite enumerations and resource limits.
- `datascript_test.cljc` exercises the public DataScript client end to end,
  including detailed lookups, counts and expiry.

The DataScript ClojureScript runner (`eacl.datascript.cljs-test-runner`)
includes the portable suites. From the repository root, the CI-equivalent
battery in `AGENTS.md` covers the JVM side. For this module alone, start an
nREPL with `clojure -M:test:nrepl --port 7794` in this directory and run:

```sh
clj-nrepl-eval -p 7794 '(do (require (quote eacl.caveats.portable.evaluator-test) (quote eacl.caveats.portable.differential-test) (quote eacl.caveats.portable.datascript-test)) (clojure.test/run-tests (quote eacl.caveats.portable.evaluator-test) (quote eacl.caveats.portable.differential-test) (quote eacl.caveats.portable.datascript-test)))'
```
