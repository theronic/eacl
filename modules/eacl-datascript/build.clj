(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-datascript options))

(defn jar [options]
  (module/jar! :eacl-datascript options))

(defn install [options]
  (module/install! :eacl-datascript options))
