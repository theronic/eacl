(ns eacl.bench.recursive-fixture
  "Deterministic populated-recursion fixtures for the v8 gate suite.

  Unlike the Explorer fixtures, every shape here has ACTIVE recursive data:
  the in-SCC `account#parent` relation is populated, so routing selects the
  genuinely recursive engine (a gate self-check asserts nonzero recursive
  work). Shapes:

  - :star   — one root owned by user-1, N-1 children each parented to the
              root. Wide frontier, depth 1: ~all generated scans are
              per-frontier emptiness probes (the 0tx shape).
  - :chain  — node-0001 owned by user-1, node-i parent of node-(i+1).
              Depth N, frontier 1. owner-mid owns the midpoint node for
              shallow/deep point-check splits.
  - :mixed  — one root, `chains` chains hanging off it. Frontier and depth.
  - :broad-union — a 0tx `read_account`-style union (owner + reader +
              legal_entity->view + parent->read_account) over a star, with
              two recursive permissions and a non-recursive arrow branch.

  All ids are zero-padded and derived solely from indexes, so any two runs
  (and any two backends) seed identical logical graphs."
  (:require [eacl.core :as eacl]))

(def schema
  "definition user {}

   definition account {
     relation owner: user
     relation reader: user
     relation parent: account
     permission view = owner + parent->view
   }")

(def broad-union-schema
  "definition user {}

   definition legal_entity {
     relation admin: user
     permission view = admin
   }

   definition account {
     relation owner: user
     relation reader: user
     relation legal_entity: legal_entity
     relation parent: account
     permission administer = owner + parent->administer
     permission read_account = administer + reader + legal_entity->view + parent->read_account
   }")

(defn- zero-pad
  [width value]
  (let [value (str value)]
    (str (apply str (repeat (max 0 (- width (count value))) "0"))
         value)))

(defn account-id [index]
  (str "account-" (zero-pad 6 (inc index))))

(defn object
  [type id]
  (eacl/spice-object type id))

(def user-1 (object :user "user-1"))
(def owner-mid (object :user "owner-mid"))
(def reader-1 (object :user "reader-1"))
(def le-viewer (object :user "le-viewer"))
(def stranger (object :user "stranger"))
(def legal-entity-1 (object :legal_entity "legal-entity-1"))

(defn- account [index] (object :account (account-id index)))

(defn- parent-edge
  "Relationship making `parent-index` the parent of `child-index`."
  [parent-index child-index]
  (eacl/->Relationship (account parent-index) :parent (account child-index)))

(defmulti relationships
  "Deterministic relationship seq for a shape config.

  Config: {:shape :star|:chain|:mixed|:broad-union
           :accounts N
           :chains C}   ; :mixed only"
  :shape)

(defmethod relationships :star
  [{:keys [accounts]}]
  (concat
   [(eacl/->Relationship user-1 :owner (account 0))]
   (map #(parent-edge 0 %) (range 1 accounts))))

(defmethod relationships :chain
  [{:keys [accounts]}]
  (let [mid (quot accounts 2)]
    (concat
     [(eacl/->Relationship user-1 :owner (account 0))
      (eacl/->Relationship owner-mid :owner (account mid))]
     (map #(parent-edge % (inc %)) (range 0 (dec accounts))))))

(defmethod relationships :mixed
  [{:keys [accounts chains] :or {chains 10}}]
  (let [per-chain (quot (dec accounts) chains)
        chain-start (fn [c] (inc (* c per-chain)))]
    (concat
     [(eacl/->Relationship user-1 :owner (account 0))]
     (mapcat
      (fn [c]
        (let [start (chain-start c)]
          (cons
           (parent-edge 0 start)
           (map #(parent-edge % (inc %))
                (range start (+ start (dec per-chain)))))))
      (range chains)))))

(defmethod relationships :broad-union
  [{:keys [accounts]}]
  (concat
   [(eacl/->Relationship user-1 :owner (account 0))
    (eacl/->Relationship reader-1 :reader (account 0))
    (eacl/->Relationship le-viewer :admin legal-entity-1)
    (eacl/->Relationship legal-entity-1 :legal_entity (account 0))]
   (map #(parent-edge 0 %) (range 1 accounts))))

(defn schema-for
  [{:keys [shape]}]
  (if (= :broad-union shape) broad-union-schema schema))

(defn account-count
  "Total accounts a shape actually seeds (mixed rounds down to full chains)."
  [{:keys [shape accounts chains] :or {chains 10}}]
  (if (= :mixed shape)
    (inc (* chains (quot (dec accounts) chains)))
    accounts))

(defn objects
  "Every app object the shape references, as spice objects."
  [{:keys [shape] :as config}]
  (concat
   (map account (range (account-count config)))
   [user-1 stranger]
   (when (= :chain shape) [owner-mid])
   (when (= :broad-union shape) [reader-1 le-viewer legal-entity-1])))

(defn object-transactions
  "Datomic/DataScript-style tempid transaction maps for app objects."
  [config]
  (map-indexed
   (fn [index {:keys [id]}]
     {:db/id (- (inc index))
      :eacl/id id})
   (objects config)))

(defn relationship-batches
  ([config] (relationship-batches config 500))
  ([config batch-size]
   (partition-all batch-size (relationships config))))

(defn view-permission
  [{:keys [shape]}]
  (if (= :broad-union shape) :read_account :view))

(defn expected-view-count
  "Exact number of accounts `subject` can view/read under a shape.

  Grants flow parent -> child (child inherits through parent->...), so an
  owner/reader at a node sees that node plus every descendant."
  [{:keys [shape accounts] :as config} subject]
  (let [total (account-count config)
        mid (quot accounts 2)]
    (cond
      (= subject stranger) 0
      (= subject user-1) total
      (and (= :chain shape) (= subject owner-mid)) (- total mid)
      (and (= :broad-union shape) (= subject reader-1)) total
      (and (= :broad-union shape) (= subject le-viewer)) total
      :else 0)))

(defn resource-query
  ([config subject] (resource-query config subject 50))
  ([config subject page-size]
   {:subject subject
    :permission (view-permission config)
    :resource/type :account
    :first page-size}))

(defn count-query
  [config subject]
  (dissoc (resource-query config subject) :first))
