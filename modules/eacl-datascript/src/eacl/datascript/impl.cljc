(ns eacl.datascript.impl
  (:require [datascript.core :as ds]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.engine.indexed :as engine]
            [eacl.engine.relationships :as relationship-engine]
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

(defn all-relation-defs
  [db]
  (mapv (fn [{:keys [e v]}]
          {:relation-id e
           :resource-type (nth v 0)
           :relation-name (nth v 1)
           :subject-type (nth v 2)})
        (ds/datoms db :avet :eacl.relation/resource-type+relation-name+subject-type)))

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
  (let [subject-id'  (when (contains? filters :subject/id)
                       (internal-id db (:subject/id filters)))
        resource-id' (when (contains? filters :resource/id)
                       (internal-id db (:resource/id filters)))
        filters'     (cond-> filters
                       (contains? filters :subject/id) (assoc :subject/id subject-id')
                       (contains? filters :resource/id) (assoc :resource/id resource-id'))]
    (if (or (and (contains? filters :subject/id) (nil? subject-id'))
            (and (contains? filters :resource/id) (nil? resource-id')))
      {:data [] :cursor nil}
      (letfn [(relationship-row [spec subject-id resource-id]
            {:spec-idx    (:idx spec)
             :subject-id  subject-id
             :resource-id resource-id
             :relationship
             (eacl/->Relationship
              (spice-object (:subject-type spec) subject-id)
              (:relation-name spec)
              (spice-object (:resource-type spec) resource-id))})
          (drop-until-after-cursor [spec cursor rows]
            (drop-while #(not (relationship-engine/after-cursor? (:scan-kind spec) cursor %))
                        rows))
          (exact-match-row [spec cursor]
            (let [row (when (and (:subject-id spec) (:resource-id spec))
                        (when (ds/entid db [schema/relationship-full-key-attr
                                           [(:subject-type spec)
                                            (:subject-id spec)
                                            (:relation-id spec)
                                            (:resource-type spec)
                                            (:resource-id spec)]])
                          (relationship-row spec (:subject-id spec) (:resource-id spec))))]
              (if row
                (drop-until-after-cursor spec cursor [row])
                [])))
          (scan-forward-anchored [spec cursor]
            (if (:resource-id spec)
              (exact-match-row spec cursor)
              (->> (ds/index-range db
                                   schema/forward-relationship-attr
                                   [(:subject-type spec)
                                    (:subject-id spec)
                                    (:relation-id spec)
                                    (:resource-type spec)
                                    (or (:resource cursor) 0)]
                                   [(:subject-type spec)
                                    (:subject-id spec)
                                    (:relation-id spec)
                                    (:resource-type spec)
                                    max-entid])
                   (map (fn [{:keys [v]}]
                          (relationship-row spec (nth v 1) (nth v 4))))
                   (drop-until-after-cursor spec cursor))))
          (scan-reverse-anchored [spec cursor]
            (if (:subject-id spec)
              (exact-match-row spec cursor)
              (->> (ds/index-range db
                                   schema/reverse-relationship-attr
                                   [(:resource-type spec)
                                    (:resource-id spec)
                                    (:relation-id spec)
                                    (:subject-type spec)
                                    (or (:subject cursor) 0)]
                                   [(:resource-type spec)
                                    (:resource-id spec)
                                    (:relation-id spec)
                                    (:subject-type spec)
                                    max-entid])
                   (map (fn [{:keys [v]}]
                          (relationship-row spec (nth v 4) (nth v 1))))
                   (drop-until-after-cursor spec cursor))))
          (scan-forward-partial [spec cursor]
            (->> (ds/index-range db
                                 schema/forward-partial-relationship-attr
                                 [(:subject-type spec)
                                  (:relation-id spec)
                                  (:resource-type spec)
                                  (or (:resource cursor) 0)
                                  0]
                                 [(:subject-type spec)
                                  (:relation-id spec)
                                  (:resource-type spec)
                                  max-entid
                                  max-entid])
                 (map (fn [{:keys [v]}]
                        (relationship-row spec (nth v 4) (nth v 3))))
                 (drop-until-after-cursor spec cursor)))
          (scan-reverse-partial [spec cursor]
            (->> (ds/index-range db
                                 schema/reverse-partial-relationship-attr
                                 [(:resource-type spec)
                                  (:relation-id spec)
                                  (:subject-type spec)
                                  (or (:subject cursor) 0)
                                  0]
                                 [(:resource-type spec)
                                  (:relation-id spec)
                                  (:subject-type spec)
                                  max-entid
                                  max-entid])
                 (map (fn [{:keys [v]}]
                        (relationship-row spec (nth v 3) (nth v 4))))
                 (drop-until-after-cursor spec cursor)))
          (scan-spec [spec cursor]
            (case (:scan-kind spec)
              :forward-anchored (scan-forward-anchored spec cursor)
              :reverse-anchored (scan-reverse-anchored spec cursor)
              :forward-partial (scan-forward-partial spec cursor)
              (scan-reverse-partial spec cursor)))]
        (relationship-engine/execute-plan
         (relationship-engine/plan-scans (all-relation-defs db) filters')
         filters'
         scan-spec)))))

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
