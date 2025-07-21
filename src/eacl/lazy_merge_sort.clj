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
       (let [heads (map first non-empty-seqs)
             min-val (apply min heads)
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

; Example:
(comment
  (let [seq1 [1 3 5 7 9]
        seq2 [2 3 6 -1 8 9 10] ; note unsorted -1 will be skipped because not sorted (undefined behaviour)
        seq3 [1 4 5 6 11]]
    (take 20 (lazy-merge-dedupe-sort [seq1 seq2 seq3]))))
  ; => (1 2 3 4 5 6 7 8 9 10 11)
