(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
   [datomic.api :as d]
   [eacl.core :as eacl :refer [spice-object]]
   [eacl.datomic.impl.base :as base]
   [eacl.datomic.impl.indexed :as impl.indexed]))

(def Relation base/Relation)
(def Permission base/Permission)

(defn Relationship
  "Constructs relationship data for the public/internal API boundary.
  Persistence is handled by tuple-specific write helpers below."
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(defn can?
  ([db subject permission resource]
   (impl.indexed/can? db subject permission resource))
  ([db demand]
   (impl.indexed/can? db demand)))

(defn lookup-subjects
  [db query]
  (impl.indexed/lookup-subjects db query))

(defn lookup-resources
  [db query]
  (impl.indexed/lookup-resources db query))

(defn count-resources
  [db query]
  (impl.indexed/count-resources db query))

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def ^:private reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(defn can!
  "The thrown exception should probably be configurable."
  [db subject permission resource]
  (if (can? db subject permission resource)
    true
    (throw (Exception. "Unauthorized"))))

(defn- unknown-object!
  [object-id]
  (throw (ex-info (str "Unknown object: " (pr-str object-id) " does not resolve to an existing entity."
                       " Pass {:allow-tempids? true} to tx-relationship for same-transaction tempids.")
           {:type :eacl/unknown-object
            :object-id object-id})))

(defn- object-id->eid-or-tempid
  "Resolves an object id to an existing eid. Unresolvable ids throw
  :eacl/unknown-object unless :allow-tempids? is set, in which case strings,
  negative longs and datomic.db.DbId values pass through as tempids for
  same-transaction entity+relationship creation. Silent tempid pass-through
  minted ghost entities on typo'd ids (audit §12). Positive numeric eids are
  verified via datom presence — the transactor rejects unallocated eids anyway,
  but with a raw :db.error/invalid-entity-id."
  [db object-id {:keys [allow-tempids?]}]
  (cond
    (number? object-id)
    (cond
      (seq (d/datoms db :eavt object-id)) object-id
      (and allow-tempids? (neg? object-id)) object-id
      :else (unknown-object! object-id))

    (string? object-id)
    (or (d/entid db [:eacl/id object-id])
        (if allow-tempids?
          object-id
          (unknown-object! object-id)))

    (instance? datomic.db.DbId object-id)
    (if allow-tempids?
      object-id
      (unknown-object! object-id))

    :else
    (or (d/entid db object-id)
        (unknown-object! object-id))))

(defn- find-relation-eid
  [db resource-type relation-name subject-type]
  (d/q '[:find ?relation .
         :in $ ?resource-type ?relation-name ?subject-type
         :where
         [?relation :eacl.relation/resource-type ?resource-type]
         [?relation :eacl.relation/relation-name ?relation-name]
         [?relation :eacl.relation/subject-type ?subject-type]]
       db resource-type relation-name subject-type))

(defn- resolve-relationship
  [db {:keys [subject relation resource]} opts]
  (let [subject-type (:type subject)
        subject-eid  (object-id->eid-or-tempid db (:id subject) opts)
        resource-type (:type resource)
        resource-eid  (object-id->eid-or-tempid db (:id resource) opts)
        relation-eid  (find-relation-eid db resource-type relation subject-type)]
    (when-not relation-eid
      (throw
       (ex-info
        (str "Missing Relation: " relation
             " on resource type " resource-type
             " for subject type " subject-type ".")
        {:resource/type resource-type
         :relation/name relation
         :subject/type subject-type})))
    {:subject subject
     :subject-type subject-type
     :subject-eid subject-eid
     :relation relation
     :relation-eid relation-eid
     :resource resource
     :resource-type resource-type
     :resource-eid resource-eid}))

(defn- relationship-tuple
  [{:keys [subject-type relation-eid resource-type resource-eid]}]
  [subject-type relation-eid resource-type resource-eid])

(defn- reverse-relationship-tuple
  [{:keys [resource-type relation-eid subject-type subject-eid]}]
  [resource-type relation-eid subject-type subject-eid])

(defn- add-relationship-txes
  [resolved]
  [[:db/add (:subject-eid resolved)
    forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/add (:resource-eid resolved)
    reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn- retract-relationship-txes
  [resolved]
  [[:db/retract (:subject-eid resolved)
    forward-relationship-attr
    (relationship-tuple resolved)]
   [:db/retract (:resource-eid resolved)
    reverse-relationship-attr
    (reverse-relationship-tuple resolved)]])

(defn- relationship-exists?
  [db {:keys [subject-eid resource-eid] :as resolved}]
  (if (and (number? subject-eid) (number? resource-eid))
    (boolean
     (seq
      (d/datoms db
        :eavt
        subject-eid
        forward-relationship-attr
        (relationship-tuple resolved))))
    false))

(defn find-one-relationship-id
  "Returns the resolved tuple identity for an existing relationship, or nil.
  A read: unresolvable endpoints mean no such relationship can exist -> nil."
  [db relationship]
  (let [resolved (try
                   (resolve-relationship db relationship {})
                   (catch clojure.lang.ExceptionInfo e
                     (when-not (= :eacl/unknown-object (:type (ex-data e)))
                       (throw e))))]
    (when (and resolved (relationship-exists? db resolved))
      resolved)))

(defn- find-relations
  [db filters]
  (let [resource-type     (:resource/type filters)
        resource-relation (:resource/relation filters)
        subject-type      (:subject/type filters)]
    (->> (d/q '[:find [(pull ?relation [:db/id
                                        :eacl.relation/resource-type
                                        :eacl.relation/relation-name
                                        :eacl.relation/subject-type]) ...]
                :where
                [?relation :eacl.relation/relation-name ?relation-name]]
          db)
      (filter (fn [relation]
                (and (or (nil? resource-type)
                         (= resource-type (:eacl.relation/resource-type relation)))
                     (or (nil? resource-relation)
                         (= resource-relation (:eacl.relation/relation-name relation)))
                     (or (nil? subject-type)
                         (= subject-type (:eacl.relation/subject-type relation)))))))))

(defn- decode-forward-datom
  [db relation-by-eid subject-eid [_subject-type relation-eid resource-type resource-eid]]
  (let [relation-name (or (get relation-by-eid relation-eid)
                          (:eacl.relation/relation-name (d/entity db relation-eid)))
        subject-type  (:eacl.relation/subject-type (d/entity db relation-eid))]
    (eacl/->Relationship
     (spice-object subject-type subject-eid)
     relation-name
     (spice-object resource-type resource-eid))))

(defn- scan-subject-relationships
  [db relations subject-eid]
  (->> relations
       (mapcat
        (fn [{:db/keys [id]
              :eacl.relation/keys [resource-type relation-name subject-type]}]
          (->> (impl.indexed/subject->resources db subject-type subject-eid id resource-type nil)
               (map #(eacl/->Relationship
                      (spice-object subject-type subject-eid)
                      relation-name
                      (spice-object resource-type %))))))
       seq))

(defn- scan-resource-relationships
  [db relations resource-eid]
  (->> relations
       (mapcat
        (fn [{:db/keys [id]
              :eacl.relation/keys [resource-type relation-name subject-type]}]
          (->> (impl.indexed/resource->subjects db resource-type resource-eid id subject-type nil)
               (map #(eacl/->Relationship
                      (spice-object subject-type %)
                      relation-name
                      (spice-object resource-type resource-eid))))))
       seq))

(defn- scan-global-relationships
  [db relations]
  (let [relation-by-eid (into {}
                              (map (juxt :db/id :eacl.relation/relation-name))
                              relations)]
    (->> relations
      (mapcat
       (fn [{:db/keys [id]
             :eacl.relation/keys [resource-type subject-type]}]
            (let [start [subject-type id resource-type 0]
                  end   [subject-type id resource-type Long/MAX_VALUE]]
              (->> (d/index-range db forward-relationship-attr start end)
                   (map (fn [datom]
                          (decode-forward-datom db relation-by-eid (:e datom) (:v datom))))))))
      seq)))

(defn- relationship-matches-filters?
  [filters {:keys [subject relation resource]}]
  (and (or (nil? (:subject/type filters))
           (= (:subject/type filters) (:type subject)))
       (or (nil? (:subject/id filters))
           (= (:subject/id filters) (:id subject)))
       (or (nil? (:resource/type filters))
           (= (:resource/type filters) (:type resource)))
       (or (nil? (:resource/id filters))
           (= (:resource/id filters) (:id resource)))
       (or (nil? (:resource/relation filters))
           (= (:resource/relation filters) relation))))

(defn read-relationships
  [db filters]
  (let [relations    (find-relations db filters)
        subject-id    (:subject/id filters)
        resource-id   (:resource/id filters)
        subject-eid  (when subject-id (d/entid db subject-id))
        resource-eid (when resource-id (d/entid db resource-id))
        normalized-filters (cond-> filters
                             subject-eid (assoc :subject/id subject-eid)
                             resource-eid (assoc :resource/id resource-eid))]
    (->> (cond
           (and subject-id (nil? subject-eid))
           (throw (ex-info "read-relationships is missing a valid :subject/id."
                    {:subject/id subject-id}))

           (and resource-id (nil? resource-eid))
           (throw (ex-info "read-relationships is missing a valid :resource/id."
                    {:resource/id resource-id}))

           subject-eid
           (scan-subject-relationships db relations subject-eid)

           resource-eid
           (scan-resource-relationships db relations resource-eid)

           :else
           (scan-global-relationships db relations))
      (filter #(relationship-matches-filters? normalized-filters %)))))

(defn tx-relationship
  "Translate relationship data into v7 tuple writes.

  Strict by default: endpoints must resolve to existing entities or this
  throws :eacl/unknown-object. Pass {:allow-tempids? true} to let unresolvable
  string ids / tempids pass through for same-transaction entity+relationship
  creation (fixtures-style)."
  ([db subject relation resource]
   (tx-relationship db (eacl/->Relationship subject relation resource) {}))
  ([db relationship]
   (tx-relationship db relationship {}))
  ([db relationship opts]
   (add-relationship-txes (resolve-relationship db relationship opts))))

(defn tx-update-relationship
  "Relationship writes are implemented against v7 forward/reverse tuple indexes.
  :touch is idempotent. Endpoints must resolve to existing entities."
  [db {:keys [operation relationship]}]
  (let [resolved (resolve-relationship db relationship {})
        exists?  (relationship-exists? db resolved)]
    (case operation
      :touch
      (when-not exists?
        (add-relationship-txes resolved))

      :create
      (if exists?
        (throw (Exception. ":create relationship conflicts with existing tuple relationship"))
        (add-relationship-txes resolved))

      :delete
      (when exists?
        (retract-relationship-txes resolved))

      :unspecified
      (throw (Exception. ":unspecified relationship update not supported.")))))
