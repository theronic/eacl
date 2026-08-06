(ns build
  (:require [clojure.tools.build.api :as b]))

(def module-dir "modules/eacl")
(def lib 'cloudafrica/eacl)
(def version "0.0.1-SNAPSHOT")
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
  (b/copy-dir {:src-dirs [(str module-dir "/src")
                          (str module-dir "/resources")]
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
