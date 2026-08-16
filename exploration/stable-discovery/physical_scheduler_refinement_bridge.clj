(ns eacl.exploration.physical-scheduler-refinement-bridge
  "Source-shaped refinement checks for the nonsemantic physical-read shell.

  This is exploration code.  It permutes completion order without threads so
  failures are deterministic and cheap to shrink/replay."
  (:import [java.util Random]))

(defn- issue-readable
  [state]
  (loop [state state]
    (if (or (not= :active (:control state))
            (= (:next-issue state) (count (:items state)))
            (= (:window-capacity state) (count (:window state))))
      state
      (let [item (nth (:items state) (:next-issue state))
            logical-id (:logical-id item)
            descriptor (:descriptor item)
            cached (get (:chunks state) descriptor)
            existing-flight? (contains? (:flights state) descriptor)
            state
            (-> state
                (update :next-issue inc)
                (update :window conj logical-id)
                (update :maximum-window max
                        (inc (count (:window state)))))
            state
            (cond
              cached
              (assoc-in state [:ready logical-id] cached)

              existing-flight?
              (update-in state [:flights descriptor] conj logical-id)

              :else
              (-> state
                  (assoc-in [:flights descriptor] #{logical-id})
                  (update :physical-calls inc)))]
        (recur state)))))

(defn- integrate-canonical
  [state]
  (loop [state state]
    (if (or (not= :active (:control state))
            (= (:next-integrate state) (count (:items state))))
      state
      (let [item (nth (:items state) (:next-integrate state))
            logical-id (:logical-id item)]
        (if-let [payload (get (:ready state) logical-id)]
          (recur
           (-> state
               (update :next-integrate inc)
               (update :integrated conj [logical-id payload])
               (update :ready dissoc logical-id)
               (update :window disj logical-id)
               issue-readable))
          state)))))

(defn- complete-physical
  [state descriptor]
  (let [waiters (get (:flights state) descriptor)
        payload (get (:payloads state) descriptor)]
    (when-not (and (seq waiters) (some? payload))
      (throw
       (ex-info "Completion did not name one live exact descriptor."
                {:descriptor descriptor
                 :flights (:flights state)})))
    (let [state
          (-> state
              (update :flights dissoc descriptor)
              ;; A complete validated response may gain independent reusable
              ;; ownership even when the request has already been canceled.
              (assoc-in [:chunks descriptor] payload))
          state
          (if (= :active (:control state))
            (reduce (fn [state logical-id]
                      (if (contains? (:window state) logical-id)
                        (assoc-in state [:ready logical-id] payload)
                        state))
                    state
                    waiters)
            state)]
      (integrate-canonical state))))

(defn- cancel
  [state]
  (-> state
      (assoc :control :canceled)
      (assoc :window #{})
      (assoc :ready {})))

(defn- priority-completion
  [state priority]
  (first (sort-by priority (keys (:flights state)))))

(defn- initial-state
  [items window-capacity]
  (let [payloads
        (reduce (fn [payloads {:keys [descriptor payload]}]
                  (if-let [existing (get payloads descriptor)]
                    (do
                      (when-not (= existing payload)
                        (throw
                         (ex-info
                          "One exact descriptor produced two payloads."
                          {:descriptor descriptor
                           :left existing
                           :right payload})))
                      payloads)
                    (assoc payloads descriptor payload)))
                {}
                items)]
    (issue-readable
     {:items items
      :payloads payloads
      :window-capacity window-capacity
      :next-issue 0
      :next-integrate 0
      :window #{}
      :flights {}
      :ready {}
      :chunks {}
      :integrated []
      :physical-calls 0
      :maximum-window 0
      :control :active})))

(defn- run-schedule
  [items window-capacity priority cancel-after-completions]
  (loop [state (initial-state items window-capacity)
         completions 0]
    (let [state
          (if (and (= completions cancel-after-completions)
                   (= :active (:control state)))
            (cancel state)
            state)]
      (cond
        (seq (:flights state))
        (let [descriptor (priority-completion state priority)]
          (recur (complete-physical state descriptor) (inc completions)))

        (= :canceled (:control state))
        state

        (= (:next-integrate state) (count items))
        state

        :else
        (throw
         (ex-info "Active scheduler deadlocked without a physical flight."
                  {:state state}))))))

(defn- shuffled-priority
  [^Random rng descriptors]
  (->> descriptors
       (map (fn [descriptor] [(.nextLong rng) descriptor]))
       (sort-by first)
       (map-indexed (fn [index [_ descriptor]] [descriptor index]))
       (into {})))

(defn- generated-case
  [^Random rng case-index]
  (let [item-count (inc (.nextInt rng 40))
        descriptor-count (inc (.nextInt rng item-count))
        descriptors
        (mapv (fn [index]
                {:basis 19
                 :operation :scan
                 :position index
                 :limit 64})
              (range descriptor-count))
        items
        (mapv (fn [logical-id]
                (let [descriptor
                      (nth descriptors (.nextInt rng descriptor-count))]
                  {:logical-id [case-index logical-id]
                   :descriptor descriptor
                   :payload [:chunk (:position descriptor)]}))
              (range item-count))
        width (inc (.nextInt rng (min 8 item-count)))
        priority (shuffled-priority rng descriptors)
        used-descriptor-count (count (set (map :descriptor items)))
        cancel-after (.nextInt rng (inc used-descriptor-count))]
    {:items items
     :width width
     :priority priority
     :cancel-after cancel-after}))

(defn- prefix?
  [prefix whole]
  (and (<= (count prefix) (count whole))
       (= prefix (subvec whole 0 (count prefix)))))

(defn- check-case!
  [{:keys [items width priority cancel-after] :as case}]
  (let [expected
        (mapv (fn [{:keys [logical-id payload]}]
                [logical-id payload])
              items)
        complete
        (run-schedule items width priority Long/MAX_VALUE)
        canceled
        (run-schedule items width priority cancel-after)]
    (when-not
     (and (= expected (:integrated complete))
          (= (count (set (map first expected))) (count expected))
          (<= (:maximum-window complete) width)
          (<= (:physical-calls complete)
              (count (set (map :descriptor items))))
          (prefix? (:integrated canceled) expected)
          (= :canceled (:control canceled))
          (empty? (:flights canceled))
          (empty? (:window canceled))
          (empty? (:ready canceled)))
      (throw
       (ex-info "Physical scheduler refinement failed."
                {:case case
                 :expected expected
                 :complete complete
                 :canceled canceled})))
    {:complete-count (count (:integrated complete))
     :canceled-count (count (:integrated canceled))
     :physical-calls (:physical-calls complete)
     :maximum-window (:maximum-window complete)}))

(defn- mutation-controls!
  []
  (let [items [{:logical-id :a :descriptor :slow :payload :slow-value}
               {:logical-id :b :descriptor :fast :payload :fast-value}]
        completion-order [:fast :slow]
        integrate-any-mutant
        (mapv (fn [descriptor]
                [(:logical-id
                  (first (filter #(= descriptor (:descriptor %)) items)))
                 (get {:slow :slow-value :fast :fast-value} descriptor)])
              completion-order)
        canonical [[:a :slow-value] [:b :fast-value]]
        page-left {:basis 1 :position 10 :limit 64}
        page-right {:basis 1 :position 11 :limit 64}
        omit-position #(dissoc % :position)
        physical-end-resume-mutant [0 4 5 6 7]
        logical-resume [0 1 2 3 4 5 6 7]
        canceled (cancel (initial-state items 2))
        post-cancel-integrate-mutant
        (conj (:integrated canceled) [:a :slow-value])]
    (when-not
     (and (not= canonical integrate-any-mutant)
          (not= page-left page-right)
          (= (omit-position page-left) (omit-position page-right))
          (not= logical-resume physical-end-resume-mutant)
          (empty? (:integrated canceled))
          (not= (:integrated canceled) post-cancel-integrate-mutant))
      (throw
       (ex-info "Physical scheduler mutation control survived."
                {:integrate-any integrate-any-mutant
                 :page-left page-left
                 :page-right page-right
                 :physical-end-resume physical-end-resume-mutant
                 :post-cancel post-cancel-integrate-mutant})))
    {:integrate-any-killed? true
     :omit-position-killed? true
     :physical-end-resume-killed? true
     :post-cancel-integration-killed? true}))

(defn run-bridge!
  ([] (run-bridge! 58013 512))
  ([seed cases]
   (let [rng (Random. (long seed))
         reports
         (mapv (fn [case-index]
                 (check-case! (generated-case rng case-index)))
               (range cases))
         controls (mutation-controls!)]
     {:seed seed
      :case-count cases
      :completion-permutations cases
      :maximum-window (reduce max 0 (map :maximum-window reports))
      :maximum-physical-calls
      (reduce max 0 (map :physical-calls reports))
      :canceled-prefixes (count (filter (comp pos? :canceled-count) reports))
      :mutation-controls controls
      :mutation-control-count (count controls)})))
