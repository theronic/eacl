(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.consistency :as consistency]
            [eacl.core :refer [spice-object]]
            [eacl.cursor :as cursor]
            [eacl.secure-format :as secure]))

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

(defn dependency-context
  "Builds bounded cursor metadata from a freshly rederived complete closure."
  [adapter {:keys [schema-scope relation-ids]}]
  (let [dependency-scope
        {:schema schema-scope
         :relations (vec (sort relation-ids))}
        proof
        {:schema (backend/invoke adapter :schema-proof schema-scope)
         :relations
         (backend/invoke adapter :relation-proof relation-ids)}]
    {:source-scope
     {:backend (backend/backend-id adapter)
      :scope (backend/invoke adapter :source-scope)}
     :graph-head (backend/invoke adapter :graph-head)
     :adapter-fingerprint (backend/fingerprint adapter)
     :identity-contract (backend/identity-contract adapter)
     ;; Store digests, never a truncated proof. Continuation rederives the
     ;; complete closure and proof from its selected immutable snapshot.
     :dependency-scope-digest
     (secure/canonical-digest
      "eacl/cursor/dependency-scope/v3"
      dependency-scope)
     :proof-digest
     (secure/canonical-digest
      "eacl/cursor/dependency-proof/v3"
      proof)}))

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

    edge))

(defn- encode-page-edge
  [adapter opts operation query edge]
  (when edge
    (let [dependencies (:cursor-dependencies opts)]
      (when-not dependencies
        (throw
         (ex-info
          "Portable pagination requires a complete dependency closure."
          {:type :eacl.pagination/dependency-proof-unavailable
           :eacl/error
           :eacl.pagination/dependency-proof-unavailable})))
      (cursor/cursor->token
       (merge
        {:v 9
         :scope (cursor-scope operation query)
         :edge (transform-edge-ids
                #(backend/invoke adapter :internal-id->object %)
                edge)}
        (dependency-context adapter dependencies))
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
      (when-not (and (= 9 (:v envelope))
                     (map? (:edge envelope)))
        (invalid-cursor! "Invalid Relay cursor envelope."
                         {:reason :invalid-envelope}
                         nil))
      (when-not (= (cursor-scope operation query) (:scope envelope))
        (invalid-cursor! "Relay cursor belongs to a different query."
                         {:reason :query-mismatch}
                         nil))
      envelope)))

(defn- validate-context!
  [adapter opts envelope]
  (let [dependencies (:cursor-dependencies opts)
        current (when dependencies
                  (dependency-context adapter dependencies))]
    (when-not current
      (throw
       (ex-info
        "Portable continuation cannot rederive its dependencies."
        {:type :eacl.pagination/stale-cursor
         :eacl/error :eacl.pagination/stale-cursor
         :reason :dependency-proof-unavailable})))
    (doseq [field [:source-scope
                   :adapter-fingerprint
                   :identity-contract]]
      (when-not (= (secure/canonicalize (get current field))
                   (secure/canonicalize (get envelope field)))
        (invalid-cursor!
         "Relay cursor execution identity does not match."
         {:reason field}
         nil)))
    (when-not (= (:dependency-scope-digest current)
                 (:dependency-scope-digest envelope))
      (throw
       (ex-info
        "Relay cursor dependency closure changed."
        {:type :eacl.pagination/stale-cursor
         :eacl/error :eacl.pagination/stale-cursor
         :reason :dependency-scope-changed})))
    (when-not (= (:proof-digest current)
                 (:proof-digest envelope))
      (throw
       (ex-info
        "Relay cursor dependency proof changed."
        {:type :eacl.pagination/stale-cursor
         :eacl/error :eacl.pagination/stale-cursor
         :reason :dependency-proof-changed})))
    true))

(defn select-continuation-adapter
  "Uses an equal current proof, otherwise reconstructs the authenticated
  original graph when no newer at-least floor forbids fallback."
  [adapter opts operation query]
  (let [token (or (:after query) (:before query))
        envelope (decode-envelope opts operation query token)]
    (if-not envelope
      adapter
      (try
        (validate-context! adapter opts envelope)
        adapter
        (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core.ExceptionInfo)
               error
          (if-not (= :eacl.pagination/stale-cursor
                     (:type (ex-data error)))
            (throw error)
            (if (= :at-least-as-fresh
                   (:cursor-consistency-mode opts))
              (consistency/cursor-conflict!
               {:cursor-graph-anchor
                (get-in envelope [:graph-head :graph-anchor])
                :selected-graph-anchor
                (:graph-anchor
                 (backend/invoke adapter :graph-head))})
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
                (when-not (and
                           (= (backend/invoke adapter :source-scope)
                              (backend/invoke exact :source-scope))
                           (= (get-in envelope
                                      [:graph-head :graph-anchor])
                              (:graph-anchor
                               (backend/invoke exact :graph-head))))
                  (throw
                   (ex-info
                    "The cursor exact locator resolved to another graph."
                    {:type :eacl.consistency/history-divergence
                     :eacl/error
                     :eacl.consistency/history-divergence})))
                exact))))))))

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

(defn externalize-page
  [adapter opts operation query page]
  (-> page
      (update :data
              (fn [objects]
                (mapv
                 (fn [{:keys [type id]}]
                   (spice-object
                    type
                    (backend/invoke adapter :internal-id->object id)))
                 objects)))
      (update-in [:page-info :start-cursor]
                 #(encode-page-edge adapter opts operation query %))
      (update-in [:page-info :end-cursor]
                 #(encode-page-edge adapter opts operation query %))))
