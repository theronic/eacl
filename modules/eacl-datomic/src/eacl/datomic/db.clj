(ns eacl.datomic.db
  "Datomic-only entity, schema-definition, and ordered adjacency operations."
  (:require [datomic.api :as d]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.endpoint-pair :as endpoint-pair]))

(defn relationship-identity-datoms
  "One owner/attribute/identity seek, retaining exact qualifier variants."
  [db entity attr value]
  (let [attribute-eid (d/entid db attr)
        prefix (endpoint-pair/identity-prefix value)]
    (take-while #(and (= entity (:e %)) (= attribute-eid (:a %))
                     (endpoint-pair/value-prefix? (:v %) prefix))
                (d/seek-datoms db :eavt entity attribute-eid (conj prefix nil)))))

(defn global-relationship-identity-datoms
  [db attr value]
  (let [attribute-eid (d/entid db attr)
        prefix (endpoint-pair/identity-prefix value)]
    (take-while #(and (= attribute-eid (:a %))
                     (endpoint-pair/value-prefix? (:v %) prefix))
                (d/seek-datoms db :avet attribute-eid (conj prefix nil)))))

(defn object-eid
  [db object-id]
  (cond
    (nil? object-id) nil
    (string? object-id) (when (d/entid db :eacl/id)
                          (d/entid db [:eacl/id object-id]))
    :else (d/entid db object-id)))

(defn- endpoint-scan
  "Ordered endpoint values for one three-component prefix on one endpoint
  entity, strictly after an exclusive bound or from an inclusive one. Lazy:
  the routed read seam realizes exactly the chunk it asked for."
  [db endpoint-id attr prefix cursor-or-options]
  (let [{:keys [direction bound-eid inclusive-bound? include-qualifier?]}
        (relationship-storage/normalize-scan-options cursor-or-options)
        attr-id (d/entid db attr)
        [p0 p1 p2] prefix
        start-tuple (endpoint-pair/seek-bound prefix bound-eid direction Long/MAX_VALUE)
        datoms (case direction
                 :asc (d/seek-datoms
                       db :eavt endpoint-id attr-id start-tuple)
                 :desc (d/rseek-datoms
                        db :eavt endpoint-id attr-id start-tuple))
        skip-bound? (and bound-eid (not inclusive-bound?))]
    (cond->> datoms
      true (take-while
            (fn [datom]
              (let [value (:v datom)]
                (and (== endpoint-id (:e datom))
                     (== attr-id (:a datom))
                     (= p0 (nth value 0))
                     (= p1 (nth value 1))
                     (= p2 (nth value 2))))))
      true (#(endpoint-pair/checked-datoms % include-qualifier?))
      skip-bound? (drop-while #(= bound-eid (nth (:v %) 3)))
      true (map (if include-qualifier? edge/from-datom #(nth (:v %) 3))))))

(defn subject->resources
  [db subject-type subject-id relation-id resource-type cursor-or-options]
  (endpoint-scan db subject-id relationship-storage/forward-attribute
                 [subject-type relation-id resource-type] cursor-or-options))

(defn resource->subjects
  [db resource-type resource-id relation-id subject-type cursor-or-options]
  (endpoint-scan db resource-id relationship-storage/reverse-attribute
                 [resource-type relation-id subject-type] cursor-or-options))

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
    (d/pull db
            '[:db/id
              :eacl.relation/subject-type
              :eacl.relation/resource-type
              :eacl.relation/relation-name]
            (:e datom))))

(defn find-permission-defs
  [db resource-type permission-name]
  (->> (d/datoms
        db :avet
        :eacl.permission/resource-type+permission-name
        [resource-type permission-name])
       (map :e)
       (map #(dissoc
              (d/pull db '[*] %)
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
    (endpoint-pair/checked-datoms
     (relationship-identity-datoms
      db subject-id relationship-storage/forward-attribute
      (endpoint-pair/forward-value subject-type relation-id resource-type resource-id))))))

(defn direct-edge
  "Stored compact edge or nil, prior to request qualification."
  [db subject-type subject-id relation-id resource-type resource-id]
  (some-> (first (endpoint-pair/checked-datoms
                 (relationship-identity-datoms
                  db subject-id relationship-storage/forward-attribute
                  (endpoint-pair/forward-value subject-type relation-id resource-type resource-id))
                 true))
          edge/from-datom))

(defn schema-version
  [db]
  (when (d/entid db :eacl/id)
    (some-> (d/entity db [:eacl/id "schema-string"])
            :eacl/schema-version)))
