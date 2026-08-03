(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]
            [eacl.verified-kernel :as verified]))

(def empty-page
  {:data []
   :page-info {:start-cursor nil
               :end-cursor nil
               :has-next-page? false
               :has-previous-page? false}})

(defrecord PageNavigationCache [state max-entries])

(defn page-navigation-cache
  "Creates a bounded, client-private cache of visited Relay page requests.

  The cache stores public pages only for the immutable snapshot against which
  they were produced. It also learns the opposite-direction request for an
  adjacent page, allowing a first Back/Forward traversal to reuse EACL's
  already-computed answer."
  ([]
   (page-navigation-cache {}))
  ([{:keys [max-entries]
     :or {max-entries 2048}}]
   (when-not (and (integer? max-entries) (pos? max-entries))
     (throw
      (ex-info
       "Relay page-navigation cache :max-entries must be positive."
       {:type :eacl/invalid-config
        :max-entries max-entries})))
   (->PageNavigationCache
    (atom {:order []
           :entries {}
           :by-start {}
           :by-end {}})
    max-entries)))

(defn clear-page-navigation-cache!
  [cache]
  (when cache
    (when-not (instance? PageNavigationCache cache)
      (throw
       (ex-info
        "Expected an EACL Relay page-navigation cache."
        {:type :eacl/invalid-config})))
    (reset! (:state cache)
            {:order []
             :entries {}
             :by-start {}
             :by-end {}}))
  nil)

(def ^:private relay-page-keys
  #{:first :last :after :before :consistency})

(defn- cursor-scope
  [operation query]
  (let [plain-object
        (fn [object]
          (when object
            (select-keys object [:type :id :relation])))]
    [operation
     (cond-> (apply dissoc query relay-page-keys)
       (:subject query) (update :subject plain-object)
       (:resource query) (update :resource plain-object))]))

(defn- page-generation
  [adapter]
  {:backend (backend/backend-id adapter)
   :source-scope (backend/invoke adapter :source-scope)
   :graph-head (backend/invoke adapter :graph-head)
   :adapter-fingerprint (backend/fingerprint adapter)
   :identity-contract (backend/identity-contract adapter)})

(defn- page-request-key
  [generation operation query]
  [generation operation (dissoc query :consistency)])

(defn- page-boundary-key
  [generation operation query token]
  [generation (cursor-scope operation query) token])

(defn- remove-index-value
  [index request-key]
  (into {}
        (remove (fn [[_ indexed-key]]
                  (= request-key indexed-key)))
        index))

(defn- evict-page-request
  [state request-key]
  (-> state
      (update :entries dissoc request-key)
      (update :by-start remove-index-value request-key)
      (update :by-end remove-index-value request-key)))

(defn- put-page-request
  [state request-key page max-entries]
  (let [order
        (conj
         (into [] (remove #(= request-key %)) (:order state))
         request-key)
        state
        (assoc state
               :order order
               :entries (assoc (:entries state) request-key page))
        overflow (- (count order) max-entries)]
    (if-not (pos? overflow)
      state
      (let [victims (take overflow order)]
        (reduce evict-page-request
                (assoc state :order (vec (drop overflow order)))
                victims)))))

(defn- page-cache-enabled?
  [cache opts]
  (and cache
       (:completed-cache? opts)
       (nil? (:cursor-ttl-seconds opts))))

(defn lookup-visited-page
  "Returns a page already visited under this exact immutable snapshot.

  Cursor selection/authentication must happen before this function. A hit
  therefore reuses computation, not consistency or trust decisions."
  [adapter opts operation query]
  (let [cache (:page-navigation-cache opts)]
    (when (page-cache-enabled? cache opts)
      (when-not (instance? PageNavigationCache cache)
        (throw
         (ex-info
          "Expected an EACL Relay page-navigation cache."
          {:type :eacl/invalid-config})))
      (some->
       (get-in
        @(:state cache)
        [:entries
         (page-request-key
          (page-generation adapter)
          operation
          query)])
       (assoc :cached? true)))))

(defn remember-visited-page!
  "Stores one current page and learns any adjacent opposite-direction alias."
  [adapter opts operation query page]
  (let [cache (:page-navigation-cache opts)]
    (when (page-cache-enabled? cache opts)
      (when-not (instance? PageNavigationCache cache)
        (throw
         (ex-info
          "Expected an EACL Relay page-navigation cache."
          {:type :eacl/invalid-config})))
      (let [generation (page-generation adapter)
            request-key
            (page-request-key generation operation query)
            scope-key
            #(page-boundary-key
              generation operation query %)
            start-token
            (get-in page [:page-info :start-cursor])
            end-token
            (get-in page [:page-info :end-cursor])
            after-token (:after query)
            before-token (:before query)
            base-query
            (apply dissoc query relay-page-keys)]
        (swap!
         (:state cache)
         (fn [state]
           (let [previous-key
                 (when after-token
                   (get-in state [:by-end (scope-key after-token)]))
                 previous-page
                 (when previous-key
                   (get-in state [:entries previous-key]))
                 next-key
                 (when before-token
                   (get-in state [:by-start (scope-key before-token)]))
                 next-page
                 (when next-key
                   (get-in state [:entries next-key]))
                 state
                 (put-page-request
                  state request-key page (:max-entries cache))
                 state
                 (cond-> state
                   start-token
                   (assoc-in [:by-start (scope-key start-token)]
                             request-key)
                   end-token
                   (assoc-in [:by-end (scope-key end-token)]
                             request-key))
                 state
                 (if (and previous-page
                          start-token
                          (integer? (:first query)))
                   (put-page-request
                    state
                    (page-request-key
                     generation
                     operation
                     (assoc base-query
                            :last (:first query)
                            :before start-token))
                    previous-page
                    (:max-entries cache))
                   state)]
             (if (and next-page
                      end-token
                      (integer? (:last query)))
               (put-page-request
                state
                (page-request-key
                 generation
                 operation
                 (assoc base-query
                        :first (:last query)
                        :after end-token))
                next-page
                (:max-entries cache))
               state)))))))
  page)

(def ^:private exact-snapshot-scope-digest
  (secure/canonical-digest
   "eacl/cursor/dependency-scope/v4"
   {:mode :exact-snapshot}))

(defn dependency-context
  "Builds bounded metadata that pins a cursor to one exact immutable snapshot.

  `dependencies` is retained as an ignored argument for source compatibility
  with callers compiled against the former proof-lifting strategy. Cursor
  correctness no longer depends on proving that two revisions have equal
  relationship projections: a continuation either uses the identical current
  revision or reconstructs the authenticated original revision."
  [adapter _dependencies]
  (let [graph-head (backend/invoke adapter :graph-head)
        snapshot-id (backend/invoke adapter :snapshot-id)]
    {:source-scope
     {:backend (backend/backend-id adapter)
      :scope (backend/invoke adapter :source-scope)}
     :graph-head graph-head
     :adapter-fingerprint (backend/fingerprint adapter)
     :identity-contract (backend/identity-contract adapter)
     :dependency-scope-digest exact-snapshot-scope-digest
     :proof-digest
     (secure/canonical-digest
      "eacl/cursor/exact-snapshot/v4"
      {:snapshot-id snapshot-id
       :graph-head graph-head})}))

(defn- transform-frontier-ids
  [f frontiers]
  (into {}
        (map (fn [[path-key frontier]]
               [path-key
                (if (= :exhausted frontier)
                  frontier
                  (f frontier))]))
        frontiers))

(defn- transform-edge-ids
  [f edge]
  (case (:kind edge)
    :lookup-eid
    (cond-> edge
      (:result-eid edge) (update :result-eid f)
      (:path-frontiers edge)
      (update :path-frontiers #(transform-frontier-ids f %)))

    :recursive-traversal
    (cond-> edge
      (get-in edge [:result :eid]) (update-in [:result :eid] f))

    :relationship-index
    (-> edge
        (update :subject-id f)
        (update :resource-id f))

    edge))

(defn- encode-page-edge
  [adapter opts operation query context edge]
  (when edge
    (cursor/cursor->token
     (merge
      {:v 10
       :scope (cursor-scope operation query)
       :edge (transform-edge-ids
              #(backend/invoke adapter :internal-id->object %)
              edge)}
      context)
     opts)))

(defn- invalid-cursor!
  [message data cause]
  (throw (ex-info message
                  (merge {:type :eacl.pagination/invalid-cursor
                          :eacl/error :eacl.pagination/invalid-cursor}
                         data)
                  cause)))

(defn- decode-envelope
  [opts operation query token]
  (when token
    (let [envelope
          (try
            (cursor/token->cursor token opts)
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              (if (= :eacl.pagination/expired-cursor
                     (:type (ex-data error)))
                (throw error)
                (invalid-cursor!
                 "Invalid Relay cursor."
                 {:reason (:reason (ex-data error))}
                 error))))]
      (when-not (and (= 10 (:v envelope))
                     (map? (:edge envelope)))
        (invalid-cursor! "Invalid Relay cursor envelope."
                         {:reason :invalid-envelope}
                         nil))
      (when-not (= (cursor-scope operation query) (:scope envelope))
        (invalid-cursor! "Relay cursor belongs to a different query."
                         {:reason :query-mismatch}
                         nil))
      envelope)))

(def ^:private execution-identity-fields
  [:source-scope :adapter-fingerprint :identity-contract])

(defn- execution-identity
  [context]
  (secure/canonical-digest
   "eacl/cursor/execution-identity/v1"
   (select-keys context execution-identity-fields)))

(defn- identity-mismatch
  [current envelope]
  (some
   (fn [field]
     (when-not (= (secure/canonicalize (get current field))
                  (secure/canonicalize (get envelope field)))
       field))
   execution-identity-fields))

(defn- graph-code
  [cursor-graph graph]
  (if (= (secure/canonicalize cursor-graph)
         (secure/canonicalize graph))
    0
    1))

(defn- continuation-proof
  [context]
  (secure/canonical-digest
   "eacl/cursor/continuation-proof/v1"
   [(:dependency-scope-digest context)
    (:proof-digest context)]))

(defn- same-continuation-proof?
  [left right]
  (and
   (= (:dependency-scope-digest left)
      (:dependency-scope-digest right))
   (= (:proof-digest left)
      (:proof-digest right))))

(defn- legacy-continuation-decision
  [opts current envelope exact]
  (cond
    (identity-mismatch current envelope) :scope-mismatch
    (same-continuation-proof? current envelope) :current
    (= :at-least-as-fresh (:cursor-consistency-mode opts)) :conflict
    (nil? exact) :snapshot-unavailable
    (or (identity-mismatch exact envelope)
        (not (same-continuation-proof? exact envelope))
        (not= 0
              (graph-code
               (get-in envelope [:graph-head :graph-anchor])
               (get-in exact [:graph-head :graph-anchor]))))
    :history-divergence
    :else :exact))

(defn- continuation-decision
  [opts current envelope exact]
  (if (= :legacy-authoritative
         (or (get-in opts [:engine-selection :mode])
             :legacy-authoritative))
    (legacy-continuation-decision opts current envelope exact)
    (let [source (execution-identity current)
          cursor-source (execution-identity envelope)
          current-proof (continuation-proof current)
          cursor-proof (continuation-proof envelope)
          mode
          (if (= :at-least-as-fresh
                 (:cursor-consistency-mode opts))
            :at-least-as-fresh
            :minimize-latency)
          exact-decision
          (when exact
            {:graph
             (graph-code
              (get-in envelope [:graph-head :graph-anchor])
              (get-in exact [:graph-head :graph-anchor]))
             :source (execution-identity exact)
             :proof (continuation-proof exact)})]
      (verified/decide
       (:engine-selection opts)
       :cursor-continuation
       {:authenticated? true
        :scope-matches? true
        :expired? false
        :source source
        :cursor-source cursor-source
        :current-proof current-proof
        :cursor-proof cursor-proof
        :mode mode
        :cursor-graph 0
        :exact exact-decision}
       #(legacy-continuation-decision
         opts current envelope exact)))))

(defn- stale-context!
  [message reason]
  (throw
   (ex-info
    message
    {:type :eacl.pagination/stale-cursor
     :eacl/error :eacl.pagination/stale-cursor
     :reason reason})))

(defn- apply-continuation-decision!
  [adapter current envelope decision]
  (case decision
    :current adapter
    :exact adapter

    :scope-mismatch
    (if-let [field (identity-mismatch current envelope)]
      (invalid-cursor!
       "Relay cursor execution identity does not match."
       {:reason field}
       nil)
      (invalid-cursor!
       "Relay cursor belongs to a different execution scope."
       {:reason :query-mismatch}
       nil))

    :conflict
    (consistency/cursor-conflict!
     {:cursor-graph-anchor
      (get-in envelope [:graph-head :graph-anchor])
      :selected-graph-anchor
      (get-in current [:graph-head :graph-anchor])})

    :snapshot-unavailable
    (stale-context!
     "Relay cursor dependency proof changed."
     (if (= (:dependency-scope-digest current)
            (:dependency-scope-digest envelope))
       :dependency-proof-changed
       :dependency-scope-changed))

    :history-divergence
    (throw
     (ex-info
      "The cursor exact locator resolved to another graph."
      {:type :eacl.consistency/history-divergence
       :eacl/error :eacl.consistency/history-divergence}))

    (invalid-cursor!
     "Generated cursor decision rejected the authenticated envelope."
     {:reason decision}
     nil)))

(defn- current-context
  [adapter _opts]
  (dependency-context adapter nil))

(defn- validate-context!
  [adapter opts envelope]
  (let [current (current-context adapter opts)]
    (apply-continuation-decision!
     adapter
     current
     envelope
     (continuation-decision opts current envelope nil))
    true))

(defn- select-envelope-adapter
  [adapter opts envelope]
  (if-not envelope
    adapter
    (let [current (current-context adapter opts)
          initial
          (continuation-decision opts current envelope nil)]
      (if (= :snapshot-unavailable initial)
        (let [exact
              (backend/invoke
               adapter
               :select-exact
               {:graph-anchor
                (get-in envelope [:graph-head :graph-anchor])
                :order-hint
                (get-in envelope [:graph-head :order-hint])
                :exact-locator
                (get-in envelope [:graph-head :exact-locator])}
               (:timeout-ms opts))]
          (when-not exact
            (throw
             (ex-info
              "The cursor's exact snapshot is no longer retained."
              {:type :eacl.consistency/snapshot-expired
               :eacl/error
               :eacl.consistency/snapshot-expired})))
          (let [exact-context
                (dependency-context exact nil)
                decision
                (continuation-decision
                 opts current envelope exact-context)]
            (apply-continuation-decision!
             exact exact-context envelope decision)
            exact))
        (do
          (apply-continuation-decision!
           adapter current envelope initial)
          adapter)))))

(defn select-continuation-adapter
  "Uses an equal current proof, otherwise reconstructs the authenticated
  original graph when no newer at-least floor forbids fallback."
  [adapter opts operation query]
  (let [token (or (:after query) (:before query))
        envelope (decode-envelope opts operation query token)]
    (select-envelope-adapter adapter opts envelope)))

(defn prepare-page-query
  "Authenticates each supplied page token once, selects its immutable snapshot,
  and converts the cursor edge into that snapshot's internal identity space."
  [adapter opts operation query]
  (let [envelopes
        (->> [:after :before]
             (keep
              (fn [field]
                (when (contains? query field)
                  [field
                   (decode-envelope
                    opts operation query (get query field))])))
             vec)
        primary-envelope (some second envelopes)
        page-adapter
        (select-envelope-adapter adapter opts primary-envelope)
        internal-query
        (reduce
         (fn [current-query [field envelope]]
           (if-not envelope
             (assoc current-query field nil)
             (do
               (when-not (identical? envelope primary-envelope)
                 (validate-context! page-adapter opts envelope))
               (assoc
                current-query
                field
                (transform-edge-ids
                 #(backend/invoke
                   page-adapter :object-id->internal %)
                 (:edge envelope))))))
         query
         envelopes)]
    {:adapter page-adapter
     :query internal-query}))

(defn- decode-page-edge
  [adapter opts operation query token]
  (when-let [envelope
             (decode-envelope opts operation query token)]
    (validate-context! adapter opts envelope)
    (transform-edge-ids
     #(backend/invoke adapter :object-id->internal %)
     (:edge envelope))))

(defn internalize-page-query
  [adapter opts operation query]
  (cond-> query
    (contains? query :after)
    (update :after #(decode-page-edge adapter opts operation query %))

    (contains? query :before)
    (update :before #(decode-page-edge adapter opts operation query %))))

(defn- externalize-page-cursors
  [adapter opts operation query page]
  (let [context (delay (dependency-context adapter nil))
        encode-edge
        (fn [edge]
          (encode-page-edge
           adapter opts operation query
           (when edge @context)
           edge))]
    (-> page
        (update-in [:page-info :start-cursor] encode-edge)
        (update-in [:page-info :end-cursor] encode-edge))))

(defn externalize-page
  [adapter opts operation query page]
  (externalize-page-cursors
   adapter opts operation query
   (update
    page :data
    (fn [objects]
      (mapv
       (fn [{:keys [type id]}]
         (spice-object
          type
          (backend/invoke adapter :internal-id->object id)))
       objects)))))

(defn externalize-relationship-page
  [adapter opts operation query page]
  (externalize-page-cursors
   adapter opts operation query
   (update
    page
    :data
    (fn [relationships]
      (mapv
       (fn [{:keys [subject relation resource]}]
         (eacl/map->Relationship
          {:subject
           (update
            subject :id
            #(backend/invoke adapter :internal-id->object %))
           :relation relation
           :resource
           (update
            resource :id
            #(backend/invoke adapter :internal-id->object %))}))
       relationships)))))
