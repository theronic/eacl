(ns eacl.datomic.io-stats
  "Optional Datomic operation telemetry normalized into EACL's cache-only
  metrics store. Datomic I/O stats describe cache/storage work for an API call;
  they are never interpreted as relationship cardinality."
  (:require [datomic.api :as d]
            [eacl.metrics :as metrics]))

(defn pull
  "Equivalent to `datomic.api/pull`. When a metrics context is bound, requests
  Datomic I/O stats and records the returned physical-cost observation."
  [db pattern eid operation]
  (if (and metrics/*store* metrics/*context*)
    (let [{:keys [ret io-stats]}
          (d/pull db pattern eid :io-context :eacl.metrics/datomic-pull)]
      (when io-stats
        (metrics/record-io! operation io-stats))
      ret)
    (d/pull db pattern eid)))
