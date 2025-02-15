(ns eacl.core
  "EACL: Enterprise Access Control based on SpiceDB.
  Poor man's ACL in Datomic suitable for tens of thousands of entities.
  Not cached. Graph traversal is costly."
  (:require
    [datomic.api :as d]
    [hyperfiddle.rcf :refer (tests tap %)]
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

; enable RCF tests macro:
(comment (hyperfiddle.rcf/enable!))

(defmacro with-fresh-conn
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
  [{:db/ident       :eacl/resource
    :db/doc         "Resource(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/subject
    :db/doc         "Subject(s)"
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl.relation/name
    :db/doc         "Relation Name (keyword. Should this be string?)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}

   {:db/ident       :eacl/relation
    :db/doc         "Relation (keyword)"
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/index       true}

   {:db/ident       :eacl/permission
    :db/doc         "Permission(s) assigned by this relation."
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/index       true}])

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

(defn Relation
  "Relation applies to a Resource and confers a set of permissions.
  This is equivalent to Spice schema

  ```
  definition product {
    relation owner: user ; we don't have types in this design.
    permission view = owner
  }
  ```"
  [relation resource permission]
  {:eacl/resource   resource
   :eacl/relation   relation
   :eacl/permission permission})

(defn Relationship [subject relation resource]
  {:eacl/subject  subject
   :eacl/relation relation
   :eacl/resource resource})

(tests
  (with-fresh-conn conn v2-eacl-schema
    @(d/transact conn
                 [; Company
                  {:db/id    "company-1"
                   :db/ident :test/company}

                  {:db/id    "company-2"
                   :db/ident :test/company2}

                  ; Team
                  {:db/id    "team-1"
                   :db/ident :test/team}

                  {:db/id    "team-2"
                   :db/ident :test/team2}

                  ;{:db/id "products"
                  ; :db/ident :type/products}

                  ;; Products
                  {:db/id    "product-1"
                   :db/ident :test/product}

                  {:db/id    "product-2"
                   :db/ident :test/product2}

                  ; the annoying part of current design is that you need to specify all resources for a relation.
                  ; I'm looking into a resource type or collective resource e.g. 'products'.
                  (Relation :product/company ["product-1" "product-2"] [:product/view :product/edit])
                  (Relation :product/owner ["product-1" "product-2"] [:product/view :product/edit :product/delete])

                  ;; Users:
                  {:db/id    "user-1"
                   :db/ident :test/user}

                  {:db/id    "user-2"
                   :db/ident :test/user2}

                  ;; Relationships:
                  (Relationship "product-1" :product/company "company-1")
                  (Relationship "product-2" :product/company "company-2")

                  ;; Team Membership:
                  (Relationship "user-1" :team/member "team-1") ; User 1 is on Team 1
                  ;(Relationship "user-2" :team/member "team-2")

                  ; User 2 is the direct :product/owner of Product 2
                  (Relationship "user-2" :product/owner "product-2")

                  ; Team 1 has control of Company 1
                  (Relationship "team-1" :team/company "company-1")
                  (Relationship "team-2" :team/company "company-2")])

    (let [db (d/db conn)]
      ":test/user can view their product"
      (can? db :test/user :product/view :test/product) := true
      "...but :test/user2 can't."
      (can? db :test/user2 :product/view :test/product) := false

      "Sanity check that relations don't affect wrong resources"
      (can? db :test/user :product/view :test/company) := false

      "User 2 can view Product 2"
      (can? db :test/user2 :product/view :test/product2) := true

      "User 2 can delete Product 2 because they have product.owner relation"
      (can? db :test/user2 :product/delete :test/product2) := true
      "...but not :test/user"
      (can? db :test/user :product/delete :test/product2) := false

      "We can enumerate subjects that can access a resource."
      ; Bug: currently returns the subject itself which needs a fix.
      (map :db/ident (lookup-subjects db :test/product :product/view)) := [:test/user :test/team :test/product]
      ":test/user2 is only subject who can delete :test/product2"
      (map :db/ident (lookup-subjects db :test/product2 :product/delete)) := [:test/user2]

      "We can enumerate resources with lookup-resources"
      (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product]
      (map :db/ident (lookup-resources db :test/user2 :product/view)) := [:test/product2])

    "Make user-1 a :product/owner of product-2"
    @(d/transact conn [(Relationship :test/user :product/owner :test/product2)])

    (let [db (d/db conn)]
      "Now :test/user can also :product/delete product 2"
      (can? db :test/user :product/delete :test/product2) := true

      (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product :test/product2]
      (map :db/ident (lookup-subjects db :test/product2 :product/delete)) := [:test/user :test/user2])

    "Now let's delete all :product/owner Relationships for :test/user2"
    (let [rels (d/q '[:find [(pull ?rel [* {:eacl/subject [*]}]) ...]
                      :where
                      [?rel :eacl/subject :test/user2]
                      [?rel :eacl/relation :product/owner]]
                    (d/db conn))]

      @(d/transact conn (for [rel rels] [:db.fn/retractEntity (:db/id rel)])))

    "Now only :test/user can access both products."
    (let [db (d/db conn)]
      (map :db/ident (lookup-subjects db :test/product2 :product/view)) := [:test/user :test/team2 :test/product2]
      (map :db/ident (lookup-resources db :test/user2 :product/view)) := []

      (can? db :test/user2 :product/delete :test/product2) := false

      ":test/user permissions unchanged"
      (map :db/ident (lookup-resources db :test/user :product/view)) := [:test/product :test/product2])))