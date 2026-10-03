(ns eacl.formal.cache-strategy-adversarial-test
  "Finite falsification check for the cache publication strategy.

  This is not a proof of the production implementation. It exhausts bounded
  publication and computation traces over a small model, and retains the two
  mutants (stable keys, joined computations) that produce a stale observation."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]))

(defn- command-traces
  [commands length]
  (if (zero? length)
    [[]]
    (for [prefix (command-traces commands (dec length))
          command commands]
      (conj prefix command))))

(defn- authorization-at-version
  [version]
  (odd? version))

(defn- race-key
  [versioned? version]
  (if versioned?
    [:query version]
    :query))

(defn- cache-race-step
  [versioned? state command]
  (case command
    :start
    (update
     state
     :jobs
     conj
     {:version (:head state)
      :value (authorization-at-version (:head state))})

    :write
    (update state :head inc)

    :publish
    (if-let [job (first (:jobs state))]
      (-> state
          (assoc-in
           [:cache (race-key versioned? (:version job))]
           (:value job))
          (update :jobs #(vec (rest %))))
      state)

    :invalidate
    (update
     state
     :cache
     dissoc
     (race-key versioned? (:head state)))

    :lookup
    (let [key (race-key versioned? (:head state))]
      (if (contains? (:cache state) key)
        (update
         state
         :observations
         conj
         {:actual (get (:cache state) key)
          :expected (authorization-at-version (:head state))})
        state))))

(defn- independent-computation-step
  [join-mutant? state command]
  (case command
    :start
    (let [version (:head state)
          job {:computed-version version
               :waiters [version]}]
      (if (and join-mutant? (seq (:jobs state)))
        (update-in state [:jobs 0 :waiters] conj version)
        (update state :jobs conj job)))

    :write
    (update state :head inc)

    :complete
    (if-let [job (first (:jobs state))]
      (-> state
          (update
           :observations
           into
           (map
            (fn [waiter-version]
              {:actual
               (authorization-at-version
                (:computed-version job))
               :expected
               (authorization-at-version waiter-version)})
            (:waiters job)))
          (update :jobs #(vec (rest %))))
      state)))

(defn- stale-observation?
  [state]
  (some
   (fn [{:keys [actual expected]}]
     (not= actual expected))
   (:observations state)))

(deftest publication-and-independent-computation-traces-exhaustive-test
  (let [cache-traces
        (command-traces
         [:start :write :publish :invalidate :lookup]
         6)
        initial-cache
        {:head 0 :jobs [] :cache {} :observations []}
        correct-cache-states
        (map
         #(reduce
           (partial cache-race-step true)
           initial-cache
           %)
         cache-traces)
        mutant-cache-states
        (map
         #(reduce
           (partial cache-race-step false)
           initial-cache
           %)
         cache-traces)
        computation-traces
        (command-traces [:start :write :complete] 5)
        initial-computation
        {:head 0 :jobs [] :observations []}
        independent-states
        (map
         #(reduce
           (partial independent-computation-step false)
           initial-computation
           %)
         computation-traces)
        join-mutant-states
        (map
         #(reduce
           (partial independent-computation-step true)
           initial-computation
           %)
         computation-traces)]
    (is (not-any? stale-observation? correct-cache-states)
        "full versioned keys survive every bounded publication trace")
    (is (some stale-observation? mutant-cache-states)
        "stable-key publication retains a bounded stale trace")
    (is (not-any? stale-observation? independent-states)
        "each request-owned miss computes against its own selected version")
    (is (some stale-observation? join-mutant-states)
        "reintroducing a computation join couples a newer request to stale work")))
