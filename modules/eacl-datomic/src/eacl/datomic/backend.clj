(ns eacl.datomic.backend
  "Datomic's storage-specific implementation of the shared v8 snapshot
  adapter. Authorization graph algorithms remain outside this namespace."
  (:require [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.db :as ddb]
            [eacl.datomic.watermark :as watermark])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util Base64]))

(def capabilities
  {:consistency #{:fully-consistent
                  :minimize-latency
                  :at-least-as-fresh
                  :at-exact-snapshot}
   :snapshots #{:current :historical}
   :cursor #{:forward :reverse :opaque :authenticated :encrypted}
   :transactions #{:schema :relationships :object-deletion}
   :cache-proofs #{:schema :relations :snapshot-bound :database-visible}
   :runtime #{:clj}})

(defn- relation-defs
  [db resource-type relation-name]
  (mapv (fn [datom]
          {:relation-id (:e datom)
           :resource-type resource-type
           :relation-name relation-name
           :subject-type (nth (:v datom) 2)})
        (ddb/relation-datoms db resource-type relation-name)))

(defn- permission-defs
  [db resource-type permission-name]
  (->> (ddb/find-permission-defs
        db resource-type permission-name)
       (mapv
        (fn [permission]
          {:permission-id (:db/id permission)
           :resource-type (:eacl.permission/resource-type permission)
           :permission-name (:eacl.permission/permission-name permission)
           :source-relation-name
           (:eacl.permission/source-relation-name permission)
           :target-type (:eacl.permission/target-type permission)
           :target-name (:eacl.permission/target-name permission)}))))

(defn- schema-proof
  [db {:keys [permission-nodes relation-ids] :as scope}]
  (if-not scope
    (some-> (ddb/schema-version db) str)
    {:relations
     (->> relation-ids
          (map (fn [relation-id]
                 (let [relation (d/entity db relation-id)]
                   {:relation-id relation-id
                    :resource-type
                    (:eacl.relation/resource-type relation)
                    :relation-name
                    (:eacl.relation/relation-name relation)
                    :subject-type
                    (:eacl.relation/subject-type relation)})))
          (sort-by (juxt :resource-type :relation-name :subject-type))
          vec)
     :permissions
     (->> permission-nodes
          (mapcat (fn [[resource-type permission-name]]
                    (permission-defs
                     db resource-type permission-name)))
          (sort-by (juxt :resource-type
                         :permission-name
                         :source-relation-name
                         :target-type
                         :target-name))
          vec)}))

(defn snapshot-adapter
  "Creates an adapter bound to one immutable Datomic db value. Proof and scan
  operations therefore cannot accidentally observe a different basis."
  ([db]
   (snapshot-adapter db {}))
  ([db {:keys [entid->object-id cache-epoch-state
               object-eid-fn subject->resources-fn
               resource->subjects-fn]
        :as opts}]
   (let [epoch-state (or cache-epoch-state
                         (watermark/make-epoch-state))
         external-id (or entid->object-id
                         (fn [snapshot eid]
                           (:eacl/id (d/entity snapshot eid))))]
     (backend/make-adapter
      {:id :datomic
       :capabilities capabilities
       :state {:db db
               :opts opts}
       :operations
       {:snapshot-id
        (fn []
          {:database-id (str (.id ^datomic.Database db))
           :basis-t (d/basis-t db)})

        :object-id->internal
        (fn [object-id]
          ((or object-eid-fn ddb/object-eid) db object-id))

        :internal-id->object
        (fn [internal-id]
          (external-id db internal-id))

        :relation-defs
        (fn [resource-type relation-name]
          (relation-defs db resource-type relation-name))

        :permission-defs
        (fn [resource-type permission-name]
          (permission-defs db resource-type permission-name))

        :subject->resources
        (fn [subject-type subject-id relation-id resource-type scan-options]
          ((or subject->resources-fn ddb/subject->resources)
           db subject-type subject-id relation-id resource-type scan-options))

        :resource->subjects
        (fn [resource-type resource-id relation-id subject-type scan-options]
          ((or resource->subjects-fn ddb/resource->subjects)
           db resource-type resource-id relation-id subject-type scan-options))

        :direct-match?
        (fn [subject-type subject-id relation-id resource-type resource-id]
          (ddb/direct-match?
           db subject-type subject-id relation-id resource-type resource-id))

        :all-permission-nodes
        (fn []
          (ddb/all-permission-nodes db))

        :frontier-key
        (fn [identity]
          (let [bytes (.getBytes (pr-str identity)
                                 StandardCharsets/UTF_8)
                digest (.digest
                        (MessageDigest/getInstance "SHA-256")
                        bytes)]
            (.encodeToString
             (.withoutPadding (Base64/getUrlEncoder))
             digest)))

        :schema-proof
        (fn
          ([] (schema-proof db nil))
          ([scope] (schema-proof db scope)))

        :relation-proof
        (fn [relation-ids]
          (watermark/safe-epoch-for epoch-state db relation-ids))}}))))
