(ns eacl.datomic.schema)

(def v3-schema
  [;; Copied from v2-eacl-schema
   {:db/ident       :resource/type
    :db/doc         "Resource Type on Domain Entities. Used by EACL."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :entity/id ; maybe this should be :id or :object/id or :spice/id?
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

   {:db/ident       :eacl.relation/resource-type+name
    :db/doc         "Tuple to enforce uniqueness of Resource Type + Relation Name, e.g. product/owner relation."
    :db/valueType   :db.type/tuple
    :db/tupleAttrs  [:eacl.relation/resource-type :eacl.relation/relation-name]
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

   {:db/ident       :eacl.permission/relation-name ; For direct relation grant
    :db/doc         "EACL Permission: Name of the direct relation that grants this permission."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; New attributes for Arrow Permissions
   {:db/ident       :eacl.permission/arrow-source-relation-name
    :db/doc         "EACL Arrow Permission: Name of the relation on this resource type that points to another resource where the target permission should be checked."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl.permission/arrow-target-permission-name
    :db/doc         "EACL Arrow Permission: Name of the permission to check on the resource type pointed to by arrow-source-relation."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ;; Relationships (Subject -> Relation -> Resource)
   {:db/ident       :eacl.relationship/subject
    :db/doc         "EACL: Ref to Subject(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one ; was many
    :db/index       true}

   {:db/ident       :eacl.relationship/relation-name
    :db/doc         "EACL Relationship: Relation Keyword"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one ; was many
    :db/index       true}

   {:db/ident       :eacl.relationship/resource
    :db/doc         "EACL Relationship: Ref to Resource(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one ; was many
    :db/index       true}])