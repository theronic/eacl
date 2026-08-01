(ns eacl.causal-model
  "Pure reference state machine for causal-token, cache, and cursor properties.

  This namespace is test-only and intentionally shares no implementation with
  eacl.cache, eacl.cursor, or a storage adapter."
  (:require [clojure.set :as set]
            [eacl.authorization-oracle :as oracle]))

(defn initial-state
  [fixture]
  (let [anchor :genesis
        snapshot {:anchor anchor
                  :parents #{}
                  :fixture fixture
                  :proof (oracle/full-content-proof fixture)}]
    {:head anchor
     :next-id 1
     :snapshots {anchor snapshot}
     :retained #{anchor}
     :cache {}
     :cursors {}}))

(defn command
  [operation & [arguments]]
  {:operation operation
   :arguments (or arguments {})})

(defn graph-write
  [relationships]
  (command :graph-write {:relationships relationships}))

(defn unrelated-write
  []
  (command :unrelated-write))

(defn schema-write
  [rules]
  (command :schema-write {:rules rules}))

(defn cache-put
  [key snapshot-id value]
  (command :cache-put {:key key :snapshot-id snapshot-id :value value}))

(defn cache-read
  [key]
  (command :cache-read {:key key}))

(defn read-command
  [query]
  (command :read {:query query}))

(defn cursor-page
  [cursor-id page-size]
  (command :cursor-page {:cursor-id cursor-id :page-size page-size}))

(defn clone-head
  [snapshot-id]
  (command :clone {:snapshot-id snapshot-id}))

(defn reset-head
  [snapshot-id]
  (command :reset {:snapshot-id snapshot-id}))

(defn restore-head
  [snapshot-id]
  (command :restore {:snapshot-id snapshot-id}))

(defn branch-head
  [snapshot-id]
  (command :branch {:snapshot-id snapshot-id}))

(defn force-head
  [snapshot-id]
  (command :force-head {:snapshot-id snapshot-id}))

(defn expire-snapshot
  [snapshot-id]
  (command :retention-expire {:snapshot-id snapshot-id}))

(defn- next-anchor
  [state]
  (keyword "mutation" (str (:next-id state))))

(defn- publish
  [state update-fixture]
  (let [parent (:head state)
        anchor (next-anchor state)
        fixture (update-fixture (get-in state [:snapshots parent :fixture]))
        snapshot {:anchor anchor
                  :parents #{parent}
                  :fixture fixture
                  :proof (oracle/full-content-proof fixture)}]
    (-> state
        (assoc :head anchor)
        (update :next-id inc)
        (assoc-in [:snapshots anchor] snapshot)
        (update :retained conj anchor))))

(defn causal-ancestors
  [state snapshot-id]
  (loop [pending [snapshot-id]
         seen #{}]
    (if-let [candidate (peek pending)]
      (if (contains? seen candidate)
        (recur (pop pending) seen)
        (recur (into (pop pending)
                     (get-in state [:snapshots candidate :parents]))
               (conj seen candidate)))
      seen)))

(defn contains-anchor?
  [state snapshot-id anchor]
  (contains? (causal-ancestors state snapshot-id) anchor))

(defn selected-snapshot
  [state]
  (get-in state [:snapshots (:head state)]))

(defn- authorization-set
  [snapshot]
  (oracle/authorization-set (:fixture snapshot)))

(defn- query-result
  [snapshot query]
  (contains? (authorization-set snapshot) query))

(defn- cache-hit
  [state key]
  (let [selected (selected-snapshot state)
        candidate (get-in state [:cache key])
        candidate-id (:snapshot-id candidate)]
    (when (and candidate
               (contains-anchor? state (:head state) candidate-id)
               (= (:proof selected) (:proof candidate)))
      (:value candidate))))

(defn- stable-results
  [snapshot {:keys [permission resource-type]}]
  (->> (authorization-set snapshot)
       (keep (fn [[subject candidate-permission resource]]
               (when (and (= permission candidate-permission)
                          (= resource-type (:type resource)))
                 [subject resource])))
       (sort-by pr-str)
       vec))

(defn apply-command
  [state {:keys [operation arguments]}]
  (case operation
    :graph-write
    (publish state #(assoc % :relationships (:relationships arguments)))

    :unrelated-write
    (publish state identity)

    :schema-write
    (publish state #(assoc % :rules (:rules arguments)))

    :cache-put
    (let [{:keys [key snapshot-id value]} arguments]
      (assoc-in state [:cache key]
                {:snapshot-id snapshot-id
                 :proof (get-in state [:snapshots snapshot-id :proof])
                 :value value}))

    :cache-read
    (assoc state :last-result (cache-hit state (:key arguments)))

    :read
    (assoc state :last-result
           (query-result (selected-snapshot state) (:query arguments)))

    :cursor-page
    (let [{:keys [cursor-id page-size]} arguments
          cursor (get-in state [:cursors cursor-id])
          selected (selected-snapshot state)
          original (get-in state [:snapshots (:snapshot-id cursor)])
          snapshot (cond
                     (nil? cursor) selected
                     (= (:proof cursor) (:proof selected)) selected
                     (contains? (:retained state) (:snapshot-id cursor)) original
                     :else nil)]
      (if-not snapshot
        (assoc state :last-error :snapshot-expired)
        (let [results (stable-results snapshot (:query cursor))
              offset (or (:offset cursor) 0)
              page (subvec results
                           (min offset (count results))
                           (min (+ offset page-size) (count results)))]
          (-> state
              (assoc :last-result page)
              (assoc-in [:cursors cursor-id]
                        (assoc cursor
                               :snapshot-id (:anchor snapshot)
                               :proof (:proof snapshot)
                               :offset (+ offset (count page))))))))

    :clone
    (assoc state :head (:snapshot-id arguments))

    :reset
    (assoc state :head (:snapshot-id arguments))

    :restore
    (assoc state :head (:snapshot-id arguments))

    :branch
    (assoc state :head (:snapshot-id arguments))

    :force-head
    (assoc state :head (:snapshot-id arguments))

    :retention-expire
    (update state :retained disj (:snapshot-id arguments))

    (throw (ex-info "Unknown causal-model command."
                    {:operation operation
                     :arguments arguments}))))

(defn run-trace
  [state commands]
  (reductions apply-command state commands))

(defn install-cursor
  [state cursor-id query]
  (let [snapshot (selected-snapshot state)]
    (assoc-in state [:cursors cursor-id]
              {:snapshot-id (:anchor snapshot)
               :proof (:proof snapshot)
               :query query
               :offset 0})))

(defn generated-divergence-traces
  "Deterministic command traces covering every destructive head transition."
  [fixture]
  (for [head-command [clone-head reset-head restore-head branch-head force-head]]
    [(graph-write (:relationships fixture))
     (head-command :genesis)
     (unrelated-write)]))

(defn cache-result-equals-selected?
  [state key query]
  (let [cached (cache-hit state key)]
    (or (nil? cached)
        (= cached (query-result (selected-snapshot state) query)))))

(defn pages-equal-proof-graph?
  [state cursor-id pages]
  (let [cursor (get-in state [:cursors cursor-id])
        snapshot (get-in state [:snapshots (:snapshot-id cursor)])
        expected (stable-results snapshot (:query cursor))]
    (= expected (vec (mapcat identity pages)))))
