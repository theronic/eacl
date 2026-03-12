(ns eacl.datomic.impl.indexed
  (:require [clojure.tools.logging :as log]
            [datomic.api :as d]
            [eacl.backend.spi :as spi]
            [eacl.core :refer [spice-object]]
            [eacl.engine.indexed :as engine]))

(def ^:private forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def ^:private reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(defn subject->resources
  [db subject-type subject-eid relation-eid resource-type cursor-resource-eid]
  {:pre [subject-type subject-eid relation-eid resource-type]}
  (let [attr-eid    (d/entid db forward-relationship-attr)
        start-tuple [subject-type
                     relation-eid
                     resource-type
                     (if cursor-resource-eid (inc cursor-resource-eid) 0)]]
    (->> (d/seek-datoms db :eavt subject-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== subject-eid e)
                 (== attr-eid a)
                 (= [subject-type relation-eid resource-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn resource->subjects
  [db resource-type resource-eid relation-eid subject-type cursor-subject-eid]
  {:pre [resource-type resource-eid relation-eid subject-type]}
  (let [attr-eid    (d/entid db reverse-relationship-attr)
        start-tuple [resource-type
                     relation-eid
                     subject-type
                     (if cursor-subject-eid (inc cursor-subject-eid) 0)]]
    (->> (d/seek-datoms db :eavt resource-eid attr-eid start-tuple)
         (take-while
          (fn [[e a v]]
            (and (== resource-eid e)
                 (== attr-eid a)
                 (= [resource-type relation-eid subject-type]
                    (subvec (vec v) 0 3)))))
         (map (fn [[_ _ v]] (nth v 3))))))

(defn relation-datoms
  "Returns relation datoms for the exact resource/relation name pair."
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [start-tuple [resource-type relation-name :a]
          end-tuple   [resource-type relation-name :z]]
      (d/index-range db
                     :eacl.relation/resource-type+relation-name+subject-type
                     start-tuple
                     end-tuple))
    []))

(defn find-relation-def
  "Compatibility helper retained for tests.
  Returns the first matching relation definition, if any."
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms db resource-type relation-name))]
    (d/pull db
            '[:db/id
              :eacl.relation/subject-type
              :eacl.relation/resource-type
              :eacl.relation/relation-name]
            (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (let [tuple-val [resource-type permission-name]]
    (->> (d/datoms db :avet :eacl.permission/resource-type+permission-name tuple-val)
         (map :e)
         (map #(d/pull db '[*] %))
         vec)))

(def permission-paths-cache
  (atom {}))

(defn evict-permission-paths-cache! []
  (engine/evict-permission-paths-cache! permission-paths-cache))

(defn direct-match-datoms-in-relationship-index
  [db subject-type subject-eid relation-eid resource-type resource-eid]
  (d/datoms db
            :eavt
            subject-eid
            forward-relationship-attr
            [subject-type relation-eid resource-type resource-eid]))

(defn indexed-backend
  [db]
  {:cache-stamp (fn []
                  (System/identityHashCode db))
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
                     (seq
                      (direct-match-datoms-in-relationship-index db
                                                                 subject-type
                                                                 subject-id
                                                                 relation-id
                                                                 resource-type
                                                                 resource-id))))})

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

(defn traverse-permission-path
  ([db subject-type subject-eid permission-name resource-type cursor-eid]
   (traverse-permission-path db subject-type subject-eid permission-name resource-type cursor-eid #{}))
  ([db subject-type subject-eid permission-name resource-type cursor-eid visited-paths]
   (engine/traverse-permission-path
    (indexed-backend db)
    get-permission-paths
    subject-type
    subject-eid
    permission-name
    resource-type
    cursor-eid
    visited-paths)))

(defn- internal-id
  [db value]
  (when value
    (d/entid db value)))

(defn can?
  [db subject permission resource]
  (let [subject-id  (internal-id db (:id subject))
        resource-id (internal-id db (:id resource))]
    (engine/can?
     (indexed-backend db)
     get-permission-paths
     (assoc subject :id subject-id)
     permission
     (assoc resource :id resource-id))))

(defn- internalize-anchor
  [db object]
  (update object :id #(internal-id db %)))

(defn lookup-resources
  [db query]
  (engine/lookup
   (indexed-backend db)
   engine/forward-direction
   get-permission-paths
   (update query :subject #(internalize-anchor db %))))

(defn lookup-subjects
  [db query]
  {:pre [(:type (:resource query)) (:id (:resource query))]}
  (engine/lookup
   (indexed-backend db)
   engine/reverse-direction
   get-permission-paths
   (update query :resource #(internalize-anchor db %))))

(defn count-resources
  [db query]
  (engine/count-results
   (indexed-backend db)
   engine/forward-direction
   get-permission-paths
   (update query :subject #(internalize-anchor db %))
   :resource))

(defn count-subjects
  [db query]
  (engine/count-results
   (indexed-backend db)
   engine/reverse-direction
   get-permission-paths
   (update query :resource #(internalize-anchor db %))
   :subject))
