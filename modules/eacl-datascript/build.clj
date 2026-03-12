(ns build
  (:require [clojure.tools.build.api :as b]))

(def module-dir "modules/eacl-datascript")
(def lib 'cloudafrica/eacl-datascript)
(def version "0.0.1-SNAPSHOT")
(def class-dir (str module-dir "/target/classes"))
(def jar-file (format "%s/target/%s-%s.jar" module-dir (name lib) version))
(def pom-basis
  {:libs {'org.clojure/clojure {:mvn/version "1.12.0-alpha5"}
          'cloudafrica/eacl {:mvn/version version}
          'datascript/datascript {:mvn/version "1.7.8"}
          'org.clojure/tools.logging {:mvn/version "1.3.0"}}})

(defn clean [_]
  (b/delete {:path (str module-dir "/target")}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis pom-basis
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
              :basis pom-basis}))
