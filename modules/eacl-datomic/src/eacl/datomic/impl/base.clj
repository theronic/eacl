(ns eacl.datomic.impl.base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require [eacl.schema.model :as model]))

(def ->relation-id model/->relation-id)
(def Relation model/Relation)
(def ->permission-id model/->permission-id)
(def Permission model/Permission)

;; NOTE: the v6-era `Relationship` fn (emitting :eacl.relationship/* entity
;; attrs) was removed: those attributes do not exist in the v7 schema, so any
;; transact of its output failed with :db.error/not-an-entity. Use
;; eacl.datomic.impl/Relationship (data) + eacl.datomic.impl/tx-relationship
;; (tx-data) instead.
