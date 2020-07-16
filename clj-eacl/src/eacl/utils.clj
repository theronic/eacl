(ns eacl.utils
  (:require [taoensso.timbre :as log]))

(def profile? true) ;; set to true to see profiling.

(defn format-nanoseconds
  [nanos]
  (cond (> nanos 1000000000)
        (format "%.1fs" (/ nanos 1.0E9))
        (> nanos 1000000)
        (format "%.0fms" (/ nanos 1.0E6))
        :else
        (format "%.0fµs" (/ nanos 1.0E3))))

(defmacro profile [k & body]
  `(let [k# ~k]
     (.time js/console k#)
     (let [res# (do ~@body)]
       (.timeEnd js/console k#)
       res#)))

(defmacro spy [& body]
  `(let [res# (do ~@body)]
     (prn res#)
     res#))


;; these are Clojure-based spy and profile. Todo conditional.
;
;(defmacro profile [k & body]
;  (if profile?
;    `(let [k# ~k
;           nano-now# (System/nanoTime)]
;       (let [res#         (do ~@body)
;             nano-after#  (System/nanoTime)
;             nano-elapsed# (- nano-after# nano-now#)]
;         (log/debug k# "Elapsed" (format-nanoseconds nano-elapsed#))
;         res#))
;    `(do ~@body)))
;
;(defmacro spy [& body]
;  `(let [res# (do ~@body)]
;     (log/debug res#)
;     res#))