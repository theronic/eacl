(ns eacl.datahike.direct-membership
  "Density-bounded direct-relation membership over one selected Datahike DB.

  Compact candidate spans use one endpoint-local ordered prefix. Sparse spans
  use exact full-tuple seeks. The operation never calls d/db, resolves schema,
  changes descriptor endpoints, or evaluates permission expressions."
  (:require [eacl.datahike.db :as ddb]
            [eacl.execution :as execution]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.request.counters :as request-counters]))

(def physical-policy-version :datahike-density-bounded-v2)
(def density-multiplier 2)
(def physical-policy-identity
  "The dense range kernel seeks from the batch's first candidate, and only a
  direct DB can honor that bound: temporal and filter wrappers fall back to
  exact visible datoms, where a range scan would realize and sort the whole
  endpoint prefix. Wrapped bases therefore always take the exact-probe
  kernel, whose realization is bounded by the candidate count on every
  admissible basis kind."
  {:id physical-policy-version
   :parameters
   {:density-multiplier density-multiplier
    :maximum-width 256
    :dense-kernel :endpoint-local-bounded-prefix-v1
    :dense-kernel-bases :direct-database-values
    :sparse-kernel :sorted-exact-seek-v1}})

(def ^:dynamic *physical-stats*
  "Optional observation-only atom for dimensional batch telemetry."
  nil)

(defn- add-stat! [counter amount]
  (when *physical-stats*
    (swap! *physical-stats* update counter (fnil + 0) amount))
  nil)

(defn- checkpoint! [stage consumed]
  (execution/check! execution/*contract* stage consumed))

(defn- shape
  [{:keys [direction descriptor]}]
  (if (= :forward direction)
    {:endpoint-eid (:subject-eid descriptor)
     :attribute relationship-storage/forward-attribute
     :prefix [(:subject-type descriptor)
              (:relation-eid descriptor)
              (:resource-type descriptor)]}
    {:endpoint-eid (:resource-eid descriptor)
     :attribute relationship-storage/reverse-attribute
     :prefix [(:resource-type descriptor)
              (:relation-eid descriptor)
              (:subject-type descriptor)]}))

(defn- ordered-candidates [candidates]
  (->> candidates
       (map-indexed (fn [index [_type eid]]
                      {:index index :eid eid}))
       (sort-by :eid)
       vec))

(defn- scatter [candidate-count ordered decisions]
  (reduce (fn [result [{:keys [index]} decision]]
            (assoc result index decision))
          (vec (repeat candidate-count false))
          (map vector ordered decisions)))

(defn dense-span?
  "The checked physical choice. Clojure integer subtraction cannot overflow;
  the public batch contract has already bounded every EID to the portable exact
  domain."
  [ordered]
  (let [candidate-count (count ordered)]
    (and (pos? candidate-count)
         (let [span (inc (- (:eid (peek ordered))
                            (:eid (first ordered))))]
           (<= span (* density-multiplier candidate-count))))))

(defn- dense-decisions
  [db {:keys [endpoint-eid attribute prefix]} ordered]
  (let [first-eid (:eid (first ordered))
        last-eid (:eid (peek ordered))
        datoms
        (ddb/eavt-tuple-prefix
         db endpoint-eid attribute 4 prefix first-eid)
        {:keys [present realized overread]}
        (loop [remaining datoms
               present (transient #{})
               realized 0]
          (checkpoint! :direct-membership-batch/dense-prefix
                       {:fetched-values realized})
          (if-let [datom (first remaining)]
            (let [eid (nth (:v datom) 3)
                  realized (inc realized)]
              (if (> eid last-eid)
                {:present (persistent! present)
                 :realized realized
                 :overread 1}
                (recur (rest remaining) (conj! present eid) realized)))
            {:present (persistent! present)
             :realized realized
             :overread 0}))]
    (request-counters/add! :fetched-values realized)
    (request-counters/add! :probes (count ordered))
    (add-stat! :physical-subgroups 1)
    (add-stat! :dense-prefix-groups 1)
    (add-stat! :prefix-values realized)
    (add-stat! :adapter-fetched-values realized)
    (add-stat! :batch-overread overread)
    (mapv #(contains? present (:eid %)) ordered)))

(defn- sparse-decisions
  [db {:keys [endpoint-eid attribute prefix]} ordered]
  (loop [index 0
         decisions (transient [])
         fetched 0]
    (if (= index (count ordered))
      (let [decisions (persistent! decisions)]
        (request-counters/add! :fetched-values fetched)
        (request-counters/add! :probes (count ordered))
        (add-stat! :physical-subgroups 1)
        (add-stat! :sparse-exact-groups 1)
        (add-stat! :exact-seeks (count ordered))
        (add-stat! :adapter-fetched-values fetched)
        decisions)
      (do
        (checkpoint! :direct-membership-batch/sparse-probe
                     {:probes index :fetched-values fetched})
        (let [eid (:eid (nth ordered index))
              present?
              (boolean
               (seq
                (ddb/eavt-datoms
                 db endpoint-eid attribute (conj prefix eid))))]
          (recur (inc index)
                 (conj! decisions present?)
                 (+ fetched (if present? 1 0))))))))

(defn direct-match-many?
  "Executes one already-normalized batch and returns decisions in the original
  candidate order. Any cancellation or provider failure escapes before the
  result vector is returned."
  [db {:keys [candidates] :as request}]
  (if (empty? candidates)
    []
    (let [ordered (ordered-candidates candidates)
          descriptor (shape request)
          ;; A wrapped basis cannot honor the dense kernel's seek bound, so
          ;; selecting it there would realize the entire endpoint prefix.
          dense? (and (ddb/direct-db? db) (dense-span? ordered))
          decisions (if dense?
                      (dense-decisions db descriptor ordered)
                      (sparse-decisions db descriptor ordered))]
      (request-counters/add! :commands 1)
      (add-stat! :adapter-commands 1)
      (add-stat! :scalar-equivalent-predicates (count candidates))
      (scatter (count candidates) ordered decisions))))
