(ns eacl.datalevin.integrity
  "Read-only offline diagnostics in an explicit native read snapshot."
  (:require [datalevin.core :as d]
            [eacl.datalevin.db :as db]
            [eacl.datalevin.qualifiers :as qualifiers]
            [eacl.relationships.qualifier-integrity :as qualifier-integrity]))

(defn qualifier-proof-input
  "Captures offline qualifier, ownership, source, version, and Relation proof inputs."
  [snapshot]
  (when-not (d/read-snapshot? snapshot)
    (throw (ex-info "Qualifier integrity requires an explicit Datalevin read snapshot."
                    {:type :eacl/unsupported-snapshot :eacl/error :eacl/unsupported-snapshot})))
  (db/with-db snapshot #(qualifier-integrity/proof-input (qualifiers/read-api) %)))

(defn qualifier-report
  ([snapshot] (qualifier-report snapshot {}))
  ([snapshot options]
   (qualifier-integrity/report (qualifier-proof-input snapshot) options)))
