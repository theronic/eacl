(ns build
  (:require [eacl.build.module :as module]))

(defn prep
  "Build and stage the generated JVM and browser runtimes for source consumers."
  [options]
  (module/prep! options))

(defn clean [options]
  (module/clean! :eacl options))

(defn jar [options]
  (module/jar! :eacl options))

(defn install [options]
  (module/install! :eacl options))
