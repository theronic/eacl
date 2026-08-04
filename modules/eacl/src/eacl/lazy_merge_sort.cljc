(ns eacl.lazy-merge-sort
  "Optimized lazy merge-sort with deduplication for sorted sequences.

  Primary entry point: `lazy-fold2-merge-dedupe-sorted-by`.

  Legacy helper names remain as wrappers for compatibility with older tests.")

(defn- lazy-merge2-dedupe-longs
  ([x y] (lazy-merge2-dedupe-longs false 0 x y))
  ([has-last? ^long last-key x y]
   (lazy-seq
    (let [sx (seq x)
          sy (seq y)]
      (cond
        (nil? sx)
        (when sy
          (if has-last?
            (drop-while #(== (long %) last-key) y)
            sy))

        (nil? sy)
        (if has-last?
          (drop-while #(== (long %) last-key) x)
          sx)

        :else
        (let [xf (long (first sx))
              yf (long (first sy))]
          (cond
            (== xf yf)
            (if (and has-last? (== xf last-key))
              (lazy-merge2-dedupe-longs true last-key (rest x) (rest y))
              (cons (first sx)
                    (lazy-merge2-dedupe-longs
                     true xf (rest x) (rest y))))

            (< xf yf)
            (if (and has-last? (== xf last-key))
              (lazy-merge2-dedupe-longs true last-key (rest x) y)
              (cons (first sx)
                    (lazy-merge2-dedupe-longs true xf (rest x) y)))

            :else
            (if (and has-last? (== yf last-key))
              (lazy-merge2-dedupe-longs true last-key x (rest y))
              (cons (first sy)
                    (lazy-merge2-dedupe-longs
                     true yf x (rest y)))))))))))

(defn- fold2-merge-dedupe-longs
  [seqs]
  (let [non-empty (vec (filter seq seqs))]
    (case (count non-empty)
      0 ()
      1 (first non-empty)
      2 (lazy-merge2-dedupe-longs (first non-empty) (second non-empty))
      (recur
       (mapv (fn [pair]
               (if (next pair)
                 (lazy-merge2-dedupe-longs (first pair) (second pair))
                 (first pair)))
             (partition-all 2 non-empty))))))

(defn- lazy-merge2-dedupe-longs-desc
  ([x y] (lazy-merge2-dedupe-longs-desc false 0 x y))
  ([has-last? ^long last-key x y]
   (lazy-seq
    (let [sx (seq x)
          sy (seq y)]
      (cond
        (nil? sx)
        (when sy
          (if has-last?
            (drop-while #(== (long %) last-key) y)
            sy))

        (nil? sy)
        (if has-last?
          (drop-while #(== (long %) last-key) x)
          sx)

        :else
        (let [xf (long (first sx))
              yf (long (first sy))]
          (cond
            (== xf yf)
            (if (and has-last? (== xf last-key))
              (lazy-merge2-dedupe-longs-desc
               true last-key (rest x) (rest y))
              (cons (first sx)
                    (lazy-merge2-dedupe-longs-desc
                     true xf (rest x) (rest y))))

            (> xf yf)
            (if (and has-last? (== xf last-key))
              (lazy-merge2-dedupe-longs-desc true last-key (rest x) y)
              (cons (first sx)
                    (lazy-merge2-dedupe-longs-desc
                     true xf (rest x) y)))

            :else
            (if (and has-last? (== yf last-key))
              (lazy-merge2-dedupe-longs-desc true last-key x (rest y))
              (cons (first sy)
                    (lazy-merge2-dedupe-longs-desc
                     true yf x (rest y)))))))))))

(defn- fold2-merge-dedupe-longs-desc
  [seqs]
  (let [non-empty (vec (filter seq seqs))]
    (case (count non-empty)
      0 ()
      1 (first non-empty)
      2 (lazy-merge2-dedupe-longs-desc (first non-empty) (second non-empty))
      (recur
       (mapv (fn [pair]
               (if (next pair)
                 (lazy-merge2-dedupe-longs-desc (first pair) (second pair))
                 (first pair)))
             (partition-all 2 non-empty))))))

(defn- lazy-merge2-dedupe-by
  ([keyfn key-cmp x y]
   (lazy-merge2-dedupe-by keyfn key-cmp false nil x y))
  ([keyfn key-cmp has-last? last-key x y]
   (lazy-seq
    (let [sx (seq x)
          sy (seq y)]
      (cond
        (nil? sx)
        (when sy
          (if has-last?
            (drop-while #(= (keyfn %) last-key) y)
            sy))

        (nil? sy)
        (if has-last?
          (drop-while #(= (keyfn %) last-key) x)
          sx)

        :else
        (let [xf (first sx)
              yf (first sy)
              xk (keyfn xf)
              yk (keyfn yf)]
          (cond
            (= xk yk)
            (if (and has-last? (= xk last-key))
              (lazy-merge2-dedupe-by
               keyfn key-cmp true last-key (rest x) (rest y))
              (cons xf
                    (lazy-merge2-dedupe-by
                     keyfn key-cmp true xk (rest x) (rest y))))

            (key-cmp xk yk)
            (if (and has-last? (= xk last-key))
              (lazy-merge2-dedupe-by
               keyfn key-cmp true last-key (rest x) y)
              (cons xf
                    (lazy-merge2-dedupe-by
                     keyfn key-cmp true xk (rest x) y)))

            :else
            (if (and has-last? (= yk last-key))
              (lazy-merge2-dedupe-by
               keyfn key-cmp true last-key x (rest y))
              (cons yf
                    (lazy-merge2-dedupe-by
                     keyfn key-cmp true yk x (rest y)))))))))))

(defn- fold2-merge-dedupe-generic
  [keyfn seqs]
  (let [key-cmp (fn [a b] (< (compare a b) 0))
        merge2 (fn [x y] (lazy-merge2-dedupe-by keyfn key-cmp x y))
        non-empty (vec (filter seq seqs))]
    (case (count non-empty)
      0 ()
      1 (first non-empty)
      2 (merge2 (first non-empty) (second non-empty))
      (recur
       keyfn
       (mapv (fn [pair]
               (if (next pair)
                 (merge2 (first pair) (second pair))
                 (first pair)))
             (partition-all 2 non-empty))))))

(defn lazy-fold2-merge-dedupe-sorted-by
  [keyfn seqs]
  (if (identical? keyfn identity)
    (fold2-merge-dedupe-longs seqs)
    (fold2-merge-dedupe-generic keyfn seqs)))

(defn lazy-fold2-merge-dedupe-sorted-by-desc
  [keyfn seqs]
  (if (identical? keyfn identity)
    (fold2-merge-dedupe-longs-desc seqs)
    (let [key-cmp (fn [a b] (> (compare a b) 0))
          merge2 (fn [x y] (lazy-merge2-dedupe-by keyfn key-cmp x y))]
      (loop [non-empty (vec (filter seq seqs))]
        (case (count non-empty)
          0 ()
          1 (first non-empty)
          2 (merge2 (first non-empty) (second non-empty))
          (recur
           (mapv (fn [pair]
                   (if (next pair)
                     (merge2 (first pair) (second pair))
                     (first pair)))
                 (partition-all 2 non-empty))))))))

(defn lazy-merge-dedupe-sort
  [seqs]
  (lazy-fold2-merge-dedupe-sorted-by identity seqs))

(defn lazy-merge-dedupe-sort-by
  [keyfn seqs]
  (lazy-fold2-merge-dedupe-sorted-by keyfn seqs))
