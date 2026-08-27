(ns eacl.bench.datalevin-test
  "Explicit local Datalevin latency, allocation, and reader-pressure fixture.
  Tagged out of normal correctness suites; invoke `run-benchmark!` through the
  module nREPL and retain the complete returned report."
  (:require [clojure.test :refer [deftest is]]
            [datalevin.constants :as constants]
            [datalevin.core :as d]
            [datalevin.util :as u]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datalevin.backend :as datalevin-backend]
            [eacl.datalevin.core :as datalevin])
  (:import [com.sun.management ThreadMXBean]
           [java.io File]
           [java.lang.management ManagementFactory]))

(def ^:private test-key "01234567890123456789012345678901")
(def ^:private warmup-samples 25)
(def ^:private measurement-samples 51)
(def ^:private write-warmup-samples 5)
(def ^:private write-measurement-samples 21)
(def ^:private document-count 256)
(def ^:private held-reader-count 32)

(def ^:private schema
  "definition user {}
   definition document {
     relation viewer: user
     permission view = viewer
   }")

(defn- percentile
  [samples p]
  (let [ordered (vec (sort samples))
        index (min (dec (count ordered))
                   (long (Math/floor (* p (dec (count ordered))))))]
    (nth ordered index)))

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

(defn- distribution
  [warmups samples f]
  (let [latencies (transient [])
        allocations (transient [])
        checksum (volatile! 0)]
    (dotimes [iteration (+ warmups samples)]
      (let [allocated-before (allocated-bytes)
            started (System/nanoTime)
            value (f iteration)
            elapsed (- (System/nanoTime) started)
            allocated-after (allocated-bytes)]
        (vswap! checksum unchecked-add-int (hash value))
        (when (>= iteration warmups)
          (conj! latencies (/ (double elapsed) 1000.0))
          (when (and allocated-before allocated-after)
            (conj! allocations (- allocated-after allocated-before))))))
    (let [latencies (persistent! latencies)
          allocations (persistent! allocations)]
      {:unit :microseconds
       :samples samples
       :min (apply min latencies)
       :p50 (percentile latencies 0.50)
       :p95 (percentile latencies 0.95)
       :p99 (percentile latencies 0.99)
       :max (apply max latencies)
       :mean (/ (reduce + latencies) (double samples))
       :allocated-bytes
       (when (seq allocations)
         {:p50 (percentile allocations 0.50)
          :p95 (percentile allocations 0.95)})
       :checksum @checksum})))

(defn- used-heap-bytes
  []
  (let [runtime (Runtime/getRuntime)]
    (- (.totalMemory runtime) (.freeMemory runtime))))

(defn- directory-bytes
  [dir]
  (reduce
   + 0
   (keep (fn [^File file]
           (when (.isFile file) (.length file)))
         (file-seq (File. (str dir))))))

(defn- with-system
  [f]
  (let [dir (u/tmp-dir (str "eacl-datalevin-benchmark-" (random-uuid)))
        conn (datalevin/create-conn dir)
        watermark (atom 0)
        client
        (datalevin/make-client
         conn
         {:source-lifecycle "benchmark-lifecycle"
          :revision-watermark watermark
          :advance-revision-watermark! #(swap! watermark max %)
          :datalevin-topology
          datalevin-backend/certified-topology-declaration
          :security-key test-key})]
    (try
      (eacl/write-schema! client schema)
      (d/transact!
       conn
       (into [{:eacl/id "alice"} {:eacl/id "bob"}]
             (map (fn [index]
                    {:eacl/id (str "document-" index)})
                  (range document-count))))
      (let [alice (eacl/spice-object :user "alice")
            bob (eacl/spice-object :user "bob")
            documents
            (mapv #(eacl/spice-object :document (str "document-" %))
                  (range document-count))]
        (eacl/create-relationships!
         client
         (mapv #(eacl/->Relationship alice :viewer %) documents))
        (f {:dir dir :conn conn :client client
            :alice alice :bob bob :documents documents}))
      (finally
        (d/close conn)
        (u/delete-files dir)))))

(defn run-benchmark!
  []
  (with-system
    (fn [{:keys [dir conn client alice bob documents]}]
      (let [document (first documents)
            heap-before (used-heap-bytes)
            disk-before (directory-bytes dir)
            provider (:source client)
            selected (source/acquire! provider :current)
            adapter (source/adapter selected)
            subject-id (backend/invoke adapter :object-id->internal "alice")
            relation-id
            (:relation-id
             (first (backend/invoke adapter :relation-defs :document :viewer)))
            scan-result
            (try
              (distribution
               warmup-samples measurement-samples
               (fn [_]
                 (backend/invoke
                  adapter :subject->resources
                  :user subject-id relation-id :document
                  {:direction :asc :limit 25})))
              (finally
                (source/release! selected)))
            permission-result
            (distribution
             warmup-samples measurement-samples
             (fn [_] (eacl/can? client alice :view document)))
            pagination-result
            (distribution
             warmup-samples measurement-samples
             (fn [_]
               (eacl/lookup-resources
                client {:subject alice :permission :view
                        :resource/type :document :first 25})))
            count-result
            (distribution
             warmup-samples measurement-samples
             (fn [_]
               (eacl/count-resources
                client {:subject alice :permission :view
                        :resource/type :document})))
            acquisition-result
            (distribution
             warmup-samples measurement-samples
             (fn [_]
               (with-open [snapshot (d/open-read-snapshot conn)]
                 (:max-tx (d/read-snapshot-info snapshot)))))
            provider-acquisition-result
            (distribution
             warmup-samples measurement-samples
             (fn [_]
               (let [selected
                     (source/acquire! provider :current)]
                 (try
                   (get-in
                    (source/semantic-identity selected)
                    [:backend-snapshot-id :basis-t])
                   (finally
                     (source/release! selected))))))
            cache-bypass-result
            (distribution
             warmup-samples measurement-samples
             (fn [_]
               (with-open [snapshot (d/open-read-snapshot conn)]
                 (d/with-read-snapshot
                  snapshot
                  #(count (d/datoms % :ave :eacl/id))))))
            relationship-present? (atom false)
            write-result
            (distribution
             write-warmup-samples write-measurement-samples
             (fn [_]
               (if (swap! relationship-present? not)
                 (eacl/write-relationship!
                  client :touch bob :viewer document)
                 (eacl/delete-relationship!
                  client (eacl/->Relationship bob :viewer document)))))
            held (mapv (fn [_] (d/open-read-snapshot conn))
                       (range held-reader-count))
            reader-pressure
            (try
              {:held-readers held-reader-count
               :active-while-held (d/active-read-snapshot-info)
               :additional-acquire-close
               (distribution
                warmup-samples measurement-samples
                (fn [_]
                  (with-open [snapshot (d/open-read-snapshot conn)]
                    (:max-tx (d/read-snapshot-info snapshot)))))}
              (finally
                (doseq [snapshot (reverse held)]
                  (d/close-read-snapshot! snapshot))))]
        {:environment
         {:os (System/getProperty "os.name")
          :architecture (System/getProperty "os.arch")
          :java-version (System/getProperty "java.version")
          :java-vendor (System/getProperty "java.vendor")
          :datalevin-version constants/version
          :documents document-count}
         :latency
         {:bounded-forward-scan-25 scan-result
          :warm-permission-check permission-result
          :first-page-25 pagination-result
          :exact-count count-result
          :snapshot-acquire-close acquisition-result
          :provider-acquire-adapt-release provider-acquisition-result
          :snapshot-cache-bypass-read cache-bypass-result
          :relationship-write write-result
          :long-reader-pressure reader-pressure}
         :resources
         {:heap-before-bytes heap-before
          :heap-after-bytes (used-heap-bytes)
          :database-before-bytes disk-before
          :database-after-bytes (directory-bytes dir)
          :active-readers-after (d/active-read-snapshot-info)}}))))

(deftest ^:benchmark datalevin-local-benchmark-test
  (let [report (run-benchmark!)]
    (is (= {:active 0 :oldest-age-ms nil}
           (get-in report [:resources :active-readers-after])))
    (is (= held-reader-count
           (get-in report
                   [:latency :long-reader-pressure
                    :active-while-held :active])))
    (is (every?
         pos?
         (map :samples
              (vals
               (dissoc (:latency report) :long-reader-pressure)))))))
