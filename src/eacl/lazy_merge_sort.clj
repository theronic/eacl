(ns eacl.lazy-merge-sort)

;; ========== Gen 1: Linear scan merge (O(k) per element) ==========
;; Retained for backward compatibility with existing tests.
;; See eacl.lazy-merge-sort.legacy for the oracle copy.

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

;; ========== Gen 3: Optimized pairwise merge with dedup ==========

;; --- Specialized fast path for identity keyfn (bare longs) ---

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
           (if (= last-key Long/MIN_VALUE)
             sy
             (drop-while #(= (long %) last-key) y)))

         (nil? sy)
         (if (= last-key Long/MIN_VALUE)
           sx
           (drop-while #(= (long %) last-key) x))

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
  "Tournament-style fold2 merge with dedup, specialized for long sequences."
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

;; --- General path for arbitrary keyfn ---

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

;; --- Heap-based k-way merge with dedup for longs ---

(defn- heap-merge-dedupe-longs
  "k-way merge using java.util.PriorityQueue with integrated dedup.
   O(log k) per element. Falls back to fold2 for k<=2."
  [seqs]
  (let [non-empty (vec (filter seq seqs))
        k (count non-empty)]
    (case k
      0 ()
      1 (first non-empty)
      2 (lazy-merge2-dedupe-longs (first non-empty) (second non-empty))
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

;; ========== Public API ==========

(defn lazy-fold2-merge-dedupe-sorted-by
  "Merges multiple sorted, deduplicated sequences using tournament-style folding with deduplication.
   keyfn extracts the comparison key from each element.
   Sequences should already be sorted and deduplicated according to (keyfn element).

   When keyfn is identity, uses a specialized fast path with primitive long comparison.
   For k > 2 with identity keyfn, uses a PriorityQueue-based k-way merge.

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
