(ns eacl.schema.relation-allowance
  "Named Relation alternatives at schema admission and replacement boundaries.

  One Relation entity holds up to two branches for its subject type: the
  concrete branch (`user`, `user with c`) and the wildcard branch (`user:*`,
  `user:* with c`). Each branch allows a set of Caveat names, where nil means
  a Relationship without a Caveat is allowed."
  (:require [clojure.set :as set]
            [eacl.caveats.values :as values]
            [eacl.caveats.definition :as definition]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.qualifier :as qualifier]
            [eacl.relationships.storage :as storage]
            [eacl.schema.wildcard :as wildcard]))

(def ^:private caveats-attribute :eacl.relation/caveats)
(def ^:private unqualified-attribute :eacl.relation/allows-unqualified?)

(def attributes
  (into #{caveats-attribute unqualified-attribute} wildcard/attributes))

(def ^:private branch-attributes
  {:concrete [caveats-attribute unqualified-attribute]
   :wildcard [wildcard/caveats-attribute wildcard/unqualified-attribute]})

(defn- invalid! []
  (throw (ex-info "Malformed Relation Caveat alternatives."
                  {:type :eacl.schema/invalid-relation-allowance
                   :eacl/error :eacl.schema/invalid-relation-allowance})))

(defn- canonical-refs? [refs]
  (and (vector? refs) (seq refs)
       (every? #(and (vector? %) (= 2 (count %))
                     (= :eacl.caveat/name (first %))
                     (values/parameter-name? (second %))) refs)
       (= refs (vec (sort (distinct refs))))))

(defn branches
  "Validates canonical schema alternatives and returns
  `{:concrete names :wildcard names}`, where each set holds allowed Caveat
  names plus nil for a Relationship without a Caveat.

  A Relation without allowance attributes is the legacy plain concrete
  branch. A declared wildcard branch always carries
  `:eacl.relation/allows-unqualified-wildcard?`. A Relation whose only branch
  is a wildcard stores `:eacl.relation/allows-unqualified? false` without
  concrete Caveats, the concrete state that a Peer predating wildcards
  rejects."
  [relation]
  (let [refs? (contains? relation caveats-attribute)
        plain? (contains? relation unqualified-attribute)
        refs (get relation caveats-attribute)
        plain (get relation unqualified-attribute)
        wildcard-refs? (contains? relation wildcard/caveats-attribute)
        wildcard? (contains? relation wildcard/unqualified-attribute)
        wildcard-refs (get relation wildcard/caveats-attribute)
        wildcard-plain (get relation wildcard/unqualified-attribute)
        wildcard-names
        (cond
          (not wildcard?) (if wildcard-refs? (invalid!) #{})
          (not (boolean? wildcard-plain)) (invalid!)
          wildcard-refs? (if (canonical-refs? wildcard-refs)
                           (cond-> (set (map second wildcard-refs))
                             wildcard-plain (conj nil))
                           (invalid!))
          wildcard-plain #{nil}
          :else (invalid!))
        concrete-names
        (cond
          (and (not refs?) (not plain?)) #{nil}
          refs? (if (and plain? (boolean? plain) (canonical-refs? refs))
                  (cond-> (set (map second refs)) plain (conj nil))
                  (invalid!))
          (and (false? plain) wildcard?) #{}
          :else (invalid!))]
    {:concrete concrete-names :wildcard wildcard-names}))

(defn names
  "Validates canonical schema alternatives and returns the concrete branch's
  names plus optional nil."
  [relation]
  (:concrete (branches relation)))

(defn- canonical-ref-attribute
  [relation attribute]
  (if (contains? relation attribute)
    (update relation attribute
            (fn [entities]
              (let [names (mapv #(if (map? %) (:eacl.caveat/name %) (second %)) entities)]
                (when-not (and (seq names) (every? values/parameter-name? names)
                               (= (count names) (count (set names))))
                  (invalid!))
                (mapv #(vector :eacl.caveat/name %) (sort names)))))
    relation))

(defn canonicalize
  "Converts a native pull's named Caveat entities into portable lookup refs."
  [relation]
  (let [result (-> relation
                   (canonical-ref-attribute caveats-attribute)
                   (canonical-ref-attribute wildcard/caveats-attribute))]
    (branches result)
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
      (let [owner [:eacl/id (:eacl/id after)]]
        (mapcat
         (fn [[refs-attribute flag-attribute]]
           (let [removed (set/difference (set (get before refs-attribute))
                                         (set (get after refs-attribute)))]
             (concat
              (map #(vector :db/retract owner refs-attribute %) (sort removed))
              (when (and (contains? before flag-attribute)
                         (not (contains? after flag-attribute)))
                [[:db/retract owner flag-attribute (get before flag-attribute)]]))))
         (vals branch-attributes))))
    (changes deltas))))

(defn- reference-branch
  "A stored reference is `[form caveat-name]`; a bare name is concrete."
  [reference]
  (if (vector? reference) reference [:concrete reference]))

(defn validate-existing!
  "The native callback validates both stored streams and returns each retained
  Relationship's `[form caveat-name]`, where form is `:concrete` or
  `:wildcard` (a bare name is concrete). Expiry never removes stored
  identity."
  [deltas referenced-caveats]
  (doseq [{:keys [before after]} (changes deltas)
          :let [allowed (branches after)]
          reference (referenced-caveats before)
          :let [[form caveat] (reference-branch reference)]]
    (when-not (contains? (get allowed form) caveat)
      (throw (ex-info (if (= :wildcard form)
                        "Relation alternatives would invalidate a stored wildcard Relationship."
                        "Relation alternatives would invalidate a stored Relationship.")
                      (cond-> {:type :eacl.schema/relationship-qualifier-in-use
                               :eacl/error :eacl.schema/relationship-qualifier-in-use
                               :relation (:eacl/id after) :caveat caveat}
                        (= :wildcard form) (assoc :wildcard? true))))))
  true)

(defn stored-caveats
  "Reads only the changed Relation's two endpoint streams at one owned basis.
   Every retained pair and non-nil qualifier is checked before an allowance
   update can make that data authoritative under a new schema. Returns one
   `[form caveat-name]` per stored half, where form is `:wildcard` when the
   subject is the wildcard subject entity."
  [{:keys [entid entity rows scan]} relation]
  (let [rid (entid [:eacl/id (:eacl/id relation)])
        wildcard-eid (entid wildcard/lookup-ref)
        st (:eacl.relation/subject-type relation)
        rt (:eacl.relation/resource-type relation)
        allowed (branches relation)]
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
              (let [form (if (and wildcard-eid (= wildcard-eid subject-eid)) :wildcard :concrete)
                    value (when qualifier-eid (entity qualifier-eid))
                    named (when-let [caveat (get value qualifier/caveat-attribute)]
                            (definition/decode-header (entity caveat)))
                    _ (when qualifier-eid (qualifier/decode value (:parameters named)))
                    caveat (:name named)]
                (when-not (contains? (get allowed form) caveat)
                  (qualifier/error! :caveat-not-allowed))
                [form caveat])))
          (scan attribute prefix)))
       [[storage/forward-attribute pair/decode-forward [st rid rt]]
        [storage/reverse-attribute pair/decode-reverse [rt rid st]]]))))
