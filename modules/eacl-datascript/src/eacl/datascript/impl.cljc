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

(def permission-def-pull
  '[:db/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name])

(defn evict-permission-paths-cache!
  ([] (evict-permission-paths-cache! permission-paths-cache))
  ([cache-atom]
   (engine/evict-permission-paths-cache! cache-atom)))

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
       (map #(ds/pull db permission-def-pull %))
       vec))

(defn- exclusive-start-id
  [cursor-id]
  (if cursor-id
    (inc cursor-id)
    0))

(defn- bounded-relationship-target-ids
  [db attr prefix cursor-id]
  (->> (ds/index-range db
                       attr
                       (conj prefix (exclusive-start-id cursor-id))
                       (conj prefix max-entid))
       (map (fn [{:keys [v]}] (peek v)))))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-resource-id]
  (bounded-relationship-target-ids db
                                   schema/forward-relationship-attr
                                   [subject-type subject-id relation-id resource-type]
                                   cursor-resource-id))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-subject-id]
  (bounded-relationship-target-ids db
                                   schema/reverse-relationship-attr
                                   [resource-type resource-id relation-id subject-type]
                                   cursor-subject-id))

(defn build-schema-catalog
  [db]
  {:relation-defs
   (reduce (fn [idx {:keys [e v]}]
             (let [[resource-type relation-name subject-type] v
                   relation-def {:relation-id e
                                 :resource-type resource-type
                                 :relation-name relation-name
                                 :subject-type subject-type}]
               (update idx [resource-type relation-name] (fnil conj []) relation-def)))
           {}
           (ds/datoms db :avet :eacl.relation/resource-type+relation-name+subject-type))
   :permission-defs
   (reduce (fn [idx {:keys [e]}]
             (let [perm (ds/pull db permission-def-pull e)]
               (update idx
                       [(:eacl.permission/resource-type perm)
                        (:eacl.permission/permission-name perm)]
                       (fnil conj [])
                       perm)))
           {}
           (ds/datoms db :avet :eacl.permission/resource-type+permission-name))})

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
  (let [subject-type      (:subject/type filters)
        subject-id        (:subject/id filters)
        resource-type     (:resource/type filters)
        resource-id       (:resource/id filters)
        resource-relation (:resource/relation filters)
        cursor            (:cursor filters)
        limit             (:limit filters)
        take-limit (fn [xs]
                     (if (and (number? limit) (<= 0 limit))
                       (take limit xs)
                       xs))
        forward-results
        (when (and subject-type subject-id resource-type resource-relation)
          (when-let [relation-eid (ds/entid db (relation-id resource-type resource-relation subject-type))]
            (->> (ds/index-range db
                                 schema/forward-relationship-attr
                                 [subject-type
                                  subject-id
                                  relation-eid
                                  resource-type
                                  (if cursor (inc cursor) 0)]
                                 [subject-type
                                  subject-id
                                  relation-eid
                                  resource-type
                                  max-entid])
                 take-limit
                 (map (fn [{:keys [v]}]
                        (let [[subject-type subject-id _ resource-type resource-id] v]
                          (eacl/->Relationship
                           (spice-object subject-type subject-id)
                           resource-relation
                           (spice-object resource-type resource-id))))))))
        reverse-results
        (when (and resource-type resource-id subject-type resource-relation)
          (when-let [relation-eid (ds/entid db (relation-id resource-type resource-relation subject-type))]
            (->> (ds/index-range db
                                 schema/reverse-relationship-attr
                                 [resource-type
                                  resource-id
                                  relation-eid
                                  subject-type
                                  (if cursor (inc cursor) 0)]
                                 [resource-type
                                  resource-id
                                  relation-eid
                                  subject-type
                                  max-entid])
                 take-limit
                 (map (fn [{:keys [v]}]
                        (let [[resource-type resource-id _ subject-type subject-id] v]
                          (eacl/->Relationship
                           (spice-object subject-type subject-id)
                           resource-relation
                           (spice-object resource-type resource-id))))))))]
    (or forward-results
        reverse-results
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
                                (= (:resource/relation filters) relation)))))
             take-limit))))

(defn- normalize-backend-options
  [cache-stamp-or-opts]
  (cond
    (nil? cache-stamp-or-opts) {}
    (fn? cache-stamp-or-opts) {:cache-stamp cache-stamp-or-opts}
    (map? cache-stamp-or-opts) cache-stamp-or-opts
    :else (throw (ex-info "Unsupported DataScript backend options"
                          {:value cache-stamp-or-opts}))))

(defn- schema-catalog-data
  [db schema-catalog]
  (cond
    (nil? schema-catalog) nil
    (fn? schema-catalog) (schema-catalog db)
    :else schema-catalog))

(defn- relation-defs-from-db
  [db resource-type relation-name]
  (mapv (fn [datom]
          {:relation-id (:e datom)
           :resource-type resource-type
           :relation-name relation-name
           :subject-type (nth (:v datom) 2)})
        (relation-datoms db resource-type relation-name)))

(defn- permission-defs-from-db
  [db resource-type permission-name]
  (mapv (fn [perm]
          {:permission-id (:db/id perm)
           :resource-type (:eacl.permission/resource-type perm)
           :permission-name (:eacl.permission/permission-name perm)
           :source-relation-name (:eacl.permission/source-relation-name perm)
           :target-type (:eacl.permission/target-type perm)
           :target-name (:eacl.permission/target-name perm)})
        (find-permission-defs db resource-type permission-name)))

(defn indexed-backend
  ([db]
   (indexed-backend db nil))
  ([db cache-stamp-or-opts]
   (let [{:keys [cache-stamp
                 schema-catalog]
          :as options} (normalize-backend-options cache-stamp-or-opts)
         permission-paths-cache-atom (:permission-paths-cache options)]
     {:cache-stamp (or cache-stamp
                       (fn []
                         (hash db)))
      :permission-paths-cache (or permission-paths-cache-atom permission-paths-cache)
      :relation-defs (fn [resource-type relation-name]
                       (if-let [catalog (schema-catalog-data db schema-catalog)]
                         (get-in catalog [:relation-defs [resource-type relation-name]] [])
                         (relation-defs-from-db db resource-type relation-name)))
      :permission-defs (fn [resource-type permission-name]
                         (if-let [catalog (schema-catalog-data db schema-catalog)]
                           (->> (get-in catalog [:permission-defs [resource-type permission-name]] [])
                                (mapv (fn [perm]
                                        {:permission-id (:db/id perm)
                                         :resource-type (:eacl.permission/resource-type perm)
                                         :permission-name (:eacl.permission/permission-name perm)
                                         :source-relation-name (:eacl.permission/source-relation-name perm)
                                         :target-type (:eacl.permission/target-type perm)
                                         :target-name (:eacl.permission/target-name perm)})))
                           (permission-defs-from-db db resource-type permission-name)))
    :subject->resources (fn [subject-type subject-id relation-id resource-type cursor-resource-id]
                          (subject->resources db subject-type subject-id relation-id resource-type cursor-resource-id))
    :resource->subjects (fn [resource-type resource-id relation-id subject-type cursor-subject-id]
                          (resource->subjects db resource-type resource-id relation-id subject-type cursor-subject-id))
    :direct-match? (fn [subject-type subject-id relation-id resource-type resource-id]
                     (boolean
                      (ds/entid db [schema/relationship-full-key-attr
                                    [subject-type subject-id relation-id resource-type resource-id]])))})))

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
  (let [backend    (backend* db-or-backend)
        cache-atom (or (:permission-paths-cache backend) permission-paths-cache)]
    (engine/get-permission-paths cache-atom calc-permission-paths backend resource-type permission-name)))

(defn can?
  ([db subject permission resource]
   (can? db nil subject permission resource))
  ([db cache-stamp-or-opts subject permission resource]
   (let [subject-id  (internal-id db (:id subject))
         resource-id (internal-id db (:id resource))]
     (engine/can?
      (indexed-backend db cache-stamp-or-opts)
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
  ([db cache-stamp-or-opts query]
   (engine/lookup
    (indexed-backend db cache-stamp-or-opts)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %)))))

(defn lookup-subjects
  ([db query]
   (lookup-subjects db nil query))
  ([db cache-stamp-or-opts query]
   (engine/lookup
    (indexed-backend db cache-stamp-or-opts)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %)))))

(defn count-resources
  ([db query]
   (count-resources db nil query))
  ([db cache-stamp-or-opts query]
   (engine/count-results
    (indexed-backend db cache-stamp-or-opts)
    engine/forward-direction
    get-permission-paths
    (update query :subject #(internalize-anchor db %))
    :resource)))

(defn count-subjects
  ([db query]
   (count-subjects db nil query))
  ([db cache-stamp-or-opts query]
   (engine/count-results
    (indexed-backend db cache-stamp-or-opts)
    engine/reverse-direction
    get-permission-paths
    (update query :resource #(internalize-anchor db %))
    :subject)))
