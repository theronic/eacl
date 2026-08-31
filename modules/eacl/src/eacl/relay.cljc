(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.consistency :as consistency]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.cursor :as cursor]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.request.counters :as request-counters]
            [eacl.request.context :as request-context]
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

(def ^:dynamic *acl-cursor-recovery-source*
  "The source authority of the outer Acl read, scoped only around its
  transient Snapshot delegation.

  Public and directly constructed Snapshots never bind this value and cannot
  acquire another basis. The ordinary target marker is checked as well so a
  Snapshot evaluation nested in unrelated dynamic work still fails closed."
  nil)

(def ^:private cursor-transport-keys
  "Relay window controls do not define the authorized result set. They remain
  caller-controlled so one boundary cursor can support forward and backward
  navigation. Consistency, principal, permission, filters, and resource type
  remain part of the authenticated semantic scope."
  #{:first :last :after :before :cache? :populate-cache? :timeout-ms
    :cancellation-token})

(def cursor-emission-order-version
  "Version of the traversal emission order committed by Relay cursor digests.

  Pinning the version in the scope digest prevents a cursor minted under a
  different emission order from being accepted accidentally. The public
  order itself is the stable first-discovery order sealed into each plan's
  composite fingerprint (`eacl.engine.sealed-plan/order-contract`), which
  the `:stable-edge` boundary carries and `eacl.engine.v8` validates."
  2)

(def cursor-continuation-semantic-abi
  "Functional meaning of an authenticated continuation boundary.

  Change this value whenever edge identity, dependency-closure construction,
  or traversal boundary semantics change. Exact database identity alone does
  not make a boundary produced by a different evaluator ABI composable."
  {:version 2
   :envelope 13
   :edge-identity :external-object-id-v1
   :dependency-context :relation-closure-v1
   :emission-order cursor-emission-order-version})

(defn- require-portable-cursor-identity!
  [position value]
  (when-not (cache/cursor-cache-data? value)
    (throw
     (ex-info
      "Relay cursor identities must be metadata-free portable data."
      {:type :eacl.pagination/unsupported-cursor-identity
       :eacl/error :eacl.pagination/unsupported-cursor-identity
       :reason :nonportable-data
       :position position})))
  value)

(defn- require-canonical-cursor-object-id!
  [position value]
  (when-not (cache/canonical-cursor-identity? value)
    (throw
     (ex-info
      "Relay cursor object IDs must use their canonical portable representation."
      {:type :eacl.pagination/unsupported-cursor-identity
       :eacl/error :eacl.pagination/unsupported-cursor-identity
       :reason :noncanonical-data
       :position position})))
  value)

(def ^:private cursor-object-id-fields
  #{:id :subject/id :resource/id})

(defn ^:no-doc cursor-query-data?
  "True when a page query is portable and every external object ID is already
  in the exact representation produced by canonical cursor transport."
  [query]
  (and
   (cache/cursor-cache-data? query)
   (loop [pending [query]]
     (if-let [value (peek pending)]
       (let [remaining (pop pending)]
         (cond
           (map? value)
           (let [next-values
                 (reduce-kv
                  (fn [values field item]
                    (if (cursor-object-id-fields field)
                      (if (cache/canonical-cursor-identity? item)
                        values
                        (reduced nil))
                      (conj values item)))
                  remaining
                  value)]
             (and next-values (recur next-values)))

           (or (vector? value) (set? value))
           (recur (into remaining value))

           :else
           (recur remaining)))
       true))))

(defn- require-portable-cursor-query!
  [query]
  (when-not (cursor-query-data? query)
    (throw
     (ex-info
      "Relay cursor query identities must use canonical portable data."
      {:type :eacl.pagination/unsupported-cursor-identity
       :eacl/error :eacl.pagination/unsupported-cursor-identity
       :reason :noncanonical-data
       :position :query})))
  query)

(defn- normalized-cursor-query
  [query]
  (-> (apply dissoc query cursor-transport-keys)
      (assoc :consistency
             (select-keys
              (public-consistency/descriptor (:consistency query))
              [:mode]))))

(defn- plain-scope-object
  [object]
  (when object
    (select-keys object [:type :id :relation])))

(defn ^:no-doc plain-page-query
  "Replaces only EACL's known public object wrappers with ordinary maps.

  Custom identity values remain untouched so the cursor/transport record guard
  can reject record identities whose canonical bytes would lose their type."
  [query]
  (cond-> query
    (:subject query) (update :subject plain-scope-object)
    (:resource query) (update :resource plain-scope-object)
    (get-in query [:authorization :subject])
    (update-in [:authorization :subject] plain-scope-object)
    (get-in query [:resource/relationship :subject])
    (update-in [:resource/relationship :subject] plain-scope-object)
    (get-in query [:subject/relationship :resource])
    (update-in [:subject/relationship :resource] plain-scope-object)))

(defn- scoped-query-form
  [query]
  (plain-page-query (normalized-cursor-query query)))

(defn- cursor-scope
  "Digest of immutable operation/query/principal/configuration identity.

  Snapshot schema and relationship proof live in the separately authenticated
  dependency context. Keeping them out of this pre-recovery scope allows a
  changed current schema to reach proof comparison and exact fallback without
  weakening rejection of an actually changed query."
  [_adapter opts operation query]
  (let [scoped-query
        (require-portable-cursor-query! (scoped-query-form query))
        authorized-page?
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
         [cursor-continuation-semantic-abi
          operation
          execution-scope
         scoped-query]]
    (cursor/memoized-context!
     (or (:cursor-codec-cache opts)
         (:cursor-construction-cache opts))
     [:cursor-query-scope 9 scope-input]
     #(secure/canonical-digest
       "eacl/cursor/query-scope/v8"
       scope-input))))

(defn- local-lineage
  "Fail-closed identity for a raw, unmanaged adapter.

  Public Acl/Snapshot execution always supplies complete basis identity. A
  low-level engine caller has no lineage authority, so its cursor scope is
  pinned to this exact adapter value instead of consulting removed source
  operations."
  [adapter]
  {:source-scope
   {:backend (backend/backend-id adapter)
    :source-id {:unmanaged-basis (backend/invoke adapter :snapshot-id)}
    :branch nil}
   :source-lifecycle nil})

(def ^:private exact-snapshot-closure-digest
  (secure/canonical-digest
   "eacl/cursor/relation-closure/v1"
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
    adapter request-proof-frame relation-ids nil nil nil))
  ([adapter request-proof-frame relation-ids codec-cache]
   (build-dependency-context
    adapter request-proof-frame relation-ids codec-cache nil nil))
  ([adapter request-proof-frame relation-ids codec-cache basis-identity]
   (build-dependency-context
    adapter request-proof-frame relation-ids codec-cache basis-identity nil))
  ([adapter request-proof-frame relation-ids codec-cache basis-identity
    request-lineage]
   (let [native-revision (consistency/native-revision adapter)
         derived-lineage
         (if basis-identity
           (request-context/lineage-for-basis basis-identity)
           (local-lineage adapter))
         _
         (when (and request-lineage
                    (not= (secure/canonicalize request-lineage)
                          (secure/canonicalize derived-lineage)))
           (throw
            (ex-info
             "Request lineage differs from the selected immutable basis."
             {:type :eacl/backend-contract-violation
              :eacl/error :eacl/backend-contract-violation
              :request-lineage request-lineage
              :derived-lineage derived-lineage})))
         base
         (cond->
          {:lineage (or request-lineage derived-lineage)
           :native-revision native-revision
           :adapter-fingerprint (backend/fingerprint adapter)
           :identity-contract (backend/identity-contract adapter)}
           (some? (:speculative-id basis-identity))
           (assoc :speculative-id (:speculative-id basis-identity)))
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
         frame
         (or descriptor
             ;; An unavailable ordered-generation frame is exact-basis-bound.
             ;; This tagged internal frame prevents cross-basis equality while
             ;; retaining the one canonical [lineage frame closure] decision
             ;; input. It is not proof of cross-basis semantic equivalence.
             {:mode :exact-basis
              :snapshot-id snapshot-id
              :native-revision native-revision})
         closure-digest
         (if relation-ids
           (secure/canonical-digest
            "eacl/cursor/relation-closure/v1"
            relation-ids)
           exact-snapshot-closure-digest)
         context-key
         [:cursor-dependency-context
          2 base frame closure-digest]]
     (cursor/memoized-context!
      codec-cache
      context-key
      #(assoc base
              :frame frame
              :closure-digest closure-digest)))))

(defn dependency-context
  "Builds bounded continuation metadata for one immutable snapshot.

  Without `relation-ids` the proof pins the exact snapshot identity
  (relationship-index cursors keep this arity). With a sorted vector of
  relation-definition eids — the query's compiled dependency closure — the
  frame becomes the schema generation plus the scalar dependency frontier, so a
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
  [adapter opts]
  (if-let [resolve-relation-ids
           (:cursor-dependency-relation-ids-fn opts)]
    (resolve-relation-ids adapter (:snapshot-semantic-identity opts))
    (some-> (:cursor-dependency-relation-ids opts) force)))

(defn- request-dependency-context
  [adapter opts]
  (if-let [relation-ids (request-relation-ids adapter opts)]
    (let [candidate (or (:request-proof-frame opts)
                        (some-> (:request-proof-frame-delay opts) force))
          frame
          (if (and candidate
                   (identical? adapter (:adapter candidate)))
            candidate
            (proof-frame/request-frame
             adapter
             {:basis-identity (:snapshot-semantic-identity opts)}))]
      (build-dependency-context
       adapter frame relation-ids
       (or (:cursor-codec-cache opts)
           (:cursor-construction-cache opts))
       (:snapshot-semantic-identity opts)
       (:request-lineage opts)))
    (build-dependency-context
     adapter nil nil
     (or (:cursor-codec-cache opts)
         (:cursor-construction-cache opts))
     (:snapshot-semantic-identity opts)
     (:request-lineage opts))))

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
    (let [public-edge
          (require-portable-cursor-identity!
           :edge
           (transform-edge-ids
            #(do
               (request-counters/add! :identity-conversions)
               (require-canonical-cursor-object-id!
                :edge
                (backend/invoke adapter :internal-id->object %)))
            edge))
          token
          (cursor/cursor->token
           (merge
            {:v 13
             :scope scope
             :edge public-edge}
            context)
           opts)]
      (execution/check! (:execution-contract opts) :cursor-encoded)
      token)))

(defn- encode-public-page-edge
  "Signs one already-externalized unsigned edge without another backend read."
  [opts scope context edge]
  (when edge
    (request-counters/add! :cursor-builds)
    (execution/check! (:execution-contract opts) :cursor-encode)
    (let [token
          (cursor/cursor->token
           (merge {:v 13 :scope scope :edge edge} context)
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
      (when-not (and (= 13 (:v envelope))
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

(defn- execution-identity
  [context]
  (secure/canonical-digest
   "eacl/cursor/execution-identity/v1"
   (select-keys context
                [:lineage :speculative-id
                 :adapter-fingerprint :identity-contract])))

(defn- identity-mismatch
  [current envelope]
  (cond
    (not= (secure/canonicalize (get-in current [:lineage :source-scope]))
          (secure/canonicalize (get-in envelope [:lineage :source-scope])))
    :source-scope

    (not= (secure/canonicalize
           (get-in current [:lineage :source-lifecycle]))
          (secure/canonicalize
           (get-in envelope [:lineage :source-lifecycle])))
    :source-lifecycle

    (not= (secure/canonicalize (:speculative-id current))
          (secure/canonicalize (:speculative-id envelope)))
    :speculative-provenance

    (not= (secure/canonicalize (:adapter-fingerprint current))
          (secure/canonicalize (:adapter-fingerprint envelope)))
    :adapter-fingerprint

    (not= (secure/canonicalize (:identity-contract current))
          (secure/canonicalize (:identity-contract envelope)))
    :identity-contract))

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
  (secure/encode-canonical
   [(:lineage context)
    (:frame context)
    (:closure-digest context)]))

(defn- continuation-decision
  [opts current envelope]
  (let [source (execution-identity current)
        cursor-source (execution-identity envelope)
        current-proof (continuation-proof current)
        cursor-proof (continuation-proof envelope)]
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
      :exact nil})))

(defn- stale-context!
  [message reason]
  (throw
   (ex-info
    message
    {:type :eacl.pagination/stale-cursor
     :eacl/error :eacl.pagination/stale-cursor
     :reason reason})))

(defn- snapshot-cursor-conflict!
  []
  (throw
   (ex-info
    "The cursor belongs to another authorization basis."
    {:type :eacl.consistency/basis-conflict
     :eacl/error :eacl.consistency/basis-conflict
     :source :cursor})))

(defn- exact-selection-capable?
  [basis-source]
  (or (source/supports? basis-source :snapshots :historical)
      (source/supports? basis-source :snapshots :exact)))

(defn- exact-selection-context
  [adapter basis-identity]
  (cond->
   {:lineage (request-context/lineage-for-basis basis-identity)
    :native-revision
    (select-keys basis-identity [:revision :exact-locator])
    :adapter-fingerprint (backend/fingerprint adapter)
    :identity-contract (backend/identity-contract adapter)}
    (some? (:speculative-id basis-identity))
    (assoc :speculative-id (:speculative-id basis-identity))))

(defn- exact-selection-matches-cursor?
  [exact envelope]
  (and (= (execution-identity exact)
          (execution-identity envelope))
       (= (secure/canonicalize (:native-revision exact))
          (secure/canonicalize (:native-revision envelope)))))

(def ^:private dependency-context-fields
  [:lineage
   :native-revision
   :speculative-id
   :adapter-fingerprint
   :identity-contract
   :frame
   :closure-digest])

(defn- envelope-dependency-context
  [envelope]
  (select-keys envelope dependency-context-fields))

(defn- current-exact-execution-context
  "Builds only the independently observable exact execution identity.

  Unlike `current-context`, this does not discover relation dependencies or
  resolve a proof frame. It is sufficient to accept an already-authenticated
  cursor only when the selected immutable basis and external identity mapping
  are exactly the ones that originally minted it."
  [adapter opts]
  (when (and (backend/deterministic? adapter)
             (= :selected-internal/immutable-external-injective-v3
                (backend/identity-contract adapter)))
    (when-let [basis-identity (:snapshot-semantic-identity opts)]
      (let [current (exact-selection-context adapter basis-identity)
            derived-lineage (:lineage current)
            request-lineage (:request-lineage opts)]
        (when (and request-lineage
                   (not= request-lineage derived-lineage))
          (throw
           (ex-info
            "Request lineage differs from the selected immutable basis."
            {:type :eacl/backend-contract-violation
             :eacl/error :eacl/backend-contract-violation
             :request-lineage request-lineage
             :derived-lineage derived-lineage})))
        current))))

(defn- exact-execution-matches-cursor?
  [current envelope]
  (and current
       (= (select-keys current
                       [:lineage :speculative-id
                        :adapter-fingerprint :identity-contract
                        :native-revision])
          (select-keys envelope
                       [:lineage :speculative-id
                        :adapter-fingerprint :identity-contract
                        :native-revision]))))

(defn- retained-exact-context
  [adapter opts envelope]
  (let [current (current-exact-execution-context adapter opts)]
    (when (exact-execution-matches-cursor? current envelope)
      (merge current
             (select-keys envelope [:frame :closure-digest])))))

(defn- exact-continuation-decision
  [opts _current envelope]
  ;; Exact execution identity and immutable identity projection were compared
  ;; independently before this call. The kernel consumes only the equality
  ;; relation for source/proof, so fixed bounded witnesses avoid re-encoding
  ;; the same large canonical context twice on every continuation hit.
  (verified/decide
   (or (:decision-kernel opts)
       subproblem/*decision-kernel*)
   :cursor-continuation
   {:authenticated? (boolean (:cursor/authenticated? envelope))
    :scope-matches? (boolean (:cursor/scope-matches? envelope))
    :expired? (boolean (:cursor/expired? envelope))
    :source "exact-selected-execution"
    :cursor-source "exact-selected-execution"
    :current-proof "exact-selected-dependency"
    :cursor-proof "exact-selected-dependency"
    :cursor-graph 0
    :exact nil}))

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

    :snapshot-unavailable
    (stale-context!
     "Relay cursor dependency proof changed."
     (if (= (:closure-digest current)
            (:closure-digest envelope))
       :frame-changed
       :relation-closure-changed))

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
  ([adapter opts envelope]
   (validate-context! adapter opts envelope nil))
  ([adapter opts envelope known-context]
   (let [retained
         (when-not known-context
           (retained-exact-context adapter opts envelope))
         current (or known-context retained (current-context adapter opts))
         decision
         (if retained
           (exact-continuation-decision opts current envelope)
           (continuation-decision opts current envelope))]
     (apply-continuation-decision!
      adapter current envelope decision)
     (ensure-cursor-satisfies-request! opts envelope)
     true)))

(defn- select-envelope-context
  [adapter opts envelope]
  (if-not envelope
    {:adapter adapter}
    (let [retained (retained-exact-context adapter opts envelope)]
      (if retained
        (let [decision
              (exact-continuation-decision opts retained envelope)]
          ;; Authentication, expiry, query scope, and execution identity still
          ;; cross the ordinary continuation decision. Only the redundant
          ;; reconstruction of a proof for this same immutable basis is gone.
          (apply-continuation-decision!
           adapter retained envelope decision)
          (ensure-cursor-satisfies-request! opts envelope)
          {:adapter adapter
           :continuation-context retained})
        (let [current (current-context adapter opts)
              initial
              (continuation-decision opts current envelope)]
          (case initial
        :snapshot-unavailable
        (do
          (ensure-cursor-satisfies-request! opts envelope)
          ;; Target dispatch precedes backend capability dispatch. A Snapshot
          ;; never owns selection authority, so a proof mismatch is a basis
          ;; conflict even on a current-only backend.
          (when-not (and (= :acl (:authorization-target-kind opts))
                         *acl-cursor-recovery-source*)
            (snapshot-cursor-conflict!))
          (when-not (exact-selection-capable?
                     *acl-cursor-recovery-source*)
            (stale-context!
             "The backend cannot reconstruct the cursor's changed frame."
             :frame-changed))
          (let [source *acl-cursor-recovery-source*
                revision
                {:revision
                 (get-in envelope [:native-revision :revision])
                 :exact-locator
                 (get-in envelope [:native-revision :exact-locator])}
                _ (execution/check!
                   (:execution-contract opts)
                   :cursor-exact-selection)
                selected
                (source/acquire!
                 source :exact revision (:timeout-ms opts))]
            (try
              (let [exact
                    (source/adapter selected)
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
                    (exact-selection-context
                     exact (source/semantic-identity selected))]
                (when-not (exact-selection-matches-cursor?
                           exact-context envelope)
                  (throw
                   (ex-info
                    "The cursor exact locator resolved to another immutable basis."
                    {:type :eacl.consistency/history-divergence
                     :eacl/error :eacl.consistency/history-divergence
                     :cursor-native-revision (:native-revision envelope)
                     :selected-native-revision
                     (:native-revision exact-context)})))
                {:adapter exact
                 :selected-snapshot selected
                 ;; Exact selection proves that this is the cursor's original
                 ;; immutable basis. Preserve its authenticated context when
                 ;; minting the next page cursor: acceptance and re-minting do
                 ;; not read an unavailable historical proof frame.
                 :continuation-context
                 (envelope-dependency-context envelope)})
              (catch #?(:clj Throwable :cljs :default) error
                (when selected
                  (source/release! selected))
                (throw error)))))

        (do
          (apply-continuation-decision!
           adapter current envelope initial)
          (ensure-cursor-satisfies-request! opts envelope)
          {:adapter adapter
           :continuation-context current})))))))

(defn select-continuation-adapter
  "Uses an equal current proof or a verified exact historical fallback."
  [adapter opts operation query]
  (let [token (or (:after query) (:before query))
        envelope (decode-envelope adapter opts operation query token)
        context (select-envelope-context adapter opts envelope)]
    (if-let [selected (:selected-snapshot context)]
      (do
        (source/release! selected)
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

(defn internalize-prepared-page-query
  "Internalizes authenticated external boundary edges after a cache miss.

  `prepare-page-query` has already authenticated, scope-checked, and selected
  the exact adapter for these edges. Deferring this identity lookup lets an
  exact transport-page hit return without touching backend identity storage."
  [adapter query]
  (reduce
   (fn [query field]
     (if-let [edge (get query field)]
       (assoc query field (internalize-continued-edge adapter edge))
       query))
   query
   [:after :before]))

(defn prepare-page-query
  "Authenticates each page token once, selects one safe snapshot, and
  either internalizes its edge or retains the authenticated external edge for
  a rendered-cache lookup."
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
                     (validate-context!
                      page-adapter opts envelope
                      (:continuation-context page-context)))
                   (assoc query field (:edge envelope)))))
             query
             envelopes)]
        {:adapter page-adapter
         :selected-snapshot selected
         :continuation-context (:continuation-context page-context)
         ;; A token can carry an expiry even when the receiving client's
         ;; current minting policy has no TTL. Such a request must never become
         ;; a pre-decode transport hit after that authenticated input expires.
         :expiring-cursor-input?
         (boolean
          (some (fn [[_ envelope]]
                  (some? (:cursor/expired-at envelope)))
                envelopes))
         :query
         (if (:defer-cursor-edge-internalization? opts)
           prepared-query
           (internalize-prepared-page-query
            page-adapter prepared-query))
         :deferred-cursor-edge-internalization?
         (boolean (:defer-cursor-edge-internalization? opts))})
      (catch #?(:clj Throwable :cljs :default) error
        (when selected
          (source/release! selected))
        (throw error)))))

(defn- decode-page-edge
  [adapter opts operation query token]
  (when-let [envelope
             (decode-envelope adapter opts operation query token)]
    (validate-context! adapter opts envelope)
    (internalize-continued-edge adapter (:edge envelope))))

(defn internalize-page-query
  [adapter opts operation query]
  (cond-> query
    (contains? query :after)
    (update :after #(decode-page-edge adapter opts operation query %))

    (contains? query :before)
    (update :before #(decode-page-edge adapter opts operation query %))))

(defn- externalize-page-cursors
  [adapter opts operation query page]
  (let [context
        (delay
          (or (::retained-cursor-dependency-context opts)
              (:cursor-dependency-context opts)
              (request-dependency-context adapter opts)))
        scope
        (delay
          (or (::retained-cursor-scope opts)
              (cursor-scope adapter opts operation query)))
        encode-edge
        (fn [edge]
          (when edge
            (if (::public-cursor-edges? opts)
              (encode-public-page-edge opts @scope @context edge)
              (encode-page-edge
               adapter opts @scope @context edge))))]
    (-> page
        (update-in [:page-info :start-cursor] encode-edge)
        (update-in [:page-info :end-cursor] encode-edge))))

(defn- internal-id->object
  [adapter opts internal-id]
  (request-counters/add! :identity-conversions)
  (execution/check! (:execution-contract opts) :render-identity)
  (let [external-id (backend/invoke adapter :internal-id->object internal-id)]
    (execution/check! (:execution-contract opts) :rendered-identity)
    external-id))

(defn- resolve-external-identities!
  [adapter opts operation internal-ids]
  (let [resolved
        (mapv
         (fn [internal-id]
            [internal-id
            (internal-id->object adapter opts internal-id)])
         (distinct internal-ids))
        missing
        (into [] (keep (fn [[internal-id external-id]]
                         (when (nil? external-id) internal-id)))
              resolved)]
    (when (seq missing)
      (throw
       (ex-info
        (str
         "Authorization results reference objects whose external identities "
         "are absent. Diagnose the backend with dangling-relationship-report; "
         "repair the relationships with delete-relationships!, and use "
         "delete-object! for future object removal.")
        {:type :eacl/unresolvable-object
         :eacl/error :eacl/unresolvable-object
         :operation operation
         :backend (backend/backend-id adapter)
         :historical? (= :as-of (backend/invoke adapter :basis-kind))
         :entity-ids missing})))
    (into {} resolved)))

(defn externalize-page
  [adapter opts operation query page]
  (let [objects (:data page)
        identities
        (resolve-external-identities!
         adapter opts operation (map :id objects))
        page-info
        (reduce
         (fn [page-info field]
           (if-let [edge (get page-info field)]
             (assoc
              page-info field
              (require-portable-cursor-identity!
               :edge
               (transform-edge-ids
                (fn [internal-id]
                  (require-canonical-cursor-object-id!
                   :edge
                   (if (contains? identities internal-id)
                     (get identities internal-id)
                     (internal-id->object adapter opts internal-id))))
                edge)))
             page-info))
         (:page-info page)
         [:start-cursor :end-cursor])
        cursor-edge?
        (boolean
         (or (:start-cursor page-info)
             (:end-cursor page-info)))
        context
        (when cursor-edge?
          (or (:cursor-dependency-context opts)
              (request-dependency-context adapter opts)))
        scope
        (when cursor-edge?
          (cursor-scope adapter opts operation query))
        public-page
        (externalize-page-cursors
         adapter
         (assoc opts
                ::retained-cursor-dependency-context
                context
                ::retained-cursor-scope
                scope
                ::public-cursor-edges? true)
         operation query
         {:data
          (mapv
           (fn [{:keys [type id]}]
             (spice-object type (get identities id)))
           objects)
          :page-info page-info})]
    (execution/check! (:execution-contract opts) :rendered-page-return)
    public-page))

(defn externalize-relationship-page
  [adapter opts operation query page]
  (request-counters/add! :renderings)
  (let [relationships (:data page)
        identities
        (resolve-external-identities!
         adapter opts operation
         (mapcat
          (fn [{:keys [subject resource]}]
            [(:id subject) (:id resource)])
          relationships))]
    (externalize-page-cursors
     adapter opts operation query
     (assoc
      page
      :data
      (mapv
       (fn [{:keys [subject relation resource]}]
         (eacl/->Relationship
          (eacl/->SpiceObject
           (:type subject)
           (get identities (:id subject))
           (:relation subject))
          relation
          (eacl/->SpiceObject
           (:type resource)
           (get identities (:id resource))
           (:relation resource))))
       relationships)))))
