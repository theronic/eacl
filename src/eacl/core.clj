(ns eacl.core
  "EACL: Enterprise Access Control based on SpiceDB.
  Poor man's ACL in Datomic suitable for tens of thousands of entities.
  Not cached. Graph traversal can be costly. Has arrow syntax support."
  (:require
   [datomic.api :as d]
   [clojure.string :as string]))

(defmacro with-fresh-conn
  ; todo: switch to newer with-mem-conn.
  "Usage: (with-fresh-conn conn schema (d/db conn))"
  [sym schema & body]
  ; todo: unique test name.
  `(let [datomic-uri# "datomic:mem://test"
         g#           (d/create-database datomic-uri#)
         ~sym (d/connect datomic-uri#)
         rx# @(d/transact ~sym ~schema)]
     (try
       (do ~@body)
       (finally
         (assert (string/starts-with? datomic-uri# "datomic:mem://") "never delete non-mem DB – just in case.")
         (d/release ~sym)
         (d/delete-database datomic-uri#)))))

(defmacro with-mem-conn
  "Like with-open for Datomic (for tests).
  Not under ~/test because needed by other modules.

  1. Creates unique in-memory Datomic.
  2. Transacts schema.
  3. Executes body with conn bound to sym.
  3. Deletes database after.

  Usage:
  (with-mem-conn [conn some-schema]
     @(d/transact conn tx-data)
     (is (= 123 (d/q '[:find ...] (d/db conn)))))"
  [[sym schema] & body]
  `(let [random-uuid# (java.util.UUID/randomUUID)
         datomic-uri# (str "datomic:mem://test-" (.toString random-uuid#))
         g#           (d/create-database datomic-uri#)]     ; can fail, but should not.
     (assert (true? g#) (str "Failed to create in-memory Datomic:" datomic-uri#))
     (let [~sym (d/connect datomic-uri#)]
       (try
         @(d/transact ~sym ~schema)                         ; can fail.
         (do ~@body)
         (finally
           (d/release ~sym)
           (d/delete-database datomic-uri#))))))

(def v3-eacl-schema
  [;; Copied from v2-eacl-schema
   {:db/ident       :resource/type
    :db/doc         "Resource Type on Domain Entities. Used by EACL."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :entity/id ; maybe this should be :id or :object/id or :spice/id?
    :db/doc         "Unique String ID to match SpiceDB Object IDs."
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true
    :db/unique      :db.unique/identity}

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

(def rules
  '[;; Reachability rules for following relationships (copied from core2)
    [(reachable ?resource ?subject)
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?subject]]
    [(reachable ?resource ?subject)
     [?relationship :eacl.relationship/resource ?resource]
     [?relationship :eacl.relationship/subject ?mid]
     (reachable ?mid ?subject)]

    ;; Direct permission check (copied and adapted from core2)
    [(has-permission ?subject ?permission-name ?resource)
     [?resource :resource/type ?resource-type]
     [?relationship :eacl.relationship/resource ?resource] ; subject has some relationship TO the resource
     [?relationship :eacl.relationship/relation-name ?relation-name-in-tuple] ; via this relation in the tuple
     [?relationship :eacl.relationship/subject ?subject]   ; subject of the relationship tuple

     ;; Permission definition: ?relation-name-in-perm-def grants ?permission-name on ?resource-type
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; THIS IS THE DIRECT GRANT

     ;; Match the relation name from the relationship tuple with the one in permission definition
     [(= ?relation-name-in-tuple ?relation-name-in-perm-def)]
     [(not= ?subject ?resource)]]

    ;; Indirect permission inheritance (copied from core2 - may need review/replacement with arrows)
    ;; This rule means: ?subject gets ?permission-name on ?resource if:
    ;; 1. A permission definition exists: for ?resource-type, ?relation-name-in-perm-def grants ?permission-name.
    ;; 2. ?resource has a relationship (as a subject of the tuple) via ?relation-name-in-perm-def to some ?target.
    ;;    (e.g. doc D is "subject" of relation "group" to group G: D --group--> G)
    ;; 3. ?subject can "reach" that ?target (e.g. user U is member of group G).
    [(has-permission ?subject ?permission-name ?resource)
     [?resource :resource/type ?resource-type]

     ;; Permission definition
     [?perm-def :eacl.permission/resource-type ?resource-type]
     [?perm-def :eacl.permission/permission-name ?permission-name]
     [?perm-def :eacl.permission/relation-name ?relation-name-in-perm-def] ; Direct relation specified in perm

     ;; Structural relationship: ?resource is linked to ?target via ?relation-name-in-perm-def
     [?structural-rel :eacl.relationship/subject ?resource]
     [?structural-rel :eacl.relationship/relation-name ?relation-name-in-perm-def]
     [?structural-rel :eacl.relationship/resource ?target]

     (reachable ?target ?subject) ; User must be able to reach the target of the structural relationship
     [(not= ?subject ?resource)]]

    ;; Arrow permission rule: ?subject gets ?perm-name-on-this-resource if it has ?perm-name-on-related on an intermediate resource
    ;; Example: User U gets :admin on VPC_X if VPC_X --:account--> ACC_Y and User U has :admin on ACC_Y.
    ;; MODIFIED based on user feedback: Rule now expects intermediate --via-relation-name--> this-resource
    ;; Example: User U gets :view on SERVER_X if ACC_Y --:account--> SERVER_X and User U has :admin on ACC_Y.
    [(has-permission ?subject ?perm-name-on-this-resource ?this-resource)
     [?this-resource :resource/type ?this-resource-type]

     ;; 1. Find an arrow permission definition for this-resource-type and perm-name-on-this-resource
     [?arrow-perm-def :eacl.permission/resource-type ?this-resource-type]
     [?arrow-perm-def :eacl.permission/permission-name ?perm-name-on-this-resource]
     [?arrow-perm-def :eacl.permission/arrow-source-relation-name ?via-relation-name]  ; e.g., :account (the relation name specified in Permission)
     [?arrow-perm-def :eacl.permission/arrow-target-permission-name ?perm-on-related] ; e.g., :admin (on the intermediate/account)

     ;; 2. Find intermediate resource: ?intermediate-resource --via-relation-name--> ?this-resource
     [?rel-linking-resources :eacl.relationship/subject ?intermediate-resource] ; e.g., account is subject of tuple
     [?rel-linking-resources :eacl.relationship/relation-name ?via-relation-name] ; relation is :account
     [?rel-linking-resources :eacl.relationship/resource ?this-resource]      ; e.g., server/vpc is resource of tuple

     ;; 3. Subject must have the target permission on the intermediate resource (recursive call)
     (has-permission ?subject ?perm-on-related ?intermediate-resource)
     [(not= ?subject ?this-resource)] ; Exclude self-references for safety
     ;; Ensure the intermediate resource is not the same as the subject to prevent some loops,
     ;; though main cycle prevention relies on data structure or more complex rule logic if needed.
     [(not= ?subject ?intermediate-resource)]
     ;; Ensure this-resource is not the same as intermediate for simple arrows like A -> B
     [(not= ?this-resource ?intermediate-resource)]]])

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
   {:eacl.permission/resource-type                resource-type
    :eacl.permission/permission-name              grant-permission
    :eacl.permission/arrow-source-relation-name   arrow-source-relation
    :eacl.permission/arrow-target-permission-name arrow-target-permission}))

(defn Relationship
  "A Relationship between a subject and a resource via Relation. Copied from core2."
  [subject relation-name resource]
  {:pre [(some? subject) (keyword? relation-name) (some? resource)]}
  {:eacl.relationship/resource      resource
   :eacl.relationship/relation-name relation-name
   :eacl.relationship/subject       subject})

(defn can?
  "Returns true if subject has permission on resource. Copied from core2."
  [db subject permission resource]
  (->> (d/q '[:find ?subject .                              ; Using . to find a single value, expecting one or none
              :in $ % ?subject ?perm ?resource
              :where
              (has-permission ?subject ?perm ?resource)] ; do we still needs this?
            db
            rules
            subject
            permission
            resource)
       (boolean)))

(defn can! [db subject permission resource]
  (if (can? db subject permission resource)
    true
    ; todo nicer error message
    (throw (Exception. "Unauthorized"))))

(defn lookup-subjects
  "Enumerates subjects that have a given permission on a specified resource."
  [db {:as            filters
       resource-ident :resource/id
       :keys          [permission]}]
  (let [resource-id (:db/id (d/entity db resource-ident))]
    (->> (d/q '[:find [?subject ...]
                :in $ % ?resource ?perm
                :where
                (has-permission ?subject ?perm ?resource)
                [(not= ?subject ?resource)]]
              db
              rules
              resource-id
              permission)
         (map #(d/entity db %)))))

(defn lookup-resources
  "Find all resources that subject can access with the given permission."
  [db {:as _filters
       subject-ident :subject/id
       :keys [permission]}]
  (->> (d/q '[:find [?resource ...]
              :in $ % ?subject ?perm
              :where
              (has-permission ?subject ?perm ?resource)]
            db
            rules
            subject-ident
            permission)
       (map #(d/entity db %))))