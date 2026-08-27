(ns eacl.consistency
  "Shared v4 backend-native revision selection over validated adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.backend.snapshot-provider :as snapshot-provider]
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

(defn source-scope
  [adapter]
  (let [scope (backend/invoke adapter :source-scope)
        lifecycle (backend/invoke adapter :source-lifecycle)]
    (when-not (and (map? scope)
                   (contains? scope :source-id)
                   (contains? scope :branch))
      (throw
       (ex-info
        "Backend returned an invalid source scope."
        {:type :eacl/invalid-backend-adapter
         :eacl/error :eacl/invalid-backend-adapter
         :backend (backend/backend-id adapter)
         :source-scope scope})))
    (causal-token/validate-source-lifecycle! lifecycle)
    (assoc (select-keys scope [:source-id :branch])
           :source-lifecycle lifecycle
           :backend (backend/backend-id adapter))))

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
  (if (snapshot-provider/provider? source)
    (let [scope (snapshot-provider/source-scope source)
          lifecycle (snapshot-provider/source-lifecycle source)]
      (causal-token/validate-source-lifecycle! lifecycle)
      (assoc scope
             :source-lifecycle lifecycle
             :backend (snapshot-provider/backend-id source)))
    (source-scope source)))

(defn- authenticate
  [adapter {:keys [format-options]} token]
  (try
    (causal-token/token-data
     format-options
     (expected-scope adapter)
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
    (if (snapshot-provider/provider? source)
      (backend/require-supported!
       (snapshot-provider/backend-id source)
       (snapshot-provider/capabilities source)
       :consistency
       mode)
      (backend/require-capability! source :consistency mode))
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
        (if (snapshot-provider/provider? source)
          (snapshot-provider/supports? source :consistency mode)
          (backend/supports? source :consistency mode))
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

(defn- selected-adapter!
  [source selected kind revision-check options]
  (let [selection-present? (some? selected)
        selected-adapter?
        (and selection-present? (backend/adapter? selected))
        identical-selection?
        (and selected-adapter? (identical? source selected))
        source-scope-value
        (when (and selected-adapter? (not identical-selection?))
          (source-scope source))
        selected-scope-value
        (when (and selected-adapter? (not identical-selection?))
          (source-scope selected))
        same-source-scope?
        (and selected-adapter?
             (or identical-selection?
                 (= source-scope-value selected-scope-value)))
        revision-observation
        (if (and same-source-scope? revision-check)
          (revision-check selected)
          {:satisfied? (nil? revision-check)})
        input
        {:kind kind
         :selection-present? selection-present?
         :selected-adapter? selected-adapter?
         :same-source-scope? same-source-scope?
         :revision-satisfied? (boolean (:satisfied? revision-observation))}
        decision
        (decide
         options
         :consistency-validation
         input)]
    (case decision
      :accept selected

      :exact-snapshot-unavailable
      (fail! :exact-snapshot-unavailable
             "The requested exact snapshot is unavailable.")

      :invalid-selected-adapter
      (throw
       (ex-info
        "A backend selection operation did not return an immutable adapter."
        {:type :eacl/invalid-backend-adapter
         :eacl/error :eacl/invalid-backend-adapter
         :backend (backend/backend-id source)
         :selected selected}))

      :incomparable-scope
      (fail! :incomparable-scope
             "Backend selection crossed source or branch scope."
             {:source source-scope-value
              :selected selected-scope-value})

      :history-divergence
      (fail! :history-divergence
             (:message revision-observation)
             (:data revision-observation)))))

(defn captured-current-selection
  "Validates the zero-coordination path over an already captured immutable DB.

  Backends use this instead of refreshing a connection through
  `:select-current`, preserving the request's single-snapshot invariant."
  [source consistency-value options]
  (let [descriptor (public-consistency/descriptor consistency-value)
        action (selection-plan source descriptor options)]
    (when-not (= :select-current action)
      (throw
       (ex-info
        "Captured-current selection requires a local consistency mode."
        {:type :eacl.verification/invalid-boundary
         :eacl/error :eacl.verification/invalid-boundary
         :mode (:mode descriptor)
         :action action})))
    ;; `selection-plan` observes capabilities through the validated source
    ;; adapter. Returning that identical immutable value cannot cross scope and
    ;; therefore needs no second generated FFI decision.
    {:adapter source
     :descriptor descriptor
     :request-token nil
     :response-token nil}))

(defn- select-adapter
  [source {:keys [mode token]} options]
  (case (selection-plan source {:mode mode} options)
    :select-authoritative
    {:adapter
     (selected-adapter!
      source
      (try
        (backend/invoke
         source :select-authoritative (:timeout-ms options))
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
          error
          (if (= :eacl/unsupported-capability
                 (:type (ex-data error)))
            (fail! :unsupported-head-barrier
                   "The backend cannot establish an authoritative head."
                   {}
                   error)
            (throw error))))
      :authoritative
      nil
      options)}

    :select-current
    {:adapter
     (selected-adapter!
      source
      (backend/invoke source :select-current)
      :current
      nil
      options)}

    :authenticate-and-select-at-least
    (let [payload (authenticate source options token)
          selected
          (selected-adapter!
           source
           (backend/invoke
            source :select-at-least payload (:timeout-ms options))
           :at-least
           (fn [selected]
             (let [actual (:revision (native-revision selected))]
               {:satisfied? (>= actual (:revision payload))
                :message
                "The selected snapshot did not reach the requested native revision."
                :data {:requested-revision (:revision payload)
                       :actual-revision actual}}))
           options)]
      {:adapter selected
       :request-token payload})

    :authenticate-and-select-exact
    (let [payload (authenticate source options token)
          selected
          (try
            (backend/invoke
             source :select-exact payload (:timeout-ms options))
            (catch #?(:clj clojure.lang.ExceptionInfo
                      :cljs cljs.core.ExceptionInfo)
              error
              (if (= :eacl/unsupported-capability
                     (:type (ex-data error)))
                (fail! :exact-snapshot-unavailable
                       "The requested exact snapshot is unavailable."
                       {}
                       error)
                (throw error))))
          selected
          (selected-adapter!
           source
           selected
           :exact
           (fn [selected]
             (let [actual (native-revision selected)]
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
           options)]
      {:adapter selected
       :request-token payload})))

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
    (snapshot-provider/release! selected)
    (catch #?(:clj Throwable :cljs :default) release-error
      (throw
       (ex-info
        "Snapshot selection failed and candidate cleanup also failed."
        {:type :eacl/snapshot-release-failed
         :eacl/error :eacl/snapshot-release-failed
         :backend (:backend (snapshot-provider/semantic-identity selected))
         :selection-error (ex-data selection-error)}
        release-error))))
  (throw selection-error))

(defn- acquire-provider-candidate!
  [source kind options & args]
  (selection-check! options :before-snapshot-acquisition)
  (let [selected (apply snapshot-provider/acquire! source kind args)]
    (try
      (selection-check! options :after-snapshot-acquisition)
      selected
      (catch #?(:clj Throwable :cljs :default) error
        (release-after-selection-error! selected error)))))

(defn- provider-selected-adapter!
  [source selected kind revision-check options required-scope]
  (let [adapter (snapshot-provider/adapter selected)
        provider-scope-value (or required-scope (expected-scope source))
        selected-scope-value (source-scope adapter)
        same-source-scope? (= provider-scope-value selected-scope-value)
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
         :backend (snapshot-provider/backend-id source)}))

      :incomparable-scope
      (fail! :incomparable-scope
             "Backend selection crossed source, branch, or lifecycle scope."
             {:source provider-scope-value
              :selected selected-scope-value})

      :history-divergence
      (fail! :history-divergence
             (:message revision-observation)
             (:data revision-observation)))))

(defn- validate-provider-candidate!
  [source selected kind revision-check options required-scope]
  (try
    (provider-selected-adapter!
     source selected kind revision-check options required-scope)
    (catch #?(:clj Throwable :cljs :default) error
      (release-after-selection-error! selected error))))

(defn- freshness-timeout!
  [payload timeout-ms]
  (fail! :freshness-timeout
         "Timed out waiting for the requested native revision."
         {:requested-revision (:revision payload)
          :timeout-ms timeout-ms}))

(defn- select-provider-at-least!
  [source payload options deadline]
  (let [timeout-ms (or (:timeout-ms options) 30000)]
    (loop []
      (selection-check! options :at-least-candidate)
      (let [remaining (remaining-millis deadline)]
        (when (zero? remaining)
          (freshness-timeout! payload timeout-ms))
        (let [selected
              (acquire-provider-candidate!
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
              (:revision (snapshot-provider/semantic-identity selected))]
          (if (>= actual (:revision payload))
            (validate-provider-candidate!
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
              (snapshot-provider/release! selected)
              (selection-check! options :at-least-retry)
              (recur))))))))

(defn- select-provider-snapshot
  [source {:keys [mode token]} options]
  (case (selection-plan source {:mode mode} options)
      :select-current
      {:selected-snapshot
       (let [selected
             (acquire-provider-candidate! source :current options)]
         (validate-provider-candidate!
          source selected :current nil options nil))}

      :select-authoritative
      {:selected-snapshot
       (let [selected
             (acquire-provider-candidate!
              source
              :authoritative
              options
              (:timeout-ms options))]
         (validate-provider-candidate!
          source selected :authoritative nil options nil))}

      :authenticate-and-select-at-least
      (let [payload (authenticate source options token)
            deadline (selection-deadline options)]
        {:selected-snapshot
         (select-provider-at-least! source payload options deadline)
         :request-token payload})

      :authenticate-and-select-exact
      (let [payload (authenticate source options token)
            deadline (selection-deadline options)
            selected
            (acquire-provider-candidate!
             source :exact options payload (remaining-millis deadline))]
        {:selected-snapshot
         (validate-provider-candidate!
          source
          selected
          :exact
          (fn [_selected-adapter]
            (let [actual
                  (select-keys
                   (snapshot-provider/semantic-identity selected)
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

(defn select-from-provider
  "Authenticates and acquires exactly one provider-owned selected snapshot.

  The caller owns `:selected-snapshot` on return and must release it in a
  `finally` scope after completely realizing the public response. Every error
  before ownership transfer releases the candidate here."
  [source consistency-value
   {:keys [format-options issue-token?]
    :as options}]
  (when-not (snapshot-provider/provider? source)
    (throw
     (ex-info
      "Provider selection requires a snapshot provider."
      {:type :eacl/invalid-snapshot-provider
       :eacl/error :eacl/invalid-snapshot-provider})))
  (let [descriptor (public-consistency/descriptor consistency-value)
        selection (select-provider-snapshot source descriptor options)
        selected (:selected-snapshot selection)]
    (try
      (let [selected-adapter (snapshot-provider/adapter selected)
            request-token (:request-token selection)
            identity (snapshot-provider/semantic-identity selected)
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
  "Authenticates and selects exactly one immutable snapshot adapter.

  Returns the adapter, normalized request descriptor, authenticated request
  token data when present, and an optional response token minted from the
  selected backend-native revision."
  [source consistency-value
   {:keys [format-options issue-token?]
    :as options}]
  (if (snapshot-provider/provider? source)
    (select-from-provider source consistency-value options)
    (let [descriptor (public-consistency/descriptor consistency-value)
          selection (select-adapter source descriptor options)
          selected (:adapter selection)
          request-token (:request-token selection)
          revision (native-revision selected)
          response-token
          (when issue-token?
            (causal-token/issue
             format-options
             (merge (source-scope selected)
                    revision)))]
      {:adapter selected
       :descriptor descriptor
       :request-token request-token
       :response-token response-token
       :native-revision revision})))

(defn selected-adapter-token
  "Issues a causal token from an already selected immutable adapter.

  This helper never selects from, synchronizes, or re-reads a live connection;
  the response token therefore names the same snapshot used by the caller."
  [adapter {:keys [format-options]}]
  (causal-token/issue
   format-options
   (merge (source-scope adapter)
          (native-revision adapter))))

(defn cursor-conflict!
  [data]
  (fail! :cursor-consistency-conflict
         "Cursor snapshot and requested freshness cannot both be satisfied."
         data))
