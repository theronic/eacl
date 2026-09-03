(ns eacl.build.source-prep-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

(defn- repository-root
  []
  (loop [candidate (.getCanonicalFile (io/file "."))]
    (cond
      (.isDirectory (io/file candidate "modules" "eacl"))
      candidate

      (nil? (.getParentFile candidate))
      (throw
       (ex-info "Could not locate the EACL repository root."
                {:start (.getCanonicalPath (io/file "."))}))

      :else
      (recur (.getParentFile candidate)))))

(deftest source-dependency-resolution-never-prepares-formal-tools
  (let [root (repository-root)
        deps
        (edn/read-string
         (slurp (io/file root "modules" "eacl" "deps.edn")))
        build-source
        (slurp (io/file root "modules" "eacl" "build.clj"))
        java-build-script
        (slurp (io/file root "formal" "smoke" "java" "run"))]
    (testing "tools.deps has no automatic preparation hook"
      (is (nil? (:deps/prep-lib deps)))
      (is (= ["src"
              "target/generated/java/classes"
              "target/generated/browser"]
             (:paths deps))))
    (testing "source preparation remains an explicit build function"
      (is (re-find #"\(defn prep" build-source))
      (is (re-find #"Build and stage the generated JVM and browser runtimes"
                   build-source)))
    (testing "Java 25 is the default but source preparation accepts an override"
      (is (re-find #"EACL_JAVA_RELEASE:-25" java-build-script))
      (is (re-find #"javac --release \"\$java_release\""
                   java-build-script)))))
