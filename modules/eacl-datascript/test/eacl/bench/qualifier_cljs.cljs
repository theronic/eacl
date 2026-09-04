(ns eacl.bench.qualifier-cljs
  (:require [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
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

(defn percentile [values p]
  (nth (vec (sort values)) (min (dec (count values)) (int (* p (count values))))))

(defn measure [operation iterations]
  (dotimes [_ (if (= 50 iterations) 5000 100)] (operation))
  (let [samples (vec (for [_ (range 40)]
                       (let [started (.now js/performance)]
                         (dotimes [_ iterations] (operation))
                         (/ (* 1000000 (- (.now js/performance) started)) iterations))))]
    {:median-ns (percentile samples 0.5) :p95-ns (percentile samples 0.95)
     :samples-ns samples :iterations-per-batch iterations}))

(defn sample-allocation! [session operation iterations]
  (js/Promise.
   (fn [resolve reject]
     (.post session "HeapProfiler.startSampling"
            #js {:samplingInterval 16384 :stackDepth 16
                 :includeObjectsCollectedByMajorGC true
                 :includeObjectsCollectedByMinorGC true}
            (fn [error]
              (if error
                (reject error)
                (try
                  (dotimes [_ iterations] (operation))
                  (.post session "HeapProfiler.stopSampling"
                         (fn [error result]
                           (if error
                             (reject error)
                             (let [samples (array-seq (.. result -profile -samples))]
                               (resolve {:estimated-allocated-bytes-per-operation
                                         (/ (reduce + (map #(.-size %) samples)) iterations)
                                         :sample-count (count samples) :iterations iterations
                                         :sampling-interval-bytes 16384})))))
                  (catch :default error (reject error)))))))))

(defn -main [& [output]]
  (let [conn (api/create-conn)
        client (api/make-client conn {})
        alice (eacl/spice-object :user "alice")
        bob (eacl/spice-object :user "bob")
        account (eacl/spice-object :account "account")
        document #(eacl/spice-object :document (str "document-" (+ 100000 %)))
        n 2000
        inspector (js/require "node:inspector")
        session (new (.-Session inspector))]
    (eacl/write-schema! client schema)
    (d/transact! conn (into [{:eacl/id "alice"} {:eacl/id "bob"} {:eacl/id "account"}]
                            (map #(hash-map :eacl/id (:id (document %)))) (range n)))
    (doseq [batch (partition-all 500
                                 (into [(eacl/->Relationship alice :owner account)]
                                       (mapcat #(vector (eacl/->Relationship alice :viewer (document %))
                                                        (eacl/->Relationship account :account (document %))))
                                       (range n)))]
      (eacl/create-relationships! client (vec batch)))
    (let [check {:subject alice :resource (document 0) :permission :direct :cache? false}
          query {:subject alice :permission :view :resource/type :document :cache? false}
          first-page (eacl/lookup-resources client (assoc query :first 50))
          cursor (get-in first-page [:page-info :end-cursor])
          reverse-page (eacl/lookup-subjects client {:resource (document 0) :permission :view :subject/type :user :last 50 :cache? false})
          operations [[:direct #(eacl/can? client check) 50 100]
                      [:negative #(eacl/can? client (assoc check :subject bob)) 50 100]
                      [:arrow #(eacl/can? client (assoc check :permission :arrow)) 50 100]
                      [:scan #(eacl/read-relationships client {:subject/type :user :resource/type :document :resource/relation :viewer :first 100 :cache? false}) 3 10]
                      [:page #(eacl/lookup-resources client (assoc query :first 50)) 3 10]
                      [:continuation #(eacl/lookup-resources client (assoc query :first 50 :after cursor)) 3 10]
                      [:reverse #(eacl/lookup-subjects client {:resource (document 0) :permission :view :subject/type :user :last 50 :cache? false}) 3 10]
                      [:count #(eacl/count-resources client query) 1 2]
                      [:warm-exact #(eacl/can? client (dissoc check :cache?)) 50 100]]
          result {:runtime (.-version js/process) :document-count n :relationship-count (inc (* 2 n))
                  :logical-relationship-datoms (reduce + (map #(count (d/datoms @conn :aevt %)) storage/attributes))
                  :first-page-ids (mapv :id (:data first-page))
                  :reverse-page-ids (mapv :id (:data reverse-page))
                  :metrics (into {} (for [[name operation iterations] operations]
                                      [name (measure operation iterations)]))}]
      (assert (= 8002 (:logical-relationship-datoms result)))
      (assert (= (mapv #(:id (document %)) (range 50)) (:first-page-ids result)))
      (assert (= ["alice"] (:reverse-page-ids result)))
      (assert (= n (:count (eacl/count-resources client query))))
      (.connect session)
      (-> (reduce (fn [promise [name operation _ iterations]]
                    (.then promise
                           (fn [result]
                             (.then (sample-allocation! session operation iterations)
                                    #(assoc-in result [:allocation name] %)))))
                  (js/Promise.resolve result) operations)
          (.then (fn [result]
                   (.disconnect session)
                   (.writeFileSync (js/require "node:fs") output (pr-str result))
                   (println :qualifier-cljs-complete output)))
          (.catch (fn [error]
                    (.disconnect session)
                    (js/console.error error)
                    (set! (.-exitCode js/process) 1)))))))

(set! *main-cli-fn* -main)
