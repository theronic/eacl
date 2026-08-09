(ns eacl.engine.portable-decisions
  "Native-safe CLJC implementation of the verified kernel's pure decisions.

  Inputs and outputs still cross `eacl.verified-kernel`, which owns strict
  shape/range validation.  This namespace contains only deterministic value
  semantics and never performs backend I/O."
  (:require [clojure.set :as set]
            [eacl.verified-kernel :as verified]))

(defn- page-decision
  [{:keys [length request default-size maximum-size]}]
  (let [{:keys [first last after before]} request
        invalid
        (cond
          (and (not= :absent first) (not= :absent last)) :both-directions
          (and (not= :absent after) (not= :absent before)) :both-bounds
          (and (not= :absent after) (= :absent first)) :after-without-first
          (and (not= :absent before) (= :absent last)) :before-without-last
          (= :nil after) :nil-after
          (= :nil before) :nil-before)
        requested (cond
                    (number? first) first
                    (number? last) last
                    :else default-size)
        invalid (or invalid
                    (when (<= requested 0) :non-positive-size)
                    (when (or (zero? maximum-size)
                              (> requested maximum-size))
                      :oversized-page))]
    (if invalid
      {:status :invalid :reason invalid}
      (let [direction (if (= :absent last) :asc :desc)
            bound (if (= :asc direction) after before)
            bound (when (number? bound) bound)
            start (if (= :asc direction)
                    (if bound (min length (inc bound)) 0)
                    (let [end (if bound (min length bound) length)]
                      (max 0 (- end requested))))
            end (if (= :asc direction)
                  (min length (+ start requested))
                  (if bound (min length bound) length))]
        {:status :valid
         :direction direction
         :size requested
         :start start
         :end end
         :has-next? (< end length)
         :has-previous? (pos? start)}))))

(defn- keyset-page-decision
  [{:keys [direction size bound? realized-count]}]
  (let [take-count (min size realized-count)
        any? (pos? take-count)
        sentinel? (> realized-count size)]
    {:take-count take-count
     :reverse? (= :desc direction)
     :has-next? (and any? (if (= :asc direction) sentinel? bound?))
     :has-previous? (and any? (if (= :asc direction) bound? sentinel?))}))

(defn- continuation-decision
  [{:keys [authenticated? scope-matches? expired? source cursor-source
           current-proof cursor-proof cursor-graph exact]}]
  (cond
    (not authenticated?) :invalid-authentication
    (or (not scope-matches?) (not= source cursor-source)) :scope-mismatch
    expired? :expired
    (= current-proof cursor-proof) :current
    (nil? exact) :snapshot-unavailable
    (or (not= (:graph exact) cursor-graph)
        (not= (:source exact) cursor-source)
        (not= (:proof exact) cursor-proof)) :history-divergence
    :else :exact))

(defn- consistency-plan
  [{:keys [mode capability-supported? managed-authority?]}]
  (cond
    (not capability-supported?)
    (case mode
      :minimize-latency :unsupported-capability
      :at-exact-snapshot :exact-snapshot-unavailable
      :unsupported-head-barrier)
    (and (contains? #{:at-least-as-fresh :at-exact-snapshot} mode)
         (not managed-authority?))
    :unsupported-head-barrier
    :else
    (case mode
      :minimize-latency :select-current
      :fully-consistent :select-authoritative
      :at-least-as-fresh :authenticate-and-select-at-least
      :at-exact-snapshot :authenticate-and-select-exact)))

(defn- consistency-validation
  [{:keys [kind selection-present? selected-adapter?
           same-source-scope? anchor-satisfied?]}]
  (cond
    (not selection-present?)
    (if (= :exact kind) :exact-snapshot-unavailable :invalid-selected-adapter)
    (not selected-adapter?) :invalid-selected-adapter
    (not same-source-scope?) :incomparable-scope
    (and (contains? #{:at-least :exact} kind) (not anchor-satisfied?))
    :history-divergence
    :else :accept))

(defn- cache-decision
  [{:keys [deterministic? dependency-scope-nonempty? expected-key
           expected-source selected-graph ancestors selected-proof entry]}]
  (let [{:keys [status authenticated? key source graph proof]} entry]
    (cond
      (or (not deterministic?)
          (not dependency-scope-nonempty?)
          (nil? selected-proof))
      {:status :miss :reason :no-proof-bypass}
      (= :missing status) {:status :miss :reason :missing}
      (= :provider-failure status) {:status :miss :reason :provider-failure}
      (not authenticated?) {:status :miss :reason :unauthenticated}
      (or (not= key expected-key) (not= source expected-source))
      {:status :miss :reason :scope-mismatch}
      (and (not= graph selected-graph) (not (contains? ancestors graph)))
      {:status :miss :reason :future-or-sibling}
      (or (nil? proof) (not= proof selected-proof))
      {:status :miss :reason :proof-mismatch}
      :else
      {:status :hit
       :provenance (if (= graph selected-graph)
                     :exact-hit
                     :causal-proof-lift)})))

(defn- subproblem-cache-decision
  [{:keys [decision] :as input}]
  (case decision
    :lookup
    (if (= :complete (:candidate input))
      :use-completed-value
      :start-independent-computation)
    :admission
    (if (and (not (:candidate-present? input))
             (< (:attempted-publications input) (:maximum-attempts input)))
      :attempt-publication
      :skip-publication)
    :publication
    (if (and (:ticket-current? input)
             (:complete? input)
             (:valid? input)
             (pos? (:weight input))
             (<= (:weight input) (:budget input)))
      :retain-publication
      :drop-publication)))

(defn- current-cache-decision
  [{:keys [stage available?]}]
  (case stage
    (:eligibility :generation)
    (if available? :probe-exact-entry :bypass-current-cache)
    :exact-entry
    (if available? :use-exact-entry :probe-managed-entry)
    :managed-entry
    (if available? :use-managed-entry :compute-current-value)))

(defn- merge-step
  [{:keys [direction left-head right-head]}]
  (cond
    (nil? left-head) :left-exhausted
    (nil? right-head) :right-exhausted
    (= left-head right-head) :take-both
    (if (= :asc direction)
      (< left-head right-head)
      (> left-head right-head)) :take-left
    :else :take-right))

(defn- merge-chunk
  [{:keys [direction left right]}]
  (loop [li 0 ri 0 values []]
    (if (or (= li (count left)) (= ri (count right)))
      {:values values :left-consumed li :right-consumed ri}
      (let [l (nth left li)
            r (nth right ri)]
        (cond
          (= l r) (recur (inc li) (inc ri) (conj values l))
          (if (= :asc direction) (< l r) (> l r))
          (recur (inc li) ri (conj values l))
          :else (recur li (inc ri) (conj values r)))))))

(defn- enumeration-route
  [{:keys [schema-identity certificate-schema-identity
           root-defined? recursive? recursive-data-active?]}]
  (cond
    (or (empty? schema-identity) (empty? certificate-schema-identity))
    {:status :rejected :reason :missing-schema-identity}
    (not= schema-identity certificate-schema-identity)
    {:status :rejected :reason :schema-identity-mismatch}
    :else
    {:status :accepted
     :route (cond
              (not root-defined?) :undefined
              (and recursive? recursive-data-active?) :recursive
              :else :acyclic)}))

(defn- acyclic-page
  [{:keys [direction realized-eids size bound?]}]
  (let [realized-count (count realized-eids)
        take-count (min size realized-count)
        sentinel? (> realized-count size)]
    {:take-count take-count
     :reverse? (= :desc direction)
     :has-next? (if (= :asc direction) sentinel? bound?)
     :has-previous? (if (= :asc direction) bound? sentinel?)
     :merge-advances realized-count
     :emitted-results take-count
     :recursive-work 0}))

(defn- acyclic-continuation
  [{:keys [authenticated? schema-matches? query-matches?
           snapshot-matches? entry-present? entry-valid?]}]
  (cond
    (not (and authenticated? schema-matches? query-matches? snapshot-matches?))
    :reject
    (and entry-present? entry-valid?) :resume
    :else :replay))

(defn- acyclic-count
  [{:keys [unique-count more? limit]}]
  (let [limited? (some? limit)]
    {:count (if (and limited? (< limit unique-count)) limit unique-count)
     :truncated? (boolean
                  (and limited?
                       (or (< limit unique-count)
                           (and (= limit unique-count) more?))))
     :recursive-work 0}))

(defn- acyclic-work
  [{:keys [requested-window merge-advances emitted-results recursive-work]}]
  (if (and (<= merge-advances (inc requested-window))
           (<= emitted-results requested-window)
           (zero? recursive-work))
    :accepted
    :rejected))

(defn- scan-rejection
  [command response]
  (let [values (:values response)
        bound (get-in command [:projection :bound-eid])]
    (cond
      (not= (:request-scope command) (:request-scope response))
      :mismatched-request-scope
      (not= (:request-id command) (:request-id response)) :mismatched-request
      (> (count values) (:chunk-size command)) :oversized-chunk
      (if (:terminal? response)
        (not= (:fetched-values response) (count values))
        (not= (:fetched-values response) (inc (count values))))
      :invalid-fetched-count
      (and (not (:terminal? response)) (empty? values))
      :non-progressing-response
      (not-every? #(and (integer? %) (not (neg? %))) values) :invalid-eid
      (not (every? true? (map < values (rest values)))) :out-of-order
      (and bound (not-every? #(< bound %) values)) :bound-violation)))

(defn- indexed-scan-decision
  [{:keys [command response]}]
  (if-let [reason (scan-rejection command response)]
    {:status :rejected :reason reason}
    {:status :accepted
     :values (:values response)
     :terminal? (:terminal? response)
     :fetched-values (:fetched-values response)}))

(defn- valid-index? [n x]
  (and (integer? x) (<= 0 x) (< x n)))

(defn- routing-path-edge
  [{:keys [kind head target]}]
  (when (contains? #{:self-permission :arrow-permission} kind)
    {:head head :target target}))

(defn- valid-routing-certificate?
  [node-count edges certificate]
  (let [{:keys [component-root forward-parent-edge reverse-parent-edge
                forward-depth reverse-depth component-rank
                multiple-member-witness self-loop-witness-edge traversal
                traversal-witness-edge]} certificate
        vectors [component-root forward-parent-edge reverse-parent-edge
                 forward-depth reverse-depth component-rank
                 multiple-member-witness self-loop-witness-edge traversal
                 traversal-witness-edge]
        shape? (every? #(and (vector? %) (= node-count (count %))) vectors)
        edge-at #(when (valid-index? (count edges) %) (nth edges %))]
    (and
     shape?
     (every? #(and (valid-index? node-count %)
                   (= (nth component-root %) %))
             component-root)
     (every? #(valid-index? node-count %) component-rank)
     (every? (fn [{:keys [head target]}]
               (and (valid-index? node-count head)
                    (valid-index? node-count target)))
             edges)
     (every?
      (fn [node]
        (let [root (nth component-root node)
              fp (nth forward-parent-edge node)
              rp (nth reverse-parent-edge node)
              fw (edge-at fp)
              rw (edge-at rp)
              multiple (nth multiple-member-witness root)
              self-loop (nth self-loop-witness-edge root)
              recursive? (or (not= -1 multiple) (not= -1 self-loop))
              witness (nth traversal-witness-edge root)]
          (and
           (if (= node root)
             (and (= -1 fp) (zero? (nth forward-depth node)))
             (and fw
                  (= (:target fw) node)
                  (= (nth component-root (:head fw)) root)
                  (< (nth forward-depth (:head fw))
                     (nth forward-depth node))))
           (if (= node root)
             (and (= -1 rp) (zero? (nth reverse-depth node)))
             (and rw
                  (= (:head rw) node)
                  (= (nth component-root (:target rw)) root)
                  (< (nth reverse-depth (:target rw))
                     (nth reverse-depth node))))
           (or (= -1 multiple)
               (and (valid-index? node-count multiple)
                    (not= multiple root)
                    (= (nth component-root multiple) root)))
           (or (= node root) (not= -1 multiple))
           (or (= -1 self-loop)
               (let [edge (edge-at self-loop)]
                 (and edge
                      (= (:head edge) (:target edge))
                      (= (nth component-root (:head edge)) root))))
           (= (nth traversal node) (nth traversal root))
           (or (not recursive?) (nth traversal node))
           (if (and (nth traversal root) (not recursive?))
             (let [edge (edge-at witness)]
               (and edge
                    (= (nth component-root (:head edge)) root)
                    (not= (nth component-root (:target edge)) root)
                    (nth traversal (:target edge))))
             (= -1 witness)))))
      (range node-count))
     (every?
      (fn [{:keys [head target]}]
        (let [hr (nth component-root head)
              tr (nth component-root target)]
          (and
           (or (= hr tr)
               (< (nth component-rank hr) (nth component-rank tr)))
           (or (not= head target)
               (not= -1 (nth self-loop-witness-edge hr)))
           (or (not (nth traversal target))
               (nth traversal head)))))
      edges))))

(defn- routing-certificate
  [{:keys [node-count path-descriptors edges certificate]}]
  (let [expected-edges (into [] (keep routing-path-edge) path-descriptors)
        path-invalid-index
        (first
         (keep-indexed
          (fn [index {:keys [head target] :as path}]
            (when-not (and (valid-index? node-count head)
                           (or (not (contains? path :target))
                               (valid-index? node-count target)))
              index))
          path-descriptors))
        path-checks (if (some? path-invalid-index)
                      (inc path-invalid-index)
                      (count path-descriptors))
        roots (:component-root certificate)
        ranks (:component-rank certificate)
        traversal (:traversal certificate)
        self-loops (:self-loop-witness-edge certificate)
        component-basic?
        (and (= node-count (count roots))
             (= node-count (count ranks))
             (every? #(and (valid-index? node-count %)
                           (= (nth roots %) %))
                     roots)
             (every? #(valid-index? node-count %) ranks))
        invalid-edge?
        (and component-basic?
             (= node-count (count traversal))
             (= node-count (count self-loops))
             (some
              (fn [{:keys [head target]}]
                (or
                 (not (and (valid-index? node-count head)
                           (valid-index? node-count target)))
                 (let [head-root (nth roots head)
                       target-root (nth roots target)]
                   (or
                    (and (not= head-root target-root)
                         (>= (nth ranks head-root) (nth ranks target-root)))
                    (and (= head target)
                         (= -1 (nth self-loops head-root)))
                    (and (nth traversal target)
                         (not (nth traversal head)))))))
              edges))
        base {:path-checks path-checks :node-checks 0 :edge-checks 0}]
    (cond
      (some? path-invalid-index)
      (assoc base :status :rejected :reason :invalid-routing-path)
      (not= expected-edges edges)
      (assoc base :status :rejected :reason :routing-path-edge-mismatch)
      (not (every? #(and (vector? %) (= node-count (count %)))
                   (vals certificate)))
      (assoc base :status :rejected :reason :shape-mismatch)
      (not component-basic?)
      (assoc base :status :rejected :reason :invalid-component
             :node-checks node-count)
      invalid-edge?
      (assoc base
             :status :rejected
             :reason :invalid-dependency-edge
             :node-checks node-count
             :edge-checks (count edges))
      (not (valid-routing-certificate? node-count edges certificate))
      (assoc base
             :status :rejected
             :reason :invalid-component-witness
             :node-checks (* 2 node-count)
             :edge-checks (count edges))
      :else
      {:status :accepted
       :traversal (:traversal certificate)
       :path-checks (count path-descriptors)
       :node-checks (* 2 node-count)
       :edge-checks (count edges)})))

(defn- relation-index
  [bindings]
  (group-by :relation bindings))

(defn- compiled-rules
  [{:keys [definitions relation-bindings]}]
  (let [by-relation (relation-index relation-bindings)]
    (vec
     (mapcat
      (fn [{:keys [kind resource-type permission relation subject-type
                   target-permission via-relation target-relation]}]
        (let [head {:resource-type resource-type :permission permission}]
          (case kind
            :direct-relation
            (for [{:keys [eid]}
                  (get by-relation {:resource-type resource-type
                                    :relation relation
                                    :subject-type subject-type})]
              {:kind :relation :head head
               :relation-eid eid :subject-type subject-type})
            :self-permission
            [{:kind :self-permission :head head
              :target-node {:resource-type resource-type
                            :permission target-permission}}]
            :arrow-permission
            (for [[relation bindings] by-relation
                  :when (and (= resource-type (:resource-type relation))
                             (= via-relation (:relation relation)))
                  {:keys [eid]} bindings]
              {:kind :arrow-permission :head head
               :via-relation-eid eid
               :intermediate-type (:subject-type relation)
               :target-node {:resource-type (:subject-type relation)
                             :permission target-permission}})
            :arrow-relation
            (for [[via-node via-bindings] by-relation
                  :when (and (= resource-type (:resource-type via-node))
                             (= via-relation (:relation via-node)))
                  [target-node target-bindings] by-relation
                  :when (and (= (:subject-type via-node)
                                (:resource-type target-node))
                             (= target-relation (:relation target-node))
                             (= subject-type (:subject-type target-node)))
                  {:keys [eid]} via-bindings
                  {target-eid :eid} target-bindings]
              {:kind :arrow-relation :head head
               :via-relation-eid eid
               :intermediate-type (:subject-type via-node)
               :target-relation-eid target-eid
               :target-subject-type subject-type})
            [])))
      definitions))))

(defn- indexed-plan-decision
  [{:keys [indexed-rules] :as input}]
  (let [expected (compiled-rules input)]
    (cond
      (not= (count indexed-rules) (count (distinct indexed-rules)))
      {:status :rejected :reason :duplicate-indexed-rule}
      (= (frequencies expected) (frequencies indexed-rules))
      {:status :certified}
      :else {:status :rejected :reason :compiled-rule-mismatch})))

(defn- indexed-seed-decision
  [{:keys [indexed-rules seed-rules subject-type]}]
  (let [expected
        (filterv
         (fn [rule]
           (case (:kind rule)
             :relation (= subject-type (:subject-type rule))
             :arrow-relation (= subject-type (:target-subject-type rule))
             false))
         indexed-rules)]
    (cond
      (not= (count seed-rules) (count (distinct seed-rules)))
      {:status :rejected :reason :duplicate-seed-rule}
      (= (frequencies expected) (frequencies seed-rules))
      {:status :certified}
      :else {:status :rejected :reason :seed-bucket-mismatch})))

(defn- object-key [object]
  [(:type object) (:id object)])

(defn- authorization-evaluation
  [{:keys [objects schema relationships request limits]}]
  (let [object-set (set (map object-key objects))
        relations (set (map (juxt :resource-type :relation :subject-type)
                            (:relations schema)))
        permissions (set (map (juxt :resource-type :permission)
                              (:permissions schema)))
        definitions (:definitions schema)
        valid?
        (and (= (count objects) (count object-set))
             (every? #(contains? object-set (object-key (:subject %)))
                     relationships)
             (every? #(contains? object-set (object-key (:resource %)))
                     relationships)
             (every? #(contains? relations
                                 [(:type (:resource %))
                                  (:relation %)
                                  (:type (:subject %))])
                     relationships)
             (every? #(contains? permissions
                                 [(:resource-type %) (:permission %)])
                     definitions))]
    (if-not valid?
      {:status :invalid-schema :errors [:not-well-formed]}
      (let [direct
            (set
             (map (fn [{:keys [subject relation resource]}]
                    [(:type resource) (:id resource) relation
                     (:type subject) (:id subject)])
                  relationships))
            derive
            (fn [grants definition]
              (let [{:keys [kind resource-type permission relation
                            subject-type target-permission via-relation
                            target-relation]} definition]
                (case kind
                  :direct-relation
                  (for [[rt rid rel st sid] direct
                        :when (= [rt rel st]
                                 [resource-type relation subject-type])]
                    [resource-type rid permission st sid])
                  :self-permission
                  (for [[rt rid perm st sid] grants
                        :when (= [rt perm]
                                 [resource-type target-permission])]
                    [resource-type rid permission st sid])
                  :arrow-relation
                  (for [[rt rid rel it iid] direct
                        :when (= [rt rel] [resource-type via-relation])
                        [irt irid irel st sid] direct
                        :when (= [irt irid irel st]
                                 [it iid target-relation subject-type])]
                    [resource-type rid permission st sid])
                  :arrow-permission
                  (for [[rt rid rel it iid] direct
                        :when (= [rt rel] [resource-type via-relation])
                        [irt irid perm st sid] grants
                        :when (= [irt irid perm]
                                 [it iid target-permission])]
                    [resource-type rid permission st sid])
                  [])))
            grants
            (loop [grants #{}
                   remaining (:max-derived-grants limits)]
              (let [next (into grants (mapcat #(derive grants %) definitions))]
                (cond
                  (> (count next) (:max-derived-grants limits)) ::limit
                  (= next grants) grants
                  (zero? remaining) ::limit
                  :else (recur next (dec remaining)))))]
        (if (= ::limit grants)
          {:status :limit-exceeded
           :operation (:operation request)
           :limit-kind :derived-grants
           :counters {:derived-grants (:max-derived-grants limits)
                      :advanced-datoms 0
                      :queued-work 0}}
          (let [{:keys [operation subject permission resource resource-type
                        subject-type count-limit]} request
                counters
                {:derived-grants (count grants)
                 :advanced-datoms (count relationships)
                 :queued-work (if (seq relationships) 1 0)}
                matching
                (case operation
                  (:can? :lookup-resources :count-resources)
                  (filter
                   (fn [[rt _ perm st sid]]
                     (and (= [rt perm st sid]
                             [(or resource-type (:type resource)) permission
                              (:type subject) (:id subject)])))
                   grants)
                  (:lookup-subjects :count-subjects)
                  (filter
                   (fn [[rt rid perm st _]]
                     (and (= [rt rid perm st]
                             [(:type resource) (:id resource)
                              permission subject-type])))
                   grants))
                items
                (case operation
                  (:lookup-resources :count-resources)
                  (->> matching (map (fn [[rt rid]] {:type rt :id rid}))
                       distinct (sort-by (juxt :type :id)) vec)
                  (:lookup-subjects :count-subjects)
                  (->> matching (map (fn [[_ _ _ st sid]] {:type st :id sid}))
                       distinct (sort-by (juxt :type :id)) vec)
                  [])]
            (case operation
              :can?
              {:status :complete
               :operation operation
               :allowed? (boolean (seq matching))
               :counters counters}
              (:lookup-resources :lookup-subjects)
              {:status :complete
               :operation operation
               :items items
               :counters counters}
              (:count-resources :count-subjects)
              (if (and (some? count-limit) (< count-limit (count items)))
                {:status :complete
                 :operation operation
                 :count count-limit
                 :truncated? true
                 :counters counters}
                {:status :complete
                 :operation operation
                 :count (count items)
                 :truncated? false
                 :counters counters}))))))))

(defn decide
  [operation input]
  (case operation
    :relationship-page (page-decision input)
    :relationship-keyset-page (keyset-page-decision input)
    :cursor-continuation (continuation-decision input)
    :consistency-plan (consistency-plan input)
    :consistency-validation (consistency-validation input)
    :cache-validation (cache-decision input)
    :current-cache-decision (current-cache-decision input)
    :subproblem-cache-decision (subproblem-cache-decision input)
    :ordered-merge-step (merge-step input)
    :ordered-merge-chunk (merge-chunk input)
    :recursive-routing-certificate (routing-certificate input)
    :enumeration-route (enumeration-route input)
    :acyclic-page (acyclic-page input)
    :acyclic-continuation (acyclic-continuation input)
    :acyclic-count (acyclic-count input)
    :acyclic-work (acyclic-work input)
    :indexed-scan-response (indexed-scan-decision input)
    :indexed-plan-certification (indexed-plan-decision input)
    :indexed-seed-certification (indexed-seed-decision input)
    :authorization-evaluation (authorization-evaluation input)))

(defrecord PortableDecisionKernel []
  verified/DecisionKernel
  (-decide [_ operation input]
    (decide operation input)))

(def portable-decision-kernel
  (->PortableDecisionKernel))
