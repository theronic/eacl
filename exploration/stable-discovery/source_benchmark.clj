(ns eacl.exploration.stable-discovery-source-benchmark
  "Ignored, exploration-only source benchmark.

  Loads through the public DataScript/EACL APIs and compares the legacy
  acyclic merge, the current byte-ordered stable candidate, and a test-only
  cost-ranked wrapper. Nothing in this namespace is production code."
  (:require [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.bench.recursive-fixture :as recursive-fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.engine.portable-indexed :as portable]
            [eacl.engine.v8 :as engine]
            [eacl.formal.production-kernel :as production-kernel]
            [eacl.secure-format :as secure-format]
            [eacl.verified-kernel :as verified]))

(def ^:private allocation-bean
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)]
    (when (instance? com.sun.management.ThreadMXBean bean)
      (let [bean ^com.sun.management.ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(defn- current-thread-allocated-bytes
  []
  (when allocation-bean
    (.getThreadAllocatedBytes
     ^com.sun.management.ThreadMXBean allocation-bean
     (.getId (Thread/currentThread)))))

(def adversarial-schema
  "definition user {}

   definition group {
     relation member: user
   }

   definition document {
     relation parent: group
     relation reader: user
     permission view = parent->member + reader
   }")

(defn- object [type id]
  (eacl/spice-object type id))

(defn- group-id [index]
  (format "group-%06d" index))

(defn- objects
  [group-count]
  (concat
   [(object :user "user-1")
    (object :document "document-direct-1")
    (object :document "document-direct-2")]
   (map #(object :group (group-id %)) (range group-count))))

(defn- object-transactions
  [group-count]
  (map-indexed
   (fn [index {:keys [id]}]
     {:db/id (str "stable-source-benchmark-" index)
      :eacl/id id})
   (objects group-count)))

(defn- relationships
  [group-count]
  (let [user (object :user "user-1")]
    (concat
     (map
      (fn [index]
        (eacl/->Relationship
         user :member (object :group (group-id index))))
      (range group-count))
     [(eacl/->Relationship
       user :reader (object :document "document-direct-1"))
      (eacl/->Relationship
       user :reader (object :document "document-direct-2"))])))

(defn seed-adversarial!
  ([] (seed-adversarial! 2000))
  ([group-count]
   (let [conn (datascript/create-conn)
         client (datascript/make-client
                 conn
                 {:cache {:remember-answers false}
                  :source-lifecycle
                  (str "stable-source-seed-" (random-uuid))})]
     (eacl/write-schema! client adversarial-schema)
     (ds/transact! conn (vec (object-transactions group-count)))
     (doseq [batch (partition-all 500 (relationships group-count))]
       (eacl/create-relationships! client (vec batch)))
     {:conn conn
      :group-count group-count
      :query {:subject (object :user "user-1")
              :permission :view
              :resource/type :document
              :first 1
              :cache? false}})))

(defn- consumer-edge
  [rule]
  (case (:kind rule)
    :self-permission
    [(:target-node rule) (:head rule) 0]

    :arrow-permission
    [(:target-node rule) (:head rule) 1]

    nil))

(defn- exact-distances
  "Exploration oracle for one explicitly supplied root. Repeated relaxation
  is deliberately independent from the intended production 0/1 shortest-path
  generator; unreachable nodes fail rather than receiving an arbitrary rank."
  [rules root]
  (let [nodes (into #{root}
                    (mapcat
                     (fn [rule]
                       (remove nil? [(:head rule) (:target-node rule)])))
                    rules)
        edges (keep consumer-edge rules)
        infinity Long/MAX_VALUE]
    (loop [distance (assoc (zipmap nodes (repeat infinity)) root 0)
           passes 0]
      (let [next-distance
            (reduce
             (fn [result [from to cost]]
               (let [to-distance (get result to infinity)]
                 (if (and (not= infinity to-distance)
                          (< (+ cost to-distance)
                             (get result from infinity)))
                   (assoc result from (+ cost to-distance))
                   result)))
             distance
             edges)]
        (cond
          (= distance next-distance)
          (do
            (when-let [unreachable
                       (seq (keep (fn [[node d]]
                                    (when (= infinity d) node))
                                  next-distance))]
              (throw
               (ex-info "Benchmark rank graph has unreachable nodes."
                        {:root root :unreachable unreachable})))
            next-distance)

          (> passes (count nodes))
          (throw (ex-info "Benchmark rank relaxation did not converge."
                          {:root root :nodes nodes :edges edges}))

          :else
          (recur next-distance (inc passes)))))))

(defn- local-read-cost
  [rule]
  (case (:kind rule)
    :relation 1
    :self-permission 0
    :arrow-relation 2
    :arrow-permission 1
    (throw (ex-info "Unsupported benchmark rule." {:rule rule}))))

(defn- ranked-input
  [input root]
  (let [distance (exact-distances (:indexed-rules input) root)
        rank-rule
        (fn [rule]
          (assoc rule :exploration/read-rank
                 (+ (local-read-cost rule)
                    (get distance (:head rule)))))]
    (-> input
        (update :indexed-rules #(mapv rank-rule %))
        (update :seed-rules-by-subject-type
                (fn [buckets]
                  (into {}
                        (map (fn [[subject-type rules]]
                               [subject-type (mapv rank-rule rules)]))
                        buckets))))))

(defn- cost-order-key
  [value]
  [(get value :exploration/read-rank Long/MAX_VALUE)
   (secure-format/encode-canonical
    (dissoc value :exploration/read-rank))])

(def ^:private portable-order-var
  (or (ns-resolve 'eacl.engine.portable-indexed 'canonical-order-key)
      (throw (ex-info "Portable candidate order function is unavailable." {}))))

(defn- with-cost-order
  [f]
  (with-redefs-fn {portable-order-var cost-order-key} f))

(defrecord ExplorationCostRankedKernel [delegate root]
  verified/DecisionKernel
  (-decide [_ operation input]
    (verified/-decide delegate operation input))

  verified/DiscoveryOrderKernel
  (-discovery-order-abi [_]
    portable/stable-discovery-abi-version)

  verified/IndexedSpeculationKernel
  (-speculative-indexed-reads [_ direction state maximum]
    (verified/-speculative-indexed-reads
     delegate direction state maximum))

  verified/IndexedTraversalKernel
  (-compile-indexed-plan [_ input]
    (with-cost-order
      #(verified/-compile-indexed-plan delegate (ranked-input input root))))
  (-initialize-indexed [_ direction input]
    (with-cost-order
      #(verified/-initialize-indexed delegate direction input)))
  (-drive-indexed [_ direction state limits fuel]
    (verified/-drive-indexed delegate direction state limits fuel))
  (-resume-indexed [_ direction state response limits]
    (verified/-resume-indexed delegate direction state response limits))
  (-continue-indexed-page [_ direction state input]
    (verified/-continue-indexed-page delegate direction state input))
  (-read-indexed-result [_ direction state]
    (verified/-read-indexed-result delegate direction state)))

(defn- canonical-root
  [resource-type permission]
  {:resource-type (portable/canonical-schema-identity resource-type)
   :permission (portable/canonical-schema-identity permission)})

(defn- client-for
  ([conn engine-kind]
   (client-for conn engine-kind (canonical-root :document :view)))
  ([conn engine-kind root]
   (let [client
         (datascript/make-client
          conn
          {:cache {:remember-answers false}
           :source-lifecycle
           (str "stable-source-" (name engine-kind) "-" (random-uuid))})]
     (case engine-kind
       :legacy client
       :byte-stable
       (assoc-in client [:opts :decision-kernel]
                 production-kernel/stable-discovery-selection)
       :cost-stable
       (assoc-in
        client [:opts :decision-kernel]
        {:kernel
         (->ExplorationCostRankedKernel
          production-kernel/stable-discovery-java-kernel
          root)})))))

(defn- run-once
  ([conn query engine-kind]
   (run-once conn query engine-kind nil true))
  ([conn query engine-kind existing-client]
   (run-once conn query engine-kind existing-client true))
  ([conn query engine-kind existing-client include-full?]
   (let [client (or existing-client (client-for conn engine-kind))
        acyclic (atom {})
        generated (atom {})
        backend-ops (atom {})
        backend-work (atom {})
        trace (atom [])
        allocated-before (current-thread-allocated-bytes)
        start (System/nanoTime)
        result
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* generated
                  engine/*backend-work-stats* backend-work
                  engine/*execution-trace* trace
                  backend/*backend-op-stats* backend-ops]
          (eacl/lookup-resources
           client
           query))
        elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
        allocated-after (current-thread-allocated-bytes)
        commands (filter #(= :generated-command (:event %)) @trace)
        full-data
        (when include-full?
          (:data
           (eacl/lookup-resources
            client
            (assoc query :first 10))))]
    {:engine engine-kind
     :elapsed-ms elapsed-ms
     :allocated-bytes
     (when (and allocated-before allocated-after)
       (- allocated-after allocated-before))
     :data (:data result)
     :full-data full-data
     :has-next? (get-in result [:page-info :has-next-page?])
     :generated-commands (count commands)
     :generated-dimensional-counters
     (:generated-dimensional-counters @generated)
     :acyclic @acyclic
     :backend-ops @backend-ops
     :backend-work @backend-work})))

(defn run-adversarial!
  ([] (run-adversarial! 2000))
  ([group-count]
   (let [{:keys [conn query] :as seeded}
         (seed-adversarial! group-count)
         runs (mapv #(run-once conn query %)
                    [:legacy :byte-stable :cost-stable])
         result-sets (mapv (comp set :full-data) runs)]
     (when-not (and (apply = result-sets)
                    (every? #(= 2 (count (:full-data %))) runs)
                    (every? :has-next? runs))
       (throw (ex-info "Benchmark engines disagree on complete result set or first-page lookahead."
                       {:runs runs})))
     (assoc (select-keys seeded [:group-count]) :runs runs))))

(defn- percentile
  [values fraction]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (max 0 (dec (long (Math/ceil (* fraction
                                                   (count ordered)))))))]
    (nth ordered index)))

(defn run-samples!
  "Seeds once, performs one unreported JIT warmup per engine, then reports
  logical work and latency distributions. DataScript storage is warm; these
  wall times are a CPU/allocation smoke signal, not a remote-backend claim."
  ([group-count] (run-samples! group-count 10))
  ([group-count samples]
   (let [{:keys [conn query]} (seed-adversarial! group-count)
         engines [:legacy :byte-stable :cost-stable]
         clients (into {}
                       (map (fn [engine-kind]
                              [engine-kind (client-for conn engine-kind)]))
                       engines)]
     (let [correctness-runs
           (mapv #(run-once conn query % (get clients %) true) engines)]
       (when-not (and (apply = (map (comp set :full-data)
                                    correctness-runs))
                      (every? #(= 2 (count (:full-data %)))
                              correctness-runs)
                      (every? :has-next? correctness-runs))
         (throw (ex-info "Sample engines disagree on complete denotation."
                         {:runs correctness-runs}))))
     (doseq [engine-kind engines]
       (run-once conn query engine-kind (get clients engine-kind) false))
     {:group-count group-count
      :samples samples
      :engines
      (mapv
       (fn [engine-kind]
         (let [client (get clients engine-kind)
               runs (mapv (fn [_]
                            (run-once conn query engine-kind client false))
                          (range samples))
               elapsed (mapv :elapsed-ms runs)
               allocated (into [] (keep :allocated-bytes) runs)
               commands
               (mapv (fn [run]
                       (if (= :legacy engine-kind)
                         (get-in run [:acyclic :backend-scans])
                         (:generated-commands run)))
                     runs)]
           (when-not (and (apply = commands)
                          (every? :has-next? runs))
             (throw (ex-info "Source benchmark was not deterministic."
                             {:engine engine-kind :runs runs})))
           {:engine engine-kind
            :backend-commands (first commands)
            :elapsed-ms
            {:min (apply min elapsed)
             :median (percentile elapsed 0.5)
             :p95 (percentile elapsed 0.95)
             :max (apply max elapsed)}
            :allocated-bytes
            (when (seq allocated)
              {:min (apply min allocated)
               :median (percentile allocated 0.5)
               :p95 (percentile allocated 0.95)
               :max (apply max allocated)})}))
       engines)})))

(defn seed-recursive!
  [config]
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn
         {:cache {:remember-answers false}
          :source-lifecycle
          (str "stable-recursive-seed-" (random-uuid))})]
    (eacl/write-schema! client (recursive-fixture/schema-for config))
    (ds/transact! conn (vec (recursive-fixture/object-transactions config)))
    (doseq [batch (recursive-fixture/relationship-batches config)]
      (eacl/create-relationships! client (vec batch)))
    {:conn conn :config config}))

(defn- run-recursive-page
  [conn config subject engine-kind page-size]
  (let [permission (recursive-fixture/view-permission config)
        root (canonical-root :account permission)
        client (client-for conn engine-kind root)
        query (assoc (recursive-fixture/resource-query
                      config subject page-size)
                     :cache? false)
        acyclic (atom {})
        generated (atom {})
        backend-work (atom {})
        trace (atom [])
        start (System/nanoTime)
        result
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* generated
                  engine/*backend-work-stats* backend-work
                  engine/*execution-trace* trace]
          (eacl/lookup-resources client query))
        elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
        commands (filter #(= :generated-command (:event %)) @trace)]
    {:engine engine-kind
     :elapsed-ms elapsed-ms
     :data (:data result)
     :has-next? (get-in result [:page-info :has-next-page?])
     :backend-commands
     (if (= :legacy engine-kind)
       (or (get-in @generated
                   [:generated-dimensional-counters :backend-commands])
           (:backend-scans @acyclic))
       (count commands))
     :generated-dimensional-counters
     (:generated-dimensional-counters @generated)
     :backend-work @backend-work
     :client client
     :query query}))

(defn run-recursive-case!
  "Compares first-page work and then exhaustively checks the full denotation.
  Intended for bounded exploration fixtures, not large latency campaigns."
  [config subject page-size]
  (let [{:keys [conn]} (seed-recursive! config)
        engines [:legacy :byte-stable :cost-stable]
        expected-count
        (recursive-fixture/expected-view-count config subject)
        first-pages
        (mapv #(run-recursive-page
                conn config subject % page-size)
              engines)
        full-runs
        (mapv
         (fn [engine-kind]
           (run-recursive-page
            conn config subject engine-kind
            (max 1 expected-count)))
         engines)
        full-sets (mapv (comp set :data) full-runs)]
    (when-not (and (apply = full-sets)
                   (every? #(= expected-count (count (:data %))) full-runs)
                   (every? #(= (pos? (- expected-count page-size))
                               (boolean (:has-next? %)))
                           first-pages))
      (throw
       (ex-info "Recursive source benchmark engines disagree."
                {:config config
                 :subject subject
                 :expected-count expected-count
                 :first-pages (mapv #(dissoc % :client) first-pages)
                 :full-counts (mapv #(count (:data %)) full-runs)})))
    {:config config
     :subject subject
     :expected-count expected-count
     :first-pages
     (mapv #(select-keys % [:engine :elapsed-ms :backend-commands
                            :has-next? :generated-dimensional-counters
                            :backend-work])
           first-pages)
     :full-commands
     (mapv #(select-keys % [:engine :backend-commands]) full-runs)}))

(defn run-recursive-suite!
  ([] (run-recursive-suite! 200))
  ([accounts]
   (mapv
    (fn [[config subject page-size]]
      (run-recursive-case! config subject page-size))
    [[{:shape :star :accounts accounts}
      recursive-fixture/user-1 1]
     [{:shape :chain :accounts accounts}
      recursive-fixture/user-1 1]
     [{:shape :broad-union :accounts accounts}
      recursive-fixture/reader-1 1]
     [{:shape :star :accounts accounts}
      recursive-fixture/stranger 1]
     [{:shape :mutual :accounts (min accounts 100)}
      recursive-fixture/user-1 1]])))
