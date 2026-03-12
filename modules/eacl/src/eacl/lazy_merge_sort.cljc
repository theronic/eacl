(ns eacl.lazy-merge-sort
  "Optimized lazy merge-sort with deduplication for sorted sequences.

  Primary entry point: `lazy-fold2-merge-dedupe-sorted-by`.

  Legacy helper names remain as wrappers for compatibility with older tests.")

(def ^:private min-sentinel
  #?(:clj Long/MIN_VALUE
     :cljs js/Number.MIN_SAFE_INTEGER))

(defn- lazy-merge2-dedupe-longs
  ([x y] (lazy-merge2-dedupe-longs min-sentinel x y))
  ([last-key x y]
   (lazy-seq
    (let [sx (seq x)
          sy (seq y)]
      (cond
        (nil? sx)
        (when sy
          (if (= last-key min-sentinel)
            sy
            (drop-while #(= % last-key) y)))

        (nil? sy)
        (if (= last-key min-sentinel)
          sx
          (drop-while #(= % last-key) x))

        :else
        (let [xf (first sx)
              yf (first sy)]
          (cond
            (= xf yf)
            (if (= xf last-key)
              (lazy-merge2-dedupe-longs last-key (rest x) (rest y))
              (cons (first sx)
                (lazy-merge2-dedupe-longs xf (rest x) (rest y))))

            (< xf yf)
            (if (= xf last-key)
              (lazy-merge2-dedupe-longs last-key (rest x) y)
              (cons (first sx)
                (lazy-merge2-dedupe-longs xf (rest x) y)))

            :else
            (if (= yf last-key)
              (lazy-merge2-dedupe-longs last-key x (rest y))
              (cons (first sy)
                (lazy-merge2-dedupe-longs yf x (rest y)))))))))))

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

(defn- lazy-merge2-dedupe-by
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
            (= xk yk)
            (if (= xk last-key)
              (lazy-merge2-dedupe-by keyfn key-cmp last-key (rest x) (rest y))
              (cons xf
                (lazy-merge2-dedupe-by keyfn key-cmp xk (rest x) (rest y))))

            (key-cmp xk yk)
            (if (= xk last-key)
              (lazy-merge2-dedupe-by keyfn key-cmp last-key (rest x) y)
              (cons xf
                (lazy-merge2-dedupe-by keyfn key-cmp xk (rest x) y)))

            :else
            (if (= yk last-key)
              (lazy-merge2-dedupe-by keyfn key-cmp last-key x (rest y))
              (cons yf
                (lazy-merge2-dedupe-by keyfn key-cmp yk x (rest y)))))))))))

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

(defn lazy-merge-dedupe-sort
  [seqs]
  (lazy-fold2-merge-dedupe-sorted-by identity seqs))

(defn lazy-merge-dedupe-sort-by
  [keyfn seqs]
  (lazy-fold2-merge-dedupe-sorted-by keyfn seqs))
