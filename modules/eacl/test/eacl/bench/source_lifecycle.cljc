(ns eacl.bench.source-lifecycle
  "Matched UUID representation qualification. Raw runs belong under target/."
  (:require [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.client.orchestration :as orchestration]
            [eacl.causal-token :as token]
            [eacl.cursor :as cursor]
            [eacl.uuid :as uuid]
            [eacl.secure-format :as secure]))

#?(:cljs (goog-define build-mode "none"))

(def protocol
  {:version 2 :warmups 100 :blocks 15 :operations-per-block 100
   :revision-reason :require-populated-snapshot-and-reconstructed-equality
   :outliers :retain-all :statistic :median-block-mean
   :resident-latency-ratio-ceiling 1.10
   :cljs-resident-latency-ratio-ceiling 1.15
   :boundary-latency-ratio-ceiling 1.25
   :required-added-io 0 :required-resident-uuid-parses 0
   :scalar-byte-increase {:uuid-string 6 :short-default 30}
   ;; Fixed before native sampling. Same-width version fields add no bytes;
   ;; nested base64 rounding accounts for the authenticated-envelope ceilings.
   :artifact-byte-increase-per-uuid
   {:uuid-string {:canonical 6 :causal 12 :cursor 8 :snapshot 6}
    :short-default {:canonical 30 :causal 56 :cursor 40 :snapshot 30}}
   :existing-gates :remain-binding})

(def baseline-values
  [[:uuid-string "854e138f-b8a4-42ee-a8f9-49c01ac19fc1"]
   [:structured {:source :benchmark :lifecycle ["history" 1]}]
   [:short-default "eacl/initial"]])

(def native-values
  [[:uuid-string #uuid "854e138f-b8a4-42ee-a8f9-49c01ac19fc1"]
   [:structured #uuid "954e138f-b8a4-42ee-a8f9-49c01ac19fc1"]
   [:short-default #uuid "00000000-0000-0000-0000-000000000000"]])

(defn- nanos []
  #?(:clj (System/nanoTime) :cljs (* 1000000 (.now js/performance))))

(defn- allocated []
  #?(:clj (.getThreadAllocatedBytes
           ^com.sun.management.ThreadMXBean
           (java.lang.management.ManagementFactory/getThreadMXBean)
           (.getId (Thread/currentThread)))
     :cljs nil))

(defn- sample [f]
  (let [n (:operations-per-block protocol) before-bytes (allocated) start (nanos)]
    (dotimes [_ n] (f))
    (let [elapsed (- (nanos) start) after-bytes (allocated)]
      {:ns-per-op (/ elapsed n)
       :bytes-per-op (when before-bytes (/ (- after-bytes before-bytes) n))})))

(defn measure [f]
  (dotimes [_ (:warmups protocol)] (f))
  (let [samples (vec (repeatedly (:blocks protocol) #(sample f)))
        median (fn [k] (nth (vec (sort (keep k samples)))
                            (quot (count samples) 2)))]
    {:samples samples :median-ns (median :ns-per-op)
     :maximum-block-ns (apply max (map :ns-per-op samples))
     :median-bytes #?(:clj (median :bytes-per-op) :cljs nil)}))

(defn workload [lifecycle]
  (let [conn (datascript/create-conn)
        client (datascript/make-client
                conn {:source-lifecycle lifecycle :cache {:max-entries 64}
                      :security-key "uuid-benchmark-fixture-key-000000"
                      :clock (constantly 100)})
        user (eacl/spice-object :user "user")
        resources (mapv #(eacl/spice-object :document (str "document-" %)) (range 4))
        _ (eacl/write-schema! client "definition user {}\ndefinition document {\n relation viewer: user\n permission view = viewer\n}")
        _ (ds/transact! conn (mapv #(hash-map :eacl/id %) (cons "user" (map :id resources))))
        _ (eacl/create-relationships! client (mapv #(eacl/->Relationship user :viewer %) resources))
        request {:subject user :permission :view :resource (first resources)}
        query {:subject user :permission :view :resource/type :document :first 1}
        first-page (eacl/lookup-resources client query)
        next-query (assoc query :after (get-in first-page [:page-info :end-cursor]))
        bounds {:max-entries 64}
        _ (eacl/can? client request)
        snapshot (datascript/export-cache-snapshot client bounds)
        _ (assert (pos? (:entry-count snapshot)) "Restore qualification requires populated authority.")
        options {:keyring {:test "uuid-benchmark-fixture-key-000000"} :current-kid :test :now-seconds 100}
        scope {:backend :datascript :source-id "benchmark" :branch nil :source-lifecycle lifecycle}
        payload (assoc scope :revision 1 :exact-locator nil :issued-at 100 :expires-at 200)
        encoded-token (token/issue options payload)
        canonical (secure/encode-canonical lifecycle)
        reconstructed (secure/decode-canonical canonical)]
    {:client client :conn conn :snapshot snapshot
     :sizes {:scalar (count canonical) :token (count encoded-token)
             :cursor (count (get-in first-page [:page-info :end-cursor]))
             :snapshot-entries (:entry-count snapshot)
             :snapshot (count (secure/encode-canonical snapshot))}
     :operations
     {:validate #(token/validate-source-lifecycle! lifecycle)
      :equal-reused #(= lifecycle lifecycle)
      :equal-reconstructed #(= lifecycle reconstructed)
      :hash-reconstructed #(hash reconstructed)
      :canonical-encode #(secure/encode-canonical lifecycle)
      :canonical-decode #(secure/decode-canonical canonical)
      :token-issue #(token/issue options payload)
      :token-verify #(token/token-data options scope encoded-token)
      :warm-check #(eacl/can? client request)
      :bypass-check #(eacl/can? client (assoc request :cache? false))
      :miss-check #(do (orchestration/clear-answer-cache! client) (eacl/can? client request))
      :count #(eacl/count-resources client (dissoc query :first))
      :first-page #(eacl/lookup-resources client query)
      :continued-page #(eacl/lookup-resources client next-query)
      :batch #(eacl/check-permissions client {:checks [request request request]})
      :snapshot-export #(datascript/export-cache-snapshot client bounds)
      :snapshot-restore #(datascript/restore-cache-snapshot! client snapshot bounds)}}))

(defn run [values]
  {:protocol protocol
   :host #?(:clj {:runtime :clj :java (System/getProperty "java.version")
                  :arch (System/getProperty "os.arch")}
            :cljs {:runtime :cljs :node (.-version js/process) :arch (.-arch js/process)
                   :build-mode build-mode})
   :cases (mapv (fn [[label lifecycle]]
                  (let [{:keys [sizes operations]} (workload lifecycle)]
                    {:label label :sizes sizes
                     :operations (into {} (map (fn [[k f]] [k (measure f)]) operations))}))
                values)})

(def resident-operations #{:validate :equal-reused :equal-reconstructed :hash-reconstructed
                           :warm-check :count :first-page :continued-page :batch})

(def expected-operations
  #{:validate :equal-reused :equal-reconstructed :hash-reconstructed
    :canonical-encode :canonical-decode :token-issue :token-verify
    :warm-check :bypass-check :miss-check :count :first-page :continued-page
    :batch :snapshot-export :snapshot-restore})

(defn- complete-workloads? [report]
  (and (= 3 (count (:cases report)))
       (= #{:uuid-string :structured :short-default} (set (map :label (:cases report))))
       (every? #(= expected-operations (set (keys (:operations %)))) (:cases report))))

(defn observations
  "Supplemental diagnostics, kept separate from the fixed latency protocol.
  Traces describe actual adapter invocations after warming each operation."
  [lifecycle]
  (let [start (nanos)
        {:keys [operations client conn snapshot sizes]} (workload lifecycle)
        setup-ns (- (nanos) start)
        encoded (secure/encode-canonical snapshot)
        compressed-size
        #?(:clj (let [out (java.io.ByteArrayOutputStream.)]
                  (with-open [gzip (java.util.zip.GZIPOutputStream. out)]
                    (.write gzip (.getBytes encoded java.nio.charset.StandardCharsets/UTF_8)))
                  (.size out))
           :cljs (.-length (.gzipSync (js/require "zlib") encoded)))
        traces (into {}
                     (for [[operation f] operations]
                       (do
                         (dotimes [_ 3] (f))
                         (let [events (atom [])]
                           (binding [backend/*invoke-observer*
                                     #(swap! events conj (select-keys % [:operation :phase]))]
                             (f))
                           [operation @events]))))]
    {:setup-ns setup-ns :sizes sizes :gzip-snapshot-bytes compressed-size
     :adapter-traces traces
     :constructor (measure #(datascript/make-client conn {:source-lifecycle lifecycle
                                                         :security-key "uuid-benchmark-fixture-key-000000"}))
     :same-lifecycle-reset (measure #(orchestration/expire-cache! client lifecycle))
     :invalid-lifecycle (measure #(try (token/validate-source-lifecycle! false)
                                      (catch #?(:clj Exception :cljs :default) _ :rejected)))}))

(defn retained-footprint
  "Retain independent decoded values across full GC; report heap deltas, not
  a universal object-size claim. Node must be launched with --expose-gc."
  [lifecycle n]
  (let [collect #?(:clj #(System/gc) :cljs (when (fn? js/global.gc) #(js/global.gc)))
        used #?(:clj #(let [rt (Runtime/getRuntime)] (- (.totalMemory rt) (.freeMemory rt)))
                :cljs #(.-heapUsed (.memoryUsage js/process)))
        wire (secure/encode-canonical lifecycle)]
    (if-not collect
      {:status :unavailable :reason :explicit-gc-required}
      (do
        (dotimes [_ 1000] (secure/decode-canonical wire))
        (collect)
        (let [before (used)
              held (mapv (fn [_] (secure/decode-canonical wire)) (range n))
              _ (collect)
              after (used)]
          {:status :measured :values (count held) :heap-delta (- after before)
           ;; Touch after the second GC so the complete graph remains live.
           :preserved? (every? #(= lifecycle %) held)})))))

(defn resident-uuid-work []
  (let [{:keys [operations]} (workload (second (first native-values)))
        counters (atom {:uuid-parses 0 :uuid-stringifications 0})
        parse uuid/parse-canonical text uuid/text]
    (doseq [operation resident-operations] (dotimes [_ 3] ((get operations operation))))
    (with-redefs [uuid/parse-canonical (fn [value]
                                       (swap! counters update :uuid-parses inc) (parse value))
                  uuid/text (fn [value]
                              (swap! counters update :uuid-stringifications inc) (text value))]
      (doseq [operation resident-operations] (dotimes [_ 3] ((get operations operation)))))
    @counters))

(defn qualify
  "Compare every preregistered operation without dropping slow samples/cases.
  This report is measurement evidence, not a universal performance claim."
  [baseline candidate]
  (let [cljs? (= :cljs (get-in candidate [:host :runtime]))
        complete? (and (complete-workloads? baseline) (complete-workloads? candidate))
        matched? (and (= (:host baseline) (:host candidate))
                      (#{:clj :cljs} (get-in candidate [:host :runtime]))
                      (= (:protocol baseline) (:protocol candidate)))
        cases (into {} (map (juxt :label identity) (:cases baseline)))
        rows
        (vec
         (for [{:keys [label operations sizes]} (:cases candidate)
               [operation measurement] operations
               :let [prior (get-in cases [label :operations operation])
                     ceiling (if (contains? resident-operations operation)
                               (get protocol (if cljs? :cljs-resident-latency-ratio-ceiling
                                                 :resident-latency-ratio-ceiling))
                               (:boundary-latency-ratio-ceiling protocol))
                     ratio (when (and (number? (:median-ns prior)) (pos? (:median-ns prior))
                                      (number? (:median-ns measurement)) (pos? (:median-ns measurement)))
                             (/ (:median-ns measurement) (:median-ns prior)))]]
           {:case label :operation operation :latency-ratio ratio :ceiling ceiling
            :passed? (boolean (and complete? matched? ratio (<= ratio ceiling)))
            :median-ns (:median-ns measurement)
            :maximum-block-ns (:maximum-block-ns measurement)
            :median-bytes (:median-bytes measurement)
            :baseline-median-bytes (:median-bytes prior)
            :sizes sizes :baseline-sizes (get-in cases [label :sizes])}))]
    {:matched-host? (boolean matched?) :complete-workloads? (boolean complete?)
     :passed? (boolean (and complete? matched? (every? :passed? rows)))
     :rows rows :failures (filterv (complement :passed?) rows)}))

#?(:cljs
   (do
     (defn -main [& [phase mode]]
       (let [values (if (= "native" phase) native-values baseline-values)]
         (println
          (pr-str
           (case mode
             "details" (mapv (fn [[label value]] [label (observations value)]) values)
             "heap" (mapv (fn [[label value]] [label (retained-footprint value 100000)]) values)
             "resident" (resident-uuid-work)
             (run values))))))
     (set! *main-cli-fn* -main)))
