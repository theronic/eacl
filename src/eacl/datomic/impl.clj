(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [datomic.api :as d]
    [eacl.core :as proto :refer [spice-object]]
    [eacl.datomic.rules :as rules]
    [eacl.datomic.rules-optimized :as rules-opt]
    [eacl.datomic.impl-optimized :as impl-opt]))

(defn id->identifier
  "This is to support custom unique entity IDs."
  [entity-id]
  [:entity/id entity-id])

; A central place to configure how IDs and resource types are handled:
; - All SpiceDB objects have a type (string) and a unique ID (string). Spice likes strings.
; - To retain parity with SpiceDB, you can configure EACL to coerce object types & IDs of different
;   types (usually UUIDs) to/from Datomic when returning SpiceObject.
; - By default EACL, uses :entity/id (unique string) and :resource/type (keyword) for objects.
; - Below, you can configure how these are coerced to/from Datomic below.

; this should be passed into the impl.
(def object-id-attr :entity/id)                             ; we default to :entity/id (string).
(def resource-type-attr :resource/type)                     ; we default to :resource/type

; To support other types of IDs that can be coerced to/from string-formattable entity IDs, than UUIDs

;(defn id->datomic
;  "EACL uses unique string ID under :entity/id"
;  [id] (identity id))
;
;(defn datomic->id [id] (identity id))

;; Graph Traversal Strategy to resolve permissions between subjects & resources:
;; - schema is typically small, i.e. we have a low number of relations and permissions
;; - resources (like servers) are typically far more numerous than subjects (like users or accounts)
;;

(defn Relation
  "Defines a relation type. Copied from core2.
  (Relation :product :owner :user) or (Relation :product/owner :user)"
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type) (keyword? relation-name) (keyword? subject-type)]}
   {:eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type  subject-type})
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
  Arity 3 (arrow relation): (Permission :vpc :admin :account :admin)
    => For :vpc, :admin permission is granted if subject has :admin on the resource linked by vpc's :account relation."

  ;; Arity 1: Direct grant, from namespaced keyword resource-type/relation-name
  ([resource-type+relation-name permission-to-grant]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? permission-to-grant)]}
   (let [rt (keyword (namespace resource-type+relation-name))
         rn (keyword (name resource-type+relation-name))]
     ;; Call arity 2
     (Permission rt rn permission-to-grant)))

  ;; Arity 2: Direct grant
  ([resource-type direct-relation-name permission-to-grant]
   {:pre [(keyword? resource-type) (keyword? direct-relation-name) (keyword? permission-to-grant)]}
   {:eacl.permission/resource-type   resource-type
    :eacl.permission/permission-name permission-to-grant
    :eacl.permission/relation-name   direct-relation-name})

  ;; Arity 3: Arrow grant
  ([resource-type
    arrow-source-relation arrow-target-permission
    grant-permission]
   {:pre [(keyword? resource-type) (keyword? grant-permission)
          (keyword? arrow-source-relation) (keyword? arrow-target-permission)]}
   {:eacl.arrow-permission/resource-type          resource-type
    :eacl.arrow-permission/permission-name        grant-permission
    :eacl.arrow-permission/source-relation-name   arrow-source-relation
    :eacl.arrow-permission/target-permission-name arrow-target-permission}))

(defn Relationship
  "A Relationship between a subject and a resource via Relation. Copied from core2."
  [subject relation-name resource]
  {:pre [(some? subject) (keyword? relation-name) (some? resource)]}
  {:eacl.relationship/resource      resource
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject       subject})

;; Use optimized implementation
(def can? impl-opt/can?)
(def can! impl-opt/can!)
(def entity->spice-object impl-opt/entity->spice-object)
(def lookup-subjects impl-opt/lookup-subjects)
(def lookup-resources impl-opt/lookup-resources)
