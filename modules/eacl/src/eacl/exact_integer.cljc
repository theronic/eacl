(ns eacl.exact-integer
  "Dependency-free exact-integer bounds shared by portable host boundaries.")

(def ^:const maximum 9007199254740991)
(def ^:const minimum -9007199254740991)

(defn exact?
  "True for an integer exactly representable by every supported host."
  [value]
  (and #?(:clj (integer? value)
          :cljs (and (number? value)
                     (js/Number.isSafeInteger value)))
       (<= minimum value maximum)))

(defn natural?
  "True for a non-negative portable exact integer."
  [value]
  (and (exact? value) (not (neg? value))))

(defn positive?
  "True for a positive portable exact integer."
  [value]
  (and (exact? value) (pos? value)))
