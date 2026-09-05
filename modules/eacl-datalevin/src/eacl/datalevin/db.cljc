(ns eacl.datalevin.db
  "Guarded native Datalevin index access for endpoint-pair relationship
  values. Every seek starts from a complete five-element tuple and is eagerly
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

(defn relationship-identity-datoms
  "A bounded native seek includes enough rows to detect duplicate qualifiers."
  [db entity attr value]
  (let [prefix (endpoint-pair/identity-prefix value)]
    (into []
          (take-while #(and (= entity (:e %)) (= attr (:a %))
                           (endpoint-pair/value-prefix? (:v %) prefix)))
          (ds/seek-datoms db :eav entity attr (conj prefix nil) 2))))

(defn global-relationship-identity-datoms
  [db attr value]
  (let [prefix (endpoint-pair/identity-prefix value)
        rows (into []
                   (take-while #(and (= attr (:a %))
                                    (endpoint-pair/value-prefix? (:v %) prefix)))
                   (ds/seek-datoms db :ave attr (conj prefix nil) nil
                                   (inc maximum-unpaged-scan-results)))]
    (when (> (count rows) maximum-unpaged-scan-results)
      (throw (ex-info "Relationship repair exceeds the bounded scan limit."
                      {:type :eacl/invalid-relationship-storage
                       :eacl/error :eacl/invalid-relationship-storage
                       :reason :repair-limit})))
    rows))

(defn all-relationship-identity-datoms
  "Exact identity cleanup may need more than the two-row membership probe.
  Only a corrupt multi-qualifier identity enters the bounded repair scan."
  [db entity attr value]
  (let [probe (relationship-identity-datoms db entity attr value)]
    (if (< (count probe) 2)
      probe
      (let [prefix (endpoint-pair/identity-prefix value)
            rows (into [] (take-while #(and (= entity (:e %)) (= attr (:a %))
                                            (endpoint-pair/value-prefix? (:v %) prefix)))
                       (ds/seek-datoms db :eav entity attr (conj prefix nil)
                                       (inc maximum-unpaged-scan-results)))]
        (when (> (count rows) maximum-unpaged-scan-results)
          (throw (ex-info "Relationship repair exceeds the bounded scan limit."
                          {:type :eacl/invalid-relationship-storage
                           :eacl/error :eacl/invalid-relationship-storage :reason :repair-limit})))
        rows))))

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
  [db entity attr prefix cursor-eid direction native-limit include-qualifier?]
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
        (into [] (take native-limit) (endpoint-pair/checked-datoms ordered include-qualifier?))))))

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
   (eavt-endpoint-prefix db entity attr prefix cursor-eid direction native-limit false))
  ([db entity attr prefix cursor-eid direction native-limit include-qualifier?]
   (if-not (and (nat-int? entity)
                (endpoint-pair/valid-prefix? prefix)
                (#{:asc :desc} direction)
                (pos-int? native-limit))
     []
     (or
      (small-endpoint-prefix
       db entity attr prefix cursor-eid direction native-limit include-qualifier?)
      (let [tail  (or cursor-eid
                      (if (= :desc direction) max-eid min-eid))
            bound (endpoint-pair/seek-bound prefix tail direction max-eid)
            scan  (if (= :desc direction)
                    (ds/rseek-datoms db :eav entity attr bound (inc native-limit))
                    (ds/seek-datoms db :eav entity attr bound (inc native-limit)))]
        (into [] (take native-limit)
              (endpoint-pair/checked-datoms
               (take-while
                (fn [{:keys [e a] :as datom}]
                  (and (= entity e) (= attr a)
                       (endpoint-pair/value-prefix? (:v datom) prefix)))
                scan)
               include-qualifier?)))))))

(defn avet-endpoint-prefix
  "Endpoint datoms across entities for an exact three-component value prefix,
  using a complete five-component AVET seek bound."
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
           bound (endpoint-pair/seek-bound prefix tail direction max-eid)
           scan  (if (= :desc direction)
                   (ds/rseek-datoms
                    db :ave attr bound cursor-entity (inc native-limit))
                   (ds/seek-datoms
                    db :ave attr bound cursor-entity (inc native-limit)))]
       (into [] (take native-limit)
             (endpoint-pair/checked-datoms
              (take-while
               (fn [{:keys [a] :as datom}]
                 (and (= attr a) (endpoint-pair/value-prefix? (:v datom) prefix)))
               scan)))))))

(defn qualified-relation-datoms
  "Complete qualified Relation stream in bounded native batches. Callers must
   consume the stream inside the selected snapshot's ownership scope."
  [db attr prefix]
  (letfn [(step [boundary]
            (lazy-seq
             (let [rows (ds/seek-datoms db :ave attr
                                        (if boundary (:v boundary) (into prefix [0 nil]))
                                        (:e boundary) 1025)
                   rows (if (and boundary (= [(:e boundary) (:v boundary)]
                                             [(:e (first rows)) (:v (first rows))]))
                          (rest rows) rows)
                   chunk (vec (take-while #(and (= attr (:a %))
                                                (endpoint-pair/value-prefix? (:v %) prefix)) rows))]
               (when (seq chunk)
                 (concat (endpoint-pair/checked-datoms chunk true)
                         (when (= (count chunk) (count rows))
                           (step (peek chunk))))))))]
    (step nil)))

(defn entity-facts [database eid]
  (mapv (fn [datom] [(:a datom) (:v datom) (:tx datom)]) (ds/datoms database :eav eid)))

(defn entity-data [database eid]
  (let [rows (entity-facts database eid)]
    (when (seq rows)
      (reduce (fn [result [a v]]
                (if (= :eacl.relation/caveats a) (update result a (fnil conj #{}) v) (assoc result a v)))
              {:db/id eid} rows))))
