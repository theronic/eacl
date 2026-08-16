(ns eacl.exploration.identity-benchmark
  "Exploration-only allocation comparison for exact grant admission shapes."
  (:import [java.lang.management ManagementFactory]
           [java.util.concurrent TimeUnit]))

(def ^:private allocation-bean
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (instance? com.sun.management.ThreadMXBean bean)
      (let [bean ^com.sun.management.ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(defn- allocated-bytes
  []
  (when allocation-bean
    (.getThreadAllocatedBytes
     ^com.sun.management.ThreadMXBean allocation-bean
     (.getId (Thread/currentThread)))))

(deftype PairKey [^long node ^long eid]
  Object
  (hashCode [_]
    (unchecked-add-int
     (unchecked-multiply-int 31 (Long/hashCode node))
     (Long/hashCode eid)))
  (equals [_ other]
    (and (instance? PairKey other)
         (= node (.-node ^PairKey other))
         (= eid (.-eid ^PairKey other)))))

(defn- flat-vector-admission
  [entries node-count]
  (loop [index 0
         admitted #{}]
    (if (= index entries)
      admitted
      (recur (inc index)
             (conj admitted [(rem index node-count) index])))))

(defn- transient-flat-vector-admission
  [entries node-count]
  (loop [index 0
         admitted (transient #{})]
    (if (= index entries)
      (persistent! admitted)
      (recur (inc index)
             (conj! admitted [(rem index node-count) index])))))

(defn- nested-vector-admission
  [entries node-count]
  (loop [index 0
         admitted (vec (repeat node-count #{}))]
    (if (= index entries)
      admitted
      (let [node (rem index node-count)]
        (recur (inc index)
               (assoc admitted node
                      (conj (nth admitted node) index)))))))

(defn- sparse-nested-map-admission
  [entries node-count]
  (loop [index 0
         admitted {}]
    (if (= index entries)
      admitted
      (let [node (rem index node-count)]
        (recur (inc index)
               (assoc admitted node
                      (conj (get admitted node #{}) index)))))))

(defn- custom-pair-admission
  [entries node-count]
  (loop [index 0
         admitted #{}]
    (if (= index entries)
      admitted
      (recur (inc index)
             (conj admitted
                   (PairKey. (long (rem index node-count))
                             (long index)))))))

(defn- transient-custom-pair-admission
  [entries node-count]
  (loop [index 0
         admitted (transient #{})]
    (if (= index entries)
      (persistent! admitted)
      (recur (inc index)
             (conj! admitted
                    (PairKey. (long (rem index node-count))
                              (long index)))))))

(defn- packed-long-admission
  [entries node-count]
  (loop [index 0
         admitted #{}]
    (if (= index entries)
      admitted
      (let [node (long (rem index node-count))
            eid (long index)
            packed (bit-or (bit-shift-left node 48) eid)]
        (recur (inc index) (conj admitted packed))))))

(defn- transient-packed-long-admission
  [entries node-count]
  (loop [index 0
         admitted (transient #{})]
    (if (= index entries)
      (persistent! admitted)
      (let [node (long (rem index node-count))
            eid (long index)
            packed (bit-or (bit-shift-left node 48) eid)]
        (recur (inc index) (conj! admitted packed))))))

(defn- inline-then-set-admission
  [entries node-count threshold]
  (loop [index 0
         admitted []]
    (if (= index entries)
      admitted
      (let [identity [(rem index node-count) index]]
        (cond
          (set? admitted)
          (recur (inc index) (conj admitted identity))

          (some #(= identity %) admitted)
          (recur (inc index) admitted)

          (< (count admitted) threshold)
          (recur (inc index) (conj admitted identity))

          :else
          (recur (inc index) (conj (set admitted) identity)))))))

(defn- persistent-stack-cycle
  [entries]
  (let [stack (loop [index 0
                     stack []]
                (if (= index entries)
                  stack
                  (recur (inc index) (conj stack index))))]
    (loop [remaining entries
           stack stack]
      (if (zero? remaining)
        stack
        (recur (dec remaining) (pop stack))))))

(defn- transient-stack-cycle
  [entries]
  (let [stack (loop [index 0
                     stack (transient [])]
                (if (= index entries)
                  stack
                  (recur (inc index) (conj! stack index))))]
    (loop [remaining entries
           stack stack]
      (if (zero? remaining)
        (persistent! stack)
        (recur (dec remaining) (pop! stack))))))

(defn- measure
  [label f repetitions]
  (dotimes [_ 4] (f))
  (let [before-bytes (allocated-bytes)
        before-time (System/nanoTime)
        retained (loop [remaining repetitions
                        retained nil]
                   (if (zero? remaining)
                     retained
                     (recur (dec remaining) (f))))
        elapsed (- (System/nanoTime) before-time)
        allocated (when before-bytes
                    (- (allocated-bytes) before-bytes))]
    {:shape label
     :repetitions repetitions
     :milliseconds-per-run
     (/ (double elapsed) repetitions 1000000.0)
     :allocated-bytes-per-run
     (when allocated (/ (double allocated) repetitions))
     :retained-logical-count
     (if (and (vector? retained)
              (every? set? retained))
       (reduce + (map count retained))
       (if (and (map? retained) (every? set? (vals retained)))
         (reduce + (map count (vals retained)))
         (count retained)))}))

(defn run-benchmarks!
  []
  (let [node-count 64
        small-snapshot (transient-flat-vector-admission 8 node-count)
        large-snapshot (transient-flat-vector-admission 100000 node-count)]
    {:small
     (mapv (fn [[label f]] (measure label f 20000))
           [[:flat-vector #(flat-vector-admission 8 node-count)]
            [:transient-flat-vector
             #(transient-flat-vector-admission 8 node-count)]
            [:nested-vector #(nested-vector-admission 8 node-count)]
            [:sparse-nested-map
             #(sparse-nested-map-admission 8 node-count)]
            [:custom-pair #(custom-pair-admission 8 node-count)]
            [:transient-custom-pair
             #(transient-custom-pair-admission 8 node-count)]
            [:packed-long #(packed-long-admission 8 node-count)]
            [:transient-packed-long
             #(transient-packed-long-admission 8 node-count)]
            [:inline-then-set
             #(inline-then-set-admission 8 node-count 8)]])
     :large
     (mapv (fn [[label f]] (measure label f 3))
           [[:flat-vector #(flat-vector-admission 100000 node-count)]
            [:transient-flat-vector
             #(transient-flat-vector-admission 100000 node-count)]
            [:nested-vector #(nested-vector-admission 100000 node-count)]
            [:sparse-nested-map
             #(sparse-nested-map-admission 100000 node-count)]
            [:custom-pair #(custom-pair-admission 100000 node-count)]
            [:transient-custom-pair
             #(transient-custom-pair-admission 100000 node-count)]
            [:packed-long #(packed-long-admission 100000 node-count)]
            [:transient-packed-long
             #(transient-packed-long-admission 100000 node-count)]])
     :checkpoint-fork
     [(measure :small-freeze-fork
               #(persistent! (transient small-snapshot))
               20000)
      (measure :small-fork-one-admission
               #(let [branch (transient small-snapshot)]
                  (persistent! (conj! branch [:branch 100001])))
               20000)
      (measure :large-freeze-fork
               #(persistent! (transient large-snapshot))
               20000)
      (measure :large-fork-one-admission
               #(let [branch (transient large-snapshot)]
                  (persistent! (conj! branch [:branch 100001])))
               20000)]
     :stack
     [(measure :small-persistent-stack
               #(persistent-stack-cycle 8)
               20000)
      (measure :small-transient-stack
               #(transient-stack-cycle 8)
               20000)
      (measure :large-persistent-stack
               #(persistent-stack-cycle 100000)
               3)
      (measure :large-transient-stack
               #(transient-stack-cycle 100000)
               3)]}))

(defn verify-transient-contract!
  []
  (let [snapshot #{[:base 1]}
        left-builder (transient snapshot)
        right-builder (transient snapshot)
        left (persistent! (conj! left-builder [:left 2]))
        right (persistent! (conj! right-builder [:right 3]))
        retired-builder (transient snapshot)
        frozen (persistent! retired-builder)
        use-after-free-rejected?
        (try
          (conj! retired-builder [:illegal 4])
          false
          (catch Throwable _ true))]
    (when-not (and (= snapshot frozen)
                   (= #{[:base 1]} snapshot)
                   (= #{[:base 1] [:left 2]} left)
                   (= #{[:base 1] [:right 3]} right)
                   (not (contains? left [:right 3]))
                   (not (contains? right [:left 2]))
                   use-after-free-rejected?)
      (throw (ex-info "Transient snapshot contract failed."
                      {:snapshot snapshot
                       :left left
                       :right right
                       :frozen frozen
                       :use-after-free-rejected?
                       use-after-free-rejected?})))
    {:forks-independent true
     :freeze-preserves-values true
     :use-after-free-rejected true}))
