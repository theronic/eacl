(ns eacl.execution
  "Normalized demand, deadline, and cancellation contracts.

  Public orchestration creates exactly one contract before consistency or cache
  work. Runtime layers may observe the contract and cooperatively check its
  absolute monotonic deadline and caller-owned cancellation token, but never
  replace either control with a fresh relative timeout or token."
  (:require [eacl.authorization.batch :as batch]))

(def default-execution-timeout-ms 30000)
(def maximum-execution-timeout-ms 3600000)

(def ^:dynamic *monotonic-nanos*
  #?(:clj (fn [] (System/nanoTime))
     :cljs (fn []
             (js/Math.floor
              (* 1000000
                 (if (exists? js/performance)
                   (.now js/performance)
                   (.now js/Date)))))))

(def ^:dynamic *contract* nil)

(defprotocol CooperativeCancellation
  "A non-blocking, caller-owned cancellation signal.

  Implementations must make `-cancelled?` safe to call repeatedly from hot
  traversal checkpoints. EACL's `cancellation-token` is the portable default."
  (-cancelled? [token])
  (-cancel! [token]))

(deftype CancellationToken [state]
  CooperativeCancellation
  (-cancelled? [_] (true? @state))
  (-cancel! [_]
    (reset! state true)
    true))

(defn cancellation-token
  "Creates one portable cooperative cancellation token.

  Supply the token as request `:cancellation-token`, then call `cancel!` from
  the request owner. A token belongs to one logical request and may be shared
  safely with the thread or callback that observes client disconnect."
  []
  (->CancellationToken (atom false)))

(defn cancellation-token?
  [value]
  (satisfies? CooperativeCancellation value))

(defn cancel!
  "Requests cooperative cancellation. Returns true and is idempotent."
  [token]
  (when-not (cancellation-token? token)
    (throw
     (ex-info
      ":cancellation-token must implement CooperativeCancellation."
      {:type :eacl.execution/invalid-contract
       :eacl/error :eacl.execution/invalid-contract
       :key :cancellation-token
       :value-type (some-> token type str)})))
  (-cancel! token)
  true)

(defn cancelled?
  "True when cooperative cancellation has been requested for `token`."
  [token]
  (boolean (and token (-cancelled? token))))

(defn now-nanos []
  (*monotonic-nanos*))

(defn- invalid-request!
  [message data]
  (throw
   (ex-info
    message
    (merge {:type :eacl.execution/invalid-contract
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
       :check-permissions :batch
       (:count-resources :count-subjects) :count
       (:lookup-resources :lookup-subjects) :page
       :unknown)}
    (case operation
      (:can? :check-permission)
      {:kind :boolean}

      :check-permissions
      {:kind :batch
       :size (count (:checks request))}

      (:count-resources :count-subjects)
      (if-some [limit (validated-count-limit request)]
        {:kind :count
         :limit limit
         :sentinel (inc limit)}
        {:kind :exact-count})

      (:lookup-resources :lookup-subjects)
      (page-demand request)

      {:kind :operation})))

(def ^:private forbidden-request-keys
  [:cache-attempt :recursive-traversal-limits :permission-tree-limits])

(defn- validate-request-controls!
  "Shared by `normalize` and `refine`: rejects structural safety envelopes
  posing as per-request demand controls and malformed cancellation tokens."
  [request]
  (when-let [forbidden (seq (filter #(contains? request %)
                                    forbidden-request-keys))]
    (invalid-request!
     "Structural safety envelopes are client configuration, not per-request demand controls."
     {:forbidden-keys (vec forbidden)}))
  (when-let [cancellation-token (:cancellation-token request)]
    (when-not (cancellation-token? cancellation-token)
      (invalid-request!
       ":cancellation-token must be created by eacl.execution/cancellation-token or implement CooperativeCancellation."
       {:key :cancellation-token
        :value-type (some-> cancellation-token type str)}))))

(defn normalize
  "Normalizes one public semantic request into its immutable execution contract."
  [client-options operation request]
  (let [request (or request {})
        _ (validate-request-controls! request)
        evaluation (normalize-evaluation (:evaluation request))
        cancellation-token (:cancellation-token request)
        timeout-ms
        (normalize-timeout-ms
         (or (:timeout-ms request)
             (:execution-timeout-ms client-options)
             default-execution-timeout-ms))
        started-nanos (now-nanos)
        deadline-nanos (+ started-nanos (* timeout-ms 1000000))
        aggregate-limits
        (batch/normalize-request-limits
         (:aggregate-limits client-options)
         (:aggregate-limits request))]
    {:version 1
     :operation operation
     :evaluation evaluation
     :demand (operation-demand operation request evaluation)
     :configured-timeout-ms timeout-ms
     :started-nanos started-nanos
     :deadline-nanos deadline-nanos
     :cancellation-token cancellation-token
     :limits (:recursive-traversal-limits client-options)
     :aggregate-limits aggregate-limits}))

(defn refine
  "Builds a nested semantic-operation contract without renewing request time.

  Composed snapshot views retain the outer absolute deadline and cancellation
  token. A nested operation may tighten aggregate work limits and select its
  own demand/evaluation shape, but it cannot loosen an outer request-wide
  ceiling or install a new clock/token budget."
  [contract client-options operation request]
  (let [request (or request {})
        _ (validate-request-controls! request)
        evaluation
        (if (contains? request :evaluation)
          (normalize-evaluation (:evaluation request))
          (:evaluation contract))
        _ (when (contains? request :timeout-ms)
            (normalize-timeout-ms (:timeout-ms request)))
        cancellation-token (:cancellation-token request)
        _ (when (and cancellation-token
                     (not (identical? cancellation-token
                                     (:cancellation-token contract))))
            (invalid-request!
             "A composed snapshot operation cannot replace the outer cancellation token."
             {:key :cancellation-token
              :reason :request-control-fixed}))
        nested-limits
        (batch/normalize-request-limits
         (:aggregate-limits client-options)
         (:aggregate-limits request))
        aggregate-limits
        (merge-with min (:aggregate-limits contract) nested-limits)]
    (assoc contract
           :operation operation
           :evaluation evaluation
           :demand (operation-demand operation request evaluation)
           :aggregate-limits aggregate-limits)))

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
   (if *contract* (expired? *contract*) false)))

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

(defn cancellation-observed!
  [contract stage consumed-work]
  (throw
   (ex-info
    "EACL authorization execution was cancelled."
    (cond->
     {:type :eacl.execution/cancelled
      :eacl/error :eacl.execution/cancelled
      :operation (:operation contract)
      :stage stage}
      (seq consumed-work) (assoc :consumed-work consumed-work)))))

(defn- resolve-work
  "Consumed-work may be a map or a 0-arg fn evaluated only on the throw
  path, so hot callers never build diagnostic maps on success."
  [consumed-work]
  (if (fn? consumed-work) (consumed-work) consumed-work))

(defn check!
  ([stage]
   (check! *contract* stage nil))
  ([contract stage]
   (check! contract stage nil))
  ([contract stage consumed-work]
   (when (and contract (expired? contract))
     (deadline-exceeded! contract stage (resolve-work consumed-work)))
   (when (and contract
              (cancelled? (:cancellation-token contract)))
     (cancellation-observed! contract stage (resolve-work consumed-work)))
   contract))

(defn cache-stage-available?
  ([contract]
   (or (nil? contract)
       (and (not (cancelled? (:cancellation-token contract)))
            (pos? (remaining-nanos contract)))))
  ([]
   (cache-stage-available? *contract*)))
