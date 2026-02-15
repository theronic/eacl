(ns eacl.legacy.lazy-merge-sort
  "Legacy and rejected merge-sort algorithms retained as correctness oracles.
   Gen 1: O(k) per element linear scan — known correct, used for oracle testing.
   Gen 2: Pairwise merge without dedup — intermediate implementation.
   Heap: O(log k) per element PriorityQueue — benchmarked slower than fold2.
   None of these are used in production. New algorithms must produce identical output.")

;; ========== Gen 1: Linear scan merge (O(k) per element) ==========

(defn lazy-merge-dedupe-sort
  "Lazily merges multiple _sorted_ sequences, deduplicating values.
   Takes an optional initial lowest value (default: 0) and a collection of sorted sequences.
   Returns a lazy sequence of deduplicated, sorted values.
   If a sequence is not sorted, any value than prior lowest will be discarded, but this is undefined behaviour."
  ([lazy-colls] (lazy-merge-dedupe-sort 0 lazy-colls))
  ([lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (map first non-empty-seqs)
             min-val       (apply min heads)
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (first s) min-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-val lowest)
           (cons min-val (lazy-merge-dedupe-sort min-val advanced-seqs))
           (lazy-merge-dedupe-sort lowest advanced-seqs)))))))

(defn lazy-parallel-merge-dedupe-sort
  "Same as lazy-merge-dedupe-sort but uses pmap instead of map."
  ([lazy-colls] (lazy-parallel-merge-dedupe-sort 0 lazy-colls))
  ([lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (pmap first non-empty-seqs)
             min-val       (apply min heads)
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (first s) min-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-val lowest)
           (cons min-val (lazy-parallel-merge-dedupe-sort min-val advanced-seqs))
           (lazy-parallel-merge-dedupe-sort lowest advanced-seqs)))))))

(defn lazy-merge-dedupe-sort-by
  "Lazily merges multiple _sorted_ sequences, deduplicating based on pred values."
  ([pred lazy-colls] (lazy-merge-dedupe-sort-by pred 0 lazy-colls))
  ([pred lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (map first non-empty-seqs)
             pred-vals     (map pred heads)
             min-pred-val  (apply min pred-vals)
             min-val       (first (filter #(= (pred %) min-pred-val) heads))
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (pred (first s)) min-pred-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-pred-val lowest)
           (cons min-val (lazy-merge-dedupe-sort-by pred min-pred-val advanced-seqs))
           (lazy-merge-dedupe-sort-by pred lowest advanced-seqs)))))))

(defn lazy-parallel-merge-dedupe-sort-by
  "Like lazy-merge-dedupe-sort-by but calls pmap instead of map."
  ([pred lazy-colls] (lazy-parallel-merge-dedupe-sort-by pred 0 lazy-colls))
  ([pred lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (pmap first non-empty-seqs)
             pred-vals     (map pred heads)
             min-pred-val  (apply min pred-vals)
             min-val       (first (filter #(= (pred %) min-pred-val) heads))
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (pred (first s)) min-pred-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-pred-val lowest)
           (cons min-val (lazy-parallel-merge-dedupe-sort-by pred min-pred-val advanced-seqs))
           (lazy-parallel-merge-dedupe-sort-by pred lowest advanced-seqs)))))))

;; ========== Gen 2: Pairwise merge (no dedup) ==========

(defn lazy-merge2
  "Lazily merges two sorted sequences using a comparison function.
   cmp should return true if first arg should come before second arg."
  [cmp x y]
  (lazy-seq
    (cond
      (nil? (seq x)) y
      (nil? (seq y)) x
      :else
      (let [[xf & xn] x
            [yf & yn] y]
        (if (cmp xf yf)
          (cons xf (lazy-merge2 cmp xn y))
          (cons yf (lazy-merge2 cmp x yn)))))))

(defn lazy-merge-all
  "Lazily merges a collection of sorted sequences."
  [cmp seqs]
  (lazy-seq
    (let [non-empty (seq (filter seq seqs))]
      (when non-empty
        (if-let [[y] (next non-empty)]
          (lazy-merge2 cmp (first non-empty) y)
          (first non-empty))))))

(defn fold2
  "Repeatedly applies function f to pairs of elements until one remains.
   Uses a tournament-style folding approach. Eager tree construction."
  [f s]
  (loop [s (vec s)]
    (case (count s)
      0 (f s)
      1 (f s)
      2 (f s)
      (recur (mapv f (partition-all 2 s))))))

(defn lazy-fold2-merge-sorted
  "Merges multiple sorted sequences using lazy fold2 algorithm."
  [cmp seqs]
  (fold2 (partial lazy-merge-all cmp) seqs))

(defn lazy-fold2-merge-sorted-by
  "Merges multiple sorted sequences using lazy fold2 algorithm.
   keyfn extracts the comparison key from each element."
  [keyfn seqs]
  (lazy-fold2-merge-sorted #(< (keyfn %1) (keyfn %2)) seqs))

(defn dedupe-by
  "Returns a lazy sequence removing consecutive duplicates in coll based on the key function f.
  Returns a transducer when no collection is provided."
  {:added "1.7"}
  ([f]
   (fn [rf]
     (let [pv (volatile! ::none)]
       (fn
         ([] (rf))
         ([result] (rf result))
         ([result input]
          (let [prior @pv
                current (f input)]
            (vreset! pv current)
            (if (= prior current)
              result
              (rf result input))))))))
  ([f coll] (sequence (dedupe-by f) coll)))

;; ========== Rejected: Heap-based k-way merge ==========
;; Benchmarked slower than fold2 at all k values (2-64).

(defn heap-merge-dedupe-longs
  "k-way merge using java.util.PriorityQueue with integrated dedup.
   O(log k) per element. Falls back to fold2 for k<=2.
   REJECTED: Slower than fold2 due to Comparator reify, object-array wrapper, Long boxing."
  [merge2-fn seqs]
  (let [non-empty (vec (filter seq seqs))
        k (count non-empty)]
    (case k
      0 ()
      1 (first non-empty)
      2 (merge2-fn (first non-empty) (second non-empty))
      ;; k > 2: use heap
      (let [^java.util.PriorityQueue heap
            (java.util.PriorityQueue. (int k)
              (reify java.util.Comparator
                (compare [_ a b]
                  (Long/compare (long (first (aget ^objects a 0)))
                                (long (first (aget ^objects b 0)))))))]
        (doseq [s non-empty]
          (.offer heap (doto (object-array 1) (aset 0 (seq s)))))
        ((fn step [^long last-key]
           (lazy-seq
             (loop []
               (when-let [^objects entry (.poll heap)]
                 (let [s (aget entry 0)
                       v (first s)
                       vl (long v)
                       rst (next s)]
                   (when rst
                     (aset entry 0 rst)
                     (.offer heap entry))
                   (if (== vl last-key)
                     (recur)
                     (cons v (step vl))))))))
         Long/MIN_VALUE)))))
