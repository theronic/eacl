(ns eacl.exploration.checkpoint-publication-benchmark
  "Exploration-only microbenchmark for local latest-only checkpoint capture.

  This measures publication mechanics, not traversal, cache-key construction,
  retained heap, remote serialization, or a production implementation."
  (:refer-clojure :exclude [run!])
  (:import (java.lang.management ManagementFactory)
           (java.util.concurrent.atomic AtomicReference)))

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

(deftype Candidate [^long ordinal state ^long weight])

(defn- publish-latest!
  [^AtomicReference latest ^Candidate candidate]
  (loop []
    (let [current ^Candidate (.get latest)]
      (cond
        (and current (>= (.-ordinal current) (.-ordinal candidate)))
        current

        (.compareAndSet latest current candidate)
        candidate

        :else
        (recur)))))

(defn- measure-indexed
  [label iterations setup operation]
  (let [warm (setup)]
    (dotimes [index 50000]
      (operation warm index)))
  (let [state (setup)
        before-bytes (allocated-bytes)
        before-time (System/nanoTime)]
    (dotimes [index iterations]
      (operation state index))
    (let [elapsed (- (System/nanoTime) before-time)
          allocated (when before-bytes
                      (- (allocated-bytes) before-bytes))
          retained
          (cond
            (instance? AtomicReference state) (.get ^AtomicReference state)
            (instance? clojure.lang.Atom state) @state
            :else state)]
      {:shape label
       :iterations iterations
       :nanoseconds-per-publication (/ (double elapsed) iterations)
       :allocated-bytes-per-publication
       (when allocated (/ (double allocated) iterations))
       :retained-ordinal
       (when (instance? Candidate retained)
         (.-ordinal ^Candidate retained))})))

(defn- flat-snapshot
  [entries]
  (persistent!
   (reduce conj! (transient #{}) (range entries))))

(defn run!
  ([] (run! 3))
  ([trials]
   (let [payload {:stack [1 2 3]
                  :admitted (flat-snapshot 100000)
                  :discovered 17
                  :pending [19]}
         direct-iterations 1000000
         freeze-iterations 100000]
     {:fixture
      {:trials trials
       :direct-iterations direct-iterations
       :freeze-iterations freeze-iterations
       :snapshot-admitted 100000
       :measurement :thread-allocation-and-elapsed-time}
      :trials
      (mapv
       (fn [trial]
         {:trial trial
          :candidate-only
          (measure-indexed
           :candidate-only direct-iterations
           (constantly (volatile! nil))
           (fn [sink index]
             (vreset! sink (Candidate. (long index) payload 128))))
          :atomic-set
          (measure-indexed
           :atomic-set direct-iterations
           #(AtomicReference. nil)
           (fn [latest index]
             (.set ^AtomicReference latest
                   (Candidate. (long index) payload 128))))
          :atomic-nonregressing-cas
          (measure-indexed
           :atomic-nonregressing-cas direct-iterations
           #(AtomicReference. nil)
           (fn [latest index]
             (publish-latest!
              latest (Candidate. (long index) payload 128))))
          :atom-nonregressing-swap
          (measure-indexed
           :atom-nonregressing-swap direct-iterations
           #(atom nil)
           (fn [latest index]
             (let [candidate (Candidate. (long index) payload 128)]
               (swap! latest
                      (fn [current]
                        (if (and current
                                 (>= (.-ordinal ^Candidate current)
                                     (.-ordinal candidate)))
                          current
                          candidate))))))
          :freeze-fork-and-cas
          (measure-indexed
           :freeze-fork-and-cas freeze-iterations
           #(AtomicReference. nil)
           (fn [latest index]
             (let [frozen (persistent! (transient (:admitted payload)))
                   state (assoc payload :admitted frozen)]
               (publish-latest!
                latest (Candidate. (long index) state 128)))))})
       (range trials))
      :qualification
      [:exploration-only
       :fresh-jvm-required
       :not-production-source
       :not-cache-key-cost
       :not-retained-heap
       :not-remote-serialization]})))
