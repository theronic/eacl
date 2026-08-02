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
    :cache-validation
    :authorization-evaluation})

(def ^:private maximum-boundary-items 1000000)
(def ^:private maximum-boundary-string-length 65536)

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

(defn- nonempty-string?
  [value]
  (and (string? value)
       (not (empty? value))
       (<= (count value) maximum-boundary-string-length)))

(defn- bounded-string?
  [value]
  (and (string? value)
       (<= (count value) maximum-boundary-string-length)))

(defn- require-value!
  [operation field predicate value]
  (when-not (predicate value)
    (boundary-error!
     "Generated-kernel boundary value has an invalid representation."
     {:operation operation
      :field field
      :value-type (str (type value))}))
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
    (require-value! operation :exact-source bounded-string? (:source exact))
    (require-value! operation :exact-proof bounded-string? (:proof exact)))
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
      (require-value! operation field bounded-string? (get input field)))
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
      (require-value! operation field bounded-string? (get entry field)))
    (require-value! operation :entry-graph safe-natural? (:graph entry))
    (require-value!
     operation
     :entry-proof
     #(or (nil? %) (bounded-string? %))
     (:proof entry))
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
      (require-value! operation field bounded-string? (get input field)))
    (require-value!
     operation :selected-graph safe-natural? (:selected-graph input))
    (require-value!
     operation
     :ancestors
     #(and (set? %) (every? safe-natural? %))
     (:ancestors input))
    (require-value!
     operation
     :selected-proof
     #(or (nil? %) (bounded-string? %))
     (:selected-proof input))
    (validate-cache-entry! (:entry input))
    input))

(defn- validate-object!
  [field value]
  (exact-keys!
   :authorization-evaluation
   field
   value
   #{:type :id})
  (require-value!
   :authorization-evaluation
   [field :type]
   nonempty-string?
   (:type value))
  (require-value!
   :authorization-evaluation
   [field :id]
   nonempty-string?
   (:id value))
  value)

(defn- bounded-vector!
  [field value]
  (require-value!
   :authorization-evaluation
   field
   #(and (vector? %)
         (<= (count %) maximum-boundary-items))
   value))

(defn- validate-relation!
  [index value]
  (let [field [:schema :relations index]]
    (exact-keys!
     :authorization-evaluation
     field
     value
     #{:resource-type :relation :subject-type})
    (doseq [key [:resource-type :relation :subject-type]]
      (require-value!
       :authorization-evaluation
       [field key]
       nonempty-string?
       (get value key)))
    value))

(defn- validate-permission!
  [index value]
  (let [field [:schema :permissions index]]
    (exact-keys!
     :authorization-evaluation
     field
     value
     #{:resource-type :permission})
    (doseq [key [:resource-type :permission]]
      (require-value!
       :authorization-evaluation
       [field key]
       nonempty-string?
       (get value key)))
    value))

(def ^:private definition-keys
  {:direct-relation
   #{:kind :resource-type :permission :relation :subject-type}
   :self-permission
   #{:kind :resource-type :permission :target-permission}
   :arrow-relation
   #{:kind :resource-type :permission :via-relation
     :target-relation :subject-type}
   :arrow-permission
   #{:kind :resource-type :permission :via-relation
     :target-permission}})

(defn- validate-definition!
  [index value]
  (let [field [:schema :definitions index]
        kind (:kind value)
        expected (get definition-keys kind)]
    (when-not expected
      (boundary-error!
       "Generated authorization definition has an unknown variant."
       {:operation :authorization-evaluation
        :field field
        :kind kind}))
    (exact-keys!
     :authorization-evaluation field value expected)
    (doseq [[key field-value] (dissoc value :kind)]
      (require-value!
       :authorization-evaluation
       [field key]
       nonempty-string?
       field-value))
    value))

(defn- validate-schema!
  [schema]
  (exact-keys!
   :authorization-evaluation
   :schema
   schema
   #{:relations :permissions :definitions})
  (doseq [[index value]
          (map-indexed vector
                       (bounded-vector!
                        [:schema :relations]
                        (:relations schema)))]
    (validate-relation! index value))
  (doseq [[index value]
          (map-indexed vector
                       (bounded-vector!
                        [:schema :permissions]
                        (:permissions schema)))]
    (validate-permission! index value))
  (doseq [[index value]
          (map-indexed vector
                       (bounded-vector!
                        [:schema :definitions]
                        (:definitions schema)))]
    (validate-definition! index value))
  schema)

(defn- validate-relationship!
  [index value]
  (let [field [:relationships index]]
    (exact-keys!
     :authorization-evaluation
     field
     value
     #{:resource :relation :subject})
    (validate-object! [field :resource] (:resource value))
    (require-value!
     :authorization-evaluation
     [field :relation]
     nonempty-string?
     (:relation value))
    (validate-object! [field :subject] (:subject value))
    value))

(def ^:private request-keys
  {:can?
   #{:operation :subject :permission :resource}
   :lookup-resources
   #{:operation :subject :permission :resource-type}
   :lookup-subjects
   #{:operation :resource :permission :subject-type}
   :count-resources
   #{:operation :subject :permission :resource-type :count-limit}
   :count-subjects
   #{:operation :resource :permission :subject-type :count-limit}})

(defn- validate-authorization-request!
  [request]
  (let [operation (:operation request)
        expected (get request-keys operation)]
    (when-not expected
      (boundary-error!
       "Generated authorization request has an unknown operation."
       {:operation :authorization-evaluation
        :field :request
        :request-operation operation}))
    (exact-keys!
     :authorization-evaluation :request request expected)
    (when-let [subject (:subject request)]
      (validate-object! [:request :subject] subject))
    (when-let [resource (:resource request)]
      (validate-object! [:request :resource] resource))
    (doseq [field [:permission :resource-type :subject-type]]
      (when (contains? request field)
        (require-value!
         :authorization-evaluation
         [:request field]
         nonempty-string?
         (get request field))))
    (when (contains? request :count-limit)
      (require-value!
       :authorization-evaluation
       [:request :count-limit]
       safe-natural?
       (:count-limit request)))
    request))

(defn- validate-traversal-limits!
  [limits]
  (exact-keys!
   :authorization-evaluation
   :limits
   limits
   #{:max-derived-grants :max-advanced-datoms :max-queued-work})
  (doseq [field [:max-derived-grants
                 :max-advanced-datoms
                 :max-queued-work]]
    (require-value!
     :authorization-evaluation
     [:limits field]
     #(and (safe-natural? %) (pos? %))
     (get limits field)))
  limits)

(defn- validate-authorization-input!
  [input]
  (exact-keys!
   :authorization-evaluation
   :input
   input
   #{:objects :schema :relationships :request :limits})
  (doseq [[index value]
          (map-indexed vector
                       (bounded-vector! :objects (:objects input)))]
    (validate-object! [:objects index] value))
  (validate-schema! (:schema input))
  (doseq [[index value]
          (map-indexed
           vector
           (bounded-vector! :relationships (:relationships input)))]
    (validate-relationship! index value))
  (validate-authorization-request! (:request input))
  (validate-traversal-limits! (:limits input))
  input)

(defn validate-input!
  [operation input]
  (case operation
    :relationship-page (validate-page-input! input)
    :cursor-continuation (validate-continuation-input! input)
    :cache-validation (validate-cache-input! input)
    :authorization-evaluation (validate-authorization-input! input)
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

(defn- validate-counters!
  [counters]
  (exact-keys!
   :authorization-evaluation
   :counters
   counters
   #{:derived-grants :advanced-datoms :queued-work})
  (doseq [field [:derived-grants :advanced-datoms :queued-work]]
    (require-value!
     :authorization-evaluation
     [:counters field]
     safe-natural?
     (get counters field)))
  counters)

(defn- validate-authorization-result!
  [result]
  (let [operation :authorization-evaluation]
    (when-not (map? result)
      (boundary-error!
       "Generated authorization result must be a map."
       {:operation operation
        :result-type (str (type result))}))
    (case (:status result)
      :limit-exceeded
      (do
        (exact-keys!
         operation :result result
         #{:status :operation :limit-kind :counters})
        (require-value!
         operation
         :operation
         (set (keys request-keys))
         (:operation result))
        (require-value!
         operation
         :limit-kind
         #{:derived-grants :advanced-datoms :queued-work}
         (:limit-kind result))
        (validate-counters! (:counters result))
        result)

      :invalid-schema
      (do
        (exact-keys!
         operation :result result
         #{:status :errors})
        (bounded-vector! :errors (:errors result))
        (doseq [error (:errors result)]
          (require-value! operation :schema-error keyword? error))
        result)

      :complete
      (let [request-operation (:operation result)]
        (case request-operation
          :can?
          (do
            (exact-keys!
             operation :result result
             #{:status :operation :allowed? :counters})
            (require-value!
             operation :allowed? boolean? (:allowed? result)))

          (:lookup-resources :lookup-subjects)
          (do
            (exact-keys!
             operation :result result
             #{:status :operation :items :counters})
            (doseq [[index value]
                    (map-indexed
                     vector
                     (bounded-vector! :items (:items result)))]
              (validate-object! [:items index] value)))

          (:count-resources :count-subjects)
          (do
            (exact-keys!
             operation :result result
             #{:status :operation :count :truncated? :counters})
            (require-value!
             operation :count safe-natural? (:count result))
            (require-value!
             operation :truncated? boolean? (:truncated? result)))

          (boundary-error!
           "Generated authorization result names an unknown operation."
           {:operation operation
            :result-operation request-operation}))
        (validate-counters! (:counters result))
        result)

      (boundary-error!
       "Generated authorization result has an unknown variant."
       {:operation operation
        :result-status (:status result)}))))

(defn validate-result!
  [operation result]
  (case operation
    :relationship-page (validate-page-result! result)
    :cursor-continuation (validate-continuation-result! result)
    :cache-validation (validate-cache-result! result)
    :authorization-evaluation (validate-authorization-result! result)
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

(defn- result-variant
  [result]
  (cond
    (keyword? result) result
    (boolean? result) :boolean
    (map? result)
    (select-keys result
                 [:status :operation :reason :provenance :direction
                  :cache-tier :type :eacl/error])
    (sequential? result) :sequence
    (set? result) :set
    (nil? result) :nil
    :else :scalar))

(defn- changed-result-fields
  [legacy verified]
  (if (and (map? legacy) (map? verified))
    (->> (into #{} (concat (keys legacy) (keys verified)))
         (filter
          #(not=
            (secure/canonicalize (get legacy % ::missing))
            (secure/canonicalize (get verified % ::missing))))
         (sort-by pr-str)
         vec)
    [:value]))

(defn decide
  "Runs one pure decision under the configured migration mode.

  `legacy-decision` is a zero-argument function. Shadow failures and
  disagreements are reported using only operation/result shape; they contain
  no request, object, authorization value, or guessable digest and cannot alter
  the legacy result."
  [selection operation input legacy-decision]
  (let [{:keys [mode kernel report-divergence]}
        (normalize-selection selection)]
    (case mode
      :legacy-authoritative
      (legacy-decision)

      :verified-authoritative
      (invoke-kernel kernel operation input)

      :verified-shadow
      (let [legacy (legacy-decision)]
        (try
          (let [verified (invoke-kernel kernel operation input)]
            (when-not (= (secure/canonicalize legacy)
                         (secure/canonicalize verified))
              (report!
               report-divergence
               {:type :eacl.verification/shadow-divergence
                :operation operation
                :changed-fields
                (changed-result-fields legacy verified)
                :legacy-variant (result-variant legacy)
                :verified-variant (result-variant verified)}))
            legacy)
          (catch #?(:clj Exception :cljs :default) error
            (report!
             report-divergence
             {:type :eacl.verification/shadow-kernel-failure
              :operation operation
              :error-type (:type (ex-data error))})
            legacy))))))
