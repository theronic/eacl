(ns eacl.lazy-merge-sort
  "Optimized lazy merge-sort with deduplication for sorted sequences.

   Primary entry point: `lazy-fold2-merge-dedupe-sorted-by`

   Uses tournament-style fold2 pairwise merging with O(n log k) complexity.
   When keyfn is identity, dispatches to a specialized fast path using
   primitive long comparison (==/<) to avoid boxing overhead.

   The fold2 tree construction is eager (mapv) but produces lazy merge nodes —
   no input elements are realized until the caller consumes the output sequence.
   This is O(k) work to build the tree structure, negligible for typical k=2-16.")

;; ========== Specialized fast path for identity keyfn (bare longs) ==========

(defn- lazy-merge2-dedupe-longs
  "Specialized 2-way merge-dedup for sorted sequences of longs.
   Uses primitive long comparison to avoid boxing overhead.
   No keyfn indirection — assumes elements are comparable longs."
  ([x y] (lazy-merge2-dedupe-longs Long/MIN_VALUE x y))
  ([^long last-key x y]
   (lazy-seq
     (let [sx (seq x)
           sy (seq y)]
       (cond
         (nil? sx)
         (when sy
           (if (== last-key Long/MIN_VALUE)
             sy
             (drop-while #(== (long %) last-key) y)))

         (nil? sy)
         (if (== last-key Long/MIN_VALUE)
           sx
           (drop-while #(== (long %) last-key) x))

         :else
         (let [xf (long (first sx))
               yf (long (first sy))]
           (cond
             ;; Both have same value
             (== xf yf)
             (if (== xf last-key)
               (lazy-merge2-dedupe-longs last-key (rest x) (rest y))
               (cons (first sx) (lazy-merge2-dedupe-longs xf (rest x) (rest y))))

             ;; x comes first
             (< xf yf)
             (if (== xf last-key)
               (lazy-merge2-dedupe-longs last-key (rest x) y)
               (cons (first sx) (lazy-merge2-dedupe-longs xf (rest x) y)))

             ;; y comes first
             :else
             (if (== yf last-key)
               (lazy-merge2-dedupe-longs last-key x (rest y))
               (cons (first sy) (lazy-merge2-dedupe-longs yf x (rest y)))))))))))

(defn- fold2-merge-dedupe-longs
  "Tournament-style fold2 merge with dedup, specialized for long sequences.
   Eagerly constructs the merge tree (O(k) lazy-seq wrappers), but no elements
   are realized until the output sequence is consumed."
  [seqs]
  (let [non-empty (vec (filter seq seqs))]
    (case (count non-empty)
      0 ()
      1 (first non-empty)
      2 (lazy-merge2-dedupe-longs (first non-empty) (second non-empty))
      (recur (mapv (fn [pair]
                     (if (next pair)
                       (lazy-merge2-dedupe-longs (first pair) (second pair))
                       (first pair)))
                   (partition-all 2 non-empty))))))

;; ========== General path for arbitrary keyfn ==========

(defn- lazy-merge2-dedupe-by
  "Lazily merges two already-deduplicated sorted sequences, maintaining deduplication.
   keyfn extracts the comparison/deduplication key from each element.
   key-cmp compares two extracted keys (not elements) — returns true if first < second."
  ([keyfn key-cmp x y]
   (lazy-merge2-dedupe-by keyfn key-cmp nil x y))
  ([keyfn key-cmp last-key x y]
   (lazy-seq
     (let [sx (seq x)
           sy (seq y)]
       (cond
         (nil? sx)
         (when sy
           (if (nil? last-key)
             sy
             (drop-while #(= (keyfn %) last-key) y)))

         (nil? sy)
         (if (nil? last-key)
           sx
           (drop-while #(= (keyfn %) last-key) x))

         :else
         (let [xf (first sx)
               yf (first sy)
               xk (keyfn xf)
               yk (keyfn yf)]
           (cond
             ;; Both have same key
             (= xk yk)
             (if (= xk last-key)
               (lazy-merge2-dedupe-by keyfn key-cmp last-key (rest x) (rest y))
               (cons xf (lazy-merge2-dedupe-by keyfn key-cmp xk (rest x) (rest y))))

             ;; x key comes first
             (key-cmp xk yk)
             (if (= xk last-key)
               (lazy-merge2-dedupe-by keyfn key-cmp last-key (rest x) y)
               (cons xf (lazy-merge2-dedupe-by keyfn key-cmp xk (rest x) y)))

             ;; y key comes first
             :else
             (if (= yk last-key)
               (lazy-merge2-dedupe-by keyfn key-cmp last-key x (rest y))
               (cons yf (lazy-merge2-dedupe-by keyfn key-cmp yk x (rest y)))))))))))

(defn- fold2-merge-dedupe-generic
  "Tournament-style fold2 merge with dedup, general keyfn."
  [keyfn seqs]
  (let [key-cmp (fn [a b] (< (compare a b) 0))
        merge2 (fn [x y] (lazy-merge2-dedupe-by keyfn key-cmp x y))
        non-empty (vec (filter seq seqs))]
    (case (count non-empty)
      0 ()
      1 (first non-empty)
      2 (merge2 (first non-empty) (second non-empty))
      (recur keyfn (mapv (fn [pair]
                           (if (next pair)
                             (merge2 (first pair) (second pair))
                             (first pair)))
                         (partition-all 2 non-empty))))))

;; ========== Public API ==========

(defn lazy-fold2-merge-dedupe-sorted-by
  "Merges multiple sorted, deduplicated sequences using tournament-style folding with deduplication.
   keyfn extracts the comparison key from each element.
   Sequences should already be sorted and deduplicated according to (keyfn element).

   When keyfn is identity, uses a specialized fast path with primitive long comparison.

   Example:
   (lazy-fold2-merge-dedupe-sorted-by identity
     [[1 3 5 7]
      [1 2 4 6 8]
      [0 5 9 10]])
   => (0 1 2 3 4 5 6 7 8 9 10)"
  [keyfn seqs]
  (if (identical? keyfn identity)
    (fold2-merge-dedupe-longs seqs)
    (fold2-merge-dedupe-generic keyfn seqs)))
