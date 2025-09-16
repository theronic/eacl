(ns eacl.datomic.impl.base
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic.")

(defrecord Cursor [path-index resource])

(defn ->relation-id
  "Uses (str kw) instead of (name kw) to retain namespaces. Leading colons are expected."
  [resource-type relation-name subject-type]
  (str "eacl.relation:" resource-type ":" relation-name ":" subject-type))

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
   {:eacl/id                     (->relation-id resource-type relation-name subject-type)
    :eacl.relation/resource-type resource-type
    :eacl.relation/relation-name relation-name
    :eacl.relation/subject-type  subject-type})
  ([resource-type+relation-name subject-type]
   {:pre [(keyword? resource-type+relation-name) (namespace resource-type+relation-name) (keyword? subject-type)]}
   (Relation
     (keyword (namespace resource-type+relation-name))
     (keyword (name resource-type+relation-name))
     subject-type)))

(defn ->permission-id
  "Uses (str kw) instead of (name kw) to retain namespaces. Leading colons are expected."
  [resource-type permission-name arrow target-type relation-or-permission]
  (str "eacl:permission:" resource-type ":" permission-name ":" arrow ":" target-type ":" relation-or-permission))

(defn Permission
  "Defines a Permission via

    (Permission :resource_type :permission_name spec)

  Where `spec` describes one of:
   1. Direct Relation  {:relation local_relation}
   2. Arrow Relation   {:arrow relation, :relation via-relation}
   3. Arrow Permission {:arrow relation, :permission via-permission}
   4. Self Permission  {:permission other_permission}

  Note that EACL does not detect or prevent cycles in schema (big todo!).

  E.g. given the following SpiceDB definition:

  definition user {}

  definition account {
    relation owner: user

    permission admin = owner
  }

  definition product {
    relation account: account

    permission direct_permission  = account
    permission arrow_relation     = account->owner
    permission arrow_permission   = account->admin
    permission self_permission    = direct_relation

    permission union_permission   = account + account->admin
  }

  In EACL, this would be modelled as:

    (Relation :account :owner :user)
    (Relation :product :account :account)

    (Permission :product :direct_relation  {:relation :account})
    (Permission :product :arrow_relation   {:arrow :account :relation :owner})
    (Permission :product :arrow_permission {:arrow :account :permission :admin})
    (Permission :product :self_permission  {:permission :admin})

  For a self permission, the omitted :arrow is inferred as :self (reserved word).

  In the future, when EACL manages schema via write-schema!, arrow specs will be
  able to support :arrow->permission or :arrow->relation syntax."
  [resource-type permission-name
   {:as   spec
    :keys [arrow relation permission]
    :or   {arrow :self}}]                                   ; default to :self if no arrow relation specified.
  {:pre [(keyword? resource-type)
         (keyword? permission-name)
         (map? spec)
         (or relation permission)
         (not (and relation permission))]}                  ; a permission resolves via a relation or a permission, but not both.
  ; confusion here between permission & permission-name.
  (cond
    ;; Direct permission: {:relation relation-name}
    relation
    ; id format: 'eacl:permission:{resource-type}:{permission-name}:{arrow}:{:relation|:permission}:{target-name}'
    {:eacl/id                              (->permission-id resource-type permission-name arrow :relation relation)
     :eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow            ; this can be :self.
     :eacl.permission/target-type          :relation
     :eacl.permission/target-name          relation}

    ;; Arrow permission: {:arrow source-relation :permission target-permission}
    permission
    {:eacl/id                              (->permission-id resource-type permission-name arrow :permission permission)
     :eacl.permission/resource-type        resource-type
     :eacl.permission/permission-name      permission-name
     :eacl.permission/source-relation-name arrow            ; this can be :self.
     :eacl.permission/target-type          :permission
     :eacl.permission/target-name          permission}

    :else
    (throw (ex-info "Invalid Permission spec. Expected one of {:relation name}, {:permission name}, {:arrow source :permission target} or {:arrow source :relation target}"
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
