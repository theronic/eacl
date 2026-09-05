(ns eacl.authorization.clock
  "Trusted wall time with a process-local non-decreasing high-water mark."
  (:require [eacl.caveats.values :as values]))

(defn clock
  "Wraps a trusted runtime clock, never request context data. Capture once per
   selected operation and retain that value through all edges and batches."
  [sample]
  (when-not (fn? sample) (values/error! :clock-function))
  (let [high-water (atom nil)]
    (fn []
      (let [time (sample)]
        (when-not (values/valid-time? time) (values/error! :clock-time))
        (swap! high-water #(if (nil? %) time (max % time)))))))

(defonce system-clock
  (clock #?(:clj (fn [] (System/currentTimeMillis)) :cljs (fn [] (.now js/Date)))))
