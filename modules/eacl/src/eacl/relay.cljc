(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.snapshot-provider :as snapshot-provider]
            [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.cursor :as cursor]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as request-counters]
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
  #{:first :last :after :before :consistency :cache?
    :cancellation-token})

(def ^:private cursor-transport-keys
  "Relay window controls do not define the authorized result set. They remain
  caller-controlled so one boundary cursor can support forward and backward
  navigation. Consistency, principal, permission, filters, and resource type
  remain part of the authenticated semantic scope."
  #{:first :last :after :before :cache? :timeout-ms
    :cancellation-token})

(def cursor-emission-order-version
  "Version of the traversal emission order committed by Relay cursor digests.

  Pinning the version in the scope digest prevents a cursor minted under a
  different emission order from being accepted accidentally. The public
  order itself is the stable first-discovery order sealed into each plan's
  composite fingerprint (`eacl.engine.sealed-plan/order-contract`), which
  the `:stable-edge` boundary carries and `eacl.engine.v8` validates."
  2)

(defn- normalized-cursor-query
  [query]
  (-> (apply dissoc query cursor-transport-keys)
      (assoc :consistency
             (select-keys
              (public-consistency/descriptor (:consistency query))
              [:mode]))))

(defn- request-schema-stamp
  "The selected snapshot's schema stamp for one request.

  Callers may share the stamp they already resolved for the request's
  derived-schema cache through `:cursor-schema-stamp` (an
  `{:adapter a :stamp delay}` pair); the pair is honored only for the very
  adapter it was resolved against, so recovery-path adapters read their own
  ordered-generation frame."
  [adapter opts]
  (let [shared (:cursor-schema-stamp opts)]
    (if (and shared (identical? (:adapter shared) adapter))
      (force (:stamp shared))
      (let [frame
            (let [candidate (:request-proof-frame opts)]
              (if (and candidate
                       (identical? adapter (:adapter candidate)))
                candidate
                (proof-frame/request-frame adapter)))
            proof (proof-frame/resolve! frame [])]
        (when (proof-frame/complete? proof)
          (:schema-stamp proof))))))

(defn- plain-scope-object
  [object]
  (when object
    (select-keys object [:type :id :relation])))

(defn- scoped-query-form
  [query]
  (cond-> (normalized-cursor-query query)
    (:subject query) (update :subject plain-scope-object)
    (:resource query) (update :resource plain-scope-object)
    (get-in query [:authorization :subject])
    (update-in [:authorization :subject] plain-scope-object)
    (get-in query [:resource/relationship :subject])
    (update-in [:resource/relationship :subject] plain-scope-object)
    (get-in query [:subject/relationship :resource])
    (update-in [:subject/relationship :resource] plain-scope-object)))

(defn- legacy-cursor-scope
  "Version-11 query scope retained so existing cursors continue while their
  current schema generation remains unchanged. Version 12 moves the schema
  proof out of query identity so exact historical recovery can run."
  [adapter opts operation query]
  (secure/canonical-digest
   "eacl/cursor/query-scope/v7"
   [cursor-emission-order-version
    operation
    {:schema-stamp (request-schema-stamp adapter opts)
     :recursive-traversal-limits (:recursive-traversal-limits opts)}
    (scoped-query-form query)]))

(defn- cursor-scope
  "Digest of immutable operation/query/principal/configuration identity.

  Snapshot schema and relationship proof live in the separately authenticated
  dependency context. Keeping them out of this pre-recovery scope allows a
  changed current schema to reach proof comparison and exact fallback without
  weakening rejection of an actually changed query."
  [_adapter opts operation query]
  (let [authorized-page?
        (boolean
         (or (:authorization query)
             (:resource/relationship query)
             (:subject/relationship query)))
        execution-scope
        (cond->
         {:recursive-traversal-limits (:recursive-traversal-limits opts)}
          authorized-page?
          (assoc
           :aggregate-limits
           (get-in opts [:execution-contract :aggregate-limits])
           :page-demand (select-keys query [:first :last])))
        scope-input
        [cursor-emission-order-version
         operation
         execution-scope
         (scoped-query-form query)]]
    (cursor/memoized-context!
     (:cursor-codec-cache opts)
     [:cursor-query-scope 8 scope-input]
     #(secure/canonical-digest
       "eacl/cursor/query-scope/v8"
       scope-input))))

(defn- navigation-boundary-scope
  "Boundary-alias scope for the client-private page-navigation cache.

  The navigation `generation` already pins the exact immutable snapshot
  (native revision included), so this digest deliberately omits the schema stamp:
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
   :source-scope (consistency/source-scope adapter)
   :native-revision (consistency/native-revision adapter)
   :adapter-fingerprint (backend/fingerprint adapter)
   :identity-contract (backend/identity-contract adapter)})

(defn- page-request-key
  [generation operation query]
  [generation operation
   (dissoc query :consistency :cancellation-token)])

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
    (execution/check! (:execution-contract opts) :page-cache-lookup)
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
    (execution/check! (:execution-contract opts) :page-cache-publication)
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

(defn- dependency-proof-descriptor
  "Returns the complete proof descriptor for one sorted relation-id vector.

  Nil falls back to exact-snapshot proof (never wrong, at most recovery
  instead of continuation reuse)."
  [request-proof-frame relation-ids]
  (let [relation-ids (vec relation-ids)
        proof (proof-frame/resolve! request-proof-frame relation-ids)
        descriptor (proof-frame/descriptor proof)]
    descriptor))

(defn- build-dependency-context
  ([adapter request-proof-frame relation-ids]
   (build-dependency-context
    adapter request-proof-frame relation-ids nil))
  ([adapter request-proof-frame relation-ids codec-cache]
   (let [native-revision (consistency/native-revision adapter)
         base
         {:source-scope (consistency/source-scope adapter)
          :native-revision native-revision
          :adapter-fingerprint (backend/fingerprint adapter)
          :identity-contract (backend/identity-contract adapter)}
         relation-ids (some-> relation-ids vec)
         descriptor
         (when relation-ids
           (dependency-proof-descriptor
            request-proof-frame relation-ids))
         snapshot-id
         (when-not descriptor
           (if request-proof-frame
             (proof-frame/snapshot-id request-proof-frame)
             (backend/invoke adapter :snapshot-id)))
         context-key
         [:cursor-dependency-context
          1 base relation-ids descriptor snapshot-id]]
     (cursor/memoized-context!
      codec-cache
      context-key
      (fn []
        (if descriptor
          (assoc base
                 :dependency-scope-digest
                 (secure/canonical-digest
                  "eacl/cursor/dependency-scope/v4"
                  {:mode :relation-dependencies
                   :relation-ids relation-ids})
                 :proof-digest
                 (secure/canonical-digest
                  "eacl/cursor/dependency-proof/v1"
                  descriptor))
          (assoc base
                 :dependency-scope-digest exact-snapshot-scope-digest
                 :proof-digest
                 (secure/canonical-digest
                  "eacl/cursor/exact-snapshot/v4"
                  {:snapshot-id snapshot-id
                   :native-revision native-revision}))))))))

(defn dependency-context
  "Builds bounded continuation metadata for one immutable snapshot.

  Without `relation-ids` the proof pins the exact snapshot identity
  (relationship-index cursors keep this arity). With a sorted vector of
  relation-definition eids — the query's compiled dependency closure — the
  proof becomes the schema stamp plus the scalar dependency frontier, so a
  transaction touching no relation in the closure leaves the proof equal and
  the continuation reusable. Unreadable stamps fall back to the
  exact-snapshot proof."
  ([adapter]
   (build-dependency-context adapter nil nil))
  ([adapter relation-ids]
   (build-dependency-context
    adapter
    (when (some? relation-ids)
      (proof-frame/request-frame adapter))
    relation-ids)))

(defn- request-relation-ids
  "The query's sorted relation-dependency vector, when the caller supplied
  one (directly or as a delay) for permission lookups."
  [opts]
  (some-> (:cursor-dependency-relation-ids opts) force))

(defn- request-dependency-context
  [adapter opts]
  (if-let [relation-ids (request-relation-ids opts)]
    (let [candidate (:request-proof-frame opts)
          frame
          (if (and candidate
                   (identical? adapter (:adapter candidate)))
            candidate
            (proof-frame/request-frame adapter))]
      (build-dependency-context
       adapter frame relation-ids (:cursor-codec-cache opts)))
    (build-dependency-context
     adapter nil nil (:cursor-codec-cache opts))))

(defn- transform-edge-ids
  ;; :stable-edge edges carry only the boundary :result-eid; engine
  ;; checkpoints live exclusively in the private continuation store and never
  ;; cross the cursor envelope.
  [f edge]
  (case (:kind edge)
    :stable-edge
    (cond-> edge
      (:result-eid edge) (update :result-eid f))

    ;; Least-path coordinates pass through UNTRANSFORMED: they interleave
    ;; rule ordinals with eids of several types (no single external
    ;; mapping applies), and the exact basis makes internal ids stable
    ;; for the cursor's whole lifetime (acyclic-keyset-pagination).
    ;; The portable cursor envelope is authenticated encryption, so these
    ;; internal path coordinates remain confidential on every backend.
    :least-path-edge
    edge

    :relationship-index
    (-> edge
        (update :subject-id f)
        (update :resource-id f))

    edge))

(defn- encode-page-edge
  [adapter opts scope context edge]
  (when edge
    (request-counters/add! :cursor-builds)
    (execution/check! (:execution-contract opts) :cursor-encode)
    (let [token
          (cursor/cursor->token
           (merge
            {:v 12
             :scope scope
             :edge (transform-edge-ids
                    #(do
                       (request-counters/add! :identity-conversions)
                       (backend/invoke adapter :internal-id->object %))
                    edge)}
            context)
           opts)]
      (execution/check! (:execution-contract opts) :cursor-encoded)
      token)))

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
    (execution/check! (:execution-contract opts) :cursor-decode)
    (let [decoded
          (try
            (cursor/token->authenticated-cursor token opts)
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              (invalid-cursor!
               "Invalid Relay cursor."
               {:reason (:reason (ex-data error))}
               error)))
          envelope (:cursor decoded)
          _ (execution/check! (:execution-contract opts) :cursor-decoded)]
      (when-not (and (contains? #{11 12} (:v envelope))
                     (map? (:edge envelope)))
        (invalid-cursor! "Invalid Relay cursor envelope."
                         {:reason :invalid-envelope}
                         nil))
      (assoc envelope
             :cursor/authenticated? (boolean (:authenticated? decoded))
             :cursor/expired? (boolean (:expired? decoded))
             :cursor/expired-at (:expired-at decoded)
             :cursor/scope-matches?
             (= ((if (= 11 (:v envelope))
                   legacy-cursor-scope
                   cursor-scope)
                 adapter opts operation query)
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

(defn- revision-code
  "Native-revision code in the numbering shared by one continuation decision:
  0 = the current selection, 1 = the cursor's different revision, and 2 = any
  other revision. The generated kernel still calls this scalar `graph`; the
  value now represents exact native revision identity, never ancestry."
  [current envelope context]
  (let [revision
        (secure/canonicalize (:native-revision context))]
    (cond
      (= revision
         (secure/canonicalize (:native-revision current)))
      0

      (= revision
         (secure/canonicalize (:native-revision envelope)))
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
        exact-decision
        (when exact
          {:graph (revision-code current envelope exact)
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
      :cursor-graph (revision-code current envelope envelope)
      :exact exact-decision})))

(defn- stale-context!
  [message reason]
  (throw
   (ex-info
    message
    {:type :eacl.pagination/stale-cursor
     :eacl/error :eacl.pagination/stale-cursor
     :reason reason})))

(defn- history-capable?
  [adapter]
  (or (backend/supports? adapter :snapshots :historical)
      (backend/supports? adapter :snapshots :exact)))

(defn- ensure-cursor-satisfies-request!
  [opts envelope]
  (when-let [floor (:cursor-freshness-floor opts)]
    (let [cursor-order (get-in envelope [:native-revision :revision])
          floor-order (:revision floor)]
      (when (or (not (integer? cursor-order))
                (not (integer? floor-order))
                (< cursor-order floor-order))
        (consistency/cursor-conflict!
         {:cursor-order-hint cursor-order
          :requested-order-hint floor-order}))))
  (when (= :at-exact-snapshot (:cursor-consistency-mode opts))
    (let [requested (:cursor-request-token opts)
          cursor-revision (:native-revision envelope)]
      (when (or (not= (:revision requested)
                      (:revision cursor-revision))
                (not= (:exact-locator requested)
                      (:exact-locator cursor-revision)))
        (consistency/cursor-conflict!
         {:cursor-revision (:revision cursor-revision)
          :requested-revision (:revision requested)
          :cursor-exact-locator (:exact-locator cursor-revision)
          :requested-exact-locator (:exact-locator requested)})))))

(defn- apply-continuation-decision!
  [adapter current envelope decision]
  (case decision
    :current adapter
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
     {:cursor-revision
      (get-in envelope [:native-revision :revision])
      :selected-revision
      (get-in current [:native-revision :revision])})

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
      "The cursor exact locator resolved to another native revision."
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

(defn- select-envelope-context
  [adapter opts envelope]
  (if-not envelope
    {:adapter adapter}
    (let [current (current-context adapter opts)
          initial
          (continuation-decision opts current envelope nil)]
      (case initial
        :snapshot-unavailable
        (do
          (ensure-cursor-satisfies-request! opts envelope)
          (when-not (history-capable? adapter)
            (stale-context!
             "The backend cannot reconstruct the cursor's changed proof."
             :dependency-proof-changed))
          (let [provider (:snapshot-provider opts)
                revision
                {:revision
                 (get-in envelope [:native-revision :revision])
                 :exact-locator
                 (get-in envelope [:native-revision :exact-locator])}
                _ (execution/check!
                   (:execution-contract opts)
                   :cursor-exact-selection)
                selected
                (when provider
                  (snapshot-provider/acquire!
                   provider :exact revision (:timeout-ms opts)))]
            (try
              (let [exact
                    (if selected
                      (snapshot-provider/adapter selected)
                      (backend/invoke
                       adapter :select-exact revision (:timeout-ms opts)))
                    _
                    (execution/check!
                     (:execution-contract opts)
                     :cursor-exact-selected)
                    _
                    (when-not exact
                      (throw
                       (ex-info
                        "The cursor's exact snapshot is no longer retained."
                        {:type :eacl.consistency/snapshot-expired
                         :eacl/error
                         :eacl.consistency/snapshot-expired})))
                    exact-context
                    (request-dependency-context exact opts)
                    decision
                    (continuation-decision
                     opts current envelope exact-context)]
                (apply-continuation-decision!
                 exact exact-context envelope decision)
                {:adapter exact
                 :selected-snapshot selected})
              (catch #?(:clj Throwable :cljs :default) error
                (when selected
                  (snapshot-provider/release! selected))
                (throw error)))))

        (do
          (apply-continuation-decision!
           adapter current envelope initial)
          {:adapter adapter})))))

(defn select-continuation-adapter
  "Uses an equal current proof or a verified exact historical fallback."
  [adapter opts operation query]
  (let [token (or (:after query) (:before query))
        envelope (decode-envelope adapter opts operation query token)
        context (select-envelope-context adapter opts envelope)]
    (if-let [selected (:selected-snapshot context)]
      (do
        (snapshot-provider/release! selected)
        (throw
         (ex-info
          "Provider-owned cursor recovery requires a resource-scoped page request."
          {:type :eacl/snapshot-scope-required
           :eacl/error :eacl/snapshot-scope-required})))
      (:adapter context))))

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

(defn- internalize-continued-edge
  "Internalizes a resume edge for an equal-proof continuation.

  A dependency-scoped equal proof may span transactions outside the query's
  relationship closure. If the boundary identity disappeared, continuing
  would drop the authenticated bound and create a hybrid walk, so reject it
  as stale rather than silently restarting."
  [adapter edge]
  (let [{:keys [edge missing?]}
        (internalize-tracked-edge adapter edge)]
    (if missing?
      (stale-context!
       "Relay cursor boundary identity is no longer available."
       :boundary-identity-changed)
      edge)))

(defn prepare-page-query
  "Authenticates each page token once, selects one safe snapshot, and
  internalizes its resume edge without rebase or restart states."
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
        page-context
        (select-envelope-context adapter opts primary-envelope)
        page-adapter (:adapter page-context)
        selected (:selected-snapshot page-context)]
    (try
      (let [prepared-query
            (reduce
             (fn [query [field envelope]]
               (if-not envelope
                 (assoc query field nil)
                 (do
                   (when-not (identical? envelope primary-envelope)
                     (validate-context! page-adapter opts envelope))
                   (assoc query field
                          (internalize-continued-edge
                           page-adapter (:edge envelope))))))
             query
             envelopes)]
        {:adapter page-adapter
         :selected-snapshot selected
         :query prepared-query})
      (catch #?(:clj Throwable :cljs :default) error
        (when selected
          (snapshot-provider/release! selected))
        (throw error)))))

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
    (-> page
        (update-in [:page-info :start-cursor] encode-edge)
        (update-in [:page-info :end-cursor] encode-edge))))

(def ^:private identity-key-version 1)

(defn- cached-internal-id->object
  [adapter opts internal-id]
  (request-counters/add! :identity-conversions)
  (execution/check! (:execution-contract opts) :render-identity)
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
    (execution/check! (:execution-contract opts) :rendered-identity)
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
          (cached-internal-id->object adapter opts id)))
       objects)))))

(defn externalize-relationship-page
  [adapter opts operation query page]
  (request-counters/add! :renderings)
  (externalize-page-cursors
   adapter opts operation query
   (update
    page
    :data
    (fn [relationships]
      (mapv
       (fn [{:keys [subject relation resource]}]
         (eacl/->Relationship
          (eacl/->SpiceObject
           (:type subject)
           (cached-internal-id->object adapter opts (:id subject))
           (:relation subject))
          relation
          (eacl/->SpiceObject
           (:type resource)
           (cached-internal-id->object adapter opts (:id resource))
           (:relation resource))))
       relationships)))))
