(ns eacl.datascript.schema
  (:require [datascript.core :as ds]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def forward-relationship-attr
  :eacl.v7.relationship/subject-type+subject+relation+resource-type+resource)

(def reverse-relationship-attr
  :eacl.v7.relationship/resource-type+resource+relation+subject-type+subject)

(def relationship-full-key-attr
  :eacl.relationship/full-key)

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

   :eacl.relationship/subject {:db/valueType :db.type/ref}
   :eacl.relationship/relation {:db/valueType :db.type/ref}
   :eacl.relationship/resource {:db/valueType :db.type/ref}
   :eacl.relationship/subject-type {:db/index true}
   :eacl.relationship/resource-type {:db/index true}
   :eacl.relationship/full-key
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relationship/subject-type
                    :eacl.relationship/subject
                    :eacl.relationship/relation
                    :eacl.relationship/resource-type
                    :eacl.relationship/resource]
    :db/unique :db.unique/identity}
   :eacl.v7.relationship/subject-type+subject+relation+resource-type+resource
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relationship/subject-type
                    :eacl.relationship/subject
                    :eacl.relationship/relation
                    :eacl.relationship/resource-type
                    :eacl.relationship/resource]
    :db/index true}
   :eacl.v7.relationship/resource-type+resource+relation+subject-type+subject
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relationship/resource-type
                    :eacl.relationship/resource
                    :eacl.relationship/relation
                    :eacl.relationship/subject-type
                    :eacl.relationship/subject]
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
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type)
        relation-eid (ds/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (count
       (ds/index-range db
         forward-relationship-attr
         [subject-type 0 relation-eid resource-type 0]
         [subject-type max-entid relation-eid resource-type max-entid])))))

(defn write-schema!
  [conn schema-string]
  (let [new-schema-map  (parser/->eacl-schema (parser/parse-schema schema-string))
        _               (validate-schema-references new-schema-map)
        db              (ds/db conn)
        existing-schema (read-schema db)
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
    (ds/transact! conn
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
    deltas))
