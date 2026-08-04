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
    :relationship-keyset-page
    :cursor-continuation
    :cache-validation
    :current-cache-decision
    :subproblem-cache-decision
    :ordered-merge-step
    :ordered-merge-chunk
    :indexed-scan-response
    :indexed-plan-certification
    :indexed-seed-certification
    :authorization-evaluation})

(def ^:private maximum-boundary-items 1000000)
(def ^:private maximum-boundary-string-length 65536)

(defprotocol DecisionKernel
  (-decide [kernel operation input]
    "Returns one strictly validated pure decision for `operation`."))

(defprotocol IndexedTraversalKernel
  (-compile-indexed-plan [kernel input]
    "Compiles certified portable rules into one opaque generated plan.")
  (-initialize-indexed [kernel direction input]
    "Creates one opaque generated traversal state from strict portable input.")
  (-drive-indexed [kernel direction state limits fuel]
    "Drives opaque generated state until scan, completion, rejection, or yield.")
  (-resume-indexed [kernel direction state response limits]
    "Resumes opaque generated state with one strict ordered scan response.")
  (-read-indexed-result [kernel direction state]
    "Reads the completed public render result and dimensional counters."))

(defn kernel?
  [candidate]
  (satisfies? DecisionKernel candidate))

(defn indexed-traversal-kernel?
  [candidate]
  (satisfies? IndexedTraversalKernel candidate))

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

(defn- validate-keyset-page-input!
  [input]
  (let [operation :relationship-keyset-page]
    (exact-keys!
     operation
     :input
     input
     #{:direction :size :bound? :realized-count})
    (require-value!
     operation :direction #{:asc :desc} (:direction input))
    (require-value! operation :size safe-natural? (:size input))
    (when (zero? (:size input))
      (boundary-error!
       "Generated keyset page size must be positive."
       {:operation operation :field :size}))
    (require-value!
     operation :bound? boolean? (:bound? input))
    (require-value!
     operation
     :realized-count
     safe-natural?
     (:realized-count input))
    (when (> (:realized-count input) (inc (:size input)))
      (boundary-error!
       "Generated keyset page input exceeds its one-row lookahead bound."
       {:operation operation
        :size (:size input)
        :realized-count (:realized-count input)}))
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
     operation :mode #{:recover-current :exact-snapshot} (:mode input))
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

(defn- validate-subproblem-cache-input!
  [input]
  (let [operation :subproblem-cache-decision]
    (require-value!
     operation
     :decision
     #{:lookup :admission :publication}
     (:decision input))
    (case (:decision input)
      :lookup
      (do
        (exact-keys!
         operation
         :input
         input
         #{:decision :recursive-self? :candidate})
        (require-value!
         operation :recursive-self? boolean? (:recursive-self? input))
        (require-value!
         operation
         :candidate
         #{:missing :computing :complete :failed}
         (:candidate input)))

      :admission
      (do
        (exact-keys!
         operation
         :input
         input
         #{:decision :candidate-present?
           :represented-candidates :maximum-candidates})
        (require-value!
         operation
         :candidate-present?
         boolean?
         (:candidate-present? input))
        (doseq [field [:represented-candidates :maximum-candidates]]
          (require-value!
           operation field safe-natural? (get input field))))

      :publication
      (do
        (exact-keys!
         operation
         :input
         input
         #{:decision :ticket-current? :complete? :valid?
           :weight :budget})
        (doseq [field [:ticket-current? :complete? :valid?]]
          (require-value! operation field boolean? (get input field)))
        (doseq [field [:weight :budget]]
          (require-value!
           operation field safe-natural? (get input field)))))
    input))

(def ^:private current-cache-stages
  #{:eligibility :generation :exact-entry :managed-entry})

(def ^:private current-cache-actions
  #{:bypass-current-cache
    :probe-exact-entry
    :use-exact-entry
    :probe-managed-entry
    :use-managed-entry
    :compute-current-value})

(defn- validate-current-cache-input!
  [input]
  (let [operation :current-cache-decision]
    (exact-keys!
     operation :input input #{:stage :available?})
    (require-value!
     operation :stage current-cache-stages (:stage input))
    (require-value!
     operation :available? boolean? (:available? input))
    input))

(def ^:private ordered-merge-steps
  #{:left-exhausted
    :right-exhausted
    :take-left
    :take-right
    :take-both})

(defn- validate-ordered-merge-input!
  [input]
  (let [operation :ordered-merge-step]
    (exact-keys!
     operation :input input #{:direction :left-head :right-head})
    (require-value!
     operation :direction #{:asc :desc} (:direction input))
    (doseq [field [:left-head :right-head]]
      (require-value!
       operation field #(or (nil? %) (safe-natural? %)) (get input field)))
    input))

(declare bounded-vector!)
(declare indexed-scan-rejection-reasons)
(declare validate-indexed-state!)

(defn- strictly-ordered-values?
  [direction values]
  (let [ordered?
        (case direction
          :asc <
          :desc >)]
    (every?
     (fn [[left right]]
       (ordered? left right))
     (partition 2 1 values))))

(defn- validate-ordered-merge-chunk-input!
  [input]
  (let [operation :ordered-merge-chunk
        direction (:direction input)]
    (exact-keys!
     operation :input input #{:direction :left :right})
    (require-value!
     operation :direction #{:asc :desc} direction)
    (doseq [field [:left :right]]
      (let [values (bounded-vector! operation field (get input field))]
        (doseq [[index value] (map-indexed vector values)]
          (require-value!
           operation [field index] safe-natural? value))
        (when-not (strictly-ordered-values? direction values)
          (boundary-error!
           "Generated merge input must be strictly ordered."
           {:operation operation
            :field field
            :direction direction}))))
    input))

(defn- validate-indexed-projection!
  ([projection]
   (validate-indexed-projection!
    :indexed-scan-response [:command :projection] projection))
  ([operation projection]
   (validate-indexed-projection!
    operation [:command :projection] projection))
  ([operation path projection]
   (let [expected
         (case (:kind projection)
           :subject->resources
           #{:kind :subject-type :subject-eid :relation-eid
             :resource-type :bound-eid}

           :resource->subjects
           #{:kind :resource-type :resource-eid :relation-eid
             :subject-type :bound-eid}

           nil)]
     (when-not expected
       (boundary-error!
        "Generated indexed projection has an unknown variant."
        {:operation operation
         :field path
         :kind (:kind projection)}))
     (exact-keys!
      operation path projection expected)
     (doseq [component [:subject-type :resource-type]]
       (when (contains? projection component)
         (require-value!
          operation
          (conj path component)
          nonempty-string?
          (get projection component))))
     (doseq [component [:subject-eid :resource-eid :relation-eid]]
       (when (contains? projection component)
         (require-value!
          operation
          (conj path component)
          safe-natural?
          (get projection component))))
     (require-value!
      operation
      (conj path :bound-eid)
      #(or (nil? %) (safe-natural? %))
      (:bound-eid projection))
     projection)))

(defn- validate-indexed-scan-command!
  [operation field command]
  (exact-keys!
   operation
   field
   command
   #{:request-scope :request-id :projection :chunk-size})
  (require-value!
   operation [field :request-scope] safe-natural?
   (:request-scope command))
  (require-value!
   operation [field :request-id] safe-natural?
   (:request-id command))
  (require-value!
   operation
   [field :chunk-size]
   #(and (safe-natural? %) (pos? %))
   (:chunk-size command))
  (validate-indexed-projection!
   operation (:projection command))
  command)

(defn- validate-indexed-scan-input!
  [input]
  (let [operation :indexed-scan-response
        {:keys [command response]}
        (exact-keys!
         operation :input input #{:command :response})]
    (validate-indexed-scan-command! operation :command command)
    (exact-keys!
     operation
     :response
     response
     #{:request-scope :request-id :values :terminal? :fetched-values})
    (require-value!
     operation [:response :request-scope] safe-natural?
     (:request-scope response))
    (require-value!
     operation [:response :request-id] safe-natural?
     (:request-id response))
    (bounded-vector!
     operation
     [:response :values]
     (:values response))
    (doseq [[index value] (map-indexed vector (:values response))]
      (require-value!
       operation [:response :values index] safe-integer? value))
    (require-value!
     operation [:response :terminal?] boolean? (:terminal? response))
    (require-value!
     operation [:response :fetched-values] safe-natural?
     (:fetched-values response))
    input))

(def ^:private indexed-rule-keys
  {:relation
   #{:kind :head :relation-eid :subject-type}
   :self-permission
   #{:kind :head :target-node}
   :arrow-relation
   #{:kind :head :via-relation-eid :intermediate-type
     :target-relation-eid :target-subject-type}
   :arrow-permission
   #{:kind :head :via-relation-eid :intermediate-type :target-node}})

(declare definition-keys)

(defn- validate-indexed-node!
  ([field node]
   (validate-indexed-node!
    :indexed-plan-certification field node))
  ([operation field node]
   (exact-keys!
    operation
    field
    node
    #{:resource-type :permission})
   (doseq [key [:resource-type :permission]]
     (require-value!
      operation
      [field key]
      nonempty-string?
      (get node key)))
   node))

(defn- validate-indexed-rule!
  ([field rule]
   (validate-indexed-rule!
    :indexed-plan-certification field rule))
  ([operation field rule]
   (let [kind (:kind rule)
         expected (get indexed-rule-keys kind)]
     (when-not expected
       (boundary-error!
        "Generated indexed plan contains an unknown rule variant."
        {:operation operation
         :field field
         :kind kind}))
     (exact-keys! operation field rule expected)
     (validate-indexed-node!
      operation [field :head] (:head rule))
     (when (contains? rule :target-node)
       (validate-indexed-node!
        operation
        [field :target-node]
        (:target-node rule)))
     (doseq [key [:relation-eid :via-relation-eid
                  :target-relation-eid]]
       (when (contains? rule key)
         (require-value!
          operation
          [field key]
          safe-natural?
          (get rule key))))
     (doseq [key [:subject-type :intermediate-type
                  :target-subject-type]]
       (when (contains? rule key)
         (require-value!
          operation
          [field key]
          nonempty-string?
          (get rule key))))
     rule)))

(defn- validate-plan-relation!
  [field relation]
  (exact-keys!
   :indexed-plan-certification
   field
   relation
   #{:resource-type :relation :subject-type})
  (doseq [key [:resource-type :relation :subject-type]]
    (require-value!
     :indexed-plan-certification
     [field key]
     nonempty-string?
     (get relation key)))
  relation)

(defn- validate-plan-permission!
  [field permission]
  (validate-indexed-node! field permission))

(defn- validate-plan-definition!
  [field definition]
  (let [kind (:kind definition)
        expected (get definition-keys kind)]
    (when-not expected
      (boundary-error!
       "Generated indexed plan contains an unknown definition variant."
       {:operation :indexed-plan-certification
        :field field
        :kind kind}))
    (exact-keys!
     :indexed-plan-certification field definition expected)
    (doseq [[key value] (dissoc definition :kind)]
      (require-value!
       :indexed-plan-certification
       [field key]
       nonempty-string?
       value))
    definition))

(defn- validate-indexed-plan-input!
  [input]
  (let [operation :indexed-plan-certification
        {:keys [relations permissions definitions relation-bindings
                indexed-rules]}
        (exact-keys!
         operation
         :input
         input
         #{:relations :permissions :definitions :relation-bindings
           :indexed-rules})]
    (doseq [[index relation]
            (map-indexed
             vector
             (bounded-vector! operation :relations relations))]
      (validate-plan-relation! [:relations index] relation))
    (doseq [[index permission]
            (map-indexed
             vector
             (bounded-vector! operation :permissions permissions))]
      (validate-plan-permission!
       [:permissions index]
       permission))
    (doseq [[index definition]
            (map-indexed
             vector
             (bounded-vector! operation :definitions definitions))]
      (validate-plan-definition!
       [:definitions index]
       definition))
    (doseq [[index binding]
            (map-indexed
             vector
             (bounded-vector!
              operation
              :relation-bindings
              relation-bindings))]
      (let [field [:relation-bindings index]]
        (exact-keys!
         operation field binding #{:eid :relation})
        (require-value!
         operation [field :eid] safe-natural? (:eid binding))
        (validate-plan-relation!
         [field :relation]
         (:relation binding))))
    (doseq [[index rule]
            (map-indexed
             vector
             (bounded-vector!
              operation
              :indexed-rules
              indexed-rules))]
      (validate-indexed-rule!
       operation [:indexed-rules index] rule))
    input))

(defn- validate-indexed-seed-input!
  [input]
  (let [operation :indexed-seed-certification
        {:keys [indexed-rules seed-rules subject-type]}
        (exact-keys!
         operation
         :input
         input
         #{:indexed-rules :seed-rules :subject-type})]
    (doseq [[index rule]
            (map-indexed
             vector
             (bounded-vector!
              operation
              :indexed-rules
              indexed-rules))]
      (validate-indexed-rule!
       operation [:indexed-rules index] rule))
    (doseq [[index rule]
            (map-indexed
             vector
             (bounded-vector! operation :seed-rules seed-rules))]
      (validate-indexed-rule!
       operation [:seed-rules index] rule))
    (require-value!
     operation :subject-type nonempty-string? subject-type)
    input))

(def ^:private indexed-directions #{:forward :reverse})

(def ^:private indexed-limit-kinds
  #{:derived-grants :advanced-datoms :queued-work})

(def ^:private indexed-counter-keys
  #{:backend-commands
    :adapter-fetched-values
    :engine-consumed-values
    :cumulative-enqueues
    :current-queue-depth
    :maximum-queue-depth
    :unique-grants
    :emitted-results
    :rule-applications
    :consumer-grant-joins
    :render-advances})

(defn- validate-indexed-limits!
  [operation limits]
  (exact-keys!
   operation
   :limits
   limits
   #{:max-derived-grants :max-advanced-datoms :max-queued-work})
  (doseq [field [:max-derived-grants
                 :max-advanced-datoms
                 :max-queued-work]]
    (require-value!
     operation
     [:limits field]
     safe-natural?
     (get limits field)))
  limits)

(defn- validate-indexed-bound!
  [operation field bound]
  (when (some? bound)
    (exact-keys! operation field bound #{:ordinal :eid})
    (require-value!
     operation [field :ordinal] safe-natural? (:ordinal bound))
    (require-value!
     operation [field :eid] safe-natural? (:eid bound)))
  bound)

(defn- validate-indexed-render!
  [operation render]
  (let [kind (:kind render)
        expected
        (case kind
          :page #{:kind :size :bound}
          :backward-page #{:kind :size :bound}
          :count #{:kind :limit}
          :all-count #{:kind}
          :boolean #{:kind :target-eid}
          nil)]
    (when-not expected
      (boundary-error!
       "Generated indexed traversal has an unknown render variant."
       {:operation operation
        :field :render
        :kind kind}))
    (exact-keys! operation :render render expected)
    (case kind
      (:page :backward-page)
      (do
        (require-value!
         operation [:render :size]
         #(and (safe-natural? %) (pos? %))
         (:size render))
        (validate-indexed-bound!
         operation [:render :bound] (:bound render))
        (when (and (= :backward-page kind)
                   (nil? (:bound render)))
          (boundary-error!
           "Backward indexed rendering requires an exact cursor bound."
           {:operation operation
            :field [:render :bound]})))

      :count
      (require-value!
       operation [:render :limit] safe-natural? (:limit render))

      :all-count
      nil

      :boolean
      (require-value!
       operation [:render :target-eid]
       safe-natural?
       (:target-eid render)))
    render))

(defn- validate-indexed-compile-input!
  [input]
  (let [operation :indexed-traversal-compile
        {:keys [indexed-rules seed-rules-by-subject-type]}
        (exact-keys!
         operation
         :input
         input
         #{:indexed-rules :seed-rules-by-subject-type})]
    (doseq [[index rule]
            (map-indexed
             vector
             (bounded-vector!
              operation :indexed-rules indexed-rules))]
      (validate-indexed-rule!
       operation [:indexed-rules index] rule))
    (require-value!
     operation
     :seed-rules-by-subject-type
     #(and (map? %)
           (<= (count %) maximum-boundary-items))
     seed-rules-by-subject-type)
    (doseq [[subject-type rules] seed-rules-by-subject-type]
      (require-value!
       operation
       [:seed-rules-by-subject-type :key]
       nonempty-string?
       subject-type)
      (doseq [[index rule]
              (map-indexed
               vector
               (bounded-vector!
                operation
                [:seed-rules-by-subject-type subject-type]
                rules))]
        (validate-indexed-rule!
         operation
         [:seed-rules-by-subject-type subject-type index]
         rule)))
    input))

(defn- validate-indexed-initialization!
  [direction input]
  (let [operation :indexed-traversal-initialize
        common
        #{:compiled-plan :request-scope :subject-type :root-node :result-type
          :render :chunk-size :limits}
        expected
        (case direction
          :forward
          (conj common :subject-eid)

          :reverse
          (conj common :root-resource-eid))]
    (require-value!
     operation :direction indexed-directions direction)
    (exact-keys! operation :input input expected)
    (validate-indexed-state!
     operation (:compiled-plan input))
    (require-value!
     operation :request-scope safe-natural? (:request-scope input))
    (doseq [field [:subject-type :result-type]]
      (require-value!
       operation field nonempty-string? (get input field)))
    (validate-indexed-node!
     operation :root-node (:root-node input))
    (when (= :forward direction)
      (require-value!
       operation :subject-eid safe-natural? (:subject-eid input)))
    (when (= :reverse direction)
      (require-value!
       operation
       :root-resource-eid
       safe-natural?
       (:root-resource-eid input)))
    (require-value!
     operation
     :chunk-size
     #(and (safe-natural? %) (pos? %))
     (:chunk-size input))
    (validate-indexed-render! operation (:render input))
    (validate-indexed-limits! operation (:limits input))
    input))

(defn- validate-indexed-response!
  [response]
  (let [operation :indexed-traversal-resume]
    (exact-keys!
     operation
     :response
     response
     #{:request-scope :request-id :values :terminal? :fetched-values})
    (require-value!
     operation :request-scope safe-natural? (:request-scope response))
    (require-value!
     operation :request-id safe-natural? (:request-id response))
    (doseq [[index value]
            (map-indexed
             vector
             (bounded-vector!
              operation :values (:values response)))]
      (require-value!
       operation [:values index] safe-natural? value))
    (require-value!
     operation :terminal? boolean? (:terminal? response))
    (require-value!
     operation
     :fetched-values safe-natural? (:fetched-values response))
    response))

(defn- validate-indexed-state!
  [operation state]
  ;; State deliberately remains an opaque generated-runtime value between
  ;; transitions. Serializing its queue, maps, and seen sets through portable
  ;; host data per edge would make the proof boundary linear in retained state
  ;; and can turn a traversal quadratic. Portable values are validated at
  ;; initialization, scan, result, counter, and typed-error boundaries.
  (require-value! operation :state some? state))

(defn- validate-indexed-init-result!
  [result]
  (let [operation :indexed-traversal-initialize]
    (case (:status result)
      :initialized
      (do
        (exact-keys!
         operation :result result #{:status :state})
        (validate-indexed-state! operation (:state result)))

      :limit-exceeded
      (do
        (exact-keys!
         operation :result result #{:status :limit-kind})
        (require-value!
         operation
         :limit-kind
         indexed-limit-kinds
         (:limit-kind result)))

      (boundary-error!
       "Generated indexed initialization returned an unknown variant."
       {:operation operation
        :status (:status result)}))
    result))

(defn- validate-indexed-drive-result!
  [result]
  (let [operation :indexed-traversal-drive
        status (:status result)]
    (case status
      :need-scan
      (do
        (exact-keys!
         operation :result result #{:status :state :command})
        (validate-indexed-state! operation (:state result))
        (validate-indexed-scan-command!
         operation :command (:command result)))

      (:complete :yielded)
      (do
        (exact-keys!
         operation :result result #{:status :state})
        (validate-indexed-state! operation (:state result)))

      :limit-exceeded
      (do
        (exact-keys!
         operation :result result
         #{:status :state :limit-kind})
        (validate-indexed-state! operation (:state result))
        (require-value!
         operation
         :limit-kind
         indexed-limit-kinds
         (:limit-kind result)))

      :render-rejected
      (do
        (exact-keys!
         operation :result result
         #{:status :state :error})
        (validate-indexed-state! operation (:state result))
        (let [error (:error result)]
          (case (:reason error)
            :cursor-skipped
            (do
              (exact-keys!
               operation :error error
               #{:reason :expected-ordinal :actual-ordinal})
              (doseq [field [:expected-ordinal :actual-ordinal]]
                (require-value!
                 operation
                 [:error field]
                 safe-natural?
                 (get error field))))

            :cursor-result-mismatch
            (do
              (exact-keys!
               operation :error error
               #{:reason :ordinal :expected-eid :actual-eid})
              (doseq [field [:ordinal :expected-eid :actual-eid]]
                (require-value!
                 operation
                 [:error field]
                 safe-natural?
                 (get error field))))

            (boundary-error!
             "Generated indexed rendering returned an unknown error."
             {:operation operation
              :reason (:reason error)}))))

      (boundary-error!
       "Generated indexed drive returned an unknown variant."
       {:operation operation
        :status status}))
    result))

(defn- validate-indexed-resume-result!
  [result]
  (let [operation :indexed-traversal-resume]
    (case (:status result)
      :resumed
      (do
        (exact-keys!
         operation :result result #{:status :state})
        (validate-indexed-state! operation (:state result)))

      :scan-rejected
      (do
        (exact-keys!
         operation :result result #{:status :reason})
        (require-value!
         operation
         :reason
         indexed-scan-rejection-reasons
         (:reason result)))

      :limit-exceeded
      (do
        (exact-keys!
         operation :result result
         #{:status :state :limit-kind})
        (validate-indexed-state! operation (:state result))
        (require-value!
         operation
         :limit-kind
         indexed-limit-kinds
         (:limit-kind result)))

      (boundary-error!
       "Generated indexed resume returned an unknown variant."
       {:operation operation
        :status (:status result)}))
    result))

(defn- validate-indexed-counters!
  [counters]
  (let [operation :indexed-traversal-result]
    (exact-keys!
     operation :counters counters indexed-counter-keys)
    (doseq [[field value] counters]
      (require-value!
       operation [:counters field] safe-natural? value))
    (when (> (:current-queue-depth counters)
             (:maximum-queue-depth counters))
      (boundary-error!
       "Generated indexed counters report current depth above maximum depth."
       {:operation operation
        :counters counters}))
    counters))

(defn- validate-indexed-public-result!
  [result]
  (let [operation :indexed-traversal-result
        common #{:status :counters :retained-logical-units}
        expected
        (case (:status result)
          :page
          (into common
                #{:items :start-ordinal :has-next? :has-previous?})
          :count (into common #{:count :truncated?})
          :boolean (conj common :allowed?)
          nil)]
    (when-not expected
      (boundary-error!
       "Generated indexed traversal returned an unknown public result."
       {:operation operation
        :status (:status result)}))
    (exact-keys! operation :result result expected)
    (validate-indexed-counters! (:counters result))
    (require-value!
     operation
     :retained-logical-units
     safe-natural?
     (:retained-logical-units result))
    (case (:status result)
      :page
      (do
        (doseq [[index eid]
                (map-indexed
                 vector
                 (bounded-vector!
                  operation :items (:items result)))]
          (require-value!
           operation [:items index] safe-natural? eid))
        (require-value!
         operation
         :start-ordinal
         safe-natural?
         (:start-ordinal result))
        (doseq [field [:has-next? :has-previous?]]
          (require-value!
           operation field boolean? (get result field))))

      :count
      (do
        (require-value!
         operation :count safe-natural? (:count result))
        (require-value!
         operation :truncated? boolean? (:truncated? result)))

      :boolean
      (require-value!
       operation :allowed? boolean? (:allowed? result)))
    result))

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
  ([field value]
   (bounded-vector! :authorization-evaluation field value))
  ([operation field value]
   (require-value!
    operation
    field
    #(and (vector? %)
          (<= (count %) maximum-boundary-items))
    value)))

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
    :relationship-keyset-page (validate-keyset-page-input! input)
    :cursor-continuation (validate-continuation-input! input)
    :cache-validation (validate-cache-input! input)
    :current-cache-decision (validate-current-cache-input! input)
    :subproblem-cache-decision
    (validate-subproblem-cache-input! input)
    :ordered-merge-step (validate-ordered-merge-input! input)
    :ordered-merge-chunk (validate-ordered-merge-chunk-input! input)
    :indexed-scan-response (validate-indexed-scan-input! input)
    :indexed-plan-certification (validate-indexed-plan-input! input)
    :indexed-seed-certification (validate-indexed-seed-input! input)
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

(defn- validate-keyset-page-result!
  [result]
  (let [operation :relationship-keyset-page]
    (exact-keys!
     operation
     :result
     result
     #{:take-count :reverse? :has-next? :has-previous?})
    (require-value!
     operation :take-count safe-natural? (:take-count result))
    (doseq [field [:reverse? :has-next? :has-previous?]]
      (require-value! operation field boolean? (get result field)))
    result))

(def continuation-decisions
  #{:current
    :rebase-current
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

(def subproblem-cache-actions
  #{:bypass-recursive-self
    :start-computation
    :join-computation
    :use-completed-value
    :join-existing
    :admit-computation
    :compute-without-admission
    :retain-publication
    :drop-publication})

(defn- validate-subproblem-cache-result!
  [result]
  (require-value!
   :subproblem-cache-decision
   :result
   subproblem-cache-actions
   result))

(defn- expected-subproblem-cache-action
  [{:keys [decision] :as input}]
  (case decision
    :lookup
    (cond
      (:recursive-self? input) :bypass-recursive-self
      (= :missing (:candidate input)) :start-computation
      (= :computing (:candidate input)) :join-computation
      (= :complete (:candidate input)) :use-completed-value
      :else :start-computation)

    :admission
    (cond
      (:candidate-present? input) :join-existing
      (< (:represented-candidates input)
         (:maximum-candidates input)) :admit-computation
      :else :compute-without-admission)

    :publication
    (if (and (:ticket-current? input)
             (:complete? input)
             (:valid? input)
             (pos? (:weight input))
             (<= (:weight input) (:budget input)))
      :retain-publication
      :drop-publication)))

(defn- validate-current-cache-result!
  [result]
  (require-value!
   :current-cache-decision
   :result
   current-cache-actions
   result))

(defn- validate-ordered-merge-result!
  [result]
  (require-value!
   :ordered-merge-step
   :result
   ordered-merge-steps
   result))

(defn- validate-ordered-merge-chunk-result!
  [result]
  (let [operation :ordered-merge-chunk]
    (exact-keys!
     operation
     :result
     result
     #{:values :left-consumed :right-consumed})
    (let [values
          (bounded-vector! operation [:result :values] (:values result))]
      (doseq [[index value] (map-indexed vector values)]
        (require-value!
         operation [:result :values index] safe-natural? value)))
    (doseq [field [:left-consumed :right-consumed]]
      (require-value!
       operation [:result field] safe-natural? (get result field)))
    result))

(defn- expected-current-cache-action
  [{:keys [stage available?]}]
  (case stage
    (:eligibility :generation)
    (if available?
      :probe-exact-entry
      :bypass-current-cache)

    :exact-entry
    (if available?
      :use-exact-entry
      :probe-managed-entry)

    :managed-entry
    (if available?
      :use-managed-entry
      :compute-current-value)))

(def indexed-scan-rejection-reasons
  #{:invalid-command
    :mismatched-request-scope
    :mismatched-request
    :oversized-chunk
    :invalid-fetched-count
    :non-progressing-response
    :invalid-eid
    :out-of-order
    :bound-violation})

(defn- validate-indexed-scan-result!
  [result]
  (let [operation :indexed-scan-response]
    (when-not (map? result)
      (boundary-error!
       "Generated indexed scan result must be a map."
       {:operation operation :result result}))
    (case (:status result)
      :accepted
      (do
        (exact-keys!
         operation
         :result
         result
         #{:status :values :terminal? :fetched-values})
        (bounded-vector!
         operation
         :values
         (:values result))
        (doseq [[index value] (map-indexed vector (:values result))]
          (require-value!
           operation [:values index] safe-natural? value))
        (require-value!
         operation :terminal? boolean? (:terminal? result))
        (require-value!
         operation :fetched-values safe-natural?
         (:fetched-values result))
        result)

      :rejected
      (do
        (exact-keys!
         operation :result result #{:status :reason})
        (require-value!
         operation
         :reason
         indexed-scan-rejection-reasons
         (:reason result))
        result)

      (boundary-error!
       "Generated indexed scan result has an unknown variant."
       {:operation operation :result result}))))

(def indexed-plan-rejection-reasons
  #{:invalid-relation-catalog
    :invalid-indexed-rule
    :duplicate-indexed-rule
    :permission-open-rule
    :compiled-rule-mismatch
    :invalid-seed-rule
    :duplicate-seed-rule
    :seed-bucket-mismatch})

(defn- validate-indexed-plan-result!
  [operation result]
  (when-not (map? result)
    (boundary-error!
     "Generated indexed plan certification result must be a map."
     {:operation operation :result result}))
  (case (:status result)
    :certified
    (exact-keys! operation :result result #{:status})

    :rejected
    (do
      (exact-keys!
       operation :result result #{:status :reason})
      (require-value!
       operation
       :reason
       indexed-plan-rejection-reasons
       (:reason result))
      result)

    (boundary-error!
     "Generated indexed plan certification has an unknown variant."
     {:operation operation :result result})))

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
    :relationship-keyset-page (validate-keyset-page-result! result)
    :cursor-continuation (validate-continuation-result! result)
    :cache-validation (validate-cache-result! result)
    :current-cache-decision
    (validate-current-cache-result! result)
    :subproblem-cache-decision
    (validate-subproblem-cache-result! result)
    :ordered-merge-step (validate-ordered-merge-result! result)
    :ordered-merge-chunk (validate-ordered-merge-chunk-result! result)
    :indexed-scan-response (validate-indexed-scan-result! result)
    :indexed-plan-certification
    (validate-indexed-plan-result! operation result)
    :indexed-seed-certification
    (validate-indexed-plan-result! operation result)
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
      (when (and (= :relationship-keyset-page operation)
                 (> (:take-count result) (:size input)))
        (boundary-error!
         "Generated keyset page exceeds its requested size."
         {:operation operation
          :size (:size input)
          :take-count (:take-count result)}))
      (when (and (= :current-cache-decision operation)
                 (not= result
                       (expected-current-cache-action input)))
        (boundary-error!
         "Generated current-cache action contradicts its validated stage."
         {:operation operation
          :stage (:stage input)
          :available? (:available? input)
          :result result}))
      (when (and (= :subproblem-cache-decision operation)
                 (not= result
                       (expected-subproblem-cache-action input)))
        (boundary-error!
         "Generated subproblem-cache action contradicts its validated state."
         {:operation operation
          :decision (:decision input)
          :result result}))
      (when (= :ordered-merge-chunk operation)
        (let [left-consumed (:left-consumed result)
              right-consumed (:right-consumed result)
              left (:left input)
              right (:right input)
              expected-values
              (->> (concat
                    (take left-consumed left)
                    (take right-consumed right))
                   distinct
                   (sort
                    (case (:direction input)
                      :asc <
                      :desc >))
                   vec)]
          (when (or (> left-consumed (count left))
                    (> right-consumed (count right))
                    (and (or (empty? left) (empty? right))
                         (or (pos? left-consumed)
                             (pos? right-consumed)))
                    (and (seq left)
                         (seq right)
                         (not
                          (or (= left-consumed (count left))
                              (= right-consumed (count right)))))
                    (not= expected-values (:values result)))
            (boundary-error!
             "Generated merge chunk contradicts its validated input."
             {:operation operation
              :direction (:direction input)
              :left-count (count left)
              :right-count (count right)
              :left-consumed left-consumed
              :right-consumed right-consumed
              :result-count (count (:values result))}))))
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

(defn- indexed-kernel
  [selection operation]
  (let [{:keys [mode kernel]} (normalize-selection selection)]
    (when (= :legacy-authoritative mode)
      (boundary-error!
       "Legacy-authoritative mode has no generated indexed traversal."
       {:operation operation
        :mode mode}))
    (when-not (indexed-traversal-kernel? kernel)
      (boundary-error!
       "The selected generated kernel has no indexed traversal implementation."
       {:operation operation
        :mode mode
        :kernel-type (str (type kernel))}))
    kernel))

(defn- invoke-indexed-kernel
  [operation invoke validate-result]
  (try
    (validate-result (invoke))
    (catch #?(:clj Exception :cljs :default) error
      (if (= :eacl.verification/invalid-boundary
             (:type (ex-data error)))
        (throw error)
        (throw
         (ex-info
          "Generated indexed traversal kernel failed closed."
          {:type :eacl.verification/kernel-failure
           :eacl/error :eacl.verification/kernel-failure
           :operation operation}
          error))))))

(defn initialize-indexed
  "Initializes an opaque generated indexed traversal.

  The returned state must be passed only to the same generated runtime. EACL
  intentionally does not serialize it between scan calls: copying retained
  queue/seen/consumer state per edge would make the proof boundary linear in
  retained state and can make a traversal quadratic."
  [selection direction input]
  (validate-indexed-initialization! direction input)
  (let [kernel
        (indexed-kernel selection :indexed-traversal-initialize)]
    (invoke-indexed-kernel
     :indexed-traversal-initialize
     #(-initialize-indexed kernel direction input)
     validate-indexed-init-result!)))

(defn compile-indexed-plan
  "Compiles certified portable rules once for reuse by request-local states."
  [selection input]
  (let [operation :indexed-traversal-compile]
    (validate-indexed-compile-input! input)
    (let [kernel (indexed-kernel selection operation)]
      (invoke-indexed-kernel
       operation
       #(-compile-indexed-plan kernel input)
       #(validate-indexed-state! operation %)))))

(defn drive-indexed
  "Drives opaque generated state for at most `fuel` internal transitions."
  [selection direction state limits fuel]
  (let [operation :indexed-traversal-drive]
    (require-value! operation :direction indexed-directions direction)
    (validate-indexed-state! operation state)
    (validate-indexed-limits! operation limits)
    (require-value!
     operation :fuel
     #(and (safe-natural? %) (pos? %))
     fuel)
    (let [kernel (indexed-kernel selection operation)]
      (invoke-indexed-kernel
       operation
       #(-drive-indexed kernel direction state limits fuel)
       validate-indexed-drive-result!))))

(defn resume-indexed
  "Resumes opaque generated state with one portable ordered scan response."
  [selection direction state response limits]
  (let [operation :indexed-traversal-resume]
    (require-value! operation :direction indexed-directions direction)
    (validate-indexed-state! operation state)
    (validate-indexed-response! response)
    (validate-indexed-limits! operation limits)
    (let [kernel (indexed-kernel selection operation)]
      (invoke-indexed-kernel
       operation
       #(-resume-indexed kernel direction state response limits)
       validate-indexed-resume-result!))))

(defn read-indexed-result
  "Reads one completed portable result without serializing opaque state."
  [selection direction state]
  (let [operation :indexed-traversal-result]
    (require-value! operation :direction indexed-directions direction)
    (validate-indexed-state! operation state)
    (let [kernel (indexed-kernel selection operation)]
      (invoke-indexed-kernel
       operation
       #(-read-indexed-result kernel direction state)
       validate-indexed-public-result!))))

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

(defn compare-shadow!
  "Compares an already-authoritative legacy result with a lazily computed
  generated result when `selection` is `:verified-shadow`.

  This is the orchestration counterpart of `decide`: it is for stateful
  adapter-driven algorithms whose generated implementation cannot be invoked
  through the pure `DecisionKernel` operation table. The reporter receives
  only result shape and changed field names. Generated failures and
  disagreements can never alter or disclose the legacy authorization result."
  [selection operation legacy verified-result]
  (when-not (fn? verified-result)
    (boundary-error!
     "Shadow result computation must be a function."
     {:operation operation}))
  (let [{:keys [mode report-divergence]}
        (normalize-selection selection)]
    (when (= :verified-shadow mode)
      (try
        (let [verified (verified-result)]
          (when-not (= (secure/canonicalize legacy)
                       (secure/canonicalize verified))
            (report!
             report-divergence
             {:type :eacl.verification/shadow-divergence
              :operation operation
              :changed-fields
              (changed-result-fields legacy verified)
              :legacy-variant (result-variant legacy)
              :verified-variant (result-variant verified)})))
        (catch #?(:clj Exception :cljs :default) error
          (report!
           report-divergence
           {:type :eacl.verification/shadow-kernel-failure
            :operation operation
            :error-type (:type (ex-data error))}))))
    legacy))
