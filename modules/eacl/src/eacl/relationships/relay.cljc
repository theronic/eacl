(ns eacl.relationships.relay
  "Backend-neutral Relay windowing for already-filtered relationship values."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]))

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

(defn- items-proof
  [items]
  (secure/canonical-digest
   "eacl/cursor/relationship-items/v3"
   {:count (count items)
    ;; Digest each bounded relationship independently before committing the
    ;; ordered vector. A legal 10k-item page otherwise exceeds the secure
    ;; format's global entry bound even though no individual value is large.
    ;; Count plus ordered leaf digests preserves multiplicity and order.
    :leaf-digests
    (mapv #(secure/canonical-digest
            "eacl/cursor/relationship-item/v3"
            %)
          items)}))

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
      (when-not (and (= 9 (:v value))
                     (= :relationships (:kind value))
                     (integer? (:offset value))
                     (not (neg? (:offset value))))
        (page-error! "Invalid relationship cursor envelope."
                     {:reason :invalid-envelope}))
      (when-not (= (scope operation filters) (:scope value))
        (page-error! "Relationship cursor belongs to a different query."
                     {:reason :query-mismatch}))
      (doseq [field [:source-scope
                     :adapter-fingerprint
                     :identity-contract]]
        (when-not (= (secure/canonicalize
                      (get snapshot-context field))
                     (secure/canonicalize (get value field)))
          (page-error!
           "Relationship cursor execution identity changed."
           {:type :eacl.pagination/invalid-cursor
            :reason field})))
      value)))

(defn- exact-items
  [opts current-context envelope]
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
        (when-not
         (and
          (= (secure/canonicalize (:source-scope current-context))
             (secure/canonicalize (:source-scope exact-context)))
          (= (get-in envelope [:graph-head :graph-anchor])
             (get-in exact-context [:graph-head :graph-anchor]))
          (= (:items-proof envelope)
             (items-proof exact-items)))
         (throw
          (ex-info
           "The relationship cursor exact locator resolved to another graph."
           {:type :eacl.consistency/history-divergence
            :eacl/error :eacl.consistency/history-divergence})))
        {:snapshot-context exact-context
         :items exact-items}))))

(defn- select-items
  [opts operation filters snapshot-context items token]
  (if-let [envelope
           (decode-envelope
            opts operation filters snapshot-context token)]
    (cond
      (= (items-proof items) (:items-proof envelope))
      {:snapshot-context snapshot-context
       :items items
       :bound (:offset envelope)}

      (= :at-least-as-fresh
         (:cursor-consistency-mode opts))
      (consistency/cursor-conflict!
       {:cursor-graph-anchor
        (get-in envelope [:graph-head :graph-anchor])
        :selected-graph-anchor
        (get-in snapshot-context [:graph-head :graph-anchor])})

      :else
      (assoc (exact-items opts snapshot-context envelope)
             :bound (:offset envelope)))
    {:snapshot-context snapshot-context
     :items items
     :bound nil}))

(defn- encode-bound
  [opts operation filters snapshot-context items offset]
  (cursor/cursor->token
   (merge
    snapshot-context
    {:v 9
     :kind :relationships
     :scope (scope operation filters)
     :items-proof (items-proof items)
     :offset offset})
   opts))

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
        [start end]
        (case direction
          :asc (let [start (if bound (inc bound) 0)]
                 [start (min n (+ start size))])
          :desc (let [end (if bound (min bound n) n)]
                  [(max 0 (- end size)) end]))
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
      :has-next-page? (boolean (and any? (< end n)))
      :has-previous-page? (boolean (and any? (pos? start)))}}))
