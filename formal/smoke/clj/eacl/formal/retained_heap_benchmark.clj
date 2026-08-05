(ns eacl.formal.retained-heap-benchmark
  "Post-full-GC retained-live-heap regression gate for recursive page walks.

  Logical cache weights, caller-thread allocation, and heap use are distinct
  dimensions. This gate therefore measures JVM heap bytes directly. Every
  before/after pair retains an explicit keepalive vector across both full-GC
  snapshots so Clojure local clearing cannot make seed-only objects disappear
  between measurements. Alternating legacy/generated trials reduce order
  bias. The result is a host-runtime regression gate, not a portable peak-heap
  theorem or SLA."
  (:refer-clojure :exclude [run!])
  (:require
   [datascript.core :as ds]
   [eacl.cache :as cache]
   [eacl.core :as eacl]
   [eacl.datascript.core :as datascript]
   [eacl.formal.production-kernel :as production])
  (:import
   (java.lang.management ManagementFactory)
   (java.math BigInteger)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)))

(def ^:private recursive-schema
  "definition user {}
   definition folder {
     relation reader: user
     relation parent: folder
     permission read = reader + parent->read
   }")

(defn- collection-count
  []
  (let [counts
        (keep
         (fn [collector]
           (let [count' (.getCollectionCount collector)]
             (when-not (= -1 count')
               count')))
         (ManagementFactory/getGarbageCollectorMXBeans))]
    (when (seq counts)
      (reduce + counts))))

(defn- stabilized-heap-snapshot!
  []
  (let [collection-count-before (collection-count)]
    (dotimes [_ 4]
      (System/gc)
      (Thread/sleep 100))
    (let [collection-count-after (collection-count)
          used-bytes
          (.getUsed
           (.getHeapMemoryUsage
            (ManagementFactory/getMemoryMXBean)))]
      (when (and collection-count-before
                 collection-count-after
                 (<= collection-count-after collection-count-before))
        (throw
         (ex-info
          "Explicit full-GC heap measurement did not run a collection."
          {:type :eacl.formal/no-observed-full-gc
           :before collection-count-before
           :after collection-count-after})))
      {:used-bytes used-bytes
       :collection-count collection-count-after})))

(defn- transact-objects!
  [conn objects]
  (ds/transact!
   conn
   (mapv (fn [object] {:eacl/id (:id object)}) objects)))

(defn- seed-recursive-chain!
  [conn writer result-count]
  (let [user (eacl/spice-object :user "retained-heap-user")
        folders
        (mapv
         #(eacl/spice-object :folder (format "folder-%06d" %))
         (range result-count))]
    (eacl/write-schema! writer recursive-schema)
    (transact-objects! conn (into [user] folders))
    (eacl/create-relationships!
     writer
     (into
      [(eacl/->Relationship user :reader (first folders))]
      (map
       (fn [[parent child]]
         (eacl/->Relationship parent :parent child))
       (partition 2 1 folders))))
    {:user user
     :folders folders}))

(defn- digest-id!
  [^MessageDigest digest id]
  (.update digest (.getBytes (str id) StandardCharsets/UTF_8))
  (.update digest (byte-array [(byte 0)])))

(defn- walk-pages!
  [client query]
  (let [digest (MessageDigest/getInstance "SHA-256")]
    (loop [after nil
           pages 0
           items 0]
      (let [page
            (eacl/lookup-resources
             client
             (cond-> query
               after (assoc :after after)))
            data (:data page)]
        (doseq [item data]
          (digest-id! digest (:id item)))
        (if (get-in page [:page-info :has-next-page?])
          (recur
           (get-in page [:page-info :end-cursor])
           (inc pages)
           (+ items (count data)))
          {:pages (inc pages)
           :items (+ items (count data))
           :result-sha256
           (format "%064x" (BigInteger. 1 (.digest digest)))})))))

(defn- selection
  [mode]
  (case mode
    :legacy
    {:mode :legacy-authoritative}

    :generated
    {:mode :verified-authoritative
     :kernel production/generated-java-kernel}))

(defn- measure-once!
  [mode result-count page-size]
  (let [conn (datascript/create-conn)
        common
        {:security-key "01234567890123456789012345678901"}
        writer
        (datascript/make-client
         conn
         (assoc common :cache cache/no-cache))
        seeded
        (seed-recursive-chain! conn writer result-count)
        options
        (assoc
         common
         :cache {:remember-answers false}
         :engine-selection (selection mode))
        client (datascript/make-client conn options)
        query
        {:subject (:user seeded)
         :permission :read
         :resource/type :folder
         :first page-size}
        ;; This vector is intentionally consumed after the second heap
        ;; snapshot. Without it, Clojure/JIT local clearing can make writer or
        ;; seed-only state die between snapshots and produce a false negative.
        keepalive [conn common writer seeded options client query]
        before (stabilized-heap-snapshot!)
        walk (walk-pages! client query)
        after (stabilized-heap-snapshot!)
        keepalive-count (count keepalive)
        retained-delta-bytes
        (- (:used-bytes after) (:used-bytes before))]
    (when-not (= result-count (:items walk))
      (throw
       (ex-info
        "Retained-heap fixture returned an incomplete page walk."
        {:type :eacl.formal/incomplete-retained-heap-walk
         :mode mode
         :expected result-count
         :actual (:items walk)})))
    {:mode mode
     :before-full-gc-used-bytes (:used-bytes before)
     :after-full-gc-used-bytes (:used-bytes after)
     :retained-delta-bytes retained-delta-bytes
     :full-gc-count-before (:collection-count before)
     :full-gc-count-after (:collection-count after)
     :keepalive-count keepalive-count
     :walk walk}))

(defn- percentile
  [samples proportion]
  (let [ordered (vec (sort samples))
        index
        (min
         (dec (count ordered))
         (long (Math/floor (* proportion (count ordered)))))]
    (nth ordered index)))

(defn run!
  "Runs the retained-live-heap gate and throws if any reviewed condition fails.

  Defaults:
  - 4,000 recursively authorized results;
  - page size 25;
  - five alternating paired trials;
  - at least 1 MiB positive retained signal per mode/trial;
  - generated retained-live-heap delta at most 1.5x legacy."
  ([]
   (run! {}))
  ([{:keys [result-count page-size trials minimum-signal-bytes
            maximum-generated-to-legacy-ratio]
     :or {result-count 4000
          page-size 25
          trials 5
          minimum-signal-bytes 1048576
          maximum-generated-to-legacy-ratio 1.5}}]
   (when-not (and (pos-int? result-count)
                  (pos-int? page-size)
                  (pos-int? trials)
                  (pos-int? minimum-signal-bytes)
                  (number? maximum-generated-to-legacy-ratio)
                  (pos? maximum-generated-to-legacy-ratio))
     (throw
      (ex-info
       "Retained-heap gate options must be positive."
       {:type :eacl.formal/invalid-retained-heap-options})))
   ;; Warm both language/runtime paths before the first measured baseline so
   ;; class loading and generated-kernel initialization are not attributed to
   ;; one engine.
   (measure-once! :legacy 64 16)
   (measure-once! :generated 64 16)
   (stabilized-heap-snapshot!)
   (let [trial-results
         (mapv
          (fn [trial]
            (let [[first-mode second-mode]
                  (if (even? trial)
                    [:legacy :generated]
                    [:generated :legacy])
                  first-result
                  (measure-once! first-mode result-count page-size)
                  second-result
                  (measure-once! second-mode result-count page-size)
                  by-mode
                  {first-mode first-result
                   second-mode second-result}
                  legacy-delta
                  (get-in by-mode [:legacy :retained-delta-bytes])
                  generated-delta
                  (get-in by-mode [:generated :retained-delta-bytes])
                  ratio (/ (double generated-delta) legacy-delta)]
              {:trial trial
               :order [first-mode second-mode]
               :legacy-retained-delta-bytes legacy-delta
               :generated-retained-delta-bytes generated-delta
               :generated-to-legacy-ratio ratio
               :legacy-result-sha256
               (get-in by-mode [:legacy :walk :result-sha256])
               :generated-result-sha256
               (get-in by-mode [:generated :walk :result-sha256])
               :walk-items
               (get-in by-mode [:generated :walk :items])
               :walk-pages
               (get-in by-mode [:generated :walk :pages])}))
          (range trials))
         deltas
         (mapcat
          (juxt
           :legacy-retained-delta-bytes
           :generated-retained-delta-bytes)
          trial-results)
         ratios (mapv :generated-to-legacy-ratio trial-results)
         digests
         (mapcat
          (juxt :legacy-result-sha256 :generated-result-sha256)
          trial-results)
         positive-signal?
         (every? #(>= % minimum-signal-bytes) deltas)
         same-results? (= 1 (count (set digests)))
         maximum-ratio (apply max ratios)
         passed?
         (and
          positive-signal?
          same-results?
          (<= maximum-ratio maximum-generated-to-legacy-ratio))
         result
         {:fixture
          {:backend :datascript
           :permission-shape :recursive-chain
           :result-count result-count
           :page-size page-size
           :trials trials
           :cache {:remember-answers false}
           :measurement :post-full-gc-live-heap-delta
           :paired-order :alternating}
          :required
          {:minimum-positive-retained-signal-bytes minimum-signal-bytes
           :maximum-generated-to-legacy-ratio
           maximum-generated-to-legacy-ratio
           :identical-complete-result-digest true
           :observed-full-gc-between-every-snapshot true
           :explicit-baseline-keepalive true}
          :trials trial-results
          :summary
          {:minimum-retained-delta-bytes (apply min deltas)
           :maximum-retained-delta-bytes (apply max deltas)
           :median-generated-to-legacy-ratio
           (percentile ratios 0.50)
           :maximum-generated-to-legacy-ratio maximum-ratio
           :positive-signal? positive-signal?
           :same-results? same-results?
           :status (if passed? :passed :failed)}
          :qualification
          [:same-process-alternating-post-full-gc-regression-measurement
           :not-peak-heap
           :not-whole-process-rss
           :not-portable-runtime-guarantee
           :not-a-formal-asymptotic-bound]}]
     (when-not passed?
       (throw
        (ex-info
         "Retained-live-heap release gate failed."
         {:type :eacl.formal/retained-heap-regression
          :result result})))
     result)))
