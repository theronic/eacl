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

(def v6-schema
  [; :eacl/id is now optional.
   {:db/ident       :eacl/id                                ; todo: figure out how to support :id, :object/id or :spice/id of different types.
    :db/doc         "Unique String ID to match SpiceDB Object IDs."
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
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

   ;; Relationships [Subject Relation Resource]
   {:db/ident       :eacl.relationship/subject-type
    :db/doc         "EACL Relationship: Subject Type Keyword"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject
    :db/doc         "EACL Relationship: Ref to Subject"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one                     ; This used to be many, but simpler if one.
    :db/index       true}

   {:db/ident       :eacl.relationship/relation-name
    :db/doc         "EACL Relationship: Relation Name (keyword)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one                     ; was many
    :db/index       true}

   {:db/ident       :eacl.relationship/resource-type
    :db/doc         "EACL: Resource Type Keyword"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/resource
    :db/doc         "EACL Relationship: Ref to Resource"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one                     ; was many
    :db/index       true}

   ;; Relationship Indices (expensive)
   {:db/ident       :eacl.relationship/subject-type+subject+relation-name+resource-type+resource
    :db/doc         "EACL Relationship: Unique identity tuple for lookup-resources."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/subject
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ; This can go away as soon as `can?` uses direct index access. May still be needed, though.
   ;{:db/ident       :eacl.relationship/resource+subject
   ; :db/doc         "EACL Relationship Index: for check-permission reachable? hop. Will go away once can? uses direct index."
   ; :db/valueType   :db.type/tuple
   ; :db/tupleAttrs  [:eacl.relationship/resource
   ;                  :eacl.relationship/subject]
   ; :db/cardinality :db.cardinality/one
   ; :db/index       true}

   ;; Reverse tuple index for efficient reverse traversal
   {:db/ident       :eacl.relationship/resource-type+resource+relation-name+subject-type+subject
    :db/doc         "Reverse tuple index for efficient reverse traversal in can? and arrow permissions"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/resource
                     :eacl.relationship/relation-name
                     :eacl.relationship/subject-type
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity}])

(defn count-relationships-using-relation
  "Counts how many Relationships are using the given Relation.
  The search attrs are indexed, but this can be way faster in v7 using references types,
  or adding a tuple index like :eacl.relationship/resource-type+relation-name+subject-type,
  but that would increase storage & write costs."
  [db {:eacl.relation/keys [resource-type relation-name subject-type]}]
  {:pre [(keyword? resource-type)
         (keyword? relation-name)
         (keyword? subject-type)]}
  ; TODO: throw for invalid Relation not present in schema.
  (or (d/q '[:find (count ?relationship) .
             :in $ ?resource-type ?relation-name ?subject-type
             :where
             [?relationship :eacl.relationship/resource-type ?resource-type]
             [?relationship :eacl.relationship/relation-name ?relation-name]
             [?relationship :eacl.relationship/subject-type ?subject-type]]
        db resource-type relation-name subject-type)
    0))

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
  {:relations   (read-relations db)
   :permissions (read-permissions db)})

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
  (let [before-relations-set (->> before-relations
                               ;(S/setval [S/ALL :db/id] S/NONE) ; no longer needed.
                               (set))
        after-relations-set  (->> after-relations
                               ;(S/setval [S/ALL :db/id] S/NONE)
                               (set))

        before-permissions-set (->> before-permissions
                                 ;(S/setval [S/ALL :db/id] S/NONE)
                                 (set))
        after-permissions-set (->> after-permissions
                                ;(S/setval [S/ALL :db/id] S/NONE)
                                (set))]
    {:relations   (calc-set-deltas before-relations-set after-relations-set)
     :permissions (calc-set-deltas before-permissions-set after-permissions-set)}))

(defn write-schema!
  "Computes delta between existing schema and
  new schema, checks for any orphaned relationships on retracted schema,
  produces tx-ops and applies."
  [conn {:as new-schema-map :keys [relations permissions]}]
  ; what this needs to do:
  ; - [ ] validate new schema
  ; - [x] read current schema
  ; - [ ] compare new vs old schema to get additions & retractions
  ; - validate
  ; - validate inputs for Relations & Permissions
  ; - read current schema
  ; - calculate delta (additions + retractions)
  ; - check if retractions would orphan any relationships, i.e. used by any
  ; - transact additions & retractions. it would still be convenient to
  ; todo: potentially parse Spice schema.
  ; we'll need to support
  ; write-schema can take and validate Relations.
  ;(validate-schema-map! schema-map) ; do we need to conform here?
  (throw (Exception. "not impl WIP"))
  (let [db              (d/db conn)
        existing-schema (read-schema db)
        {:as   schema-deltas
         ; consider naming these deltas
         relation-deltas :relations
         permission-deltas :permissions} (compare-schema existing-schema new-schema-map)
        {relation-additions :additions
         relation-retractions :retractions} relation-deltas
        orphaned-rels   (for [rel-retraction relation-retractions]
                          [rel-retraction (count-relationships-using-relation db rel-retraction)])]
    (doseq [[rel cnt] orphaned-rels]
      (assert (zero? cnt) (str "Relation " rel " would orphan " cnt " relationships.")))
    relation-deltas)
  ; WIP.
  #_(compare-schema existing-schema new-schema-map))