(ns build
  (:require [eacl.build.module :as module]))

(defn clean [options]
  (module/clean! :eacl-caveats-portable options))

(defn jar [options]
  (module/jar! :eacl-caveats-portable options))

(defn install [options]
  (module/install! :eacl-caveats-portable options))
