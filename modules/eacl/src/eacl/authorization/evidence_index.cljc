(ns eacl.authorization.evidence-index
  "Bounded positional evidence aggregation for incremental operator joins.
   A changed child updates only its ancestor path; unchanged roots still
   retain changed leaves for later transitions."
  (:require [eacl.authorization.evidence :as evidence]))

(def maximum-width 1048576)
(defrecord EvidenceIndex [operation width offset tree])

(defn value [index] (nth (:tree index) 1))
(defn storage-weight [index] (count (:tree index)))

(defn build
  "Builds a deterministic balanced tree. `step!` charges each composition to
   the owning evaluator's existing work and cancellation contract."
  [operation values step!]
  (when-not (and (contains? #{:union :intersection} operation) (vector? values)
                (<= (count values) maximum-width) (fn? step!))
    (evidence/error! :index-shape))
  (let [width (count values)
        offset (loop [n 1] (if (>= n width) n (recur (* 2 n))))
        identity (= operation :intersection)
        tree (loop [i 0 tree (transient (vec (repeat (* 2 offset) identity)))]
               (if (= i width)
                 tree
                 (recur (inc i) (assoc! tree (+ offset i) (nth values i)))))
        tree (loop [i (dec offset) tree tree]
               (if (zero? i)
                 (persistent! tree)
                 (do
                   (step!)
                   (recur (dec i) (assoc! tree i (evidence/combine operation
                                                                 (nth tree (* 2 i))
                                                                 (nth tree (inc (* 2 i)))))))))]
    (->EvidenceIndex operation width offset tree)))

(defn replace-slot
  "Replaces one already validated child value, preserving canonical evidence.
   The tree shape and therefore witness selection stay fixed for its lifetime."
  [index slot replacement step!]
  (when-not (and (integer? slot) (<= 0 slot) (< slot (:width index)) (fn? step!))
    (evidence/error! :index-slot))
  (let [leaf (+ (:offset index) slot)]
    (if (= replacement (nth (:tree index) leaf))
      index
      (assoc index :tree
             (loop [i (quot leaf 2) tree (assoc (:tree index) leaf replacement)]
               (if (zero? i)
                 tree
                 (do
                   (step!)
                   (let [next-value (evidence/combine (:operation index)
                                                       (nth tree (* 2 i))
                                                       (nth tree (inc (* 2 i))))]
                     (if (= next-value (nth tree i))
                       tree
                       (recur (quot i 2) (assoc tree i next-value)))))))))))
