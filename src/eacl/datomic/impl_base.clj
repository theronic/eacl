(ns eacl.datomic.impl-base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic.")

(defrecord Cursor [path-index resource])

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
  "Defines how a permission is granted using the unified permission schema.
  
  Direct permission: (Permission resource-type {:relation relation-name} permission-name)
    => For resource-type, relation grants permission
  
  Arrow to permission: (Permission resource-type {:arrow source-relation :permission target-permission} permission-name)
    => For resource-type, permission is granted if subject has target-permission on the resource 
       of type linked by source-relation
  
  Arrow to relation: (Permission resource-type {:arrow source-relation :relation target-relation} permission-name)
    => For resource-type, permission is granted if subject has target-relation on the resource 
       linked by source-relation"
  [resource-type permission-name spec]
  {:pre [(keyword? resource-type)
         (keyword? permission-name)
         (map? spec)]}  ;(or (map? spec) (keyword? spec))
  (cond
    ;; Arrow permission: {:arrow source-relation :permission target-permission}
    (and (:arrow spec) (:permission spec))
    {:eacl.permission/resource-type resource-type
     :eacl.permission/permission-name permission-name
     :eacl.permission/source-relation-name (:arrow spec)
     :eacl.permission/target-type :permission
     :eacl.permission/target-name (:permission spec)}

    ;; Arrow permission: {:arrow source-relation :relation target-relation}
    (and (:arrow spec) (:relation spec))
    {:eacl.permission/resource-type resource-type
     :eacl.permission/permission-name permission-name
     :eacl.permission/source-relation-name (:arrow spec)
     :eacl.permission/target-type :relation
     :eacl.permission/target-name (:relation spec)}

    ;; Direct permission: {:relation relation-name}
    (:relation spec)
    {:eacl.permission/resource-type resource-type
     :eacl.permission/permission-name permission-name
     :eacl.permission/target-type :relation
     :eacl.permission/target-name (:relation spec)}

    :else
    (throw (ex-info "Invalid Permission spec. Expected {:relation name} or {:arrow source :permission target} or {:arrow source :relation target}"
                    {:spec spec}))))

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
