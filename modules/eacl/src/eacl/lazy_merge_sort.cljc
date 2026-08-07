(ns eacl.lazy-merge-sort
  "Bounded lazy merge/deduplication for backend-ordered EID streams.

  The algorithm is specified by formal/dafny/OrderedMerge.dfy and exercised
  against the generated ordered-merge authority by the formal smoke suite.")

(defn- lazy-merge2-dedupe-longs
  ([left right]
   (lazy-merge2-dedupe-longs false 0 left right))
  ([has-last? last-key left right]
   (lazy-seq
    (let [left' (seq left)
          right' (seq right)]
      (cond
        (nil? left')
        (when right'
          (if has-last?
            (drop-while #(== (long %) (long last-key)) right')
            right'))

        (nil? right')
        (if has-last?
          (drop-while #(== (long %) (long last-key)) left')
          left')

        :else
        (let [left-key (long (first left'))
              right-key (long (first right'))]
          (cond
            (== left-key right-key)
            (if (and has-last? (== left-key (long last-key)))
              (lazy-merge2-dedupe-longs
               true last-key (next left') (next right'))
              (cons
               (first left')
               (lazy-merge2-dedupe-longs
                true left-key (next left') (next right'))))

            (< left-key right-key)
            (if (and has-last? (== left-key (long last-key)))
              (lazy-merge2-dedupe-longs
               true last-key (next left') right')
              (cons
               (first left')
               (lazy-merge2-dedupe-longs
                true left-key (next left') right')))

            :else
            (if (and has-last? (== right-key (long last-key)))
              (lazy-merge2-dedupe-longs
               true last-key left' (next right'))
              (cons
               (first right')
               (lazy-merge2-dedupe-longs
                true right-key left' (next right')))))))))))

(defn- lazy-merge2-dedupe-longs-desc
  ([left right]
   (lazy-merge2-dedupe-longs-desc false 0 left right))
  ([has-last? last-key left right]
   (lazy-seq
    (let [left' (seq left)
          right' (seq right)]
      (cond
        (nil? left')
        (when right'
          (if has-last?
            (drop-while #(== (long %) (long last-key)) right')
            right'))

        (nil? right')
        (if has-last?
          (drop-while #(== (long %) (long last-key)) left')
          left')

        :else
        (let [left-key (long (first left'))
              right-key (long (first right'))]
          (cond
            (== left-key right-key)
            (if (and has-last? (== left-key (long last-key)))
              (lazy-merge2-dedupe-longs-desc
               true last-key (next left') (next right'))
              (cons
               (first left')
               (lazy-merge2-dedupe-longs-desc
                true left-key (next left') (next right'))))

            (> left-key right-key)
            (if (and has-last? (== left-key (long last-key)))
              (lazy-merge2-dedupe-longs-desc
               true last-key (next left') right')
              (cons
               (first left')
               (lazy-merge2-dedupe-longs-desc
                true left-key (next left') right')))

            :else
            (if (and has-last? (== right-key (long last-key)))
              (lazy-merge2-dedupe-longs-desc
               true last-key left' (next right'))
              (cons
               (first right')
               (lazy-merge2-dedupe-longs-desc
                true right-key left' (next right')))))))))))

(defn- fold-pairs
  [merge-two streams]
  (loop [remaining (vec (filter seq streams))]
    (case (count remaining)
      0 ()
      1 (first remaining)
      2 (merge-two (first remaining) (second remaining))
      (recur
       (mapv
        (fn [pair]
          (if (next pair)
            (merge-two (first pair) (second pair))
            (first pair)))
        (partition-all 2 remaining))))))

(defn- lazy-merge2-dedupe-by
  ([key-fn before? left right]
   (lazy-merge2-dedupe-by key-fn before? false nil left right))
  ([key-fn before? has-last? last-key left right]
   (lazy-seq
    (let [left' (seq left)
          right' (seq right)]
      (cond
        (nil? left')
        (when right'
          (if has-last?
            (drop-while #(= (key-fn %) last-key) right')
            right'))

        (nil? right')
        (if has-last?
          (drop-while #(= (key-fn %) last-key) left')
          left')

        :else
        (let [left-value (first left')
              right-value (first right')
              left-key (key-fn left-value)
              right-key (key-fn right-value)]
          (cond
            (= left-key right-key)
            (if (and has-last? (= left-key last-key))
              (lazy-merge2-dedupe-by
               key-fn before? true last-key
               (next left') (next right'))
              (cons
               left-value
               (lazy-merge2-dedupe-by
                key-fn before? true left-key
                (next left') (next right'))))

            (before? left-key right-key)
            (if (and has-last? (= left-key last-key))
              (lazy-merge2-dedupe-by
               key-fn before? true last-key
               (next left') right')
              (cons
               left-value
               (lazy-merge2-dedupe-by
                key-fn before? true left-key
                (next left') right')))

            :else
            (if (and has-last? (= right-key last-key))
              (lazy-merge2-dedupe-by
               key-fn before? true last-key
               left' (next right'))
              (cons
               right-value
               (lazy-merge2-dedupe-by
                key-fn before? true right-key
                left' (next right')))))))))))

(defn lazy-fold2-merge-dedupe-sorted-by
  [key-fn streams]
  (if (identical? key-fn identity)
    (fold-pairs lazy-merge2-dedupe-longs streams)
    (fold-pairs
     #(lazy-merge2-dedupe-by
       key-fn (fn [left right] (neg? (compare left right))) %1 %2)
     streams)))

(defn lazy-fold2-merge-dedupe-sorted-by-desc
  [key-fn streams]
  (if (identical? key-fn identity)
    (fold-pairs lazy-merge2-dedupe-longs-desc streams)
    (fold-pairs
     #(lazy-merge2-dedupe-by
       key-fn (fn [left right] (pos? (compare left right))) %1 %2)
     streams)))
