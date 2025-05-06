(ns eacl.impl
  (:require [eacl.protocols :as proto :refer [IAuthorization]]
            [eacl.core2 :as eacl]))

(defrecord Spiceomic [conn]
  IAuthorization
  (can? [this subject permission resource]
    ; how to resolve these?
    (eacl/can? (d/db conn) subject permission resource))

  (can? [this subject permission resource _consistency]
    (eacl/can? (d/db conn) subject permission resource))

  (can? [this {:as demand :keys [subject permission resource consistency]}]
    (eacl/can? (d/db conn) subject permission resource))

  (read-schema [this]
    (throw (Exception. "not impl.")))

  (write-schema! [this schema]
    ; write-schema can take and validaet Relations.
    (throw (Exception. "not impl."))))