(ns eacl.exploration.backend-physical-probe
  "Disposable Datahike JDBC/DynamoDB physical-read qualification.

  Each run creates a unique database, compares concurrent scans with a
  sequential oracle, counts backing reads by wrapping the backend's public read
  function, injects latency at that exact boundary, probes interior P/P+1
  behavior, and deletes the database in `finally`."
  (:require [datahike.api :as d]
            [konserve-jdbc.core :as jdbc-store]
            [konserve-dynamodb.core :as dynamo-store])
  (:import [java.sql SQLTransientConnectionException]
           [java.util UUID]
           [java.util.concurrent Callable Executors TimeUnit]))

(def ^:private tuple-attribute :probe/ordered-pair)
(def ^:private endpoint-ident :probe/endpoint)

(defn- common-config [store]
  {:store store
   :schema-flexibility :write
   :attribute-refs? true
   :keep-history? false
   :max-string-length 0
   :store-cache-size 1
   :search-cache-size 0
   :index-config {:diff-buf-size 256}
   :fuse-index-roots? true
   :commit-graph? false})

(defn- jdbc-config []
  (let [suffix (str (.replace (str (UUID/randomUUID)) "-" ""))]
    (common-config
     {:backend :jdbc
      :dbtype "h2"
      :dbname (str "mem:eacl_stable_discovery_" suffix
                   ";DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE")
      :user "sa"
      :password ""
      :table (str "eacl_probe_" suffix)
      :id (UUID/randomUUID)})))

(defn- dynamo-config []
  (let [suffix (subs (.replace (str (UUID/randomUUID)) "-" "") 0 20)]
    (common-config
     {:backend :dynamodb
      :endpoint "http://127.0.0.1:8000"
      :region "us-east-1"
      :table (str "eacl-stable-discovery-" suffix)
      :access-key "dummy"
      :secret "dummy"
      :consistent-read? true
      :read-capacity 100
      :write-capacity 100
      :id (UUID/randomUUID)})))

(defn- postgres-config []
  (let [suffix (str (.replace (str (UUID/randomUUID)) "-" ""))]
    (common-config
     {:backend :jdbc
      :dbtype "postgresql"
      :dbname "eaclprobe"
      :host "127.0.0.1"
      :port 19004
      :user "eaclprobe"
      :password "eaclprobe"
      :table (str "eacl_probe_" suffix)
      :id (UUID/randomUUID)})))

(defn- install-fixture! [conn total]
  (d/transact
   conn
   [{:db/ident tuple-attribute
     :db/valueType :db.type/tuple
     :db/tupleTypes [:db.type/keyword :db.type/long]
     :db/cardinality :db.cardinality/many
     :db/index true}
    {:db/ident endpoint-ident}])
  (let [endpoint (:db/id (d/entity (d/db conn) endpoint-ident))]
    (doseq [start (range 0 total 256)]
      (d/transact
       conn
       (mapv
        (fn [index]
          [:db/add endpoint tuple-attribute [:range (long index)]])
        (range start (min total (+ start 256))))))
    endpoint))

(defn- ordered-value-seq [db endpoint attr-id exclusive-bound]
  (->> (d/seek-datoms
        db
        {:index :eavt
         :components
         [endpoint tuple-attribute [:range (long exclusive-bound)]]})
       (take-while
        (fn [{:keys [e a v]}]
          (and (= endpoint e)
               (= attr-id a)
               (vector? v)
               (= 2 (count v))
               (= :range (first v)))))
       (map (comp long second :v))
       (filter #(< (long exclusive-bound) (long %)))))

(defn- ordered-values [db endpoint attr-id exclusive-bound limit]
  (->> (ordered-value-seq db endpoint attr-id exclusive-bound)
       (take limit)
       vec))

(defn- run-bounded! [width tasks]
  (let [executor (Executors/newFixedThreadPool width)]
    (try
      (let [futures
            (mapv
             (fn [task]
               (.submit
                executor
                ^Callable
                (reify Callable
                  (call [_] (task)))))
             tasks)]
        (mapv #(.get %) futures))
      (finally
        (.shutdown executor)
        (.awaitTermination executor 30 TimeUnit/SECONDS)))))

(defn- counted-jdbc [latency-ms f]
  (let [original jdbc-store/read-operation
        calls (atom 0)]
    (with-redefs
     [jdbc-store/read-operation
      (fn [& args]
        (swap! calls inc)
        (when (pos? latency-ms)
          (Thread/sleep latency-ms))
        (apply original args))]
      {:result (f) :reads @calls})))

(defn- counted-dynamo [latency-ms f]
  (let [original dynamo-store/get-item
        calls (atom 0)]
    (with-redefs
     [dynamo-store/get-item
      (fn [& args]
        (swap! calls inc)
        (when (pos? latency-ms)
          (Thread/sleep latency-ms))
        (apply original args))]
      {:result (f) :reads @calls})))

(defn- counted [backend latency-ms f]
  (case backend
    :jdbc (counted-jdbc latency-ms f)
    :dynamodb (counted-dynamo latency-ms f)))

(defn- with-fresh-snapshot [config f]
  (let [conn (d/connect config)]
    (try
      (let [db (d/db conn)
            endpoint (:db/id (d/entity db endpoint-ident))
            attr-id (:db/id (d/entity db tuple-attribute))]
        (f db endpoint attr-id))
      (finally
        (d/release conn)
        ;; konserve-jdbc release closes the pooled datasource but can leave the
        ;; closed value in its global pool. A later cold reconnect must not
        ;; reuse it. The production lifecycle defect is recorded separately.
        (when (= :jdbc (get-in config [:store :backend]))
          (reset! jdbc-store/pool nil))))))

(defn- scan-campaign [backend config descriptors oracle latency-ms]
  (mapv
   (fn [width]
     (with-fresh-snapshot
       config
       (fn [db endpoint attr-id]
         (let [started (System/nanoTime)
               {:keys [result reads]}
               (counted
                backend latency-ms
                (fn []
                  (run-bounded!
                   width
                   (mapv
                    (fn [{:keys [bound limit]}]
                      (fn []
                        (ordered-values
                         db endpoint attr-id bound limit)))
                    descriptors))))
               elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
           (when-not (= oracle result)
             (throw
              (ex-info
               "Concurrent backend scan diverged from sequential oracle."
               {:backend backend :width width})))
           {:width width
            :backing-reads reads
            :elapsed-ms elapsed-ms
            :exact? true}))))
   [1 2 4 8 16]))

(defn- boundary-events [backend config]
  (with-fresh-snapshot
    config
    (fn [db endpoint attr-id]
      (let [counter
            (case backend
              :jdbc
              (let [original jdbc-store/read-operation
                    calls (atom 0)]
                {:calls calls
                 :binding
                 [#'jdbc-store/read-operation
                  (fn [& args]
                    (swap! calls inc)
                    (apply original args))]})

              :dynamodb
              (let [original dynamo-store/get-item
                    calls (atom 0)]
                {:calls calls
                 :binding
                 [#'dynamo-store/get-item
                  (fn [& args]
                    (swap! calls inc)
                    (apply original args))]}))
            [target replacement] (:binding counter)]
        (with-redefs-fn
          {target replacement}
          (fn []
            (loop [remaining (seq (ordered-value-seq db endpoint attr-id -1))
                   previous 0
                   events []]
              (if-let [value (first remaining)]
                (let [calls @(:calls counter)]
                  (recur
                   (next remaining)
                   calls
                   (cond-> events
                     (> calls previous)
                     (conj {:value value
                            :previous previous
                            :calls calls
                            :delta (- calls previous)}))))
                events))))))))

(defn- measure-one [backend config bound limit]
  (with-fresh-snapshot
    config
    (fn [db endpoint attr-id]
      (counted
       backend 0
       #(ordered-values db endpoint attr-id bound limit)))))

(defn- boundary-lookahead [backend config events]
  (->> events
       (map :value)
       (filter #(>= % 2))
       (map #(- % 2))
       distinct
       (mapv
        (fn [bound]
          (let [p (measure-one backend config bound 1)
                p-plus-one (measure-one backend config bound 2)]
            {:bound bound
             :first-value (inc bound)
             :lookahead-value (+ bound 2)
             :p-reads (:reads p)
             :p-plus-one-reads (:reads p-plus-one)
             :extra-reads (- (:reads p-plus-one) (:reads p))})))))

(defn- failure-probe [backend config]
  (with-fresh-snapshot
    config
    (fn [db endpoint attr-id]
      (let [attempts (atom 0)
            outcome
            (try
              (case backend
                :jdbc
                (let [original jdbc-store/read-operation]
                  (with-redefs
                   [jdbc-store/read-operation
                    (fn [& args]
                      (if (= 1 (swap! attempts inc))
                        (throw
                         (SQLTransientConnectionException.
                          "injected transient JDBC read failure"))
                        (apply original args)))]
                    (ordered-values db endpoint attr-id -1 1)))

                :dynamodb
                (let [original dynamo-store/get-item]
                  (with-redefs
                   [dynamo-store/get-item
                    (fn [& args]
                      (if (= 1 (swap! attempts inc))
                        nil
                        (apply original args)))]
                    (ordered-values db endpoint attr-id -1 1))))
              {:status :unexpected-success}
              (catch Throwable error
                {:status :failed
                 :class (.getName (class error))
                 :message (.getMessage error)
                 :data (ex-data error)}))]
        (assoc outcome :attempts @attempts)))))

(defn run! [backend]
  (let [physical-backend (if (= backend :jdbc-postgres) :jdbc backend)
        config (case backend
                 :jdbc (jdbc-config)
                 :jdbc-postgres (postgres-config)
                 :dynamodb (dynamo-config))
        total 4096
        unique-descriptors
        (mapv
         (fn [index]
           {:bound (dec (* index 128)) :limit 64})
         (range 32))
        identical-descriptors
        (vec (repeat 32 {:bound -1 :limit 64}))]
    (try
      (d/create-database config)
      ;; The JDBC backend leaves the datasource used by create-database in its
      ;; global pool after closing it. Force the disposable probe to construct
      ;; a live pool for the subsequent connect. This is a measured lifecycle
      ;; workaround, not an application recommendation.
      (when (= physical-backend :jdbc)
        (reset! jdbc-store/pool nil))
      (let [seed-conn (d/connect config)]
        (try
          (install-fixture! seed-conn total)
          (finally
            (d/release seed-conn)
            (when (= physical-backend :jdbc)
              (reset! jdbc-store/pool nil)))))
      (let [oracle
            (with-fresh-snapshot
              config
              (fn [db endpoint attr-id]
                (mapv
                 (fn [{:keys [bound limit]}]
                   (ordered-values db endpoint attr-id bound limit))
                 unique-descriptors)))
            identical-oracle
            (with-fresh-snapshot
              config
              (fn [db endpoint attr-id]
                (mapv
                 (fn [{:keys [bound limit]}]
                   (ordered-values db endpoint attr-id bound limit))
                 identical-descriptors)))
            events (boundary-events physical-backend config)]
        {:backend backend
         :fixture {:total total
                   :store-cache-size 1
                   :unique-descriptors (count unique-descriptors)
                   :identical-descriptors (count identical-descriptors)}
         :unique-local
         (scan-campaign physical-backend config unique-descriptors oracle 0)
         :unique-latency-10ms
         (scan-campaign physical-backend config unique-descriptors oracle 10)
         :identical-latency-10ms
         (scan-campaign
          physical-backend config identical-descriptors identical-oracle 10)
         :boundary-events events
         :boundary-lookahead
         (boundary-lookahead physical-backend config events)
         :failure (failure-probe physical-backend config)
         :qualification
         (cond-> [:disposable-local
                  :captured-datahike-db
                  :sequential-oracle
                  :injected-read-latency]
           (= backend :jdbc) (conj :h2-local)
           (= backend :jdbc-postgres) (conj :postgres-15-local)
           (= backend :dynamodb) (conj :dynamodb-local
                                       :strongly-consistent-reads))})
      (finally
        (try
          (when (d/database-exists? config)
            (d/delete-database config))
          (catch Throwable cleanup-error
            (binding [*out* *err*]
              (println "cleanup failed:" (.getMessage cleanup-error)))))))))

(defn summarize [result]
  (let [campaign-summary
        (fn [campaign]
          (mapv #(select-keys % [:width :backing-reads :elapsed-ms :exact?])
                campaign))
        lookahead (:boundary-lookahead result)]
    (-> result
        (update :unique-local campaign-summary)
        (update :unique-latency-10ms campaign-summary)
        (update :identical-latency-10ms campaign-summary)
        (assoc :boundary-events
               {:count (count (:boundary-events result))
                :first (first (:boundary-events result))
                :last (last (:boundary-events result))})
        (assoc :boundary-lookahead
               {:count (count lookahead)
                :extra-read-frequencies
                (frequencies (map :extra-reads lookahead))
                :all-exactly-one-extra?
                (every? #(= 1 (:extra-reads %)) lookahead)}))))

(defn -main [& [backend-name]]
  (let [backend (keyword backend-name)]
    (when-not (#{:jdbc :jdbc-postgres :dynamodb} backend)
      (throw
       (ex-info "usage: clojure -M -m eacl.exploration.backend-physical-probe jdbc|jdbc-postgres|dynamodb"
                {:backend backend-name})))
    (prn (summarize (run! backend)))
    (shutdown-agents)))
