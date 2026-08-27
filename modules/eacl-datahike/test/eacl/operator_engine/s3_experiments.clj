(ns eacl.operator-engine.s3-experiments
  "Disposable, loopback-only Datahike/Konserve-S3 operator probes.

  The experiment creates a unique database in an existing MinIO bucket, writes
  actual EACL relationship tuples, separates connection/head work from query
  work, and deletes the database in `finally`. It is exploration code only."
  (:refer-clojure :exclude [run!])
  (:require [clojure.string :as string]
            [datahike.api :as d]
            [eacl.backend.direct-membership :as direct-dispatch]
            [eacl.backend.v8 :as backend]
            [eacl.cache :as cache]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datahike.direct-membership :as datahike-direct]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.datahike.impl :as datahike-impl]
            [eacl.datahike.schema :as datahike-schema]
            [eacl.engine.v8 :as engine]
            [eacl.relationships.storage :as relationship-storage]
            [eacl.request.counters :as request-counters]
            [konserve.impl.defaults :as konserve-defaults]
            [konserve-s3.core :as konserve-s3]
            [konserve-s3.storage :as s3-storage])
  (:import [java.lang.management ManagementFactory]
           [java.net URI]))

(def physical-batch-cap 256)
(def ^:private experiment-security-key
  "operator-engine-s3-experiment-key-01")
(def ^:private thread-bean (ManagementFactory/getThreadMXBean))

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
                 :security-key experiment-security-key
                 :source-lifecycle "operator-engine-s3-experiment"})
        user (eacl/spice-object :user "probe-user")
        resources
        (mapv #(eacl/spice-object :resource (format "r-%05d" %))
              (range resource-count))
        folders
        (mapv #(eacl/spice-object :folder (format "f-%03d" %))
              (range 64))]
    (try
      (binding [orchestration/*operator-expression-writes-enabled?* true]
        (eacl/write-schema!
         client
         (str "definition user {}\n\n"
              "definition folder {\n"
              "  relation viewer: user\n"
              "  permission view = viewer\n"
              "}\n\n"
              "definition resource {\n"
              "  relation candidate: user\n"
              "  relation selective: user\n"
              "  relation dense_banned: user\n"
              "  relation sparse_banned: user\n"
              "  relation parent: folder\n"
              "  permission candidate_view = candidate\n"
              "  permission selective_view = selective\n"
              "  permission intersection_view = candidate & selective\n"
              "  permission dense_exclusion = candidate - dense_banned\n"
              "  permission sparse_exclusion = candidate - sparse_banned\n"
              "  permission arrow_intersection = candidate & parent->view\n"
              "}")))
      (doseq [batch (partition-all 512 (into [user] (concat folders resources)))]
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
      (doseq [batch (partition-all
                     256
                     (keep-indexed (fn [index resource]
                                     (when-not (zero? (mod index 4)) resource))
                                   resources))]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship user :dense_banned %) batch)))
      (doseq [batch (partition-all 256 (take-nth 64 resources))]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship user :sparse_banned %) batch)))
      (eacl/create-relationships!
       client
       (mapv #(eacl/->Relationship user :viewer %) (take-nth 2 folders)))
      (doseq [batch (partition-all 256 (map-indexed vector resources))]
        (eacl/create-relationships!
         client
         (mapv (fn [[index resource]]
                 (eacl/->Relationship (nth folders (mod index (count folders)))
                                      :parent resource))
               batch)))
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
         :resource-eids resource-eids
         :user user})
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

(defn- physical-branch-object-key [config]
  (s3-storage/->key
   (str (get-in config [:store :id]))
   (konserve-defaults/key->store-key (or (:branch config) :db))))

(defn- observe-gets [read]
  (let [events (atom [])
        get-object konserve-s3/get-object
        get-object-with-etag konserve-s3/get-object-with-etag
        record!
        (fn [operation key bytes started]
          (swap! events conj
                 {:operation operation
                  :key key
                  :bytes (if bytes (alength ^bytes bytes) 0)
                  :elapsed-nanos (- (System/nanoTime) started)}))]
    (with-redefs [konserve-s3/get-object
                  (fn [client bucket key]
                    (let [started (System/nanoTime)
                          bytes (get-object client bucket key)]
                      (record! :get-object key bytes started)
                      bytes))
                  konserve-s3/get-object-with-etag
                  (fn [client bucket key]
                    (let [started (System/nanoTime)
                          response (get-object-with-etag client bucket key)]
                      (record! :get-object-with-etag key (:data response)
                               started)
                      response))]
      {:result (read) :events @events})))

(defn- io-summary [branch-key observation]
  (let [events (:events observation)]
    {:gets (count events)
     :branch-head-gets (count (filter #(= branch-key (:key %)) events))
     :index-and-metadata-gets
     (count (remove #(= branch-key (:key %)) events))
     :bytes (reduce + 0 (map :bytes events))
     :physical-keys (into (sorted-map) (frequencies (map :key events)))
     :operations
     (operation-counts (get-in observation [:result :stats]))}))

(defn- instance-field [value field]
  (clojure.lang.Reflector/getInstanceField value field))

(defn- index-storage [db]
  (instance-field (:eavt db) "_storage"))

(defn- index-state [db]
  (let [storage (index-storage db)
        stats @(instance-field storage "stats")
        cache-value @(instance-field storage "cache")]
    {:reads (:reads stats)
     :accessed (:accessed stats)
     :writes (:writes stats)
     :cache-entries (count cache-value)}))

(defn- state-delta [before after]
  (into {}
        (for [key (keys before)]
          [key (- (get after key 0) (get before key 0))])))

(defn- allocated-bytes []
  (let [bean ^com.sun.management.ThreadMXBean thread-bean]
    (.getThreadAllocatedBytes bean (.getId (Thread/currentThread)))))

(defn- measure-public-call
  [config db read]
  (let [branch-key (physical-branch-object-key config)
        ledger (request-counters/make-ledger)
        backend-operations (atom {})
        dispatch-physical (atom {})
        datahike-physical (atom {})
        traversal (atom {})
        index-before (index-state db)
        allocated-before (allocated-bytes)
        started (System/nanoTime)
        observation
        (observe-gets
         #(konserve-s3/with-global-io-stats
            (binding [backend/*backend-op-stats* backend-operations
                      direct-dispatch/*physical-stats* dispatch-physical
                      datahike-direct/*physical-stats* datahike-physical
                      engine/*recursive-traversal-stats* traversal
                      engine/*operator-routing-enabled?* true]
              (request-counters/call-with-ledger ledger read))))
        elapsed (- (System/nanoTime) started)
        allocated (- (allocated-bytes) allocated-before)
        index-after (index-state db)]
    {:result (get-in observation [:result :result])
     :latency-nanos elapsed
     :allocated-bytes allocated
     :request-counters (request-counters/snapshot ledger)
     :adapter-operations @backend-operations
     :dispatch-physical @dispatch-physical
     :datahike-physical @datahike-physical
     :traversal @traversal
     :index-state {:before index-before
                   :after index-after
                   :delta (state-delta index-before index-after)}
     :io (io-summary branch-key observation)}))

(defn- page-summary [page]
  {:items (count (:data page))
   :ids (mapv :id (:data page))
   :has-next-page? (get-in page [:page-info :has-next-page?])})

(defn- without-result [measurement summary]
  (assoc (dissoc measurement :result) :result summary))

(defn- connect-observed [config]
  (konserve-s3/shutdown-clients!)
  (let [branch-key (physical-branch-object-key config)
        observation
        (observe-gets
         #(konserve-s3/with-global-io-stats (d/connect config)))]
    {:conn (get-in observation [:result :result])
     :connection-io (io-summary branch-key observation)}))

(defn- public-page-sequence
  [config {:keys [user]} permission]
  (let [{:keys [conn connection-io]} (connect-observed config)]
    (try
      (let [db (d/db conn)
            client (datahike/make-client
                    conn
                    {:cache cache/no-cache
                     :security-key experiment-security-key
                     :source-lifecycle "operator-engine-s3-experiment"})
            query {:subject user :permission permission
                   :resource/type :resource :first 20 :cache? false}
            first-page (measure-public-call
                        config db #(eacl/lookup-resources client query))
            warm-page (measure-public-call
                       config db #(eacl/lookup-resources client query))
            adjacent-query
            (assoc query :after (get-in first-page
                                        [:result :page-info :end-cursor]))
            adjacent-page
            (measure-public-call
             config db #(eacl/lookup-resources client adjacent-query))]
        {:connection-io connection-io
         :cold-first-page
         (without-result first-page (page-summary (:result first-page)))
         :immediate-warm-repeat
         (without-result warm-page (page-summary (:result warm-page)))
         :adjacent-page
         (without-result adjacent-page (page-summary (:result adjacent-page)))})
      (finally
        (d/release conn)
        (konserve-s3/shutdown-clients!)))))

(defn- public-count
  [config {:keys [user]} permission count-limit]
  (let [{:keys [conn connection-io]} (connect-observed config)]
    (try
      (let [db (d/db conn)
            client (datahike/make-client
                    conn
                    {:cache cache/no-cache
                     :security-key experiment-security-key
                     :source-lifecycle "operator-engine-s3-experiment"})
            query (cond-> {:subject user :permission permission
                           :resource/type :resource :cache? false}
                    count-limit (assoc :count-limit count-limit))
            measurement
            (measure-public-call config db #(eacl/count-resources client query))]
        {:connection-io connection-io
         :measurement
         (without-result measurement (:result measurement))})
      (finally
        (d/release conn)
        (konserve-s3/shutdown-clients!)))))

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

(defn- span-candidates
  [resource-eids span-factor]
  (let [candidate-count 256
        start-index 1000
        span (* candidate-count span-factor)
        last-offset (dec span)]
    (mapv (fn [candidate-index]
            (nth resource-eids
                 (+ start-index
                    (quot (* candidate-index last-offset)
                          (dec candidate-count)))))
          (range candidate-count))))

(defn- minio-multiplier-cases
  [config fixture]
  (into
   (sorted-map)
   (for [span-factor [1 2 4 8]
         :let [candidates (span-candidates (:resource-eids fixture)
                                           span-factor)
               exact (measure-cold config fixture candidates :exact)
               prefix (measure-cold config fixture candidates :prefix)]]
     [span-factor
      {:span-factor span-factor
       :candidate-count (count candidates)
       :span (:span exact)
       :equal-decisions? (= (:decisions exact) (:decisions prefix))
       :exact (dissoc exact :decisions)
       :prefix (dissoc prefix :decisions)
       :selected-at-multiplier-2
       (if (<= (:span exact) (* 2 (count candidates)))
         :bounded-prefix
         :sparse-exact)}])))

(defn run!
  ([]
   (run! {:endpoint "http://127.0.0.1:19000"
          :bucket "eacl-datahike-local"
          :resource-count 4096
          :cache-size 8192}))
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
             demand (measure-demand config fixture dense 21)
             multipliers (minio-multiplier-cases config fixture)
             intersection
             (public-page-sequence config fixture :intersection_view)
             dense-exclusion
             (public-page-sequence config fixture :dense_exclusion)
             sparse-exclusion
             (public-page-sequence config fixture :sparse_exclusion)
             arrow (public-page-sequence config fixture :arrow_intersection)
             bounded-count
             (public-count config fixture :intersection_view 20)
             exact-count
             (public-count config fixture :intersection_view nil)
             after-eviction
             (public-page-sequence config fixture :intersection_view)]
         {:format-version 2
          :fixture {:store-id store-id
                    :endpoint (:endpoint options)
                    :bucket (:bucket options)
                    :resource-count resource-count
                    :cache-size (:cache-size options)
                    :selective-cardinality
                    (count (take-nth 4 resource-eids))
                    :dense-exclusion-cardinality
                    (count (take-nth 4 resource-eids))
                    :sparse-exclusion-cardinality
                    (- resource-count (count (take-nth 64 resource-eids)))
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
          :multiplier-neighborhood multipliers
          :public-operator
          {:intersection intersection
           :dense-exclusion dense-exclusion
           :sparse-exclusion sparse-exclusion
           :arrow arrow
           :bounded-count bounded-count
           :exact-count exact-count
           :after-node-cache-eviction after-eviction}
          :measurement-separation
          {:bounded [:intersection :dense-exclusion :sparse-exclusion
                     :arrow :bounded-count]
           :exhaustive [:exact-count]
           :connection-metadata-reported-separately true}
          :qualification
          [:disposable-minio :direct-s3 :actual-eacl-tuples
           :cold-query-separated-from-connection :warm-repeat
           :public-operator-routing :physical-keys-and-bytes
           :datahike-index-reads :bounded-vs-exhaustive]})
       (finally
         (when (d/database-exists? config)
           (d/delete-database config))
         (konserve-s3/shutdown-clients!))))))
