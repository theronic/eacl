(ns eacl.datomic.schema
  (:require [clojure.walk :as walk]
            [datomic.api :as d]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.datomic.storage :as target-storage]
            [eacl.relationships.upgrade :as upgrade]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.relationships.legacy-v7 :as legacy-v7]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as expression-limits]
            [eacl.schema.expression-persistence :as expression-persistence]
            [eacl.schema.expression-policy :as expression-policy]
            [eacl.schema.expression-resolver :as expression-resolver]
            [eacl.schema.model :as model]
            [eacl.schema.replacement-plan :as replacement-plan]))

; should these Malli specs be in a separate namespace, e.g. specs?
; might be confused for Datomic fn's like Relation / Permission in impl. base.
; Ideally Datomic impl. should reuse these.

(def Relation
  [:map
   [:eacl.relation/resource-type :keyword]
   [:eacl.relation/subject-type :keyword]
   [:eacl.relation/relation-name :keyword]])

; todo: fix the Malli schema for unified Permission.
;(def DirectPermission
;  [:map
;   [:eacl.permission/resource-type :keyword]
;   [:eacl.permission/relation-name :keyword]
;   [:eacl.permission/permission-name :keyword]
;
;   [:eacl.relation/subject-type :keyword]
;   [:eacl.relation/relation-name :keyword]])

;(def ArrowPermission
;  [:map
;   [:eacl.arrow-permission/resource-type :keyword]
;   [:eacl.arrow-permission/source-relation-name :keyword]
;   [:eacl.arrow-permission/target-permission-name :keyword]
;   [:eacl.arrow-permission/permission-name :keyword]])

;(def Permission
;  [:or DirectPermission ArrowPermission])

(def schema-version-attr-definition
  "Schema-generation stamp. write-schema! asserts a fresh squuid here in the
  same transaction as any definition change. A connection-backed EACL client
  reads it once at construction and replaces its one cached generation when
  write-schema! is invoked through that client. Do not edit EACL definitions
  outside write-schema!."
  {:db/ident       :eacl/schema-version
   :db/doc         "Squuid bumped by write-schema! whenever definitions change. EACL clients latch one generation at construction."
   :db/valueType   :db.type/uuid
   :db/cardinality :db.cardinality/one
   :db/index       true})

(def permission-storage-version 8)

(def permission-storage-version-attr-definition
  {:db/ident       :eacl/permission-storage-version
   :db/doc         "Authoritative EACL permission representation version (8 = canonical expressions)."
   :db/valueType   :db.type/long
   :db/cardinality :db.cardinality/one
   :db/index       true})

(def relation-version-attr-definition
  "Per-relation change stamp: a ref to the transaction that last added or
  retracted a relationship using this relation.

  EACL's relationship write helpers append
  `[:db/add <relation-eid> :eacl/relation-version \"datomic.tx\"]` to their own
  tx-data, so a writer publishes exactly which relations moved, atomically with
  the move itself. A reader takes the max stamp over the relations a permission
  actually depends on, which is why an unrelated relation's churn cannot
  invalidate a cached answer.

  The value is the tx entity, not a fresh squuid, so the assertion is
  IDEMPOTENT: a transaction touching a thousand relationships of one relation
  emits one identical datom rather than a thousand conflicting ones, and
  callers may freely concat the output of several helpers into one transaction.
  Tx entity ids increase monotonically with `t`, so a max over a dependency set
  is strictly increasing on any write to it.

  History must remain available because proof-equivalent reuse is valid for any
  readable immutable basis, including `d/as-of` values. Datomic indexing may
  discard superseded values of a `:db/noHistory true` attribute, which would
  make an otherwise valid historical proof unreadable."
  {:db/ident       :eacl/relation-version
   :db/doc         "Ref to the transaction that last changed a relationship using this relation. Bumped by EACL's relationship tx-data helpers; read by the result cache to scope invalidation to affected relations."
   :db/valueType   :db.type/ref
   :db/cardinality :db.cardinality/one
   :db/noHistory   false})

(defn- ensure-relation-version-history!
  "Installs the relation-generation attribute when requested and upgrades
  existing databases to retain future generation history."
  [conn install-if-missing?]
  (let [db (d/db conn)
        relation-version-eid (d/entid db :eacl/relation-version)]
    (cond
      (and (nil? relation-version-eid) install-if-missing?)
      @(d/transact conn [relation-version-attr-definition])

      (and relation-version-eid
           (true? (:db/noHistory
                   (d/entity db relation-version-eid))))
      @(d/transact conn [{:db/id :eacl/relation-version
                          :db/noHistory false}])

      :else
      nil)))

(def assert-relation-unused-fn-definition
  "Commit-time guard for relation removal.

  The ordinary preflight count provides a useful error with a count, but it is
  necessarily stale by commit time. This transactor-side check closes the race
  where a relationship is created after that count and before the relation
  entity is retracted."
  {:db/ident :eacl.fn/assert-relation-unused
   :db/fn
   (d/function
    {:lang "clojure"
     :params '[db resource-type relation-eid subject-type]
     :code
     (walk/postwalk-replace
      {'endpoint-value-constructor endpoint-pair/constructor-form}
      '(let [endpoint-value endpoint-value-constructor]
         (if (or
           (seq
            (datomic.api/index-range
             db
             :eacl.v9.relationship/subject-type+relation+resource-type+resource+qualifier
             (endpoint-value subject-type relation-eid resource-type 0 nil)
             (endpoint-value subject-type relation-eid resource-type Long/MAX_VALUE Long/MAX_VALUE)))
           ;; Healthy relationships have both tuple halves. Check the reverse
           ;; index too so relation removal cannot strand a reverse-only tuple
           ;; after an interrupted legacy write or manual data corruption.
           (seq
            (datomic.api/index-range
             db
             :eacl.v9.relationship/resource-type+relation+subject-type+subject+qualifier
             (endpoint-value resource-type relation-eid subject-type 0 nil)
             (endpoint-value resource-type relation-eid subject-type Long/MAX_VALUE Long/MAX_VALUE))))
        (throw
         (ex-info
          "Cannot delete an EACL relation that is used by relationships."
          {:type :eacl.schema/relation-in-use
           :eacl/error :eacl.schema/relation-in-use
           :relation-eid relation-eid
           :resource-type resource-type
           :subject-type subject-type}))
        [])))})})

(def v7-compatible-schema
  [; :eacl/id is now optional.
   {:db/ident       :eacl/id                                ; todo: figure out how to support :id, :object/id or :spice/id of different types.
    :db/doc         "Unique String ID to match SpiceDB Object IDs."
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident       :eacl/schema-string
    :db/doc         "Stores the SpiceDB schema string."
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   schema-version-attr-definition
   permission-storage-version-attr-definition
   relation-version-attr-definition
   assert-relation-unused-fn-definition

   {:db/ident       :eacl/storage-version
    :db/doc         "Relationship storage ABI: 9 = five-slot qualifier-reference endpoint pairs. Written only by explicit bootstrap or a verified migration; clients require completed storage 9."
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; Relations
   {:db/ident       :eacl.relation/resource-type
    :db/doc         "EACL Relation: Resource Type"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relation/relation-name
    :db/doc         "EACL Relation Name (keyword)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relation/subject-type
    :db/doc         "EACL Relation: Subject Type"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ; Relation Indices (these are cheap because Relations are sparse)

   {:db/ident       :eacl.relation/resource-type+relation-name+subject-type
    :db/doc         "EACL Relation: Unique identity tuple enforce uniqueness of Resource Type + Relation Name + Subject Type"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relation/resource-type
                     :eacl.relation/relation-name
                     :eacl.relation/subject-type]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; Unified Permissions Schema
   {:db/ident       :eacl.permission/resource-type
    :db/doc         "EACL Permission: Resource Type"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/permission-name
    :db/doc         "EACL Permission: Permission Name"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/source-relation-name
    :db/doc         "EACL Permission: Source relation for arrow permissions (optional - not present for direct permissions)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/target-type
    :db/doc         "EACL Permission: Target type (:relation or :permission)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/target-name
    :db/doc         "EACL Permission: Target name (relation name or permission name)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/expression-payload
    :db/doc         "EACL canonical permission expression payload."
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}

   ; Permission Indices
   {:db/ident       :eacl.permission/resource-type+permission-name
    :db/doc         "EACL Permission: Index for finding all permissions on a resource type"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; v9 Relationships: forward and reverse tuple indexes only.
   {:db/ident       relationship-storage/forward-attribute
    :db/doc         "EACL v9 relationship tuple from subject to resource."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  relationship-storage/tuple-types
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       relationship-storage/reverse-attribute
    :db/doc         "EACL v9 reverse relationship tuple from resource to subject."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  relationship-storage/tuple-types
    :db/cardinality :db.cardinality/many
    :db/index       true}])

(def v8-schema
  "Clean v8 Datomic install. Released-v7 flat permission attributes are
  intentionally omitted; an existing v7 database retains those immutable
  Datomic schema entities as inert upgrade history after its flat rows retire."
  (filterv
   #(not (contains? expression-persistence/legacy-flat-attributes
                    (:db/ident %)))
   (into v7-compatible-schema
         [(second upgrade/metadata-schema) target-storage/basis-guard])))

(defn install!
  "Explicitly installs and bootstraps fresh Relationship storage 9. Existing
  v7 databases must use eacl.datomic.migrations.v7-to-v9/migrate! instead."
  [conn]
  @(d/transact conn v8-schema)
  (target-storage/bootstrap! conn)
  conn)

(def v7-schema
  "Compatibility name for the former all-in-one installer. New v8 databases
  should call `install!`; released-v7 databases already contain the flat
  attributes required by the explicit permission migration."
  (legacy-v7/source-schema v7-compatible-schema))

(def ^:private authoritative-permission-attribute-idents
  #{:eacl/id
    :eacl/permission-storage-version
    :eacl.permission/resource-type
    :eacl.permission/permission-name
    :eacl.permission/expression-payload
    :eacl.permission/resource-type+permission-name})

(def ^:private additive-v8-permission-attribute-idents
  (disj authoritative-permission-attribute-idents
        :eacl/id
        :eacl.permission/resource-type
        :eacl.permission/permission-name))

(def ^:private authoritative-permission-attribute-definitions
  (into {}
        (keep (fn [definition]
                (when (contains? authoritative-permission-attribute-idents
                                 (:db/ident definition))
                  [(:db/ident definition) definition])))
        v8-schema))

(defn- ident-value
  [db value]
  (when value
    (or (:db/ident value)
        (d/ident db value))))

(defn- attribute-shape
  [db ident]
  (when-let [eid (d/entid db ident)]
    (let [attribute (d/entity db eid)]
      {:value-type (ident-value db (:db/valueType attribute))
       :cardinality (ident-value db (:db/cardinality attribute))
       :unique (ident-value db (:db/unique attribute))
       :tuple-attrs (some->> (:db/tupleAttrs attribute)
                             (mapv #(ident-value db %)))
       :tuple-types (some->> (:db/tupleTypes attribute)
                             (mapv #(ident-value db %)))
       :no-history? (true? (:db/noHistory attribute))
       :is-component? (true? (:db/isComponent attribute))
       :fulltext? (true? (:db/fulltext attribute))
       :indexed? (true? (:db/index attribute))})))

(defn- expected-attribute-shape
  [definition]
  {:value-type (:db/valueType definition)
   :cardinality (:db/cardinality definition)
   :unique (:db/unique definition)
   :tuple-attrs (:db/tupleAttrs definition)
   :tuple-types (:db/tupleTypes definition)
   :no-history? (true? (:db/noHistory definition))
   :is-component? (true? (:db/isComponent definition))
   :fulltext? (true? (:db/fulltext definition))
   :indexed? (true? (:db/index definition))})

(defn- assert-authoritative-permission-attribute-shapes!
  [db]
  (doseq [[ident definition]
          authoritative-permission-attribute-definitions
          :let [actual (attribute-shape db ident)]
          :when actual]
    (let [expected (expected-attribute-shape definition)]
      (when-not (= expected actual)
        (throw
         (ex-info
          "An existing Datomic attribute conflicts with EACL v8 permission storage."
          {:type :eacl.migration/attribute-conflict
           :eacl/error :eacl.migration/attribute-conflict
           :attribute ident
           :expected expected
           :actual actual})))))
  nil)

(defn- ensure-v8-permission-attributes!
  "Installs only authoritative additive v8 permission attributes. Derived
  metric attributes are intentionally absent."
  [conn]
  (let [db (d/db conn)
        _ (assert-authoritative-permission-attribute-shapes! db)
        missing
        (into []
              (filter #(and (contains? additive-v8-permission-attribute-idents
                                       (:db/ident %))
                            (nil? (d/entid db (:db/ident %)))))
              v8-schema)]
    (when (seq missing)
      @(d/transact conn missing))
    (assert-authoritative-permission-attribute-shapes! (d/db conn))
    (count missing)))

(defn count-relationships-using-relation
  "Counts current forward relationship tuples that reference the given relation."
  [db {:eacl.relation/keys [resource-type relation-name subject-type] :as relation}]
  {:pre [(keyword? resource-type)
         (keyword? relation-name)
         (keyword? subject-type)]}
  ;; Reuse the canonical identity stored by the parser rather than duplicating
  ;; its formatting contract here.
  (let [relation-eid (d/entid db [:eacl/id (:eacl/id relation)])]
    (if-not relation-eid
      0
      (max
       (reduce (fn [n _] (inc n))
               0
               (d/index-range db
                              relationship-storage/forward-attribute
                              (endpoint-pair/forward-value subject-type relation-eid resource-type 0)
                              (endpoint-pair/forward-value subject-type relation-eid resource-type Long/MAX_VALUE Long/MAX_VALUE)))
       (reduce (fn [n _] (inc n))
               0
               (d/index-range db
                              relationship-storage/reverse-attribute
                              (endpoint-pair/reverse-value resource-type relation-eid subject-type 0)
                              (endpoint-pair/reverse-value resource-type relation-eid subject-type Long/MAX_VALUE Long/MAX_VALUE)))))))

(defn relationship-present-for-relation?
  "A bounded endpoint-index presence decision used only by speculative
  retain-inert planning. It performs at most one seek per physical direction
  and deliberately does not enumerate or count relationship tuples."
  [db {:eacl.relation/keys [resource-type subject-type] :as relation}]
  (when-let [relation-eid (d/entid db [:eacl/id (:eacl/id relation)])]
    (boolean
     (or
      (first
       (d/index-range db
                      relationship-storage/forward-attribute
                      (endpoint-pair/forward-value subject-type relation-eid resource-type 0)
                      (endpoint-pair/forward-value subject-type relation-eid resource-type Long/MAX_VALUE Long/MAX_VALUE)))
      (first
       (d/index-range db
                      relationship-storage/reverse-attribute
                      (endpoint-pair/reverse-value resource-type relation-eid subject-type 0)
                      (endpoint-pair/reverse-value resource-type relation-eid subject-type Long/MAX_VALUE Long/MAX_VALUE)))))))

(defn read-relations
  "Enumerates all EACL Relation schema entities in DB and returns pull maps."
  [db]
  (d/q '[:find [(pull ?relation [:eacl/id
                                 :eacl.relation/subject-type
                                 :eacl.relation/resource-type
                                 :eacl.relation/relation-name]) ...]
         :where
         [?relation :eacl.relation/relation-name ?relation-name]]
       db))

(defn read-permissions
  "Enumerates all EACL permission schema entities in DB and returns maps."
  [db]
  (d/q '[:find [(pull ?perm [:eacl/id
                             :eacl.permission/resource-type
                             :eacl.permission/permission-name
                             :eacl.permission/source-relation-name
                             :eacl.permission/target-type
                             :eacl.permission/target-name
                             :eacl.permission/expression-payload]) ...]
         :where
         [?perm :eacl.permission/permission-name]]
       db))

(defn read-schema
  "Enumerates all EACL permission schema entities in DB and returns maps."
  ; todo: unparse into SpiceDB string schema if desired.
  [db & [_format]]
  (let [permissions (read-permissions db)]
    (expression-persistence/validate-entities permissions)
    {:relations   (read-relations db)
     :permissions permissions}))

(defn- read-schema-unchecked
  "Migration-only physical schema read. Normal readers must use read-schema so
  flat, mixed, duplicate, or corrupt permission storage fails closed."
  [db]
  {:relations (read-relations db)
   :permissions (read-permissions db)})

(defn prepare-cache-coherence!
  "Initializes missing physical relation generations in an upgraded database.

  Datomic schema generations predate this migration and must already exist;
  a missing schema version fails closed instead of inventing authority."
  [conn]
  (ensure-relation-version-history! conn false)
  (let [db (d/db conn)
        schema-eid (d/entid db [:eacl/id "schema-string"])]
    (when-not (and (d/entid db :eacl/relation-version)
                   schema-eid
                   (seq (d/datoms db :eavt schema-eid
                                  :eacl/schema-version)))
      (throw
       (ex-info
        "Datomic database lacks the native EACL generation schema."
        {:type :eacl.cache/generation-schema-missing
         :eacl/error :eacl.cache/generation-schema-missing
         :backend :datomic})))
    (let [relation-eids
          (if (d/entid db :eacl.relation/relation-name)
            (mapv :e (d/datoms db :aevt :eacl.relation/relation-name))
            [])
          missing-relations
          (filterv #(empty? (d/datoms db :eavt %
                                      :eacl/relation-version))
                   relation-eids)
          tx-data
          (mapv #(vector :db/add % :eacl/relation-version "datomic.tx")
                missing-relations)
          report (when (seq tx-data) @(d/transact conn tx-data))
          db-after (if report (:db-after report) db)
          missing-after
          (filterv #(empty? (d/datoms db-after :eavt %
                                      :eacl/relation-version))
                   relation-eids)]
      {:prepared? true
       :changed? (boolean report)
       :schema-generation-initialized? false
       :relation-generations-initialized (count missing-relations)
       :missing-after missing-after
       :db-after db-after})))

(def validate-schema-references
  "The shared reference validator (`eacl.schema.model/validate-schema-references`):
  direct permissions reference existing relations, arrows reference valid
  source relations and targets that exist on every subject type, self
  permissions reference existing permissions, and relation subject types are
  defined definitions. ADR 012 requires that an invalid schema is rejected
  with no changes made."
  model/validate-schema-references)

; now we have to do a diff of relations and permissions
; we can safely delete permissions because will simply resolve
; but when deleting Relations, we need to check if there are any relationships
; can we use the existing read-relationships internals for this?

(def calc-set-deltas model/calc-set-deltas)
(def compare-schema model/compare-schema)

(defn- cas-failure-data
  [throwable]
  (loop [cause throwable]
    (when cause
      (let [data (ex-data cause)]
        (if (= :db.error/cas-failed (:db/error data))
          data
          (recur (.getCause ^Throwable cause)))))))

(defn- caused-by-type
  [throwable type]
  (loop [cause throwable]
    (when cause
      (if (= type (:type (ex-data cause)))
        cause
        (recur (.getCause ^Throwable cause))))))

(defn- transact-schema!
  "Submits a schema replacement, translating Datomic's generic CAS failure
  into a stable EACL concurrency error.

  The CAS is the commit-time assertion that the diff was calculated from the
  generation still in the database. Without it, two replacement writers can
  both commit additions calculated from the same old generation and leave the
  UNION of their schemas behind."
  [conn tx-data expected-version]
  (try
    @(d/transact conn tx-data)
    (catch Throwable throwable
      (if-let [relation-in-use
               (caused-by-type throwable
                               :eacl.schema/relation-in-use)]
        (throw
         (ex-info (.getMessage ^Throwable relation-in-use)
                  (ex-data relation-in-use)
                  throwable))
        (if-let [cause-data (cas-failure-data throwable)]
          (throw
           (ex-info
            "The EACL schema changed concurrently; retry against a new client or database value."
            {:type :eacl.schema/concurrent-write
             :eacl/error :eacl.schema/concurrent-write
             :expected-version expected-version
             :actual-version (impl.indexed/schema-version (d/db conn))
              ;; The shared key names used by the other backends.
             :expected-generation expected-version
             :actual-generation (impl.indexed/schema-version (d/db conn))
             :backend-error cause-data
             :datomic-error cause-data}
            throwable))
          (throw throwable))))))

(defn- legacy-flat-permission?
  [permission]
  (not (contains? permission :eacl.permission/expression-payload)))

(defn- legacy-permission-node
  [relation-subject-types
   {:eacl.permission/keys
    [resource-type source-relation-name target-type target-name]}]
  (when-not (contains? #{:relation :permission} target-type)
    (throw (ex-info "Legacy permission has an invalid target type."
                    {:type :eacl.schema/corrupt-expression-storage
                     :eacl/error :eacl.schema/corrupt-expression-storage
                     :reason :invalid-legacy-target-type
                     :target-type target-type})))
  (if (= :self source-relation-name)
    (case target-type
      :relation
      (expression/relation
       target-name
       (get relation-subject-types [resource-type target-name]))

      :permission
      (expression/permission target-name))
    (expression/arrow
     source-relation-name
     (mapv (fn [subject-type]
             {:subject-type subject-type
              :target-kind target-type
              :target-name target-name})
           (get relation-subject-types
                [resource-type source-relation-name])))))

(defn- expression-metadata
  [resolved-expression limits]
  (let [source-metrics
        (expression-limits/check-source!
         (:root resolved-expression) limits)
        {:keys [encoded-byte-size]}
        (expression-limits/check-expression-bytes!
         resolved-expression limits)
        {:keys [dag metrics]}
        (expression-limits/check-normalized!
         resolved-expression limits)]
    {:source-metrics source-metrics
     :encoded-byte-size encoded-byte-size
     :normalized-dag dag
     :normalized-metrics metrics}))

(defn- legacy-flat-candidate-schema
  "Converts released v6/v7 union-only permission rows into the canonical v8
  expression representation. This function is reachable only from the
  explicit v6->v7 migration; ordinary v8 reads never synthesize expressions."
  [{:keys [relations permissions]} limits]
  (model/validate-schema-references
   {:relations relations :permissions permissions})
  (let [relation-subject-types
        (reduce
         (fn [result relation]
           (update result
                   [(:eacl.relation/resource-type relation)
                    (:eacl.relation/relation-name relation)]
                   (fnil conj [])
                   (:eacl.relation/subject-type relation)))
         {}
         relations)
        relation-subject-types
        (update-vals relation-subject-types
                     #(vec (sort-by str (distinct %))))
        expressions
        (mapv
         (fn [[[resource-type permission-name] rules]]
           (let [nodes
                 (->> rules
                      (sort-by
                       (juxt (comp str
                                   :eacl.permission/source-relation-name)
                             (comp str :eacl.permission/target-type)
                             (comp str :eacl.permission/target-name)))
                      (mapv #(legacy-permission-node
                              relation-subject-types %)))
                 root (if (= 1 (count nodes))
                        (first nodes)
                        (expression/union nodes))]
             (expression/expression
              resource-type permission-name root)))
         (sort-by
          (fn [[[resource-type permission-name] _]]
            [(str resource-type) (str permission-name)])
          (group-by
           (juxt :eacl.permission/resource-type
                 :eacl.permission/permission-name)
           permissions)))
        metadata (mapv #(expression-metadata % limits) expressions)]
    (expression-limits/check-aggregate!
     metadata limits)
    (expression-persistence/candidate-schema
     {:definitions
      (->> relations
           (mapcat
            (juxt :eacl.relation/resource-type
                  :eacl.relation/subject-type))
           distinct
           (sort-by str)
           vec)
      :relations relations
      :expressions expressions
      :expression-metadata metadata})))

(defn- plan-schema-candidate
  [db schema-string new-schema-map
   {:keys [allow-empty-schema? validate-existing? orphan-policy]
    :or {validate-existing? true
         orphan-policy :error}}]
  (let [existing-schema ((if validate-existing?
                           read-schema
                           read-schema-unchecked)
                         db)
        _ (when
           (and (empty? (:definitions new-schema-map))
                (not allow-empty-schema?)
                (or (seq (:relations existing-schema))
                    (seq (:permissions existing-schema))))
            (throw
             (ex-info
              (str "Refusing to replace a non-empty schema with zero definitions."
                   " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
              {:type :eacl.schema/empty-schema-guard
               :eacl/error :eacl.schema/empty-schema-guard
               :existing
               {:relations (count (:relations existing-schema))
                :permissions (count (:permissions existing-schema))}})))
        deltas (compare-schema existing-schema new-schema-map)
        semantic
        (replacement-plan/plan
         {:deltas deltas
          :orphan-policy orphan-policy
          :relationship-count
          #(count-relationships-using-relation db %)
          :relationship-present?
          #(relationship-present-for-relation? db %)})
        {:keys [relations permissions]} deltas
        relation-retractions (:retractions relations)
        permission-retractions
        (expression-persistence/entity-deletions permissions)
        relation-addition-entities
        (mapv (fn [relation]
                (assoc relation :db/id (d/tempid :db.part/user)))
              (:additions relations))
        relation-initial-stamps
        (mapv (fn [relation]
                [:db/add (:db/id relation)
                 :eacl/relation-version "datomic.tx"])
              relation-addition-entities)
        schema-entity (or (d/entid db [:eacl/id "schema-string"])
                          (d/tempid :db.part/user))
        schema-stamp-entity
        (cond-> {:db/id schema-entity
                 :eacl/id "schema-string"
                 :eacl/permission-storage-version
                 permission-storage-version}
          (some? schema-string)
          (assoc :eacl/schema-string schema-string))
        tx-data
        (vec
         (concat
          relation-addition-entities
          relation-initial-stamps
          (:additions permissions)
          (for [relation relation-retractions]
            [:db.fn/retractEntity [:eacl/id (:eacl/id relation)]])
          (for [permission permission-retractions]
            [:db.fn/retractEntity [:eacl/id (:eacl/id permission)]])
          [schema-stamp-entity]))
        relation-commit-guards
        (mapv
         (fn [relation]
           [:eacl.fn/assert-relation-unused
            (:eacl.relation/resource-type relation)
            (d/entid db [:eacl/id (:eacl/id relation)])
            (:eacl.relation/subject-type relation)])
         relation-retractions)
        no-op? (not (some seq
                          [(:additions relations)
                           (:retractions relations)
                           (:additions permissions)
                           (:retractions permissions)]))
        effective-tx-data (if no-op? [] tx-data)]
    (assoc semantic
           :tx-data effective-tx-data
           :speculative-tx-data
           (if no-op?
             []
             (conj effective-tx-data
                   [:db/add schema-entity :eacl/schema-version (d/squuid)]))
           :relation-commit-guards relation-commit-guards
           :schema-entity schema-entity
           :schema-stamp-entity schema-stamp-entity
           :no-op? no-op?
           :schema-string schema-string)))

(defn plan-schema-replacement
  "Pure prospective schema replacement plan for one immutable Datomic db.
  Parsing, validation, semantic diffing, orphan policy, transaction data and
  stable effect identities are shared with committed `write-schema!`."
  [db schema-string options]
  (let [expression-limits
        (expression-policy/normalize-client-limits
         (:expression-limits options))
        new-schema-map
        (expression-persistence/candidate-schema
         (expression-resolver/validate-schema schema-string expression-limits))]
    (binding [expression-persistence/*expression-limits* expression-limits]
      (plan-schema-candidate db schema-string new-schema-map options))))

(defn- committed-retain-inert!
  []
  (throw
   (ex-info
    ":orphan-policy :retain-inert is available only through eacl/with-schema."
    {:type :eacl.schema/invalid-orphan-policy
     :eacl/error :eacl.schema/invalid-orphan-policy
     :orphan-policy :retain-inert
     :operation :write-schema!})))

(defn- write-schema-candidate!
  [conn schema-string new-schema-map options known-schema-version]
  (when (= :retain-inert (:orphan-policy options))
    (committed-retain-inert!))
  ;; Fresh/partially installed v7 databases may not have the stamp
  ;; attributes yet. This is schema installation, not a v6 compatibility
  ;; path. :eacl/relation-version is installed the same way so a database
  ;; created before per-relation stamps picks it up on its next valid schema
  ;; write rather than silently running without result caching.
  (ensure-relation-version-history! conn true)
  (let [db (d/db conn)
        missing (cond-> []
                  (not (d/entid db :eacl/schema-version))
                  (conj schema-version-attr-definition)

                  (not (d/entid db :eacl/permission-storage-version))
                  (conj permission-storage-version-attr-definition)

                  (not (d/entid db :eacl.fn/assert-relation-unused))
                  (conj assert-relation-unused-fn-definition))]
    (when (seq missing)
      @(d/transact conn missing)))
  (let [db (d/db conn)
        plan (plan-schema-candidate
              db schema-string new-schema-map options)
        deltas (:deltas plan)
        schema-changed? (not (:no-op? plan))
             ;; A connection-backed client passes the version its generation
             ;; was built from. A direct caller uses the version in this exact
             ;; db snapshot. Either value becomes the CAS expectation; a stale
             ;; client therefore fails closed instead of relabeling a new diff.
        current-version (if (= ::read-current-version
                               known-schema-version)
                          (impl.indexed/schema-version db)
                          known-schema-version)
        stamp-missing? (nil? current-version)
        stamp-schema? (or schema-changed? stamp-missing?)
        next-version (if stamp-schema?
                       (d/squuid)
                       current-version)
        schema-entity (:schema-entity plan)
        tx-data
        (concat
         (if (and stamp-missing? (:no-op? plan))
           [(:schema-stamp-entity plan)]
           (:tx-data plan))
         (:relation-commit-guards plan)
              ;; The schema text and generation rotate atomically. CAS with
              ;; old==new is deliberate for a structural no-op: it still
              ;; asserts that another replacement did not commit after the
              ;; diff above was calculated.
         [[:db.fn/cas schema-entity
           :eacl/schema-version current-version next-version]])
        report
        (if stamp-schema?
          (transact-schema!
           conn
           tx-data
           current-version)
          (let [db (d/db conn)]
            {:db-before db
             :db-after db
             :tx-data []
             :no-op? true}))]
    (with-meta deltas
      {:eacl/schema-version next-version
       :eacl.schema/db-after (:db-after report)
       :eacl.schema/no-op? (boolean (:no-op? report))})))

(defn write-schema!
  "Computes delta between existing schema and
  new schema, checks for any orphaned relationships on retracted schema,
  produces tx-ops and applies.

  Throws if schema is invalid (parse failure, operator validation, reference
  validation, orphan check), or if the new schema contains zero definitions
  while a non-empty schema is stored (belt-and-braces against parser gaps —
  a malformed input must never be able to retract the whole schema). Pass
  {:allow-empty-schema? true} to explicitly wipe the stored schema."
  ([conn schema-string]
   (write-schema! conn schema-string {}))
  ([conn schema-string opts]
   (write-schema! conn schema-string opts ::read-current-version))
  ([conn schema-string opts known-schema-version]
   (let [expression-limits
         (expression-policy/normalize-client-limits
          (:expression-limits opts))]
     (binding [expression-persistence/*expression-limits* expression-limits]
       (let [new-schema-map
             (expression-persistence/candidate-schema
              (expression-resolver/validate-schema
               schema-string expression-limits))]
         (write-schema-candidate!
          conn schema-string new-schema-map opts known-schema-version))))))

(defn migrate-v6-schema!
  "Migration-only conversion of released flat v6 schema rows to canonical
  expression storage. If schema-string is supplied it is fully parsed and
  validated before replacement; otherwise the stored union-only rows are
  converted deterministically. The strict v8 read path is never relaxed."
  [conn schema-string]
  (let [expression-limits (expression-policy/normalize-client-limits nil)
        existing (read-schema-unchecked (d/db conn))
        permissions (:permissions existing)
        flat-permissions (filterv legacy-flat-permission? permissions)
        expression-permissions
        (filterv (complement legacy-flat-permission?) permissions)]
    (cond
      (and (seq flat-permissions) (seq expression-permissions))
      ;; Produce the same stable corruption classification as every normal
      ;; reader instead of guessing how an interrupted external rewrite should
      ;; be reconciled.
      (expression-persistence/validate-entities permissions)

      (seq flat-permissions)
      (let [candidate
            (if schema-string
              (expression-persistence/candidate-schema
               (expression-resolver/validate-schema
                schema-string expression-limits))
              (legacy-flat-candidate-schema existing expression-limits))]
        (binding [expression-persistence/*expression-limits* expression-limits]
          (write-schema-candidate!
           conn schema-string candidate
           {:validate-existing? false
            :expression-limits expression-limits}
           ::read-current-version)))

      schema-string
      (write-schema! conn schema-string)

      :else
      nil)))

(defn permission-storage-shape
  "Classifies only permission-definition storage without touching relationship
  tuples. `:flat` is the released v7 input; ordinary v8 reads accept only
  `:expression` or `:none`."
  [db]
  (let [permissions (read-permissions db)
        flat? (some legacy-flat-permission? permissions)
        expression? (some (complement legacy-flat-permission?) permissions)]
    (cond
      (and flat? expression?) :mixed
      expression? :expression
      flat? :flat
      :else :none)))

(defn- permission-denotation
  "Canonical denotation of one candidate permission entity, independent of
  presentation. Explicit grouping flags are annotations on an already-built
  tree, and released-v7 flat rows carry no arm order, so union children
  compare as a set."
  [entity]
  (walk/postwalk
   (fn [node]
     (if (map? node)
       (let [node (dissoc node :grouped?)]
         (if (and (= :union (:op node)) (vector? (:children node)))
           (update node :children
                   (fn [children]
                     (->> children
                          (mapcat #(if (= :union (:op %))
                                     (:children %)
                                     [%]))
                          distinct
                          (sort-by pr-str)
                          vec)))
           node))
       node))
   (:root (expression-persistence/decode-entity entity))))

(defn- assert-equivalent-permission-denotations!
  "Rejects a replacement schema that redefines or removes any permission the
  stored v7 rows define. Additive permissions are permitted; everything else
  must be semantically equivalent, or the upgrade would flip authorization
  under a storage-only banner. Rejection leaves the v7 rows and stamp
  active."
  [stored-candidate supplied-candidate]
  (let [index (fn [candidate]
                (into {}
                      (map (juxt (juxt :eacl.permission/resource-type
                                       :eacl.permission/permission-name)
                                 identity))
                      (:permissions candidate)))
        stored (index stored-candidate)
        supplied (index supplied-candidate)
        removed (vec (sort (remove #(contains? supplied %) (keys stored))))
        divergent
        (vec
         (sort
          (for [[key stored-entity] stored
                :let [supplied-entity (get supplied key)]
                :when (and supplied-entity
                           (not= (permission-denotation stored-entity)
                                 (permission-denotation supplied-entity)))]
            key)))]
    (when (or (seq removed) (seq divergent))
      (throw
       (ex-info
        "The v7->v8 permission upgrade must preserve stored permission semantics."
        {:type :eacl.migration/permission-semantic-change
         :eacl/error :eacl.migration/permission-semantic-change
         :divergent-permissions divergent
         :removed-permissions removed})))))

(defn migrate-v7-permissions!
  "Atomically replaces released v7 flat permissions with v8 expressions.

  The complete candidate and relation-identity diff are computed before any
  additive v8 attribute is installed. Relation additions/retractions are
  rejected: v7 relationship tuples refer to relation entity ids and this
  migration is intentionally permission-only. A supplied replacement schema
  must additionally be semantically equivalent to the stored v7 permissions
  for every permission present in both; only additive permissions may
  differ. The final write atomically retracts old permission entities,
  asserts expressions, stores the schema text when supplied, advances
  :eacl/schema-version, and stamps :eacl/permission-storage-version."
  ([conn schema-string]
   (migrate-v7-permissions! conn schema-string nil))
  ([conn schema-string expression-limit-overrides]
  (let [expression-limits
        (expression-policy/normalize-client-limits expression-limit-overrides)
        db (d/db conn)
        existing (read-schema-unchecked db)
        permissions (:permissions existing)
        shape (permission-storage-shape db)]
    (case shape
      :mixed
      (expression-persistence/validate-entities permissions)

      :expression
      {:status :already-v8
       :permission-storage-version permission-storage-version
       :relationships-touched 0}

      (:flat :none)
      (let [;; Validate every released-v7 row even when the caller supplies a
            ;; replacement schema. A replacement must not silently erase
            ;; evidence that the input storage was already corrupt.
            stored-candidate
            (when (= :flat shape)
              (legacy-flat-candidate-schema existing expression-limits))
            candidate
            (if schema-string
              (binding [expression-persistence/*expression-limits*
                        expression-limits]
                (expression-persistence/candidate-schema
                 (expression-resolver/validate-schema
                  schema-string expression-limits)))
              (or stored-candidate
                  (legacy-flat-candidate-schema existing expression-limits)))
            _ (when (and schema-string stored-candidate)
                (binding [expression-persistence/*expression-limits*
                          expression-limits]
                  (assert-equivalent-permission-denotations!
                   stored-candidate candidate)))
            deltas (compare-schema existing candidate)
            relation-additions (get-in deltas [:relations :additions])
            relation-retractions (get-in deltas [:relations :retractions])]
        (when (or (seq relation-additions) (seq relation-retractions))
          (throw
           (ex-info
            "The v7->v8 permission upgrade cannot change relation identities."
            {:type :eacl.migration/relation-schema-change
             :eacl/error :eacl.migration/relation-schema-change
             :relation-additions (count relation-additions)
             :relation-retractions (count relation-retractions)})))
        (let [expected-version (impl.indexed/schema-version db)
              _ (ensure-v8-permission-attributes! conn)
              result
              (binding [expression-persistence/*expression-limits*
                        expression-limits]
                (write-schema-candidate!
                 conn schema-string candidate
                 {:validate-existing? false
                  :expression-limits expression-limits}
                 expected-version))
              no-op? (boolean (:eacl.schema/no-op? (meta result)))]
          ;; The report describes the transaction that actually ran. A
          ;; structural no-op stamps nothing, so it must not claim a storage
          ;; version no transaction wrote.
          {:status (if no-op? :no-op :migrated)
           :permission-storage-version
           (if no-op?
             (:eacl/permission-storage-version
              (d/entity (d/db conn) [:eacl/id "schema-string"]))
             permission-storage-version)
           :relationships-touched 0
           :permission-additions
           (count (get-in result [:permissions :additions]))
           :permission-retractions
           (count (get-in result [:permissions :retractions]))
           :schema-generation (:eacl/schema-version (meta result))}))))))
