(ns eacl.datascript.schema
  (:require [datascript.core :as ds]
            [eacl.datascript.mutation :as journal]
            [eacl.mutation :as mutation]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def forward-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource)

(def reverse-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject)

(def max-entid
  #?(:clj Long/MAX_VALUE
     :cljs js/Number.MAX_SAFE_INTEGER))

(def schema-change-attrs
  #{:eacl.relation/resource-type
    :eacl.relation/relation-name
    :eacl.relation/subject-type
    :eacl.relation/resource-type+relation-name+subject-type
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name
    :eacl.permission/resource-type+permission-name
    :eacl.permission/full-key
    :eacl/schema-string})

(def datascript-schema
  {:eacl/id {:db/unique :db.unique/identity}
   :eacl.relation/resource-type {:db/index true}
   :eacl.relation/relation-name {:db/index true}
   :eacl.relation/subject-type {:db/index true}
   :eacl.relation/resource-type+relation-name+subject-type
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relation/resource-type
                    :eacl.relation/relation-name
                    :eacl.relation/subject-type]
    :db/unique :db.unique/identity}

   :eacl.permission/resource-type {:db/index true}
   :eacl.permission/permission-name {:db/index true}
   :eacl.permission/source-relation-name {:db/index true}
   :eacl.permission/target-type {:db/index true}
   :eacl.permission/target-name {:db/index true}
   :eacl.permission/resource-type+permission-name
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/permission-name]
    :db/index true}
   :eacl.permission/full-key
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/unique :db.unique/identity}

   :eacl.mutation/id
   {:db/unique :db.unique/identity
    :db/index true}
   :eacl.mutation/fingerprint {:db/index true}
   :eacl.mutation/kind {:db/index true}
   :eacl.mutation/issued-at {:db/index true}
   :eacl.mutation/expires-at {:db/index true}
   :eacl.graph/family-id {:db/index true}
   :eacl.graph/head-id {:db/index true}
   :eacl.graph/head-order {:db/valueType :db.type/ref}
   :eacl.schema/mutation-id {:db/index true}
   :eacl.relation/mutation-id {:db/index true}
   :eacl.dependency/mutation-id {:db/index true}

   :eacl.v7.relationship/subject-type+relation+resource-type+resource
   {:db/cardinality :db.cardinality/many
    :db/index true}
   :eacl.v7.relationship/resource-type+relation+subject-type+subject
   {:db/cardinality :db.cardinality/many
    :db/index true}})

(defn merge-schema
  ([] datascript-schema)
  ([extra-schema]
   (merge datascript-schema extra-schema)))

(defn create-conn
  ([] (create-conn nil))
  ([extra-schema]
   (ds/create-conn (merge-schema extra-schema))))

(defn read-relations
  [db]
  (ds/q '[:find [(pull ?relation [:eacl/id
                                  :eacl.relation/subject-type
                                  :eacl.relation/resource-type
                                  :eacl.relation/relation-name]) ...]
          :where
          [?relation :eacl.relation/relation-name]]
        db))

(defn read-permissions
  [db]
  (ds/q '[:find [(pull ?perm [:eacl/id
                              :eacl.permission/resource-type
                              :eacl.permission/permission-name
                              :eacl.permission/source-relation-name
                              :eacl.permission/target-type
                              :eacl.permission/target-name]) ...]
          :where
          [?perm :eacl.permission/permission-name]]
        db))

(defn read-schema
  [db & [_format]]
  {:relations   (read-relations db)
   :permissions (read-permissions db)})

(def validate-schema-references model/validate-schema-references)
(def calc-set-deltas model/calc-set-deltas)
(def compare-schema model/compare-schema)

(defn count-relationships-using-relation
  "Counts relationships that reference the given relation, exactly.
  Scans the endpoint-pair forward index whose leading components are
  [subject-type relation resource-type]."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type)
        relation-eid (ds/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      ;; nil-padded to full value arity: DataScript sorts vectors length-first.
      (->> (ds/seek-datoms db :avet
                           forward-relationship-attr
                           [subject-type relation-eid resource-type nil])
           (take-while (fn [datom]
                         (and (= forward-relationship-attr (:a datom))
                              (let [v (:v datom)]
                                (and (vector? v)
                                     (= 4 (count v))
                                     (= subject-type (nth v 0))
                                     (= relation-eid (nth v 1))
                                     (= resource-type (nth v 2)))))))
           (count)))))

(defn write-schema!
  "Parses, validates, diffs and transacts a SpiceDB schema string.
  Throws :eacl.schema/parse-error on unparseable input (a failed parse must
  never retract the stored schema) and :eacl.schema/empty-schema-guard when the
  new schema has zero definitions while a non-empty schema is stored; pass
  {:allow-empty-schema? true} to wipe intentionally."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string
    {:keys [allow-empty-schema? token-ttl-seconds retention-grace-seconds]}]
   (let [new-schema-map  (parser/->eacl-schema (parser/parse-schema schema-string))
         _               (validate-schema-references new-schema-map)
         db              (ds/db conn)
         existing-schema (read-schema db)
         _               (when (and (empty? (:definitions new-schema-map))
                                    (not allow-empty-schema?)
                                    (or (seq (:relations existing-schema))
                                        (seq (:permissions existing-schema))))
                           (throw (ex-info (str "Refusing to replace a non-empty schema with zero definitions."
                                                " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
                                           {:type :eacl.schema/empty-schema-guard
                                            :existing {:relations (count (:relations existing-schema))
                                                       :permissions (count (:permissions existing-schema))}})))
         deltas          (compare-schema existing-schema new-schema-map)
         {:keys [relations permissions]} deltas
         relation-retractions   (:retractions relations)
         permission-retractions (:retractions permissions)]
     (doseq [rel relation-retractions]
       (let [cnt (count-relationships-using-relation db rel)]
         (when (pos? cnt)
           (throw (ex-info (str "Cannot delete relation " (:eacl.relation/relation-name rel)
                                " because it is used by " cnt " relationships.")
                           {:relation rel :count cnt})))))
     (let [tx-data
           (vec
            (concat
             (:additions relations)
             (:additions permissions)
             (for [rel relation-retractions
                   :let [eid (ds/entid db [:eacl/id (:eacl/id rel)])]
                   :when eid]
               [:db/retractEntity eid])
             (for [perm permission-retractions
                   :let [eid (ds/entid db [:eacl/id (:eacl/id perm)])]
                   :when eid]
               [:db/retractEntity eid])
             [{:eacl/id "schema-string"
               :eacl/schema-string schema-string}]))
           stored-string
           (some-> (ds/entity db [:eacl/id "schema-string"])
                   :eacl/schema-string)
           changed?
           (or (not= stored-string schema-string)
               (some seq
                     [(:additions relations)
                      (:retractions relations)
                      (:additions permissions)
                      (:retractions permissions)]))
           mutation-id (mutation/new-id)
           report
           (if changed?
             (journal/transact!
              conn
              {:mutation-id mutation-id
               :kind :schema
               :canonical-data
               {:operation :write-schema
                :schema-string schema-string
                :deltas deltas}
               :schema-change? true
               :token-ttl-seconds token-ttl-seconds
               :retention-grace-seconds retention-grace-seconds
               :relation-ids
               (mapv (fn [relation]
                       [:eacl/id (:eacl/id relation)])
                     (:additions relations))
               :tx-data tx-data})
             {:db-before db
              :db-after db
              :tx-data []
              :mutation-id (:head-id (journal/ensure-migrated! conn))
              :no-op? true})]
       (assoc deltas
              :eacl.mutation/id (:mutation-id report)
              :eacl.mutation/db-after (:db-after report)
              :eacl.mutation/no-op? (boolean (:no-op? report)))))))
