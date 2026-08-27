(ns eacl.bench.authorization-amplification-test
  "Reproducible pre/post evidence for authorization request amplification.

  This benchmark is deliberately excluded from normal correctness suites.
  Invoke `run-baseline!` through a Datalevin-enabled nREPL and retain the
  complete returned EDN, including raw samples and environment metadata."
  (:require [clojure.test :refer [deftest is]]
            [clojure.java.shell :as shell]
            [datalevin.constants :as constants]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.bench.paired :as paired]
            [eacl.client.orchestration :as orchestration]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as datalevin-backend]
            [eacl.datalevin.core :as datalevin]
            [eacl.datalevin.impl :as datalevin-impl]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.v8 :as engine]
            [eacl.execution :as execution]
            [eacl.proof-frame :as proof-frame]
            [eacl.relay :as relay]
            [eacl.request.counters :as request-counters])
  (:import [com.sun.management OperatingSystemMXBean ThreadMXBean]
           [java.lang ProcessHandle]
           [java.lang.management ManagementFactory]))

(def ^:private test-key "01234567890123456789012345678901")
(def ^:private scalar-warmups 25)
(def ^:private scalar-samples 51)
(def ^:private cache-gate-samples 101)
(def ^:private page-warmups 7)
(def ^:private page-samples 21)
(def ^:private document-count 256)
(def ^:private page-size 10)
(def ^:private probe-page-size 32)
(def ^:private pre-change-acquisition-allocation-p50 226728)
(def ^:private acquisition-allocation-ceiling
  (quot pre-change-acquisition-allocation-p50 4))

(def ^:private fixed-cost-allocation-ratchets
  {:contract-normalization
   {:series :relationship-page-10
    :phase :contract-normalization
    :maximum-allocation-p50 2304
    :counter :contract-normalizations
    :expected-counter 1}
   :identity-conversion
   {:series :relationship-page-10
    :phase :identity-conversion
    :maximum-allocation-p50 110000
    :counter :identity-conversions
    :expected-counter 24}
   :cache-key-construction
   {:series :cache-hit-check
    :phase :cache-key-and-lookup
    :maximum-allocation-p50 30000
    :counter :cache-key-builds
    :expected-counter 1}
   :result-rendering
   {:series :relationship-page-10}
   :phase :rendering
   :maximum-allocation-p50 26000
   :counter :renderings
   :expected-counter 1
   :cursor-minting
   {:series :relationship-page-10
    :phase :cursor-minting
    :maximum-allocation-p50 34000
    :counter :cursor-builds
    :expected-counter 2}})

(def ^:private profiled-phases
  [:contract-normalization
   :selection
   :identity-conversion
   :proof-frame
   :cache-key-and-lookup
   :evaluation
   :rendering
   :cursor-minting])

(def ^:private phase-index
  (zipmap profiled-phases (range)))

(def ^:dynamic *phase-totals* nil)
(def ^:dynamic *phase-frame* nil)

(def ^:private schema
  "definition user {}
   definition document {
     relation listed: user
     relation viewer: user
     permission view = viewer
   }")

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* p (dec (count ordered))))))]
    (nth ordered index)))

(defn- summary
  [samples]
  {:min (apply min samples)
   :p50 (percentile samples 0.50)
   :p95 (percentile samples 0.95)
   :p99 (percentile samples 0.99)
   :max (apply max samples)
   :mean (/ (reduce + samples) (double (count samples)))})

(defn- allocation-bean
  []
  (let [bean (ManagementFactory/getThreadMXBean)]
    (when (instance? ThreadMXBean bean)
      (let [bean ^ThreadMXBean bean]
        (when (.isThreadAllocatedMemorySupported bean)
          (when-not (.isThreadAllocatedMemoryEnabled bean)
            (.setThreadAllocatedMemoryEnabled bean true))
          bean)))))

(def ^:private allocated-memory-bean (delay (allocation-bean)))

(defn- allocated-bytes
  []
  (when-let [bean @allocated-memory-bean]
    (.getThreadAllocatedBytes
     ^ThreadMXBean bean (.getId (Thread/currentThread)))))

(defn- profiled-call
  "Measures one named span and subtracts nested named spans from its result.

  The recorder is a primitive long array so attribution does not allocate a
  map for every observed call. Unnamed orchestration and the small dynamic
  instrumentation cost remain visible as the operation's explicit
  `:unattributed` remainder."
  [phase f]
  (if-not *phase-totals*
    (f)
    (let [parent *phase-frame*
          children (long-array 2)
          allocated-before (allocated-bytes)
          started (System/nanoTime)]
      (try
        (binding [*phase-frame* children]
          (f))
        (finally
          (let [elapsed (- (System/nanoTime) started)
                allocated-after (allocated-bytes)
                allocated (when (and allocated-before allocated-after)
                            (- allocated-after allocated-before))
                index (get phase-index phase)
                offset (* 3 index)]
            (aset-long ^longs *phase-totals* offset
                       (inc (aget ^longs *phase-totals* offset)))
            (aset-long ^longs *phase-totals* (inc offset)
                       (+ (aget ^longs *phase-totals* (inc offset))
                          (- elapsed (aget children 0))))
            (when allocated
              (aset-long ^longs *phase-totals* (+ offset 2)
                         (+ (aget ^longs *phase-totals* (+ offset 2))
                            (- allocated (aget children 1)))))
            (when parent
              (aset-long ^longs parent 0 (+ (aget ^longs parent 0) elapsed))
              (when allocated
                (aset-long ^longs parent 1
                           (+ (aget ^longs parent 1) allocated))))))))))

(defn- phase-wrapper
  [phase f]
  (fn [& args]
    (profiled-call phase #(apply f args))))

(defn- call-with-profiled-boundaries
  [f]
  (let [normalize execution/normalize
        select-request-basis @#'orchestration/select-request-basis
        request-frame proof-frame/request-frame
        resolve-proof proof-frame/resolve!
        cached-engine-result @#'orchestration/cached-engine-result
        can? engine/can?
        read-relationships datalevin-impl/read-relationships
        externalize-relationship-page relay/externalize-relationship-page
        externalize-page-cursors @#'relay/externalize-page-cursors
        invoke backend/invoke]
    (with-redefs-fn
      {#'execution/normalize
       (phase-wrapper :contract-normalization normalize)
       #'orchestration/select-request-basis
       (phase-wrapper :selection select-request-basis)
       #'proof-frame/request-frame
       (phase-wrapper :proof-frame request-frame)
       #'proof-frame/resolve!
       (phase-wrapper :proof-frame resolve-proof)
       #'orchestration/cached-engine-result
       (phase-wrapper :cache-key-and-lookup cached-engine-result)
       #'engine/can?
       (phase-wrapper :evaluation can?)
       #'datalevin-impl/read-relationships
       (phase-wrapper :evaluation read-relationships)
       #'relay/externalize-relationship-page
       (phase-wrapper :rendering externalize-relationship-page)
       #'relay/externalize-page-cursors
       (phase-wrapper :cursor-minting externalize-page-cursors)
       #'backend/invoke
       (fn [adapter operation & args]
         (if (#{:object-id->internal :internal-id->object} operation)
           (profiled-call
            :identity-conversion
            #(apply invoke adapter operation args))
           (apply invoke adapter operation args)))}
      f)))

(defn- profile-once
  [f]
  (let [totals (long-array (* 3 (count profiled-phases)))
        allocated-before (allocated-bytes)
        started (System/nanoTime)
        value
        (binding [*phase-totals* totals]
          (f))
        elapsed (- (System/nanoTime) started)
        allocated-after (allocated-bytes)
        allocated (when (and allocated-before allocated-after)
                    (- allocated-after allocated-before))
        phases
        (into
         {}
         (map
          (fn [phase]
            (let [offset (* 3 (get phase-index phase))]
              [phase
               {:calls (aget totals offset)
                :elapsed-ns (aget totals (inc offset))
                :allocated-bytes (aget totals (+ offset 2))}]))
          profiled-phases))
        attributed-time (reduce + (map :elapsed-ns (vals phases)))
        attributed-allocation
        (when allocated
          (reduce + (map :allocated-bytes (vals phases))))]
    {:value value
     :elapsed-ns elapsed
     :allocated-bytes allocated
     :phases phases
     :unattributed
     {:elapsed-ns (max 0 (- elapsed attributed-time))
      :allocated-bytes
      (when allocated
        (max 0 (- allocated attributed-allocation)))}}))

(defn- profile-distribution
  [warmups samples f]
  (let [observations
        (vec
         (for [iteration (range (+ warmups samples))]
           (profile-once #(f iteration))))
        measured (subvec observations warmups)
        summarize
        (fn [values]
          (assoc (summary values) :raw (vec values)))
        phase-result
        (fn [phase]
          {:calls
           (summarize
            (mapv #(get-in % [:phases phase :calls]) measured))
           :latency-us
           (summarize
            (mapv #(/ (double (get-in % [:phases phase :elapsed-ns]))
                      1000.0)
                  measured))
           :allocated-bytes
           (when (every? some? (map :allocated-bytes measured))
             (summarize
              (mapv #(get-in % [:phases phase :allocated-bytes])
                    measured)))})]
    {:samples samples
     :warmups warmups
     :latency-us
     (summarize (mapv #(/ (double (:elapsed-ns %)) 1000.0) measured))
     :allocated-bytes
     (when (every? some? (map :allocated-bytes measured))
       (summarize (mapv :allocated-bytes measured)))
     :phases (into {} (map (fn [phase] [phase (phase-result phase)]))
                   profiled-phases)
     :unattributed
     {:latency-us
      (summarize
       (mapv #(/ (double (get-in % [:unattributed :elapsed-ns])) 1000.0)
             measured))
      :allocated-bytes
      (when (every? some? (map :allocated-bytes measured))
        (summarize
         (mapv #(get-in % [:unattributed :allocated-bytes]) measured)))}
     :checksums (mapv #(hash (:value %)) measured)}))

(defn- distribution
  [warmups samples f]
  (let [latencies (transient [])
        allocations (transient [])
        checksums (transient [])]
    (dotimes [iteration (+ warmups samples)]
      (let [allocated-before (allocated-bytes)
            started (System/nanoTime)
            value (f iteration)
            elapsed (- (System/nanoTime) started)
            allocated-after (allocated-bytes)]
        (when (>= iteration warmups)
          (conj! latencies (/ (double elapsed) 1000.0))
          (conj! checksums (hash value))
          (when (and allocated-before allocated-after)
            (conj! allocations (- allocated-after allocated-before))))))
    (let [latencies (persistent! latencies)
          allocations (persistent! allocations)]
      {:unit :microseconds
       :samples samples
       :warmups warmups
       :latency-us (assoc (summary latencies) :raw latencies)
       :allocated-bytes
       (when (seq allocations)
         (assoc (summary allocations) :raw allocations))
       :checksums (persistent! checksums)})))

(defn- benchmark-client
  [conn]
  (let [watermark (atom 0)]
    (datalevin/make-client
     conn
     {:source-lifecycle "authorization-amplification-baseline"
      :revision-watermark watermark
      :advance-revision-watermark! #(swap! watermark max %)
      :datalevin-topology
      datalevin-backend/certified-topology-declaration
      :security-key test-key})))

(defn- fixture-relationships
  [{:keys [catalog alice bob documents]}]
  (into
   []
   cat
   [(map #(eacl/->Relationship catalog :listed %) documents)
    (map #(eacl/->Relationship alice :viewer %) documents)
    (map #(eacl/->Relationship bob :viewer %)
         (take-nth 5 documents))]))

(defn- with-system
  [f]
  (let [dir (u/tmp-dir
             (str "eacl-authorization-amplification-" (random-uuid)))
        conn (datalevin/create-conn dir)
        client (benchmark-client conn)]
    (try
      (eacl/write-schema! client schema)
      (d/transact!
       conn
       (into
        (mapv (fn [id] {:eacl/id id}) ["catalog" "alice" "bob" "carol"])
        (map (fn [index] {:eacl/id (str "document-" index)})
             (range document-count))))
      (let [catalog (eacl/spice-object :user "catalog")
            alice (eacl/spice-object :user "alice")
            bob (eacl/spice-object :user "bob")
            carol (eacl/spice-object :user "carol")
            documents
            (mapv #(eacl/spice-object :document (str "document-" %))
                  (range document-count))
            fixture {:dir dir
                     :conn conn
                     :client client
                     :catalog catalog
                     :alice alice
                     :bob bob
                     :carol carol
                     :documents documents}]
        (eacl/create-relationships! client (fixture-relationships fixture))
        (f fixture))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn- relationship-query
  [first after]
  (cond-> {:subject/type :user
           :subject/id "catalog"
           :resource/relation :listed
           :resource/type :document
           :first first
           :cache? false}
    after (assoc :after after)))

(defn- allowed?
  [acl subject relationship]
  (:allowed?
   (eacl/check-permission
    acl
    {:subject subject
     :permission :view
     :resource (:resource relationship)
     :cache? false})))

(defn- any-authorized-after?
  [acl subject after]
  (loop [after after]
    (let [result
          (eacl/read-relationships
           acl (relationship-query probe-page-size after))
          page (:page-info result)]
      (cond
        (some #(allowed? acl subject %) (:data result)) true
        (:has-next-page? page) (recur (:end-cursor page))
        :else false))))

(defn- scalar-loop-page
  [acl subject]
  (loop [after nil
         allowed []]
    (let [remaining (- page-size (count allowed))
          result
          (eacl/read-relationships acl (relationship-query remaining after))
          page (:page-info result)
          allowed' (into allowed (filter #(allowed? acl subject %)) (:data result))
          end-cursor (:end-cursor page)]
      (cond
        (= page-size (count allowed'))
        {:data allowed'
         :page-info
         {:end-cursor end-cursor
          :has-next-page?
          (and (:has-next-page? page)
               (any-authorized-after? acl subject end-cursor))}}

        (:has-next-page? page)
        (recur end-cursor allowed')

        :else
        {:data allowed'
         :page-info {:end-cursor end-cursor
                     :has-next-page? false}}))))

(defn- measured-scalar-loop
  [client subject]
  (eacl/with-snapshot [snapshot (eacl/snapshot client)]
    (scalar-loop-page snapshot subject)))

(declare environment)

(defn- scan-route-page
  [client subject candidate-window]
  (eacl/read-relationships
   client
   (assoc (relationship-query page-size nil)
          :authorization {:subject subject
                          :permission :view
                          :on :resource}
          :aggregate-limits {:candidate-window candidate-window})))

(defn- enumerate-route-page
  [client subject catalog candidate-window]
  (eacl/lookup-resources
   client
   {:subject subject
    :permission :view
    :resource/type :document
    :resource/relationship {:relation :listed
                            :subject catalog}
    :first page-size
    :cache? false
    :aggregate-limits {:candidate-window candidate-window}}))

(defn- ratio-reduction
  [baseline candidate]
  (- 1.0 (/ (double candidate) (double baseline))))

(def ^:private pre-change-release-p50
  {:dense {:latency-us 3326.917
           :allocated-bytes 9436264}
   :all-rejected {:latency-us 62639.541
                  :allocated-bytes 193244640}})

(def ^:private aggregate-absolute-ceilings
  {["Mac OS X" "aarch64" "26"]
   {:scan-route-dense
    {:latency-p50-us 1000
     :allocation-p50-bytes 1200000}
    :scan-route-sparse
    {:latency-p50-us 2500
     :allocation-p50-bytes 3200000}
    :enumerate-route-all-rejected
    {:latency-p50-us 750
     :allocation-p50-bytes 500000}}})

(defn- release-comparison
  [arm-result baseline minimum-reduction]
  (let [latency-reduction
        (ratio-reduction (:latency-us baseline)
                         (get-in arm-result [:latency-us :p50]))
        allocation-reduction
        (ratio-reduction (:allocated-bytes baseline)
                         (get-in arm-result [:allocated-bytes :p50]))]
    {:baseline baseline
     :minimum-reduction minimum-reduction
     :latency-reduction latency-reduction
     :allocation-reduction allocation-reduction
     :passed? (and (<= minimum-reduction latency-reduction)
                   (<= minimum-reduction allocation-reduction))}))

(defn run-aggregate-gate!
  "Runs the interleaved scalar-loop versus aggregate-route release gates."
  []
  (with-system
    (fn [{:keys [client catalog alice bob carol]}]
      (let [paired-report
            (paired/run-paired!
             {:arms
              [[:scalar-loop-dense
                (fn [_] (measured-scalar-loop client alice))]
               [:scan-route-dense
                (fn [_] (scan-route-page client alice 11))]
               [:scalar-loop-sparse
                (fn [_] (measured-scalar-loop client bob))]
               [:scan-route-sparse
                (fn [_] (scan-route-page client bob 64))]
               [:scalar-loop-all-rejected
                (fn [_] (measured-scalar-loop client carol))]
               [:enumerate-route-all-rejected
                (fn [_]
                  (enumerate-route-page
                   client carol catalog document-count))]]
              :warmups page-warmups
              :samples page-samples
              :absolute-ceilings aggregate-absolute-ceilings
              :comparisons
              [{:baseline :scalar-loop-dense
                :candidate :scan-route-dense
                :minimum-latency-reduction 0.30
                :minimum-allocation-reduction 0.40}
               {:baseline :scalar-loop-sparse
                :candidate :scan-route-sparse
                :minimum-latency-reduction 0.40
                :minimum-allocation-reduction 0.40}
               {:baseline :scalar-loop-all-rejected
                :candidate :enumerate-route-all-rejected
                :minimum-latency-reduction 0.90
                :minimum-allocation-reduction 0.90}]})
            arms (:arms paired-report)
            release
            {:dense
             (release-comparison
              (:scan-route-dense arms)
              (:dense pre-change-release-p50) 0.70)
             :all-rejected
             (release-comparison
              (:enumerate-route-all-rejected arms)
              (:all-rejected pre-change-release-p50) 0.90)}]
        (assoc paired-report
               :benchmark
               :authorization-request-amplification-aggregate-gate
               :phase :post-aggregate-routes
               :fixture
               {:dense :alice-views-every-document
                :sparse :bob-views-every-fifth-document
                :all-rejected :carol-views-no-documents
                :relationship-filter :catalog-listed-document}
               :release-comparisons release
               :passed?
               (and (every? :passed? (:comparisons paired-report))
                    (every? :passed? (vals release))
                    (or (= :not-applicable
                           (get-in paired-report
                                   [:absolute-ceilings :status]))
                        (true?
                         (get-in paired-report
                                 [:absolute-ceilings :passed?]))))
               :active-readers-after
               (d/active-read-snapshot-info))))))

(defn run-aggregate-counter-gate!
  "Captures deterministic amplification counters for both page routes."
  []
  (with-system
    (fn [{:keys [client catalog alice]}]
      (let [capture
            (fn [f]
              (let [ledger (request-counters/make-ledger)
                    value
                    (binding [request-counters/*ledger* ledger]
                      (f))]
                {:value value
                 :counters (request-counters/snapshot ledger)}))
            scan (capture #(scan-route-page client alice 11))
            enumerate
            (capture #(enumerate-route-page client alice catalog 11))]
        {:format-version 1
         :benchmark
         :authorization-request-amplification-aggregate-counter-gate
         :environment (environment)
         :routes
         {:scan scan
          :enumerate enumerate}
         :passed?
         (and
          (every?
           (fn [{:keys [counters]}]
             (= {:acquisitions 1
                 :releases 1
                 :public-entries 1
                 :context-constructions 1}
                (select-keys
                 counters
                 [:acquisitions :releases :public-entries
                  :context-constructions])))
           [scan enumerate])
          (<= (get-in scan [:counters :candidates-examined]) 11)
          (zero? (get-in scan [:counters :probes]))
          (= (get-in enumerate [:counters :candidates-examined])
             (get-in enumerate [:counters :probes]))
          (<= (get-in enumerate [:counters :candidates-examined]) 11))
         :active-readers-after (d/active-read-snapshot-info)}))))

(defn- used-heap-bytes
  []
  (let [runtime (Runtime/getRuntime)]
    (- (.totalMemory runtime) (.freeMemory runtime))))

(defn- committed-virtual-memory-bytes
  []
  (let [bean (ManagementFactory/getOperatingSystemMXBean)]
    (when (instance? OperatingSystemMXBean bean)
      (.getCommittedVirtualMemorySize ^OperatingSystemMXBean bean))))

(defn- resident-set-bytes
  []
  (let [{:keys [exit out]}
        (shell/sh "ps" "-o" "rss=" "-p"
                  (str (.pid (ProcessHandle/current))))]
    (when (zero? exit)
      (some-> out .trim parse-long (* 1024)))))

(defn- forced-gc-observation
  []
  (dotimes [_ 3]
    (System/gc)
    (System/runFinalization))
  {:used-heap-bytes (used-heap-bytes)
   :resident-set-bytes (resident-set-bytes)
   :committed-virtual-memory-bytes
   (committed-virtual-memory-bytes)})

(defn- caught-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(defn run-aggregate-resource-gate!
  "Exercises retained memory, native address space, reader pressure, and
  repeated post-acquisition failures for both aggregate page routes."
  []
  (with-system
    (fn [{:keys [conn client catalog alice bob]}]
      (let [scan #(scan-route-page client alice 11)
            enumerate #(enumerate-route-page client alice catalog 11)]
        (dotimes [index 40]
          ((if (even? index) scan enumerate)))
        (let [before (forced-gc-observation)]
          (dotimes [index 400]
            ((if (even? index) scan enumerate)))
          (let [after (forced-gc-observation)
                heap-delta
                (max 0 (- (:used-heap-bytes after)
                          (:used-heap-bytes before)))
                native-before (:committed-virtual-memory-bytes before)
                native-after (:committed-virtual-memory-bytes after)
                native-delta
                (when (and native-before native-after)
                  (max 0 (- native-after native-before)))
                rss-before (:resident-set-bytes before)
                rss-after (:resident-set-bytes after)
                rss-delta
                (when (and rss-before rss-after)
                  (max 0 (- rss-after rss-before)))
                held (mapv (fn [_] (d/open-read-snapshot conn))
                           (range 32))
                reader-pressure
                (try
                  (let [observations
                        (mapv
                         (fn [index]
                           ((if (even? index) scan enumerate))
                           (d/active-read-snapshot-info))
                         (range 32))]
                    {:held 32
                     :observations observations
                     :passed?
                     (every? #(= 32 (:active %)) observations)})
                  (finally
                    (doseq [snapshot (reverse held)]
                      (d/close-read-snapshot! snapshot))))
                failures
                (mapv
                 (fn [index]
                   (let [scan? (even? index)
                         failure
                         (caught-data
                          (if scan?
                            #(eacl/read-relationships
                              client
                              (assoc
                               (relationship-query page-size nil)
                               :cache? false
                               :authorization
                               {:subject bob
                                :permission :view
                                :on :resource}
                               :aggregate-limits
                               {:candidate-window 64
                                :max-commands 1}))
                            #(eacl/lookup-resources
                              client
                              {:subject alice
                               :permission :view
                               :resource/type :document
                               :resource/relationship
                               {:relation :listed
                                :subject catalog}
                               :first page-size
                               :cache? false
                               :aggregate-limits
                               {:candidate-window 11
                                :max-probes 1}})))]
                     {:route (if scan? :scan :enumerate)
                      :error (:type failure)
                      :active-readers
                      (:active (d/active-read-snapshot-info))}))
                 (range 100))
                failures-pass?
                (every?
                 #(and (= :eacl.execution/resource-limit-exceeded
                          (:error %))
                       (zero? (:active-readers %)))
                 failures)
                passed?
                (and (<= heap-delta (* 16 1024 1024))
                     (or (nil? rss-delta)
                         (<= rss-delta (* 64 1024 1024)))
                     (:passed? reader-pressure)
                     failures-pass?
                     (= {:active 0 :oldest-age-ms nil}
                        (d/active-read-snapshot-info)))]
            {:format-version 1
             :benchmark
             :authorization-request-amplification-aggregate-resource-gate
             :environment (environment)
             :iterations {:warmup 40 :measured 400
                          :repeated-failures 100}
             :forced-gc
             {:before before
              :after after
              :retained-heap-delta-bytes heap-delta
              :maximum-retained-heap-delta-bytes (* 16 1024 1024)
              :resident-set-delta-bytes rss-delta
              :maximum-resident-set-delta-bytes (* 64 1024 1024)
              :native-address-space-delta-bytes native-delta
              :native-address-space-qualification
              :observation-only-jvm-committed-virtual-memory-is-not-rss}
             :reader-pressure
             (dissoc reader-pressure :observations)
             :repeated-failures
             {:routes (frequencies (map :route failures))
              :errors (frequencies (map :error failures))
              :maximum-active-readers
              (apply max (map :active-readers failures))
              :passed? failures-pass?}
             :active-readers-after (d/active-read-snapshot-info)
             :passed? passed?}))))))

(defn- op-counts
  [stats]
  {:definition-reads
   (+ (get stats :relation-defs 0)
      (get stats :permission-defs 0))
   :proof-frame-reads (get stats :proof-frame 0)
   :direct-match-probes (get stats :direct-match? 0)
   :subject-resource-scans (get stats :subject->resources 0)
   :resource-subject-scans (get stats :resource->subjects 0)})

(defn- measured
  [stats seal-count warmups samples f]
  (let [before-stats @stats
        before-seals @seal-count
        result (distribution warmups samples f)
        after-stats @stats]
    (assoc result
           :operation-counts
           (assoc (op-counts (merge-with - after-stats before-stats))
                  :plan-seals (- @seal-count before-seals)))))

(defn- environment
  []
  (let [runtime (Runtime/getRuntime)]
    {:captured-at (str (java.time.Instant/now))
     :os (System/getProperty "os.name")
     :os-version (System/getProperty "os.version")
     :architecture (System/getProperty "os.arch")
     :java-version (System/getProperty "java.version")
     :java-vendor (System/getProperty "java.vendor")
     :jvm-name (System/getProperty "java.vm.name")
     :jvm-options
     (vec (.getInputArguments (ManagementFactory/getRuntimeMXBean)))
     :maximum-heap-bytes (.maxMemory runtime)
     :available-processors (.availableProcessors runtime)
     :datalevin-version constants/version
     :documents document-count
     :sparse-acceptance-ratio 0.20
     :page-size page-size}))

(defn- profiled-client
  [client]
  (let [opts (:runtime client)
        convert (:spice-object->internal opts)]
    (assoc
     client :opts
     (assoc
      opts :spice-object->internal
      (fn [& args]
        (profiled-call
         :identity-conversion
         #(apply convert args)))))))

(defn run-ratchet-origin!
  "Profiles exclusive fixed-cost phases on the retained Datalevin fixture.

  The result is the ratchet origin for tasks 4.2--4.4. Each operation keeps
  raw per-call samples. Named spans are exclusive of nested named spans;
  lifecycle glue and profiler overhead are retained under `:unattributed`
  instead of being silently assigned to a convenient phase."
  []
  (with-system
    (fn [{:keys [client alice documents]}]
      (let [client (profiled-client client)
            provider (:source client)
            demand {:subject alice
                    :permission :view
                    :resource (first documents)}
            _ (eacl/check-permission client demand)]
        (call-with-profiled-boundaries
         (fn []
           {:format-version 1
            :benchmark :authorization-request-amplification-phase-profile
            :phase :ratchet-origin
            :implementation-stage
            :request-context-complete-before-scalar-fixed-cost-ratchets
            :environment (environment)
            :method
            {:clock :system-nano-time
             :allocation-source :current-thread-allocated-bytes
             :attribution :exclusive-nested-spans
             :unattributed
             "Lifecycle glue, boundary code without a named span, and the small profiling-instrumentation remainder."
             :scalar {:warmups scalar-warmups :samples scalar-samples}}
            :series
            {:cache-hit-check
             (profile-distribution
              scalar-warmups scalar-samples
              (fn [_] (eacl/check-permission client demand)))
             :cache-bypass-check
             (profile-distribution
              scalar-warmups scalar-samples
              (fn [_]
                (eacl/check-permission
                 client (assoc demand :cache? false))))
             :relationship-page-10
             (profile-distribution
              scalar-warmups scalar-samples
              (fn [_]
                (eacl/read-relationships
                 client (relationship-query page-size nil))))
             :provider-acquisition
             (profile-distribution
              scalar-warmups scalar-samples
              (fn [_]
                (profiled-call
                 :selection
                 (fn []
                   (let [selection
                         (source/acquire! provider :current)]
                     (try
                       (source/semantic-identity selection)
                       (finally
                         (source/release! selection))))))))}
            :active-readers-after (d/active-read-snapshot-info)}))))))

(defn run-acquisition-gate!
  "Runs the post-change provider acquisition series against the retained
  pre-change p50 allocation and its one-quarter ceiling."
  []
  (with-system
    (fn [{:keys [client]}]
      (let [provider (:source client)
            result
            (distribution
             scalar-warmups scalar-samples
             (fn [_]
               (let [selection
                     (source/acquire! provider :current)]
                 (try
                   (source/semantic-identity selection)
                   (finally
                     (source/release! selection))))))
            allocation-p50 (get-in result [:allocated-bytes :p50])]
        {:format-version 1
         :benchmark :authorization-request-amplification-acquisition-gate
         :phase :post-revision-only-acquisition
         :environment (environment)
         :pre-change-allocation-p50
         pre-change-acquisition-allocation-p50
         :maximum-allocation-p50 acquisition-allocation-ceiling
         :result result
         :passed? (<= allocation-p50 acquisition-allocation-ceiling)
         :active-readers-after (d/active-read-snapshot-info)}))))

(defn run-cache-hit-gate!
  "Interleaves one completed-answer hit with the same memoized direct-relation
  demand under explicit cache bypass. The hit must win on both p50 measures."
  []
  (with-system
    (fn [{:keys [client alice documents]}]
      (let [demand {:subject alice
                    :permission :view
                    :resource (first documents)}
            _ (eacl/check-permission client demand)
            report
            (paired/run-paired!
             {:arms
              [[:cache-hit
                (fn [_]
                  (eacl/check-permission client demand))]
               [:cache-bypass
                (fn [_]
                  (eacl/check-permission
                   client (assoc demand :cache? false)))]]
              :warmups scalar-warmups
              :samples cache-gate-samples
              :comparisons
              [{:baseline :cache-bypass
                :candidate :cache-hit
                :minimum-latency-reduction 0.0
                :minimum-allocation-reduction 0.0}]})]
        (assoc report
               :benchmark :authorization-request-amplification-cache-hit-gate
               :phase :post-hit-path-fix
               :active-readers-after (d/active-read-snapshot-info))))))

(defn- request-counts
  [f]
  (let [ledger (request-counters/make-ledger)]
    (binding [request-counters/*ledger* ledger]
      (f))
    (request-counters/snapshot ledger)))

(defn run-fixed-cost-gate!
  "Checks accepted allocation ceilings and their deterministic work counts.

  Allocation is profiled with the same exclusive phase boundaries as the
  origin report. Counters are captured in separate unprofiled executions so
  snapshotting the ledger cannot affect the measured request."
  []
  (with-system
    (fn [{:keys [client alice documents]}]
      (let [client (profiled-client client)
            demand {:subject alice
                    :permission :view
                    :resource (first documents)}
            _ (eacl/check-permission client demand)
            profile-series
            (call-with-profiled-boundaries
             (fn []
               {:cache-hit-check
                (profile-distribution
                 scalar-warmups scalar-samples
                 (fn [_] (eacl/check-permission client demand)))
                :relationship-page-10
                (profile-distribution
                 scalar-warmups scalar-samples
                 (fn [_]
                   (eacl/read-relationships
                    client (relationship-query page-size nil))))}))
            counts
            {:cache-hit-check
             (request-counts
              #(eacl/check-permission client demand))
             :relationship-page-10
             (request-counts
              #(eacl/read-relationships
                client (relationship-query page-size nil)))}
            ratchets
            (into
             {}
             (map
              (fn [[name {series-key :series
                          :keys [phase maximum-allocation-p50
                                 counter expected-counter]
                          :as ratchet}]]
                (let [allocation-p50
                      (get-in
                       (get profile-series series-key)
                       [:phases phase :allocated-bytes :p50])
                      observed-counter (get-in counts [series-key counter])]
                  [name
                   (assoc ratchet
                          :allocation-p50 allocation-p50
                          :observed-counter observed-counter
                          :passed?
                          (and (<= allocation-p50
                                   maximum-allocation-p50)
                               (= expected-counter
                                  observed-counter)))]))
              fixed-cost-allocation-ratchets))]
        {:format-version 1
         :benchmark :authorization-request-amplification-fixed-cost-gate
         :phase :post-rendering-and-cursor-fix
         :environment (environment)
         :ratchets ratchets
         :series profile-series
         :counts counts
         :active-readers-after (d/active-read-snapshot-info)}))))

(defn run-scalar-series!
  "Re-runs the five scalar fixed-cost series for the checked-in comparison."
  []
  (with-system
    (fn [{:keys [client alice documents]}]
      (let [stats (atom {})
            seal-count (atom 0)
            original-seal sealed-plan/seal-plan
            provider (:source client)]
        (binding [backend/*backend-op-stats* stats]
          (with-redefs [sealed-plan/seal-plan
                        (fn [& args]
                          (swap! seal-count inc)
                          (apply original-seal args))]
            (let [demand {:subject alice
                          :permission :view
                          :resource (first documents)}
                  _ (eacl/check-permission client demand)
                  selected (source/acquire! provider :current)
                  plan-seal
                  (try
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_]
                       (sealed-plan/seal-plan
                        (source/adapter selected)
                        [:document :view])))
                    (finally
                      (source/release! selected)))
                  report
                  {:format-version 1
                   :benchmark
                   :authorization-request-amplification-scalar-series
                   :phase :post-change
                   :environment (environment)
                   :series
                   {:cache-hit-scalar
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_] (eacl/check-permission client demand)))
                    :cache-bypass-scalar
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_]
                       (eacl/check-permission
                        client (assoc demand :cache? false))))
                    :relationship-page-10
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_]
                       (eacl/read-relationships
                        client (relationship-query page-size nil))))
                    :provider-acquisition
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_]
                       (let [selection
                             (source/acquire! provider :current)]
                         (try
                           (source/semantic-identity selection)
                           (finally
                             (source/release! selection))))))
                    :plan-seal plan-seal}
                   :final-operation-counts
                   (assoc (op-counts @stats) :plan-seals @seal-count)}]
              (assoc report
                     :active-readers-after
                     (d/active-read-snapshot-info)))))))))

(defn run-baseline!
  "Runs the complete pre/post benchmark and returns machine-readable EDN data."
  []
  (with-system
    (fn [{:keys [conn client alice bob carol documents]}]
      (let [stats (atom {})
            seal-count (atom 0)
            original-seal sealed-plan/seal-plan
            provider (:source client)]
        (binding [backend/*backend-op-stats* stats]
          (with-redefs [sealed-plan/seal-plan
                        (fn [& args]
                          (swap! seal-count inc)
                          (apply original-seal args))]
            (let [cache-demand
                  {:subject alice
                   :permission :view
                   :resource (first documents)}
                  _ (eacl/check-permission client cache-demand)
                  selected (source/acquire! provider :current)
                  plan-seal
                  (try
                    (measured
                     stats seal-count scalar-warmups scalar-samples
                     (fn [_]
                       (sealed-plan/seal-plan
                        (source/adapter selected)
                        [:document :view])))
                    (finally
                      (source/release! selected)))]
              {:format-version 1
               :benchmark :authorization-request-amplification
               :phase :pre-change
               :environment (environment)
               :series
               {:cache-hit-scalar
                (measured
                 stats seal-count scalar-warmups scalar-samples
                 (fn [_] (eacl/check-permission client cache-demand)))
                :cache-bypass-scalar
                (measured
                 stats seal-count scalar-warmups scalar-samples
                 (fn [_]
                   (eacl/check-permission client (assoc cache-demand :cache? false))))
                :relationship-page-10
                (measured
                 stats seal-count scalar-warmups scalar-samples
                 (fn [_]
                   (eacl/read-relationships
                    client (relationship-query page-size nil))))
                :provider-acquisition
                (measured
                 stats seal-count scalar-warmups scalar-samples
                 (fn [_]
                   (let [selection
                         (source/acquire! provider :current)]
                     (try
                       (source/semantic-identity selection)
                       (finally
                         (source/release! selection))))))
                :plan-seal plan-seal
                :scalar-loop-dense
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_] (measured-scalar-loop client alice)))
                :scalar-loop-sparse
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_] (measured-scalar-loop client bob)))
                :scalar-loop-all-rejected
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_] (measured-scalar-loop client carol)))
                :lookup-resources-dense
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_]
                   (eacl/lookup-resources
                    client {:subject alice :permission :view
                            :resource/type :document :first page-size
                            :cache? false})))
                :lookup-resources-sparse
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_]
                   (eacl/lookup-resources
                    client {:subject bob :permission :view
                            :resource/type :document :first page-size
                            :cache? false})))
                :lookup-resources-all-rejected
                (measured
                 stats seal-count page-warmups page-samples
                 (fn [_]
                   (eacl/lookup-resources
                    client {:subject carol :permission :view
                            :resource/type :document :first page-size
                            :cache? false})))}
               :final-operation-counts
               (assoc (op-counts @stats) :plan-seals @seal-count)
               :active-readers-after (d/active-read-snapshot-info)})))))))

(deftest ^:benchmark authorization-amplification-baseline-test
  (let [report (run-baseline!)]
    (is (= :pre-change (:phase report)))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))
    (is (every? #(pos? (:samples %)) (vals (:series report))))))

(deftest ^:benchmark authorization-amplification-ratchet-origin-test
  (let [report (run-ratchet-origin!)
        series (:series report)]
    (is (= :ratchet-origin (:phase report)))
    (is (= #{:cache-hit-check
             :cache-bypass-check
             :relationship-page-10
             :provider-acquisition}
           (set (keys series))))
    (is (every? #(= (set profiled-phases)
                    (set (keys (:phases %))))
                (vals series)))
    (is (zero? (get-in series
                       [:cache-hit-check :phases :evaluation :calls :p50])))
    (is (= 1 (get-in series
                     [:cache-bypass-check :phases :evaluation :calls :p50])))
    (is (<= (get-in series
                    [:provider-acquisition :allocated-bytes :p50])
            acquisition-allocation-ceiling))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-cache-hit-gate-test
  (let [report (run-cache-hit-gate!)
        comparison (first (:comparisons report))]
    (is (true? (:passed? comparison)))
    (is (pos? (:latency-reduction comparison)))
    (is (pos? (:allocation-reduction comparison)))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-fixed-cost-gate-test
  (let [report (run-fixed-cost-gate!)]
    (is (= (set (keys fixed-cost-allocation-ratchets))
           (set (keys (:ratchets report)))))
    (doseq [[name ratchet] (:ratchets report)]
      (is (true? (:passed? ratchet))
          (str name " exceeded " (:maximum-allocation-p50 ratchet)
               " bytes or its " (:counter ratchet)
               " count changed: " ratchet)))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-post-change-scalar-test
  (let [report (run-scalar-series!)]
    (is (= :post-change (:phase report)))
    (is (= #{:cache-hit-scalar
             :cache-bypass-scalar
             :relationship-page-10
             :provider-acquisition
             :plan-seal}
           (set (keys (:series report)))))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-aggregate-gate-test
  (let [report (run-aggregate-gate!)]
    (doseq [comparison (:comparisons report)]
      (is (true? (:passed? comparison)) (pr-str comparison)))
    (doseq [[fixture comparison] (:release-comparisons report)]
      (is (true? (:passed? comparison))
          (str (name fixture) " release comparison failed: "
               (pr-str comparison))))
    (is (true? (:passed? report)))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-aggregate-counter-gate-test
  (let [report (run-aggregate-counter-gate!)]
    (is (true? (:passed? report)) (pr-str (:routes report)))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))

(deftest ^:benchmark authorization-amplification-aggregate-resource-gate-test
  (let [report (run-aggregate-resource-gate!)]
    (is (true? (:passed? report)) (pr-str report))
    (is (= {:active 0 :oldest-age-ms nil}
           (:active-readers-after report)))))
