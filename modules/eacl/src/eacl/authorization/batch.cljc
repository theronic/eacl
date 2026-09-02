(ns eacl.authorization.batch
  "Portable validation and resource accounting for ordered point-check batches.

  This namespace owns no backend state. Public clients validate here before
  selecting a snapshot, then execute the normalized demands through their
  context-bound scalar point kernel."
  (:require [eacl.request.counters :as request-counters]))

(def default-aggregate-limits
  {:max-batch-size 256
   :max-commands 1000000
   :max-transitions 4000000
   :max-fetched-values 1000000
   :max-candidates-examined 1000000
   :max-probes 1000000
   :max-output-units 100000
   :max-allocation-proxy 10000000
   :candidate-window 10000})

(def ^:private aggregate-limit-keys
  (set (keys default-aggregate-limits)))

(def ^:private request-keys
  #{:checks
    :consistency
    :timeout-ms
    :cancellation-token
    :cache?
    :populate-cache?
    :evaluation
    :aggregate-limits})

(def ^:private demand-keys #{:subject :permission :resource})
(def ^:private endpoint-keys #{:type :id :relation})
(def ^:private per-demand-control-keys
  #{:consistency
    :timeout-ms
    :cancellation-token
    :cache?
    :populate-cache?
    :evaluation
    :aggregate-limits
    :recursive-traversal-limits
    :permission-tree-limits
    :cache-attempt})

(defn- invalid-config!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl/invalid-config
            :eacl/error :eacl/invalid-config}
           data))))

(defn- invalid-request!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl.batch/invalid-request
            :eacl/error :eacl.batch/invalid-request}
           data))))

(defn normalize-client-limits
  "Validates finite positive client ceilings and fills their defaults."
  [overrides]
  (let [overrides (if (nil? overrides) {} overrides)]
    (when-not (map? overrides)
      (invalid-config!
       ":aggregate-limits must be a map."
       {:key :aggregate-limits :value overrides}))
    (when-let [unknown (seq (remove aggregate-limit-keys (keys overrides)))]
      (invalid-config!
       "Unknown aggregate authorization limit."
       {:key :aggregate-limits
        :unknown-keys (vec unknown)
        :known-keys aggregate-limit-keys}))
    (when-not (every? (fn [[_ value]]
                        (and (integer? value) (pos? value)))
                      overrides)
      (invalid-config!
       "Aggregate authorization limits must be positive integers."
       {:key :aggregate-limits :value overrides}))
    (merge default-aggregate-limits overrides)))

(defn normalize-request-limits
  "Applies request-wide refinements without allowing a request to weaken its
  client's safety envelope."
  [configured overrides]
  (let [configured (if (nil? configured)
                     default-aggregate-limits
                     configured)
        overrides (if (nil? overrides) {} overrides)]
    (when-not (map? overrides)
      (invalid-request!
       ":aggregate-limits must be a map."
       {:reason :invalid-aggregate-limits
        :key :aggregate-limits
        :value overrides}))
    (when-let [unknown (seq (remove aggregate-limit-keys (keys overrides)))]
      (invalid-request!
       "Unknown request aggregate authorization limit."
       {:reason :unknown-request-key
        :key :aggregate-limits
        :unknown-keys (vec unknown)
        :known-keys aggregate-limit-keys}))
    (doseq [[key value] overrides]
      (when-not (and (integer? value) (pos? value))
        (invalid-request!
         "Request aggregate authorization limits must be positive integers."
         {:reason :invalid-aggregate-limit
          :key key
          :value value}))
      (when (> value (get configured key))
        (invalid-request!
         "A request cannot weaken the configured aggregate authorization limits."
         {:reason :aggregate-limit-weakening
          :key key
          :value value
          :configured-maximum (get configured key)})))
    (merge configured overrides)))

(defn- validate-endpoint!
  [endpoint position demand-index]
  (when-not (map? endpoint)
    (invalid-request!
     "A batch authorization endpoint must be a map."
     {:reason :malformed-demand
      :demand-index demand-index
      :position position
      :value endpoint}))
  (when-let [unknown (seq (remove endpoint-keys (keys endpoint)))]
    (invalid-request!
     "A batch authorization endpoint contains unknown keys."
     {:reason :unknown-demand-key
      :demand-index demand-index
      :position position
      :unknown-keys (vec unknown)
      :known-keys endpoint-keys}))
  (when-not (keyword? (:type endpoint))
    (invalid-request!
     "A batch authorization endpoint :type must be a keyword."
     {:reason :malformed-demand
      :demand-index demand-index
      :position position
      :key :type
      :value (:type endpoint)}))
  (when-not (contains? endpoint :id)
    (invalid-request!
     "A batch authorization endpoint must contain :id."
     {:reason :malformed-demand
      :demand-index demand-index
      :position position
      :missing-key :id})))

(defn validate-demand!
  "Validates one closed scalar point demand without consulting a snapshot."
  [demand demand-index]
  (when-not (map? demand)
    (invalid-request!
     "Every :checks entry must be a point-authorization demand map."
     {:reason :malformed-demand
      :demand-index demand-index
      :value demand}))
  (when-let [controls (seq (filter per-demand-control-keys (keys demand)))]
    (invalid-request!
     "Batch request controls are request-wide, not per-demand."
     {:reason :per-demand-control
      :demand-index demand-index
      :forbidden-keys (vec controls)}))
  (when-let [unknown (seq (remove demand-keys (keys demand)))]
    (invalid-request!
     "A batch point demand contains unknown keys."
     {:reason :unknown-demand-key
      :demand-index demand-index
      :unknown-keys (vec unknown)
      :known-keys demand-keys}))
  (when-let [missing (seq (remove #(contains? demand %) demand-keys))]
    (invalid-request!
     "A batch point demand is missing required keys."
     {:reason :malformed-demand
      :demand-index demand-index
      :missing-keys (vec missing)}))
  (when-not (keyword? (:permission demand))
    (invalid-request!
     "A batch point demand :permission must be a keyword."
     {:reason :malformed-demand
      :demand-index demand-index
      :key :permission
      :value (:permission demand)}))
  (validate-endpoint! (:subject demand) :subject demand-index)
  (validate-endpoint! (:resource demand) :resource demand-index)
  demand)

(defn validate-request!
  "Validates and normalizes the complete public batch envelope.

  This function is deliberately called before snapshot selection or cache
  lifecycle capture."
  [request configured-limits]
  (when-not (map? request)
    (invalid-request!
     "check-permissions requires a request map."
     {:reason :malformed-request :value request}))
  (when-let [unknown (seq (remove request-keys (keys request)))]
    (invalid-request!
     "The check-permissions request contains unknown keys."
     {:reason :unknown-request-key
      :unknown-keys (vec unknown)
      :known-keys request-keys}))
  (when-not (contains? request :checks)
    (invalid-request!
     "The check-permissions request requires :checks."
     {:reason :malformed-request :missing-key :checks}))
  (when-not (vector? (:checks request))
    (invalid-request!
     ":checks must be a vector."
     {:reason :malformed-request
      :key :checks
      :value (:checks request)}))
  (let [limits
        (normalize-request-limits
         configured-limits (:aggregate-limits request))
        checks (:checks request)
        maximum (:max-batch-size limits)]
    (when (> (count checks) maximum)
      (throw
       (ex-info
        "The authorization batch exceeds its configured maximum size."
        {:type :eacl.execution/resource-limit-exceeded
         :eacl/error :eacl.execution/resource-limit-exceeded
         :reason :aggregate-limit-exceeded
         :limit-kind :batch-size
         :limit maximum
         :actual (count checks)})))
    (doseq [[index demand] (map-indexed vector checks)]
      (validate-demand! demand index))
    (assoc request :aggregate-limits limits)))

(defn demand-key
  "The exact normalized point-demand identity used by the request memo."
  [demand]
  (select-keys demand [:subject :permission :resource]))

(defn scalar-contract
  "Derives a point-check contract without renewing any request-wide control."
  [batch-contract]
  (assoc batch-contract
         :operation :check-permission
         :demand {:kind :boolean}))

(defn- numeric-delta
  [before after key]
  (max 0 (- (get after key 0) (get before key 0))))

(defn aggregate-counters
  "Returns safe cumulative counters relative to the start of one batch."
  [work-before work-after ledger-before ledger-after output-units]
  (let [commands (numeric-delta work-before work-after :advanced-datoms)
        transitions (numeric-delta work-before work-after :queued-work)
        fetched-values (numeric-delta work-before work-after :fetched-values)
        ledger-delta (request-counters/delta ledger-before ledger-after)
        candidates (:candidates-examined ledger-delta)
        probes (:probes ledger-delta)
        allocation-proxy
        (+ commands transitions fetched-values candidates probes output-units)]
    {:commands commands
     :transitions transitions
     :fetched-values fetched-values
     :candidates-examined candidates
     :probes probes
     :output-units output-units
     :allocation-proxy allocation-proxy}))

(def ^:private limit->counter
  {:max-commands :commands
   :max-transitions :transitions
   :max-fetched-values :fetched-values
   :max-candidates-examined :candidates-examined
   :max-probes :probes
   :max-output-units :output-units
   :max-allocation-proxy :allocation-proxy})

(def empty-aggregate-counters
  {:commands 0
   :transitions 0
   :fetched-values 0
   :candidates-examined 0
   :probes 0
   :output-units 0
   :allocation-proxy 0})

(defn check-aggregate-limits!
  [limits counters demand-index]
  (doseq [[limit-key counter-key] limit->counter]
    (let [limit (get limits limit-key)
          actual (get counters counter-key 0)]
      (when (> actual limit)
        (throw
         (ex-info
          "The authorization batch exhausted an aggregate resource limit."
          (cond->
           {:type :eacl.execution/resource-limit-exceeded
            :eacl/error :eacl.execution/resource-limit-exceeded
            :reason :aggregate-limit-exceeded
            :limit-kind counter-key
            :limit limit
            :actual actual
            :aggregate-counters counters}
            (some? demand-index)
            (assoc :demand-index demand-index)))))))
  counters)

(defn throw-demand-error!
  "Preserves a typed failure while attaching its batch position and only safe
  cumulative counters."
  [error demand-index counters]
  (let [data (or (ex-data error) {})]
    (throw
     (ex-info
      (or (ex-message error) "Authorization batch demand failed.")
      (assoc data
             :demand-index (if (contains? data :demand-index)
                             (:demand-index data)
                             demand-index)
             :aggregate-counters
             (or (:aggregate-counters data) counters))
      error))))

(defn call-with-demand-error
  "Runs `f`, preserving an already indexed batch error and otherwise attaching
  the demand that was pending at the outer request boundary."
  [demand-index counters f]
  (try
    (f)
    (catch #?(:clj Throwable :cljs :default) error
      (if (contains? (or (ex-data error) {}) :demand-index)
        (throw error)
        (throw-demand-error! error demand-index counters)))))
