(ns eacl.formal.indexed-traversal-benchmark
  "Target-runtime scaling probe for the generated indexed traversal.

  This measures the complete CLJS boundary from one ordered backend response
  through generated fixed-point drive and public result conversion. It is
  deliberately independent of database seek cost."
  (:refer-clojure :exclude [run!])
  (:require
   [eacl.formal.production-kernel :as production]
   [eacl.verified-kernel :as verified]))

(def ^:private selection
  {:mode :verified-authoritative
   :kernel production/generated-javascript-kernel})

(def ^:private direct-rule
  {:kind :relation
   :head {:resource-type "folder" :permission "read"}
   :relation-eid 1
   :subject-type "user"})

(defn- percentile
  [values proportion]
  (let [ordered (vec (sort values))
        index
        (min
         (dec (count ordered))
         (js/Math.floor (* proportion (count ordered))))]
    (nth ordered index)))

(defn- start-run
  [compiled-plan size]
  (let [limits
        {:max-derived-grants (+ size 16)
         :max-advanced-datoms (+ size 16)
         :max-queued-work (+ size 16)}
        initialized
        (verified/initialize-indexed
         selection
         :forward
         {:compiled-plan compiled-plan
          :request-scope 991
          :subject-type "user"
          :subject-eid 7
          :root-node
          {:resource-type "folder" :permission "read"}
          :result-type "folder"
          :render {:kind :page :size size :bound nil}
          :chunk-size size
          :limits limits})
        need-scan
        (verified/drive-indexed
         selection :forward (:state initialized) limits 64)]
    {:limits limits
     :need-scan need-scan}))

(defn- measure-once
  [compiled-plan size]
  (let [{:keys [limits need-scan]} (start-run compiled-plan size)
        command (:command need-scan)
        values (vec (range 1 (inc size)))
        started (.now js/performance)
        resumed
        (verified/resume-indexed
         selection :forward (:state need-scan)
         {:request-scope (:request-scope command)
          :request-id (:request-id command)
          :values values
          :terminal? true
          :fetched-values size}
         limits)
        completed
        (verified/drive-indexed
         selection :forward (:state resumed)
         limits (+ 128 (* 4 size)))
        result
        (verified/read-indexed-result
         selection :forward (:state completed))
        elapsed-ns
        (* 1000000.0 (- (.now js/performance) started))]
    (when-not
     (and (= :complete (:status completed))
          (= :page (:status result))
          (= values (:items result))
          (= size (get-in result [:counters :unique-grants]))
          (= size (get-in result [:counters :emitted-results])))
      (throw
       (ex-info
        "Generated indexed traversal benchmark changed semantics."
        {:size size
         :completed-status (:status completed)
         :result-status (:status result)
         :item-count (count (:items result))
         :counters (:counters result)})))
    elapsed-ns))

(defn run!
  ([]
   (run! {}))
  ([{:keys [sizes warmup samples]
     :or {sizes [1024 2048 4096 8192 16384]
          warmup 2
          samples 5}}]
   (let [compiled-plan
         (verified/compile-indexed-plan
          selection
          {:indexed-rules [direct-rule]
           :seed-rules-by-subject-type
           {"user" [direct-rule]}})
         measurements
         (mapv
          (fn [size]
            (dotimes [_ warmup]
              (measure-once compiled-plan size))
            (let [observed
                  (mapv
                   (fn [_] (measure-once compiled-plan size))
                   (range samples))
                  p50 (percentile observed 0.5)]
              {:size size
               :p50-elapsed-ns p50
               :p95-elapsed-ns (percentile observed 0.95)
               :p50-ns-per-result (/ p50 size)}))
          sizes)
         smallest (first measurements)
         largest (peek measurements)
         normalized-ratio
         (/ (:p50-ns-per-result largest)
            (:p50-ns-per-result smallest))
         maximum-normalized-ratio 1.5]
     {:runtime :cljs-generated-javascript
      :fixture :one-direct-relation-complete-denotation
      :measurements measurements
      :required
      {:maximum-largest-to-smallest-normalized-p50-per-result-ratio
       maximum-normalized-ratio}
      :normalized-p50-per-result-ratio normalized-ratio
      :status
      (if (<= normalized-ratio maximum-normalized-ratio)
        :passed
        :failed)
      :qualification
      :target-runtime-regression-measurement-not-backend-or-heap-proof})))

(defn -main
  []
  (let [sizes-env (.. js/process -env -EACL_INDEXED_SIZES)
        sizes
        (when (seq sizes-env)
          (mapv #(js/Number.parseInt % 10)
                (.split sizes-env ",")))
        samples-env (.. js/process -env -EACL_INDEXED_SAMPLES)
        warmup-env (.. js/process -env -EACL_INDEXED_WARMUP)]
    (let [result
          (run!
           (cond-> {}
             sizes (assoc :sizes sizes)
             (seq samples-env)
             (assoc :samples (js/Number.parseInt samples-env 10))
             (seq warmup-env)
             (assoc :warmup (js/Number.parseInt warmup-env 10))))]
      (println (pr-str result))
      (when (= :failed (:status result))
        (throw
         (ex-info
          "Generated JavaScript indexed traversal scaling regressed."
          result))))))

(set! *main-cli-fn* -main)
