(ns eacl.datomic.impl-base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic.")

(defrecord Cursor [path-index resource-id])

(defn Relation
  "Defines a relation type. Copied from core2.
  (Relation :product :owner :user) or (Relation :product/owner :user)"
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type) (keyword? relation-name) (keyword? subject-type)]}
   {:eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? subject-type)]}
   (Relation
    (keyword (namespace resource-type+relation-name))
    (keyword (name resource-type+relation-name))
    subject-type)))

(defn Permission
  "Defines how a permission is granted.
  Arity 1 (direct relation from namespaced keyword): (Permission :document/owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 2 (direct relation): (Permission :document :owner :view)
    => For :document, relation :owner grants :view permission.
  Arity 4 (arrow relation): (Permission :vpc :admin :account :admin :account)
    => For :vpc, :admin permission is granted if subject has :admin on the resource of type :account linked by vpc's :account relation."
  ;; Arity 1: Direct grant, from namespaced keyword resource-type/relation-name
  ([resource-type+relation-name permission-to-grant]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? permission-to-grant)]}
   (let [resource-type (keyword (namespace resource-type+relation-name))
         relation-name (keyword (name resource-type+relation-name))]
     ;; Call arity 2
     (Permission resource-type relation-name permission-to-grant)))
  ;; Arity 2: Direct grant
  ([resource-type direct-relation-name permission-to-grant]
   {:pre [(keyword? resource-type) (keyword? direct-relation-name) (keyword? permission-to-grant)]}
   {:eacl.permission/resource-type resource-type
    :eacl.permission/permission-name permission-to-grant
    :eacl.permission/relation-name direct-relation-name})
  ;; Arity 4: Arrow grant
  ([resource-type arrow-source-relation arrow-target-permission grant-permission]
   {:pre [(keyword? resource-type) (keyword? grant-permission)
          (keyword? arrow-source-relation) (keyword? arrow-target-permission)]}
   {:eacl.arrow-permission/resource-type resource-type
    :eacl.arrow-permission/permission-name grant-permission
    :eacl.arrow-permission/source-relation-name arrow-source-relation
    :eacl.arrow-permission/target-permission-name arrow-target-permission}))

(defn Relationship
  "A Relationship between a subject and a resource via Relation. Copied from core2."
  [subject relation-name resource]
  ; :pre can be expensive.
  {:pre [(:id subject)
         (:type subject)
         (keyword? relation-name)
         (:id resource)
         (:type resource)]}
  {:eacl.relationship/resource-type (:type resource)
   :eacl.relationship/resource (:id resource)
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject-type (:type subject)
   :eacl.relationship/subject (:id subject)})
