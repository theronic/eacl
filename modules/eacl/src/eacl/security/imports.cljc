(ns eacl.security.imports
  "Private trust attached to authenticated optional cache inputs."
  (:require [eacl.security.protocols :as keyrings]))

(def ^:dynamic *consumed-import?* nil)

(deftype ImportedValue [value controller kid])

(defn imported? [value] (instance? ImportedValue value))

(defn protect [value controller kid]
  (->ImportedValue value controller kid))

(defn value [^ImportedValue entry] (.-value entry))

(defn accepted?
  "One current controller capture and one named-key lookup. A private import
   is usable only inside a scope that suppresses derived cache publication."
  [^ImportedValue entry]
  (and *consumed-import?*
       (some? (get (:keys (keyrings/-snapshot (.-controller entry))) (.-kid entry)))))

(defn consumed! []
  (when *consumed-import?* (reset! *consumed-import?* true)))

(defn derived? [] (boolean (and *consumed-import?* @*consumed-import?*)))

(defn run
  "Retains optional-input provenance until the caller publishes its result.
   Imported values cannot silently become locally computed cache authority."
  [f]
  (binding [*consumed-import?* (or *consumed-import?* (atom false))]
    (let [result (f)]
      (cond-> result (derived?) (assoc :eacl.cache/imported? true)))))
