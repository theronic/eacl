(ns eacl.relationships.mutations
  "Backend-neutral relationship mutation helpers."
  (:require [eacl.relationships.storage :as storage]))

(defn- relationship-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) relation (:type resource) (:id resource)])

(def ^:private relationship-operation-kinds #{:db/add :db/retract})

(defn- relationship-operation-relation-id
  [operations op]
  (when (and (vector? op)
             (contains? operations (first op))
             (contains? storage/attributes (nth op 2 nil))
             (vector? (nth op 3 nil)))
    (nth (nth op 3) 1 nil)))

(defn validate-batch!
  "Rejects different operations on one resolved logical relationship.

  Every backend plans a batch from one immutable calculation snapshot, so
  repeating an operation has the same outcome as one occurrence and may
  collapse in native tx-data. Mixed operations on the same relationship have
  no portable meaning: Datomic can reject their add/retract datoms, while
  statement-order visibility can make DataScript or Datahike choose a
  different result. Reject them before any transaction is submitted."
  [updates]
  (reduce
   (fn [operations-by-relationship
        {:keys [operation relationship]}]
     (let [key (relationship-key relationship)]
       (if-let [previous (get operations-by-relationship key)]
         (if (= previous operation)
           operations-by-relationship
           (throw
            (ex-info
             "A relationship mutation batch contains conflicting operations for one relationship."
             {:type :eacl/invalid-relationship-update-batch
              :eacl/error :eacl/invalid-relationship-update-batch
              :reason :conflicting-operations
              :operations [previous operation]})))
         (assoc operations-by-relationship key operation))))
   {}
   updates)
  true)

(defn stamp-relation-generations
  "Adds one idempotent backend-native generation stamp per affected relation.

  `stamp` converts a relation eid to the backend's native transaction op;
  `generation-attribute` identifies stamps already present in `tx-data`."
  [tx-data generation-attribute stamp]
  (let [ops (vec tx-data)
        relation-ids
        (into #{}
              (keep (partial relationship-operation-relation-id
                             relationship-operation-kinds))
              ops)
        stamped
        (into #{}
              (keep
               (fn [op]
                 (when (and (vector? op)
                            (= :db/add (first op))
                            (= generation-attribute (nth op 2 nil)))
                   (nth op 1))))
              ops)]
    (into ops
          (map stamp)
          (sort (remove stamped relation-ids)))))

(defn affected-relation-ids
  "Returns sorted unique relation ids named by selected relationship ops."
  [tx-data operations]
  (->> tx-data
       (into #{}
             (keep (partial relationship-operation-relation-id operations)))
       sort
       vec))
