(ns eacl.datomic.raw-plan-isolation-test
  "The raw facade's sealed-plan reuse must never serve one database view's
  plan to another view that reports the same source identity
  (bidirectional-arrow-point-check hardening; the aliasing was introduced
  when the facade's per-call random lifecycle became process-stable).

  Sealed plans embed relation eids and permission arms, so a plan compiled
  from a d/filter view (which hides definition datoms but not the schema
  stamp) or a d/with speculative value (which shares the later committed
  basis) answers wrongly for the plain database, and vice versa. Plan reuse
  is therefore restricted to ordinary views of a stamped schema generation;
  everything else compiles per call."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.impl.base :as base]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]))

(def ^:private stamped-schema
  "definition user {}
definition org {
  relation member: user
  permission view = member
}
definition doc {
  relation owner: user
  relation org: org
  permission view = owner + org->view
}")

(deftest filtered-view-never-shares-plans-with-the-plain-database-test
  ;; A filter that hides the owner permission arm changes the sealed plan
  ;; but not the schema stamp, the database id, or the basis. Whichever
  ;; view seals first must not answer for the other.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache cache/no-cache})
          _ (eacl/write-schema! acl stamped-schema)
          _ @(d/transact conn [{:eacl/id "alice"} {:eacl/id "doc1"}])
          _ (eacl/create-relationship!
             acl (->Relationship (spice-object :user "alice")
                                 :owner (spice-object :doc "doc1")))
          db (d/db conn)
          owner-arm-eids
          (into #{}
                (comp (map :e)
                      (filter (fn [eid]
                                (= :owner
                                   (:eacl.permission/target-name
                                    (d/pull db
                                            [:eacl.permission/target-name]
                                            eid))))))
                (d/datoms db :avet
                          :eacl.permission/resource-type+permission-name
                          [:doc :view]))
          _ (is (= 1 (count owner-arm-eids)))
          filtered (d/filter db (fn [_ datom]
                                  (not (contains? owner-arm-eids
                                                  (:e datom)))))
          alice (spice-object :user "alice")
          doc (spice-object :doc "doc1")]
      (testing "filtered view first cannot poison the plain view"
        (engine/expire-plans!)
        (is (false? (impl/can? filtered alice :view doc))
            "the filtered view hides the owner arm")
        (is (true? (impl/can? db alice :view doc))
            "the plain view keeps its owner arm"))
      (testing "plain view first cannot leak its plan into the filtered view"
        (engine/expire-plans!)
        (is (true? (impl/can? db alice :view doc)))
        (is (false? (impl/can? filtered alice :view doc)))))))

(deftest speculative-value-never-shares-plans-with-the-committed-basis-test
  ;; Unstamped databases key plans by basis, which a d/with value shares
  ;; with the NEXT committed transaction. A speculative extra permission
  ;; arm must not answer for the committed database at the same basis.
  (with-mem-conn [conn schema/v7-schema]
    (let [_ @(d/transact conn [(base/Relation :doc :owner :user)
                               (base/Relation :doc :editor :user)
                               (base/Permission :doc :view {:relation :owner})])
          _ @(d/transact conn [{:eacl/id "alice"} {:eacl/id "doc1"}])
          db0 (d/db conn)
          speculative
          (:db-after
           (d/with db0 [(base/Permission :doc :view {:relation :editor})
                        [:db/add [:eacl/id "alice"]
                         :eacl.v7.relationship/subject-type+relation+resource-type+resource
                         [:user (d/entid db0 [:eacl/id
                                              "eacl.relation::doc::editor::user"])
                          :doc (d/entid db0 [:eacl/id "doc1"])]]
                        [:db/add [:eacl/id "doc1"]
                         :eacl.v7.relationship/resource-type+relation+subject-type+subject
                         [:doc (d/entid db0 [:eacl/id
                                             "eacl.relation::doc::editor::user"])
                          :user (d/entid db0 [:eacl/id "alice"])]]]))
          ;; Commit a DIFFERENT transaction so the committed basis equals the
          ;; speculative basis: alice becomes an editor in data, but the
          ;; committed schema still grants :view through :owner only.
          editor-rel-eid (d/entid db0 [:eacl/id "eacl.relation::doc::editor::user"])
          doc-eid (d/entid db0 [:eacl/id "doc1"])
          alice-eid (d/entid db0 [:eacl/id "alice"])
          _ @(d/transact conn
                         [[:db/add alice-eid
                           :eacl.v7.relationship/subject-type+relation+resource-type+resource
                           [:user editor-rel-eid :doc doc-eid]]
                          [:db/add doc-eid
                           :eacl.v7.relationship/resource-type+relation+subject-type+subject
                           [:doc editor-rel-eid :user alice-eid]]])
          committed (d/db conn)
          alice (spice-object :user "alice")
          doc (spice-object :doc "doc1")]
      (is (= (d/basis-t speculative) (d/basis-t committed))
          "the aliasing precondition: identical basis")
      (testing "speculative plan first must not answer for the committed basis"
        (engine/expire-plans!)
        (is (true? (impl/can? speculative alice :view doc))
            "the speculative view grants through its extra editor arm")
        (is (false? (impl/can? committed alice :view doc))
            "the committed schema grants only through :owner")))))
