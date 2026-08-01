(ns eacl.datahike.schema
  (:require [datahike.api :as d]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.mutation :as journal]
            [eacl.mutation :as mutation]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

(def forward-relationship-attr
  :eacl.v7.relationship/subject-type+subject+relation+resource-type+resource)

(def reverse-relationship-attr
  :eacl.v7.relationship/resource-type+resource+relation+subject-type+subject)

(def forward-partial-relationship-attr
  :eacl.v7.relationship/subject-type+relation+resource-type+resource+subject)

(def reverse-partial-relationship-attr
  :eacl.v7.relationship/resource-type+relation+subject-type+subject+resource)

(def relationship-full-key-attr
  :eacl.relationship/full-key)

(def relation-key-attr
  :eacl.relation/resource-type+relation-name+subject-type)

(def permission-key-attr
  :eacl.permission/resource-type+permission-name)

(def max-entid Long/MAX_VALUE)

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

(def ^:private component-schema
  "The attributes composite tuples are derived FROM. Datahike's `:write`
   flexibility needs a declared `:db/valueType` and `:db/cardinality` on every
   attribute, which DataScript's schema map leaves implicit."
  [{:db/ident       :eacl/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :eacl/schema-string
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident       :eacl.relation/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/permission-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/source-relation-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/target-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/target-name
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.mutation/id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity
    :db/index       true}
   {:db/ident       :eacl.mutation/fingerprint
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/kind
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/issued-at
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.mutation/expires-at
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/family-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/head-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.graph/head-order
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl.schema/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relation/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.dependency/mutation-id
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl.relationship/relation
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl.relationship/resource
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :eacl.relationship/subject-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.relationship/resource-type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}])

(def ^:private tuple-schema
  "The composite tuples. These ARE the v7 engine: every relationship traversal
   is a bounded range over one of the four orderings below, so each must be
   indexed. Deriving them requires datahike >= 0.8.1759 (replikativ/datahike#921)
   under `:attribute-refs?`; before that fix they were silently never derived,
   and every permission check denied."
  [{:db/ident       :eacl.relation/resource-type+relation-name+subject-type
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relation/resource-type
                     :eacl.relation/relation-name
                     :eacl.relation/subject-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :eacl.permission/resource-type+permission-name
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :eacl.permission/full-key
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/source-relation-name
                     :eacl.permission/target-type
                     :eacl.permission/target-name
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :eacl.relationship/full-key
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/subject
                     :eacl.relationship/relation
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       forward-relationship-attr
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/subject
                     :eacl.relationship/relation
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       reverse-relationship-attr
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/resource
                     :eacl.relationship/relation
                     :eacl.relationship/subject-type
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       forward-partial-relationship-attr
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/relation
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       reverse-partial-relationship-attr
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/relation
                     :eacl.relationship/subject-type
                     :eacl.relationship/subject
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/index       true}])

(def datahike-schema
  "EACL's own attributes, as datahike transaction data."
  (into component-schema tuple-schema))

(defn merge-schema
  "EACL's attributes plus the caller's. `extra-schema` is datahike-native
   transaction data (a sequence of attribute maps), NOT DataScript's map of
   attribute to options: datahike's `:write` flexibility needs value types that
   a DataScript schema map does not carry, so the two are not interconvertible."
  ([] datahike-schema)
  ([extra-schema]
   (if (seq extra-schema)
     (into datahike-schema extra-schema)
     datahike-schema)))

(def default-config
  {:store              {:backend :memory}
   :schema-flexibility :write
   :keep-history?      false
   ;; EACL object ids come from the caller, so no cap is imposed here; stating 0
   ;; declares that as intentional rather than leaving datahike to warn about it.
   :max-string-length  0})

(defn create-conn
  "A datahike connection carrying EACL's schema.

  `config` is merged over `default-config`; pass `{:attribute-refs? true}` to get
  Datomic's numeric attribute representation. A memory store gets a fresh id per
  connection unless one is supplied, so two calls do not collide."
  ([] (create-conn nil nil))
  ([extra-schema] (create-conn extra-schema nil))
  ([extra-schema config]
   (let [cfg (-> (merge default-config config)
                 (update :store #(merge {:id (random-uuid)} %)))]
     (d/create-database cfg)
     (let [conn (d/connect cfg)]
       (d/transact conn (merge-schema extra-schema))
       conn))))

(def relation-pull
  '[:eacl/id
    :eacl.relation/subject-type
    :eacl.relation/resource-type
    :eacl.relation/relation-name])

(def permission-pull
  '[:eacl/id
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/source-relation-name
    :eacl.permission/target-type
    :eacl.permission/target-name])

(defn read-relations
  "Every relation definition. Enumerated from the relation key index rather than
   by query: the index is the engine's own view of the schema, so a relation
   that is invisible here is invisible to permission evaluation too."
  [db]
  (mapv #(d/pull db relation-pull (:e %))
        (ddb/avet-datoms db relation-key-attr)))

(defn read-permissions
  [db]
  (mapv #(d/pull db permission-pull (:e %))
        (ddb/avet-datoms db permission-key-attr)))

(defn read-schema
  [db & [_format]]
  {:relations   (read-relations db)
   :permissions (read-permissions db)})

(def validate-schema-references model/validate-schema-references)
(def calc-set-deltas model/calc-set-deltas)
(def compare-schema model/compare-schema)

(defn count-relationships-using-relation
  "Counts relationships that reference the given relation, exactly.
  Scans the forward-partial index whose leading components are
  [subject-type relation]; a range over the forward index with varying middle
  components would span other relations of the same subject-type and overcount."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  (let [relation-id  (model/->relation-id resource-type relation-name subject-type)
        relation-eid (ddb/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (count (ddb/seek-tuple-prefix db forward-partial-relationship-attr 5
                                    [subject-type relation-eid])))))

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
         db              (d/db conn)
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
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id rel)])]
                   :when eid]
               [:db/retractEntity eid])
             (for [perm permission-retractions
                   :let [eid (ddb/entid db [:eacl/id (:eacl/id perm)])]
                   :when eid]
               [:db/retractEntity eid])
             [{:eacl/id "schema-string"
               :eacl/schema-string schema-string}]))
           stored-string
           (some-> (d/entity db [:eacl/id "schema-string"])
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
