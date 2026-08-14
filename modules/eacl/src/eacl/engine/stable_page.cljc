(ns eacl.engine.stable-page
  "Result-edge pagination and exact continuation for stable discovery
  (adopt-stable-discovery-enumeration, section 6).

  - A public cursor is one bounded HMAC edge token binding the format
    version, order ABI, composite plan fingerprint, source lifecycle, exact
    basis, anchor (subject or resource), traversal direction, fixed page
    size, the boundary result's one-based ordinal and external identity,
    and optional expiry. Navigation mode (after/before) is request input.
  - Continuation is a latest-only in-process checkpoint per exact execution
    identity — complete history-free reducer state PLUS the undelivered
    lookahead segment and the constant-size boundary identity — replaced
    only on a strictly greater scalar transition ordinal; or governed
    deterministic replay validating the boundary before any page publishes.
  - Continuation on a basis other than the token's exact basis is rejected
    typed (:eacl.page/stale-cursor), or as
    :eacl.page/cursor-consistency-conflict when the request's consistency
    mode demanded fresher than the pinned basis. The certified
    full-read-scope dependency-proof path for current-only topologies is
    specified in the change and not yet implemented here.
  - Replay budgets surface as :eacl.page/resource-exhausted — distinct from
    stale-cursor — when they make a page unreachable."
  (:require [clojure.string :as string]
            [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]
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

(defn- execution-binding
  "Everything the cursor binds besides the boundary itself."
  [{:keys [adapter plan direction anchor subject-type page-size]}]
  {:v token-version
   :order-abi order-abi
   :fingerprint (:fingerprint plan)
   :lifecycle (backend/invoke adapter :source-lifecycle)
   :basis (backend/invoke adapter :native-revision)
   :direction direction
   :anchor anchor
   :subject-type subject-type
   :page-size page-size})

(defn- checkpoint-key [binding]
  (secure-format/canonical-digest token-domain (dissoc binding :basis)))

(defn edge-token
  "Mints the authenticated edge token for the boundary result at one-based
  `ordinal` with external `boundary` identity."
  [options binding ordinal boundary]
  (let [payload (-> binding
                    (assoc :ordinal ordinal :boundary boundary)
                    (cond-> (:token-ttl-seconds options)
                      (assoc :expires-at
                             (+ (long (/ (System/currentTimeMillis) 1000))
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
      (when (> (long (/ (System/currentTimeMillis) 1000)) (long expires-at))
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

(defn make-checkpoint-store
  "Bounded in-process latest-only checkpoint store. `:max-entries` bounds
  the identity count; `:max-entry-admissions` is the per-checkpoint weight
  cap (an overweight checkpoint is dropped without failing the request)."
  ([] (make-checkpoint-store {}))
  ([{:keys [max-entries max-entry-admissions]
     :or {max-entries 64 max-entry-admissions 1000000}}]
   (atom {:entries {} :order [] :max-entries max-entries
          :max-entry-admissions max-entry-admissions})))

(defn checkpoint-put!
  "Publishes synchronously; for one key only a strictly greater scalar
  transition ordinal replaces the retained checkpoint (nonregressing)."
  [store key checkpoint]
  (when store
    (swap! store
           (fn [{:keys [entries order max-entries max-entry-admissions]
                 :as state}]
             (let [existing (get entries key)]
               (cond
                 (> (count (:admitted (:state checkpoint)))
                    max-entry-admissions)
                 state ;; overweight: dropped, request unaffected

                 (and existing
                      (>= (:transitions (:state existing))
                          (:transitions (:state checkpoint))))
                 state ;; nonregressing: never replace with older progress

                 :else
                 (let [order (conj (vec (remove #{key} order)) key)
                       entries (assoc entries key checkpoint)
                       evict (when (> (count order) max-entries)
                               (first order))]
                   (cond-> (assoc state
                                  :entries entries
                                  :order order)
                     evict (-> (update :entries dissoc evict)
                               (update :order subvec 1))))))))
    nil))

(defn- checkpoint-hit
  "A checkpoint serves a continuation only when its delivered boundary
  ordinal and constant-size boundary identity both match the token."
  [store key ordinal boundary]
  (when store
    (let [{:keys [entries]} @store
          entry (get entries key)]
      (when (and entry
                 (= ordinal (:ordinal entry))
                 (= boundary (:boundary entry)))
        entry))))

;; ---------------------------------------------------------------------------
;; Page execution
;; ---------------------------------------------------------------------------

(defn- resolve-anchor-eid
  [{:keys [adapter direction anchor]}]
  (backend/invoke adapter :object-id->internal (second anchor)))

(defn- external-id
  [{:keys [adapter]} internal-id]
  (backend/invoke adapter :internal-id->object internal-id))

(defn- run-fresh
  [{:keys [adapter plan direction subject-type] :as options} anchor-eid target]
  (let [run-options (merge (select-keys options
                                        [:adapter :fetch-fn :plan
                                         :subject-type :cut-point!
                                         :physical-chunk-size :sidecar-cap
                                         :max-admissions :max-commands
                                         :max-transitions])
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
                                       :max-admissions :max-commands
                                       :max-transitions])
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

(defn- state-at-boundary
  "Reconstructs semantic state and pending lookahead at boundary `ordinal`:
  by checkpoint when the exact edge matches, else by governed deterministic
  replay that validates the boundary identity before continuing."
  [options binding store key anchor-eid ordinal boundary]
  (if-let [hit (checkpoint-hit store key ordinal boundary)]
    {:state (:state hit) :pending (:pending hit)}
    (let [replayed (guard-exhaustion
                    #(run-fresh options anchor-eid ordinal))
          results (:results replayed)]
      (when (or (< (count results) ordinal)
                (not= boundary (external-id options (peek results))))
        (page-error! :eacl.page/invalid-cursor
                     "Replay could not validate the cursor boundary."
                     {:ordinal ordinal :boundary boundary}))
      {:state (reducer/history-free replayed) :pending []})))

(defn- deliver-page
  "Runs from `state`+`pending` at absolute delivered ordinal `from` until
  `page-size` results plus one lookahead are available or the graph
  exhausts. Returns page internals."
  [options state pending from page-size]
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
  "Executes one stable-discovery page.

  Required options: :adapter :plan :direction (:forward|:reverse) :anchor
  ([type external-id] — the subject for :forward, the resource for
  :reverse) :subject-type :page-size. Optional: :after or :before (edge
  token), :checkpoints (store from make-checkpoint-store), :security-key,
  :token-ttl-seconds, :consistency, reducer budgets, :cut-point!.

  Returns {:data [external-ids] :page-info {...}} in canonical forward
  order for both navigation modes."
  [{:keys [plan direction anchor subject-type page-size after before
           checkpoints]
    :as options}]
  {:pre [(some? plan) (contains? #{:forward :reverse} direction)
         (vector? anchor) (keyword? subject-type)
         (pos-int? page-size) (not (and after before))]}
  (let [binding (execution-binding options)
        key (checkpoint-key binding)
        anchor-eid (resolve-anchor-eid options)
        empty-page {:data []
                    :page-info {:has-next-page? false
                                :has-previous-page? false
                                :start-cursor nil :end-cursor nil}}]
    (cond
      (nil? anchor-eid)
      empty-page

      before
      (let [payload (decode-token options binding before)
            ordinal (:ordinal payload)
            start (max 0 (- ordinal 1 page-size))
            replayed (guard-exhaustion
                      #(run-fresh options anchor-eid ordinal))
            results (:results replayed)
            _ (when (or (< (count results) ordinal)
                        (not= (:boundary payload)
                              (external-id options (peek results))))
                (page-error! :eacl.page/invalid-cursor
                             "Backward run could not validate the supplied edge."
                             {:ordinal ordinal}))
            window (subvec results start (dec ordinal))
            externals (mapv #(external-id options %) window)]
        {:data externals
         :page-info
         {:has-next-page? true
          :has-previous-page? (pos? start)
          :start-cursor (when (seq externals)
                          (edge-token options binding (inc start)
                                      (first externals)))
          :end-cursor (when (seq externals)
                        (edge-token options binding (dec ordinal)
                                    (peek externals)))}})

      :else
      (let [payload (when after (decode-token options binding after))
            ordinal (if payload (:ordinal payload) 0)
            boundary (:boundary payload)
            {:keys [state pending]}
            (if (pos? ordinal)
              (state-at-boundary options binding checkpoints key
                                 anchor-eid ordinal boundary)
              {:state nil :pending []})
            {:keys [page-ids lookahead end-state]}
            (if state
              (deliver-page options state pending ordinal page-size)
              (let [run (guard-exhaustion
                         #(run-fresh options anchor-eid (+ page-size 1)))]
                {:page-ids (vec (take page-size (:results run)))
                 :lookahead (vec (drop page-size (:results run)))
                 :end-state (reducer/history-free run)}))
            externals (mapv #(external-id options %) page-ids)
            delivered (+ ordinal (count externals))]
        (when (and (seq externals) checkpoints)
          (checkpoint-put!
           checkpoints key
           {:ordinal delivered
            :boundary (peek externals)
            :pending (vec lookahead)
            :state end-state}))
        {:data externals
         :page-info
         {:has-next-page? (boolean (seq lookahead))
          :has-previous-page? (pos? ordinal)
          :start-cursor (when (seq externals)
                          (edge-token options binding (inc ordinal)
                                      (first externals)))
          :end-cursor (when (seq externals)
                        (edge-token options binding delivered
                                    (peek externals)))}}))))
