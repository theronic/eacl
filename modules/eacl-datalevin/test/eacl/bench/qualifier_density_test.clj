(ns eacl.bench.qualifier-density-test
  "Native tree/page inspection; deliberately outside production request paths."
  (:require [datalevin.core :as dl]
            [eacl.core :as eacl]
            [eacl.bench.qualifier-storage-test :as workload]
            [eacl.relationships.storage :as storage]))

(defn- datascript-tree [^me.tonsky.persistent_sorted_set.PersistentSortedSet tree]
  (let [store (.-_storage tree)]
    (loop [pending [(.root tree)] branches 0 leaves 0 entries 0]
      (if-let [^me.tonsky.persistent_sorted_set.ANode node (peek pending)]
        (if (zero? (.level node))
          (recur (pop pending) branches (inc leaves) (+ entries (.len node)))
          (recur (into (pop pending) (map #(.child ^me.tonsky.persistent_sorted_set.Branch node store (int %))) (range (.len node)))
                 (inc branches) leaves entries))
        {:branch-nodes branches :leaf-nodes leaves :leaf-entries entries
         :entries-per-leaf (/ entries (double (max 1 leaves)))}))))

(defn- datahike-tree [^org.replikativ.persistent_sorted_set.PersistentSortedSet tree]
  (let [store (.-_storage tree)]
    (loop [pending [(.root tree)] branches 0 leaves 0 entries 0]
      (if-let [^org.replikativ.persistent_sorted_set.ANode node (peek pending)]
        (if (zero? (.level node))
          (recur (pop pending) branches (inc leaves) (+ entries (.len node)))
          (recur (into (pop pending) (map #(.child ^org.replikativ.persistent_sorted_set.Branch node store (int %))) (range (.len node)))
                 (inc branches) leaves entries))
        {:branch-nodes branches :leaf-nodes leaves :leaf-entries entries
         :entries-per-leaf (/ entries (double (max 1 leaves)))}))))

(defn index-density [backend database]
  (case backend
    :datascript (into {} (for [index [:eavt :aevt :avet]] [index (datascript-tree (get database index))]))
    :datahike (into {} (for [index [:eavt :aevt :avet]] [index (datahike-tree (get database index))]))
    :datalevin (let [lmdb (.-lmdb ^datalevin.storage.Store (:store database))]
                 (into {} (for [index (dl/list-dbis lmdb)]
                            [index (dl/stat lmdb index)])))
    :datomic {:unavailable "Datomic memory database does not expose native segment/page occupancy."}))

(defn run-density! [backend document-count output]
  (let [{:keys [client snapshot transact! rows durable-bytes close!]} (workload/open-system backend)
        alice (eacl/spice-object :user "alice")
        account (eacl/spice-object :account "account")
        document #(eacl/spice-object :document (format "document-%06d" %))]
    (try
      (eacl/write-schema! client workload/schema)
      (transact! (into [{:eacl/id "alice"} {:eacl/id "account"}]
                       (map #(hash-map :eacl/id (:id (document %)))) (range document-count)))
      (let [relationships (into [(eacl/->Relationship alice :owner account)]
                                (mapcat #(vector (eacl/->Relationship account :account (document %))
                                                 (eacl/->Relationship alice :viewer (document %))))
                                (range document-count))]
        (doseq [batch (partition-all 500 relationships)]
          (eacl/create-relationships! client (vec batch)))
        (let [db (snapshot)
              tuples (into [] (mapcat #(seq (rows db %))) storage/attributes)
              result {:backend backend :document-count document-count
                      :relationship-count (count relationships)
                      :logical-relationship-datoms (count tuples)
                      :index-density (index-density backend db)
                      :durable-apparent-bytes (durable-bytes)
                      :relationship-transaction-edn-bytes
                      (count (.getBytes (pr-str (mapv #(vector :db/add (:e %) (:a %) (:v %)) tuples)) "UTF-8"))}]
          (assert (= (* 2 (count relationships)) (count tuples)))
          (spit output (pr-str result))
          result))
      (finally (close!)))))
