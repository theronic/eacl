(ns eacl.backend.entity-id
  "Native backend IDs use the host's exact integer range, not the wire range."
  (:require [eacl.exact-integer :as exact-integer]))

(defn valid? [value]
  #?(:clj (and (integer? value) (<= 0 value Long/MAX_VALUE))
     :cljs (exact-integer/natural? value)))

(defn wire-value
  "Lossless portable form of a validated native integer."
  [value]
  (if (exact-integer/exact? value) value (str value)))
