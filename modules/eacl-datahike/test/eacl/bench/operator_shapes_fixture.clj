(ns eacl.bench.operator-shapes-fixture
  "Account charts for the operator shapes that still cost more than their
  operands after delegation: expiring shares of recursive operands, a union
  at an operator's root, and recursion through an intersection whose other
  operand is a relation guard.

  Subjects:
  - `owner` reads and may delete the root and is eligible on every account;
  - `split` may delete root.3's subtree, reads root.3.1's, and is eligible on
    root.3's subtree except root.3.1.1's;
  - `rootsharee` holds unexpired expiring `deleter` and `reader` grants on
    the root, and nothing else;
  - `sharee` holds the same expiring grants on root.2 only."
  (:require [clojure.string :as str]
            [datahike.api :as d]
            [eacl.bench.recursive-intersection-fixture :as chart]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]))

(def now
  "The fixed evaluation time every client reads."
  1000000)

(def expiry
  "An hour after `now`: every expiring grant is unexpired when evaluated."
  (+ now 3600000))

(def schema
  "definition user {}

   definition account {
     relation parent: account
     relation reader: user
     relation deleter: user
     relation eligible: user

     permission read_account = reader + parent->read_account
     permission delete_granted = deleter + parent->delete_granted
     permission delete = delete_granted & read_account
     permission delete_top = deleter + (delete_granted & read_account)
     permission deleter_only = deleter
     permission inherited = reader + (parent->inherited & eligible)
     permission reachable = reader + parent->reachable
     permission eligible_only = eligible
   }")

(defn- under? [path root] (or (= path root) (str/starts-with? path (str root "."))))

(defn- expiring [relationship] (assoc relationship :valid-until-ms expiry))

(defn- relationships [nodes]
  (let [paths (map first nodes)
        grant (fn [subject relation path]
                (eacl/->Relationship (chart/user subject) relation (chart/account path)))]
    (vec
     (concat
      (for [[path parent] nodes :when parent]
        (eacl/->Relationship (chart/account parent) :parent (chart/account path)))
      [(grant "owner" :reader "root")
       (grant "owner" :deleter "root")
       (grant "split" :deleter "root.3")
       (grant "split" :reader "root.3.1")
       (expiring (grant "rootsharee" :deleter "root"))
       (expiring (grant "rootsharee" :reader "root"))
       (expiring (grant "sharee" :deleter "root.2"))
       (expiring (grant "sharee" :reader "root.2"))]
      (for [path paths] (grant "owner" :eligible path))
      (for [path paths
            :when (and (under? path "root.3") (not (under? path "root.3.1.1")))]
        (grant "split" :eligible path))))))

(def client-options (assoc chart/client-options :clock (constantly now)))

(defn setup
  "An in-memory Datahike store holding the schema, a chart of `shape`, and
  every subject's relationships."
  [shape]
  (let [conn (eacl.datahike/create-conn
              [{:db/ident :app/id :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}])
        acl (eacl.datahike/make-client conn client-options)
        nodes (chart/tree shape)
        ids (concat (for [subject ["owner" "split" "rootsharee" "sharee"]]
                      (str "user:" subject))
                    (for [[path] nodes] (str "account:" path)))]
    (eacl/write-schema! acl schema)
    (d/transact conn (vec (for [id ids] {:app/id id})))
    (eacl/create-relationships! acl (relationships nodes))
    {:conn conn :nodes nodes}))

(defn warm-snapshot
  "One read-only snapshot reused by every sample."
  [{:keys [conn]}]
  (eacl/snapshot
   (eacl.datahike/make-client (atom @conn)
                              (assoc client-options :read-only? true))))

(defn expected-inherited
  "`inherited` for a subject, derived from the chart alone: its reader
  accounts and every descendant reached through accounts it is eligible on."
  [{:keys [nodes]} subject]
  (let [paths (map first nodes)
        [reads eligible?]
        (case subject
          "owner" [#{"root"} (constantly true)]
          "split" [#{"root.3.1"} #(and (under? % "root.3")
                                       (not (under? % "root.3.1.1")))])
        parent (into {} (map (fn [[path p]] [path p])) nodes)
        inherited? (fn inherited? [path]
                     (or (contains? reads path)
                         (and (eligible? path)
                              (some? (parent path))
                              (inherited? (parent path)))))]
    (set (for [path paths :when (inherited? path)]
           (str "account:" path)))))

(defn lookup-ids
  "Every resource id of a complete cursor walk, shared answer caches
  bypassed."
  [acl subject permission]
  (chart/lookup-ids acl (chart/user subject) permission))

(defn check
  "The permissionship of one check, shared answer caches bypassed."
  [acl subject permission path]
  (:permissionship
   (eacl/check-permission acl {:subject (chart/user subject)
                               :permission permission
                               :resource (chart/account path)
                               :cache? false})))
