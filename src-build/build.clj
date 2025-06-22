(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'cloudafrica/eacl)
(def version "0.0.1")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
      (b/delete {:path "target"}))

(defn jar [_]
      (b/write-pom {:class-dir class-dir
                    :lib lib
                    :version version
                    :basis (b/create-basis {:project "deps.edn"})})
      (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
      (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn deploy [_]
      (jar nil)
      (b/install {:jar-file jar-file})
      (b/deploy {:jar-file jar-file
                 :class-dir class-dir
                 :lib lib
                 :version version}))