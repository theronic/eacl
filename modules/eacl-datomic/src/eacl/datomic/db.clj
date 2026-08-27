(ns eacl.datomic.db
  "Datomic-only entity, schema-definition, and ordered adjacency operations."
  (:require [datomic.api :as d]
            [eacl.datomic.io-stats :as io-stats]
            [eacl.relationships.storage :as relationship-storage]))

(defn object-eid
  [db object-id]
  (cond
    (nil? object-id) nil
    (string? object-id) (when (d/entid db :eacl/id)
                          (d/entid db [:eacl/id object-id]))
    :else (d/entid db object-id)))

(defn- scan-options
  [cursor-or-options]
  (if (and (map? cursor-or-options)
           (contains? cursor-or-options :direction))
    {:direction (:direction cursor-or-options)
     :bound-eid (:bound-eid cursor-or-options)
     :inclusive-bound? (boolean (:inclusive-bound? cursor-or-options))}
    {:direction :asc
     :bound-eid cursor-or-options
     :inclusive-bound? false}))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (scan-options cursor-or-options)
        attr-id (d/entid db relationship-storage/forward-attribute)
        prefix [subject-type relation-id resource-type]
        start-tuple (conj prefix
                          (case direction
                            :asc (or bound-eid 0)
                            :desc (or bound-eid Long/MAX_VALUE)))
        datoms (case direction
                 :asc (d/seek-datoms
                       db :eavt subject-id attr-id start-tuple)
                 :desc (d/rseek-datoms
                        db :eavt subject-id attr-id start-tuple))
        skip-bound? (and bound-eid (not inclusive-bound?))]
    (->> datoms
         (take-while
          (fn [datom]
            (let [value (:v datom)]
              (and (== subject-id (:e datom))
                   (== attr-id (:a datom))
                   (= subject-type (nth value 0))
                   (= relation-id (nth value 1))
                   (= resource-type (nth value 2))))))
         (drop-while
          #(and skip-bound? (= bound-eid (nth (:v %) 3))))
         (map #(nth (:v %) 3)))))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound?]}
        (scan-options cursor-or-options)
        attr-id (d/entid db relationship-storage/reverse-attribute)
        prefix [resource-type relation-id subject-type]
        start-tuple (conj prefix
                          (case direction
                            :asc (or bound-eid 0)
                            :desc (or bound-eid Long/MAX_VALUE)))
        datoms (case direction
                 :asc (d/seek-datoms
                       db :eavt resource-id attr-id start-tuple)
                 :desc (d/rseek-datoms
                        db :eavt resource-id attr-id start-tuple))
        skip-bound? (and bound-eid (not inclusive-bound?))]
    (->> datoms
         (take-while
          (fn [datom]
            (let [value (:v datom)]
              (and (== resource-id (:e datom))
                   (== attr-id (:a datom))
                   (= resource-type (nth value 0))
                   (= relation-id (nth value 1))
                   (= subject-type (nth value 2))))))
         (drop-while
          #(and skip-bound? (= bound-eid (nth (:v %) 3))))
         (map #(nth (:v %) 3)))))

(defn relation-datoms
  [db resource-type relation-name]
  (if (and resource-type relation-name)
    (let [attribute
          :eacl.relation/resource-type+relation-name+subject-type
          attribute-id (d/entid db attribute)]
      (->> (d/seek-datoms db :avet attribute
                          [resource-type relation-name])
           (take-while
            (fn [datom]
              (and (= attribute-id (:a datom))
                   (= resource-type (nth (:v datom) 0))
                   (= relation-name (nth (:v datom) 1)))))))
    []))

(defn find-relation-def
  [db resource-type relation-name]
  (when-let [datom (first (relation-datoms
                           db resource-type relation-name))]
    (io-stats/pull db
                   '[:db/id
                     :eacl.relation/subject-type
                     :eacl.relation/resource-type
                     :eacl.relation/relation-name]
                   (:e datom)
                   :relation-definition-pull)))

(defn find-permission-defs
  [db resource-type permission-name]
  (->> (d/datoms
        db :avet
        :eacl.permission/resource-type+permission-name
        [resource-type permission-name])
       (map :e)
       (map #(dissoc
              (io-stats/pull db '[*] % :permission-expression-pull)
              ;; Released flat-permission schemas installed these derived
              ;; tuple attributes permanently. After expression migration,
              ;; Datomic materializes nil-filled projections from the shared
              ;; resource/permission fields. Omit only those physical
              ;; projections; genuine mixed rows retain their scalar legacy
              ;; source/target fields and still fail closed in the shared
              ;; expression decoder.
              :eacl.permission/resource-type+source-relation-name+target-type+permission-name
              :eacl.permission/resource-type+source-relation-name+target-type+target-name
              :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name))
       vec))

(defn all-permission-nodes
  [db]
  (->> (d/q '[:find ?resource-type ?permission-name
              :where
              [?permission :eacl.permission/resource-type ?resource-type]
              [?permission :eacl.permission/permission-name ?permission-name]]
            db)
       (mapv vec)
       set))

(defn direct-match?
  [db subject-type subject-id relation-id resource-type resource-id]
  (boolean
   (seq
    (d/datoms
     db :eavt subject-id relationship-storage/forward-attribute
     [subject-type relation-id resource-type resource-id]))))

(defn schema-version
  [db]
  (when (d/entid db :eacl/id)
    (some-> (d/entity db [:eacl/id "schema-string"])
            :eacl/schema-version)))
