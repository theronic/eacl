(ns build
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b]))

(def module-dir
  (if (.exists (io/file "modules/eacl-datahike/src"))
    "modules/eacl-datahike"
    "."))

(def lib 'cloudafrica/eacl-datahike)
(def version "0.0.1-SNAPSHOT")
(def class-dir (str module-dir "/target/classes"))
(def jar-file (format "%s/target/%s-%s.jar" module-dir (name lib) version))
(def pom-basis
  {:libs {'org.clojure/clojure {:mvn/version "1.11.4"}
          'cloudafrica/eacl {:mvn/version version}
          'com.rpl/specter {:mvn/version "1.1.4"}
          'org.replikativ/datahike {:mvn/version "0.8.1759"}}})

(defn clean [_]
  (b/delete {:path (str module-dir "/target")}))

(defn jar [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis pom-basis
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
              :basis pom-basis}))
