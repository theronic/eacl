(ns eacl.bench.recursive-intersection-fixture
  "Account charts for `&` over two recursive permissions, the shape a consumer
  (0tx) hit on every chart load. Two schemas: a minimal one, and a ledger
  schema whose `read_account` closure is about twenty rules. The owner may read
  and delete every account; `split` may delete one subtree and read only part
  of it."
  (:require [datahike.api :as d]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]))

(def minimal-schema
  "definition user {}

   definition account {
     relation parent: account
     relation reader: user
     relation deleter: user

     permission read_account = reader + parent->read_account
     permission delete_granted = deleter + parent->delete_granted
     permission delete = delete_granted & read_account
   }")

(def ledger-schema
  "0tx's ledger schema with `delete = delete_granted & read_account`: the
  `read_account` closure spans about twenty rules."
  "definition user {}

   definition ledger {
     relation owner: user
     relation manager: user
     relation viewer: user

     permission administer = owner + manager
     permission view = administer + viewer
   }

   definition account {
     relation ledger: ledger
     relation parent: account

     relation manager: user
     relation sharer: user
     relation deleter: user
     relation reader: user
     relation entry_reader: user
     relation proposer: user
     relation entry_creator: user
     relation entry_editor: user
     relation entry_deleter: user
     relation transfer_source: user
     relation transfer_target: user

     permission share = manager + sharer

     permission direct_propose = proposer + manager
     permission direct_create = entry_creator + manager
     permission direct_edit = entry_editor + manager
     permission direct_delete_entry = entry_deleter + manager
     permission direct_transfer_from = transfer_source + manager
     permission direct_transfer_to = transfer_target + manager
     permission direct_read_entries = entry_reader + direct_propose + direct_create + direct_edit + direct_delete_entry + direct_transfer_from + direct_transfer_to
     permission direct_read_account = reader + sharer + direct_read_entries

     permission manage_account = manager + ledger->administer + parent->manage_account
     permission share_account = share + ledger->administer + parent->share_account
     permission propose_entries = direct_propose + ledger->administer + parent->propose_entries
     permission create_entries = direct_create + ledger->administer + parent->create_entries
     permission edit_entries = direct_edit + ledger->administer + parent->edit_entries
     permission delete_entry = direct_delete_entry + ledger->administer + parent->delete_entry
     permission transfer_lines_from = direct_transfer_from + ledger->administer + parent->transfer_lines_from
     permission transfer_lines_to = direct_transfer_to + ledger->administer + parent->transfer_lines_to
     permission read_entries = direct_read_entries + ledger->view + parent->read_entries
     permission read_account = direct_read_account + ledger->view + parent->read_account

     permission delete_granted = deleter + manager + ledger->administer + parent->delete_granted
     permission delete = delete_granted & read_account
     permission delete_union = deleter + manager + ledger->administer + parent->delete_union

     permission list_ledger = direct_read_account + ledger->view
   }")

(defn tree
  "[[path parent-path] ...] for a tree whose level i has (nth shape i)
  children per node."
  [shape]
  (loop [level 0 frontier ["root"] acc [["root" nil]]]
    (if (= level (count shape))
      acc
      (let [next (vec (for [p frontier i (range (nth shape level))]
                        [(str p "." i) p]))]
        (recur (inc level) (mapv first next) (into acc next))))))

(defn object [type id] (eacl/spice-object type (str (name type) ":" id)))
(defn user [id] (object :user id))
(defn account [path] (object :account path))

(defn- relationships [kind nodes]
  (let [edges (for [[path parent] nodes :when parent]
                (eacl/->Relationship (account parent) :parent (account path)))]
    (vec
     (concat
      edges
      (case kind
        ;; owner reads and may delete everything; split may delete
        ;; everything but read only root.3's subtree.
        :minimal [(eacl/->Relationship (user "owner") :reader (account "root"))
                  (eacl/->Relationship (user "owner") :deleter (account "root"))
                  (eacl/->Relationship (user "split") :deleter (account "root"))
                  (eacl/->Relationship (user "split") :reader (account "root.3"))]
        ;; owner owns the ledger; split may delete root.3's subtree and read
        ;; only root.3.1's.
        :ledger [(eacl/->Relationship (object :ledger "l") :ledger (account "root"))
                 (eacl/->Relationship (user "owner") :owner (object :ledger "l"))
                 (eacl/->Relationship (user "split") :deleter (account "root.3"))
                 (eacl/->Relationship (user "split") :reader (account "root.3.1"))])))))

(def client-options
  {:object-id->lookup-ref (fn [id] [:app/id id])
   :entid->object-id (fn [db eid] (:app/id (d/entity db eid)))})

(defn setup
  "An in-memory Datahike store holding `kind`'s schema (:minimal or :ledger),
  a chart of `shape`, and the fixture's relationships."
  [kind shape]
  (let [conn (eacl.datahike/create-conn
              [{:db/ident :app/id :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}])
        acl (eacl.datahike/make-client conn client-options)
        nodes (tree shape)
        ids (concat ["user:owner" "user:split" "ledger:l"]
                    (for [[path] nodes] (str "account:" path)))]
    (eacl/write-schema! acl (case kind :minimal minimal-schema :ledger ledger-schema))
    (d/transact conn (vec (for [id ids] {:app/id id})))
    (eacl/create-relationships! acl (relationships kind nodes))
    {:kind kind :conn conn :nodes nodes :leaf (first (last nodes))}))

(defn warm-snapshot
  "One read-only snapshot reused by every sample: client and plan setup stay
  out of the measured work."
  [{:keys [conn]}]
  (eacl/snapshot
   (eacl.datahike/make-client (atom @conn)
                              (assoc client-options :read-only? true))))

(defn lookup-ids
  "Every resource id of a complete cursor walk of `permission` for
  `subject`, with the shared answer caches bypassed."
  [acl subject permission]
  (loop [after nil acc []]
    (let [page (eacl/lookup-resources
                acl (cond-> {:subject subject :permission permission
                             :resource/type :account :first 1000 :cache? false}
                      after (assoc :after after)))
          acc (into acc (map :id) (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) acc)
        acc))))

(defn check
  [acl subject permission resource]
  (eacl/can? acl {:subject subject :permission permission
                  :resource resource :cache? false}))
