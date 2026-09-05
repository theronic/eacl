(ns eacl.relationships.inspection
  "Physical Relationship inspection, separate from Caveat authorization."
  (:require [eacl.authorization.qualification :as qualification]))

(def qualifier-key ::qualifier-eid)
(def relation-key ::relation-eid)

(defn row
  "Keeps qualifier alignment inside a native Relationship row until decoding.
   Ordinary rows retain their existing value with no annotation allocation."
  [relationship relation-eid qualifier-eid]
  (if qualifier-eid
    (assoc relationship qualifier-key qualifier-eid relation-key relation-eid)
    relationship))

(defn decode [request relationship]
  (if-let [qid (get relationship qualifier-key)]
    (merge (dissoc relationship qualifier-key relation-key)
           (qualification/inspect request (get relationship relation-key) qid))
    relationship))

(defn active?
  "Exclusive expiry only. Caveated input is not an authorization grant."
  [time relationship]
  (let [deadline (:valid-until-ms relationship)]
    (or (nil? deadline) (< time deadline))))

(defn window-options [request filters options]
  (if-not request
    options
    (let [options (assoc options :include-qualifier? true)]
      (if (= :expiry-active (:relationship-state filters))
        (assoc options :accept?
               (fn [relationship]
                 (let [relationship (decode request relationship)]
                   (and (active? (:time request) relationship)
                        (if-let [accept? (:accept? options)] (accept? relationship) true)))))
        options))))

(defn decode-page [request page]
  (if request
    (update page :data #(mapv (partial decode request) %))
    page))

(defn page-info [request filters page]
  (if request
    (assoc page :relationship-state (get filters :relationship-state :stored)
           :evaluation-time-ms (:time request))
    page))
