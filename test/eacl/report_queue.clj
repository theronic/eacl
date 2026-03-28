(ns eacl.report-queue
  (:require [datomic.api :as d]
            [clojure.test :as t :refer [deftest testing is]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log]))

(defn missing-entity?
  "Returns true if no :eavt datoms for this eid.
  Can be used to detect new entities or fully retracted entities.

  impl. of empty? does (not (seq coll))."
  [db eid]
  (empty? (d/datoms db :eavt eid)))

(defn make-managed-listener
  "Returns {:keys [stop-fn listener]}."
  [conn handler on-stop timeout-ms]                         ; todo timeout.
  (let [report-queue    (d/tx-report-queue conn)
        !stop-signal    (atom false)
        stop-fn         #(reset! !stop-signal true)
        listener-future (future
                          (try
                            (loop []
                              (if @!stop-signal
                                (do
                                  (log/debug "Stopping managed tx-report-queue listener.")
                                  (on-stop))
                                (let [timeout-chan (a/timeout timeout-ms)
                                      report-chan  (a/thread (.take report-queue))]
                                  (a/alt!!
                                    report-chan ([report]
                                                 (when report ; can be nil?
                                                   (try
                                                     (handler report)
                                                     (catch Exception ex
                                                       (log/error "Error processing tx-report:" ex))))
                                                 (recur))
                                    ; timeout is expected.
                                    timeout-chan (recur)))))
                            (catch InterruptedException ex
                              (log/warn "Listener interrupted: " (.getMessage ex)))
                            (catch Exception ex
                              (log/warn "Unexpected error in listener: " (.getMessage ex)))))]
    {:listener-future listener-future
     :stop-fn         stop-fn}))

(with-mem-conn [conn [{:db/ident       :server/name
                       :db/valueType   :db.type/string
                       :db/cardinality :db.cardinality/one}

                      {:db/ident       :server/ram-mib
                       :db/valueType   :db.type/long
                       :db/cardinality :db.cardinality/one}]]
  (let [!stopped? (promise)
        {:keys [stop-fn listener]} (make-managed-listener
                                     conn
                                     (fn [{:as report :keys [tx-data db-before db-after]}]
                                       (let [eid-set              (set (map :e tx-data))

                                             {:as         grouped-by-added
                                              retractions false
                                              additions   true} (group-by :added tx-data)

                                             ; could be group-by.
                                             retracted-eids       (map :e retractions)
                                             addition-eids        (map :e additions)

                                             new-entity-eids      (set (filter (partial missing-entity? db-before) addition-eids))
                                             updated-eids         (set (remove (partial missing-entity? db-before) addition-eids)) ; can be optimised.
                                             ; and here we can also inspect changed.
                                             fully-retracted-eids (set (filter (partial missing-entity? db-after) retracted-eids))]

                                         ; If you want to loop over datoms:
                                         ;(doall
                                         ;  (for [datom tx-data]
                                         ;    (log/info "datom:" (pr-str datom))))

                                         ; We can use this to inform our projection layer and no diffing would be required.
                                         ; and no diffing will be required.

                                         (log/info "Created:" new-entity-eids)
                                         (log/info "Updated:" updated-eids) ; todo check logic here.
                                         (log/info "Deleted:" fully-retracted-eids)
                                         (log/info "Fully retracted entities:" fully-retracted-eids)))
                                     ;(log/info "tx-data: " tx-data))
                                     (fn [] (deliver !stopped? true))
                                     5000)]

    @(d/transact conn [{:db/ident    :server1
                        :server/name "Server 1"
                        :server/ram-mib 8}

                       {:db/ident    :server2
                        :server/name "Server 2"
                        :server/ram-mib 8}

                       {:db/ident    :server3
                        :server/name "Server 3"}])

    @(d/transact conn [[:db/retractEntity :server1]
                       {:db/ident :server2 :server/ram-mib 16}
                       {:db/ident :server3 :server/ram-mib 32}])

    (Thread/sleep 500) ; wait for report-queue listener to run.

    ; we need a sleep here?
    (stop-fn)
    ; wait for listener to stop.
    @!stopped?))