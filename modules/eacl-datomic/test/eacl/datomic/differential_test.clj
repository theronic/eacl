(ns eacl.datomic.differential-test
  "Seeded randomized differential tests codifying the audit's cross-engine
  invariant: for every (subject, permission, resource-type),

    lookup-resources set == can?-derived ground truth
                         == paginated union at several page sizes
                         == count-resources

  and the reverse via lookup-subjects. Hand-rolled seeded RNG — no new deps.
  These invariants held for the engines during the 2026-07-06 audit; this
  makes them executable against every future change."
  (:require [clojure.test :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl :refer [Relationship]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private differential-schema
  "Exercises direct relations, arrow->permission, arrow->relation,
  self-permission, and overlapping multi-path unions."
  "definition user {}

   definition platform {
     relation super_admin: user
   }

   definition account {
     relation owner: user
     relation platform: platform

     permission admin = owner + platform->super_admin
   }

   definition server {
     relation account: account
     relation shared: user

     permission admin = account->admin + shared
     permission view = admin + shared
   }")

(defn- rand-subset
  [^java.util.Random rng coll p]
  (vec (filter (fn [_] (< (.nextDouble rng) p)) coll)))

(defn- entity-txes
  [ids]
  (mapv (fn [id] {:db/id id :eacl/id id}) ids))

(defn- eid-of [db id] (d/entid db [:eacl/id id]))

(defn- collect-paged
  "Collects a full paginated enumeration at the given page size."
  [db query page-size]
  (loop [cursor nil
         acc    []]
    (let [page (idx/lookup-resources db (assoc query :limit page-size :cursor cursor))
          acc' (into acc (map :id (:data page)))]
      (if (seq (:data page))
        (recur (:cursor page) acc')
        acc'))))

(defn- check-forward-invariants!
  [db label subject permission resource-type all-resource-eids sorted?]
  (let [query {:subject subject :permission permission :resource/type resource-type}
        full  (mapv :id (:data (idx/lookup-resources db (assoc query :limit -1))))
        truth (set (filter #(idx/can? db subject permission (spice-object resource-type %))
                           all-resource-eids))]
    (is (= truth (set full))
        (str label ": lookup-resources set must equal can? ground truth"))
    (is (= (count full) (count (distinct full)))
        (str label ": no duplicates"))
    (when sorted?
      (is (= full (sort full))
          (str label ": non-recursive results are in ascending eid order")))
    (doseq [page-size [1 3 7]]
      (is (= full (collect-paged db query page-size))
          (str label ": paginated union at page size " page-size " must equal the full enumeration")))
    (is (= (count full)
           (:count (idx/count-resources db (assoc query :limit -1))))
        (str label ": count-resources must agree"))
    full))

(defn- check-reverse-invariants!
  [db label resource permission subject-type all-subject-eids]
  (let [subjects (mapv :id (:data (idx/lookup-subjects db {:resource resource
                                                           :permission permission
                                                           :subject/type subject-type
                                                           :limit -1})))
        truth    (set (filter #(idx/can? db (spice-object subject-type %) permission resource)
                              all-subject-eids))]
    (is (= truth (set subjects))
        (str label ": lookup-subjects set must equal can? ground truth"))
    (is (= (count subjects) (count (distinct subjects)))
        (str label ": no duplicate subjects"))))

(deftest differential-nonrecursive-test
  (doseq [seed [7 23 42 1337]]
    (with-mem-conn [conn schema/v6-schema]
      (let [rng       (java.util.Random. (long seed))
            users     (mapv #(str "user-" %) (range 3))
            accounts  (mapv #(str "acct-" %) (range 3))
            servers   (mapv #(str "srv-" %) (range 12))
            platform  "platform-1"]
        (schema/write-schema! conn differential-schema)
        @(d/transact conn (entity-txes (concat users accounts servers [platform])))
        (let [db0 (d/db conn)
              rels (concat
                    ;; platform super admins
                    (for [u (rand-subset rng users 0.3)]
                      (Relationship (spice-object :user u) :super_admin (spice-object :platform platform)))
                    ;; account owners + platform membership
                    (mapcat (fn [a]
                              (concat
                               (for [u (rand-subset rng users 0.5)]
                                 (Relationship (spice-object :user u) :owner (spice-object :account a)))
                               (when (< (.nextDouble rng) 0.5)
                                 [(Relationship (spice-object :platform platform) :platform (spice-object :account a))])))
                            accounts)
                    ;; servers: account membership + direct shares
                    (mapcat (fn [s]
                              (concat
                               (when (< (.nextDouble rng) 0.8)
                                 [(Relationship (spice-object :account (nth accounts (.nextInt rng (count accounts))))
                                                :account (spice-object :server s))])
                               (for [u (rand-subset rng users 0.15)]
                                 (Relationship (spice-object :user u) :shared (spice-object :server s)))))
                            servers))]
          @(d/transact conn (into [] (mapcat #(impl/tx-relationship db0 %)) rels)))
        (let [db          (d/db conn)
              server-eids (mapv #(eid-of db %) servers)
              account-eids (mapv #(eid-of db %) accounts)
              user-eids   (mapv #(eid-of db %) users)]
          (testing (str "seed " seed ": forward invariants for every user × permission")
            (doseq [u users
                    [perm rt eids] [[:view :server server-eids]
                                    [:admin :server server-eids]
                                    [:admin :account account-eids]]]
              (check-forward-invariants! db (str "seed " seed " user " u " " perm " " rt)
                                         (spice-object :user (eid-of db u))
                                         perm rt eids true)))
          (testing (str "seed " seed ": reverse invariants for every server")
            (doseq [s servers]
              (check-reverse-invariants! db (str "seed " seed " server " s)
                                         (spice-object :server (eid-of db s))
                                         :view :user user-eids))))))))

;; NOTE: the recursive-schema differential test from the Datomic-peer lineage is
;; omitted: this branch's shared engine intentionally prunes cyclic permission
;; graphs during path calculation (no recursive frontier engine here yet).
