(ns eacl.schema.relation-allowance
  "Named Relation alternatives at schema admission and replacement boundaries."
  (:require [clojure.set :as set]
            [eacl.caveats.values :as values]
            [eacl.caveats.definition :as definition]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.storage :as storage]))

(def attributes #{:eacl.relation/caveats :eacl.relation/allows-unqualified?})

(defn- invalid! []
  (throw (ex-info "Malformed Relation Caveat alternatives."
                  {:type :eacl.schema/invalid-relation-allowance
                   :eacl/error :eacl.schema/invalid-relation-allowance})))

(defn names
  "Validates canonical schema alternatives and returns names plus optional nil."
  [relation]
  (let [refs? (contains? relation :eacl.relation/caveats)
        plain? (contains? relation :eacl.relation/allows-unqualified?)
        refs (:eacl.relation/caveats relation)
        plain (:eacl.relation/allows-unqualified? relation)]
    (when (or (not= refs? plain?)
              (and refs? (not (and (boolean? plain) (vector? refs) (seq refs)
                                   (every? #(and (vector? %) (= 2 (count %))
                                                 (= :eacl.caveat/name (first %))
                                                 (values/parameter-name? (second %))) refs)
                                   (= refs (vec (sort (distinct refs))))))))
      (invalid!))
    (cond-> (set (map second refs)) (or (not refs?) plain) (conj nil))))

(defn canonicalize
  "Converts a native pull's named Caveat entities into portable lookup refs."
  [relation]
  (let [result
        (if (contains? relation :eacl.relation/caveats)
          (update relation :eacl.relation/caveats
                  (fn [entities]
                    (let [names (mapv :eacl.caveat/name entities)]
                      (when-not (and (seq names) (every? values/parameter-name? names)
                                     (= (count names) (count (set names))))
                        (invalid!))
                      (mapv #(vector :eacl.caveat/name %) (sort names)))))
          relation)]
    (names result)
    result))

(defn changes [{:keys [additions retractions]}]
  (let [before (into {} (map (juxt :eacl/id identity)) retractions)]
    (into [] (keep (fn [after]
                     (when-let [prior (get before (:eacl/id after))]
                       {:before prior :after after})))
          (sort-by :eacl/id additions))))

(defn entity-deletions
  "Only removed identities are entity retractions; allowance updates keep eids."
  [{:keys [additions retractions]}]
  (let [retained (set (map :eacl/id additions))]
    (remove #(contains? retained (:eacl/id %)) retractions)))

(defn attribute-retractions
  "Removes replaced optional facts without retracting the retained Relation."
  [deltas]
  (vec
   (mapcat
    (fn [{:keys [before after]}]
      (let [owner [:eacl/id (:eacl/id after)]
            removed (set/difference (set (:eacl.relation/caveats before))
                                    (set (:eacl.relation/caveats after)))]
        (concat
         (map #(vector :db/retract owner :eacl.relation/caveats %) (sort removed))
         (when (and (contains? before :eacl.relation/allows-unqualified?)
                    (not (contains? after :eacl.relation/allows-unqualified?)))
           [[:db/retract owner :eacl.relation/allows-unqualified? (:eacl.relation/allows-unqualified? before)]]))))
    (changes deltas))))

(defn validate-existing!
  "The native callback validates both stored streams and returns each retained
   Relationship's Caveat name or nil. Expiry never removes stored identity."
  [deltas referenced-caveats]
  (doseq [{:keys [before after]} (changes deltas)
          :let [allowed (names after)]
          caveat (referenced-caveats before)]
    (when-not (contains? allowed caveat)
      (throw (ex-info "Relation alternatives would invalidate a stored Relationship."
                      {:type :eacl.schema/relationship-qualifier-in-use
                       :eacl/error :eacl.schema/relationship-qualifier-in-use
                       :relation (:eacl/id after) :caveat caveat}))))
  true)

(defn stored-caveats
  "Reads only the changed Relation's two endpoint streams at one owned basis.
   Every retained pair and non-nil qualifier is checked before an allowance
   update can make that data authoritative under a new schema."
  [{:keys [entid entity rows scan]} relation]
  (let [rid (entid [:eacl/id (:eacl/id relation)])
        st (:eacl.relation/subject-type relation)
        rt (:eacl.relation/resource-type relation)
        allowed (names relation)]
    (when rid
      (mapcat
       (fn [[attribute decode prefix]]
         (map
          (fn [datom]
            (let [decoded (decode (:e datom) (:v datom))
                  {:keys [subject-type subject-eid relation-eid resource-type resource-eid qualifier-eid]} decoded
                  forward (pair/forward-value st rid rt resource-eid qualifier-eid)
                  reverse (pair/reverse-value rt rid st subject-eid qualifier-eid)]
              (when-not (and decoded (= st subject-type) (= rt resource-type) (= rid relation-eid)
                             (= [forward] (mapv :v (take 2 (rows subject-eid storage/forward-attribute forward))))
                             (= [reverse] (mapv :v (take 2 (rows resource-eid storage/reverse-attribute reverse)))))
                (qualifier/error! :asymmetric-or-duplicate-relationship))
              (let [value (when qualifier-eid (entity qualifier-eid))
                    named (when-let [caveat (get value qualifier/caveat-attribute)]
                            (definition/decode-header (entity caveat)))
                    _ (when qualifier-eid (qualifier/decode value (:parameters named)))
                    caveat (:name named)]
                (when-not (contains? allowed caveat)
                  (qualifier/error! :caveat-not-allowed))
                caveat)))
          (scan attribute prefix)))
       [[storage/forward-attribute pair/decode-forward [st rid rt]]
        [storage/reverse-attribute pair/decode-reverse [rt rid st]]]))))
