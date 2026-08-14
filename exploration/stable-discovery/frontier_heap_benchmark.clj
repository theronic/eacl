(ns eacl.exploration.frontier-heap-benchmark
  "Exploration-only retained-heap measurements for deep frontier shapes.

  These are JVM observations after explicit full GC, not portable byte
  theorems.  The specialized classes model the minimum proposed primitive
  fields; the map shape models the current source prototype."
  (:refer-clojure :exclude [run!])
  (:import [java.lang.management ManagementFactory]))

(deftype GoalKey [^long node ^long eid]
  Object
  (hashCode [_]
    (unchecked-add-int
     (unchecked-multiply-int 31 (Long/hashCode node))
     (Long/hashCode eid)))
  (equals [_ other]
    (and (instance? GoalKey other)
         (= node (.-node ^GoalKey other))
         (= eid (.-eid ^GoalKey other)))))

(deftype ScanFrame
  [^long kind
   ^long rule
   ^long anchor
   ^long bound])

(deftype SidecarEntry
  [^long logical-bound
   values
   ^boolean more-physical])

(defn- collection-count
  []
  (let [counts
        (keep
         (fn [collector]
           (let [count' (.getCollectionCount collector)]
             (when-not (= -1 count') count')))
         (ManagementFactory/getGarbageCollectorMXBeans))]
    (when (seq counts) (reduce + counts))))

(defn- stabilized-heap-snapshot!
  []
  (let [before (collection-count)]
    (dotimes [_ 4]
      (System/gc)
      (Thread/sleep 100))
    (let [after (collection-count)
          used
          (.getUsed
           (.getHeapMemoryUsage
            (ManagementFactory/getMemoryMXBean)))]
      (when (and before after (<= after before))
        (throw
         (ex-info
          "Explicit full-GC frontier measurement observed no collection."
          {:before before :after after})))
      {:used-bytes used :collection-count after})))

(defn- prototype-map-frames
  [entries]
  (let [rules
        (mapv (fn [ordinal]
                {:ordinal ordinal
                 :rule :arrow-permission
                 :target-node [:account :view]
                 :resource-type :account
                 :intermediate-type :account})
              (range 64))]
    (mapv
     (fn [index]
       {:kind :reverse-via-permission
       :rule (nth rules (rem index (count rules)))
        :resource-id (long index)
        :bound-eid nil})
     (range entries))))

(defn- compact-scan-frames
  [entries]
  (mapv
   (fn [index]
     (ScanFrame.
      3
      (long (rem index 64))
      (long index)
      -1))
   (range entries)))

(defn- compact-sidecar
  [depth physical-width]
  (persistent!
   (reduce
    (fn [sidecar index]
      (let [buffer (long-array physical-width)]
        (dotimes [offset physical-width]
          (aset-long buffer offset
                     (+ (* (long index) physical-width) offset)))
        (assoc! sidecar
                (GoalKey. 3 (long index))
                (SidecarEntry.
                 (long (dec (* (inc index) physical-width)))
                 buffer
                 true))))
    (transient {})
    (range depth))))

(defn- compact-checkpoint
  [admitted-count stack-count]
  {:admitted
   (persistent!
    (reduce
     (fn [result index]
       (conj! result
              (GoalKey. (long (rem index 64)) (long index))))
     (transient #{})
     (range admitted-count)))
   :stack (compact-scan-frames stack-count)})

(defn- advance-checkpoint
  [checkpoint start additions candidate-index]
  {:admitted
   (persistent!
    (reduce
     (fn [admitted index]
       (conj! admitted (GoalKey. (long (rem index 64)) (long index))))
     (transient (:admitted checkpoint))
     (range start (+ start additions))))
   :stack
   (conj (:stack checkpoint)
         (ScanFrame. 3
                     (long (rem candidate-index 64))
                     (long candidate-index)
                     -1))})

(defn- checkpoint-chain
  [admitted-count stack-count candidates additions retain-all?]
  (loop [candidate-index 0
         next-eid admitted-count
         checkpoint (compact-checkpoint admitted-count stack-count)
         retained []]
    (if (= candidate-index candidates)
      (if retain-all? retained checkpoint)
      (let [next
            (advance-checkpoint checkpoint next-eid additions candidate-index)]
        (recur (inc candidate-index)
               (+ next-eid additions)
               next
               (cond-> retained retain-all? (conj next)))))))

(defn- retained-once!
  [label logical-count build]
  (stabilized-heap-snapshot!)
  (let [before (stabilized-heap-snapshot!)
        retained (build)
        after (stabilized-heap-snapshot!)
        keepalive
        [(System/identityHashCode retained)
         (count retained)
         (when (map? retained)
           (+ (count (:admitted retained))
              (count (:stack retained))))]]
    {:shape label
     :logical-count logical-count
     :retained-delta-bytes (- (:used-bytes after) (:used-bytes before))
     :full-gc-count-before (:collection-count before)
     :full-gc-count-after (:collection-count after)
     :keepalive keepalive}))

(defn run!
  ([] (run! {}))
  ([{:keys [trials map-frame-count compact-frame-count buffer-depth
            physical-width checkpoint-admitted checkpoint-stack
            candidate-base-admitted candidate-base-stack
            candidate-count candidate-additions]
     :or {trials 3
          map-frame-count 100000
          compact-frame-count 1000000
          buffer-depth 100000
          physical-width 64
          checkpoint-admitted 1000000
          checkpoint-stack 100000
          candidate-base-admitted 100000
          candidate-base-stack 10000
          candidate-count 64
          candidate-additions 100}}]
   (doseq [value [trials map-frame-count compact-frame-count buffer-depth
                  physical-width checkpoint-admitted checkpoint-stack
                  candidate-base-admitted candidate-base-stack
                  candidate-count candidate-additions]]
     (when-not (pos-int? value)
       (throw (ex-info "Frontier heap options must be positive integers."
                       {:value value}))))
   ;; Warm constructors and class loading before the first baseline.
   (prototype-map-frames 64)
   (compact-scan-frames 64)
   (compact-sidecar 4 physical-width)
   (compact-checkpoint 64 16)
   (checkpoint-chain 64 16 2 2 true)
   (let [measurements
         (mapv
          (fn [trial]
            {:trial trial
             :prototype-map-frames
             (retained-once!
              :prototype-map-frames map-frame-count
              #(prototype-map-frames map-frame-count))
             :compact-scan-frames
             (retained-once!
              :compact-scan-frames compact-frame-count
              #(compact-scan-frames compact-frame-count))
             :compact-sidecar
             (retained-once!
              :compact-sidecar (* buffer-depth physical-width)
              #(compact-sidecar buffer-depth physical-width))
             :compact-checkpoint
             (retained-once!
              :compact-checkpoint (+ checkpoint-admitted checkpoint-stack)
              #(compact-checkpoint checkpoint-admitted checkpoint-stack))
             :latest-checkpoint-only
             (retained-once!
              :latest-checkpoint-only
              (+ candidate-base-admitted
                 (* candidate-count candidate-additions)
                 candidate-base-stack candidate-count)
              #(checkpoint-chain
                candidate-base-admitted candidate-base-stack
                candidate-count candidate-additions false))
             :checkpoint-candidate-backlog
             (retained-once!
              :checkpoint-candidate-backlog candidate-count
              #(checkpoint-chain
                candidate-base-admitted candidate-base-stack
                candidate-count candidate-additions true))})
          (range trials))]
     {:fixture
      {:trials trials
       :map-frame-count map-frame-count
       :compact-frame-count compact-frame-count
       :buffer-depth buffer-depth
       :physical-width physical-width
       :checkpoint-admitted checkpoint-admitted
       :checkpoint-stack checkpoint-stack
       :candidate-base-admitted candidate-base-admitted
       :candidate-base-stack candidate-base-stack
       :candidate-count candidate-count
       :candidate-additions candidate-additions
       :measurement :post-full-gc-live-heap-delta}
      :measurements measurements
      :qualification
      [:exploration-only
       :jvm-specific
       :not-peak-heap
       :not-rss
       :requires-production-source-repeat]})))
