(ns eacl.formal.production-kernel-cljs
  "Production CLJS kernel.

  The browser uses the differentially certified CLJC implementations.  The
  generated JavaScript adapter remains in `production-kernel-js` as a formal
  oracle and is not required by this namespace or the production hot path."
  (:require [eacl.engine.portable-decisions :as decisions]
            [eacl.engine.portable-indexed :as indexed]
            [eacl.formal.current-cache-refinement :as cache-refinement]))

(def portable-cljs-kernel
  (indexed/portable-indexed-kernel
   decisions/portable-decision-kernel))

(def current-cache-refinement
  {:artifact-sha256 cache-refinement/artifact-sha256
   :mapping-digest cache-refinement/mapping-digest})

(def default-selection
  {:kernel portable-cljs-kernel})
