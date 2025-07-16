(ns eacl.datomic.schema
  (:require [clojure.set]
            [datomic.api :as d]
            [com.rpl.specter :as S]
            [malli.core :as m]))

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

(def v5-schema
  [; :eacl/id is now optional.
   {:db/ident :eacl/id ; todo: figure out how to support :id, :object/id or :spice/id of different types.
    :db/doc "Unique String ID to match SpiceDB Object IDs."
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   ;; Relations
   {:db/ident :eacl.relation/resource-type
    :db/doc "EACL Relation: Resource Type"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.relation/relation-name
    :db/doc "EACL Relation Name (keyword)"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.relation/subject-type
    :db/doc "EACL Relation: Subject Type"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   ; Relation Indices (these are cheap because Relations are sparse)

   {:db/ident :eacl.relation/resource-type+relation-name+subject-type
    :db/doc "EACL Relation: Unique identity tuple enforce uniqueness of Resource Type + Relation Name + Subject Type"
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relation/resource-type
                    :eacl.relation/relation-name
                    :eacl.relation/subject-type]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   ;; Unified Permissions Schema
   {:db/ident :eacl.permission/resource-type
    :db/doc "EACL Permission: Resource Type"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.permission/permission-name
    :db/doc "EACL Permission: Permission Name"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.permission/source-relation-name
    :db/doc "EACL Permission: Source relation for arrow permissions (optional - not present for direct permissions)"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.permission/target-type
    :db/doc "EACL Permission: Target type (:relation or :permission)"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.permission/target-name
    :db/doc "EACL Permission: Target name (relation name or permission name)"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   ; Permission Indices
   {:db/ident :eacl.permission/resource-type+permission-name
    :db/doc "EACL Permission: Index for finding all permissions on a resource type"
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.permission/resource-type+target-type+target-name+permission-name
    :db/doc "EACL Permission: Unique identity tuple to enforce uniqueness"
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   {:db/ident :eacl.permission/resource-type+source-relation-name+target-type+target-name+permission-name
    :db/doc "EACL Permission: Full unique identity tuple for arrow permissions"
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.permission/resource-type
                    :eacl.permission/source-relation-name
                    :eacl.permission/target-type
                    :eacl.permission/target-name
                    :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   ;; Relationships [Subject Relation Resource]
   {:db/ident :eacl.relationship/subject-type
    :db/doc "EACL Relationship: Subject Type Keyword"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.relationship/subject
    :db/doc "EACL Relationship: Ref to Subject"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one ; This used to be many, but simpler if one.
    :db/index true}

   {:db/ident :eacl.relationship/relation-name
    :db/doc "EACL Relationship: Relation Name (keyword)"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one ; was many
    :db/index true}

   {:db/ident :eacl.relationship/resource-type
    :db/doc "EACL: Resource Type Keyword"
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index true}

   {:db/ident :eacl.relationship/resource
    :db/doc "EACL Relationship: Ref to Resource"
    :db/valueType :db.type/ref
    :db/cardinality :db.cardinality/one ; was many
    :db/index true}

   ;; Relationship Indices (expensive)
   {:db/ident :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    :db/doc "EACL Relationship: Unique identity tuple for lookup-resources."
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relationship/subject-type
                    :eacl.relationship/subject
                    :eacl.relationship/relation-name
                    :eacl.relationship/resource-type
                    :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}

   ; This can go away as soon as `can?` uses direct index access. May still be needed, though.
   {:db/ident :eacl.relationship/resource+subject
    :db/doc "EACL Relationship Index: for check-permission reachable? hop. Will go away once can? uses direct index."
    :db/valueType :db.type/tuple
    :db/tupleAttrs [:eacl.relationship/resource
                    :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index true}])

{:db/ident :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
 :db/doc "Reverse tuple index for efficient reverse traversal"
 :db/valueType :db.type/tuple
 :db/tupleAttrs [:eacl.relationship/resource-type
                 :eacl.relationship/resource
                 :eacl.relationship/relation-name
                 :eacl.relationship/subject-type
                 :eacl.relationship/subject]
 :db/cardinality :db.cardinality/one
 :db/index true
 :db/unique :db.unique/identity}

;; Legacy arrow-permission attributes removed in unified schema 

{:db/ident :eacl.relationship/resource+relation-name+subject-type+subject
 :db/doc "Tuple for reverse lookup"
 :db/valueType :db.type/tuple
 :db/tupleAttrs [:eacl.relationship/resource
                 :eacl.relationship/relation-name
                 :eacl.relationship/subject-type
                 :eacl.relationship/subject]
 :db/cardinality :db.cardinality/one
 :db/index true}

; This will be needed for efficient lookup-subjects
;{:db/ident       :eacl.relationship/subject+resource-type
; :db/doc         "EACL Relationship: Tuple Index on [Subject Type + Resource Type]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject
;                  :eacl.relationship/resource-type]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/resource-type+relation-name
; :db/doc         "EACL Relationship: Tuple Index on [Resource Type + Relation Name]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/resource-type
;                  :eacl.relationship/relation-name]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/subject-type+relation-name
; :db/doc         "EACL Relationship: Tuple Index on [Subject Type + Relation Name]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject-type
;                  :eacl.relationship/relation-name]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/resource-type+relation-name+subject
; :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/resource-type
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/subject]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/subject-type+relation-name+resource-type
; :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject-type
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/resource-type]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/subject+relation-name+resource-type
; :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/resource-type]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/resource+relation-name+subject-type
; :db/doc         "EACL Relationship: Tuple Index on [Resource + Relation + Subject Type]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/resource
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/subject-type]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;; hmm I think we're going to need relationship indices on subject & resource types
; do we need this?
;{:db/ident       :eacl.relationship/resource+relation-name
; :db/doc         "EACL Relationship: Unique identity tuple to enforce uniqueness of [Subject + Relation + Resource]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/resource
;                  :eacl.relationship/relation-name]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/resource+relation-name+subject
; :db/doc         "EACL Relationship: Unique identity tuple to enforce uniqueness of [Subject + Relation + Resource]."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/resource
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/subject]
; :db/cardinality :db.cardinality/one
; :db/unique      :db.unique/identity}

;{:db/ident       :eacl.relationship/subject+relation-name+resource
; :db/doc         "EACL Relationship: Unique identity tuple for lookup-resources."
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject
;                  :eacl.relationship/relation-name
;                  :eacl.relationship/resource]
; :db/cardinality :db.cardinality/one
; :db/unique      :db.unique/identity}

;; Critical for subject-based lookups
;{:db/ident       :eacl.relationship/subject+relation-name
; :db/doc         "Index for efficient subject-based lookups"
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/subject
;                  :eacl.relationship/relation-name]
; :db/cardinality :db.cardinality/one
; :db/index       true}

;{:db/ident       :eacl.relationship/relation-name+resource
; :db/doc         "Index for relation-based traversal"
; :db/valueType   :db.type/tuple
; :db/tupleAttrs  [:eacl.relationship/relation-name
;                  :eacl.relationship/resource]
; :db/cardinality :db.cardinality/one
; :db/index       true}

(defn read-relations
  "Enumerates all EACL Relation schema entities in DB and returns pull maps."
  [db]
  (d/q '[:find [(pull ?relation [:eacl.relation/subject-type
                                 :eacl.relation/resource-type
                                 :eacl.relation/relation-name]) ...]
         :where
         [?relation :eacl.relation/relation-name ?relation-name]]
       db))

(defn read-permissions
  "Enumerates all EACL permission schema entities in DB and returns maps."
  [db]
  (d/q '[:find [(pull ?perm [:eacl.permission/resource-type
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
  {:relations (read-relations db)
   :permissions (read-permissions db)})

; now we have to do a diff of relations and permissions
; we can safely delete permissions because will simply resolve
; but when deleting Relations, we need to check if there are any relationships
; can we use the existing read-relationships internals for this?

(defn compare-schema
  "Compares before & after schema (without DB IDs) and returns a diff via clojure.set/difference."
  [{:as before
    before-relations :relations
    before-permissions :permissions}
   {:as after
    after-relations :relations
    after-permissions :permissions}]
  ; how to get a nice left vs. right diff?
  {:relations (clojure.set/difference
               (->> before-relations
                    (S/setval [S/ALL :db/id] S/NONE) ; no longer needed.
                    (set))
               (->> after-relations
                    (S/setval [S/ALL :db/id] S/NONE)
                    (set)))
   :permissions (clojure.set/difference
                 (->> before-permissions
                      (S/setval [S/ALL :db/id] S/NONE)
                      (set))
                 (->> after-permissions
                      (S/setval [S/ALL :db/id] S/NONE)
                      (set)))})

(defn write-schema!
  "Gets existing EACL schema, Loops over tx-data, validates the shape."
  [conn {:as new-schema :keys [relations permissions]}]
  (let [db (d/db conn)
        existing-schema (read-schema db)]
    ; WIP.
    (compare-schema existing-schema new-schema)))