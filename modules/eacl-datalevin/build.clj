(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-datalevin options))

(defn jar [options]
  (module/jar! :eacl-datalevin options))

(defn install [options]
  (module/install! :eacl-datalevin options))
