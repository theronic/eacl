(ns build
  (:require [clojure.java.io :as io]
            [clojure.tools.build.api :as b])
  (:import (java.util.jar JarFile)))

(def module-dir
  (if (.exists (io/file "modules/eacl/src"))
    "modules/eacl"
    "."))
(def repository-root
  (if (= "modules/eacl" module-dir)
    "."
    "../.."))
(def lib 'cloudafrica/eacl)
(def version "8.0.0-SNAPSHOT")
(def class-dir (str module-dir "/target/classes"))
(def jar-file (format "%s/target/%s-%s.jar" module-dir (name lib) version))
(def generated-java-classes
  (str repository-root "/target/formal/java/classes"))
(def required-release-entries
  ["eacl/formal/production_kernel.clj"
   "eacl/formal/production_kernel_cljs.cljs"
   "eacl/engine/portable_decisions.cljc"
   "eacl/engine/portable_indexed.cljc"
   "AcyclicEngine/__default.class"
   "IndexedTraversal/__default.class"
   "dafny/DafnySequence.class"])

(defn- require-generated-runtime!
  []
  (doseq [[label path]
          [["generated JVM classes" generated-java-classes]]]
    (when-not (.exists (io/file path))
      (throw
       (ex-info
        (str "Missing " label
             "; run `bin/formal build-java` before packaging cloudafrica/eacl.")
        {:artifact label
         :path path})))))

(defn clean [_]
  (b/delete {:path (str module-dir "/target")}))

(defn- assert-packaged-runtime!
  []
  (with-open [archive (JarFile. jar-file)]
    (let [missing
          (remove #(.getEntry archive %) required-release-entries)]
      (when (seq missing)
        (throw
         (ex-info
          "Published EACL artifact is missing required authority runtime entries."
          {:jar jar-file
           :missing (vec missing)}))))))

(defn jar [_]
  (require-generated-runtime!)
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis (b/create-basis {:project (str module-dir "/deps.edn")})
                :src-dirs [(str module-dir "/src")]})
  (b/copy-dir {:src-dirs [(str module-dir "/src")]
               :target-dir class-dir})
  (b/copy-dir {:src-dirs [generated-java-classes]
               :target-dir class-dir})
  (let [result
        (b/jar {:class-dir class-dir
                :jar-file jar-file})]
    (assert-packaged-runtime!)
    result))

(defn install [_]
  (jar nil)
  (b/install {:class-dir class-dir
              :jar-file jar-file
              :lib lib
              :version version
              :basis (b/create-basis {:project (str module-dir "/deps.edn")})}))
