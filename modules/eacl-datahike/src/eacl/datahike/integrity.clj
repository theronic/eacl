(ns eacl.datahike.integrity
  "Explicit, offline relationship-integrity diagnostics.

   Nothing here runs on an authorization hot path. A full report scans both
   relationship tuple attributes and probes the peer half of every datom."
  (:require [eacl.datahike.impl :as impl]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(defn dangling-relationship-halves
  "Returns a lazy sequence of relationship halves whose peer half is absent."
  [db]
  (impl/orphaned-relationship-halves db))

(defn dangling-relationship-report
  "Returns total and per-half dangling counts plus a bounded sample.

   `:sample-size` defaults to 20. This is an offline operation that scans every
   relationship half and performs one exact peer-half index probe per half.
   With a logarithmic persistent index lookup, its expected cost is
   O(H log D) for H relationship halves in a database of D datoms."
  ([db]
   (dangling-relationship-report db {}))
  ([db options]
   (endpoint-pair/dangling-report
    (dangling-relationship-halves db) options)))
