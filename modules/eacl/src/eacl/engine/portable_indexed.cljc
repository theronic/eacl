(ns eacl.engine.portable-indexed
  "Portable indexed fixed-point traversal used by the CLJS production kernel.

  The state is deliberately persistent Clojure data.  It implements the same
  scan-command protocol as the generated kernel while keeping all browser hot
  path arithmetic on safe native integers."
  (:require [eacl.verified-kernel :as verified]))

(def ^:private empty-queue
  #?(:clj clojure.lang.PersistentQueue/EMPTY
     :cljs (.-EMPTY cljs.core/PersistentQueue)))

(def ^:private counter-keys
  [:backend-commands
   :adapter-fetched-values
   :engine-consumed-values
   :cumulative-enqueues
   :current-queue-depth
   :maximum-queue-depth
   :unique-grants
   :emitted-results
   :rule-applications
   :consumer-grant-joins
   :render-advances])

(defn- empty-counters []
  (zipmap counter-keys (repeat 0)))

(defn- add-counter
  ([state key] (add-counter state key 1))
  ([state key amount]
   (assoc state :counters (update (:counters state) key + amount))))

(defn- enqueue
  [state work]
  (let [queue (conj (:queue state) work)
        depth (count queue)
        counters
        (-> (:counters state)
            (update :cumulative-enqueues inc)
            (assoc :current-queue-depth depth)
            (update :maximum-queue-depth max depth))]
    (assoc state :queue queue :counters counters)))

(defn- enqueue-all
  [state works]
  (reduce enqueue state works))

(defn- dequeue
  [state]
  (let [work (peek (:queue state))
        queue (pop (:queue state))
        counters
        (assoc (:counters state) :current-queue-depth (count queue))]
    [work (assoc state :queue queue :counters counters)]))

(defn- subject-resources
  [subject-type subject-eid relation-eid resource-type]
  {:kind :subject->resources
   :subject-type subject-type
   :subject-eid subject-eid
   :relation-eid relation-eid
   :resource-type resource-type
   :bound-eid nil})

(defn- resource-subjects
  [resource-type resource-eid relation-eid subject-type]
  {:kind :resource->subjects
   :resource-type resource-type
   :resource-eid resource-eid
   :relation-eid relation-eid
   :subject-type subject-type
   :bound-eid nil})

(defn- stream
  [projection continuation]
  {:kind :stream
   :projection projection
   :values []
   :more? true
   :continuation continuation})

(defn- rule-head
  [rule]
  (:head rule))

(defn- rules-by-head
  [rules]
  (->> rules
       (group-by rule-head)
       (into {}
             (map (fn [[head head-rules]]
                    [head (vec head-rules)])))))

(defn- forward-consumers
  [rules]
  (->> rules
       (keep (fn [rule]
               (when (contains? #{:self-permission :arrow-permission}
                                (:kind rule))
                 [(:target-node rule) rule])))
       (group-by first)
       (into {}
             (map (fn [[node pairs]]
                    [node (mapv second pairs)])))))

(defrecord PortablePlan
           [rules seed-rules-by-subject-type rules-by-head forward-consumers])

(defn compile-plan
  [{:keys [indexed-rules seed-rules-by-subject-type]}]
  (->PortablePlan
   indexed-rules
   seed-rules-by-subject-type
   (rules-by-head indexed-rules)
   (forward-consumers indexed-rules)))

(defn- limit-kind
  [state limits]
  (let [counters (:counters state)]
    (cond
      (> (:unique-grants counters) (:max-derived-grants limits))
      :derived-grants

      (> (:engine-consumed-values counters) (:max-advanced-datoms limits))
      :advanced-datoms

      (> (:current-queue-depth counters) (:max-queued-work limits))
      :queued-work)))

(defn- retained-units
  [state]
  (+ (count (:queue state))
     (reduce
      (fn [n work]
        (+ n (if (= :stream (:kind work))
               (count (:values work))
               0)))
      0
      (:queue state))
     (count (:seen-grants state))
     (count (:emitted state))
     (if (= :reverse (:direction state))
       (count (:rules-by-head state))
       0)
     (count (:seen-goals state))
     (count (:grants-by-goal state))
     (count (:consumers state))
     (count (:seen-consumers state))
     (count (get-in state [:render-state :items]))))

(defn- initial-render-state
  [render]
  (case (:kind render)
    :page
    {:items []
     :start-ordinal 0
     :has-previous? false}

    (:count :all-count)
    {:count 0}

    :boolean
    {:allowed? false}))

(defn- common-state
  [direction plan input]
  {:direction direction
   :queue empty-queue
   :pending-scan nil
   :next-request-id 0
   :request-scope (:request-scope input)
   :chunk-size (:chunk-size input)
   :root-node (:root-node input)
   :result-type (:result-type input)
   :render (:render input)
   :render-state (initial-render-state (:render input))
   :complete? false
   :continuation-state nil
   :counters (empty-counters)
   :seen-grants #{}
   :emitted #{}
   :rules-by-head (:rules-by-head plan)
   :consumers {}
   :seen-consumers #{}
   :seen-goals #{}
   :grants-by-goal {}})

(defn- seed-forward
  [state plan subject-type subject-eid]
  (let [seeds (get (:seed-rules-by-subject-type plan) subject-type [])
        state (add-counter state :rule-applications (count seeds))]
    (reduce
     (fn [state rule]
       (case (:kind rule)
         :relation
         (enqueue
          state
          (stream
           (subject-resources
            subject-type subject-eid (:relation-eid rule)
            (get-in rule [:head :resource-type]))
           {:op :forward-grant :node (:head rule)}))

         :arrow-relation
         (enqueue
          state
          (stream
           (subject-resources
            subject-type subject-eid (:target-relation-eid rule)
            (:intermediate-type rule))
           {:op :forward-arrow-relation
            :node (:head rule)
            :intermediate-type (:intermediate-type rule)
            :via-relation-eid (:via-relation-eid rule)
            :resource-type (get-in rule [:head :resource-type])}))

         state))
     state
     seeds)))

(defn- seed-reverse
  [state subject-type root-resource-eid]
  (-> state
      (assoc :subject-type subject-type
             :root-resource-eid root-resource-eid)
      (enqueue {:kind :goal
                :node (:root-node state)
                :resource-eid root-resource-eid})))

(defn initialize
  [direction {:keys [compiled-plan subject-type subject-eid
                     root-resource-eid] :as input}]
  (let [state (common-state direction compiled-plan input)
        state (case direction
                :forward
                (-> state
                    (assoc :consumers (:forward-consumers compiled-plan))
                    (seed-forward compiled-plan subject-type subject-eid))
                :reverse
                (seed-reverse state subject-type root-resource-eid))]
    (if-let [kind (limit-kind state (:limits input))]
      {:status :limit-exceeded :limit-kind kind}
      {:status :initialized :state state})))

(defn- forward-consumer-work
  [grant rule]
  (case (:kind rule)
    :self-permission
    [{:kind :grant
      :node (:head rule)
      :resource-eid (:resource-eid grant)}]

    :arrow-permission
    [(stream
      (subject-resources
       (:intermediate-type rule)
       (:resource-eid grant)
       (:via-relation-eid rule)
       (get-in rule [:head :resource-type]))
      {:op :forward-grant :node (:head rule)})]

    []))

(defn- reverse-consumer-work
  [consumer grant]
  (case (:op consumer)
    :reverse-propagate-grant
    [{:kind :grant
      :node (:node consumer)
      :resource-eid (:resource-eid consumer)
      :subject-type (:subject-type grant)
      :subject-eid (:subject-eid grant)}]
    []))

(defn- continuation-work
  [continuation eid]
  (case (:op continuation)
    :forward-grant
    [{:kind :grant
      :node (:node continuation)
      :resource-eid eid}]

    :forward-arrow-relation
    [(stream
      (subject-resources
       (:intermediate-type continuation)
       eid
       (:via-relation-eid continuation)
       (:resource-type continuation))
      {:op :forward-grant :node (:node continuation)})]

    :reverse-grant
    [{:kind :grant
      :node (:node continuation)
      :resource-eid (:resource-eid continuation)
      :subject-type (:subject-type continuation)
      :subject-eid eid}]

    :reverse-arrow-relation
    [(stream
      (resource-subjects
       (:intermediate-type continuation)
       eid
       (:target-relation-eid continuation)
       (:subject-type continuation))
      {:op :reverse-grant
       :node (:node continuation)
       :resource-eid (:resource-eid continuation)
       :subject-type (:subject-type continuation)})]

    :reverse-arrow-permission
    [{:kind :register-consumer
      :consumer-key [(:target-node continuation) eid]
      :consumer {:op :reverse-propagate-grant
                 :node (:node continuation)
                 :resource-eid (:resource-eid continuation)}}
     {:kind :goal
      :node (:target-node continuation)
      :resource-eid eid}]

    []))

(defn- record-emission
  [state eid]
  (let [ordinal (dec (get-in state [:counters :emitted-results]))
        render (:render state)
        kind (:kind render)
        render-state (:render-state state)]
    (case kind
      :boolean
      (if (= eid (:target-eid render))
        (-> state
            (assoc-in [:render-state :allowed?] true)
            (assoc :complete? true))
        state)

      :count
      (let [n (inc (:count render-state))]
        (if (> n (:limit render))
          (-> state
              (assoc-in [:render-state :count] (:limit render))
              (assoc-in [:render-state :truncated?] true)
              (assoc :complete? true))
          (assoc-in state [:render-state :count] n)))

      :all-count
      (update-in state [:render-state :count] inc)

      :page
      (cond
        (< (count (:items render-state)) (:size render))
        (let [items (conj (:items render-state) eid)
              state (assoc state :render-state (assoc render-state :items items))]
          (if (= (count items) (:size render))
            (assoc state :continuation-state
                   (-> state
                       (assoc :continuation-state nil)
                       (assoc :complete? false)))
            state))

        :else
        (-> state
            (assoc-in [:render-state :has-next?] true)
            (assoc :complete? true))))))

(defn- emit
  [state eid]
  (let [counters
        (-> (:counters state)
            (update :emitted-results inc)
            (update :render-advances inc))]
    (record-emission
     (assoc state
            :emitted (conj (:emitted state) eid)
            :counters counters)
     eid)))

(defn- process-forward-grant
  [state {:keys [node resource-eid] :as grant}]
  (let [grant-key [node resource-eid]]
    (if (contains? (:seen-grants state) grant-key)
      state
      (let [rules (get (:consumers state) node [])
            rule-count (count rules)
            counters
            (-> (:counters state)
                (update :unique-grants inc)
                (update :rule-applications + rule-count)
                (update :consumer-grant-joins + rule-count))
            state
            (-> state
                (assoc
                 :seen-grants (conj (:seen-grants state) grant-key)
                 :counters counters)
                (enqueue-all
                 (mapcat #(forward-consumer-work grant %) rules)))]
        (if (and (= node (:root-node state))
                 (not (contains? (:emitted state) resource-eid)))
          (emit state resource-eid)
          state)))))

(defn- add-reverse-consumer
  [state key consumer]
  (let [registration [key consumer]]
    (if (contains? (:seen-consumers state) registration)
      state
      (let [grants (get (:grants-by-goal state) key [])]
        (-> state
            (update :seen-consumers conj registration)
            (update-in [:consumers key] (fnil conj []) consumer)
            (add-counter :consumer-grant-joins (count grants))
            (enqueue-all (mapcat #(reverse-consumer-work consumer %) grants)))))))

(defn- expand-reverse-rule
  [state resource-eid rule]
  (case (:kind rule)
    :relation
    (if (= (:subject-type state) (:subject-type rule))
      (enqueue
       state
       (stream
        (resource-subjects
         (get-in rule [:head :resource-type]) resource-eid
         (:relation-eid rule) (:subject-type state))
        {:op :reverse-grant
         :node (:head rule)
         :resource-eid resource-eid
         :subject-type (:subject-type state)}))
      state)

    :self-permission
    (enqueue-all
     state
     [{:kind :register-consumer
       :consumer-key [(:target-node rule) resource-eid]
       :consumer {:op :reverse-propagate-grant
                  :node (:head rule)
                  :resource-eid resource-eid}}
      {:kind :goal
       :node (:target-node rule)
       :resource-eid resource-eid}])

    :arrow-relation
    (if (= (:subject-type state) (:target-subject-type rule))
      (enqueue
       state
       (stream
        (resource-subjects
         (get-in rule [:head :resource-type]) resource-eid
         (:via-relation-eid rule) (:intermediate-type rule))
        {:op :reverse-arrow-relation
         :node (:head rule)
         :resource-eid resource-eid
         :subject-type (:subject-type state)
         :intermediate-type (:intermediate-type rule)
         :target-relation-eid (:target-relation-eid rule)}))
      state)

    :arrow-permission
    (enqueue
     state
     (stream
      (resource-subjects
       (get-in rule [:head :resource-type]) resource-eid
       (:via-relation-eid rule) (:intermediate-type rule))
      {:op :reverse-arrow-permission
       :node (:head rule)
       :resource-eid resource-eid
       :target-node (:target-node rule)}))))

(defn- process-reverse-goal
  [state {:keys [node resource-eid]}]
  (let [key [node resource-eid]]
    (if (contains? (:seen-goals state) key)
      state
      (let [rules (get (:rules-by-head state) node [])]
        (reduce
         #(expand-reverse-rule %1 resource-eid %2)
         (-> state
             (update :seen-goals conj key)
             (add-counter :rule-applications (count rules)))
         rules)))))

(defn- process-reverse-grant
  [state {:keys [node resource-eid subject-type subject-eid] :as grant}]
  (let [grant-key [node resource-eid subject-type subject-eid]
        goal-key [node resource-eid]]
    (if (contains? (:seen-grants state) grant-key)
      state
      (let [consumers (get (:consumers state) goal-key [])
            counters
            (-> (:counters state)
                (update :unique-grants inc)
                (update :consumer-grant-joins + (count consumers)))
            state
            (-> state
                (assoc
                 :seen-grants (conj (:seen-grants state) grant-key)
                 :grants-by-goal
                 (update (:grants-by-goal state) goal-key (fnil conj []) grant)
                 :counters counters)
                (enqueue-all (mapcat #(reverse-consumer-work % grant)
                                     consumers)))]
        (if (and (= node (:root-node state))
                 (= resource-eid (:root-resource-eid state))
                 (= subject-type (:result-type state))
                 (not (contains? (:emitted state) subject-eid)))
          (emit state subject-eid)
          state)))))

(defn- request-scan
  [state work]
  (let [command {:request-scope (:request-scope state)
                 :request-id (:next-request-id state)
                 :projection (:projection work)
                 :chunk-size (:chunk-size state)}
        state (-> state
                  (assoc :pending-scan {:work work :command command})
                  (update :next-request-id inc)
                  (add-counter :backend-commands))]
    {:status :need-scan :state state :command command}))

(defn- consume-stream-value
  [state work]
  (let [eid (first (:values work))
        remaining (subvec (:values work) 1)
        state (-> state
                  (add-counter :engine-consumed-values)
                  (enqueue-all (continuation-work (:continuation work) eid)))]
    (if (or (seq remaining) (:more? work))
      (enqueue state (assoc work :values remaining))
      state)))

(defn- finish-exhausted
  [state]
  (let [render (:render state)]
    (case (:kind render)
      :page
      (-> state
          (assoc-in [:render-state :has-next?] false)
          (assoc :complete? true))

      :count
      (-> state
          (assoc-in [:render-state :truncated?] false)
          (assoc :complete? true))

      :all-count
      (-> state
          (assoc-in [:render-state :truncated?] false)
          (assoc :complete? true))

      :boolean
      (assoc state :complete? true))))

(defn- drive-step
  [state]
  (if (empty? (:queue state))
    (finish-exhausted state)
    (let [[work state] (dequeue state)]
      (case (:kind work)
        :stream
        (if (seq (:values work))
          (consume-stream-value state work)
          (if (:more? work)
            (request-scan state work)
            state))

        :grant
        (case (:direction state)
          :forward (process-forward-grant state work)
          :reverse (process-reverse-grant state work))

        :goal
        (process-reverse-goal state work)

        :register-consumer
        (add-reverse-consumer state (:consumer-key work) (:consumer work))))))

(def ^:private scan-batch-size 64)

(defn- pending-outcome
  [state pending]
  (let [commands (mapv :command pending)
        state (assoc state :pending-scans pending)]
    (if (= 1 (count commands))
      {:status :need-scan
       :state state
       :command (first commands)}
      {:status :need-scans
       :state state
       :commands commands})))

(defn drive
  [_direction state limits fuel]
  (loop [state (dissoc state :pending-scans)
         fuel fuel
         pending []
         original state]
    (cond
      (and (seq pending)
           (or (:complete? state) (empty? (:queue state))))
      (pending-outcome state pending)

      (:render-error state)
      {:status :render-rejected
       :state state
       :error (:render-error state)}

      (:complete? state)
      {:status :complete :state state}

      (limit-kind state limits)
      {:status :limit-exceeded
       :state state
       :limit-kind (limit-kind state limits)}

      (zero? fuel)
      ;; A fuel boundary cannot expose a partially collected scan wave.
      {:status :yielded :state (if (seq pending) original state)}

      :else
      (let [outcome (drive-step state)]
        (if (and (map? outcome) (= :need-scan (:status outcome)))
          (let [next-pending
                (conj pending (get-in outcome [:state :pending-scan]))
                next-state (assoc (:state outcome) :pending-scan nil)]
            (if (= scan-batch-size (count next-pending))
              (pending-outcome next-state next-pending)
              (recur next-state (dec fuel) next-pending original)))
          (do
            (recur outcome (dec fuel) pending original)))))))

(defn- scan-rejection
  [command response]
  (let [values (:values response)
        bound (get-in command [:projection :bound-eid])]
    (cond
      (not= (:request-scope command) (:request-scope response))
      :mismatched-request-scope
      (not= (:request-id command) (:request-id response))
      :mismatched-request
      (> (count values) (:chunk-size command)) :oversized-chunk
      (if (:terminal? response)
        (not= (:fetched-values response) (count values))
        (not= (:fetched-values response) (inc (count values))))
      :invalid-fetched-count
      (and (not (:terminal? response)) (empty? values))
      :non-progressing-response
      (not-every? #(and (integer? %) (not (neg? %))) values)
      :invalid-eid
      (not (every? true? (map < values (rest values)))) :out-of-order
      (and bound (not-every? #(< bound %) values)) :bound-violation)))

(defn- resume-one
  [state pending response limits]
  (if-let [{:keys [work command]} pending]
    (if-let [reason (scan-rejection command response)]
      {:status :scan-rejected :reason reason}
      (let [values (:values response)
            projection (cond-> (:projection work)
                         (seq values) (assoc :bound-eid (peek values)))
            work (assoc work
                        :values values
                        :more? (not (:terminal? response))
                        :projection projection)
            state (-> state
                      (add-counter :adapter-fetched-values
                                   (:fetched-values response))
                      (enqueue work))]
        (if-let [kind (limit-kind state limits)]
          {:status :limit-exceeded :state state :limit-kind kind}
          {:status :resumed :state state})))
    {:status :scan-rejected :reason :invalid-command}))

(defn resume
  [_direction state response limits]
  (let [pending
        (or (:pending-scans state)
            (some-> (:pending-scan state) vector))
        responses (if (vector? response) response [response])
        state (-> state
                  (assoc :pending-scan nil)
                  (dissoc :pending-scans))]
    (if (not= (count pending) (count responses))
      {:status :scan-rejected :reason :invalid-command}
      (loop [state state
             pending pending
             responses responses]
        (if (empty? pending)
          {:status :resumed :state state}
          (let [outcome
                (resume-one
                 state (first pending) (first responses) limits)]
            (if (= :resumed (:status outcome))
              (recur (:state outcome) (rest pending) (rest responses))
              outcome)))))))

(defn continue-page
  [_direction state {:keys [size bound]}]
  (let [render (:render state)
        result (:render-state state)
        items (:items result)
        last-ordinal (+ (:start-ordinal result) (dec (count items)))]
    (cond
      (not= :page (:kind render))
      {:status :rejected :reason :not-forward-page}
      (not (:complete? state))
      {:status :rejected :reason :not-complete}
      (not (get-in state [:render-state :has-next?]))
      {:status :rejected :reason :no-lookahead}
      (or (empty? items)
          (not= last-ordinal (:ordinal bound))
          (not= (peek items) (:eid bound)))
      {:status :rejected :reason :boundary-mismatch}
      :else
      {:status :continued
       :state
       (let [render-state
             (assoc
              (initial-render-state {:kind :page :size size})
              :start-ordinal (inc (:ordinal bound))
              :has-previous? true)]
         (-> (:continuation-state state)
             (assoc :render {:kind :page :size size}
                    :render-state render-state
                    :complete? false
                    :render-error nil
                    :continuation-state nil)))})))

(defn read-result
  [_direction state]
  (let [render (:render state)
        result (:render-state state)
        common {:counters (:counters state)
                :retained-logical-units (retained-units state)}]
    (case (:kind render)
      :page
      (merge common
             {:status :page
              :items (:items result)
              :start-ordinal (:start-ordinal result)
              :has-next? (boolean (:has-next? result))
              :has-previous? (boolean (:has-previous? result))})

      (:count :all-count)
      (merge common
             {:status :count
              :count (:count result)
              :truncated? (boolean (:truncated? result))})

      :boolean
      (merge common
             {:status :boolean
              :allowed? (:allowed? result)}))))

(defrecord PortableIndexedKernel [decision-kernel]
  verified/DecisionKernel
  (-decide [_ operation input]
    (verified/-decide decision-kernel operation input))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (compile-plan input))
  (-initialize-indexed [_ direction input]
    (initialize direction input))
  (-drive-indexed [_ direction state limits fuel]
    (drive direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (resume direction state response limits))
  (-continue-indexed-page [_ direction state input]
    (continue-page direction state input))
  (-read-indexed-result [_ direction state]
    (read-result direction state)))

(defn portable-indexed-kernel
  "Wraps a decision kernel with the native CLJC indexed traversal."
  [decision-kernel]
  (->PortableIndexedKernel decision-kernel))
