(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [eacl.datomic.impl-base :as base]
    [eacl.datomic.impl-optimized :as impl-optimized]
    [eacl.datomic.impl-indexed :as impl-indexed]))

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

;; Use indexed implementation for better performance with large offsets
(def can? impl-optimized/can?)
(def can! impl-optimized/can!)
(def entity->spice-object impl-optimized/entity->spice-object)
(def lookup-subjects impl-optimized/lookup-subjects)
;(def lookup-resources impl/lookup-resources)
;(def lookup-resources impl-grok/lookup-resources)

(def lookup-resources impl-indexed/lookup-resources)