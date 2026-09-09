(ns eacl.bench.release-comparison
  "Paired revision comparison through public APIs; invoke explicitly via nREPL."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [datascript.core :as ds]
            [datomic.api :as dt]
            [datahike.api :as dh]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.cache :as cache]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.datahike.core :as datahike])
  (:import [java.lang.management ManagementFactory]))

(defn- open! [backend]
  (let [options {:cache cache/no-cache}]
    (case backend
      :datascript (let [conn (datascript/create-conn)]
                    {:client (datascript/make-client conn options)
                     :transact! #(ds/transact! conn %) :close! (fn [])})
      :datomic (let [uri (str "datomic:mem://release-comparison-" (random-uuid))
                     _ (dt/create-database uri)
                     conn (dt/connect uri)
                     _ (datomic-schema/install! conn)]
                 {:client (datomic/make-client conn options)
                  :transact! #(deref (dt/transact conn %))
                  :close! #(do (dt/release conn) (dt/delete-database uri))})
      :datahike (let [conn (datahike/create-conn)]
                  {:client (datahike/make-client conn options)
                   :transact! #(dh/transact conn %) :close! #(dh/release conn)}))))

(defn- operations [client]
  (let [server (eacl/spice-object :server (fixture/server-id 0 0))
        page (fixture/resource-query fixture/super-user :view 20)]
    (array-map
     :point #(assert (true? (eacl/can? client fixture/super-user :view server)))
     :negative #(assert (false? (eacl/can? client (eacl/spice-object :user "stranger") :view server)))
     :first-page #(assert (= 20 (count (:data (eacl/lookup-resources client page)))))
     :continuation #(let [first-page (eacl/lookup-resources client page)]
                      (assert (= 20 (count (:data (eacl/lookup-resources
                                                   client (assoc page :after (get-in first-page [:page-info :end-cursor]))))))))
     :count #(assert (= 2000 (:count (eacl/count-resources client (fixture/count-query fixture/super-user :view)))))
     :reverse-page #(assert (seq (:data (eacl/lookup-subjects client {:resource server :permission :view :subject/type :user :first 20})))))))

(defn- percentile [values fraction]
  (nth (vec (sort values)) (min (dec (count values)) (long (* fraction (count values))))))

(defn- measure [operation]
  (let [deadline (+ (System/nanoTime) 1000000000)
        bean ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        tid (.getId (Thread/currentThread))]
    (.setThreadAllocatedMemoryEnabled bean true)
    (loop [n 0]
      (when (or (< n 50) (< (System/nanoTime) deadline))
        (operation)
        (recur (inc n))))
    (let [samples (vec (for [_ (range 50)]
                         (let [bytes (.getThreadAllocatedBytes bean tid)
                               started (System/nanoTime)]
                           (dotimes [_ 3] (operation))
                           {:ns (/ (- (System/nanoTime) started) 3.0)
                            :bytes (/ (- (.getThreadAllocatedBytes bean tid) bytes) 3.0)})))]
      {:median-ns (percentile (map :ns samples) 0.5)
       :p95-ns (percentile (map :ns samples) 0.95)
       :median-bytes (percentile (map :bytes samples) 0.5)
       :samples samples})))

(defn capture! [output-path]
  (let [shape (assoc fixture/default-shape :servers-per-account 400)
        report {:environment {:java (System/getProperty "java.runtime.version")
                              :clojure (clojure-version)
                              :os (System/getProperty "os.name")
                              :arch (System/getProperty "os.arch")}
                :shape shape :cache :disabled
                :results
                (into (array-map)
                      (for [backend [:datascript :datomic :datahike]]
                        (let [{:keys [client transact! close!]} (open! backend)]
                          (try
                            (eacl/write-schema! client fixture/schema)
                            (transact! (vec (fixture/object-transactions shape)))
                            (doseq [batch (fixture/relationship-batches shape)]
                              (eacl/create-relationships! client (vec batch)))
                            [backend (into (array-map)
                                           (for [[name operation] (operations client)]
                                             [name (try (measure operation)
                                                        (catch clojure.lang.ExceptionInfo error
                                                          {:error (ex-data error)
                                                           :message (.getMessage error)}))]))]
                            (finally (close!))))))}]
    (io/make-parents output-path)
    (with-open [writer (io/writer output-path)]
      (binding [*out* writer] (pprint/pprint report)))
    output-path))
