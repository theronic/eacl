(ns eacl.bench.qualifier-migration-test
  (:require [clojure.java.io :as io]
            [datalevin.core :as dl]
            [eacl.datomic.migrations.v7-to-v9-test :as dt]
            [eacl.datahike.migrations.v7-to-v9-test :as dh]
            [eacl.datascript.migrations.v7-to-v9-test :as ds]
            [eacl.datalevin.migrations.v7-to-v9-test :as dv]
            [eacl.relationships.migration-contract :as contract]
            [eacl.relationships.legacy-v7 :as legacy]
            [eacl.relationships.storage :as storage]
            [eacl.relationships.upgrade-test :refer [error-data]]))

(defn directory-bytes [directory]
  (when directory
    (reduce + 0 (map #(.length %) (filter #(.isFile %) (file-seq (io/file directory)))))))

(defn run-migration! [backend relationship-count output]
  (let [fixture ((case backend
                   :datomic dt/open-source :datascript ds/open-source
                   :datalevin dv/open-source
                   :datahike #(dh/open-source {:store {:backend :file :path (str (java.nio.file.Files/createTempDirectory "eacl-migration-bench-" (make-array java.nio.file.attribute.FileAttribute 0)) "/db")}})))
        {:keys [snapshot transact! entid write-schema! migrate! close! reopen! directory]} fixture
        progress (atom [])
        source-bytes (atom 0)
        bytes #(directory-bytes directory)]
    (try
      (write-schema! contract/schema)
      (transact! [{:eacl/id "schema-string" :eacl/storage-version 7} {:eacl/id "alice"}])
      (doseq [batch (partition-all 500 (range relationship-count))]
        (transact! (mapv #(hash-map :eacl/id (str "document-" %)) batch)))
      (let [db (snapshot)
            r (entid db [:eacl.relation/resource-type+relation-name+subject-type [:document :viewer :user]])
            a (entid db [:eacl/id "alice"])]
        (doseq [batch (partition-all 500 (range relationship-count))]
          (let [ops (vec (mapcat (fn [n]
                                   (let [doc (entid db [:eacl/id (str "document-" n)])]
                                     [[:db/add a legacy/forward-attribute (legacy/endpoint-value :user r :document doc)]
                                      [:db/add doc legacy/reverse-attribute (legacy/endpoint-value :document r :user a)]])) batch))]
            (swap! source-bytes + (count (.getBytes (pr-str ops) "UTF-8")))
            (transact! ops))))
      (let [initial-bytes (bytes)
            started (System/nanoTime)
            observe! (fn [report]
                       (swap! progress conj (assoc report :elapsed-ns (- (System/nanoTime) started)
                                                   :durable-apparent-bytes (bytes))))
            interrupted (error-data #(migrate! {:quiesced? true :batch-size 250
                                                :on-progress (fn [report]
                                                               (observe! report)
                                                               (when (= 250 (:converted report))
                                                                 (throw (ex-info "Rehearsal restart" {:reason :interrupted}))))}))
            _ (assert (= :interrupted (:reason interrupted)))
            stopped (System/nanoTime)
            _ (when reopen! (reopen!))
            reopened (System/nanoTime)
            report (migrate! {:quiesced? true :batch-size 250 :on-progress observe!})
            finished (System/nanoTime)
            db (snapshot)
            rerun-start (System/nanoTime)
            rerun (migrate! {:quiesced? true})
            rerun-ns (- (System/nanoTime) rerun-start)
            result {:backend backend :relationship-count relationship-count
                    :logical-relationship-datoms (* 2 relationship-count)
                    :batch-size 250 :source-transaction-edn-bytes @source-bytes
                    :total-elapsed-ns (- finished started)
                    :relationships-per-second (/ (* 1e9 relationship-count) (- finished started))
                    :reopen-ns (- reopened stopped) :resume-to-complete-ns (- finished reopened)
                    :complete-rerun-ns rerun-ns :native-close-reopen? (boolean reopen!)
                    :initial-durable-apparent-bytes initial-bytes
                    :peak-durable-apparent-bytes (when initial-bytes (apply max initial-bytes (keep :durable-apparent-bytes @progress)))
                    :final-durable-apparent-bytes (bytes)
                    :report report :progress @progress}]
        (assert (= :complete (:state report)))
        (assert (= relationship-count (:source-count report)))
        (assert (:already-complete? rerun))
        (spit output (pr-str result))
        (dissoc result :progress))
      (finally (close!)))))
