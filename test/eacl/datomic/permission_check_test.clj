(ns eacl.datomic.permission-check-test
  "Correctness tests for `can?`'s arrow evaluation.

  `can?` answers an arrow by intersecting two sorted intermediate streams —
  the resource's intermediates and the intermediates the subject holds through
  a single relationship — instead of scanning the resource's side in full. That
  is only a complete answer when every way to satisfy the far side IS a single
  relationship. When the far side is a permission with arrows or aliases of its
  own, an empty intersection proves nothing and the check must still recurse.
  Getting that distinction wrong denies access that should be granted, which no
  timing benchmark would catch."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(defn- client! [conn schema-string]
  (let [acl (core/make-client conn {:cache false})]
    (eacl/write-schema! acl schema-string)
    acl))

(defn- ids! [conn & ids]
  @(d/transact conn (mapv (fn [id] {:eacl/id id}) ids)))

(defn- relate! [acl subject relation resource]
  (eacl/create-relationship! acl (->Relationship subject relation resource)))

;; --- arrow to a permission whose paths are all plain relations --------------

(def ^:private exhaustive-schema
  "definition user {}
   definition team { relation member: user
                     permission access = member }
   definition doc  { relation team: team
                     permission view = team->access }")

(deftest arrow-to-all-relation-permission-test
  (doseq [fan-out [1 2 50]]
    (with-mem-conn [conn schema/v7-schema]
      (let [acl (client! conn exhaustive-schema)
            teams (mapv #(str "t" %) (range fan-out))]
        (apply ids! conn "alice" "bob" "d1" teams)
        (doseq [t teams]
          (relate! acl (spice-object :team t) :team (spice-object :doc "d1")))
        ;; alice is in the LAST team, so a scan from the front is worst case
        (relate! acl (spice-object :user "alice") :member
                 (spice-object :team (last teams)))

        (testing (str "fan-out " fan-out)
          (is (true? (eacl/can? acl (spice-object :user "alice")
                                :view (spice-object :doc "d1")))
              "membership of any one intermediate grants the arrow")
          (is (false? (eacl/can? acl (spice-object :user "bob")
                                 :view (spice-object :doc "d1")))
              "an empty intersection is a definitive false when the far side
               can only be satisfied by one relationship")
          (is (= ["d1"]
                 (mapv :id (:data (eacl/lookup-resources
                                   acl {:subject (spice-object :user "alice")
                                        :permission :view
                                        :resource/type :doc}))))
              "and lookup agrees with can?"))))))

;; --- arrow to a permission that has arrows of its own -----------------------

(def ^:private nested-schema
  "definition user {}
   definition group { relation member: user
                      relation parent: group
                      permission access = member + parent->access }
   definition doc   { relation grp: group
                      permission view = grp->access }")

(deftest arrow-to-permission-with-its-own-arrows-test
  ;; doc -grp-> {gN, decoy...}, gN's parent chain ends at g0, alice is a member
  ;; of g0 only.
  ;;
  ;; The intersection is EMPTY: the doc's intermediates are {gN, decoys} and
  ;; alice's directly-held groups are {g0}. `access` is not exhaustive — it
  ;; also has `parent->access` — so an empty intersection proves nothing and
  ;; the check must fall through to the full recursion.
  ;;
  ;; The decoys matter: with a single intermediate `can?` takes the fan-out-1
  ;; point-probe path and never reaches the intersection at all, so a version
  ;; of this test without them passes even when `exhaustive?` is wrong.
  (doseq [depth [1 2 5]
          decoys [2 20]]
    (with-mem-conn [conn schema/v7-schema]
      (let [acl (client! conn nested-schema)
            groups (mapv #(str "g" %) (range (inc depth)))
            decoy-ids (mapv #(str "decoy" %) (range decoys))]
        (apply ids! conn "alice" "bob" "d1" (concat groups decoy-ids))
        (relate! acl (spice-object :group (last groups)) :grp (spice-object :doc "d1"))
        (doseq [decoy decoy-ids]
          (relate! acl (spice-object :group decoy) :grp (spice-object :doc "d1")))
        ;; g0 <- g1 <- ... <- gN, with gN attached to the doc
        (doseq [[child parent] (map vector (rest groups) groups)]
          (relate! acl (spice-object :group parent) :parent (spice-object :group child)))
        (relate! acl (spice-object :user "alice") :member (spice-object :group "g0"))

        (testing (str "depth " depth ", " decoys " decoy intermediates")
          (is (< 1 (count (:data (eacl/read-relationships
                                  acl {:resource/id "d1" :resource/relation :grp}))))
              "the doc must have more than one intermediate, or the fan-out-1
               fast path bypasses what this test is for")
          (is (true? (eacl/can? acl (spice-object :user "alice")
                                :view (spice-object :doc "d1")))
              "a grant reachable only through the target permission's own arrow
               must survive an empty direct intersection")
          (is (false? (eacl/can? acl (spice-object :user "bob")
                                 :view (spice-object :doc "d1"))))))))

  (testing "the direct intersection still answers when it can"
    (with-mem-conn [conn schema/v7-schema]
      (let [acl (client! conn nested-schema)]
        (ids! conn "alice" "d1" "g0")
        (relate! acl (spice-object :group "g0") :grp (spice-object :doc "d1"))
        (relate! acl (spice-object :user "alice") :member (spice-object :group "g0"))
        (is (true? (eacl/can? acl (spice-object :user "alice")
                              :view (spice-object :doc "d1"))))))))

;; --- arrow whose target is a relation, not a permission ---------------------

(def ^:private arrow-to-relation-schema
  "definition user {}
   definition team { relation member: user
                     relation guest: user }
   definition doc  { relation team: team
                     permission view = team->member }")

(deftest arrow-to-relation-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (client! conn arrow-to-relation-schema)
          teams (mapv #(str "t" %) (range 30))]
      (apply ids! conn "alice" "bob" "d1" teams)
      (doseq [t teams]
        (relate! acl (spice-object :team t) :team (spice-object :doc "d1")))
      (relate! acl (spice-object :user "alice") :member (spice-object :team "t29"))
      ;; bob is a GUEST of the same team — a different relation, so no grant
      (relate! acl (spice-object :user "bob") :guest (spice-object :team "t29"))

      (is (true? (eacl/can? acl (spice-object :user "alice")
                            :view (spice-object :doc "d1"))))
      (is (false? (eacl/can? acl (spice-object :user "bob")
                             :view (spice-object :doc "d1")))
          "the intersection must be scoped to the arrow's target relation"))))

;; --- multi-subject-type relations -------------------------------------------

(def ^:private multi-subject-schema
  "definition user {}
   definition robot {}
   definition team { relation member: user | robot }
   definition doc  { relation team: team
                     permission view = team->member }")

(deftest multi-subject-type-arrow-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (client! conn multi-subject-schema)
          teams (mapv #(str "t" %) (range 20))]
      (apply ids! conn "alice" "r2d2" "d1" teams)
      (doseq [t teams]
        (relate! acl (spice-object :team t) :team (spice-object :doc "d1")))
      (relate! acl (spice-object :robot "r2d2") :member (spice-object :team "t19"))

      (is (true? (eacl/can? acl (spice-object :robot "r2d2")
                            :view (spice-object :doc "d1"))))
      (is (false? (eacl/can? acl (spice-object :user "alice")
                             :view (spice-object :doc "d1")))
          "a relation of another subject type must not grant this subject"))))

;; --- path ordering ----------------------------------------------------------

(def ^:private union-schema
  "definition user {}
   definition team { relation member: user
                     permission access = member }
   definition doc  { relation team: team
                     relation owner: user
                     permission arrow_written_first = team->access + owner
                     permission relation_written_first = owner + team->access }")

(deftest union-paths-are-ordered-cheapest-first-test
  ;; Path order used to come out of a clojure.set/difference in write-schema!,
  ;; so which branch of a union `can?` tried first was hash order and could
  ;; differ between deployments of the same schema. On a doc with many teams
  ;; that decided between a point lookup and a full intermediate scan.
  (with-mem-conn [conn schema/v7-schema]
    (client! conn union-schema)
    (let [db (d/db conn)]
      (doseq [permission [:arrow_written_first :relation_written_first]]
        (is (= [:relation :arrow]
               (mapv :type (idx/get-permission-paths db :doc permission)))
            (str permission " is evaluated relation-first regardless of source order")))))

  (testing "both orderings agree, and agree with lookup"
    (with-mem-conn [conn schema/v7-schema]
      (let [acl (client! conn union-schema)
            teams (mapv #(str "t" %) (range 40))]
        (apply ids! conn "alice" "bob" "d1" teams)
        (doseq [t teams]
          (relate! acl (spice-object :team t) :team (spice-object :doc "d1")))
        (relate! acl (spice-object :user "alice") :owner (spice-object :doc "d1"))
        (relate! acl (spice-object :user "bob") :member (spice-object :team "t39"))
        (doseq [permission [:arrow_written_first :relation_written_first]]
          (is (true? (eacl/can? acl (spice-object :user "alice")
                                permission (spice-object :doc "d1")))
              (str permission " via the direct relation"))
          (is (true? (eacl/can? acl (spice-object :user "bob")
                                permission (spice-object :doc "d1")))
              (str permission " via the arrow")))))))
