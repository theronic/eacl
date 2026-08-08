(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]
            [eacl.spicedb.consistency :as public-consistency]
            [eacl.subproblem-cache :as subproblem]
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
  #{:first :last :after :before :consistency :cache?})

(def ^:private cursor-transport-keys
  "Relay window controls do not define the authorized result set. They remain
  caller-controlled so one boundary cursor can support forward and backward
  navigation. Consistency, principal, permission, filters, and resource type
  remain part of the authenticated semantic scope."
  #{:first :last :after :before :cache?})

(def cursor-emission-order-version
  "Version of the traversal emission order committed by Relay cursor digests.

  Version 2 activates bounded, ordered scan waves. The batched protocol is
  extensionally equivalent to the sequential protocol, but pinning the
  protocol order here prevents a cursor minted by a different emission
  schedule from being accepted accidentally."
  2)

(defn- normalized-cursor-query
  [query]
  (-> (apply dissoc query cursor-transport-keys)
      (assoc :consistency
             (public-consistency/descriptor (:consistency query)))))

(defn- request-schema-stamp
  "The selected snapshot's schema stamp for one request.

  Callers may share the stamp they already resolved for the request's
  derived-schema cache through `:cursor-schema-stamp` (an
  `{:adapter a :stamp delay}` pair); the pair is honored only for the very
  adapter it was resolved against, so recovery-path adapters read their own
  stamp. The `:schema-proof` invocation is memoized per adapter instance."
  [adapter opts]
  (let [shared (:cursor-schema-stamp opts)]
    (if (and shared (identical? (:adapter shared) adapter))
      (force (:stamp shared))
      (backend/invoke adapter :schema-proof))))

(defn- plain-scope-object
  [object]
  (when object
    (select-keys object [:type :id :relation])))

(defn- scoped-query-form
  [query]
  (cond-> (normalized-cursor-query query)
    (:subject query) (update :subject plain-scope-object)
    (:resource query) (update :resource plain-scope-object)))

(defn- cursor-scope
  "Digest of the complete authenticated query scope, including the selected
  snapshot's schema generation. A cursor minted under another schema
  generation therefore fails scope validation unconditionally — recovery
  mode included."
  [adapter opts operation query]
  (secure/canonical-digest
   "eacl/cursor/query-scope/v7"
   [cursor-emission-order-version
    operation
    {:schema-stamp (request-schema-stamp adapter opts)}
    (scoped-query-form query)]))

(defn- navigation-boundary-scope
  "Boundary-alias scope for the client-private page-navigation cache.

  The navigation `generation` already pins the exact immutable snapshot
  (graph head included), so this digest deliberately omits the schema stamp:
  it correlates adjacent pages within one generation and carries no
  validation authority."
  [operation query]
  (secure/canonical-digest
   "eacl/cursor/query-scope/v6"
   [cursor-emission-order-version
    operation
    (scoped-query-form query)]))

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
  [generation (navigation-boundary-scope operation query) token])

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

(defn- dependency-stamp-digests
  "Builds the dependency-scoped digest pair for one sorted relation-id vector.

  Returns nil when the schema stamp or any relation stamp is unreadable, so
  the caller falls back to the exact-snapshot proof (never wrong, at most a
  recovery instead of a continuation hit)."
  [adapter schema-stamp relation-ids]
  (let [relation-ids (vec relation-ids)
        relation-stamps
        (backend/invoke adapter :relation-proof relation-ids)]
    (when (and (some? schema-stamp)
               (some? relation-stamps))
      {:dependency-scope-digest
       (secure/canonical-digest
        "eacl/cursor/dependency-scope/v4"
        {:mode :relation-dependencies
         :relation-ids relation-ids})
       :proof-digest
       (secure/canonical-digest
        "eacl/cursor/dependency-proof/v1"
        {:schema-stamp schema-stamp
         :relation-stamps relation-stamps})})))

(defn- build-dependency-context
  [adapter schema-stamp relation-ids]
  (let [graph-head (backend/invoke adapter :graph-head)
        base
        {:source-scope
         {:backend (backend/backend-id adapter)
          :scope (backend/invoke adapter :source-scope)}
         :graph-head graph-head
         :adapter-fingerprint (backend/fingerprint adapter)
         :identity-contract (backend/identity-contract adapter)}
        dependency-digests
        (when (some? relation-ids)
          (dependency-stamp-digests adapter schema-stamp relation-ids))]
    (if dependency-digests
      (merge base dependency-digests)
      (assoc base
             :dependency-scope-digest exact-snapshot-scope-digest
             :proof-digest
             (secure/canonical-digest
              "eacl/cursor/exact-snapshot/v4"
              {:snapshot-id (backend/invoke adapter :snapshot-id)
               :graph-head graph-head})))))

(defn dependency-context
  "Builds bounded continuation metadata for one immutable snapshot.

  Without `relation-ids` the proof pins the exact snapshot identity
  (relationship-index cursors keep this arity). With a sorted vector of
  relation-definition eids — the query's compiled dependency closure — the
  proof becomes the schema stamp plus the per-relation stamps, so a
  transaction touching no relation in the closure leaves the proof equal and
  the continuation reusable. Unreadable stamps fall back to the
  exact-snapshot proof."
  ([adapter]
   (build-dependency-context adapter nil nil))
  ([adapter relation-ids]
   (build-dependency-context
    adapter
    (when (some? relation-ids)
      (backend/invoke adapter :schema-proof))
    relation-ids)))

(defn- request-relation-ids
  "The query's sorted relation-dependency vector, when the caller supplied
  one (directly or as a delay) for permission lookups."
  [opts]
  (some-> (:cursor-dependency-relation-ids opts) force))

(defn- request-dependency-context
  [adapter opts]
  (if-let [relation-ids (request-relation-ids opts)]
    (build-dependency-context
     adapter
     (request-schema-stamp adapter opts)
     relation-ids)
    (dependency-context adapter)))

(defn- transform-edge-ids
  ;; v11 :lookup-eid edges carry only the boundary :result-eid; path
  ;; frontiers live exclusively in the private continuation store and never
  ;; cross the cursor envelope (the dead frontier-coercion branch was
  ;; deleted by trusted-surface-hygiene 11.1).
  [f edge]
  (case (:kind edge)
    :lookup-eid
    (cond-> edge
      (:result-eid edge) (update :result-eid f))

    :relationship-index
    (-> edge
        (update :subject-id f)
        (update :resource-id f))

    edge))

(defn- encode-page-edge
  [adapter opts scope context edge]
  (when edge
    (cursor/cursor->token
     (merge
      {:v 11
       :scope scope
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
  "Authenticates one Relay token and returns its envelope annotated with the
  computed decode facts (`:cursor/authenticated?`, `:cursor/expired?`,
  `:cursor/scope-matches?`).

  Authentication and envelope-shape failures throw here — an unauthenticated
  token has no payload to decide over. Expiry and query-scope mismatch are
  returned as computed booleans and rejected by the verified continuation
  decision, which reproduces the historical public errors."
  [adapter opts operation query token]
  (when token
    (let [decoded
          (try
            (cursor/token->authenticated-cursor token opts)
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              (invalid-cursor!
               "Invalid Relay cursor."
               {:reason (:reason (ex-data error))}
               error)))
          envelope (:cursor decoded)]
      (when-not (and (= 11 (:v envelope))
                     (map? (:edge envelope)))
        (invalid-cursor! "Invalid Relay cursor envelope."
                         {:reason :invalid-envelope}
                         nil))
      (assoc envelope
             :cursor/authenticated? (boolean (:authenticated? decoded))
             :cursor/expired? (boolean (:expired? decoded))
             :cursor/expired-at (:expired-at decoded)
             :cursor/scope-matches?
             (= (cursor-scope adapter opts operation query)
                (:scope envelope))))))

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
  "Graph-anchor code in the numbering shared by one continuation decision:
  0 = the current selection's graph, 1 = the cursor's (different) graph,
  2 = any other graph. The kernel's exact arm compares codes, so an exact
  selection is accepted only when it resolves the cursor's own graph."
  [current envelope context]
  (let [anchor
        (secure/canonicalize
         (get-in context [:graph-head :graph-anchor]))]
    (cond
      (= anchor
         (secure/canonicalize
          (get-in current [:graph-head :graph-anchor])))
      0

      (= anchor
         (secure/canonicalize
          (get-in envelope [:graph-head :graph-anchor])))
      1

      :else 2)))

(defn- continuation-proof
  [context]
  (secure/canonical-digest
   "eacl/cursor/continuation-proof/v1"
   [(:dependency-scope-digest context)
    (:proof-digest context)]))

(defn- continuation-decision
  [opts current envelope exact]
  (let [source (execution-identity current)
        cursor-source (execution-identity envelope)
        current-proof (continuation-proof current)
        cursor-proof (continuation-proof envelope)
        mode
        (if (= :at-exact-snapshot
               (:cursor-consistency-mode opts))
          :exact-snapshot
          :recover-current)
        exact-decision
        (when exact
          {:graph (graph-code current envelope exact)
           :source (execution-identity exact)
           :proof (continuation-proof exact)})]
    (verified/decide
     (or (:decision-kernel opts)
         subproblem/*decision-kernel*)
     :cursor-continuation
     {:authenticated? (boolean (:cursor/authenticated? envelope))
      :scope-matches? (boolean (:cursor/scope-matches? envelope))
      :expired? (boolean (:cursor/expired? envelope))
      :source source
      :cursor-source cursor-source
      :current-proof current-proof
      :cursor-proof cursor-proof
      :mode mode
      :cursor-graph (graph-code current envelope envelope)
      :exact exact-decision})))

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
    :rebase-current adapter
    :exact adapter

    :expired
    (throw (cursor/expired-cursor-error (:cursor/expired-at envelope)))

    :scope-mismatch
    (cond
      ;; The query-scope comparison computed at decode preceded the
      ;; execution-identity comparison historically; preserve that order and
      ;; its public error when the kernel rejects the scope.
      (false? (:cursor/scope-matches? envelope))
      (invalid-cursor!
       "Relay cursor belongs to a different query."
       {:reason :query-mismatch}
       nil)

      :else
      (if-let [field (identity-mismatch current envelope)]
        (invalid-cursor!
         "Relay cursor execution identity does not match."
         {:reason field}
         nil)
        (invalid-cursor!
         "Relay cursor belongs to a different execution scope."
         {:reason :query-mismatch}
         nil)))

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
  [adapter opts]
  (request-dependency-context adapter opts))

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
    {:adapter adapter
     :recovery nil}
    (let [current (current-context adapter opts)
          initial
          (continuation-decision opts current envelope nil)]
      (case initial
        :snapshot-unavailable
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
                (request-dependency-context exact opts)
                decision
                (continuation-decision
                 opts current envelope exact-context)]
            (apply-continuation-decision!
             exact exact-context envelope decision)
            {:adapter exact
             :recovery nil}))

        :rebase-current
        (do
          (apply-continuation-decision!
           adapter current envelope initial)
          {:adapter adapter
           :recovery :rebased})

        (do
          (apply-continuation-decision!
           adapter current envelope initial)
          {:adapter adapter
           :recovery nil})))))

(defn select-continuation-adapter
  "Uses an equal current proof, recovers non-exact reads on current, and
  reconstructs history only for explicit exact-snapshot requests."
  [adapter opts operation query]
  (let [token (or (:after query) (:before query))
        envelope (decode-envelope adapter opts operation query token)]
    (:adapter (select-envelope-adapter adapter opts envelope))))

(defn- internalize-tracked-edge
  [adapter edge]
  (let [missing? (atom false)
        transformed
        (transform-edge-ids
         (fn [object-id]
           (let [internal-id
                 (backend/invoke
                  adapter :object-id->internal object-id)]
             (when (nil? internal-id)
               (reset! missing? true))
             internal-id))
         edge)]
    {:edge transformed
     :missing? @missing?}))

(defn- internalize-rebased-edge
  [adapter edge]
  (let [{:keys [edge missing?]}
        (internalize-tracked-edge adapter edge)]
    (if missing?
      {:edge nil
       :recovery :restarted}
      {:edge
       (cond-> edge
         (#{:lookup-eid} (:kind edge))
         (assoc :rebase? true))
       :recovery :rebased})))

(defn- internalize-continued-edge
  "Internalizes a resume edge for an equal-proof continuation.

  A dependency-scoped equal proof spans transactions outside the query's
  closure, so the boundary object can have been retracted raw while its
  relationship tuples survive. That identity loss cannot be a silent bound
  drop: restart honestly, exactly like the rebase path."
  [adapter edge]
  (let [{:keys [edge missing?]}
        (internalize-tracked-edge adapter edge)]
    (if missing?
      {:edge nil
       :recovery :restarted}
      {:edge edge
       :recovery nil})))

(defn prepare-page-query
  "Authenticates each page token once, selects the consistency-mode snapshot,
  and converts or safely restarts its resume edge in that identity space."
  [adapter opts operation query]
  (let [envelopes
        (->> [:after :before]
             (keep
              (fn [field]
                (when (contains? query field)
                  [field
                   (decode-envelope
                    adapter opts operation query (get query field))])))
             vec)
        primary-envelope (some second envelopes)
        selection
        (select-envelope-adapter adapter opts primary-envelope)
        page-adapter (:adapter selection)
        selected-recovery (:recovery selection)
        prepared
        (reduce
         (fn [{:keys [query recovery]} [field envelope]]
           (if-not envelope
             {:query (assoc query field nil)
              :recovery recovery}
             (do
               (when-not (identical? envelope primary-envelope)
                 (validate-context! page-adapter opts envelope))
               (let [{internal-edge :edge
                      edge-recovery :recovery}
                     (if selected-recovery
                       (internalize-rebased-edge
                        page-adapter (:edge envelope))
                       (internalize-continued-edge
                        page-adapter (:edge envelope)))]
                 {:query
                  (if internal-edge
                    (assoc query field internal-edge)
                    (dissoc query field))
                  :recovery
                  (if (= :restarted edge-recovery)
                    :restarted
                    (or recovery edge-recovery))}))))
         {:query query
          :recovery selected-recovery}
         envelopes)]
    {:adapter page-adapter
     :query (:query prepared)
     :recovery (:recovery prepared)}))

(defn- decode-page-edge
  [adapter opts operation query token]
  (when-let [envelope
             (decode-envelope adapter opts operation query token)]
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
  (let [context (delay (request-dependency-context adapter opts))
        scope (cursor-scope adapter opts operation query)
        encode-edge
        (fn [edge]
          (encode-page-edge
           adapter opts scope
           (when edge @context)
           edge))]
    (cond-> (-> page
                (update-in [:page-info :start-cursor] encode-edge)
                (update-in [:page-info :end-cursor] encode-edge))
      (:cursor-recovery opts)
      (update-in
       [:page-info :cursor-recovery]
       #(or % (:cursor-recovery opts))))))

(def ^:private identity-key-version 1)

(defn- cached-internal-id->object
  [adapter internal-id]
  (let [resolved
        (subproblem/resolve-bound!
         :projection
         [identity-key-version
          :internal-id->object
          (backend/backend-id adapter)
          (backend/identity-contract adapter)
          internal-id]
         {:valid? some?
          :weight-fn
          (fn [value]
            (+ 96
               (if (string? value)
                 (* 2 (count value))
                 160)))}
         #(backend/invoke adapter :internal-id->object internal-id))]
    (when (:cached? resolved)
      (subproblem/record-avoided-backend-operation!))
    (:value resolved)))

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
          (cached-internal-id->object adapter id)))
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
            #(cached-internal-id->object adapter %))
           :relation relation
           :resource
           (update
            resource :id
            #(cached-internal-id->object adapter %))}))
       relationships)))))
