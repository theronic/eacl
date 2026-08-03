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
  ([adapter opts operation query edge]
   (encode-page-edge
    adapter opts operation query edge
    (when edge (dependency-context adapter nil))))
  ([adapter opts operation query edge context]
   (when edge
     (cursor/cursor->token
      (merge
       {:v 10
        :scope (cursor-scope operation query)
        :edge (transform-edge-ids
               #(backend/invoke adapter :internal-id->object %)
               edge)}
       context)
      opts))))

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

(defn- legacy-continuation-decision
  [opts current envelope exact]
  (cond
    (identity-mismatch current envelope) :scope-mismatch
    (= (continuation-proof current)
       (continuation-proof envelope))
    :current
    (= :at-least-as-fresh (:cursor-consistency-mode opts)) :conflict
    (nil? exact) :snapshot-unavailable
    (or (identity-mismatch exact envelope)
        (not= (:dependency-scope-digest exact)
              (:dependency-scope-digest envelope))
        (not= (continuation-proof exact)
              (continuation-proof envelope))
        (not= 0
              (graph-code
               (get-in envelope [:graph-head :graph-anchor])
               (get-in exact [:graph-head :graph-anchor]))))
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
    :source (execution-identity current)
    :cursor-source (execution-identity envelope)
    :current-proof (continuation-proof current)
    :cursor-proof (continuation-proof envelope)
    :mode
    (if (= :at-least-as-fresh
           (:cursor-consistency-mode opts))
      :at-least-as-fresh
      :minimize-latency)
    :cursor-graph 0
    :exact
    (when exact
      {:graph
       (graph-code (get-in envelope
                           [:graph-head :graph-anchor])
       (get-in exact [:graph-head :graph-anchor]))
       :source (execution-identity exact)
       :proof (continuation-proof exact)})}
   #(legacy-continuation-decision opts current envelope exact)))

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

(defn- select-adapter-for-envelope
  [adapter opts envelope]
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
        adapter))))

(defn select-continuation-context
  "Selects the cursor-pinned adapter and decodes its physical edge once."
  [adapter opts operation query]
  (let [field (cond
                (contains? query :after) :after
                (contains? query :before) :before)
        token (get query field)
        envelope (decode-envelope opts operation query token)]
    (if-not envelope
      {:adapter adapter}
      (let [selected
            (select-adapter-for-envelope adapter opts envelope)]
        {:adapter selected
         :decoded-page-bound
         {:field field
          :token token
          :operation operation
          :scope (cursor-scope operation query)
          :edge
          (transform-edge-ids
           #(backend/invoke selected :object-id->internal %)
           (:edge envelope))}}))))

(defn select-continuation-adapter
  "Uses an equal current proof, otherwise reconstructs the authenticated
  original graph when no newer at-least floor forbids fallback."
  [adapter opts operation query]
  (:adapter
   (select-continuation-context adapter opts operation query)))

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
  (let [predecoded (:decoded-page-bound opts)
        internalize
        (fn [field token]
          (if (= {:field field
                  :token token
                  :operation operation
                  :scope (cursor-scope operation query)}
                 (select-keys
                  predecoded
                  [:field :token :operation :scope]))
            (:edge predecoded)
            (decode-page-edge
             adapter opts operation query token)))]
    (cond-> query
      (contains? query :after)
      (update :after #(internalize :after %))

      (contains? query :before)
      (update :before #(internalize :before %)))))

(defn- externalize-page-cursors
  [adapter opts operation query page]
  (let [start (get-in page [:page-info :start-cursor])
        end (get-in page [:page-info :end-cursor])
        context (when (or start end)
                  (dependency-context adapter nil))]
    (-> page
        (assoc-in
         [:page-info :start-cursor]
         (encode-page-edge
          adapter opts operation query start context))
        (assoc-in
         [:page-info :end-cursor]
         (encode-page-edge
          adapter opts operation query end context)))))

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
