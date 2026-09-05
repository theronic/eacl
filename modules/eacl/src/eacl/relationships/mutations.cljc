(ns eacl.relationships.mutations
  "Backend-neutral relationship mutation helpers."
  (:require [eacl.authorization.context :as context]
            [eacl.caveats.values :as values]
            [eacl.relationships.storage :as storage]))

(def qualifier-keys #{:caveat :caveat-context :valid-until-ms})
(def ^:private relationship-keys (into #{:subject :relation :resource} qualifier-keys))

(defn- invalid-qualifier! [reason]
  (throw (ex-info "Invalid Relationship qualifier input."
                  {:type :eacl/invalid-relationship-qualifier
                   :eacl/error :eacl/invalid-relationship-qualifier :reason reason})))

(defn normalize-relationship
  "Admits portable public qualifier input before basis selection. Caveat names
   resolve at the selected writer basis; declared parameter validation remains
   at that boundary. Empty optional data canonicalizes to the ordinary shape."
  [{:keys [caveat caveat-context valid-until-ms] :as relationship}]
  (when-not (and (map? relationship) (every? relationship-keys (keys relationship)))
    (invalid-qualifier! :relationship-shape))
  (when (and (some? caveat) (not (values/parameter-name? caveat)))
    (invalid-qualifier! :caveat-name))
  (when (and (contains? relationship :caveat-context) (nil? caveat))
    (invalid-qualifier! :context-without-caveat))
  (when (and (some? valid-until-ms) (not (values/valid-time? valid-until-ms)))
    (invalid-qualifier! :expiry))
  (if-not (some #(contains? relationship %) qualifier-keys)
    relationship
    (let [bound (when (some? caveat-context)
                  (context/value (context/prepare caveat-context)))]
      (cond-> (apply dissoc relationship qualifier-keys)
        caveat (assoc :caveat caveat)
        (seq bound) (assoc :caveat-context bound)
        (some? valid-until-ms) (assoc :valid-until-ms valid-until-ms)))))

(defn- relationship-key
  [{:keys [subject relation resource]}]
  [(:type subject) (:id subject) relation (:type resource) (:id resource)])

(def ^:private relationship-operation-kinds #{:db/add :db/retract})
(def ^:private supported-update-operations #{:create :touch :delete})

(defn validate-operation!
  "Validates a public relationship update operation before endpoint work."
  [operation]
  (when-not (contains? supported-update-operations operation)
    (throw
     (ex-info
      (str (pr-str operation)
           " relationship update is not supported. Use :create, :touch or :delete.")
      {:type :eacl/unsupported-operation
       :eacl/error :eacl/unsupported-operation
       :operation operation})))
  true)

(defn conflict!
  "Raises the common strict-create conflict."
  [relationship]
  (throw
   (ex-info
    ":create conflicts with an existing relationship. Use :touch for idempotent writes."
    {:type :eacl/relationship-conflict
     :eacl/error :eacl/relationship-conflict
     :relationship relationship})))

(defn- relationship-operation-relation-id
  [operations op]
  (when (and (vector? op)
             (contains? operations (first op))
             (contains? storage/attributes (nth op 2 nil))
             (vector? (nth op 3 nil)))
    (nth (nth op 3) 1 nil)))

(defn coalesce-updates
  "Rejects different intents on one resolved logical relationship and keeps
   one identical update before native allocation or transaction planning.

  Every backend plans a batch from one immutable calculation snapshot, so
  repeating an operation has the same outcome as one occurrence and may
  collapse in native tx-data. Mixed operations on the same relationship have
  no portable meaning: Datomic can reject their add/retract datoms, while
  statement-order visibility can make DataScript or Datahike choose a
  different result. Reject them before any transaction is submitted."
  [updates]
  (:updates
   (reduce
    (fn [{:keys [seen] :as state} {:keys [operation relationship] :as entry}]
      (let [key (relationship-key relationship)
            intent [operation (when-not (= :delete operation) (select-keys relationship qualifier-keys))
                    (:prepared-qualifier entry)]]
        (if-let [previous (get seen key)]
          (if (= previous intent)
            state
            (throw
             (ex-info
              "A relationship mutation batch contains conflicting updates for one relationship."
              {:type :eacl/invalid-relationship-update-batch
               :eacl/error :eacl/invalid-relationship-update-batch
               :reason (if (= (first previous) operation) :conflicting-qualifiers :conflicting-operations)
               :operations [(first previous) operation]})))
          (-> state (assoc-in [:seen key] intent) (update :updates conj entry)))))
    {:seen {} :updates []}
    updates)))

(defn validate-batch!
  "Compatibility predicate for already normalized/resolved update batches."
  [updates]
  (coalesce-updates updates)
  true)

(defn normalize-updates
  "Normalizes and coalesces public input before any inert qualifier allocation."
  [updates]
  (coalesce-updates
   (mapv (fn [{:keys [operation relationship] :as update}]
           (validate-operation! operation)
           (assoc update :relationship (normalize-relationship relationship)))
         updates)))

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
