(ns eacl.datomic.schema)

(def v4-schema
  [{:db/ident       :resource/type
    :db/doc         "Resource Type on Domain Entities. Used by EACL."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :entity/id                              ; todo: figure out how to support :id, :object/id or :spice/id of different types.
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

   {:db/ident       :eacl.relation/resource-type+relation-name ; + subject-type?
    :db/doc         "EACL Relation: Unique identity tuple to enforce uniqueness of Resource Type + Relation Name, e.g. product/owner relation."
    ; this won't work if we want to support multiple subject types per relation.
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relation/resource-type
                     :eacl.relation/relation-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; Permissions
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

   {:db/ident       :eacl.permission/relation-name          ; For direct relation grant
    :db/doc         "EACL Permission: Name of the direct relation that grants this permission."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/resource-type+relation-name+permission-name
    :db/doc         "EACL Permission: Unique identity tuple to enforce uniqueness of [Resource Type + Relation + Permission]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/relation-name
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; Arrow-syntax Permissions, e.g. `permission reboot = owner + account->admin`.

   {:db/ident       :eacl.arrow-permission/resource-type
    :db/doc         "EACL Arrow Permission: Resource Type"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.arrow-permission/permission-name
    :db/doc         "EACL Arrow Permission: Permission Name"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ; suspect we also need to store the related resource type here, which is known.

   {:db/ident       :eacl.arrow-permission/source-relation-name
    :db/doc         "EACL Arrow Permission: Name of the relation on this resource type that points to another resource where the target permission should be checked."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.arrow-permission/target-permission-name
    :db/doc         "EACL Arrow Permission: Name of the permission to check on the resource type pointed to by arrow-source-relation."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; For arrow permission traversal
   {:db/ident       :eacl.arrow-permission/resource-type+permission-name
    :db/doc         "Index for arrow permission lookups"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.arrow-permission/resource-type
                     :eacl.arrow-permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.arrow-permission/resource-type+target-permission-name
    :db/doc         "Index for arrow permission lookups"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.arrow-permission/resource-type
                     :eacl.arrow-permission/target-permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ; Arrow Permission Uniqueness Constraint
   {:db/ident       :eacl.arrow-permission/resource-type+source-relation-name+target-permission-name+permission-name
    :db/doc         "EACL Permission: Unique identity tuple to enforce uniqueness of [Resource Type + Source Relation + Target Permission + Permission Grant]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.arrow-permission/resource-type
                     :eacl.arrow-permission/source-relation-name
                     :eacl.arrow-permission/target-permission-name
                     :eacl.arrow-permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; Relationships (Subject -> Relation -> Resource)
   {:db/ident       :eacl.relationship/subject-type
    :db/doc         "EACL: Subject Type Keyword"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject
    :db/doc         "EACL: Ref to Subject"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one                     ; was many
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

   ;; Relationship Indices
   ;; Some of these may be overkill and no longer needed.

   {:db/ident       :eacl.relationship/subject+resource-type
    :db/doc         "EACL Relationship: Tuple Index on [Subject Type + Resource Type]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject
                     :eacl.relationship/resource-type]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/resource-type+relation-name
    :db/doc         "EACL Relationship: Tuple Index on [Resource Type + Relation Name]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/relation-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject-type+relation-name
    :db/doc         "EACL Relationship: Tuple Index on [Subject Type + Relation Name]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/relation-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/resource-type+relation-name+subject
    :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource-type
                     :eacl.relationship/relation-name
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject-type+relation-name+resource-type
    :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject-type
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource-type]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/subject+relation-name+resource-type
    :db/doc         "EACL Relationship: Tuple Index on [Subject + Relation + Resource Type]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource-type]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/resource+relation-name+subject-type
    :db/doc         "EACL Relationship: Tuple Index on [Resource + Relation + Subject Type]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource
                     :eacl.relationship/relation-name
                     :eacl.relationship/subject-type]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ; do we still need this?
   {:db/ident       :eacl.relationship/resource+subject
    :db/doc         "EACL Relationship: Unique identity tuple to enforce uniqueness of [Subject + Relation + Resource]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/index       true}
   ;:db/unique      :db.unique/identity}

   ;; hmm I think we're going to need relationship indices on subject & resource types
   ; do we need this?
   ;{:db/ident       :eacl.relationship/resource+relation-name
   ; :db/doc         "EACL Relationship: Unique identity tuple to enforce uniqueness of [Subject + Relation + Resource]."
   ; :db/valueType   :db.type/tuple
   ; :db/tupleAttrs  [:eacl.relationship/resource
   ;                  :eacl.relationship/relation-name]
   ; :db/cardinality :db.cardinality/one
   ; :db/index       true}

   {:db/ident       :eacl.relationship/resource+relation-name+subject
    :db/doc         "EACL Relationship: Unique identity tuple to enforce uniqueness of [Subject + Relation + Resource]."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/resource
                     :eacl.relationship/relation-name
                     :eacl.relationship/subject]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :eacl.relationship/subject+relation-name+resource
    :db/doc         "EACL Relationship: Unique identity tuple for lookup-resources."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   {:db/ident       :eacl.relationship/subject+relation-name+resource-type+resource
    :db/doc         "EACL Relationship: Unique identity tuple for lookup-resources."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject
                     :eacl.relationship/relation-name
                     :eacl.relationship/resource-type
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}

   ;; NEW PERFORMANCE OPTIMIZATION INDICES
   
   ;; Critical for subject-based lookups
   {:db/ident       :eacl.relationship/subject+relation-name
    :db/doc         "Index for efficient subject-based lookups"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/subject
                     :eacl.relationship/relation-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.relationship/relation-name+resource
    :db/doc         "Index for relation-based traversal"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relationship/relation-name
                     :eacl.relationship/resource]
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; Index for permission lookups by type
   {:db/ident       :eacl.permission/resource-type+permission-name
    :db/doc         "Index for finding all relations that grant a permission"
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.permission/resource-type
                     :eacl.permission/permission-name]
    :db/cardinality :db.cardinality/one
    :db/index       true}])


