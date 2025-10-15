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

; Experimenting with answers from https://stackoverflow.com/questions/62806958/fast-sorting-algorithm-for-multiple-ordered-arrays

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
  "Lazily merges a collection of sorted sequences.
   Returns empty seq if input is empty."
  [cmp seqs]
  (lazy-seq
    (let [non-empty (seq (filter seq seqs))]
      (when non-empty
        (if-let [[y] (next non-empty)]
          ;; Two or more sequences
          (lazy-merge2 cmp (first non-empty) y)
          ;; Only one sequence left
          (first non-empty))))))

(defn fold2
  "Repeatedly applies function f to pairs of elements until one remains.
   Uses a tournament-style folding approach."
  [f s]
  (loop [s s]
    (if (next (next s)) ; if more than 2 elements
      (recur (map f (partition-all 2 s)))
      (f s))))

(defn lazy-fold2-merge-sorted
  "Merges multiple sorted sequences using lazy fold2 algorithm.
   Sequences should already be sorted according to cmp.
   cmp is a comparison function that returns true if first arg < second arg."
  [cmp seqs]
  (prn 'lazy-fold2-merge-sorted cmp seqs)
  (fold2 (partial lazy-merge-all cmp) seqs))

(defn lazy-fold2-merge-sorted-by
  "Merges multiple sorted sequences using lazy fold2 algorithm.
   keyfn extracts the comparison key from each element.
   Sequences should already be sorted according to (keyfn element).

   Example:
   (lazy-fold2-merge-sorted-by identity [[1 3 5] [2 4 6] [0 7 8]])"
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

(comment
  (dedupe-by first [[:a 1] [:a 2] [:b 1]]))

;; Example usage:
(comment

  (lazy-fold2-merge-sorted-by identity
    '((17592186045501 17592186045502 17592186045503) (17592186045501) ()))

  ;; Merge sorted sequences of numbers
  (lazy-fold2-merge-sorted-by identity
    [[1 3 5 7]
     [1 2 4 6 8]
     [0 9 10]])
  ;; => (0 1 2 3 4 5 6 7 8 9 10)

  ;; Merge sorted sequences by custom key
  (lazy-fold2-merge-sorted-by :priority
    [{:priority 1 :name "low"}
     {:priority 5 :name "high"}]
    [{:priority 2 :name "medium"}
     {:priority 3 :name "mid"}])

  ;; Works lazily - doesn't realize entire sequence at once
  (take 5
    (lazy-fold2-merge-sorted-by identity
      [(range 0 1000 2)
       (range 1 1000 2)
       (range 0 1000 3)])))
;; => (0 0 1 2 2)
