(ns eacl.permission-tree
  "Portable, snapshot-bound shallow permission-tree expansion."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.core :as eacl]
            [eacl.execution :as execution]
            [eacl.exact-integer :as exact-integer]
            [eacl.schema.expression-persistence :as expression-persistence]))

(def default-limits
  {:max-depth 50
   :max-schema-components 100000
   :max-relationship-values 100000
   :max-tree-nodes 100000
   :max-leaf-subjects 100000})

(def ^:private query-keys
  #{:resource :permission :consistency :timeout-ms
    :cancellation-token :cache? :populate-cache?})

(defn normalize-limits
  [overrides]
  (let [overrides (if (nil? overrides) {} overrides)
        known (set (keys default-limits))]
    (when-not (map? overrides)
      (throw
       (ex-info
        "EACL Config Error: :permission-tree-limits must be a map."
        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
         :key :permission-tree-limits
         :value overrides})))
    (when-let [unknown (seq (remove known (keys overrides)))]
      (throw
       (ex-info
        "EACL Config Error: unknown permission-tree limit."
        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
         :key :permission-tree-limits
         :unknown-keys (vec unknown)
         :known-keys known})))
    (when-not (every? exact-integer/positive? (vals overrides))
      (throw
       (ex-info
        "EACL Config Error: permission-tree limits must be positive portable exact integers."
        {:type :eacl/invalid-config :eacl/error :eacl/invalid-config
         :key :permission-tree-limits
         :value overrides
         :maximum backend/maximum-exact-integer})))
    (merge default-limits overrides)))

(defn- unqualified-keyword?
  [value]
  (and (keyword? value) (nil? (namespace value))))

(defn validate-request!
  [query]
  (when-not (map? query)
    (throw
     (ex-info
      "expand-permission-tree requires a request map."
      {:type :eacl.permission-tree/invalid-request
       :eacl/error :eacl.permission-tree/invalid-request})))
  (when-let [unknown (seq (remove query-keys (keys query)))]
    (throw
     (ex-info
      "expand-permission-tree received unknown request keys."
      {:type :eacl.permission-tree/invalid-request
       :eacl/error :eacl.permission-tree/invalid-request
       :unknown-keys (vec unknown)
       :known-keys query-keys})))
  (doseq [required [:resource :permission]]
    (when-not (contains? query required)
      (throw
       (ex-info
        "expand-permission-tree is missing a required request key."
        {:type :eacl.permission-tree/invalid-request
         :eacl/error :eacl.permission-tree/invalid-request
         :missing-key required}))))
  (let [{:keys [resource permission]} query]
    (when-not (and (associative? resource)
                   (unqualified-keyword? (:type resource))
                   (some? (:id resource)))
      (throw
       (ex-info
        "expand-permission-tree requires a resource with an unqualified keyword :type and non-nil :id."
        {:type :eacl.permission-tree/invalid-request
         :eacl/error :eacl.permission-tree/invalid-request
         :key :resource})))
    (when (some? (:relation resource))
      (throw
       (ex-info
        "expand-permission-tree resources cannot carry a subject relation."
        {:type :eacl.permission-tree/invalid-request
         :eacl/error :eacl.permission-tree/invalid-request
         :key :resource/relation})))
    (when-not (unqualified-keyword? permission)
      (throw
       (ex-info
        "expand-permission-tree requires an unqualified keyword permission."
        {:type :eacl.permission-tree/invalid-request
         :eacl/error :eacl.permission-tree/invalid-request
         :key :permission}))))
  query)

(defn- permission-tree-error!
  [type message data]
  (throw
   (ex-info
    message
    (merge {:type type :eacl/error type
            :operation :expand-permission-tree}
           data))))

(defn- adapter-contract!
  [reason]
  (permission-tree-error!
   :eacl.permission-tree/adapter-contract-violation
   "The selected backend violated the permission-tree adapter contract."
   {:reason reason}))

(defn- adapter-call!
  [f]
  (let [controlled-error (volatile! nil)
        controlled!
        (fn [thunk]
          (try
            (thunk)
            (catch #?(:clj Throwable :cljs :default) error
              (vreset! controlled-error error)
              (throw error))))]
    (try
      (f controlled!)
      (catch #?(:clj Throwable :cljs :default) error
        (if (identical? error @controlled-error)
          (throw error)
          ;; Do not retain the adapter error as a cause: its message or data
          ;; may contain an internal eid or a relationship value. Namespace
          ;; lookalikes are not trusted; only an exact error caught from one
          ;; of the guarded kernel callbacks can cross this boundary.
          (adapter-contract! :adapter-operation-failed))))))

(defn selected-basis-token
  "Issues a token from the selected basis through the redacting boundary."
  [opts]
  (if-let [basis-identity (:snapshot-semantic-identity opts)]
    (adapter-call!
     (fn [_]
       (consistency/selected-basis-token basis-identity opts)))
    (adapter-contract! :missing-basis-identity)))

(def ^:private dimension->limit
  {:depth :max-depth
   :schema-components :max-schema-components
   :relationship-values :max-relationship-values
   :tree-nodes :max-tree-nodes
   :leaf-subjects :max-leaf-subjects})

(defn- safe-counters
  [counters]
  (select-keys counters
               [:schema-components :relationship-values
                :tree-nodes :leaf-subjects]))

(defn- consume!
  [limits counters dimension amount]
  (let [limit-key (get dimension->limit dimension)
        current (get @counters dimension 0)
        configured (get limits limit-key)]
    (if (> amount (- configured current))
      (permission-tree-error!
       :eacl.permission-tree/limit-exceeded
       "Permission-tree structural work limit exceeded."
       {:dimension limit-key
        :limit configured
        :attempted-work amount
        ;; Counters report only work actually accepted by the envelope. This
        ;; avoids constructing max-exact-integer + 1 in ClojureScript.
        :consumed-work (safe-counters @counters)})
      (vswap! counters update dimension + amount))))

(defn- check-depth!
  [limits counters depth]
  (when (> depth (:max-depth limits))
    (permission-tree-error!
     :eacl.permission-tree/limit-exceeded
     "Permission-tree maximum depth exceeded."
     {:dimension :max-depth
      :limit (:max-depth limits)
      :consumed-work (assoc (safe-counters @counters) :depth depth)})))

(defn- definition-sequence!
  [kind resource-type name value]
  (when-not (and (sequential? value) (every? map? value))
    (adapter-contract! :invalid-definition-sequence))
  (let [definitions (vec value)]
    (case kind
      :relation
      (do
        (when-not
         (every?
          (fn [definition]
            (and (exact-integer/natural? (:relation-id definition))
                 (= resource-type (:resource-type definition))
                 (= name (:relation-name definition))
                 (unqualified-keyword? (:subject-type definition))))
          definitions)
          (adapter-contract! :malformed-relation-definition))
        (when-not (= (count definitions)
                     (count (distinct (map :subject-type definitions))))
          (adapter-contract! :duplicate-relation-subject-type)))

      :permission
      (when-not
       (every?
        (fn [definition]
          (and (exact-integer/natural? (:permission-id definition))
               (= resource-type (:resource-type definition))
               (= name (:permission-name definition))
               (unqualified-keyword?
                (:source-relation-name definition))
               (contains? #{:relation :permission}
                          (:target-type definition))
               (unqualified-keyword? (:target-name definition))))
        definitions)
        (adapter-contract! :malformed-permission-definition))

      (adapter-contract! :unknown-definition-kind))
    definitions))

(def ^:private memo-miss
  ;; Identity-safe miss sentinel (CLJS does not intern keyword literals).
  #?(:clj (Object.) :cljs (js/Object.)))

(defn- schedule
  [work assembler child-frames]
  ;; rseq is O(1) on the vectors every caller passes; (vec v) on a vector
  ;; is identity, so non-vector inputs pay one materialization at most.
  (into (conj work assembler) (rseq (vec child-frames))))

(defn- assemble-children
  [values child-count]
  (let [start (- (count values) child-count)]
    [(subvec values 0 start)
     (subvec values start)]))

(defn- operator-expression?
  [root]
  (loop [pending [root]]
    (if-let [node (peek pending)]
      (case (:op node)
        (:intersection :exclusion) true
        :union (recur (into (pop pending) (:children node)))
        (recur (pop pending)))
      false)))

(defn expand
  "Expands one root against exactly one immutable selected adapter.

  Returns a fully externalized tree. Failures throw before any tree is
  returned; no lazy sequence or internal backend identity escapes."
  [adapter {:keys [limits execution-contract]} resource permission]
  (let [limits (normalize-limits limits)
        ;; Request-local single-threaded state: volatiles, not atoms.
        counters (volatile! {:schema-components 0
                             :relationship-values 0
                             :tree-nodes 0
                             :leaf-subjects 0})
        schema-cache (volatile! {})
        expression-cache (volatile! {})
        codec-cache (volatile! {})
        check! (fn [stage]
                 (execution/check!
                  execution-contract stage #(safe-counters @counters)))
        definitions!
        (fn [kind resource-type name]
          (let [cache-key [kind resource-type name]
                hit (get @schema-cache cache-key memo-miss)]
            (if-not (identical? memo-miss hit)
              hit
              (let [operation (if (= :relation kind)
                                :relation-defs
                                :permission-defs)
                    raw
                    (adapter-call!
                     (fn [controlled!]
                       (backend/reduce-definitions
                        adapter operation [resource-type name] []
                        {:before-realize!
                         #(controlled!
                           (fn []
                             (check! :permission-tree-definition-read)))
                         :after-realize!
                         #(controlled!
                           (fn []
                             (check! :permission-tree-definition-read)))
                         :step
                         (fn [definitions definition]
                           (controlled!
                            (fn []
                              (consume! limits counters
                                        :schema-components 1)
                              (conj definitions definition))))})))
                    definitions
                    (definition-sequence!
                      kind resource-type name raw)
                    _ (check! :permission-tree-definition-read)]
                (vswap! schema-cache assoc cache-key definitions)
                definitions))))
        expression!
        (fn [resource-type permission-name]
          (let [cache-key [resource-type permission-name]
                hit (get @expression-cache cache-key memo-miss)]
            (if-not (identical? memo-miss hit)
              hit
              (let [entity
                    (adapter-call!
                     (fn [_]
                       (backend/invoke adapter :permission-expression
                                       resource-type permission-name)))
                    resolved
                    (when entity
                      (expression-persistence/decode-entity entity))]
                (when (and resolved
                           (not= cache-key
                                 [(:resource-type resolved)
                                  (:permission-name resolved)]))
                  (adapter-contract! :expression-identity-mismatch))
                (vswap! expression-cache assoc cache-key resolved)
                resolved))))
        render-internal!
        (fn [type internal-id]
          (when-not (exact-integer/natural? internal-id)
            (adapter-contract! :invalid-internal-identity))
          (let [cache-key [type internal-id]
                hit (get @codec-cache cache-key memo-miss)]
            (if-not (identical? memo-miss hit)
              hit
              (do
                (check! :permission-tree-render)
                (let [external-id
                      (adapter-call!
                       (fn [_]
                         (backend/invoke
                          adapter :internal-id->object internal-id)))]
                  (check! :permission-tree-render)
                  (when (nil? external-id)
                    (permission-tree-error!
                     :eacl.permission-tree/codec-failure
                     "The selected backend could not externalize a scanned object."
                     {:reason :missing-external-identity}))
                  (let [rendered (eacl/spice-object type external-id)]
                    (vswap! codec-cache assoc cache-key rendered)
                    rendered))))))
        scan-relation!
        (fn [resource-descriptor relation-definitions leaf?]
          (if (nil? (:internal-id resource-descriptor))
            []
            (reduce
             (fn [subjects {:keys [relation-id subject-type]}]
               (adapter-call!
                (fn [controlled!]
                  (backend/reduce-scan
                   adapter
                   :resource->subjects
                   [(:type resource-descriptor)
                    (:internal-id resource-descriptor)
                    relation-id
                    subject-type
                    {:direction :asc
                     :bound-eid nil
                     :inclusive-bound? false}]
                   subjects
                   {:before-realize!
                    #(controlled!
                      (fn []
                        (check! :permission-tree-relationship-realization)))
                    :after-realize!
                    #(controlled!
                      (fn []
                        (check! :permission-tree-relationship-realization)))
                    :step
                    (fn [acc internal-id]
                      (controlled!
                       (fn []
                         (consume! limits counters
                                   :relationship-values 1)
                         (when leaf?
                           (consume! limits counters :leaf-subjects 1))
                         ;; A leaf renders straight to its public object;
                         ;; intermediates keep the typed descriptor.
                         (conj acc
                               (if leaf?
                                 (render-internal! subject-type internal-id)
                                 {:type subject-type
                                  :internal-id internal-id
                                  :identity
                                  [:internal subject-type internal-id]
                                  :public (render-internal!
                                           subject-type internal-id)})))))}))))
             []
             relation-definitions)))
        root-type (:type resource)
        root-id (:id resource)
        _ (check! :permission-tree-root-resolution)
        internal-root-id
        (adapter-call!
         (fn [_]
           (backend/invoke adapter :object-id->internal root-id)))
        _ (check! :permission-tree-root-resolution)
        _ (when (and (some? internal-root-id)
                     (not (exact-integer/natural? internal-root-id)))
            (adapter-contract! :invalid-root-internal-identity))
        root-descriptor
        {:type root-type
         :internal-id internal-root-id
         :identity (if (some? internal-root-id)
                     [:internal root-type internal-root-id]
                     [:external root-type root-id])
         :public (eacl/spice-object root-type root-id)}]
    (loop [work [{:op :expand
                  :resource root-descriptor
                  :name permission
                  :expected :either
                  :depth 1
                  :active #{}}]
           values []]
      (check! :permission-tree-work-transition)
      (if-not (seq work)
        (do
          (when-not (= 1 (count values))
            (adapter-contract! :invalid-final-value-stack))
          (first values))
        (let [frame (peek work)
              work (pop work)
              [next-work next-values]
              (case (:op frame)
                :expand
                (let [{:keys [resource name expected depth active]} frame
                      _ (check-depth! limits counters depth)
                      expansion-key [(:identity resource) name]
                      relations (definitions! :relation (:type resource) name)
                      expression (expression! (:type resource) name)
                      operator-expression
                      (when (and expression
                                 (operator-expression? (:root expression)))
                        expression)
                      permissions
                      (when-not operator-expression
                        (definitions! :permission (:type resource) name))
                      _ (when (and (seq relations)
                                   (or operator-expression
                                       (seq permissions)))
                          (adapter-contract! :contradictory-root-definition))
                      actual (cond
                               (seq relations) :relation
                               (or operator-expression
                                   (seq permissions)) :permission
                               :else nil)
                      _ (when-not actual
                          (if (= :either expected)
                            (permission-tree-error!
                             :eacl.permission-tree/unknown-root
                             "The requested relation or permission is not defined."
                             {:resource-type (:type resource)
                              :permission name})
                            (adapter-contract! :missing-referenced-definition)))
                      _ (when (and (not= :either expected)
                                   (not= expected actual))
                          (adapter-contract! :wrong-referenced-definition-kind))]
                  (if (= :relation actual)
                    (do
                      (consume! limits counters :tree-nodes 1)
                      (let [subjects (scan-relation! resource relations true)]
                        [work
                         (conj values
                               {:expanded-object (:public resource)
                                :expanded-relation name
                                :leaf {:subjects subjects}})]))
                    (do
                      (when (contains? active expansion-key)
                        (permission-tree-error!
                         :eacl.permission-tree/cycle-detected
                         "Permission-tree expansion encountered an active-path cycle."
                         {:path-node [(:type resource) name]}))
                      (consume! limits counters :tree-nodes 1)
                      (let [next-active (conj active expansion-key)]
                        (if operator-expression
                          [(conj work
                                 {:op :expression-node
                                  :resource resource
                                  :permission name
                                  :node (:root operator-expression)
                                  :depth (inc depth)
                                  :root? true
                                  :active next-active})
                           values]
                          (let [components
                                (mapv
                                 (fn [definition]
                                   {:op :component
                                    :resource resource
                                    :permission name
                                    :definition definition
                                    :depth (inc depth)
                                    :active next-active})
                                 permissions)]
                            [(schedule
                              work
                              {:op :assemble
                               :resource resource
                               :name name
                               :child-count (count components)}
                              components)
                             values]))))))

                :expression-node
                (let [{:keys [resource permission node depth root? active]}
                      frame
                      _ (check-depth! limits counters depth)
                      _ (consume! limits counters :schema-components 1)]
                  (case (:op node)
                    :relation
                    [(conj work
                           {:op :expand
                            :resource resource
                            :name (:name node)
                            :expected :relation
                            :depth depth
                            :active active})
                     values]

                    :permission
                    [(conj work
                           {:op :expand
                            :resource resource
                            :name (:name node)
                            :expected :permission
                            :depth depth
                            :active active})
                     values]

                    :arrow
                    (let [_ (consume! limits counters :tree-nodes 1)
                          source-relations
                          (definitions!
                            :relation (:type resource) (:relation node))
                          source-permissions
                          (definitions!
                            :permission (:type resource) (:relation node))
                          _ (when (or (empty? source-relations)
                                      (seq source-permissions))
                              (adapter-contract!
                               :invalid-arrow-source-definition))
                          partitions (into {}
                                           (map (juxt :subject-type identity))
                                           (:partitions node))
                          _ (when-not (= (set (keys partitions))
                                         (set (map :subject-type
                                                   source-relations)))
                              (adapter-contract!
                               :arrow-partition-mismatch))
                          intermediates
                          (scan-relation! resource source-relations false)
                          targets
                          (mapv
                           (fn [intermediate]
                             (let [{:keys [target-kind target-name]}
                                   (get partitions (:type intermediate))]
                               {:op :expand
                                :resource intermediate
                                :name target-name
                                :expected target-kind
                                :depth (inc depth)
                                :active active}))
                           intermediates)]
                      [(schedule
                        work
                        {:op :assemble-expression
                         :resource resource
                         :name permission
                         :operation :union
                         :child-count (count targets)}
                        targets)
                       values])

                    (:union :intersection)
                    (let [_ (when-not root?
                              (consume! limits counters :tree-nodes 1))
                          children
                          (mapv
                           (fn [child]
                             {:op :expression-node
                              :resource resource
                              :permission permission
                              :node child
                              :depth (inc depth)
                              :root? false
                              :active active})
                           (:children node))]
                      [(schedule
                        work
                        {:op :assemble-expression
                         :resource resource
                         :name permission
                         :operation (:op node)
                         :child-count (count children)}
                        children)
                       values])

                    :exclusion
                    (let [_ (when-not root?
                              (consume! limits counters :tree-nodes 1))
                          children
                          (mapv
                           (fn [child]
                             {:op :expression-node
                              :resource resource
                              :permission permission
                              :node child
                              :depth (inc depth)
                              :root? false
                              :active active})
                           [(:left node) (:right node)])]
                      [(schedule
                        work
                        {:op :assemble-expression
                         :resource resource
                         :name permission
                         :operation :exclusion
                         :child-count 2}
                        children)
                       values])

                    (adapter-contract! :unknown-expression-node)))

                :component
                (let [{:keys [resource permission definition depth active]}
                      frame
                      _ (check-depth! limits counters depth)
                      source (:source-relation-name definition)
                      target-kind (:target-type definition)
                      target-name (:target-name definition)]
                  (if (= :self source)
                    [(conj work
                           {:op :expand
                            :resource resource
                            :name target-name
                            :expected target-kind
                            :depth depth
                            :active active})
                     values]
                    (let [_ (consume! limits counters :tree-nodes 1)
                          source-relations
                          (definitions!
                            :relation (:type resource) source)
                          source-permissions
                          (definitions!
                            :permission (:type resource) source)
                          _ (when (or (empty? source-relations)
                                      (seq source-permissions))
                              (adapter-contract!
                               :invalid-arrow-source-definition))
                          intermediates
                          (scan-relation!
                           resource source-relations false)
                          targets
                          (mapv
                           (fn [intermediate]
                             {:op :expand
                              :resource intermediate
                              :name target-name
                              :expected target-kind
                              :depth (inc depth)
                              :active active})
                           intermediates)]
                      [(schedule
                        work
                        {:op :assemble
                         :resource resource
                         ;; SpiceDB annotates tuple-to-userset's outer set
                         ;; node with the original resource-and-permission.
                         :name permission
                         :child-count (count targets)}
                        targets)
                       values])))

                :assemble
                (let [[remaining children]
                      (assemble-children values (:child-count frame))]
                  [work
                   (conj remaining
                         {:expanded-object
                          (get-in frame [:resource :public])
                          :expanded-relation (:name frame)
                          :intermediate
                          {:operation :union
                           :children (vec children)}})])

                :assemble-expression
                (let [[remaining children]
                      (assemble-children values (:child-count frame))]
                  [work
                   (conj remaining
                         {:expanded-object
                          (get-in frame [:resource :public])
                          :expanded-relation (:name frame)
                          :intermediate
                          {:operation (:operation frame)
                           :children (vec children)}})])

                (adapter-contract! :unknown-work-frame))]
          ;; The loop-top check! covers the recur boundary; a second check
          ;; here doubled the per-transition deadline cost for the same
          ;; observable cut points.
          (recur next-work next-values))))))
