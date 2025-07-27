(ns eacl.datomic.impl.base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic.")

(defrecord Cursor [path-index resource])

(defn Relation
  "Defines a relation type. Copied from core2.
  (Relation :product :owner :user) or (Relation :product/owner :user)"
  ([resource-type relation-name subject-type]
   {:pre [(keyword? resource-type)
          (keyword? relation-name)
          (keyword? subject-type)
          ; :self is reserved word.
          (not= resource-type :self)
          (not= relation-name :self)]}
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
  "Defines how a permission is granted using the unified permission schema.
  
  Direct permission: (Permission resource-type permission-name {:relation relation-name})
    => For resource-type, relation grants permission
  
  Arrow to permission: (Permission resource-type permission-name {:arrow source-relation :permission target-permission})
    => For resource-type, permission is granted if subject has target-permission on the resource 
       of type linked by source-relation
  
  Arrow to relation: (Permission resource-type permission-name {:arrow source-relation :relation target-relation})
    => For resource-type, permission is granted if subject has target-relation on the resource 
       linked by source-relation"
  [resource-type permission-name
   {:as spec
    :keys [arrow relation permission]
    :or {arrow :self}}] ; default to :self if no arrow relation specified.
  {:pre [(keyword? resource-type)
         (keyword? permission-name)
         (map? spec)
         (not (and relation permission))]} ; a permission resolves via a relation or a permission, but not both.
  (cond
    ;; Direct permission: {:relation relation-name}
    relation
    {:eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow ; this can be :self.
     :eacl.permission/target-type          :relation
     :eacl.permission/target-name          relation}

    ;; Arrow permission: {:arrow source-relation :permission target-permission}
    permission
    {:eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow ; this can be :self.
     :eacl.permission/target-type          :permission
     :eacl.permission/target-name          permission}

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
   :eacl.relationship/resource      (:id resource)
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject-type  (:type subject)
   :eacl.relationship/subject       (:id subject)})
