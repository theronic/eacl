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

;; AI Work:

(defn lazy-merge2-dedupe-by
  "Lazily merges two already-deduplicated sorted sequences, maintaining deduplication.
   keyfn extracts the comparison/deduplication key from each element.
   cmp compares two elements and returns true if first should come before second.
   Assumes both input sequences are already deduplicated."
  ([keyfn cmp x y]
   (lazy-merge2-dedupe-by keyfn cmp nil x y))
  ([keyfn cmp last-key x y]
   (lazy-seq
     (cond
       (empty? x)
       ;; Skip any remaining duplicates in y and return rest
       (when-let [s (seq (drop-while #(= (keyfn %) last-key) y))]
         s)

       (empty? y)
       ;; Skip any remaining duplicates in x and return rest
       (when-let [s (seq (drop-while #(= (keyfn %) last-key) x))]
         s)

       :else
       (let [xf (first x)
             yf (first y)
             xk (keyfn xf)
             yk (keyfn yf)]
         (cond
           ;; Both sequences have elements with the same key
           (= xk yk)
           (if (= xk last-key)
             ;; Duplicate of last emitted - skip both
             (lazy-merge2-dedupe-by keyfn cmp last-key (rest x) (rest y))
             ;; New value - emit first one, advance both
             (cons xf (lazy-merge2-dedupe-by keyfn cmp xk (rest x) (rest y))))

           ;; x element comes first
           (cmp xf yf)
           (if (= xk last-key)
             ;; Duplicate of last emitted - skip x
             (lazy-merge2-dedupe-by keyfn cmp last-key (rest x) y)
             ;; New value - emit x
             (cons xf (lazy-merge2-dedupe-by keyfn cmp xk (rest x) y)))

           ;; y element comes first
           :else
           (if (= yk last-key)
             ;; Duplicate of last emitted - skip y
             (lazy-merge2-dedupe-by keyfn cmp last-key x (rest y))
             ;; New value - emit y
             (cons yf (lazy-merge2-dedupe-by keyfn cmp yk x (rest y))))))))))

(defn lazy-merge-all-dedupe-by
  "Lazily merges a collection of sorted, deduplicated sequences while maintaining deduplication.
   keyfn extracts the comparison key.
   cmp is a comparison function.
   Returns empty seq if input is empty."
  [keyfn cmp seqs]
  (lazy-seq
    (let [non-empty (seq (filter seq seqs))]
      (when non-empty
        (if-let [[y] (next non-empty)]
          ;; Two or more sequences - merge first two with dedup
          (lazy-merge2-dedupe-by keyfn cmp (first non-empty) y)
          ;; Only one sequence left - return it as-is
          (first non-empty))))))

(defn lazy-fold2-merge-dedupe-sorted-by
  "Merges multiple sorted, deduplicated sequences using tournament-style folding with deduplication.
   keyfn extracts the comparison key from each element.
   Sequences should already be sorted and deduplicated according to (keyfn element).

   This combines the performance of lazy-fold2-merge-sorted-by with the deduplication
   of lazy-merge-dedupe-sort-by. Deduplication happens at each merge level in the
   tournament tree, which is much more efficient than deduplicating after merging all sequences.

   Example:
   (lazy-fold2-merge-dedupe-sorted-by identity
     [[1 3 5 7]
      [1 2 4 6 8]
      [0 5 9 10]])
   => (0 1 2 3 4 5 6 7 8 9 10)"
  [keyfn seqs]
  (fold2
    (partial lazy-merge-all-dedupe-by keyfn #(< (keyfn %1) (keyfn %2)))
    seqs))

;; Example usage:
(comment
  ;; Basic merge with deduplication
  (lazy-fold2-merge-dedupe-sorted-by identity
    [[1 3 5 7 9]
     [1 2 4 6 8]
     [0 5 9 10]])
  ;; => (0 1 2 3 4 5 6 7 8 9 10)

  ;; Works with the example from the original code
  (lazy-fold2-merge-dedupe-sorted-by identity
    '((17592186045501 17592186045502 17592186045503)
      (17592186045501)
      ()))
  ;; => (17592186045501 17592186045502 17592186045503)

  ;; Merge with custom key function
  (lazy-fold2-merge-dedupe-sorted-by first
    [[[1 :a1] [3 :c1] [5 :e1]]
     [[1 :a2] [2 :b2] [3 :c2] [5 :e2]]
     [[0 :z] [5 :e3] [9 :i]]])
  ;; => ([0 :z] [1 :a1] [2 :b2] [3 :c1] [5 :e1] [9 :i])

  ;; Works lazily with infinite sequences
  (take 10
    (lazy-fold2-merge-dedupe-sorted-by identity
      [(range 0 1000000 3)
       (range 0 1000000 5)
       (range 0 1000000 7)]))
  ;; => (0 3 5 6 7 9 10 12 14 15)

  ;; Handles many sequences efficiently
  (take 20
    (lazy-fold2-merge-dedupe-sorted-by identity
      (map #(range % 1000 %) (range 1 20)))))
  ;; Returns deduplicated merge of all ranges

