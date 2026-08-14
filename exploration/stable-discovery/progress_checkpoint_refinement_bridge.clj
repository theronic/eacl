(ns eacl.exploration.progress-checkpoint-refinement-bridge
  "Exploration-only source-shaped bridge for latest-only progress publication.

  A checkpoint is an immutable semantic reducer state at one canonical
  transition ordinal. Publication is a synchronous CAS of that reference;
  there is deliberately no async candidate queue, serialization, physical
  response buffer, or completed-answer interpretation in this model."
  (:import (java.util.concurrent Callable CountDownLatch Executors TimeUnit)
           (java.util.concurrent.atomic AtomicReference)))

(def ^:private program
  {:successors [[1 2] [3] [3 4] [5] [5] []]
   :results #{3 4 5}})

(def ^:private exact-context
  {:basis "basis-17"
   :schema "schema-4"
   :direction :forward
   :subject [:account 7]
   :permission [:document :read]
   :boundary :start
   :page-size 100
   :work-limit 1000000
   :logical-window 4096
   :order-abi 1
   :engine "v8-exploration"})

(def ^:private context-fields
  [:basis :schema :direction :subject :permission :boundary :page-size
   :work-limit :logical-window :order-abi :engine])

(defn- admit
  [values seen]
  (reduce
   (fn [[seen fresh] value]
     (if (contains? seen value)
       [seen fresh]
       [(conj seen value) (conj fresh value)]))
   [seen []]
   values))

(defn- initial-state
  []
  (let [[admitted roots] (admit [0] #{})]
    {:stack roots :admitted admitted :results []}))

(defn- step
  [{:keys [stack admitted results] :as state}]
  (if (empty? stack)
    state
    (let [node (first stack)
          [admitted fresh] (admit (get-in program [:successors node]) admitted)]
      {:stack (into (vec fresh) (subvec stack 1))
       :admitted admitted
       :results (cond-> results
                  (contains? (:results program) node) (conj node))})))

(defn- state-at
  [ordinal]
  (nth (iterate step (initial-state)) ordinal))

(defn- progress-entry
  [context epoch ordinal]
  {:kind :progress
   :complete? true
   :context context
   :epoch epoch
   :ordinal ordinal
   :state (state-at ordinal)})

(defrecord CheckpointStore [context epoch ^AtomicReference latest])

(defn- checkpoint-store
  [context epoch]
  (->CheckpointStore context epoch (AtomicReference. nil)))

(defn- admissible-entry?
  [store candidate]
  (and (= :progress (:kind candidate))
       (true? (:complete? candidate))
       (= (:context store) (:context candidate))
       (= (:epoch store) (:epoch candidate))
       (nat-int? (:ordinal candidate))))

(defn- publish-progress!
  [store candidate]
  (when (admissible-entry? store candidate)
    (let [latest ^AtomicReference (:latest store)]
      (loop []
        (let [current (.get latest)]
          (cond
            (and current (>= (:ordinal current) (:ordinal candidate)))
            current

            (.compareAndSet latest current candidate)
            candidate

            :else
            (recur)))))))

(defn- restore-progress
  [store]
  (some-> ^AtomicReference (:latest store) .get :state))

(defn- completed-answer-hit
  [entry]
  (when (and (= :answer (:kind entry))
             (true? (:complete? entry)))
    (:answer entry)))

(defn- permutations
  [values]
  (if (empty? values)
    [[]]
    (mapcat
     (fn [value]
       (map #(into [value] %)
            (permutations (vec (remove #{value} values)))))
     values)))

(defn- qualify-permutations!
  []
  (let [entries (mapv #(progress-entry exact-context 3 %) [1 2 3 4])
        orders (vec (permutations entries))]
    (doseq [order orders]
      (let [store (checkpoint-store exact-context 3)]
        (doseq [candidate order]
          (publish-progress! store candidate))
        (let [latest (.get ^AtomicReference (:latest store))]
          (assert (= 4 (:ordinal latest)))
          (assert (= (state-at 4) (:state latest)))
          (assert (= (state-at 4) (restore-progress store)))
          (let [advanced (step (restore-progress store))]
            (assert (not= advanced (restore-progress store)))
            (assert (= (state-at 4) (restore-progress store)))))))
    (count orders)))

(defn- qualify-context-and-control!
  []
  (let [store (checkpoint-store exact-context 7)
        valid (progress-entry exact-context 7 3)
        wrong-contexts
        (mapv
         (fn [field]
           (progress-entry
            (assoc exact-context field [:mutated field]) 7 4))
         context-fields)
        wrong-epoch (progress-entry exact-context 6 4)
        incomplete (assoc valid :complete? false :ordinal 5)
        after-cancel (progress-entry exact-context 7 4)]
    (assert (= valid (publish-progress! store valid)))
    (doseq [wrong-context wrong-contexts]
      (assert (nil? (publish-progress! store wrong-context))))
    (assert (nil? (publish-progress! store wrong-epoch)))
    (assert (nil? (publish-progress! store incomplete)))
    ;; Publication after cancellation is safe only for an exact immutable
    ;; state already computed before control froze the semantic trajectory.
    (assert (= after-cancel (publish-progress! store after-cancel)))
    (assert (= 4 (:ordinal (.get ^AtomicReference (:latest store)))))
    (assert (nil? (completed-answer-hit
                   (.get ^AtomicReference (:latest store)))))
    (count wrong-contexts)))

(defn- qualify-cancellation-prefixes!
  []
  (doseq [cancel-at (range 1 7)]
    (let [store (checkpoint-store exact-context 9)
          candidates (mapv #(progress-entry exact-context 9 %)
                           (range 1 (inc cancel-at)))]
      (doseq [candidate (reverse candidates)]
        (publish-progress! store candidate))
      (let [latest (.get ^AtomicReference (:latest store))]
        (assert (= cancel-at (:ordinal latest)))
        (assert (= (state-at cancel-at) (:state latest)))
        (assert (= (state-at 9)
                   (nth (iterate step (:state latest))
                        (- 9 cancel-at)))))))
  6)

(defn- qualify-real-cas-contention!
  []
  (let [pool (Executors/newFixedThreadPool 4)]
    (try
      (dotimes [round 64]
        (let [store (checkpoint-store exact-context 11)
              start (CountDownLatch. 1)
              entries (mapv #(progress-entry exact-context 11 %)
                            (range 1 9))
              futures
              (mapv
               (fn [entry]
                 (.submit
                  pool
                  ^Callable
                  (reify Callable
                    (call [_]
                      (.await start)
                      (publish-progress! store entry)))))
               (if (even? round) entries (vec (reverse entries))))]
          (.countDown start)
          (doseq [future futures]
            (.get future))
          (let [latest (.get ^AtomicReference (:latest store))]
            (assert (= 8 (:ordinal latest)))
            (assert (= (state-at 8) (:state latest))))))
      64
      (finally
        (.shutdownNow pool)
        (.awaitTermination pool 2 TimeUnit/SECONDS)))))

(defn- killed-controls
  []
  (let [entries (mapv #(progress-entry exact-context 3 %) [1 2 3 4])
        blind-latest (last (reverse entries))
        valid (first entries)
        wrong-context (assoc valid :context (assoc exact-context :basis "wrong"))
        partial (assoc valid :complete? false)]
    {:blind-replacement-killed? (not= 4 (:ordinal blind-latest))
     :context-omission-killed?
     (let [store (checkpoint-store exact-context 3)]
       (nil? (publish-progress! store wrong-context)))
     :partial-publication-killed?
     (let [store (checkpoint-store exact-context 3)]
       (nil? (publish-progress! store partial)))
     :progress-as-answer-killed?
     (nil? (completed-answer-hit valid))}))

(defn run-bridge!
  []
  (let [permutation-count (qualify-permutations!)
        context-mutation-count (qualify-context-and-control!)
        cancellation-prefixes (qualify-cancellation-prefixes!)
        contention-rounds (qualify-real-cas-contention!)
        controls (killed-controls)]
    (assert (every? true? (vals controls)))
    {:permutation-count permutation-count
     :context-mutation-count context-mutation-count
     :cancellation-prefixes cancellation-prefixes
     :contention-rounds contention-rounds
     :controls controls
     :control-count (count controls)}))
