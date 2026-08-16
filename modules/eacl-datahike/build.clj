(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-datahike options))

(defn jar [options]
  (module/jar! :eacl-datahike options))

(defn install [options]
  (module/install! :eacl-datahike options))
