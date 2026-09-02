(ns eacl.datalevin.db
  "Guarded native Datalevin index access for endpoint-pair relationship
  values. Every seek starts from a complete four-element tuple and is eagerly
  realized inside the explicit Datalevin read-snapshot scope."
  (:require [datalevin.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(def min-eid 0)

(def max-eid backend/maximum-exact-integer)

(def maximum-unpaged-scan-results 100000)

(def ^:private small-endpoint-scan-threshold
  "Maximum number of relationship datoms on one endpoint for the local scan
  fast path. Datalevin's exact EAV prefix lookup is substantially cheaper than
  opening a seek iterator for the small adjacency lists that dominate
  interactive authorization. Larger endpoints retain the bounded exact-prefix
  seek so unrelated relationships cannot amplify a requested scan."
  32)

(defn with-db
  "Runs `f` with a raw Datalevin DB. Public request paths pass an explicit
  read snapshot; raw compatibility helpers may pass a DB directly."
  [snapshot-or-db f]
  (if (ds/read-snapshot? snapshot-or-db)
    (ds/with-read-snapshot snapshot-or-db f)
    (f snapshot-or-db)))

(defn entid
  [db eid-or-ref]
  (ds/entid db eid-or-ref))

(defn avet-datoms
  ([db attr]
   (ds/datoms db :ave attr))
  ([db attr value]
   (ds/datoms db :ave attr value)))

(defn eavt-datoms
  ([db entity attr]
   (ds/datoms db :eav entity attr))
  ([db entity attr value]
   (ds/datoms db :eav entity attr value)))

(defn- within-inclusive-cursor?
  [direction cursor-eid {:keys [v]}]
  (or (nil? cursor-eid)
      (let [endpoint-eid (nth v 3)]
        (if (= :desc direction)
          (<= endpoint-eid cursor-eid)
          (>= endpoint-eid cursor-eid)))))

(defn- small-endpoint-prefix
  "Returns an exact result for a small endpoint adjacency list, or nil when
  the list crossed the threshold and the caller must use the bounded native
  seek. The threshold+1 sample makes that choice without ever mistaking a
  truncated sample for a complete adjacency list."
  [db entity attr prefix cursor-eid direction native-limit]
  (let [sample
        (into []
              (take (inc small-endpoint-scan-threshold))
              (ds/datoms db :eav entity attr))]
    (when (<= (count sample) small-endpoint-scan-threshold)
      (let [matching
            (into []
                  (filter #(and (endpoint-pair/value-prefix? (:v %) prefix)
                                (within-inclusive-cursor?
                                 direction cursor-eid %)))
                  sample)
            ordered (if (= :desc direction)
                      (rseq matching)
                      matching)]
        (into [] (take native-limit) ordered)))))

(defn eavt-endpoint-prefix
  "Endpoint datoms for an exact three-component value prefix.

  `cursor-eid` is an inclusive lower/upper seek bound; portable cursor logic
  decides whether to drop the boundary row. Direction is `:asc` or `:desc`."
  ([db entity attr prefix]
   (eavt-endpoint-prefix db entity attr prefix nil :asc
                         maximum-unpaged-scan-results))
  ([db entity attr prefix cursor-eid direction]
   (eavt-endpoint-prefix db entity attr prefix cursor-eid direction
                         maximum-unpaged-scan-results))
  ([db entity attr prefix cursor-eid direction native-limit]
   (if-not (and (nat-int? entity)
                (endpoint-pair/valid-prefix? prefix)
                (#{:asc :desc} direction)
                (pos-int? native-limit))
     []
     (or
      (small-endpoint-prefix
       db entity attr prefix cursor-eid direction native-limit)
      (let [tail  (or cursor-eid
                      (if (= :desc direction) max-eid min-eid))
            bound (conj prefix tail)
            scan  (if (= :desc direction)
                    (ds/rseek-datoms db :eav entity attr bound native-limit)
                    (ds/seek-datoms db :eav entity attr bound native-limit))]
        (into []
              (take-while
               (fn [{:keys [e a] :as datom}]
                 (and (= entity e)
                      (= attr a)
                      (endpoint-pair/value-prefix? (:v datom) prefix))))
              scan))))))

(defn avet-endpoint-prefix
  "Endpoint datoms across entities for an exact three-component value prefix,
  using a complete four-component AVET seek bound."
  ([db attr prefix]
   (avet-endpoint-prefix db attr prefix nil :asc
                         maximum-unpaged-scan-results))
  ([db attr prefix cursor-eid direction]
   (avet-endpoint-prefix db attr prefix cursor-eid direction
                         maximum-unpaged-scan-results))
  ([db attr prefix cursor-eid direction native-limit]
   (avet-endpoint-prefix db attr prefix cursor-eid nil direction native-limit))
  ([db attr prefix cursor-eid cursor-entity direction native-limit]
   (if-not (and (endpoint-pair/valid-prefix? prefix)
                (#{:asc :desc} direction)
                (or (nil? cursor-entity) (nat-int? cursor-entity))
                (pos-int? native-limit))
     []
     (let [tail  (or cursor-eid
                     (if (= :desc direction) max-eid min-eid))
           bound (conj prefix tail)
           scan  (if (= :desc direction)
                   (ds/rseek-datoms
                    db :ave attr bound cursor-entity native-limit)
                   (ds/seek-datoms
                    db :ave attr bound cursor-entity native-limit))]
       (into []
             (take-while
              (fn [{:keys [a] :as datom}]
                (and (= attr a)
                     (endpoint-pair/value-prefix? (:v datom) prefix))))
             scan)))))
