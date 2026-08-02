(ns eacl.verified-kernel
  "Strict portable boundary for pure decisions made by generated Dafny code.

  Generated Java and JavaScript adapters implement `DecisionKernel`. Production
  orchestration remains responsible for authenticated decoding, immutable
  snapshot selection, and backend calls, but it cannot use a cursor or cache
  candidate that an authoritative kernel rejected."
  (:require [eacl.backend.v8 :as backend]
            [eacl.secure-format :as secure]))

(def modes
  #{:legacy-authoritative
    :verified-shadow
    :verified-authoritative})

(def operations
  #{:relationship-page
    :cursor-continuation
    :cache-validation})

(defprotocol DecisionKernel
  (-decide [kernel operation input]
    "Returns one strictly validated pure decision for `operation`."))

(defn kernel?
  [candidate]
  (satisfies? DecisionKernel candidate))

(defn- boundary-error!
  [message data]
  (throw
   (ex-info
    message
    (merge
     {:type :eacl.verification/invalid-boundary
      :eacl/error :eacl.verification/invalid-boundary}
     data))))

(defn- exact-keys!
  [operation label value expected]
  (when-not (and (map? value)
                 (= expected (set (keys value))))
    (boundary-error!
     "Generated-kernel boundary map has unknown or missing fields."
     {:operation operation
      :field label
      :expected-keys expected
      :actual-keys (when (map? value) (set (keys value)))}))
  value)

(defn- safe-integer?
  [value]
  (and
   #?(:clj (integer? value)
      :cljs (and (number? value)
                 (js/Number.isSafeInteger value)))
   (<= backend/minimum-exact-integer
       value
       backend/maximum-exact-integer)))

(defn- safe-natural?
  [value]
  (and (safe-integer? value) (not (neg? value))))

(defn- require-value!
  [operation field predicate value]
  (when-not (predicate value)
    (boundary-error!
     "Generated-kernel boundary value has an invalid representation."
     {:operation operation
      :field field
      :value value}))
  value)

(defn- page-presence?
  [value]
  (or (contains? #{:absent :nil} value)
      (safe-natural? value)))

(defn- validate-page-input!
  [input]
  (let [operation :relationship-page
        {:keys [length request default-size maximum-size]}
        (exact-keys!
         operation
         :input
         input
         #{:length :request :default-size :maximum-size})]
    (require-value! operation :length safe-natural? length)
    (require-value! operation :default-size safe-natural? default-size)
    (require-value! operation :maximum-size safe-natural? maximum-size)
    (exact-keys!
     operation
     :request
     request
     #{:first :last :after :before :has-legacy-limit?
       :has-legacy-cursor?})
    (doseq [field [:first :last :after :before]]
      (require-value!
       operation
       field
       page-presence?
       (get request field)))
    (doseq [field [:has-legacy-limit? :has-legacy-cursor?]]
      (require-value! operation field boolean? (get request field)))
    input))

(defn- validate-exact-input!
  [operation exact]
  (when exact
    (exact-keys!
     operation :exact exact #{:graph :source :proof})
    (require-value! operation :exact-graph safe-natural? (:graph exact))
    (require-value! operation :exact-source string? (:source exact))
    (require-value! operation :exact-proof string? (:proof exact)))
  exact)

(defn- validate-continuation-input!
  [input]
  (let [operation :cursor-continuation
        expected
        #{:authenticated? :scope-matches? :expired?
          :source :cursor-source :current-proof :cursor-proof
          :mode :cursor-graph :exact}]
    (exact-keys! operation :input input expected)
    (doseq [field [:authenticated? :scope-matches? :expired?]]
      (require-value! operation field boolean? (get input field)))
    (doseq [field [:source :cursor-source :current-proof :cursor-proof]]
      (require-value! operation field string? (get input field)))
    (require-value!
     operation :mode #{:minimize-latency :at-least-as-fresh} (:mode input))
    (require-value!
     operation :cursor-graph safe-natural? (:cursor-graph input))
    (validate-exact-input! operation (:exact input))
    input))

(defn- validate-cache-entry!
  [entry]
  (let [operation :cache-validation]
    (exact-keys!
     operation
     :entry
     entry
     #{:status :authenticated? :key :source :graph :proof})
    (require-value!
     operation :entry-status #{:candidate :missing :provider-failure}
     (:status entry))
    (require-value!
     operation :entry-authenticated boolean? (:authenticated? entry))
    (doseq [field [:key :source]]
      (require-value! operation field string? (get entry field)))
    (require-value! operation :entry-graph safe-natural? (:graph entry))
    (require-value!
     operation :entry-proof #(or (nil? %) (string? %)) (:proof entry))
    entry))

(defn- validate-cache-input!
  [input]
  (let [operation :cache-validation
        expected
        #{:deterministic? :dependency-scope-nonempty?
          :expected-key :expected-source :selected-graph
          :ancestors :selected-proof :entry}]
    (exact-keys! operation :input input expected)
    (doseq [field [:deterministic? :dependency-scope-nonempty?]]
      (require-value! operation field boolean? (get input field)))
    (doseq [field [:expected-key :expected-source]]
      (require-value! operation field string? (get input field)))
    (require-value!
     operation :selected-graph safe-natural? (:selected-graph input))
    (require-value!
     operation
     :ancestors
     #(and (set? %) (every? safe-natural? %))
     (:ancestors input))
    (require-value!
     operation :selected-proof #(or (nil? %) (string? %))
     (:selected-proof input))
    (validate-cache-entry! (:entry input))
    input))

(defn validate-input!
  [operation input]
  (case operation
    :relationship-page (validate-page-input! input)
    :cursor-continuation (validate-continuation-input! input)
    :cache-validation (validate-cache-input! input)
    (boundary-error!
     "Unknown generated-kernel operation."
     {:operation operation
      :known-operations operations})))

(defn- validate-page-result!
  [result]
  (let [operation :relationship-page]
    (when-not (map? result)
      (boundary-error!
       "Generated page result must be a map."
       {:operation operation :result result}))
    (case (:status result)
      :invalid
      (do
        (exact-keys! operation :result result #{:status :reason})
        (require-value! operation :reason keyword? (:reason result))
        result)

      :valid
      (do
        (exact-keys!
         operation :result result
         #{:status :direction :size :start :end
           :has-next? :has-previous?})
        (require-value!
         operation :direction #{:asc :desc} (:direction result))
        (doseq [field [:size :start :end]]
          (require-value!
           operation field safe-natural? (get result field)))
        (doseq [field [:has-next? :has-previous?]]
          (require-value!
           operation field boolean? (get result field)))
        (when-not (<= (:start result) (:end result))
          (boundary-error!
           "Generated page window is inverted."
           {:operation operation :result result}))
        result)

      (boundary-error!
       "Generated page result has an unknown variant."
       {:operation operation :result result}))))

(def continuation-decisions
  #{:current
    :exact
    :invalid-authentication
    :scope-mismatch
    :expired
    :conflict
    :snapshot-unavailable
    :history-divergence})

(defn- validate-continuation-result!
  [result]
  (require-value!
   :cursor-continuation :result continuation-decisions result))

(def cache-miss-reasons
  #{:missing
    :provider-failure
    :no-proof-bypass
    :unauthenticated
    :scope-mismatch
    :future-or-sibling
    :proof-mismatch})

(defn- validate-cache-result!
  [result]
  (let [operation :cache-validation]
    (when-not (map? result)
      (boundary-error!
       "Generated cache result must be a map."
       {:operation operation :result result}))
    (case (:status result)
      :hit
      (do
        (exact-keys!
         operation :result result #{:status :provenance})
        (require-value!
         operation :provenance #{:exact-hit :causal-proof-lift}
         (:provenance result))
        result)

      :miss
      (do
        (exact-keys! operation :result result #{:status :reason})
        (require-value!
         operation :reason cache-miss-reasons (:reason result))
        result)

      (boundary-error!
       "Generated cache result has an unknown variant."
       {:operation operation :result result}))))

(defn validate-result!
  [operation result]
  (case operation
    :relationship-page (validate-page-result! result)
    :cursor-continuation (validate-continuation-result! result)
    :cache-validation (validate-cache-result! result)
    (boundary-error!
     "Unknown generated-kernel operation."
     {:operation operation
      :known-operations operations})))

(defn normalize-selection
  [selection]
  (let [selection
        (cond
          (nil? selection) {:mode :legacy-authoritative}
          (keyword? selection) {:mode selection}
          (map? selection) selection
          :else
          (boundary-error!
           "Engine selection must be a mode keyword or configuration map."
           {:selection selection}))
        allowed #{:mode :kernel :report-divergence}
        unknown (seq (remove allowed (keys selection)))
        mode (or (:mode selection) :legacy-authoritative)
        kernel (:kernel selection)
        reporter (:report-divergence selection)]
    (when unknown
      (boundary-error!
       "Engine selection contains unknown fields."
       {:unknown-fields (vec unknown)
        :allowed-fields allowed}))
    (require-value! :engine-selection :mode modes mode)
    (when (and (not= :legacy-authoritative mode)
               (not (kernel? kernel)))
      (boundary-error!
       "Verified engine modes require a generated DecisionKernel."
       {:mode mode
        :kernel-type (str (type kernel))}))
    (when (and reporter (not (fn? reporter)))
      (boundary-error!
       "The shadow divergence reporter must be a function."
       {:reporter reporter}))
    {:mode mode
     :kernel kernel
     :report-divergence reporter}))

(defn- invoke-kernel
  [kernel operation input]
  (validate-input! operation input)
  (try
    (let [result
          (validate-result! operation (-decide kernel operation input))]
      (when (and (= :relationship-page operation)
                 (= :valid (:status result))
                 (or (> (:end result) (:length input))
                     (> (:size result) (:maximum-size input))
                     (zero? (:size result))))
        (boundary-error!
         "Generated page result exceeds its validated input bounds."
         {:operation operation
          :length (:length input)
          :maximum-size (:maximum-size input)
          :result result}))
      result)
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.verification/invalid-boundary
             (:type (ex-data error)))
        (throw error)
        (throw
         (ex-info
          "Generated verification kernel failed closed."
          {:type :eacl.verification/kernel-failure
           :eacl/error :eacl.verification/kernel-failure
           :operation operation}
          error))))))

(defn- report!
  [reporter diagnostic]
  (when reporter
    (try
      (reporter diagnostic)
      (catch #?(:clj Exception :cljs :default) _
        nil))))

(defn decide
  "Runs one pure decision under the configured migration mode.

  `legacy-decision` is a zero-argument function. Shadow failures and
  disagreements are reported using only a canonical input digest and result
  variants; they cannot alter the legacy result."
  [selection operation input legacy-decision]
  (let [{:keys [mode kernel report-divergence]}
        (normalize-selection selection)]
    (case mode
      :legacy-authoritative
      (legacy-decision)

      :verified-authoritative
      (invoke-kernel kernel operation input)

      :verified-shadow
      (let [legacy (legacy-decision)
            digest
            (secure/canonical-digest
             "eacl/verified-kernel/shadow-input/v1"
             [operation input])]
        (try
          (let [verified (invoke-kernel kernel operation input)]
            (when-not (= (secure/canonicalize legacy)
                         (secure/canonicalize verified))
              (report!
               report-divergence
               {:type :eacl.verification/shadow-divergence
                :operation operation
                :input-digest digest
                :legacy legacy
                :verified verified}))
            legacy)
          (catch #?(:clj Exception :cljs :default) error
            (report!
             report-divergence
             {:type :eacl.verification/shadow-kernel-failure
              :operation operation
              :input-digest digest
              :error-type (:type (ex-data error))})
            legacy))))))
