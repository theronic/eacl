(ns eacl.relay
  "Portable opaque Relay cursor handling for synchronous v8 adapters."
  (:require [eacl.backend.v8 :as backend]
            [eacl.core :refer [spice-object]]
            [eacl.cursor :as cursor]))

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
    (cursor/cursor->token
     {:v 8
      :scope (cursor-scope operation query)
      :snapshot-id (backend/invoke adapter :snapshot-id)
      :edge (transform-edge-ids
             #(backend/invoke adapter :internal-id->object %)
             edge)}
     opts)))

(defn- invalid-cursor!
  [message data cause]
  (throw (ex-info message
                  (merge {:type :eacl.pagination/invalid-cursor
                          :eacl/error :eacl.pagination/invalid-cursor}
                         data)
                  cause)))

(defn- decode-page-edge
  [adapter opts operation query token]
  (when token
    (let [envelope
          (try
            (cursor/token->cursor token opts)
            (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
              (invalid-cursor!
               "Invalid Relay cursor."
               {:reason (:reason (ex-data error))}
               error)))]
      (when-not (and (= 8 (:v envelope))
                     (map? (:edge envelope)))
        (invalid-cursor! "Invalid Relay cursor envelope."
                         {:reason :invalid-envelope}
                         nil))
      (when-not (= (cursor-scope operation query) (:scope envelope))
        (invalid-cursor! "Relay cursor belongs to a different query."
                         {:reason :query-mismatch}
                         nil))
      (when-not (= (backend/invoke adapter :snapshot-id)
                   (:snapshot-id envelope))
        (invalid-cursor!
         "Relay cursor snapshot is no longer current."
         {:reason :snapshot-changed}
         nil))
      (transform-edge-ids
       #(backend/invoke adapter :object-id->internal %)
       (:edge envelope)))))

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
