(ns eacl.schema.expression-limit-calibration
  "Reproducible JVM codec/allocation calibration for the checked-in policy.

  Evaluate `(run! {:warmups 20 :samples 50})` through the repository nREPL."
  (:refer-clojure :exclude [run!])
  (:require [eacl.bench.paired :as paired]
            [eacl.schema.expression :as expression]
            [eacl.schema.expression-limits :as limits]
            [eacl.schema.expression-policy :as policy]))

(defn- leaf [i]
  {:op :relation
   :name (keyword (str "r" i))
   :subject-types [:user]
   :grouped? false})

(defn- wide [n]
  {:op :intersection
   :children (mapv leaf (range n))
   :grouped? false})

(defn- arrow [n]
  {:op :arrow
   :relation :parent
   :partitions
   (mapv (fn [i]
           {:subject-type (keyword (str "t" i))
            :target-kind :permission
            :target-name :view})
         (range n))
   :grouped? false})

(defn- deep [n]
  (reduce (fn [left i]
            {:op :exclusion
             :left left
             :right (leaf i)
             :grouped? false})
          (leaf n)
          (range n)))

(defn- balanced [depth counter]
  (if (zero? depth)
    (leaf (swap! counter inc))
    {:op (if (even? depth) :intersection :union)
     :children [(balanced (dec depth) counter)
                (balanced (dec depth) counter)]
     :grouped? false}))

(defn calibration-inputs []
  {:wide-direct-fan-in-128 (wide 128)
   :arrow-type-partitions-256 (arrow 256)
   :exclusion-depth-64 (deep 63)
   :balanced-nodes-511 (balanced 8 (atom -1))})

(defn- admit [root]
  (let [value (expression/expression :document :view root)]
    {:expression-bytes (limits/expression-byte-size value)
     :normalized (:metrics (limits/normalized-dag value))}))

(defn- compact-arm [[name result]]
  [name
   {:latency-us (select-keys (:latency-us result) [:min :p50 :p95 :max :mean])
    :allocated-bytes
    (some-> (:allocated-bytes result)
            (select-keys [:min :p50 :p95 :max :mean]))}])

(defn run!
  [{:keys [warmups samples] :or {warmups 20 samples 50}}]
  (let [inputs (calibration-inputs)
        benchmark
        (paired/run-paired!
          {:arms (mapv (fn [[name root]]
                         [name (fn [_] (admit root))])
                       inputs)
           :warmups warmups
           :samples samples})]
    {:format-version 1
     :algorithm :height-ordered-shallow-record-interning
     :policy-digest policy/compatibility-digest
     :policy policy/compatibility-value
     :environment (:environment benchmark)
     :warmups warmups
     :samples samples
     :fixtures
     (into {} (map (fn [[name root]] [name (admit root)]) inputs))
     :measurements (into {} (map compact-arm (:arms benchmark)))}))
