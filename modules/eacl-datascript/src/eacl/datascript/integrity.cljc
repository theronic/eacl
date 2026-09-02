(ns eacl.datascript.integrity
  "Read-only, offline endpoint-pair integrity diagnostics."
  (:require [eacl.datascript.impl :as impl]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(defn dangling-relationship-halves
  "Returns a lazy sequence of relationship halves whose exact peer is absent."
  [db]
  (impl/orphaned-relationship-halves db))

(defn dangling-relationship-report
  "Returns deterministic total/per-half dangling counts and a bounded sample.
  This scan never mutates the database."
  ([db]
   (dangling-relationship-report db {}))
  ([db options]
   (endpoint-pair/dangling-report
    (dangling-relationship-halves db) options)))
