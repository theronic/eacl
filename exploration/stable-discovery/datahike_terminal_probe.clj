(ns eacl.exploration.datahike-terminal-probe
  "Disposable direct-S3/MinIO measurement of terminal scan strategies.

  The store UUID is unique per run and is deleted in `finally`. This probe
  compares demand-lazy `P` plus a later empty scan with eager `P+1` exhaustion
  detection. It does not modify production or use AWS."
  (:refer-clojure :exclude [run!])
  (:require [datahike.api :as d]
            [eacl-datahike-demo.config :as demo-config]
            [konserve-s3.core :as konserve-s3]))

(def ^:private tuple-attribute :probe/ordered-pair)
(def ^:private endpoint-ident :probe/endpoint)

(defn- runtime-config
  [{:keys [endpoint bucket store-id cache-size]}]
  (let [uri (java.net.URI. endpoint)]
    (merge
     demo-config/default-config
     {:store-backend :s3
      :store-id store-id
      :s3-bucket bucket
      :s3-region "auto"
      :s3-endpoint-override
      {:protocol (keyword (.getScheme uri))
       :hostname (.getHost uri)
       :port (.getPort uri)}
      :s3-path-style-access? true
      :s3-access-key "minioadmin"
      :s3-secret "minioadmin123"
      :datahike-store-cache-size cache-size
      :datahike-search-cache-size 0
      :security-key "0123456789abcdef0123456789abcdef"})))

(defn- install-fixture!
  [conn total]
  (d/transact
   conn
   [{:db/ident tuple-attribute
     :db/valueType :db.type/tuple
     :db/tupleTypes [:db.type/keyword :db.type/long]
     :db/cardinality :db.cardinality/many
     :db/index true}
    {:db/ident endpoint-ident}])
  (let [endpoint (:db/id (d/entity (d/db conn) endpoint-ident))]
    (doseq [start (range 0 total 512)]
      (d/transact
       conn
       (mapv
        (fn [index]
          [:db/add endpoint tuple-attribute [:range (long index)]])
        (range start (min total (+ start 512))))))
    endpoint))

(defn- ordered-values
  [db endpoint exclusive-bound limit]
  (->> (d/seek-datoms
        db
        {:index :eavt
         :components
         [endpoint tuple-attribute [:range (long exclusive-bound)]]})
       (take-while
        (fn [{:keys [e a v]}]
          (and (= endpoint e)
               (or (= tuple-attribute a)
                   (= (:db/id (d/entity db tuple-attribute)) a))
               (vector? v)
               (= :range (first v)))))
       (map (comp long second :v))
       (filter #(< (long exclusive-bound) (long %)))
       (take limit)
       vec))

(defn- ordered-value-seq
  [db endpoint exclusive-bound]
  (->> (d/seek-datoms
        db
        {:index :eavt
         :components
         [endpoint tuple-attribute [:range (long exclusive-bound)]]})
       (take-while
        (fn [{:keys [e a v]}]
          (and (= endpoint e)
               (or (= tuple-attribute a)
                   (= (:db/id (d/entity db tuple-attribute)) a))
               (vector? v)
               (= :range (first v)))))
       (map (comp long second :v))
       (filter #(< (long exclusive-bound) (long %)))))

(defn- strategy-p-only
  [db endpoint start-bound width]
  {:values (ordered-values db endpoint start-bound width)})

(defn- strategy-p-then-terminal
  [db endpoint start-bound width]
  (let [values (ordered-values db endpoint start-bound width)
        terminal (ordered-values db endpoint (peek values) width)]
    {:values values :terminal terminal}))

(defn- strategy-p-plus-one
  [db endpoint start-bound width]
  (let [lookahead (ordered-values db endpoint start-bound (inc width))]
    {:values (vec (take width lookahead))
     :terminal? (<= (count lookahead) width)
     :lookahead-count (count lookahead)}))

(defn- measure-one!
  [database-config endpoint start-bound width strategy]
  (let [conn (d/connect database-config)]
    (try
      (let [db (d/db conn)
            started (System/nanoTime)
            {result :result stats :stats}
            (konserve-s3/with-global-io-stats
              (case strategy
                :p-only
                (strategy-p-only db endpoint start-bound width)

                :p-then-terminal
                (strategy-p-then-terminal db endpoint start-bound width)

                :p-plus-one
                (strategy-p-plus-one db endpoint start-bound width)))
            elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)]
        {:strategy strategy
         :width width
         :elapsed-ms elapsed-ms
         :stats stats
         :result-shape
         (cond-> {:values (count (:values result))}
           (contains? result :terminal)
           (assoc :terminal-values (count (:terminal result)))
           (contains? result :terminal?)
           (assoc :terminal? (:terminal? result)
                  :lookahead-count (:lookahead-count result)))})
      (finally
        (d/release conn)))))

(defn run!
  ([]
   (run! {:endpoint "http://127.0.0.1:19003"
          :bucket "eacl-stable-discovery-probe"
          :total 4096
          :cache-size 1
          :widths [1 8 64]
          :trials 6}))
  ([{:keys [total widths trials] :as options}]
   (let [store-id (random-uuid)
         config (runtime-config (assoc options :store-id store-id))
         database-config (demo-config/datahike-config config)]
     (try
       (d/create-database database-config)
       (let [seed-conn (d/connect database-config)
             endpoint
             (try
               (install-fixture! seed-conn total)
               (finally
                 (d/release seed-conn)))
             measurements
             (mapv
              (fn [[trial width strategy]]
                (let [start-bound (- total width 1)
                      measured
                      (measure-one!
                       database-config endpoint start-bound width strategy)]
                  (assoc measured :trial trial)))
              (for [trial (range trials)
                    width widths
                    strategy
                    (if (even? trial)
                      [:p-only :p-then-terminal :p-plus-one]
                      [:p-plus-one :p-then-terminal :p-only])]
                [trial width strategy]))]
         {:fixture {:store-id store-id
                    :total total
                    :cache-size (:cache-size options)
                    :widths widths
                    :trials trials
                    :terminal-cardinality :exactly-width}
          :measurements measurements
          :qualification
          [:disposable-minio
           :direct-s3
           :not-production-s3
           :not-latency-injected]})
       (finally
         (when (d/database-exists? database-config)
           (d/delete-database database-config))
         (konserve-s3/shutdown-clients!))))))

(defn run-interior-boundary-search!
  "Search likely PSS leaf boundaries for cases where eager P+1 performs more
  S3 GETs than consuming exactly P values. Every measurement reconnects with a
  one-entry Datahike store cache. The unique fixture is deleted in `finally`."
  ([]
   (run-interior-boundary-search!
    {:endpoint "http://127.0.0.1:19003"
     :bucket "eacl-stable-discovery-probe"
     :total 4096
     :cache-size 1
     :widths [1 8 64]
     :candidate-bounds
     (vec
      (distinct
       (for [center (range 256 4096 256)
             delta (range -6 7)
             :let [bound (+ center delta)]
             :when (<= 0 bound 4094)]
         bound)))}))
  ([{:keys [total widths candidate-bounds] :as options}]
   (let [store-id (random-uuid)
         config (runtime-config (assoc options :store-id store-id))
         database-config (demo-config/datahike-config config)]
     (try
       (d/create-database database-config)
       (let [seed-conn (d/connect database-config)
             endpoint
             (try
               (install-fixture! seed-conn total)
               (finally
                 (d/release seed-conn)))
             comparisons
             (mapv
              (fn [[bound width]]
                (let [p-only
                      (measure-one!
                       database-config endpoint bound width :p-only)
                      p-plus-one
                      (measure-one!
                       database-config endpoint bound width :p-plus-one)]
                  {:bound bound
                   :width width
                   :p-values (get-in p-only [:result-shape :values])
                   :p-plus-one-values
                   (get-in p-plus-one [:result-shape :lookahead-count])
                   :p-gets (get-in p-only [:stats :get :n] 0)
                   :p-plus-one-gets
                   (get-in p-plus-one [:stats :get :n] 0)
                   :extra-gets
                   (- (get-in p-plus-one [:stats :get :n] 0)
                      (get-in p-only [:stats :get :n] 0))
                   :p-elapsed-ms (:elapsed-ms p-only)
                   :p-plus-one-elapsed-ms (:elapsed-ms p-plus-one)}))
              (for [bound candidate-bounds
                    width widths
                    :when (< (+ bound width) total)]
                [bound width]))]
         {:fixture {:store-id store-id
                    :total total
                    :cache-size (:cache-size options)
                    :candidate-count (count candidate-bounds)
                    :widths widths}
          :comparisons comparisons
          :extra-get-cases (filterv #(pos? (:extra-gets %)) comparisons)
          :qualification
          [:disposable-minio
           :direct-s3
           :interior-boundary-search
           :not-production-s3
           :not-latency-injected]})
       (finally
         (when (d/database-exists? database-config)
           (d/delete-database database-config))
         (konserve-s3/shutdown-clients!))))))

(defn run-stream-boundary-map!
  "Discover actual node-load positions in one lazy sequential scan, then test
  P=1 immediately before each observed later GET. This avoids guessing PSS leaf
  boundaries from logical tuple ordinals."
  ([]
   (run-stream-boundary-map!
    {:endpoint "http://127.0.0.1:19003"
     :bucket "eacl-stable-discovery-probe"
     :total 4096
     :cache-size 1}))
  ([{:keys [total] :as options}]
   (let [store-id (random-uuid)
         config (runtime-config (assoc options :store-id store-id))
         database-config (demo-config/datahike-config config)]
     (try
       (d/create-database database-config)
       (let [seed-conn (d/connect database-config)
             endpoint
             (try
               (install-fixture! seed-conn total)
               (finally
                 (d/release seed-conn)))
             scan-conn (d/connect database-config)
             load-events
             (try
               (let [acc (atom {})]
                 (konserve-s3/set-global-io-stats! acc)
                 (try
                   (loop [remaining
                          (seq (ordered-value-seq
                                (d/db scan-conn) endpoint -1))
                          previous-gets 0
                          events []]
                     (if-let [value (first remaining)]
                       (let [gets (get-in @acc [:get :n] 0)]
                         (recur
                          (next remaining)
                          gets
                          (cond-> events
                            (> gets previous-gets)
                            (conj {:value value
                                   :previous-gets previous-gets
                                   :gets gets
                                   :delta (- gets previous-gets)}))))
                       events))
                   (finally
                     (konserve-s3/set-global-io-stats! nil))))
               (finally
                 (d/release scan-conn)))
             candidate-bounds
             (->> load-events
                  (map :value)
                  (filter #(>= % 2))
                  (map #(- % 2))
                  distinct
                  vec)
             comparisons
             (mapv
              (fn [bound]
                (let [p-only
                      (measure-one!
                       database-config endpoint bound 1 :p-only)
                      p-plus-one
                      (measure-one!
                       database-config endpoint bound 1 :p-plus-one)]
                  {:bound bound
                   :first-value (inc bound)
                   :lookahead-value (+ bound 2)
                   :p-gets (get-in p-only [:stats :get :n] 0)
                   :p-plus-one-gets
                   (get-in p-plus-one [:stats :get :n] 0)
                   :extra-gets
                   (- (get-in p-plus-one [:stats :get :n] 0)
                      (get-in p-only [:stats :get :n] 0))}))
              candidate-bounds)]
         {:fixture {:store-id store-id
                    :total total
                    :cache-size (:cache-size options)}
          :load-events load-events
          :candidate-bounds candidate-bounds
          :comparisons comparisons
          :extra-get-cases (filterv #(pos? (:extra-gets %)) comparisons)
          :qualification
          [:disposable-minio
           :direct-s3
           :observed-stream-boundaries
           :not-production-s3
           :not-latency-injected]})
       (finally
         (when (d/database-exists? database-config)
           (d/delete-database database-config))
         (konserve-s3/shutdown-clients!))))))
