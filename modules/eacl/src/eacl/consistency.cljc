(ns eacl.consistency
  "Shared v4 backend-native revision selection over validated adapters."
  (:require [eacl.backend.v8 :as backend]
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
  [adapter]
  (source-scope adapter))

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
    (backend/require-capability! source :consistency mode)
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
        (backend/supports? source :consistency mode)
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

(defn select
  "Authenticates and selects exactly one immutable snapshot adapter.

  Returns the adapter, normalized request descriptor, authenticated request
  token data when present, and an optional response token minted from the
  selected backend-native revision."
  [source consistency-value
   {:keys [format-options issue-token?]
    :as options}]
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
     :native-revision revision}))

(defn cursor-conflict!
  [data]
  (fail! :cursor-consistency-conflict
         "Cursor snapshot and requested freshness cannot both be satisfied."
         data))
