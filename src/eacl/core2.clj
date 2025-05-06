(ns eacl.core2
  "EACL: Enterprise Access Control based on SpiceDB.
  Poor man's ACL in Datomic suitable for tens of thousands of entities.
  Not cached. Graph traversal can be costly."
  (:require
    [datomic.api :as d]
    [clojure.string :as string]
    [criterium.core :as crit]))

; EACL is based on SpiceDB.
; You have Subjects & Resources.
; You defined a Relation on a resource, e.g. :product/owner, which confers a set of permissions, e.g. :product/view, :product/delete,etc.
; Then you create Relationships between Subjects & Resources, which has {:keys [subject relation resource]}.
; If a subject can reach a resource via a relation, the permissions from that relation is conferred on the subject.
;
; The most annoying part of the design right now is that you have to specify all relevant resources on a relation,
; because we don't have resource types like SpiceDB, or a collective grouping like 'products'.
;
; EACL does not support ZedTokens ala Zookies and makes no claims about being fast, but it is flexible.
; SpiceDB is heavily optimised to maintain a consistent cache.

; Note that subject.relation is not currently supported.

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

(def v2-eacl-schema
  [{:db/ident       :resource/type
    :db/doc         "Resource Type(s) (keyword), e.g. :server or :vpc."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one ; only one resource type for now.
    :db/index       true}

   {:db/ident       :eacl/resource
    :db/doc         "EACL: Ref to Resource(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/resource-type
    :db/doc         "EACL: Resource Types"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many ; one or many?
    :db/index       true}

   {:db/ident       :eacl/subject
    :db/doc         "EACL: Subject(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl.relation/name ; why not eacl/relation-name?
    :db/doc         "EACL: Relation Name (keyword)" ; string may be better.
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   ; todo: make resource type + relation name unique. Tuple?
   
   {:db/ident       :eacl/subject-type
    :db/doc         "EACL Relation: Subject Type(s) (keyword), e.g. :user."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/relation
    :db/doc         "EACL Permission: Relation(s) (keyword)" ; why not?
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/permission
    :db/doc         "Permission(s) conferred via this Relation."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/index       true}])

; why not use Records for this?
(defn Relation
  "A Relation applies to a Resource (todo: Resource Type), and confers a set of permissions
   to all subjects 'related' to via have Relationships with this Relation.
  This is equivalent to Spice schema

  ```
  definition product { ; product is resource type
    relation owner: user ; we don't constrain type of user (maybe we should?).
    permission view = owner
  }
  ```

  (Relation :product :owner [:view]), where
   - :product is :resource/type.
   - :owner is `relation owner = ...`
   - perms is seq of permissions (keywords).

  permissions can be single or multi-arity.
  "
  [resource-type relation-ident permissions]
  {:resource/type   resource-type
   :eacl/relation   relation-ident
   :eacl/permission permissions})

(defn Relationship
  "A Relationship relates a subject and a resource via a Relation (see above).
   Subjects inherit permissions for a resource via a Relation."
  [subject relation-ident resource]
  {:eacl/subject  subject
   :eacl/relation relation-ident
   :eacl/resource resource})

(def rules
  '[;; Reachability rules for following relationships
    [(reachable ?r ?s)
     [?rel :eacl/subject ?s]
     [?rel :eacl/resource ?r]]
    [(reachable ?r ?s)
     [?rel :eacl/subject ?mid]
     [?rel :eacl/resource ?r]
     (reachable ?mid ?s)]

    ;; Direct permission check
    [(has-permission ?subject ?resource ?perm)
     [?p :eacl/resource ?resource]
     [?p :eacl/permission ?perm]
     [?p :eacl/relation ?rel-type]
     [?rel :eacl/subject ?subject]                          ; subject directly has relation
     [?rel :eacl/relation ?rel-type]
     [?rel :eacl/resource ?resource]                        ; to the resource
     [(not= ?subject ?resource)]]                           ; exclude self-reference

    ;; Indirect permission inheritance
    [(has-permission ?subject ?resource ?perm)
     [?p :eacl/resource ?resource]
     [?p :eacl/permission ?perm]
     [?p :eacl/relation ?rel-type]
     [?rel :eacl/subject ?resource]
     [?rel :eacl/relation ?rel-type]
     [?rel :eacl/resource ?target]
     (reachable ?target ?subject)
     [(not= ?subject ?resource)]]])

(defn can?
  "Returns true if subject has permission on resource."
  [db subject permission resource]
  (->> (d/q '[:find ?subject .
              :in $ % ?subject ?perm ?resource
              :where
              (has-permission ?subject ?resource ?perm)]
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
  [db resource permission]
  (->> (d/q '[:find [?subject ...]
              :in $ % ?resource ?perm
              :where
              (has-permission ?subject ?resource ?perm)]
            db
            rules
            resource
            permission)
       (map #(d/entity db %))))

(defn lookup-resources
  "Find all resources that subject can access with the given permission."
  [db subject permission]
  (->> (d/q '[:find [?resource ...]
              :in $ % ?subject ?perm
              :where
              (has-permission ?subject ?resource ?perm)]
            db
            rules
            subject
            permission)
       (map #(d/entity db %))))

; prospective
;(defrecord rRelation [type relation permission])
;(defrecord rRelationship [subject relation resource])
