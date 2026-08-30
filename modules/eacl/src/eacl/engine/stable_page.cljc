(ns eacl.engine.stable-page
  "Result-edge pagination and exact continuation for stable discovery
  (adopt-stable-discovery-enumeration, section 6).

  - The standalone API cursor is one bounded HMAC edge token binding the format
    version, order ABI, composite plan fingerprint, source lifecycle, exact
    basis, anchor (subject or resource), traversal direction, fixed page
    size, the boundary result's one-based ordinal and external identity,
    and optional expiry. Navigation mode (after/before) is request input.
  - The engine-facing path receives a latest-only checkpoint key built from
    request lineage, the complete plan frame, plan/order identity, traversal,
    anchor and page size. Equal frames exclude the changed-slice hazard, so
    history-free state plus lookahead may resume across native revisions.
  - The standalone token path remains exact-basis-bound and derives its own
    exact checkpoint key. It does not claim cross-basis frame equivalence.
  - Replay budgets surface as :eacl.page/resource-exhausted — distinct from
    stale-cursor — when they make a page unreachable."
  (:require [clojure.string :as string]
            [eacl.backend.v8 :as backend]
            [eacl.cache.standard-lru :as lru]
            [eacl.engine.physical :as physical]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.execution :as execution]
            [eacl.secure-format :as secure-format]))

(def token-version 1)
(def order-abi 1)
(def token-domain "eacl/stable-page/v1")
(def token-prefix "eacl_sd1.")

(defn- page-error!
  [error message data]
  (throw (ex-info message (assoc data :eacl/error error))))

;; ---------------------------------------------------------------------------
;; Cursor tokens
;; ---------------------------------------------------------------------------

(defonce ^:private default-key
  (delay (secure-format/warn-defaulted-token-key!)
         secure-format/default-root-key))

(defn- token-key [options]
  (secure-format/derive-key (or (:security-key options) @default-key)
                            token-domain))

(defn ^:no-doc execution-binding
  "Everything the standalone token binds besides the boundary itself.

  Its `:basis` is intentionally exact. Public EACL Relay cursors validate
  lineage and frame before calling `edge-page`; they do not use this token."
  [{:keys [adapter basis-identity plan direction anchor subject-type
           page-size]}]
  {:v token-version
   :order-abi order-abi
   :fingerprint (:fingerprint plan)
   :lifecycle (:source-lifecycle basis-identity)
   :basis (backend/invoke adapter :native-revision)
   :direction direction
   :anchor anchor
   :subject-type subject-type
   :page-size page-size})

(defn ^:no-doc checkpoint-key
  "Exact checkpoint identity for the standalone token API.

  The engine-facing caller supplies a frame key instead. Equal complete plan
  frames exclude the changed-slice hazard described here; this standalone
  path has no such proof and therefore keeps the exact basis in its binding."
  [binding]
  (secure-format/canonical-digest token-domain binding))

(defn edge-token
  "Mints the authenticated edge token for the boundary result at one-based
  `ordinal` with external `boundary` identity."
  [options binding ordinal boundary]
  (let [payload (-> binding
                    (assoc :ordinal ordinal :boundary boundary)
                    (cond-> (:token-ttl-seconds options)
                      (assoc :expires-at
                             (+ (quot #?(:clj (System/currentTimeMillis)
                                         :cljs (.now js/Date))
                                      1000)
                                (long (:token-ttl-seconds options))))))
        encoded (secure-format/encode-canonical payload)
        payload-bytes (secure-format/utf8-bytes encoded)
        tag (secure-format/hmac-sha-256 (token-key options) payload-bytes)]
    (str token-prefix
         (secure-format/b64url-encode payload-bytes)
         "."
         (secure-format/b64url-encode tag))))

(defn- decode-token
  "Verifies integrity, expiry, and every bound field against the current
  execution binding; returns the payload or throws typed."
  [options binding token]
  (when-not (and (string? token)
                 (string/starts-with? token token-prefix))
    (page-error! :eacl.page/invalid-cursor "Unrecognized cursor format." {}))
  (let [parts (string/split
               (subs token (count token-prefix)) #"\." 2)
        _ (when (not= 2 (count parts))
            (page-error! :eacl.page/invalid-cursor "Malformed cursor." {}))
        payload-bytes (try (secure-format/b64url-decode (first parts))
                           (catch #?(:clj Throwable :cljs :default) _
                             (page-error! :eacl.page/invalid-cursor
                                          "Malformed cursor payload." {})))
        tag (try (secure-format/b64url-decode (second parts))
                 (catch #?(:clj Throwable :cljs :default) _
                   (page-error! :eacl.page/invalid-cursor
                                "Malformed cursor tag." {})))
        expected (secure-format/hmac-sha-256 (token-key options)
                                             payload-bytes)
        _ (when-not (secure-format/secure-equal? expected tag)
            (page-error! :eacl.page/invalid-cursor
                         "Cursor failed authentication." {}))
        payload (secure-format/decode-canonical
                 (secure-format/bytes->utf8 payload-bytes))]
    (when-let [expires-at (:expires-at payload)]
      (when (> (quot #?(:clj (System/currentTimeMillis)
                        :cljs (.now js/Date))
                     1000)
               (long expires-at))
        (page-error! :eacl.page/expired-cursor "Cursor expired."
                     {:expires-at expires-at})))
    (doseq [field [:v :order-abi :fingerprint :lifecycle :direction
                   :anchor :subject-type :page-size]]
      (when (not= (get binding field) (get payload field))
        (page-error! :eacl.page/invalid-cursor
                     "Cursor is bound to an incompatible execution context."
                     {:field field
                      :expected (get binding field)
                      :cursor (get payload field)})))
    (when (not= (:basis binding) (:basis payload))
      (if (contains? #{:fully-consistent :at-least-as-fresh}
                     (:consistency options))
        (page-error! :eacl.page/cursor-consistency-conflict
                     "The request's consistency mode cannot be satisfied at the cursor's pinned basis."
                     {:cursor-basis (:basis payload)
                      :current-basis (:basis binding)})
        ;; Current-only continuation: the certified full-read-scope
        ;; dependency-proof path is not yet implemented, so a basis change
        ;; rejects explicitly rather than falling forward.
        (page-error! :eacl.page/stale-cursor
                     "The cursor's exact basis is no longer selectable."
                     {:cursor-basis (:basis payload)
                      :current-basis (:basis binding)})))
    payload))

;; ---------------------------------------------------------------------------
;; Latest-only checkpoint store (task 6.4)
;; ---------------------------------------------------------------------------

(defrecord CheckpointStore [storage max-entry-admissions])

(def ^:private default-max-entry-admissions 1000000)

(defn ^:no-doc checkpoint-store?
  [value]
  (instance? CheckpointStore value))

(defn make-checkpoint-store
  "Standard-LRU-backed latest-only checkpoint store. `:max-entries` bounds
  the identity count; `:max-entry-admissions` is a semantic per-checkpoint
  retention cap (an overweight checkpoint is dropped without failing the
  request)."
  ([] (make-checkpoint-store {}))
  ([{:keys [max-entries max-entry-admissions]
     :or {max-entries 64
          max-entry-admissions default-max-entry-admissions}}]
   (->CheckpointStore
    (lru/store max-entries)
    max-entry-admissions)))

(defn context-store?
  "A client-scoped continuation context (`eacl.continuation/private-context`):
  fn-map storage with its own bounds and eviction, distinct from the
  standalone checkpoint store."
  [store]
  (and (map? store)
       (fn? (:get store))
       (fn? (:hit! store))
       (fn? (:miss! store))
       (fn? (:put! store))))

(defn- checkpoint-progress
  [checkpoint]
  [(:ordinal checkpoint) (get-in checkpoint [:state :transitions])])

(defn- progress-newer?
  [[candidate-ordinal candidate-transitions]
   [resident-ordinal resident-transitions]]
  (or (> candidate-ordinal resident-ordinal)
      (and (= candidate-ordinal resident-ordinal)
           (> candidate-transitions resident-transitions))))

(defn- retention-eligible-checkpoint?
  [store checkpoint]
  (try
    (let [maximum (if (checkpoint-store? store)
                    (:max-entry-admissions store)
                    (get store :max-entry-admissions
                         default-max-entry-admissions))]
      (<= (count (get-in checkpoint [:state :admitted])) maximum))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn checkpoint-put!
  "Publishes synchronously; for one key only a later delivered boundary, or
  greater traversal progress at the same boundary, replaces the retained
  checkpoint (nonregressing)."
  [store key checkpoint]
  (cond
    (not (execution/cache-stage-available?))
    nil

    (not (retention-eligible-checkpoint? store checkpoint))
    nil

    (context-store? store)
    ;; The client context compares progress outside storage and closes the
    ;; concurrent older/newer race with expected-value LRU replacement.
    (try
      ((:put! store) key checkpoint)
      nil
      (catch #?(:clj Exception :cljs :default) _ nil))

    (checkpoint-store? store)
    (try
      (let [storage (:storage store)
            candidate-progress (checkpoint-progress checkpoint)]
        (loop []
          (let [{:keys [found? value]} (lru/peek-entry storage key)]
            (if found?
              (when (progress-newer?
                     candidate-progress (checkpoint-progress value))
                (when-not (lru/replace-if! storage key value checkpoint)
                  (recur)))
              (when-not (lru/put-if-absent! storage key checkpoint)
                (recur))))))
      nil
      (catch #?(:clj Exception :cljs :default) _ nil))))

(defn ^:no-doc checkpoint-hit
  "A checkpoint serves a continuation only when its delivered boundary
  ordinal and constant-size boundary identity both match the token."
  [store key ordinal boundary]
  (when (execution/cache-stage-available?)
    (try
      (when-let [entry
                 (cond
                   (context-store? store) ((:get store) key)
                   (checkpoint-store? store)
                   (let [{:keys [found? value]}
                         (lru/peek-entry (:storage store) key)]
                     (when found? value)))]
        (if (and (= ordinal (:ordinal entry))
                 (= boundary (:boundary entry)))
          (do
            ;; The caller already holds an authenticated immutable checkpoint.
            ;; A lost touch race or store failure affects only future retention.
            (try
              (if (context-store? store)
                ((:hit! store) key entry)
                (lru/hit-if-value! (:storage store) key entry))
              (catch #?(:clj Exception :cljs :default) _ nil))
            entry)
          (do
            (when (context-store? store)
              ((:miss! store) :boundary-mismatch))
            nil)))
      (catch #?(:clj Exception :cljs :default) _ nil))))

;; ---------------------------------------------------------------------------
;; Page execution
;; ---------------------------------------------------------------------------

(defn- run-fresh
  [{:keys [direction] :as options} anchor-eid target]
  (let [run-options (merge (select-keys options
                                        [:adapter :fetch-fn :plan
                                         :subject-type :cut-point!
                                         :physical-chunk-size :sidecar-cap
                                         :result-sink :result-window-size
                                         :max-admissions :max-commands
                                         :max-transitions :max-values :max-stack])
                           {:target target})]
    (case direction
      :forward (reducer/run-forward
                (assoc run-options :subject-eid anchor-eid))
      :reverse (reducer/run-reverse
                (assoc run-options :resource-eid anchor-eid)))))

(defn- run-resume
  [options checkpoint-state target]
  (reducer/resume (merge (select-keys options
                                      [:adapter :fetch-fn :plan
                                       :subject-type :cut-point!
                                       :physical-chunk-size :sidecar-cap
                                       :result-sink :result-window-size
                                       :max-admissions :max-commands
                                       :max-transitions :max-values :max-stack])
                         {:target target})
                  checkpoint-state))

(defn- guard-exhaustion
  "Reducer limit failures during continuation surface as the distinct typed
  resource-exhaustion result, never as a stale cursor."
  [thunk]
  (try
    (thunk)
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) error
      (if (= :eacl.reducer/limit-exceeded (:eacl/error (ex-data error)))
        (page-error! :eacl.page/resource-exhausted
                     "Continuation exceeded its governed replay budget."
                     (dissoc (ex-data error) :eacl/error))
        (throw error)))))

(defn- governed-replay
  "A replay (a checkpoint miss, a backward run, or a last-window run) runs
  under the service-edge replay ledger when the caller configured one
  (`:service-admission`), keyed by the continuation identity, and always
  under the exhaustion guard."
  [{:keys [service-admission checkpoint-key]} thunk]
  (physical/with-replay-admission
    service-admission
    (or checkpoint-key ::anonymous-replay)
    #(guard-exhaustion thunk)))

(defn- state-at-boundary
  "Reconstructs semantic state and pending lookahead at boundary `ordinal`:
  by checkpoint when the exact edge matches, else by governed deterministic
  replay that validates the boundary identity at the already accepted basis
  before continuing. Public cursor acceptance always precedes this lookup."
  [options store key anchor-eid ordinal boundary-eid]
  (if-let [hit (checkpoint-hit store key ordinal boundary-eid)]
    (do
      (when-let [stats reducer/*observer-stats*]
        (swap! stats update :continuation-hits (fnil inc 0)))
      {:state (:state hit) :pending (:pending hit)})
    (let [replayed (governed-replay
                    options
                    #(run-fresh (assoc options
                                       :result-sink :window
                                       :result-window-size 1)
                                anchor-eid ordinal))
          results (:results replayed)]
      (when (or (< (:discovered replayed) ordinal)
                (not= boundary-eid (peek results)))
        (page-error! :eacl.page/invalid-cursor
                     "Replay could not validate the cursor boundary."
                     {:ordinal ordinal}))
      {:state (reducer/history-free replayed) :pending []})))

(declare deliver-page deliver-raw-page)

(defn edge-page
  "Engine-facing pagination over internal-eid boundaries: `after`/`before`
  are {:ordinal n :eid e} edges (already authenticated by the caller's
  cursor layer against the same composite fingerprint, lineage and frame).
  Returns {:eids [..] :start-ordinal k :has-next? :has-previous?} in
  canonical forward order. A `:last-window?` request returns the final
  window of the exhausted sequence. When `:service-admission` names a
  service-edge admission, replays (checkpoint misses, backward runs and last
  windows) run under its replay ledger keyed by `:checkpoint-key`."
  [{:keys [plan direction anchor-eid subject-type page-size
           after before last-window? checkpoints checkpoint-key
           raw-candidates?]
    :as options}]
  {:pre [(some? plan) (contains? #{:forward :reverse} direction)
         (keyword? subject-type) (pos-int? page-size)
         (not (and after before))]}
  (cond
    (nil? anchor-eid)
    {:eids [] :start-ordinal 0 :has-next? false :has-previous? false}

    before
    (let [{:keys [ordinal eid]} before
          start (max 0 (- ordinal 1 page-size))
          replayed (governed-replay
                    options
                    #(run-fresh (assoc options
                                       :result-sink :window
                                       :result-window-size (inc page-size))
                                anchor-eid ordinal))
          results (:results replayed)]
      (when (or (< (:discovered replayed) ordinal)
                (not= eid (peek results)))
        (page-error! :eacl.page/invalid-cursor
                     "Backward run could not validate the supplied edge."
                     {:ordinal ordinal}))
      {:eids (pop results)
       :start-ordinal start
       :has-next? true
       :has-previous? (pos? start)})

    last-window?
    (let [run (governed-replay
               options
               #(run-fresh (assoc options
                                  :result-sink :window
                                  :result-window-size page-size)
                           anchor-eid
                           reducer/exhaustion-target))
          results (:results run)]
      {:eids results
       :start-ordinal (max 0 (- (:discovered run) (count results)))
       :has-next? false
       :has-previous? (> (:discovered run) (count results))})

    :else
    (let [ordinal (:ordinal after 0)
          {:keys [state pending]}
          (if (pos? ordinal)
            (state-at-boundary options checkpoints checkpoint-key
                               anchor-eid ordinal (:eid after))
            {:state nil :pending []})
          {:keys [page-ids lookahead end-state]}
          (if state
            (if raw-candidates?
              (deliver-raw-page options state pending page-size)
              (deliver-page options state pending page-size))
            (let [run (guard-exhaustion
                       #(run-fresh
                         options anchor-eid
                         (if raw-candidates? page-size (inc page-size))))]
              {:page-ids (vec (take page-size (:results run)))
               :lookahead (if raw-candidates?
                            []
                            (vec (drop page-size (:results run))))
               :end-state (reducer/history-free run)}))
          delivered (+ ordinal (count page-ids))]
      (when (and (seq page-ids) checkpoints checkpoint-key)
        (checkpoint-put!
         checkpoints checkpoint-key
         {:ordinal delivered
          :boundary (peek page-ids)
          :pending (vec lookahead)
          :state end-state}))
      {:eids page-ids
       :start-ordinal ordinal
       :has-next? (boolean (seq lookahead))
       :has-previous? (pos? ordinal)})))

(defn- deliver-raw-page
  "Continues a checkpoint by exactly `page-size` candidates, with no
  lookahead. Filtered lookup orchestration owns its sentinel and budget, so
  fetching an extra authorized candidate here would cross that boundary."
  [options state pending page-size]
  (let [pending (vec (take page-size pending))
        needed (- page-size (count pending))
        continued
        (when (pos? needed)
          (guard-exhaustion
           #(run-resume options state (+ (:discovered state) needed))))]
    {:page-ids (into pending (when continued (:results continued)))
     :lookahead []
     :end-state (if continued (reducer/history-free continued) state)}))

(defn- deliver-page
  "Runs from `state`+`pending` (whose scalar `:discovered` count is the
  absolute delivered ordinal) until `page-size` results plus one lookahead
  are available or the graph exhausts. Returns page internals."
  [options state pending page-size]
  (let [needed-fresh (- (inc page-size) (count pending))
        continued (when (pos? needed-fresh)
                    (guard-exhaustion
                     #(run-resume options state
                                  (+ (:discovered state) needed-fresh))))
        available (into (vec pending)
                        (when continued (:results continued)))
        page-ids (vec (take page-size available))
        lookahead (vec (take 1 (drop page-size available)))]
    {:page-ids page-ids
     :lookahead lookahead
     :end-state (if continued (reducer/history-free continued) state)}))

(defn page
  "Executes one stable-discovery page with self-contained authenticated
  tokens (the standalone API; the public EACL client authenticates edges
  through its own cursor envelope and calls `edge-page` directly).

  Returns {:data [external-ids] :page-info {...}} in canonical forward
  order for both navigation modes."
  [{:keys [adapter plan direction anchor subject-type page-size after before
           checkpoints]
    :as options}]
  {:pre [(some? plan) (contains? #{:forward :reverse} direction)
         (vector? anchor) (keyword? subject-type)
         (pos-int? page-size) (not (and after before))]}
  (let [binding (execution-binding options)
        key (checkpoint-key binding)
        anchor-eid (backend/invoke adapter :object-id->internal
                                   (second anchor))
        payload (when-let [token (or after before)]
                  (decode-token options binding token))
        boundary-eid (when payload
                       (backend/invoke (:adapter options)
                                       :object-id->internal
                                       (:boundary payload)))
        _ (when (and payload (nil? boundary-eid))
            (page-error! :eacl.page/invalid-cursor
                         "Cursor boundary identity is unknown at this basis."
                         {:ordinal (:ordinal payload)}))
        edge (when payload {:ordinal (:ordinal payload) :eid boundary-eid})
        result (edge-page (assoc options
                                 :anchor-eid anchor-eid
                                 :after (when after edge)
                                 :before (when before edge)
                                 :checkpoints checkpoints
                                 :checkpoint-key key))
        externals (mapv #(backend/invoke adapter :internal-id->object %)
                        (:eids result))
        start-ordinal (:start-ordinal result)]
    {:data externals
     :page-info
     {:has-next-page? (boolean (and (seq externals) (:has-next? result)))
      :has-previous-page? (boolean (and (seq externals)
                                        (:has-previous? result)))
      :start-cursor (when (seq externals)
                      (edge-token options binding (inc start-ordinal)
                                  (first externals)))
      :end-cursor (when (seq externals)
                    (edge-token options binding
                                (+ start-ordinal (count externals))
                                (peek externals)))}}))
