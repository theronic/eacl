(ns eacl.formal.qualified.discovery-model
  "Finite completion-set oracle for weighted first-discovery propagation.
   This model is verification-only and has no production dependencies."
  (:require [clojure.set :as set]))

(defn denotation
  "Independent per-world reachability, then union worlds at each vertex."
  [graph seeds universe]
  (reduce
   (fn [answer world]
     (loop [pending (vec (for [[node worlds] seeds :when (contains? worlds world)] node))
            visited #{}]
       (if-let [node (peek pending)]
         (if (contains? visited node)
           (recur (pop pending) visited)
           (recur (into (pop pending)
                        (for [[target worlds] (get graph node) :when (contains? worlds world)] target))
                  (conj visited node)))
         (reduce #(update %1 %2 (fnil conj #{}) world) answer visited))))
   {} universe))

(defn admit
  "Coalesces a changed prefix at its queued position, rewinding its scan."
  [state node incoming]
  (let [previous (get-in state [:values node] #{})
        joined (set/union previous incoming)]
    (if (= previous joined)
      state
      (-> state
          (assoc-in [:values node] joined)
          (assoc-in [:pending node] {:index 0 :worlds joined})
          (cond-> (not (contains? (:pending state) node)) (update :stack conj node))
          (update :changes inc)))))

(defn initial [seeds]
  ;; Initial seeds are staged together in canonical order, as in enumeration.
  (let [state (reduce (fn [state [node worlds]] (admit state node worlds))
                      {:values {} :pending {} :stack [] :changes 0 :steps 0 :emitted []} seeds)]
    (update state :stack #(vec (reverse %)))))

(defn run
  "Runs width-one transitions, stopping only after a complete node discovery."
  [graph initial target]
  (loop [state initial]
    (when (> (:steps state) 10000)
      (throw (ex-info "Finite discovery model exceeded its termination bound" {})))
    (if (or (empty? (:stack state)) (>= (count (:emitted state)) target))
      state
      (let [node (peek (:stack state))
            {:keys [index worlds]} (get-in state [:pending node])
            edges (get graph node [])
            state (-> state
                      (update :steps inc)
                      (cond-> (not (some #{node} (:emitted state))) (update :emitted conj node)))]
        (if (= index (count edges))
          (recur (-> state (update :stack pop) (update :pending dissoc node)))
          (let [[child edge-worlds] (nth edges index)
                state (assoc-in state [:pending node :index] (inc index))]
            (recur (admit state child (set/intersection worlds edge-worlds)))))))))
