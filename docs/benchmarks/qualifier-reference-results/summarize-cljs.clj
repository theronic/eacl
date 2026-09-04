(require '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.pprint :as pprint])
(def directory (or (first *command-line-args*) (str (.getParent (io/file *file*)))))
(defn percentile [xs p] (nth (vec (sort xs)) (min (dec (count xs)) (long (* p (count xs))))))
(defn runs [side]
  (mapv #(edn/read-string (slurp (str directory "/" side "-cljs-" % ".edn"))) [1 2 3]))
(def before (runs "baseline"))
(def after (runs "candidate"))
(assert (apply = (map #(select-keys % [:first-page-ids :reverse-page-ids :logical-relationship-datoms]) (concat before after))))
(defn metric [runs name p]
  (percentile (mapcat #(get-in % [:metrics name :samples-ns]) runs) p))
(defn allocation [runs name]
  (/ (double (reduce + (map #(get-in % [:allocation name :estimated-allocated-bytes-per-operation]) runs))) (count runs)))
(def result
  (into {} (for [name (keys (:metrics (first before)))]
             (let [median-ratio (/ (metric after name 0.5) (metric before name 0.5))
                   p95-ratio (/ (metric after name 0.95) (metric before name 0.95))
                   allocation-ratio (/ (allocation after name) (allocation before name))]
               [name {:baseline-median-ns (metric before name 0.5)
                      :candidate-median-ns (metric after name 0.5)
                      :median-ratio median-ratio :p95-ratio p95-ratio
                      :estimated-allocation-ratio allocation-ratio
                      :pass? (and (<= median-ratio (if (= name :warm-exact) 1.2 1.5))
                                  (<= p95-ratio 1.75) (<= allocation-ratio 1.5))}]))))
(spit (str directory "/cljs-summary.edn") (with-out-str (pprint/pprint result)))
(pprint/pprint result)
