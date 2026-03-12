(ns eacl.datascript.impl
  (:require [datascript.core :as ds]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.engine.indexed :as engine]
            [eacl.schema.model :as model]
            [eacl.datascript.schema :as schema]))

(def Relation model/Relation)
(def Permission model/Permission)

(defn Relationship
  [subject relation resource]
  (eacl/->Relationship subject relation resource))

(def permission-paths-cache
  (atom {}))

(def max-entid
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_SAFE_INTEGER))

(defn evict-permission-paths-cache! []
  (engine/evict-permission-paths-cache! permission-paths-cache))

(defn relation-datoms
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (ds/index-range db
                    :eacl.relation/resource-type+relation-name+subject-type
                    [resource-type relation-name :a]
                    [resource-type relation-name :z])
    []))

(defn find-relation-def
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms db resource-type relation-name))]
    (ds/pull db
             '[:db/id
               :eacl.relation/subject-type
               :eacl.relation/resource-type
               :eacl.relation/relation-name]
             (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (->> (ds/datoms db :avet :eacl.permission/resource-type+permission-name [resource-type permission-name])
       (map :e)
       (map #(ds/pull db '[*] %))
       vec))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-resource-id]
  (->> (ds/index-range db
                       schema/forward-relationship-attr
                       [subject-type subject-id relation-id resource-type (if cursor-resource-id (inc cursor-resource-id) 0)]
                       [subject-type subject-id relation-id resource-type max-entid])
       (map (fn [datom] (nth (:v datom) 4)))))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-subject-id]
  (->> (ds/index-range db
                       schema/reverse-relationship-attr
                       [resource-type resource-id relation-id subject-type (if cursor-subject-id (inc cursor-subject-id) 0)]
                       [resource-type resource-id relation-id subject-type max-entid])
       (map (fn [datom] (nth (:v datom) 4)))))

(defn- relationship-tuple
  [{:keys [subject-type subject-id relation-id resource-type resource-id]}]
  [subject-type subject-id relation-id resource-type resource-id])

(defn- relationship-reverse-tuple
  [{:keys [resource-type resource-id relation-id subject-type subject-id]}]
  [resource-type resource-id relation-id subject-type subject-id])

(defn- internal-id
  [db value]
  (when value
    (ds/entid db value)))

(defn- relation-id
  [resource-type relation-name subject-type]
  [:eacl/id (model/->relation-id resource-type relation-name subject-type)])

(defn- resolve-relationship
  [db {:keys [subject relation resource]}]
  (let [subject-type  (:type subject)
        subject-id    (internal-id db (:id subject))
        resource-type (:type resource)
        resource-id   (internal-id db (:id resource))
        relation-eid  (ds/entid db (relation-id resource-type relation subject-type))]
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
     :subject-id subject-id
     :relation relation
     :relation-id relation-eid
     :resource resource
     :resource-type resource-type
     :resource-id resource-id}))

(defn find-one-relationship-id
  [db relationship]
  (let [resolved (resolve-relationship db relationship)
        existing (ds/entid db [schema/relationship-full-key-attr
                               (relationship-tuple resolved)])]
    (when existing
      resolved)))

(defn tx-update-relationship
  [db {:keys [operation relationship]}]
  (let [resolved  (resolve-relationship db relationship)
        tuple     (relationship-tuple resolved)
        existing  (ds/entid db [schema/relationship-full-key-attr tuple])]
    (case operation
      :touch
      (when-not existing
        [{:eacl.relationship/subject-type (:subject-type resolved)
          :eacl.relationship/subject      (:subject-id resolved)
          :eacl.relationship/relation     (:relation-id resolved)
          :eacl.relationship/resource-type (:resource-type resolved)
          :eacl.relationship/resource     (:resource-id resolved)}])

      :create
      (if existing
        (throw (ex-info ":create relationship conflicts with existing tuple relationship"
                 {:relationship relationship}))
        [{:eacl.relationship/subject-type (:subject-type resolved)
          :eacl.relationship/subject      (:subject-id resolved)
          :eacl.relationship/relation     (:relation-id resolved)
          :eacl.relationship/resource-type (:resource-type resolved)
          :eacl.relationship/resource     (:resource-id resolved)}])

      :delete
      (when existing
        [[:db/retractEntity existing]])

      :unspecified
      [{:eacl.relationship/subject-type (:subject-type resolved)
        :eacl.relationship/subject      (:subject-id resolved)
        :eacl.relationship/relation     (:relation-id resolved)
        :eacl.relationship/resource-type (:resource-type resolved)
        :eacl.relationship/resource     (:resource-id resolved)}]

      (throw (ex-info "Unknown relationship operation"
                      {:operation operation})))))

(defn read-relationships
  [db filters]
  (->> (ds/datoms db :avet schema/forward-relationship-attr)
       (map (fn [{:keys [v]}]
              (let [[subject-type subject-id relation-id resource-type resource-id] v
                    rel-ent (ds/entity db relation-id)]
                (eacl/->Relationship
                 (spice-object subject-type subject-id)
                 (:eacl.relation/relation-name rel-ent)
                 (spice-object resource-type resource-id)))))
       (filter (fn [{:keys [subject relation resource]}]
                 (and (or (nil? (:subject/type filters))
                          (= (:subject/type filters) (:type subject)))
                      (or (nil? (:subject/id filters))
                          (= (:subject/id filters) (:id subject)))
                      (or (nil? (:resource/type filters))
                          (= (:resource/type filters) (:type resource)))
                      (or (nil? (:resource/id filters))
                          (= (:resource/id filters) (:id resource)))
                      (or (nil? (:resource/relation filters))
                          (= (:resource/relation filters) relation)))))))

(defn indexed-backend
  ([db]
   (indexed-backend db nil))
  ([db cache-stamp-fn]
   {:cache-stamp (or cache-stamp-fn
                     (fn []
                       (hash db)))
    :relation-defs (fn [resource-type relation-name]
                     (mapv (fn [datom]
                             {:relation-id (:e datom)
                              :resource-type resource-type
                              :relation-name relation-name
                              :subject-type (nth (:v datom) 2)})
                           (relation-datoms db resource-type relation-name)))
    :permission-defs (fn [resource-type permission-name]
                       (mapv (fn [perm]
                               {:permission-id (:db/id perm)
                                :resource-type (:eacl.permission/resource-type perm)
                                :permission-name (:eacl.permission/permission-name perm)
                                :source-relation-name (:eacl.permission/source-relation-name perm)
                                :target-type (:eacl.permission/target-type perm)
                                :target-name (:eacl.permission/target-name perm)})
                             (find-permission-defs db resource-type permission-name)))
    :subject->resources (fn [subject-type subject-id relation-id resource-type cursor-resource-id]
                          (subject->resources db subject-type subject-id relation-id resource-type cursor-resource-id))
    :resource->subjects (fn [resource-type resource-id relation-id subject-type cursor-subject-id]
                          (resource->subjects db resource-type resource-id relation-id subject-type cursor-subject-id))
    :direct-match? (fn [subject-type subject-id relation-id resource-type resource-id]
                     (boolean
                      (ds/entid db [schema/relationship-full-key-attr
                                    [subject-type subject-id relation-id resource-type resource-id]])))}))

(defn- backend*
  [db-or-backend]
  (if (and (map? db-or-backend) (contains? db-or-backend :cache-stamp))
    db-or-backend
    (indexed-backend db-or-backend)))

(defn calc-permission-paths
  ([db-or-backend resource-type permission-name]
   (engine/calc-permission-paths (backend* db-or-backend) resource-type permission-name))
  ([db-or-backend resource-type permission-name visited-perms]
   (engine/calc-permission-paths (backend* db-or-backend) resource-type permission-name visited-perms)))

(defn get-permission-paths
  [db-or-backend resource-type permission-name]
  (let [backend (backend* db-or-backend)]
    (engine/get-permission-paths permission-paths-cache calc-permission-paths backend resource-type permission-name)))

(defn can?
  ([db subject permission resource]
   (can? db nil subject permission resource))
  ([db cache-stamp-fn subject permission resource]
   (let [subject-id  (internal-id db (:id subject))
         resource-id (internal-id db (:id resource))]
     (engine/can?
      (indexed-backend db cache-stamp-fn)
      get-permission-paths
      (assoc subject :id subject-id)
      permission
      (assoc resource :id resource-id)))))

(defn- internalize-anchor
  [db object]
  (update object :id #(internal-id db %)))

(defn lookup-resources
  ([db query]
   (lookup-resources db nil query))
  ([db cache-stamp-fn query]
   (engine/lookup
    (indexed-backend db cache-stamp-fn)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %)))))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db nil query))
  ([db cache-stamp-fn query]
   (engine/lookup
    (indexed-backend db cache-stamp-fn)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %)))))

(defn count-resources
  ([db query]
   (count-resources db nil query))
  ([db cache-stamp-fn query]
   (engine/count-results
    (indexed-backend db cache-stamp-fn)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %))
    :resource)))

(defn count-subjects
  ([db query]
   (count-subjects db nil query))
  ([db cache-stamp-fn query]
   (engine/count-results
    (indexed-backend db cache-stamp-fn)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %))
    :subject)))
