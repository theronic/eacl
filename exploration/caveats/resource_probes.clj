(ns eacl.exploration.caveats.resource-probes
  (:require [clojure.string :as str]
            [exoscale.cel.parser :as cel]
            [exoscale.cel.expr :as expr])
  (:import [java.lang.management ManagementFactory]
           [com.sun.management ThreadMXBean]))

(def cases
  [{:id :boolean-plan-255-nodes
    :source (str/join " && " (repeat 128 "a"))
    :bindings {:a true} :expected true}
   {:id :source-group-depth-32
    :source (str (apply str (repeat 32 "(")) "a == 1"
                 (apply str (repeat 32 ")")))
    :bindings {:a 1} :expected true}
   {:id :string-source-8008-bytes
    :source (str (pr-str (apply str (repeat 4000 "a"))) " == "
                 (pr-str (apply str (repeat 4000 "a"))))
    :bindings {} :expected true}
   {:id :substring-4096-by-2048
    :source "a.contains(b)"
    :bindings {:a (str (apply str (repeat 4095 "a")) "b")
               :b (str (apply str (repeat 2047 "a")) "c")}
    :expected false}
   {:id :membership-128-strings
    :source "x in xs"
    :bindings {:x (str (apply str (repeat 1023 "a")) "z")
               :xs (mapv #(str (apply str (repeat 1023 "a")) (char (+ 32 (mod % 58))))
                         (range 128))}
    :expected false}
   {:id :map-128-entries
    :source "m[x]"
    :bindings {:m (into {} (map #(vector (str %) true)) (range 128)) :x "127"}
    :expected true}])

(defn sample [f]
  (let [^ThreadMXBean bean (ManagementFactory/getThreadMXBean)
        tid (.threadId (Thread/currentThread))]
    (dotimes [_ 200] (f))
    (let [samples (vec (repeatedly
                       40
                       #(let [allocated (.getThreadAllocatedBytes bean tid)
                              start (System/nanoTime)]
                          (dotimes [_ 10] (f))
                          {:ns (/ (- (System/nanoTime) start) 10.0)
                           :bytes (/ (- (.getThreadAllocatedBytes bean tid) allocated) 10.0)})))
          times (sort (map :ns samples))]
      {:p50-ns (nth times 20) :p95-ns (nth times 37)
       :mean-allocated-bytes (/ (reduce + (map :bytes samples)) 40.0)})))

(defn run-probes! []
  {:java (System/getProperty "java.version")
   :os (System/getProperty "os.name")
   :arch (System/getProperty "os.arch")
   :results
   (mapv (fn [{:keys [id source bindings expected]}]
           (let [program (cel/make-program source)
                 evaluate #(let [r (cel/eval-for program bindings {:translate-result? false})]
                             (assert (and (expr/bool? r) (= expected (expr/val r)))))]
             (evaluate)
             {:id id :source-utf8-bytes (alength (.getBytes ^String source "UTF-8"))
              :compile (sample #(cel/make-program source))
              :evaluate-including-binding-conversion (sample evaluate)}))
         cases)})
