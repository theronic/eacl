(ns eacl.relationships.relay
  "Backend-neutral Relay windowing for already-filtered relationship values."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]
            [eacl.verified-kernel :as verified]))

(def ^:private default-page-size 1000)
(def ^:private max-page-size 10000)
(def ^:private page-keys #{:first :last :after :before :consistency})

(defn- page-error!
  [message data]
  (throw (ex-info message
                  (merge {:eacl/error :eacl.pagination/invalid-cursor}
                         data))))

(defn- scope
  [operation filters]
  [operation (apply dissoc filters page-keys)])

(defn- page-request
  [filters]
  (let [first? (contains? filters :first)
        last? (contains? filters :last)
        after? (contains? filters :after)
        before? (contains? filters :before)]
    (when (or (contains? filters :limit)
              (contains? filters :cursor))
      (page-error! "Relationship reads use :first/:after or :last/:before."
                   {:type :eacl.pagination/legacy-pagination}))
    (when (or (and first? last?)
              (and after? before?)
              (and after? (not first?))
              (and before? (not last?)))
      (page-error! "Invalid Relay relationship pagination arguments."
                   (select-keys filters [:first :last :after :before])))
    (let [direction (if last? :desc :asc)
          size (or (:first filters) (:last filters) default-page-size)
          token (if (= :asc direction)
                  (:after filters)
                  (:before filters))]
      (when-not (and (integer? size)
                     (pos? size)
                     (<= size max-page-size))
        (page-error! "Relationship page size is out of range."
                     {:size size :max max-page-size}))
      {:direction direction :size size :token token})))

(defn- snapshot-proof
  [snapshot-context]
  (secure/canonical-digest
   "eacl/cursor/relationship-exact-snapshot/v4"
   {:source-scope (:source-scope snapshot-context)
    :adapter-fingerprint (:adapter-fingerprint snapshot-context)
    :identity-contract (:identity-contract snapshot-context)
    :graph-anchor
    (get-in snapshot-context [:graph-head :graph-anchor])
    :exact-locator
    (get-in snapshot-context [:graph-head :exact-locator])}))

(defn- decode-envelope
  [opts operation filters snapshot-context token]
  (when token
    (let [value
          (try
            (cursor/token->cursor token opts)
            (catch #?(:clj Exception :cljs :default) error
              (if (= :eacl.pagination/expired-cursor
                     (:type (ex-data error)))
                (throw error)
                (page-error! "Invalid relationship cursor."
                             {:type :eacl.pagination/invalid-cursor
                              :reason (:reason (ex-data error))}))))]
      (when-not (and (= 10 (:v value))
                     (= :relationships (:kind value))
                     (integer? (:offset value))
                     (not (neg? (:offset value)))
                     (string? (:snapshot-proof value)))
        (page-error! "Invalid relationship cursor envelope."
                     {:reason :invalid-envelope}))
      (when-not (= (scope operation filters) (:scope value))
        (page-error! "Relationship cursor belongs to a different query."
                     {:reason :query-mismatch}))
      value)))

(def ^:private execution-identity-fields
  [:source-scope :adapter-fingerprint :identity-contract])

(defn- execution-identity
  [context]
  (secure/canonical-digest
   "eacl/cursor/relationship-execution-identity/v1"
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

(defn- exact-items
  [opts _current-context envelope]
  (let [adapter (:relationship-adapter opts)
        materialize (:relationship-items-for-adapter opts)]
    (when-not (and adapter materialize)
      (page-error!
       "Relationship cursor result proof changed and exact fallback is unavailable."
       {:type :eacl.pagination/stale-cursor
        :reason :items-proof-changed}))
    (let [exact
          (try
            (backend/invoke
             adapter
             :select-exact
             {:graph-anchor
              (get-in envelope [:graph-head :graph-anchor])
              :order-hint
              (get-in envelope [:graph-head :order-hint])
              :exact-locator
              (get-in envelope [:graph-head :exact-locator])}
             (:timeout-ms opts))
            (catch #?(:clj Exception :cljs :default) error
              (if (= :eacl/unsupported-capability
                     (:type (ex-data error)))
                nil
                (throw error))))]
      (when-not exact
        (throw
         (ex-info
          "The relationship cursor's exact snapshot is no longer retained."
          {:type :eacl.consistency/snapshot-expired
           :eacl/error :eacl.consistency/snapshot-expired})))
      (let [exact-context
            {:source-scope
             {:backend (backend/backend-id exact)
              :scope (backend/invoke exact :source-scope)}
             :graph-head (backend/invoke exact :graph-head)
             :adapter-fingerprint (backend/fingerprint exact)
             :identity-contract (backend/identity-contract exact)}
            exact-items (vec (materialize exact))]
        {:snapshot-context exact-context
         :items exact-items}))))

(defn- legacy-continuation-decision
  [opts current envelope exact]
  (cond
    (identity-mismatch (:snapshot-context current) envelope) :scope-mismatch
    (= (snapshot-proof (:snapshot-context current))
       (:snapshot-proof envelope))
    :current
    (= :at-least-as-fresh
       (:cursor-consistency-mode opts))
    :conflict
    (nil? exact) :snapshot-unavailable
    (or (identity-mismatch (:snapshot-context exact) envelope)
        (not= 0
              (graph-code
               (get-in envelope [:graph-head :graph-anchor])
               (get-in exact
                       [:snapshot-context :graph-head :graph-anchor])))
        (not= (:snapshot-proof envelope)
              (snapshot-proof (:snapshot-context exact))))
    :history-divergence
    :else :exact))

(defn- continuation-decision
  [opts current envelope exact]
  (verified/decide
   (:engine-selection opts)
   :cursor-continuation
   {:authenticated? true
    :scope-matches? true
    :expired? false
    :source (execution-identity (:snapshot-context current))
    :cursor-source (execution-identity envelope)
    :current-proof (snapshot-proof (:snapshot-context current))
    :cursor-proof (:snapshot-proof envelope)
    :mode
    (if (= :at-least-as-fresh
           (:cursor-consistency-mode opts))
      :at-least-as-fresh
      :minimize-latency)
    :cursor-graph 0
    :exact
    (when exact
      {:graph
       (graph-code
        (get-in envelope [:graph-head :graph-anchor])
        (get-in exact
                [:snapshot-context :graph-head :graph-anchor]))
       :source
       (execution-identity (:snapshot-context exact))
       :proof (snapshot-proof (:snapshot-context exact))})}
   #(legacy-continuation-decision opts current envelope exact)))

(defn- apply-continuation-decision!
  [opts current envelope exact decision]
  (case decision
    :current current
    :exact exact

    :scope-mismatch
    (page-error!
     "Relationship cursor execution identity changed."
     {:type :eacl.pagination/invalid-cursor
      :reason (or (identity-mismatch
                   (:snapshot-context current)
                   envelope)
                  :query-mismatch)})

    :conflict
    (consistency/cursor-conflict!
     {:cursor-graph-anchor
      (get-in envelope [:graph-head :graph-anchor])
      :selected-graph-anchor
      (get-in current
              [:snapshot-context :graph-head :graph-anchor])})

    :snapshot-unavailable
    (page-error!
     "Relationship cursor result proof changed and exact fallback is unavailable."
     {:type :eacl.pagination/stale-cursor
      :reason :items-proof-changed})

    :history-divergence
    (throw
     (ex-info
      "The relationship cursor exact locator resolved to another graph."
      {:type :eacl.consistency/history-divergence
       :eacl/error :eacl.consistency/history-divergence
       :cursor-graph-anchor
       (get-in envelope [:graph-head :graph-anchor])
       :selected-graph-anchor
       (get-in exact [:snapshot-context :graph-head :graph-anchor])
       :cursor-snapshot-proof (:snapshot-proof envelope)
       :selected-snapshot-proof
       (some-> exact :snapshot-context snapshot-proof)}))

    (page-error!
     "Generated relationship cursor decision rejected the envelope."
     {:type :eacl.pagination/invalid-cursor
      :reason decision})))

(defn- select-items
  [opts operation filters snapshot-context items token]
  (if-let [envelope
           (decode-envelope
            opts operation filters snapshot-context token)]
    (let [current {:snapshot-context snapshot-context
                   :items items}
          initial
          (continuation-decision opts current envelope nil)
          selected
          (if (= :snapshot-unavailable initial)
            (let [exact (exact-items opts snapshot-context envelope)
                  decision
                  (continuation-decision
                   opts current envelope exact)]
              (apply-continuation-decision!
               opts current envelope exact decision))
            (apply-continuation-decision!
             opts current envelope nil initial))]
      (assoc selected :bound (:offset envelope)))
    {:snapshot-context snapshot-context
     :items items
     :bound nil}))

(defn- encode-bound
  [opts operation filters snapshot-context _items offset]
  (cursor/cursor->token
   (merge
    snapshot-context
    {:v 10
     :kind :relationships
     :scope (scope operation filters)
     :snapshot-proof (snapshot-proof snapshot-context)
     :offset offset})
   opts))

(defn- page-presence
  [filters field decoded-bound? decoded-bound]
  (cond
    (not (contains? filters field)) :absent
    (nil? (get filters field)) :nil
    decoded-bound? decoded-bound
    :else (get filters field)))

(defn- raw-page-input
  [filters bound length]
  (let [after? (contains? filters :after)
        before? (contains? filters :before)]
    {:length length
     :request
     {:first (page-presence filters :first false nil)
      :last (page-presence filters :last false nil)
      :after (page-presence filters :after after? bound)
      :before (page-presence filters :before before? bound)
      :has-legacy-limit? (contains? filters :limit)
      :has-legacy-cursor? (contains? filters :cursor)}
     :default-size default-page-size
     :maximum-size max-page-size}))

(defn- legacy-page-decision
  [n direction size bound]
  (let [[start end]
        (case direction
          :asc
          (let [start (if (some? bound) (inc bound) 0)]
            [start (min n (+ start size))])

          :desc
          (let [end (if (some? bound) (min bound n) n)]
            [(max 0 (- end size)) end]))
        any? (< start end)]
    {:status :valid
     :direction direction
     :size size
     :start start
     :end end
     :has-next? (boolean (and any? (< end n)))
     :has-previous? (boolean (and any? (pos? start)))}))

(defn paginate
  "Applies a Relay window to a canonical vector of public relationships."
  [opts operation filters snapshot-context items]
  (let [current-items (vec items)
        {:keys [snapshot-context items bound]}
        (select-items
         opts
         operation
         filters
         snapshot-context
         current-items
         (:token (page-request filters)))
        n (count items)
        {:keys [direction size token]} (page-request filters)
        page-decision
        (verified/decide
         (:engine-selection opts)
         :relationship-page
         (raw-page-input filters bound n)
         #(legacy-page-decision n direction size bound))
        _ (when (= :invalid (:status page-decision))
            (page-error!
             "Generated relationship pagination rejected the request."
             {:type :eacl.pagination/invalid-cursor
              :reason (:reason page-decision)}))
        {:keys [start end has-next? has-previous?]} page-decision
        page-items (if (< start end)
                     (subvec items start end)
                     [])
        any? (boolean (seq page-items))
        start-offset (when any? start)
        end-offset (when any? (dec end))]
    {:data page-items
     :page-info
     {:start-cursor
      (when start-offset
        (encode-bound
         opts operation filters snapshot-context items start-offset))
     :end-cursor
      (when end-offset
        (encode-bound
         opts operation filters snapshot-context items end-offset))
      :has-next-page? (boolean (and any? has-next?))
      :has-previous-page? (boolean (and any? has-previous?))}}))
