(ns eacl.datomic.schema
  (:require [clojure.set]
            [datomic.api :as d]
            [eacl.datomic.impl.indexed :as impl.indexed]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.schema.model :as model]
            [eacl.spicedb.parser :as parser]))

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

  :db/noHistory because only the current stamp is ever read; without it every
  EACL write would accumulate a permanent datom per relation."
  {:db/ident       :eacl/relation-version
   :db/doc         "Ref to the transaction that last changed a relationship using this relation. Bumped by EACL's relationship tx-data helpers; read by the result cache to scope invalidation to affected relations."
   :db/valueType   :db.type/ref
   :db/cardinality :db.cardinality/one
   :db/noHistory   true})

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
     '(if (or
           (seq
            (datomic.api/index-range
             db
             :eacl.v7.relationship/subject-type+relation+resource-type+resource
             [subject-type relation-eid resource-type 0]
             [subject-type relation-eid resource-type Long/MAX_VALUE]))
           ;; Healthy relationships have both tuple halves. Check the reverse
           ;; index too so relation removal cannot strand a reverse-only tuple
           ;; after an interrupted legacy write or manual data corruption.
           (seq
            (datomic.api/index-range
             db
             :eacl.v7.relationship/resource-type+relation+subject-type+subject
             [resource-type relation-eid subject-type 0]
             [resource-type relation-eid subject-type Long/MAX_VALUE])))
        (throw
         (ex-info
          "Cannot delete an EACL relation that is used by relationships."
          {:type :eacl.schema/relation-in-use
           :eacl/error :eacl.schema/relation-in-use
           :relation-eid relation-eid
           :resource-type resource-type
           :subject-type subject-type}))
        [])})})

(def v7-schema
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
   relation-version-attr-definition
   assert-relation-unused-fn-definition

   {:db/ident       :eacl/storage-version
    :db/doc         "EACL relationship storage-model major version (7 = tuple relationships). Stamped by eacl.migrations.v6-to-v7 on completed migration; eacl.datomic.core/make-client refuses to start against unmigrated v6 relationship data without it."
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

   ; Permission Indices
   {:db/ident       :eacl.permission/resource-type+permission-name
    :db/doc         "EACL Permission: Index for finding all permissions on a resource type"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; Added: Enumeration indices for efficient arrow permission lookup
   {:db/ident       :eacl.permission/resource-type+source-relation-name+target-type+permission-name
    :db/doc         "EACL Permission: Index for enumerating permission-type arrows"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/source-relation-name
                     :eacl.permission/target-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/resource-type+source-relation-name+target-type+target-name
    :db/doc         "EACL Permission: Index for enumerating relation-type arrows"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/source-relation-name
                     :eacl.permission/target-type
                     :eacl.permission/target-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name
    :db/doc         "EACL Permission: Full unique identity tuple to prevent duplicate permissions."
    ; I suspect the tuple order can be improved for faster permission enumeration.
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/source-relation-name
                     :eacl.permission/target-type
                     :eacl.permission/target-name
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; v7 Relationships: forward and reverse tuple indexes only.
   {:db/ident       relationship-storage/forward-attribute
    :db/doc         "EACL v7 relationship tuple from subject to resource."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       relationship-storage/reverse-attribute
    :db/doc         "EACL v7 reverse relationship tuple from resource to subject."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}])

(defn count-relationships-using-relation
  "Counts v7 forward relationship tuples that reference the given relation."
  [db {:eacl.relation/keys [resource-type relation-name subject-type] :as relation}]
  {:pre [(keyword? resource-type)
         (keyword? relation-name)
         (keyword? subject-type)]}
  ;; Reuse the canonical identity stored by the parser rather than duplicating
  ;; its formatting contract here.
  (let [relation-eid (d/entid db [:eacl/id (:eacl/id relation)])]
    (if-not relation-eid
      0
      (reduce (fn [n _] (inc n))
              0
              (d/index-range db
                             relationship-storage/forward-attribute
                             [subject-type relation-eid resource-type 0]
                             [subject-type relation-eid resource-type Long/MAX_VALUE])))))

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
                             :eacl.permission/target-name]) ...]
         :where
         [?perm :eacl.permission/permission-name]]
       db))

(defn read-schema
  "Enumerates all EACL permission schema entities in DB and returns maps."
  ; todo: unparse into SpiceDB string schema if desired.
  [db & [_format]]
  {:relations   (read-relations db)
   :permissions (read-permissions db)})

(defn prepare-cache-coherence!
  "Initializes missing physical relation generations in an upgraded database.

  Datomic schema generations predate this migration and must already exist;
  a missing schema version fails closed instead of inventing authority."
  [conn]
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
            (into [] (map :e)
                  (d/datoms db :aevt :eacl.relation/relation-name))
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

(defn calc-set-deltas [before after]
  {:additions   (clojure.set/difference after before)
   :unchanged   (clojure.set/intersection before after)
   :retractions (clojure.set/difference before after)})

(defn compare-schema
  "Compares before & after schema (without DB IDs) and returns a diff via clojure.set/difference."
  [{before-relations   :relations
    before-permissions :permissions}
   {after-relations   :relations
    after-permissions :permissions}]
  ; how to get a nice left vs. right diff?
  ; when can we ditch the setval :db/id?
  (let [before-relations-set   (set before-relations)
        after-relations-set    (set after-relations)
        before-permissions-set (set before-permissions)
        after-permissions-set  (set after-permissions)]
    {:relations   (calc-set-deltas before-relations-set after-relations-set)
     :permissions (calc-set-deltas before-permissions-set after-permissions-set)}))

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
  ([conn schema-string
    {:keys [allow-empty-schema?]}
    known-schema-version]
   (let [new-schema-map (parser/->eacl-schema
                         (parser/parse-schema schema-string))
         ;; ADR 012 says an invalid schema makes NO database changes. This must
         ;; therefore precede even the compatibility installation below.
         _ (validate-schema-references new-schema-map)]
     ;; Fresh/partially installed v7 databases may not have the stamp
     ;; attributes yet. This is schema installation, not a v6 compatibility
     ;; path. :eacl/relation-version is installed the same way so a database
     ;; created before per-relation stamps picks it up on its next valid schema
     ;; write rather than silently running without result caching.
     (let [db (d/db conn)
           missing (cond-> []
                     (not (d/entid db :eacl/schema-version))
                     (conj schema-version-attr-definition)

                     (not (d/entid db :eacl/relation-version))
                     (conj relation-version-attr-definition)

                     (not (d/entid db :eacl.fn/assert-relation-unused))
                     (conj assert-relation-unused-fn-definition))]
       (when (seq missing)
         @(d/transact conn missing)))
     (let [db                     (d/db conn)
           existing-schema        (read-schema db)
           _                      (when (and (empty? (:definitions new-schema-map))
                                           (not allow-empty-schema?)
                                           (or (seq (:relations existing-schema))
                                               (seq (:permissions existing-schema))))
                                  (throw (ex-info (str "Refusing to replace a non-empty schema with zero definitions."
                                                       " Pass {:allow-empty-schema? true} to write-schema! if this is intentional.")
                                                  {:type :eacl.schema/empty-schema-guard :eacl/error :eacl.schema/empty-schema-guard
                                                   :existing {:relations (count (:relations existing-schema))
                                                              :permissions (count (:permissions existing-schema))}})))
           deltas                 (compare-schema existing-schema new-schema-map)
           {:keys [relations permissions]} deltas
           relation-retractions   (:retractions relations)
           permission-retractions (:retractions permissions)]

       ;; Check for orphaned relationships.
       (doseq [rel relation-retractions]
         (let [cnt (count-relationships-using-relation db rel)]
           (when (pos? cnt)
             (throw (ex-info (str "Cannot delete relation " (:eacl.relation/relation-name rel)
                                  " because it is used by " cnt " relationships.")
                             {:type :eacl.schema/relation-in-use
                              :eacl/error :eacl.schema/relation-in-use
                              :relation rel
                              :count cnt})))))

       ;; Transact changes.
       (let [schema-changed? (boolean
                              (or (seq (:additions relations))
                                  (seq relation-retractions)
                                  (seq (:additions permissions))
                                  (seq permission-retractions)))
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
             schema-entity (or (d/entid db [:eacl/id "schema-string"])
                               (d/tempid :db.part/user))
             relation-commit-guards
             (mapv
              (fn [relation]
                [:eacl.fn/assert-relation-unused
                 (:eacl.relation/resource-type relation)
                 (d/entid db [:eacl/id (:eacl/id relation)])
                 (:eacl.relation/subject-type relation)])
              relation-retractions)
             relation-addition-entities
             (mapv (fn [relation]
                     (assoc relation
                            :db/id (d/tempid :db.part/user)))
                   (:additions relations))
             relation-initial-stamps
             (mapv (fn [relation]
                     [:db/add (:db/id relation)
                      :eacl/relation-version "datomic.tx"])
                   relation-addition-entities)
             tx-data
             (concat
              ;; Additions
              relation-addition-entities
              relation-initial-stamps
              (:additions permissions)
              ;; Retractions
              (for [rel relation-retractions]
                [:db.fn/retractEntity [:eacl/id (:eacl/id rel)]])
              (for [perm permission-retractions]
                [:db.fn/retractEntity [:eacl/id (:eacl/id perm)]])
              ;; Close the orphan-check race in the transactor's db value. A
              ;; relationship committed after the count above makes this
              ;; transaction fail instead of deleting an in-use definition.
              relation-commit-guards
              ;; The schema text and generation rotate atomically. CAS with
              ;; old==new is deliberate for a structural no-op: it still
              ;; asserts that another replacement did not commit after the
              ;; diff above was calculated.
              [{:db/id schema-entity
                :eacl/id "schema-string"
                :eacl/schema-string schema-string}
               [:db.fn/cas schema-entity
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
         (with-meta
           deltas
           {:eacl/schema-version next-version
            :eacl.schema/db-after (:db-after report)
            :eacl.schema/no-op? (boolean (:no-op? report))}))))))
