(ns eacl.datomic.impl
  "EACL: Enterprise Access Control. Spice-compatible authorization system in Datomic."
  (:require
    [datomic.api :as d]
    [clojure.string :as string]
    [eacl.core :as proto :refer [spice-object]]))

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
  "Returns true if subject has permission on resource. Copied from core2.
  Note: we are not checking subject & resource types, but we probably should."
  [db subject-id permission resource-id]
  (if-not (and (:db/id (d/entity db subject-id))
               (:db/id (d/entity db resource-id)))
    false
    (->> (d/q '[:find ?subject .                            ; Using . to find a single value, expecting one or none
                :in $ % ?subject ?perm ?resource
                :where
                (has-permission ?subject ?perm ?resource)]  ; do we still needs this?
              db
              rules
              subject-id
              permission
              resource-id)
         (boolean))))

(defn can! [db subject-id permission resource-id]
  (if (can? db subject-id permission resource-id)
    true
    ; todo nicer error message
    (throw (Exception. "Unauthorized"))))

(defn entity->spice-object [ent]
  (spice-object (:resource/type ent) (:entity/id ent)))

(defn lookup-subjects
  "Enumerates subjects that have a given permission on a specified resource."
  [db {:as         filters
       resource-id :resource/id
       :keys       [permission]}]
  (if-let [resource-eid (:db/id (d/entity db [:entity/id resource-id]))]
    (->> (d/q '[:find [?subject ...]
                :in $ % ?resource-eid ?perm
                :where
                (has-permission ?subject ?perm ?resource-eid)
                [(not= ?subject ?resource-eid)]]
              db
              rules
              resource-eid
              permission)
         (map #(d/entity db %))
         (map entity->spice-object))
    []))

(defn lookup-resources
  "Find all resources that subject can access with the given permission."
  [db {:as        _filters
       subject-id :subject/id
       :keys      [permission]}]
  (if-let [subject-eid (:db/id (d/entity db [:entity/id subject-id]))]
    (->> (d/q '[:find [?resource ...]
                :in $ % ?subject-eid ?perm
                :where
                (has-permission ?subject-eid ?perm ?resource)]
              db
              rules
              subject-eid
              permission)
         (map #(d/entity db %))
         (map entity->spice-object))
    []))