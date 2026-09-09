(ns eacl.authorization.data
  "Bounded native entity data for qualifier, Caveat, and Relation resolution.
   Unknown fields remain visible to the strict semantic decoders."
  (:require [eacl.caveats.definition :as definition]
            [eacl.execution :as execution]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.request.counters :as counters]))

(def maximum-entity-facts 4096)
(def capability :bounded-snapshot-data-v1)
(def ^:private single-valued-attributes
  (into (conj qualifier/attributes :eacl.relation/allows-unqualified?) definition/attributes))

(defn collect
  "Consumes at most the fixed fact ceiling plus one overflow witness. Native
   readers must also apply their index limit when their API realizes eagerly.
   Attribute id resolution and assertion versions belong to the same basis."
  [eid datoms attribute-key assertion-version?]
  (when-not (qualifier/concrete-eid? eid) (qualifier/error! :qualification-entity-id))
  (loop [rows (seq datoms) entity nil version nil n 0]
    (if-let [datom (first rows)]
      (do
        (counters/add-fetched-values!)
        (when (= n maximum-entity-facts) (qualifier/error! :qualification-entity-limit))
        (execution/check! :qualification-data/fact)
        (when-not (= eid (:e datom)) (qualifier/error! :qualification-entity-bound))
        (let [attribute (attribute-key (:a datom))
              value (:v datom)]
          (when-not (keyword? attribute) (qualifier/error! :qualification-attribute))
          (when (and (contains? single-valued-attributes attribute) (contains? entity attribute))
            (qualifier/error! :nonfunctional-qualification-entity))
          (recur (next rows)
                 (if (= :eacl.relation/caveats attribute)
                   (update (or entity {:db/id eid}) attribute (fnil conj #{}) value)
                   (assoc (or entity {:db/id eid}) attribute value))
                 (if (and assertion-version? (= qualifier/marker-attribute attribute)) (:tx datom) version)
                 (inc n))))
      {:entity entity :version version :fact-count n})))
