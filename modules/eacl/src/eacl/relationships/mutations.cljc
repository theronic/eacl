(ns eacl.relationships.mutations
  "Backend-neutral validation for public relationship mutation batches.")

(defn- relationship-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) relation (:type resource) (:id resource)])

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
