(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-caveats-jvm options))

(defn jar [options]
  (module/jar! :eacl-caveats-jvm options))

(defn install [options]
  (module/install! :eacl-caveats-jvm options))
