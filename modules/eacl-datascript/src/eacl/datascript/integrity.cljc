(ns eacl.datascript.integrity
  "Read-only, offline endpoint-pair integrity diagnostics."
  (:require [eacl.datascript.qualifiers :as qualifiers]
            [eacl.relationships.qualifier-integrity :as qualifier-integrity]
            [eacl.datascript.impl :as impl]
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

(defn qualifier-proof-input
  "Captures offline qualifier, ownership, source, version, and Relation proof inputs."
  [snapshot]
  (qualifier-integrity/proof-input (qualifiers/read-api) snapshot))

(defn qualifier-report
  ([snapshot] (qualifier-report snapshot {}))
  ([snapshot options]
   (qualifier-integrity/report (qualifier-proof-input snapshot) options)))
