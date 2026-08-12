(ns eacl.execution
  "Normalized demand, deadline, and cache-attempt contracts.

  Public orchestration creates exactly one contract before consistency or cache
  work. Runtime layers may observe the contract and check its absolute monotonic
  deadline, but never replace it with a fresh relative timeout."
  (:refer-clojure :exclude [next]))

(def default-execution-timeout-ms 30000)
(def maximum-execution-timeout-ms 3600000)

(def default-cache-attempt
  {:evaluation-reserve-ms 10
   :maximum-atomic-attempts 4})

(def ^:private positive-cache-attempt-keys
  (set (keys default-cache-attempt)))

(def ^:dynamic *monotonic-nanos*
  #?(:clj (fn [] (System/nanoTime))
     :cljs (fn []
             (js/Math.floor
              (* 1000000
                 (if (exists? js/performance)
                   (.now js/performance)
                   (.now js/Date)))))))

(def ^:dynamic *contract* nil)

(defn now-nanos []
  (*monotonic-nanos*))

(defn- invalid-request!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-request
            :eacl/error :eacl.execution/invalid-contract}
           data))))

(defn normalize-timeout-ms
  [value]
  (when-not (and (integer? value)
                 (pos? value)
                 (<= value maximum-execution-timeout-ms))
    (invalid-request!
     ":timeout-ms must be a positive integer within the supported range."
     {:key :timeout-ms
      :value value
      :maximum-timeout-ms maximum-execution-timeout-ms}))
  value)

(defn normalize-evaluation
  [value]
  (let [value (or value :demand)]
    (when-not (contains? #{:demand :complete-denotation} value)
      (invalid-request!
       ":evaluation must be :demand or :complete-denotation."
       {:key :evaluation
        :value value
        :supported #{:demand :complete-denotation}}))
    value))

(defn normalize-cache-attempt
  [overrides]
  (let [overrides (or overrides {})]
    (when-not (map? overrides)
      (throw
       (ex-info
        ":cache-attempt must be a map."
        {:type :eacl/invalid-config
         :key :cache-attempt
         :value overrides})))
    (when-let [unknown (seq (remove positive-cache-attempt-keys
                                    (keys overrides)))]
      (throw
       (ex-info
        "Unknown cache-attempt option."
        {:type :eacl/invalid-config
         :key :cache-attempt
         :unknown-keys (vec unknown)
         :known-keys positive-cache-attempt-keys})))
    (when-not (every? (fn [[_ value]]
                        (and (integer? value) (pos? value)))
                      overrides)
      (throw
       (ex-info
        "Cache-attempt limits must be positive integers."
        {:type :eacl/invalid-config
         :key :cache-attempt
         :value overrides})))
    (merge default-cache-attempt overrides)))

(defn- page-demand
  [request]
  (cond
    (contains? request :first)
    {:kind :page
     :direction :forward
     :size (:first request)
     :bounded? true}

    (contains? request :last)
    {:kind :page
     :direction :backward
     :size (:last request)
     :bounded? (contains? request :before)}

    :else
    {:kind :page
     :direction :forward
     :size nil
     :bounded? true}))

(defn- validated-count-limit
  [request]
  (when (contains? request :count-limit)
    (let [limit (:count-limit request)]
      (when-not (and (integer? limit) (not (neg? limit)))
        (invalid-request!
         ":count-limit must be a non-negative integer."
         {:key :count-limit :value limit}))
      limit)))

(defn operation-demand
  [operation request evaluation]
  (if (= :complete-denotation evaluation)
    {:kind :complete-denotation
     :render
     (case operation
       (:can? :check-permission) :boolean
       (:count-resources :count-subjects) :count
       (:lookup-resources :lookup-subjects) :page
       :unknown)}
    (case operation
      (:can? :check-permission)
      {:kind :boolean}

      (:count-resources :count-subjects)
      (if-some [limit (validated-count-limit request)]
        {:kind :count
         :limit limit
         :sentinel (inc limit)}
        {:kind :exact-count})

      (:lookup-resources :lookup-subjects)
      (page-demand request)

      {:kind :operation})))

(defn normalize
  "Normalizes one public semantic request into its immutable execution contract."
  [client-options operation request]
  (let [request (or request {})
        _ (when-let [forbidden
                     (seq
                      (filter #(contains? request %)
                              [:cache-attempt
                               :recursive-traversal-limits
                               :permission-tree-limits]))]
            (invalid-request!
             "Cache-attempt and structural safety envelopes are client configuration, not per-request demand controls."
             {:forbidden-keys (vec forbidden)}))
        evaluation (normalize-evaluation (:evaluation request))
        timeout-ms
        (normalize-timeout-ms
         (or (:timeout-ms request)
             (:execution-timeout-ms client-options)
             default-execution-timeout-ms))
        started-nanos (now-nanos)
        deadline-nanos (+ started-nanos (* timeout-ms 1000000))]
    {:version 1
     :operation operation
     :evaluation evaluation
     :demand (operation-demand operation request evaluation)
     :configured-timeout-ms timeout-ms
     :started-nanos started-nanos
     :deadline-nanos deadline-nanos
     :limits (:recursive-traversal-limits client-options)
     :cache-attempt (or (:cache-attempt client-options)
                        default-cache-attempt)}))

(defn remaining-nanos
  ([contract]
   (max 0 (- (:deadline-nanos contract) (now-nanos))))
  ([]
   (remaining-nanos *contract*)))

(defn remaining-millis
  ([contract]
   (let [remaining (remaining-nanos contract)]
     (if (zero? remaining)
       0
       (max 1 (quot (+ remaining 999999) 1000000)))))
  ([]
   (remaining-millis *contract*)))

(defn expired?
  ([contract]
   (not (pos? (remaining-nanos contract))))
  ([]
   (and *contract* (expired? *contract*))))

(defn deadline-exceeded!
  [contract stage consumed-work]
  (throw
   (ex-info
    "EACL authorization execution deadline exceeded."
    (cond->
     {:type :eacl.execution/deadline-exceeded
      :eacl/error :eacl.execution/deadline-exceeded
      :operation (:operation contract)
      :stage stage
      :timeout-ms (:configured-timeout-ms contract)}
      (seq consumed-work) (assoc :consumed-work consumed-work)))))

(defn check!
  ([stage]
   (check! *contract* stage nil))
  ([contract stage]
   (check! contract stage nil))
  ([contract stage consumed-work]
   (when (and contract (expired? contract))
     (deadline-exceeded! contract stage consumed-work))
   contract))

(defn cache-stage-available?
  [contract]
  (let [{:keys [evaluation-reserve-ms]}
        (:cache-attempt contract)
        remaining-ms (remaining-millis contract)]
    (and (pos? remaining-ms)
         (> remaining-ms evaluation-reserve-ms))))
