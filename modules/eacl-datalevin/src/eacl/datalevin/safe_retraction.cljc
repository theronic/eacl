(ns eacl.datalevin.safe-retraction
  "Optional in-process Datalevin transaction function for safe entity
  retraction. Datalevin cannot persist arbitrary Clojure function values, so
  this adapter deliberately supports only direct `:db.fn/call` invocation."
  (:require [datalevin.core :as ds]
            [eacl.backend.writer :as backend-writer]
            [eacl.datalevin.db :as ddb]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]
            [eacl.request.counters :as request-counters]))

(def support
  (safe/support-descriptor
   {:backend :datalevin
    :mode :direct
    :reason :in-process-db-fn-call
    :requires-installation? false
    :transport-safe? false}))

(defn support-descriptor
  []
  support)

(defn- relation-generation-stamps
  [relation-ids]
  (mapv
   (fn [relation-id]
     [:db/add relation-id
      :eacl.datalevin/relation-generation
      :db/current-tx])
   (distinct relation-ids)))

(defn retract-entity-function
  "Datalevin target-only transaction function implementation."
  [db target]
  (safe/validate-target! target)
  (let [target-eid (if (nat-int? target) target (ds/entid db target))
        lookup-ref? (vector? target)]
    (if (and lookup-ref? (nil? target-eid))
      []
      (let [live? (and target-eid
                       (seq (ds/datoms db :eav target-eid)))
            closure
            (when live?
              (let [component-attrs
                    (safe/component-attributes (ds/schema db))]
                (safe/component-closure
                 target-eid
                 (fn [eid]
                   (into []
                         (comp
                          (filter #(contains? component-attrs (:a %)))
                          (map :v))
                         (ds/datoms db :eav eid))))))]
        (safe/ensure-unprotected! target-eid closure #(ds/entity db %))
        (let [plan
              (if live?
                (safe/combine-plans
                 (map (fn [eid]
                        (safe/plan-local-halves
                         eid
                         (mapv :v (ds/datoms db :eav eid
                                            storage/forward-attribute))
                         (mapv :v (ds/datoms db :eav eid
                                            storage/reverse-attribute))))
                      closure))
                (if target-eid
                  (safe/known-ghost-plan
                   (storage/relation-triples
                    (ddb/avet-datoms
                     db :eacl.relation/resource-type+relation-name+subject-type))
                   target-eid
                   (fn [attribute value]
                     (ddb/global-relationship-identity-datoms db attribute value)))
                  safe/empty-plan))]
          (into []
                (concat
                 (:peer-retractions plan)
                 (relation-generation-stamps (:relation-ids plan))
                 (when live?
                   [[:db.fn/retractEntity target-eid]]))))))))

(defn prepare!
  "Returns the direct invocation capability; no installation is needed."
  [_conn]
  {:installed? false :state :direct :support support})

(defn install!
  "Always rejects named installation. Datalevin's value encoding does not
  round-trip arbitrary Clojure functions; use `prepare!` and direct tx data."
  [_conn]
  (throw
   (ex-info
    "Datalevin cannot persist the EACL Clojure transaction function; use direct prepared invocation."
    {:type :eacl.safe-retraction/installation-unavailable
     :eacl/error :eacl.safe-retraction/installation-unavailable
     :backend :datalevin
     :support support
     :alternative
     {:prepare 'eacl.datalevin.safe-retraction/prepare!
      :tx-data 'eacl.datalevin.safe-retraction/retract-entity-tx-data}})))

(defn retract-entity-tx-data
  "Builds one in-process invocation. Call `prepare!` before use."
  [target]
  (safe/validate-target! target)
  [[:db.fn/call (fn [db wrapped-target]
                  (retract-entity-function db (first wrapped-target)))
    [target]]])

(defn direct-retract-entity-tx-data
  "Builds one in-process `:db.fn/call` invocation without installation.

  Call `prepare!` once for a new connection before submitting this data."
  [target]
  (retract-entity-tx-data target))

(defn transact-retract-entity!
  "Submits safe retraction through a Datalevin EACL writer.

  The writer supplies the per-open admission token and synchronously advances
  the external revision watermark after commit. Raw submission is valid only
  when expansion touches no protected EACL attribute."
  [client target]
  (let [writer (:writer client)]
    (when-not (and (backend-writer/writer? writer)
                   (= :datalevin (backend-writer/backend-id writer)))
      (throw
       (ex-info
        "Safe Datalevin retraction requires a writable Datalevin EACL client."
        {:type :eacl/invalid-client
         :eacl/error :eacl/invalid-client
         :backend :datalevin})))
    (let [{:keys [conn options api]} (backend-writer/state writer)
          report
          (loop [attempt 1]
            (let [outcome
                  (try
                    {:value
                     (backend-writer/invoke
                      writer :transact! conn
                      {:tx-data (retract-entity-tx-data target)})}
                    (catch #?(:clj Throwable :cljs :default) error
                      {:error error}))]
              (if-let [error (:error outcome)]
                (if (and (< attempt 8)
                         (= :eacl.datalevin/stale-connection-generation
                            (:type (ex-data error))))
                  (recur (inc attempt))
                  (throw error))
                (:value outcome))))
          native-revision
          ((:db-native-revision api) (:db-after report))]
      (request-counters/add! :writer-submissions)
      ((:after-commit! api) native-revision options)
      report)))
