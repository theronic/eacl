(ns eacl.datahike.backend
  "Datahike storage operations for the shared v8 authorization engine."
  (:require [datahike.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as impl]
            [eacl.datahike.schema :as schema]))

(def capabilities
  {:consistency #{:fully-consistent}
   :snapshots #{:current}
   :cursor #{:forward :reverse :opaque}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:schema :relations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(defn- normalized-permission
  [permission]
  {:permission-id (:db/id permission)
   :resource-type (:eacl.permission/resource-type permission)
   :permission-name (:eacl.permission/permission-name permission)
   :source-relation-name
   (:eacl.permission/source-relation-name permission)
   :target-type (:eacl.permission/target-type permission)
   :target-name (:eacl.permission/target-name permission)})

(defn- apply-scan-window
  [ids {:keys [direction bound-eid inclusive-bound?]}]
  (let [direction (or direction :asc)
        ordered (sort ids)
        within-bound?
        (case direction
          :asc (if bound-eid
                 (if inclusive-bound?
                   #(<= bound-eid %)
                   #(< bound-eid %))
                 (constantly true))
          :desc (if bound-eid
                  (if inclusive-bound?
                    #(>= bound-eid %)
                    #(> bound-eid %))
                  (constantly true)))]
    (cond->> (filter within-bound? ordered)
      (= :desc direction) reverse)))

(defn- schema-proof
  [db {:keys [permission-nodes relation-ids] :as scope}]
  (let [{:keys [relation-defs permission-defs]}
        (impl/build-schema-catalog db)
        relation-ids (set relation-ids)
        scoped-relations
        (cond->> (mapcat identity (vals relation-defs))
          scope (filter #(contains? relation-ids (:relation-id %))))
        scoped-permissions
        (if scope
          (mapcat #(get permission-defs % []) permission-nodes)
          (mapcat identity (vals permission-defs)))]
    {:relations
     (->> scoped-relations
          (sort-by (juxt :resource-type :relation-name :subject-type))
          vec)
     :permissions
     (->> scoped-permissions
          (map normalized-permission)
          (sort-by (juxt :resource-type
                         :permission-name
                         :source-relation-name
                         :target-type
                         :target-name))
          vec)}))

(defn- relation-proof
  [db relation-ids]
  (let [wanted (set relation-ids)]
    (->> (d/q '[:find ?relation ?subject-type ?subject
                 ?resource-type ?resource
                 :where
                 [?relationship :eacl.relationship/relation ?relation]
                 [?relationship :eacl.relationship/subject-type ?subject-type]
                 [?relationship :eacl.relationship/subject ?subject]
                 [?relationship :eacl.relationship/resource-type ?resource-type]
                 [?relationship :eacl.relationship/resource ?resource]]
               db)
         (filter #(contains? wanted (nth % 0)))
         sort
         vec)))

(defn snapshot-adapter
  "Creates a v8 adapter bound to one immutable Datahike db value."
  [db {:keys [object-id->entid entid->object-id]}]
  (backend/make-adapter
   {:id :datahike
    :capabilities capabilities
    :state {:db db}
    :operations
    {:snapshot-id
     (fn []
       {:database-id (select-keys (:config db)
                                  [:store :attribute-refs?])
        :basis-t (:max-tx db)})

     :object-id->internal
     (fn [object-id]
       (if (number? object-id)
         object-id
         (object-id->entid db object-id)))

     :internal-id->object
     (fn [internal-id]
       (entid->object-id db internal-id))

     :relation-defs
     (fn [resource-type relation-name]
       (mapv (fn [{:keys [e v]}]
               {:relation-id e
                :resource-type resource-type
                :relation-name relation-name
                :subject-type (nth v 2)})
             (impl/relation-datoms db resource-type relation-name)))

     :permission-defs
     (fn [resource-type permission-name]
       (mapv normalized-permission
             (impl/find-permission-defs
              db resource-type permission-name)))

     :subject->resources
     (fn [subject-type subject-id relation-id resource-type options]
       (apply-scan-window
        (impl/subject->resources
         db subject-type subject-id relation-id resource-type nil)
        options))

     :resource->subjects
     (fn [resource-type resource-id relation-id subject-type options]
       (apply-scan-window
        (impl/resource->subjects
         db resource-type resource-id relation-id subject-type nil)
        options))

     :direct-match?
     (fn [subject-type subject-id relation-id resource-type resource-id]
       (boolean
        (ddb/entid
         db
         [schema/relationship-full-key-attr
          [subject-type subject-id relation-id resource-type resource-id]])))

     :all-permission-nodes
     (fn []
       (->> (ddb/avet-datoms db schema/permission-key-attr)
            (map :v)
            set))

     :frontier-key pr-str

     :schema-proof
     (fn
       ([] (schema-proof db nil))
       ([scope] (schema-proof db scope)))

     :relation-proof
     (fn [relation-ids]
       (relation-proof db relation-ids))}}))
