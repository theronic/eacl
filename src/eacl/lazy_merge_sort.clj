(ns eacl.lazy-merge-sort)

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
             ;; Advance sequences that have the minimum value at their head
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (first s) min-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-val lowest)
           ;; Yield the value and continue with new lowest
           (cons min-val (lazy-merge-dedupe-sort min-val advanced-seqs))
           ;; Skip the value (deduplicate) and continue
           (lazy-merge-dedupe-sort lowest advanced-seqs)))))))

(defn lazy-parallel-merge-dedupe-sort
  "Same as lazy-merge-dedupe-sort but uses pmap instead of map. Need to benchmark first."
  ([lazy-colls] (lazy-parallel-merge-dedupe-sort 0 lazy-colls))
  ([lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (pmap first non-empty-seqs)
             min-val       (apply min heads)
             ;; Advance sequences that have the minimum value at their head
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (first s) min-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-val lowest)
           ;; Yield the value and continue with new lowest
           (cons min-val (lazy-parallel-merge-dedupe-sort min-val advanced-seqs))
           ;; Skip the value (deduplicate) and continue
           (lazy-parallel-merge-dedupe-sort lowest advanced-seqs)))))))

(defn lazy-merge-dedupe-sort-by
  "Lazily merges multiple _sorted_ sequences, deduplicating based on pred values.
   Takes a predicate function, an optional initial lowest pred value, and a collection of sorted sequences.
   Returns a lazy sequence of deduplicated, sorted values (original values, not pred results).
   If a sequence is not sorted by pred, any value with pred result less than prior lowest will be discarded."
  ([pred lazy-colls] (lazy-merge-dedupe-sort-by pred 0 lazy-colls))
  ([pred lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (map first non-empty-seqs)
             pred-vals     (map pred heads)
             min-pred-val  (apply min pred-vals)
             ;; Find the first value with the minimum pred value
             min-val       (first (filter #(= (pred %) min-pred-val) heads)) ; this can be optimized.
             ;; Advance sequences that have pred values <= minimum pred value
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (pred (first s)) min-pred-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-pred-val lowest)
           ;; Yield the value and continue with new lowest
           (cons min-val (lazy-merge-dedupe-sort-by pred min-pred-val advanced-seqs))
           ;; Skip the value (deduplicate) and continue
           (lazy-merge-dedupe-sort-by pred lowest advanced-seqs)))))))

(defn lazy-parallel-merge-dedupe-sort-by
  "Like lazy-merge-dedupe-sort-by but calls pmap instead of map. Need to benchmark."
  ([pred lazy-colls] (lazy-parallel-merge-dedupe-sort-by pred 0 lazy-colls))
  ([pred lowest lazy-colls]
   (lazy-seq
     (when-let [non-empty-seqs (seq (filter seq lazy-colls))]
       (let [heads         (pmap first non-empty-seqs)
             pred-vals     (map pred heads)
             min-pred-val  (apply min pred-vals)
             ;; Find the first value with the minimum pred value
             min-val       (first (filter #(= (pred %) min-pred-val) heads)) ; this can be optimized.
             ;; Advance sequences that have pred values <= minimum pred value
             advanced-seqs (map (fn [s]
                                  (if (and (seq s) (<= (pred (first s)) min-pred-val))
                                    (rest s)
                                    s))
                                lazy-colls)]
         (if (> min-pred-val lowest)
           ;; Yield the value and continue with new lowest
           (cons min-val (lazy-parallel-merge-dedupe-sort-by pred min-pred-val advanced-seqs))
           ;; Skip the value (deduplicate) and continue
           (lazy-parallel-merge-dedupe-sort-by pred lowest advanced-seqs)))))))


; Example:
(comment
  (let [seq1 [1 3 5 7 9]
        seq2 [2 3 6 -1 8 9 10]                              ; note unsorted -1 will be skipped because not sorted (undefined behaviour)
        seq3 [1 4 5 6 11]]
    (take 20 (lazy-merge-dedupe-sort [seq1 seq2 seq3])))
  ; => (1 2 3 4 5 6 7 8 9 10 11)

  (let [seq1 [[1 :a1] [3 :c1] [5 :e1] [7 :g1] [9 :i1]]
        seq2 [[2 :b2] [3 :c3] [6 :f1] [-1 :bad2] [8 :h2] [9 :i2] [10 :j2]] ; note unsorted -1 will be skipped because not sorted (undefined behaviour)
        seq3 [[1 :a3] [4 :d3] [5 :e3] [6 :f3] [11 :k3]]]
    (take 20 (lazy-merge-dedupe-sort-by first [seq1 seq2 seq3])))
  => ([1 :a1] [2 :b2] [3 :c1] [4 :d3] [5 :e1] [6 :f1] [7 :g1] [8 :h2] [9 :i1] [10 :j2] [11 :k3])
  #_nil)
