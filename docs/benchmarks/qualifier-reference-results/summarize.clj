(require '[clojure.edn :as edn] '[clojure.pprint :as pprint] '[clojure.java.io :as io])
(def directory (or (first *command-line-args*) (str (.getParent (io/file *file*)))))
(def backends [:datascript :datomic :datahike :datalevin])
(def metrics [:direct :negative :arrow :scan :page :continuation :count :warm-exact])
(defn percentile [xs p] (nth (vec (sort xs)) (min (dec (count xs)) (long (* p (count xs))))))
(defn results [side backend]
  (mapv #(edn/read-string (slurp (str (str directory "/") side (if (= backend :datascript) "-isolated-" "-trial-") % "-" (name backend) ".edn"))) [1 2 3]))
(def summary
  (into {}
        (for [backend backends
              :let [before (results "baseline" backend) after (results "candidate" backend)
                    metric (fn [runs name key p] (percentile (mapcat #(map key (get-in % [:metrics name :samples])) runs) p))]]
          [backend {:write-median-ratio (/ (double (percentile (map :write-ns after) 0.5)) (percentile (map :write-ns before) 0.5))
                    :metrics (into {} (for [name metrics]
                                        [name (let [median-ratio (/ (metric after name :ns 0.5) (metric before name :ns 0.5))
                                                    p95-ratio (/ (metric after name :ns 0.95) (metric before name :ns 0.95))
                                                    allocation-ratio (/ (metric after name :bytes 0.5) (metric before name :bytes 0.5))]
                                                {:baseline-median-ns (metric before name :ns 0.5)
                                                 :candidate-median-ns (metric after name :ns 0.5)
                                                 :median-ratio median-ratio :p95-ratio p95-ratio
                                                 :allocation-ratio allocation-ratio
                                                 :pass? (and (<= median-ratio (if (= name :warm-exact) 1.2 1.5))
                                                             (<= p95-ratio 1.75) (<= allocation-ratio 1.5))})]))}])) )
(spit (str directory "/benchmark-summary-final.edn") (with-out-str (pprint/pprint summary)))
(pprint/pprint summary)
