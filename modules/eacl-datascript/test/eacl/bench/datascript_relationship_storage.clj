(ns eacl.bench.datascript-relationship-storage
  "Reproducible JVM comparison of PR #92's relationship-entity layout and the
  endpoint-pair layout. Run explicitly through nREPL; this is not a test
  namespace and is intentionally excluded from the regular suite."
  (:require [clojure.java.io :as io]
            [datascript.core :as ds]
            [eacl.datascript.db :as ddb]
            [eacl.relationships.endpoint-pair :as endpoint-pair])
  (:import [com.sun.management ThreadMXBean]
           [java.lang.management ManagementFactory]
           [java.time Instant]))

(def old-subject-attr :bench.old/subject)
(def old-relation-attr :bench.old/relation)
(def old-resource-attr :bench.old/resource)
(def old-subject-type-attr :bench.old/subject-type)
(def old-resource-type-attr :bench.old/resource-type)
(def old-full-key-attr :bench.old/full-key)
(def old-forward-attr :bench.old/forward)
(def old-reverse-attr :bench.old/reverse)
(def old-forward-partial-attr :bench.old/forward-partial)
(def old-reverse-partial-attr :bench.old/reverse-partial)
(def new-forward-attr :bench.new/forward)
(def new-reverse-attr :bench.new/reverse)

(def old-relationship-attrs
  [old-subject-attr old-relation-attr old-resource-attr
   old-subject-type-attr old-resource-type-attr old-full-key-attr
   old-forward-attr old-reverse-attr
   old-forward-partial-attr old-reverse-partial-attr])

(def old-schema
  {:bench/id {:db/unique :db.unique/identity}
   old-subject-attr {:db/valueType :db.type/ref}
   old-relation-attr {:db/valueType :db.type/ref}
   old-resource-attr {:db/valueType :db.type/ref}
   old-subject-type-attr {:db/index true}
   old-resource-type-attr {:db/index true}
   old-full-key-attr
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [old-subject-type-attr old-subject-attr
                    old-relation-attr old-resource-type-attr
                    old-resource-attr]
    :db/unique :db.unique/identity}
   old-forward-attr
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [old-subject-type-attr old-subject-attr
                    old-relation-attr old-resource-type-attr
                    old-resource-attr]
    :db/index true}
   old-reverse-attr
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [old-resource-type-attr old-resource-attr
                    old-relation-attr old-subject-type-attr
                    old-subject-attr]
    :db/index true}
   old-forward-partial-attr
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [old-subject-type-attr old-relation-attr
                    old-resource-type-attr old-resource-attr
                    old-subject-attr]
    :db/index true}
   old-reverse-partial-attr
   {:db/valueType :db.type/tuple
    :db/tupleAttrs [old-resource-type-attr old-relation-attr
                    old-subject-type-attr old-subject-attr
                    old-resource-attr]
    :db/index true}})

(def new-schema
  {:bench/id {:db/unique :db.unique/identity}
   new-forward-attr
   {:db/cardinality :db.cardinality/many
    :db/index true}
   new-reverse-attr
   {:db/cardinality :db.cardinality/many
    :db/index true}})

(defn- base-db
  [schema relationship-count]
  (let [width (long (Math/ceil (Math/sqrt relationship-count)))
        conn (ds/create-conn schema)
        subjects (mapv #(str "subject-" %) (range width))
        resources (mapv #(str "resource-" %) (range width))]
    (ds/transact!
     conn
     (concat
      [{:bench/id "relation"}]
      (map (fn [id] {:bench/id id}) subjects)
      (map (fn [id] {:bench/id id}) resources)))
    (let [db (ds/db conn)
          subject-eids (mapv #(ds/entid db [:bench/id %]) subjects)
          resource-eids (mapv #(ds/entid db [:bench/id %]) resources)
          relation-eid (ds/entid db [:bench/id "relation"])
          pairs
          (->> (for [subject-eid subject-eids
                     resource-eid resource-eids]
                 [subject-eid resource-eid])
               (take relationship-count)
               vec)]
      {:db db
       :relation-eid relation-eid
       :subject-eids subject-eids
       :resource-eids resource-eids
       :pairs pairs})))

(defn- old-create-tx
  [relation-eid pairs]
  (mapv
   (fn [[subject-eid resource-eid]]
     {old-subject-type-attr :user
      old-subject-attr subject-eid
      old-relation-attr relation-eid
      old-resource-type-attr :document
      old-resource-attr resource-eid})
   pairs))

(defn- new-create-tx
  [relation-eid pairs]
  (mapcat
   (fn [[subject-eid resource-eid]]
     [[:db/add subject-eid new-forward-attr
       (endpoint-pair/forward-value
        :user relation-eid :document resource-eid)]
      [:db/add resource-eid new-reverse-attr
       (endpoint-pair/reverse-value
        :document relation-eid :user subject-eid)]])
   pairs))

(defn- seeded-layout
  [layout relationship-count]
  (let [{:keys [db relation-eid pairs] :as base}
        (base-db (if (= :old layout) old-schema new-schema)
                 relationship-count)
        tx-data ((if (= :old layout) old-create-tx new-create-tx)
                 relation-eid pairs)
        report (ds/with db tx-data)]
    (assoc base
           :layout layout
           :base-db db
           :db (:db-after report)
           :tx-data tx-data
           :relationship-eids
           (when (= :old layout)
             (mapv
              #(ds/entid
                (:db-after report)
                [old-full-key-attr
                 [:user (first %) relation-eid :document (second %)]])
              pairs)))))

(defn- old-direct?
  [{:keys [db relation-eid pairs]}]
  (let [[subject-eid resource-eid] (nth pairs (quot (count pairs) 2))]
    (boolean
     (ds/entid
      db
      [old-full-key-attr
       [:user subject-eid relation-eid :document resource-eid]]))))

(defn- new-direct?
  [{:keys [db relation-eid pairs]}]
  (let [[subject-eid resource-eid] (nth pairs (quot (count pairs) 2))]
    (boolean
     (seq
      (ddb/eavt-datoms
       db subject-eid new-forward-attr
       (endpoint-pair/forward-value
        :user relation-eid :document resource-eid))))))

(defn- old-forward-adjacency
  [{:keys [db relation-eid subject-eids]}]
  (let [subject-eid (nth subject-eids (quot (count subject-eids) 2))]
    (->> (ds/index-range
          db old-forward-attr
          [:user subject-eid relation-eid :document 0]
          [:user subject-eid relation-eid :document Long/MAX_VALUE])
         (mapv (comp #(nth % 4) :v)))))

(defn- new-forward-adjacency
  [{:keys [db relation-eid subject-eids]}]
  (let [subject-eid (nth subject-eids (quot (count subject-eids) 2))]
    (->> (ddb/eavt-endpoint-prefix
          db subject-eid new-forward-attr
          [:user relation-eid :document])
         (mapv (comp #(nth % 3) :v)))))

(defn- old-reverse-adjacency
  [{:keys [db relation-eid resource-eids]}]
  (let [resource-eid (nth resource-eids (quot (count resource-eids) 2))]
    (->> (ds/index-range
          db old-reverse-attr
          [:document resource-eid relation-eid :user 0]
          [:document resource-eid relation-eid :user Long/MAX_VALUE])
         (mapv (comp #(nth % 4) :v)))))

(defn- new-reverse-adjacency
  [{:keys [db relation-eid resource-eids]}]
  (let [resource-eid (nth resource-eids (quot (count resource-eids) 2))]
    (->> (ddb/eavt-endpoint-prefix
          db resource-eid new-reverse-attr
          [:document relation-eid :user])
         (mapv (comp #(nth % 3) :v)))))

(defn- old-page
  [{:keys [db relation-eid]}]
  (->> (ds/index-range
        db old-forward-partial-attr
        [:user relation-eid :document 0 0]
        [:user relation-eid :document Long/MAX_VALUE Long/MAX_VALUE])
       (take 100)
       (mapv :v)))

(defn- new-page
  [{:keys [db relation-eid]}]
  (->> (ddb/avet-endpoint-prefix
        db new-forward-attr [:user relation-eid :document])
       (take 100)
       (mapv (juxt :e :v))))

(defn- old-content-proof
  [{:keys [db]}]
  (->> (ds/q
        '[:find ?relation ?subject-type ?subject ?resource-type ?resource
          :where
          [?relationship :bench.old/relation ?relation]
          [?relationship :bench.old/subject-type ?subject-type]
          [?relationship :bench.old/subject ?subject]
          [?relationship :bench.old/resource-type ?resource-type]
          [?relationship :bench.old/resource ?resource]]
        db)
       sort
       hash))

(defn- new-content-proof
  [{:keys [db]}]
  (hash
   (sort
    (concat
     (map (fn [{:keys [e v]}] [:forward e v])
          (ddb/avet-datoms db new-forward-attr))
     (map (fn [{:keys [e v]}] [:reverse e v])
          (ddb/avet-datoms db new-reverse-attr))))))

(defn- old-delete-tx
  [{:keys [relationship-eids]} batch-size]
  (mapv (fn [eid] [:db/retractEntity eid])
        (take batch-size relationship-eids)))

(defn- new-delete-tx
  [{:keys [relation-eid pairs]} batch-size]
  (mapcat
   (fn [[subject-eid resource-eid]]
     [[:db/retract subject-eid new-forward-attr
       (endpoint-pair/forward-value
        :user relation-eid :document resource-eid)]
      [:db/retract resource-eid new-reverse-attr
       (endpoint-pair/reverse-value
        :document relation-eid :user subject-eid)]])
   (take batch-size pairs)))

(defn- relationship-datom-count
  [{:keys [layout db]}]
  (if (= :old layout)
    (reduce + (map #(count (ds/datoms db :aevt %))
                   old-relationship-attrs))
    (+ (count (ddb/avet-datoms db new-forward-attr))
       (count (ddb/avet-datoms db new-reverse-attr)))))

(defn- allocated-bytes
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (instance? ThreadMXBean bean)
      (let [bean ^ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          (.getThreadAllocatedBytes bean (.getId (Thread/currentThread))))))))

(defn- datascript-version
  []
  (when-let [resource
             (io/resource
              "META-INF/maven/datascript/datascript/pom.properties")]
    (some-> (re-find #"(?m)^version=(.+)$" (slurp resource))
            second)))

(defn- percentile
  [sorted-values fraction]
  (nth sorted-values
       (min (dec (count sorted-values))
            (long (Math/floor (* fraction (count sorted-values)))))))

(defn- measure
  [f {:keys [warmup samples iterations]}]
  (dotimes [_ warmup]
    (dotimes [_ iterations] (hash (f))))
  (let [observations
        (mapv
         (fn [_]
           (let [before-bytes (allocated-bytes)
                 start (System/nanoTime)
                 sink
                 (loop [i 0
                        value 0]
                   (if (= i iterations)
                     value
                     (recur (inc i) (unchecked-add value (hash (f))))))
                 elapsed (- (System/nanoTime) start)
                 after-bytes (allocated-bytes)]
             (when (= Long/MIN_VALUE sink)
               (throw (ex-info "Unreachable benchmark sink." {})))
             {:ns-per-op (/ (double elapsed) iterations)
              :bytes-per-op
              (when (and before-bytes after-bytes)
                (/ (double (- after-bytes before-bytes)) iterations))}))
         (range samples))
        latency (sort (map :ns-per-op observations))
        allocation (sort (keep :bytes-per-op observations))]
    {:p50-ns (percentile latency 0.50)
     :p95-ns (percentile latency 0.95)
     :mean-ns (/ (reduce + latency) (count latency))
     :mean-bytes
     (when (seq allocation)
       (/ (reduce + allocation) (count allocation)))}))

(defn- correctness!
  [old new]
  (doseq [[label old-fn new-fn]
          [[:direct old-direct? new-direct?]
           [:forward-adjacency old-forward-adjacency new-forward-adjacency]
           [:reverse-adjacency old-reverse-adjacency new-reverse-adjacency]]]
    (when-not (= (old-fn old) (new-fn new))
      (throw
       (ex-info "Old/new benchmark fixtures are not logically equivalent."
                {:operation label
                 :old (old-fn old)
                 :new (new-fn new)})))))

(defn run-benchmark!
  "Runs old/new workloads and returns a printable EDN report.

  Options:
  - `:graph-sizes` relationship counts (default [1024 4096])
  - `:warmup`, `:samples`, `:iterations` for reads/proofs
  - `:write-*` equivalents for immutable create/delete batches
  - `:batch-size` relationships per measured write (default 100)"
  ([]
   (run-benchmark! {}))
  ([{:keys [graph-sizes warmup samples iterations
            write-warmup write-samples write-iterations batch-size]
     :or {graph-sizes [1024 4096]
          warmup 20
          samples 30
          iterations 20
          write-warmup 3
          write-samples 10
          write-iterations 1
          batch-size 100}}]
   (let [read-method {:warmup warmup
                      :samples samples
                      :iterations iterations}
         write-method {:warmup write-warmup
                       :samples write-samples
                       :iterations write-iterations}
         results
         (mapv
          (fn [relationship-count]
            (let [old (seeded-layout :old relationship-count)
                  new (seeded-layout :new relationship-count)
                  _ (correctness! old new)
                  old-create
                  #(-> (ds/with
                        (:base-db old)
                        (take batch-size (:tx-data old)))
                       :db-after :max-tx)
                  new-create
                  #(-> (ds/with
                        (:base-db new)
                        (take (* 2 batch-size) (:tx-data new)))
                       :db-after :max-tx)
                  old-delete
                  #(-> (ds/with
                        (:db old)
                        (old-delete-tx old batch-size))
                       :db-after :max-tx)
                  new-delete
                  #(-> (ds/with
                        (:db new)
                        (new-delete-tx new batch-size))
                       :db-after :max-tx)
                  workloads
                  {:direct [#(old-direct? old) #(new-direct? new)
                            read-method]
                   :forward-adjacency
                   [#(old-forward-adjacency old)
                    #(new-forward-adjacency new)
                    read-method]
                   :reverse-adjacency
                   [#(old-reverse-adjacency old)
                    #(new-reverse-adjacency new)
                    read-method]
                   :relationship-page
                   [#(old-page old) #(new-page new) read-method]
                   :content-proof
                   [#(old-content-proof old)
                    #(new-content-proof new)
                    read-method]
                   :create-batch
                   [old-create new-create write-method]
                   :delete-batch
                   [old-delete new-delete write-method]}]
              {:relationships relationship-count
               :storage-datoms
               {:old (relationship-datom-count old)
                :new (relationship-datom-count new)}
               :workloads
               (into
                {}
                (map
                 (fn [[operation [old-fn new-fn method]]]
                   [operation
                    {:old (measure old-fn method)
                     :new (measure new-fn method)}]))
                workloads)}))
          graph-sizes)]
     {:benchmark :datascript-relationship-storage
      :generated-at (str (Instant/now))
      :hardware
      {:os (System/getProperty "os.name")
       :os-version (System/getProperty "os.version")
       :arch (System/getProperty "os.arch")
       :processors (.availableProcessors (Runtime/getRuntime))
       :max-memory-bytes (.maxMemory (Runtime/getRuntime))}
      :runtime
      {:java (System/getProperty "java.version")
       :clojure (clojure-version)
       :datascript (datascript-version)}
      :methodology
      {:read {:warmup warmup
              :samples samples
              :iterations-per-sample iterations}
       :write {:warmup write-warmup
               :samples write-samples
               :iterations-per-sample write-iterations
               :batch-size batch-size}
       :correctness
       "Old/new direct and forward/reverse adjacency results compared before timing."
       :measurement
       "System/nanoTime wall latency; per-thread allocated bytes when supported."}
      :results results})))
