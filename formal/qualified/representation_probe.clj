(ns eacl.formal.qualified.representation-probe
  "Development-only allocation probe for the compact scan representation.
   Whole authorization latency gates run separately on an idle host.")

(defn endpoint [datom] (nth (:v datom) 3))
(defn compact-edge [datom]
  (let [v (:v datom) eid (nth v 3) qid (nth v 4)]
    (if (nil? qid) eid [eid qid])))
(defn mapped-edge [datom]
  (let [v (:v datom)] {:eid (nth v 3) :qualifier-eid (nth v 4)}))
(defn paired-edge [datom]
  (let [v (:v datom)] [(nth v 3) (nth v 4)]))

(defn fixture [qualified-count]
  (mapv (fn [i] {:v [:user 1 :document (+ 100 i) (when (< i qualified-count) (+ 1000 i))]})
        (range 100)))

(defn measure [project datoms]
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
        thread-id (.threadId (Thread/currentThread))
        bytes #(.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean thread-id)
        run #(into [] (map project) datoms)
        consume (volatile! nil)]
    (dotimes [_ 20000] (vreset! consume (run)))
    (let [samples (vec (for [_ (range 11)]
                         (let [start (bytes)]
                           (dotimes [_ 10000] (vreset! consume (run)))
                           (/ (- (bytes) start) 10000.0))))]
      {:median-bytes-per-100-edges (nth (vec (sort samples)) 5)
       :samples samples :result-count (count @consume)})))

(defn run-probe! [output]
  (let [results
        (into (sorted-map)
              (for [percent [0 5 10]]
                [percent (into {}
                               (for [[name project] [[:eid-only endpoint] [:sparse-pair compact-edge]
                                                     [:always-pair paired-edge] [:always-map mapped-edge]]]
                                 [name (measure project (fixture percent))]))]))
        report {:java (System/getProperty "java.version")
                :os (System/getProperty "os.name")
                :scope :thread-allocation-only
                :edges-per-chunk 100 :results results}]
    (spit output (pr-str report))
    report))
