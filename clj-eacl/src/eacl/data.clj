(ns eacl.data
  (:require
    [datascript.core :as d]
    [eacl.utils :refer [spy profile]]))

(defn q [qry & inputs]
  (profile qry (apply d/q qry inputs)))