(ns eacl.bench.qualifier-storage-test
  "Matched-host public API workload, loadable in both storage-7 and storage-9 checkouts."
  (:require [clojure.java.io :as io]
            [datomic.api :as dt]
            [datahike.api :as dh]
            [datascript.core :as ds]
            [datalevin.core :as dl]
            [datalevin.util :as u]
            [eacl.core :as eacl]
            [eacl.datomic.core :as dt-api]
            [eacl.datomic.schema :as dt-schema]
            [eacl.datahike.core :as dh-api]
            [eacl.datascript.core :as ds-api]
            [eacl.datalevin.core :as dl-api]
            [eacl.relationships.storage :as storage]))

(def schema
  "definition user {}
   definition account {
     relation owner: user
   }
   definition document {
     relation viewer: user
     relation account: account
     permission direct = viewer
     permission arrow = account->owner
     permission view = viewer + account->owner
   }")

(defn directory-bytes [directory]
  (when directory
    (reduce + 0 (map #(.length %) (filter #(.isFile %) (file-seq (io/file directory)))))))

(defn open-system [backend]
  (let [directory (u/tmp-dir (str "eacl-qualifier-bench-" (random-uuid)))
        uri (str "datomic:mem://qualifier-bench-" (random-uuid))
        watermark (atom 0)
        config {:security-key "01234567890123456789012345678901"
                :source-lifecycle "qualifier-benchmark"}
        [conn api db transact! rows close!]
        (case backend
          :datomic
          (do (dt/create-database uri)
              (let [conn (dt/connect uri)]
                (if-let [install! (ns-resolve 'eacl.datomic.schema 'install!)]
                  (install! conn)
                  @(dt/transact conn dt-schema/v8-schema))
                [conn dt-api/make-client dt/db #(deref (dt/transact %1 %2))
                 #(dt/datoms %1 :aevt %2)
                 #(do (dt/release conn) (dt/delete-database uri))]))
          :datahike
          (let [conn (dh-api/create-conn nil {:store {:backend :file :path (str directory "/db")}})
                config (:config (dh/db conn))]
            [conn dh-api/make-client dh/db dh/transact
             #(dh/datoms %1 {:index :aevt :components [%2]})
             #(do (dh/release conn) (dh/delete-database config))])
          :datascript
          (let [conn (ds-api/create-conn)]
            [conn ds-api/make-client ds/db ds/transact! #(ds/datoms %1 :aevt %2) (fn [])])
          :datalevin
          (let [conn (dl-api/create-conn directory)]
            [conn dl-api/make-client dl/db dl/transact! #(dl/datoms %1 :ave %2) #(dl/close conn)]))
        config (cond-> config
                 (= :datalevin backend)
                 (assoc :revision-watermark watermark
                        :advance-revision-watermark! #(swap! watermark max %)))]
    {:client (api conn config) :snapshot #(db conn)
     :transact! #(transact! conn %) :rows rows
     :durable-bytes #(when (#{:datahike :datalevin} backend) (directory-bytes directory))
     :close! #(do (close!) (when (.exists (io/file directory)) (u/delete-files directory)))}))

(defn- percentile [values p]
  (nth (vec (sort values)) (min (dec (count values)) (long (* p (count values))))))

(defn measure [operation batches iterations]
  (dotimes [_ (case iterations 1000 20000 100 5000 3 500 100)] (operation))
  (let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
        tid (.threadId (Thread/currentThread))
        allocated #(.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean tid)
        samples
        (vec (for [_ (range batches)]
               (let [bytes (allocated) started (System/nanoTime)]
                 (dotimes [_ iterations] (operation))
                 {:ns (/ (- (System/nanoTime) started) (double iterations))
                  :bytes (/ (- (allocated) bytes) (double iterations))})))]
    {:median-ns (percentile (map :ns samples) 0.5)
     :p95-ns (percentile (map :ns samples) 0.95)
     :median-thread-allocated-bytes (percentile (map :bytes samples) 0.5)
     :samples samples}))

(defn run-backend!
  [backend document-count output]
  (let [{:keys [client snapshot transact! rows durable-bytes close!]} (open-system backend)
        alice (eacl/spice-object :user "alice")
        account (eacl/spice-object :account "account")
        document #(eacl/spice-object :document (format "document-%06d" %))]
    (try
      (eacl/write-schema! client schema)
      (transact! (into [{:eacl/id "alice"} {:eacl/id "bob"} {:eacl/id "account"}]
                       (map #(hash-map :eacl/id (:id (document %))))
                       (range document-count)))
      (let [relationships (into [(eacl/->Relationship alice :owner account)]
                                (mapcat #(vector (eacl/->Relationship account :account (document %))
                                                 (eacl/->Relationship alice :viewer (document %))))
                                (range document-count))
            started (System/nanoTime)]
        (doseq [batch (partition-all 500 relationships)]
          (eacl/create-relationships! client (vec batch)))
        (let [write-ns (- (System/nanoTime) started)
              query {:subject alice :permission :view :resource/type :document :cache? false}
              check {:subject alice :resource (document 0) :permission :direct :cache? false}
              cold-start (System/nanoTime)
              _ (assert (true? (eacl/can? client check)))
              cold-first-check-ns (- (System/nanoTime) cold-start)
              first-page (eacl/lookup-resources client (assoc query :first 50))
              continuation (get-in first-page [:page-info :end-cursor])
              expected-count (eacl/count-resources client query)
              db (snapshot)
              tuples (into [] (mapcat #(seq (rows db %))) storage/attributes)
              operations
              [[:direct #(eacl/can? client check) 50 100]
               [:negative #(eacl/can? client (assoc check :subject (eacl/spice-object :user "bob"))) 50 100]
               [:arrow #(eacl/can? client (assoc check :permission :arrow)) 50 100]
               [:scan #(eacl/read-relationships client {:subject/type :user :resource/type :document
                                                       :resource/relation :viewer :first 100 :cache? false}) 40 3]
               [:page #(eacl/lookup-resources client (assoc query :first 50)) 40 3]
               [:continuation #(eacl/lookup-resources client (assoc query :first 50 :after continuation)) 40 3]
               [:count #(eacl/count-resources client query) 30 1]
               [:warm-exact #(eacl/can? client (dissoc check :cache?)) 50 1000]]
              _ (assert (= (* 2 (count relationships)) (count tuples)))
              _ (assert (= 50 (count (:data first-page))))
              _ (assert (= document-count (:count expected-count)))
              results
              {:backend backend :storage-version (if-let [v (ns-resolve 'eacl.relationships.storage 'version)] @v 7)
               :java (System/getProperty "java.version")
               :document-count document-count :relationship-count (count relationships)
               :logical-relationship-datoms (count tuples)
               :write-ns write-ns :relationships-per-second (/ (* 1e9 (count relationships)) write-ns)
               :cold-first-check-ns cold-first-check-ns
               :durable-apparent-bytes (durable-bytes)
               :tuple-value-edn-bytes (count (.getBytes (pr-str (mapv :v tuples)) "UTF-8"))
               :tuple-datom-edn-bytes (count (.getBytes (pr-str (mapv (juxt :e :a :v) tuples)) "UTF-8"))
               :count-result expected-count
               :first-page-ids (mapv :id (:data first-page))
               :metrics (into {} (for [[name operation batches iterations] operations]
                                  [name (measure operation batches iterations)]))}]
          (spit output (pr-str results))
          (dissoc results :metrics)))
      (finally (close!)))))
