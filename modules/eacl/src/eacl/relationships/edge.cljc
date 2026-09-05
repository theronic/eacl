(ns eacl.relationships.edge
  "Compact ordered scan values. Ordinary edges remain their native eid;
   only qualified edges allocate an [eid qualifier-eid] pair."
  (:require [eacl.exact-integer :as exact]))

(def format-version 1)

(defn pack [eid qualifier-eid]
  (if (nil? qualifier-eid) eid [eid qualifier-eid]))

(defn endpoint [edge]
  (if (vector? edge) (nth edge 0) edge))

(defn qualifier-id [edge]
  (when (vector? edge) (nth edge 1)))

(defn from-datom [datom]
  (let [value (:v datom)]
    (pack (nth value 3) (nth value 4))))

(defn valid?
  "Structural adapter guard. Native qualifier existence/format/allowance is
   checked by the request resolver, never by treating a bad ref as nil."
  [edge]
  (if (vector? edge)
    (and (= 2 (count edge))
         (exact/natural? (nth edge 0))
         (exact/natural? (nth edge 1))
         (pos? (nth edge 1)))
    (exact/natural? edge)))
