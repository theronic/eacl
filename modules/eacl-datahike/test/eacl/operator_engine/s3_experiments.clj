(ns eacl.operator-engine.s3-experiments
  "Disposable, loopback-only Datahike/Konserve-S3 operator probes.

  The experiment creates a unique database in an existing MinIO bucket, writes
  actual EACL relationship tuples, separates connection/head work from query
  work, and deletes the database in `finally`. It is exploration code only."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as string]
            [datahike.api :as d]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as datahike-impl]
            [eacl.datahike.schema :as datahike-schema]
            [eacl.relationships.storage :as relationship-storage]
            [konserve-s3.core :as konserve-s3])
  (:import [java.net URI]))

(def physical-batch-cap 256)

(defn- loopback-endpoint! [endpoint]
  (let [uri (URI. endpoint)]
    (when-not (and (contains? #{"127.0.0.1" "localhost" "::1"}
                              (.getHost uri))
                   (contains? #{"http" "https"} (.getScheme uri)))
      (throw (ex-info "S3 experiments require a loopback MinIO endpoint."
                      {:endpoint endpoint})))
    uri))

(defn- database-config
  [{:keys [endpoint bucket store-id cache-size]}]
  (let [uri (loopback-endpoint! endpoint)]
    {:store
     {:backend :s3
      :bucket bucket
      :region "auto"
      :id store-id
      :endpoint-override
      {:protocol (keyword (.getScheme uri))
       :hostname (.getHost uri)
       :port (.getPort uri)}
      :path-style-access? true
      :access-key "minioadmin"
      :secret "minioadmin123"}
     :writer {:backend :self
              :writer-ownership :shared
              :require-fencing :global}
     :schema-flexibility :write
     :attribute-refs? true
     :keep-history? false
     :max-string-length 0
     :store-cache-size cache-size
     :search-cache-size 0
     :index-config {:diff-buf-size 256}
     :fuse-index-roots? true
     :commit-graph? false}))

(defn- seed!
  [config resource-count]
  (let [conn (datahike/create-conn nil config)
        client (datahike/make-client
                conn
                {:cache cache/no-cache
                 :security-key "operator-engine-s3-experiment-key-01"})
        user (eacl/spice-object :user "probe-user")
        resources
        (mapv #(eacl/spice-object :resource (format "r-%05d" %))
              (range resource-count))]
    (try
      (eacl/write-schema!
       client
       (str "definition user {}\n\n"
            "definition resource {\n"
            "  relation candidate: user\n"
            "  relation selective: user\n"
            "  permission candidate_view = candidate\n"
            "  permission selective_view = selective\n"
            "}"))
      (doseq [batch (partition-all 512 (into [user] resources))]
        (d/transact
         conn
         (mapv (fn [{:keys [id]}] {:eacl/id id}) batch)))
      (doseq [batch (partition-all 256 resources)]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship user :candidate %) batch)))
      (doseq [batch (partition-all 256 (take-nth 4 resources))]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship user :selective %) batch)))
      (let [db (d/db conn)
            subject-eid (:db/id (d/entity db [:eacl/id "probe-user"]))
            relation-eid
            (:db/id
             (d/entity
              db
              [datahike-schema/relation-key-attr
               [:resource :selective :user]]))
            resource-eids
            (->> (ddb/avet-datoms db :eacl/id)
                 (filter #(and (string? (:v %))
                               (string/starts-with? (:v %) "r-")))
                 (map :e)
                 sort
                 vec)]
        {:subject-eid subject-eid
         :relation-eid relation-eid
         :resource-eids resource-eids})
      (finally
        (d/release conn)
        (konserve-s3/shutdown-clients!)))))

(defn- exact-decisions
  [db subject-eid relation-eid candidates]
  (mapv
   #(datahike-impl/direct-match?
     db :user subject-eid relation-eid :resource %)
   candidates))

(defn- prefix-decisions
  [db subject-eid relation-eid candidates]
  (let [first-eid (first candidates)
        last-eid (peek candidates)
        values
        (->> (ddb/eavt-tuple-prefix
              db subject-eid relationship-storage/forward-attribute 4
              [:user relation-eid :resource] first-eid)
             (map #(nth (:v %) 3))
             (take-while #(<= % last-eid))
             vec)
        present (set values)]
    {:decisions (mapv #(contains? present %) candidates)
     :values-scanned (count values)}))

(defn- operation-counts [stats]
  (into {}
        (map (fn [[operation summary]] [operation (:n summary)]))
        stats))

(defn- measure-cold
  [config {:keys [subject-eid relation-eid]} candidates strategy]
  (konserve-s3/shutdown-clients!)
  (let [{conn :result connection-stats :stats}
        (konserve-s3/with-global-io-stats (d/connect config))]
    (try
      (let [db (d/db conn)
            evaluate
            #(case strategy
               :exact
               {:decisions
                (exact-decisions db subject-eid relation-eid candidates)
                :values-scanned (count candidates)}

               :prefix
               (prefix-decisions db subject-eid relation-eid candidates))
            first-measurement
            (konserve-s3/with-global-io-stats (evaluate))
            warm-measurement
            (konserve-s3/with-global-io-stats (evaluate))]
        {:strategy strategy
         :candidate-count (count candidates)
         :span (inc (- (peek candidates) (first candidates)))
         :connection-operations (operation-counts connection-stats)
         :query-operations (operation-counts (:stats first-measurement))
         :warm-query-operations (operation-counts (:stats warm-measurement))
         :values-scanned (get-in first-measurement [:result :values-scanned])
         :decisions (get-in first-measurement [:result :decisions])})
      (finally
        (d/release conn)
        (konserve-s3/shutdown-clients!)))))

(defn- adaptive-decisions
  [probe candidates demand]
  (loop [offset 0
         width (min (count candidates) physical-batch-cap (max 1 demand))
         accepted []
         physical 0
         batches 0]
    (if (or (>= (count accepted) demand) (= offset (count candidates)))
      {:accepted (vec (take demand accepted))
       :physical-candidates physical
       :batches batches}
      (let [end (min (count candidates) (+ offset width))
            batch (subvec candidates offset end)
            decisions (probe batch)
            accepted'
            (into accepted
                  (keep-indexed
                   (fn [index decision]
                     (when decision (nth batch index))))
                  decisions)]
        (recur end
               (min physical-batch-cap
                    (- (count candidates) end)
                    (* 2 width))
               accepted'
               (+ physical (count batch))
               (inc batches))))))

(defn- measure-demand
  [config {:keys [subject-eid relation-eid]} candidates demand]
  (konserve-s3/shutdown-clients!)
  (let [{conn :result connection-stats :stats}
        (konserve-s3/with-global-io-stats (d/connect config))]
    (try
      (let [db (d/db conn)
            probe #(exact-decisions db subject-eid relation-eid %)
            adaptive
            (konserve-s3/with-global-io-stats
              (adaptive-decisions probe candidates demand))]
        (konserve-s3/shutdown-clients!)
        {:demand demand
         :candidate-window (count candidates)
         :connection-operations (operation-counts connection-stats)
         :adaptive
         (assoc (:result adaptive)
                :query-operations (operation-counts (:stats adaptive)))})
      (finally
        (d/release conn)
        (konserve-s3/shutdown-clients!)))))

(defn run!
  ([]
   (run! {:endpoint "http://127.0.0.1:19000"
          :bucket "eacl-datahike-local"
          :resource-count 4096
          :cache-size 1}))
  ([{:keys [resource-count] :as options}]
   (let [store-id (random-uuid)
         config (database-config (assoc options :store-id store-id))
         started (System/nanoTime)]
     (try
       (let [{:keys [resource-eids] :as fixture}
             (seed! config resource-count)
             dense (subvec resource-eids 1000 1256)
             last-index (dec (count resource-eids))
             sparse
             (mapv #(nth resource-eids
                         (quot (* % last-index) 255))
                   (range 256))
             dense-exact (measure-cold config fixture dense :exact)
             dense-prefix (measure-cold config fixture dense :prefix)
             sparse-exact (measure-cold config fixture sparse :exact)
             sparse-prefix (measure-cold config fixture sparse :prefix)
             demand (measure-demand config fixture dense 21)]
         {:format-version 1
          :fixture {:store-id store-id
                    :endpoint (:endpoint options)
                    :bucket (:bucket options)
                    :resource-count resource-count
                    :cache-size (:cache-size options)
                    :selective-cardinality
                    (count (filter true? (:decisions dense-exact)))
                    :experiment-elapsed-ms
                    (/ (double (- (System/nanoTime) started)) 1000000.0)}
          :dense
          {:equal-decisions?
           (= (:decisions dense-exact) (:decisions dense-prefix))
           :exact (dissoc dense-exact :decisions)
           :prefix (dissoc dense-prefix :decisions)}
          :sparse
          {:equal-decisions?
           (= (:decisions sparse-exact) (:decisions sparse-prefix))
           :exact (dissoc sparse-exact :decisions)
           :prefix (dissoc sparse-prefix :decisions)}
          :adaptive-demand
          (assoc
           demand
           :fixed-width
           {:accepted
            (into []
                  (comp
                   (keep-indexed
                    (fn [index decision]
                      (when decision (nth dense index))))
                   (take 21))
                  (:decisions dense-exact))
            :physical-candidates (count dense)
            :batches 1
            :query-operations (:query-operations dense-exact)})
          :qualification
          [:disposable-minio :direct-s3 :actual-eacl-tuples
           :cold-query-separated-from-connection :warm-repeat]})
       (finally
         (when (d/database-exists? config)
           (d/delete-database config))
         (konserve-s3/shutdown-clients!))))))
