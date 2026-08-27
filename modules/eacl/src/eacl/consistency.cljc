(ns eacl.consistency
  "Shared v4 backend-native revision selection over validated adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.backend.source :as source]
            [eacl.causal-token :as causal-token]
            [eacl.spicedb.consistency :as public-consistency]
            [eacl.subproblem-cache :as subproblem]
            [eacl.verified-kernel :as verified]))

(def error-types
  {:unsupported-head-barrier
   :eacl.consistency/unsupported-head-barrier
   :token-expired :eacl.consistency/token-expired
   :history-divergence :eacl.consistency/history-divergence
   :freshness-unavailable :eacl.consistency/freshness-unavailable
   :freshness-timeout :eacl.consistency/freshness-timeout
   :incomparable-scope :eacl.consistency/incomparable-scope
   :exact-snapshot-unavailable
   :eacl.consistency/exact-snapshot-unavailable
   :cursor-consistency-conflict
   :eacl.consistency/cursor-consistency-conflict})

(defn fail!
  ([reason message]
   (fail! reason message {} nil))
  ([reason message data]
   (fail! reason message data nil))
  ([reason message data cause]
   (throw
    (ex-info
     message
     (merge
      {:type (get error-types reason
                  :eacl.consistency/selection-failed)
       :eacl/error (get error-types reason
                        :eacl.consistency/selection-failed)
       :reason reason}
      data)
     cause))))

(defn native-revision
  [adapter]
  (let [revision (backend/invoke adapter :native-revision)]
    (when-not (and (map? revision)
                   (integer? (:revision revision))
                   (not (neg? (:revision revision)))
                   (= (:revision revision)
                      (backend/invoke adapter :order-hint))
                   (= (:exact-locator revision)
                      (backend/invoke adapter :exact-locator)))
      (throw
       (ex-info
        "Backend returned an invalid native revision."
        {:type :eacl/invalid-backend-adapter
         :eacl/error :eacl/invalid-backend-adapter
         :backend (backend/backend-id adapter)
         :native-revision revision})))
    revision))

(defn- expected-scope
  [source]
  (let [scope (source/source-scope source)
        lifecycle (source/source-lifecycle source)]
    (causal-token/validate-source-lifecycle! lifecycle)
    (assoc scope
           :source-lifecycle lifecycle
           :backend (source/backend-id source))))

(defn- authenticate
  [source {:keys [format-options]} token]
  (try
    (causal-token/token-data
     format-options
     (expected-scope source)
     token)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
      error
      (case (:reason (ex-data error))
        :expired
        (fail! :token-expired
               "The causal token has expired."
               {}
               error)

        :scope-mismatch
        (fail! :incomparable-scope
               "The causal token belongs to another source or branch."
               {}
               error)

        (throw error)))))

(defn- decide
  [options operation input]
  (verified/decide
   (or (:decision-kernel options)
       subproblem/*decision-kernel*)
   operation
   input))

(defn- capability-error
  [source mode]
  (try
    (backend/require-supported!
     (source/backend-id source)
     (source/capabilities source)
     :consistency
     mode)
    nil
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
      error
      error)))

(defn- reject-plan!
  [source mode decision capability-supported?]
  (let [cause
        (when-not capability-supported?
          (capability-error source mode))]
    (case decision
      :unsupported-capability
      (if cause
        (throw cause)
        (throw
         (ex-info
          "The verified consistency plan contradicted backend capabilities."
          {:type :eacl.verification/invalid-boundary
           :eacl/error :eacl.verification/invalid-boundary
           :mode mode})))

      :exact-snapshot-unavailable
      ;; Exact selection has a stable public failure regardless of whether the
      ;; backend omitted the exact capability or advertised it but cannot
      ;; reconstruct the requested locator.  Preserve the capability failure
      ;; only as the cause; leaking :eacl/unsupported-capability here would
      ;; contradict the verified consistency boundary.
      (fail! :exact-snapshot-unavailable
             "The backend cannot reconstruct exact snapshots."
             {}
             cause)

      :unsupported-head-barrier
      (cond
        (and capability-supported?
             (#{:at-least-as-fresh :at-exact-snapshot} mode))
        (fail! :unsupported-head-barrier
               "The backend cannot establish the requested native revision barrier.")

        (= :at-least-as-fresh mode)
        (fail! :unsupported-head-barrier
               "The backend cannot establish causal freshness."
               {}
               cause)

        :else
        (fail! :unsupported-head-barrier
               "The backend cannot establish an authoritative head."
               {}
               cause)))))

(defn selection-plan
  "Returns the validated selection action for one normalized descriptor.

  The capability observation remains an adapter boundary fact. The finite
  decision over that fact is made by the generated kernel."
  [source {:keys [mode]} options]
  (let [capability-supported?
        (source/supports? source :consistency mode)
        input
        {:mode mode
         :capability-supported? capability-supported?}
        decision
        (decide
         options
         :consistency-plan
         input)]
    (if (contains?
         #{:select-current
           :select-authoritative
           :authenticate-and-select-at-least
           :authenticate-and-select-exact}
         decision)
      decision
      (reject-plan!
       source mode decision capability-supported?))))

(defn- selection-check!
  [options phase]
  (when-let [check! (:selection-check! options)]
    (check! phase))
  nil)

(defn- monotonic-millis
  []
  #?(:clj (/ (double (System/nanoTime)) 1000000.0)
     :cljs (.now js/Date)))

(defn- selection-deadline
  [options]
  (+ (monotonic-millis)
     (double (or (:timeout-ms options) 30000))))

(defn- remaining-millis
  [deadline]
  (let [remaining (- deadline (monotonic-millis))]
    (if (pos? remaining)
      (max 1 (long remaining))
      0)))

(defn- release-after-selection-error!
  [selected selection-error]
  (try
    (source/release! selected)
    (catch #?(:clj Throwable :cljs :default) release-error
      (throw
       (ex-info
        "Snapshot selection failed and candidate cleanup also failed."
        {:type :eacl/snapshot-release-failed
         :eacl/error :eacl/snapshot-release-failed
         :backend (:backend (source/semantic-identity selected))
         :selection-error (ex-data selection-error)}
        release-error))))
  (throw selection-error))

(defn- acquire-source-candidate!
  [source kind options & args]
  (selection-check! options :before-snapshot-acquisition)
  (let [selected (apply source/acquire! source kind args)]
    (try
      (selection-check! options :after-snapshot-acquisition)
      selected
      (catch #?(:clj Throwable :cljs :default) error
        (release-after-selection-error! selected error)))))

(defn- source-selected-adapter!
  [source selected kind revision-check options required-scope]
  (let [adapter (source/adapter selected)
        source-scope-value (or required-scope (expected-scope source))
        selected-scope-value
        (select-keys
         (source/semantic-identity selected)
         [:backend :source-id :branch :source-lifecycle])
        same-source-scope? (= source-scope-value selected-scope-value)
        revision-observation
        (if (and same-source-scope? revision-check)
          (revision-check adapter)
          {:satisfied? (nil? revision-check)})
        input
        {:kind kind
         :selection-present? true
         :selected-adapter? true
         :same-source-scope? same-source-scope?
         :revision-satisfied? (boolean (:satisfied? revision-observation))}
        decision
        (decide options :consistency-validation input)]
    (case decision
      :accept selected

      :exact-snapshot-unavailable
      (fail! :exact-snapshot-unavailable
             "The requested exact snapshot is unavailable.")

      :invalid-selected-adapter
      (throw
       (ex-info
        "A provider acquisition did not return an immutable adapter."
        {:type :eacl/invalid-backend-adapter
         :eacl/error :eacl/invalid-backend-adapter
         :backend (source/backend-id source)}))

      :incomparable-scope
      (fail! :incomparable-scope
             "Backend selection crossed source, branch, or lifecycle scope."
             {:source source-scope-value
              :selected selected-scope-value})

      :history-divergence
      (fail! :history-divergence
             (:message revision-observation)
             (:data revision-observation)))))

(defn- validate-source-candidate!
  [source selected kind revision-check options required-scope]
  (try
    (source-selected-adapter!
     source selected kind revision-check options required-scope)
    (catch #?(:clj Throwable :cljs :default) error
      (release-after-selection-error! selected error))))

(defn- freshness-timeout!
  [payload timeout-ms]
  (fail! :freshness-timeout
         "Timed out waiting for the requested native revision."
         {:requested-revision (:revision payload)
          :timeout-ms timeout-ms}))

(defn- select-source-at-least!
  [source payload options deadline]
  (let [timeout-ms (or (:timeout-ms options) 30000)]
    (loop []
      (selection-check! options :at-least-candidate)
      (let [remaining (remaining-millis deadline)]
        (when (zero? remaining)
          (freshness-timeout! payload timeout-ms))
        (let [selected
              (acquire-source-candidate!
               source :at-least options payload remaining)
              _
              (when (zero? (remaining-millis deadline))
                (try
                  (freshness-timeout! payload timeout-ms)
                  (catch #?(:clj Throwable :cljs :default) error
                    (release-after-selection-error! selected error))))
              ;; Acquisition already captured and validated native revision in
              ;; the selected snapshot's semantic identity. Re-observing the
              ;; adapter here is unnecessary and, for owned native snapshots,
              ;; would create a post-validation failure point that could leak
              ;; the candidate before ownership transfer.
              actual
              (:revision (source/semantic-identity selected))]
          (if (>= actual (:revision payload))
            (validate-source-candidate!
             source
             selected
             :at-least
             (fn [_]
               {:satisfied? true
                :message
                "The selected snapshot did not reach the requested native revision."
                :data {:requested-revision (:revision payload)
                       :actual-revision actual}})
             options
             (select-keys
              payload
              [:backend :source-id :branch :source-lifecycle]))
            (do
              (source/release! selected)
              (selection-check! options :at-least-retry)
              (recur))))))))

(defn- select-source-snapshot
  [source {:keys [mode token]} options]
  (case (selection-plan source {:mode mode} options)
      :select-current
      {:selected-snapshot
       (let [selected
             (acquire-source-candidate! source :current options)]
         (validate-source-candidate!
          source selected :current nil options nil))}

      :select-authoritative
      {:selected-snapshot
       (let [selected
             (acquire-source-candidate!
              source
              :authoritative
              options
              (:timeout-ms options))]
         (validate-source-candidate!
          source selected :authoritative nil options nil))}

      :authenticate-and-select-at-least
      (let [payload (authenticate source options token)
            deadline (selection-deadline options)]
        {:selected-snapshot
         (select-source-at-least! source payload options deadline)
         :request-token payload})

      :authenticate-and-select-exact
      (let [payload (authenticate source options token)
            deadline (selection-deadline options)
            selected
            (acquire-source-candidate!
             source :exact options payload (remaining-millis deadline))]
        {:selected-snapshot
         (validate-source-candidate!
          source
          selected
          :exact
          (fn [_selected-adapter]
            (let [actual
                  (select-keys
                   (source/semantic-identity selected)
                   [:revision :exact-locator])]
              {:satisfied?
               (and (= (:revision payload) (:revision actual))
                    (= (:exact-locator payload)
                       (:exact-locator actual)))
               :message
               "The exact locator resolved to a different native revision."
               :data
               {:expected-revision
                (select-keys payload [:revision :exact-locator])
                :actual-revision actual}}))
          options
          (select-keys
           payload
           [:backend :source-id :branch :source-lifecycle]))
         :request-token payload})))

(defn select-from-source
  "Authenticates and acquires exactly one provider-owned selected snapshot.

  The caller owns `:selected-snapshot` on return and must release it in a
  `finally` scope after completely realizing the public response. Every error
  before ownership transfer releases the candidate here."
  [source consistency-value
   {:keys [format-options issue-token?]
    :as options}]
  (when-not (source/source? source)
    (throw
     (ex-info
      "Basis selection requires a certified source."
      {:type :eacl/invalid-source
       :eacl/error :eacl/invalid-source})))
  (let [descriptor (public-consistency/descriptor consistency-value)
        selection (select-source-snapshot source descriptor options)
        selected (:selected-snapshot selection)]
    (try
      (let [selected-adapter (source/adapter selected)
            request-token (:request-token selection)
            identity (source/semantic-identity selected)
            revision (select-keys identity [:revision :exact-locator])
            response-token
            (when issue-token?
              (causal-token/issue
               format-options
               (select-keys
                identity
                [:backend :source-id :branch :source-lifecycle
                 :revision :exact-locator])))]
        {:selected-snapshot selected
         :adapter selected-adapter
         :descriptor descriptor
         :request-token request-token
         :response-token response-token
         :native-revision revision})
      (catch #?(:clj Throwable :cljs :default) error
        (release-after-selection-error! selected error)))))

(defn select
  "Authenticates and acquires exactly one immutable snapshot from a source."
  [source consistency-value
   options]
  (select-from-source source consistency-value options))

(defn selected-basis-token
  "Issues a causal token from a closed semantic basis identity. This is the
  source-free token path used by public snapshots."
  [basis-identity {:keys [format-options]}]
  (causal-token/issue
   format-options
   (select-keys
    basis-identity
    [:backend :source-id :branch :source-lifecycle
     :revision :exact-locator])))

(defn cursor-conflict!
  [data]
  (fail! :cursor-consistency-conflict
         "Cursor snapshot and requested freshness cannot both be satisfied."
         data))
