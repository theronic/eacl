(ns eacl.consistency
  "Shared v3 consistency selection over validated backend adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.causal-token :as causal-token]
            [eacl.spicedb.consistency :as public-consistency]))

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
  (let [scope (backend/invoke adapter :source-scope)]
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
    (assoc (select-keys scope [:source-id :branch])
           :backend (backend/backend-id adapter))))

(defn graph-head
  [adapter]
  (let [head (backend/invoke adapter :graph-head)]
    (when-not (and (map? head)
                   (string? (:graph-anchor head))
                   (not-empty (:graph-anchor head))
                   (or (nil? (:order-hint head))
                       (and (integer? (:order-hint head))
                            (not (neg? (:order-hint head)))))
                   (= (:order-hint head)
                      (backend/invoke adapter :order-hint))
                   (= (:exact-locator head)
                      (backend/invoke adapter :exact-locator)))
      (throw
       (ex-info
        "Backend returned an invalid graph head."
        {:type :eacl/invalid-backend-adapter
         :eacl/error :eacl/invalid-backend-adapter
         :backend (backend/backend-id adapter)
         :graph-head head})))
    head))

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

(defn- selected-adapter!
  [source selected]
  (when-not (backend/adapter? selected)
    (throw
     (ex-info
      "A backend selection operation did not return an immutable adapter."
      {:type :eacl/invalid-backend-adapter
       :eacl/error :eacl/invalid-backend-adapter
       :backend (backend/backend-id source)
       :selected selected})))
  (when-not (= (source-scope source) (source-scope selected))
    (fail! :incomparable-scope
           "Backend selection crossed source or branch scope."
           {:source (source-scope source)
            :selected (source-scope selected)}))
  selected)

(defn- require-authority!
  [{:keys [coherence-authority]}]
  (when-not (= :managed coherence-authority)
    (fail! :unsupported-head-barrier
           "Causal selection requires complete writer authority."
           {:coherence-authority
            (or coherence-authority :unknown)})))

(defn- select-adapter
  [source {:keys [mode token]} options]
  (try
    (backend/require-capability! source :consistency mode)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
      error
      (if (= :eacl/unsupported-capability (:type (ex-data error)))
        (case mode
          (:fully-consistent :synchronized-head)
          (fail! :unsupported-head-barrier
                 "The backend cannot establish an authoritative head."
                 {}
                 error)

          :at-exact-snapshot
          (fail! :exact-snapshot-unavailable
                 "The backend cannot reconstruct exact snapshots."
                 {}
                 error)

          :at-least-as-fresh
          (fail! :unsupported-head-barrier
                 "The backend cannot establish causal freshness."
                 {}
                 error)

          (throw error))
        (throw error))))
  (case mode
    (:fully-consistent :synchronized-head)
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
            (throw error)))))}

    (:local-snapshot :minimize-latency)
    {:adapter
     (selected-adapter!
      source
      (backend/invoke source :select-current))}

    :at-least-as-fresh
    (do
      (require-authority! options)
      (let [payload (authenticate source options token)
            selected
            (selected-adapter!
             source
             (backend/invoke
              source :select-at-least payload (:timeout-ms options)))]
        (when-not (backend/invoke
                   selected :contains-anchor?
                   (:graph-anchor payload))
          (fail! :history-divergence
                 "The selected history does not contain the requested mutation anchor."
                 {:graph-anchor (:graph-anchor payload)}))
        {:adapter selected
         :request-token payload}))

    :at-exact-snapshot
    (do
      (require-authority! options)
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
            selected (when selected
                       (selected-adapter! source selected))]
        (when-not selected
          (fail! :exact-snapshot-unavailable
                 "The requested exact snapshot is unavailable."))
        (when-not (= (:graph-anchor payload)
                     (:graph-anchor (graph-head selected)))
          (fail! :history-divergence
                 "The exact locator resolved to a divergent graph."
                 {:expected-anchor (:graph-anchor payload)
                  :actual-anchor
                  (:graph-anchor (graph-head selected))}))
        {:adapter selected
         :request-token payload}))))

(defn select
  "Authenticates and selects exactly one immutable snapshot adapter.

  Returns the adapter, normalized request descriptor, authenticated request
  token data when present, and an optional response token minted from the
  selected graph head."
  [source consistency-value
   {:keys [format-options coherence-authority issue-token?]
    :as options}]
  (let [descriptor (public-consistency/descriptor consistency-value)
        selection (select-adapter source descriptor options)
        selected (:adapter selection)
        request-token (:request-token selection)
        head (graph-head selected)
        response-token
        (when (and issue-token?
                   (= :managed coherence-authority))
          (causal-token/issue
           format-options
           (merge (source-scope selected)
                  head)))]
    {:adapter selected
     :descriptor descriptor
     :request-token request-token
     :response-token response-token
     :graph-head head}))

(defn cursor-conflict!
  [data]
  (fail! :cursor-consistency-conflict
         "Cursor snapshot and requested freshness cannot both be satisfied."
         data))
