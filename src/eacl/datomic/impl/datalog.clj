(ns eacl.datomic.impl.datalog
  "Compatibility namespace for the retired Datalog implementation.

  The indexed implementation owns pagination semantics. Keeping these vars
  delegated avoids a stale second list API with the removed :limit/:cursor
  contract."
  (:require [eacl.datomic.impl.indexed :as indexed]))

(def can? indexed/can?)
(def lookup-subjects indexed/lookup-subjects)
(def lookup-resources indexed/lookup-resources)
(def count-resources indexed/count-resources)
