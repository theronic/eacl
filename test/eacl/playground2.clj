(ns eacl.playground2
  (:require [datomic.api :as d]
            [clojure.test :as t :refer [deftest testing is]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl-base :refer [Relation Permission]] ; just for tests.
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server ->account]]
            ;[eacl.]
            [clojure.tools.logging :as log]))

(declare conn)

(comment

  (d/delete-database datomic-uri)

  (def datomic-uri "datomic:mem://eacl-playground")
  (d/create-database datomic-uri)
  (def conn (d/connect datomic-uri))
  @(d/transact conn schema/v5-schema)
  @(d/transact conn fixtures/base-fixtures))

(defn find-relation-permissions
  "Returns matching permissions where :target-type is :relation."
  [db resource-type permission]
  ; todo avoid cycles by checking seen perm-def.
  (d/q '[:find [(pull ?perm-def [*]) ...]                       ; todo: can we return values here more directly?
         :in $ ?resource-type ?permission
         :where
         ; todo: tuple optimization.
         [?perm-def :eacl.permission/resource-type ?resource-type]
         [?perm-def :eacl.permission/permission-name ?permission]
         [?perm-def :eacl.permission/target-type :relation]
         [?perm-def :eacl.permission/source-relation-name ?source-relation]] ; via-relation rather?
       db resource-type permission))

(defn find-arrow-permissions
  "Arrows permission means either,
  permission thing = relation->relation | permission
  or
  permission thing = self->relation | permission.

  Self-relational permissions are still considered arrow permissions. Better name?"
  ; todo combine these two queries (find-relation-permissions) and group-by target-type. saves a query
  [db resource-type permission]
  ; todo avoid cycles by checking seen perm-def.
  (d/q '[:find [(pull ?perm-def [*]) ...]                       ; todo: can we return values here more directly?
         :in $ ?resource-type ?permission
         :where
         ; todo: tuple optimization.
         [?perm-def :eacl.permission/resource-type ?resource-type]
         [?perm-def :eacl.permission/permission-name ?permission]
         ;[?perm-def :eacl.permission/target-type :permission]
         [?perm-def :eacl.permission/source-relation-name ?source-relation]] ; via-relation rather?
       db resource-type permission))

(comment
  (find-relation-permissions (d/db conn) :server :view)
  (find-arrow-permissions (d/db conn) :server :view))

(defn get-relation-subject-type
  "Given a resource type and relation name, returns the target subject type, which becomes an inferred resource type."
  [db resource-type source-relation-name]
  (d/q '[:find ?subject-type .
         :in $ ?resource-type ?source-relation
         :where
         [?relation :eacl.relation/resource-type ?resource-type]
         [?relation :eacl.relation/relation-name ?source-relation]
         [?relation :eacl.relation/subject-type ?subject-type]]
       db resource-type source-relation-name))

(defn resolve-permissions-recursively
  "_subject-type is an optimization that is not currently used because permissions do not capture the subject type,
  but it could and shrink the search graph, i.e. only search along paths we know involves this subject-type.
  However, that requires that EACL manages schema holistically, resolving arrow relation types and storing. It does not do this yet."
  [db paths _subject-type permission initial-resource-type]
  (log/debug 'resolve-permissions-recursively initial-resource-type permission)
  (let [;direct-relations     (find-relation-permissions db initial-resource-type permission)
        arrow-permissions    (find-arrow-permissions db initial-resource-type permission)
        ; ok bug here in that direct & arrow return dupes, because
        ; can we not just return all of it?
        combined-permissions arrow-permissions] ; (concat direct-relations arrow-permissions)]
    ; paths input only used as debug for now.
    ; todo reverse?
    ; suspect we need to query for relation-permissions here and check for terminal?
    (for [perm combined-permissions
          :let [{:as                   perm-def
                 :eacl.permission/keys [resource-type source-relation-name target-type permission-name target-name]} perm]] ;(find-via-permissions db resource-type permission)
      ; todo: care about target-type.
      ; todo: prevent cycles.
      ; todo: encode subject-type. why not just use what we have?
      (if (= :self source-relation-name)                    ; direct relation = terminal condition.
        ; so here is where we have to dispatch on target-type via target-name: either :permission, or :relation.
        ; if it's a relation, it's direct, and if it's a permission, we recursive
        ; target-name contains the relation name. not sure if correct.
        (let [local-type (get-relation-subject-type db initial-resource-type target-name)] ; dunno if correct.
          ; if this type is missing, it means we have a Permission that refers to a missing Relation.
          ; EACL currently allows this, but should not. What to do if nil here?
          [resource-type
           permission-name
           (case target-type
             :relation {:relation [target-name source-relation-name] :subject/type local-type}
             :permission {:permission target-name :subject/type local-type})
           target-name])
            ;perm]])
        (if-let [resource-type' (get-relation-subject-type db resource-type source-relation-name)] ; subject becomes the resource.
          (let [permission' permission-name]                ; permission hop.
            ;(assoc perm-def :eacl.relation/subject-type resource-type')
            (resolve-permissions-recursively db (conj paths perm-def) _subject-type permission' resource-type'))
          [])))))

(def simple-schema
  [(Relation :platform :super_admin :user)

   (Relation :server :owner :user)
   (Relation :server :account :account)

   (Relation :account :owner :user)
   (Relation :account :platform :platform)

   (Permission :account :admin {:relation :platform})

   (Permission :account :view {:relation :owner})

   ; todo: shared admin test.
   ; todo: more complex NIC / Lease relationships.

   (Permission :server :view {:relation :owner})
   (Permission :server :view {:arrow :account :permission :view})

   (Permission :server :admin {:arrow :account :relation :platform})])

(comment

  (d/q [:find ?subject-type ?relation
        :in ?resource-type
        :where

        [?permission :eacl.permission/resource-type ?resource-type]

        [?rel :eacl.relation/subject-type ?subject]
        [?rel :eacl.relation/subject-type ?subject]])

  (resolve-permissions-recursively (d/db conn) [] :user :view :server)

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn simple-schema)
    (resolve-permissions-recursively (d/db conn) [] :user :view :server))

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn simple-schema)
    (resolve-permissions-recursively (d/db conn) [] :user :admin :server))

  (find-arrow-permissions (d/db conn) :server :view)

  (calc-permission-paths (d/db conn) (->user 123) :view (->server 123)))

(deftest recursive-permission-resolution-tests
  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn simple-schema)

    ; there are two :view permissions on a server.
    ; - the direct path should be single lookup.

    (testing "server.view permission yields two paths: one direct, and the other is a hop via account to account->owner"
      ; each of these paths should yield something we can search for in relationship index, and then based on the results,
      ; it should recurse and do a sub-search along that path.
      ; hmm, we probably need subject type in here.
      (let [db (d/db conn)]
        ; can we even traverse this? oh man.
        (is (= [[[:server {:relation :owner :subject/type :user}]] ; direct relationship lookup: [:user :owner :account].
        ; arrow permission we need to unify from server :account -> account <- :owner user
        ; Q: do we search from server -> user via account, or from user -> server via account, or both?
        ; kind of hard to do both, but it seems we have to... that's what the Datalog query does.
        ; few ways to go about it:
        ; either you
        ; note: in indexed impl., each step has :relation and :source-relation-name. it seems that subject type comes from matches.
        ; ideally this should be encoded in the step, though.
                [[[:server {:arrow :self :relation :owner} :account]
                  [:account {:arrow :self :relation :owner} :user]]]]
               (resolve-permissions-recursively db [] :user :view :server))))))
  (testing "server.view resolves"))

;(defn calc-permission-paths
;  "Recursively traverses the permission schema (Relation & Permission) to return a coll
;  of Relationship search paths from the graph, that could yield a permission between subject & resource.
;  Assumes no cycles?"
;  [db subject permission resource]
;  (let [{subject-type :type
;         subject-id   :id} subject
;
;        {resource-type :type
;         resource-id   :id} resource]
;    (let [relation-perms  (find-relation-permissions db resource-type permission)
;          paths (resolve-permissions-recursively db [] resource-type permission)]
;      ; these are the same thing, todo unifyi
;      {:direct relation-perms
;       :arrows paths})))

;(mapcat
;  (apply concat (apply map f colls)))

;(defn paths-to-permission
;  ""
;  [db perm-def]
;  (log/debug 'paths-to-permission perm-def)
;  ; todo reverse?
;  ; termination condition: when we find a relation, we're done.
;  ; if we find a :permission, we need to recurse.
;  ; what about :self?
;  (let [via-permissions (find-via-permissions db resource-type permission)
;        _ (log/debug 'via-perms via-permissions)
;        ; suspect we need to query for relation-permissions here and check for terminal?
;        via-paths       (->> via-permissions
;                             (mapcat (fn [{:as                   perm-def
;                                           :eacl.permission/keys [resource-type source-relation-name target-type permission-name]}]
;                                       ; todo: care about target-type.
;                                       (if (= :self source-relation-name)
;                                         [:terminal perm-def]
;                                         ;(if (= :relation target-type))
;                                         ;(conj paths [:terminal perm-def]) ; terminal condition.
;                                         (let [subject-type (get-relation-subject-type db resource-type source-relation-name)]
;                                           (recursively-get-paths db (conj paths perm-def) subject-type permission-name))))))]
;    via-paths))



    ; 1. we need to start from the resource-type and search backwards to find subjects that have that resource.
    ; arrow permissions either yield target-type :permission or :relation:
    ; - if it's a relation, then we can stop, because that can resolve to a relationship seq.
    ; - but if it's another permission, we need to recurse until we find a :relation.
    ;   - those subjects may be of a different type
    ; hmm, we probably need to pull this out.

    ; starting from the subject


(comment)
  ;(let [relations (d/q '[:find ?subject-type ?relation-name
  ;                       :in $ ?resource-type
  ;                       :where
  ;                       [?relation :eacl.relation/resource-type ?resource-type]
  ;                       [?relation :eacl.relation/relation-name ?relation-name]
  ;                       [?relation :eacl.relation/subject-type ?subject-type]]
  ;                     db)]))

;(deftest playground-tests
;  (with-mem-conn [conn schema/v5-schema]
;    @(d/transact conn [[:db/retractEntity :test/attr]])))

;(defmacro spy
;  "A macro that prints the expression and its evaluated value, then returns the value."
;  [expr]
;  `(let [value# ~expr]
;     (prn (str (quote ~expr) " => ") value#)
;     value#))
;
