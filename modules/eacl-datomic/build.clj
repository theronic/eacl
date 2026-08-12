(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-datomic options))

(defn jar [options]
  (module/jar! :eacl-datomic options))

(defn install [options]
  (module/install! :eacl-datomic options))
