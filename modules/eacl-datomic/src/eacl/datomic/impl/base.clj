(ns eacl.datomic.impl.base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require [eacl.schema.model :as model]))

(defrecord Cursor [path-index resource])

(def ->relation-id model/->relation-id)
(def Relation model/Relation)
(def ->permission-id model/->permission-id)
(def Permission model/Permission)

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
   :eacl.relationship/resource      (:id resource)
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject-type  (:type subject)
   :eacl.relationship/subject       (:id subject)})
