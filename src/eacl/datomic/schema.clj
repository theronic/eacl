(ns eacl.datomic.schema
  (:require [clojure.set]
            [datomic.api :as d]
            [com.rpl.specter :as S]
            [malli.core :as m]
            [eacl.spicedb.parser :as parser]
            [eacl.datomic.impl.indexed :as impl.indexed]))

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
   {:db/ident       :eacl.v7.relationship/subject-type+relation+resource-type+resource
    :db/doc         "EACL v7 relationship tuple from subject to resource."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl.v7.relationship/resource-type+relation+subject-type+subject
    :db/doc         "EACL v7 reverse relationship tuple from resource to subject."
    :db/valueType   :db.type/tuple
    :db/tupleTypes  [:db.type/keyword
                     :db.type/ref
                     :db.type/keyword
                     :db.type/ref]
    :db/cardinality :db.cardinality/many
    :db/index       true}])

(def v6-schema
  "Compatibility alias while tests and callers move to the v7 name."
  v7-schema)

(defn count-relationships-using-relation
  "Counts v7 forward relationship tuples that reference the given relation."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  {:pre [(keyword? resource-type)
         (keyword? relation-name)
         (keyword? subject-type)]}
  (let [relation-id  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type)
        relation-eid (d/entid db [:eacl/id relation-id])]
    (if-not relation-eid
      0
      (reduce (fn [n _] (inc n))
        0
        (d/index-range db
          :eacl.v7.relationship/subject-type+relation+resource-type+resource
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

(defn validate-schema-references
  "Validates that all permission references are valid.
   Returns nil if valid, throws ex-info with :errors vector if invalid.
   
   Validates:
   - Direct permissions reference existing relations on same resource type
   - Arrow permissions reference valid source relations on same resource type
   - Arrow targets (permission or relation) exist on target resource type
   - Self-referencing permissions reference existing permissions on same type
   
   ADR 012 requires: 'Invalid schema should be rejected and no changes made.'"
  [{:keys [relations permissions]}]
  (let [;; Build lookups
        relations-by-type   (group-by :eacl.relation/resource-type relations)
        permissions-by-type (group-by :eacl.permission/resource-type permissions)

        relation-names-by-type
                            (into {} (for [[rt rels] relations-by-type]
                                       [rt (set (map :eacl.relation/relation-name rels))]))

        permission-names-by-type
                            (into {} (for [[rt perms] permissions-by-type]
                                       [rt (set (map :eacl.permission/permission-name perms))]))

        ;; Get subject types for each relation (for arrow target validation)
        relation-subject-types
                            (into {} (for [rel relations]
                                       [[(:eacl.relation/resource-type rel)
                                         (:eacl.relation/relation-name rel)]
                                        (:eacl.relation/subject-type rel)]))

        errors              (atom [])]

    (doseq [perm permissions]
      (let [res-type    (:eacl.permission/resource-type perm)
            perm-name   (:eacl.permission/permission-name perm)
            source-rel  (:eacl.permission/source-relation-name perm)
            target-type (:eacl.permission/target-type perm)
            target-name (:eacl.permission/target-name perm)]

        ;; For self permissions (source-rel = :self), validate target exists on same resource
        (if (= source-rel :self)
          (if (= target-type :relation)
            ;; Self -> relation: validate relation exists on this resource type
            (when-not (contains? (get relation-names-by-type res-type) target-name)
              (swap! errors conj
                {:type       :invalid-self-relation
                 :permission (str (name res-type) "/" (name perm-name))
                 :target     target-name
                 :message    (str "Permission " (name res-type) "/" (name perm-name)
                             " references non-existent relation: " (name target-name))}))
            ;; Self -> permission: validate permission exists on this resource type
            (when-not (contains? (get permission-names-by-type res-type) target-name)
              (swap! errors conj
                {:type       :invalid-self-permission
                 :permission (str (name res-type) "/" (name perm-name))
                 :target     target-name
                 :message    (str "Permission " (name res-type) "/" (name perm-name)
                             " references non-existent permission: " (name target-name))})))

          ;; For arrow permissions (source-rel != :self)
          (do
            ;; Validate source relation exists on this resource type
            (when-not (contains? (get relation-names-by-type res-type) source-rel)
              (swap! errors conj
                {:type       :missing-source-relation
                 :permission (str (name res-type) "/" (name perm-name))
                 :relation   source-rel
                 :message    (str "Permission " (name res-type) "/" (name perm-name)
                             " references non-existent relation: " (name source-rel))}))

            ;; If source relation exists, validate target exists on target resource type
            (when (contains? (get relation-names-by-type res-type) source-rel)
              (let [target-res-type (get relation-subject-types [res-type source-rel])]
                (when target-res-type
                  (if (= target-type :relation)
                    ;; Arrow to relation: validate relation exists on target type
                    (when-not (contains? (get relation-names-by-type target-res-type) target-name)
                      (swap! errors conj
                        {:type        :invalid-arrow-target-relation
                         :permission  (str (name res-type) "/" (name perm-name))
                         :arrow-via   source-rel
                         :target-type target-res-type
                         :target      target-name
                         :message     (str "Permission " (name res-type) "/" (name perm-name)
                                       " arrow via " (name source-rel) "->" (name target-name)
                                       " - relation '" (name target-name) "' does not exist on " (name target-res-type))}))
                    ;; Arrow to permission: validate permission exists on target type
                    (when-not (contains? (get permission-names-by-type target-res-type) target-name)
                      (swap! errors conj
                        {:type        :invalid-arrow-target-permission
                         :permission  (str (name res-type) "/" (name perm-name))
                         :arrow-via   source-rel
                         :target-type target-res-type
                         :target      target-name
                         :message     (str "Permission " (name res-type) "/" (name perm-name)
                                       " arrow via " (name source-rel) "->" (name target-name)
                                       " - permission '" (name target-name) "' does not exist on " (name target-res-type))}))))))))))

    (when (seq @errors)
      (throw (ex-info "Invalid schema: reference validation failed"
               {:errors      @errors
                :error-count (count @errors)})))
    nil))

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
  [{:as                before
    before-relations   :relations
    before-permissions :permissions}
   {:as               after
    after-relations   :relations
    after-permissions :permissions}]
  ; how to get a nice left vs. right diff?
  ; when can we ditch the setval :db/id?
  (let [before-relations-set   (->> before-relations
                                 ;(S/setval [S/ALL :db/id] S/NONE) ; no longer needed.
                                   (set))
        after-relations-set    (->> after-relations
                                    ;(S/setval [S/ALL :db/id] S/NONE)
                                    (set))

        before-permissions-set (->> before-permissions
                                 ;(S/setval [S/ALL :db/id] S/NONE)
                                 (set))
        after-permissions-set  (->> after-permissions
                                 ;(S/setval [S/ALL :db/id] S/NONE)
                                  (set))]
    {:relations   (calc-set-deltas before-relations-set after-relations-set)
     :permissions (calc-set-deltas before-permissions-set after-permissions-set)}))

(defn write-schema!
  "Computes delta between existing schema and
  new schema, checks for any orphaned relationships on retracted schema,
  produces tx-ops and applies.
  
  Throws if schema is invalid (operator validation, reference validation, orphan check)."
  [conn schema-string]
  (let [new-schema-map         (parser/->eacl-schema (parser/parse-schema schema-string))
        ;; Validate schema references before proceeding (ADR 012 requirement)
        _                      (validate-schema-references new-schema-map)
        db                     (d/db conn)
        existing-schema        (read-schema db)
        deltas                 (compare-schema existing-schema new-schema-map)
        {:keys [relations permissions]} deltas
        relation-retractions   (:retractions relations)
        permission-retractions (:retractions permissions)]

    ;; Check for orphaned relationships
    (doseq [rel relation-retractions]
      (let [cnt (count-relationships-using-relation db rel)]
        (when (pos? cnt)
          (throw (ex-info (str "Cannot delete relation " (:eacl.relation/relation-name rel)
                            " because it is used by " cnt " relationships.")
                   {:relation rel :count cnt})))))

    ;; Transact changes
    (let [tx-data (concat
                    ;; Additions
                    (:additions relations)
                    (:additions permissions)
                     ;; Retractions
                    (for [rel relation-retractions]
                      [:db.fn/retractEntity [:eacl/id (:eacl/id rel)]])
                    (for [perm permission-retractions]
                      [:db.fn/retractEntity [:eacl/id (:eacl/id perm)]])
                     ;; Store schema string
                    [{:eacl/id            "schema-string"
                      :eacl/schema-string schema-string}])]
      @(d/transact conn tx-data)
      (impl.indexed/evict-permission-paths-cache!)
      deltas)))
