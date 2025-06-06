(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.impl-optimized :as impl]))
    ;[eacl.datomic.impl-indexed :as impl-idx]))


; A central place to configure how IDs and resource types are handled:
; - All SpiceDB objects have a type (string) and a unique ID (string). Spice likes strings.
; - To retain parity with SpiceDB, you can configure EACL to coerce object types & IDs of different
;   types (usually UUIDs) to/from Datomic when returning SpiceObject.
; - By default EACL, uses :entity/id (unique string) and :resource/type (keyword) for objects.
; - Below, you can configure how these are coerced to/from Datomic below.

; this should be passed into the impl.
;(def object-id-attr :entity/id)                             ; we default to :entity/id (string).
;(def resource-type-attr :resource/type)                     ; we default to :resource/type

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

(def Relation base/Relation)
(def Permission base/Permission)
(def Relationship base/Relationship)

;; Use optimized implementation
(def can? impl/can?)
(def can! impl/can!)
(def entity->spice-object impl/entity->spice-object)
(def lookup-subjects impl/lookup-subjects)
(def lookup-resources impl/lookup-resources)
