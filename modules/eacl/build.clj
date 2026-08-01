(ns build
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def module-dir
  (if (.exists (io/file "modules/eacl/src"))
    "modules/eacl"
    "."))
(def lib 'cloudafrica/eacl)
(def version "8.0.0-SNAPSHOT")
(def class-dir (str module-dir "/target/classes"))
(def jar-file (format "%s/target/%s-%s.jar" module-dir (name lib) version))

(defn clean [_]
  (b/delete {:path (str module-dir "/target")}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project (str module-dir "/deps.edn")})
                :src-dirs [(str module-dir "/src")]})
  (b/copy-dir {:src-dirs [(str module-dir "/src")]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:class-dir class-dir
              :jar-file jar-file
              :lib lib
              :version version
              :basis (b/create-basis {:project (str module-dir "/deps.edn")})}))
