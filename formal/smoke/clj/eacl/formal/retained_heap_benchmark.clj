(ns eacl.formal.retained-heap-benchmark
  "Generated-engine post-full-GC retained-live-heap regression gate.

  Logical cache weights, caller-thread allocation, and heap use are distinct
  dimensions. This gate measures JVM heap bytes directly and retains the seed,
  clients, and query across both snapshots. It is a host-runtime regression
  gate, not a portable peak-heap theorem or SLA."
  (:refer-clojure :exclude [run!])
  (:require
   [datascript.core :as ds]
   [eacl.cache :as cache]
   [eacl.core :as eacl]
   [eacl.datascript.core :as datascript])
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
             (when-not (= -1 count') count')))
         (ManagementFactory/getGarbageCollectorMXBeans))]
    (when (seq counts) (reduce + counts))))

(defn- stabilized-heap-snapshot!
  []
  (let [before (collection-count)]
    (dotimes [_ 4]
      (System/gc)
      (Thread/sleep 100))
    (let [after (collection-count)
          used
          (.getUsed
           (.getHeapMemoryUsage
            (ManagementFactory/getMemoryMXBean)))]
      (when (and before after (<= after before))
        (throw
         (ex-info
          "Explicit full-GC heap measurement did not run a collection."
          {:type :eacl.formal/no-observed-full-gc
           :before before
           :after after})))
      {:used-bytes used :collection-count after})))

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
    {:user user :folders folders}))

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
             (cond-> query after (assoc :after after)))
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

(defn- measure-once!
  [result-count page-size]
  (let [conn (datascript/create-conn)
        common {:security-key "01234567890123456789012345678901"}
        writer
        (datascript/make-client
         conn
         (assoc common :cache cache/no-cache))
        seeded (seed-recursive-chain! conn writer result-count)
        options (assoc common :cache {:remember-answers false})
        client (datascript/make-client conn options)
        query
        {:subject (:user seeded)
         :permission :read
         :resource/type :folder
         :first page-size}
        keepalive [conn common writer seeded options client query]
        before (stabilized-heap-snapshot!)
        walk (walk-pages! client query)
        after (stabilized-heap-snapshot!)
        keepalive-count (count keepalive)
        retained-delta (- (:used-bytes after) (:used-bytes before))]
    (when-not (= result-count (:items walk))
      (throw
       (ex-info
        "Retained-heap fixture returned an incomplete page walk."
        {:type :eacl.formal/incomplete-retained-heap-walk
         :expected result-count
         :actual (:items walk)})))
    {:before-full-gc-used-bytes (:used-bytes before)
     :after-full-gc-used-bytes (:used-bytes after)
     :retained-delta-bytes retained-delta
     :full-gc-count-before (:collection-count before)
     :full-gc-count-after (:collection-count after)
     :keepalive-count keepalive-count
     :walk walk}))

(defn run!
  "Runs the generated-only retained-live-heap gate.

  The absolute ceiling replaces the removed v7-engine ratio. The fixture and
  ceiling are deliberately explicit so a runtime or generated-artifact growth
  regression fails even though no obsolete engine is packaged."
  ([]
   (run! {}))
  ([{:keys [result-count page-size trials minimum-signal-bytes
            maximum-retained-delta-bytes]
     :or {result-count 4000
          page-size 25
          trials 5
          minimum-signal-bytes 1048576
          maximum-retained-delta-bytes 8388608}}]
   (when-not
    (every?
     pos-int?
     [result-count page-size trials minimum-signal-bytes
      maximum-retained-delta-bytes])
     (throw
      (ex-info
       "Retained-heap gate options must be positive integers."
       {:type :eacl.formal/invalid-retained-heap-options})))
   (measure-once! 64 16)
   (stabilized-heap-snapshot!)
   (let [results
         (mapv
          (fn [trial]
            (assoc
             (measure-once! result-count page-size)
             :trial trial))
          (range trials))
         deltas (mapv :retained-delta-bytes results)
         digests (mapv #(get-in % [:walk :result-sha256]) results)
         positive-signal?
         (every? #(>= % minimum-signal-bytes) deltas)
         below-ceiling?
         (every? #(<= % maximum-retained-delta-bytes) deltas)
         same-results? (= 1 (count (set digests)))
         passed? (and positive-signal? below-ceiling? same-results?)
         result
         {:fixture
          {:backend :datascript
           :engine :generated-only
           :permission-shape :recursive-chain
           :result-count result-count
           :page-size page-size
           :trials trials
           :cache {:remember-answers false}
           :measurement :post-full-gc-live-heap-delta}
          :required
          {:minimum-positive-retained-signal-bytes minimum-signal-bytes
           :maximum-retained-delta-bytes maximum-retained-delta-bytes
           :identical-complete-result-digest true
           :observed-full-gc-between-every-snapshot true
           :explicit-baseline-keepalive true}
          :trials results
          :summary
          {:minimum-retained-delta-bytes (apply min deltas)
           :maximum-retained-delta-bytes (apply max deltas)
           :positive-signal? positive-signal?
           :below-ceiling? below-ceiling?
           :same-results? same-results?
           :status (if passed? :passed :failed)}
          :qualification
          [:generated-only-post-full-gc-regression-measurement
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
