(ns eacl.build.module-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.build.api :as b]
            [eacl.build.module :as module])
  (:import (java.io DataOutputStream FileOutputStream)
           (java.nio.file Files)))

(defn- temporary-directory
  []
  (.toFile
   (Files/createTempDirectory
    "eacl-bytecode-test-"
    (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-class-header!
  [file major]
  (with-open [output
              (DataOutputStream.
               (FileOutputStream. ^java.io.File file))]
    (.writeInt output (unchecked-int 0xCAFEBABE))
    (.writeShort output 0)
    (.writeShort output major)))

(defn- write-entry!
  [root entry]
  (let [file (io/file root entry)]
    (.mkdirs (.getParentFile file))
    (spit file "fixture")
    file))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (ex-data error))))

(deftest generated-bytecode-target-defaults-to-java-26-and-allows-older-java
  (let [directory (temporary-directory)
        class-file (io/file directory "Generated.class")]
    (try
      (testing "the explicit Java 26 default accepts class-file major 70"
        (write-class-header! class-file 70)
        (is (true? (module/assert-generated-bytecode!
                    (.getPath directory)
                    {:java-release 26}))))
      (testing "a mismatched class level is rejected"
        (write-class-header! class-file 69)
        (let [failure
              (try
                (module/assert-generated-bytecode!
                 (.getPath directory)
                 {:java-release 26})
                nil
                (catch clojure.lang.ExceptionInfo exception
                  exception))]
          (is (= :eacl.build/unsupported-bytecode
                 (:type (ex-data failure))))
          (is (= 70 (:expected-major (ex-data failure))))))
      (testing "an explicit Java 8 target accepts class-file major 52"
        (write-class-header! class-file 52)
        (is (true? (module/assert-generated-bytecode!
                    (.getPath directory)
                    {:java-release 8}))))
      (finally
        (b/delete {:path (.getPath directory)})))))

(deftest datalevin-adapter-jar-cannot-bundle-backends-or-native-runtime
  (let [directory (temporary-directory)
        classes (io/file directory "classes")
        jar-file (io/file directory "adapter.jar")]
    (try
      (write-entry! classes "eacl/datalevin/core.cljc")
      (b/jar {:class-dir (.getPath classes)
              :jar-file (.getPath jar-file)})
      (is (true?
           (module/assert-adapter-jar-isolation!
            :eacl-datalevin (.getPath jar-file))))
      (doseq [forbidden-entry
              ["eacl/datomic/core.clj"
               "eacl/datahike/core.clj"
               "eacl/datascript/core.cljc"
               "datalevin/dtlvnative/linux-arm64/libdtlv.so"
               "org/bytedeco/javacpp.class"]]
        (b/delete {:path (.getPath classes)})
        (write-entry! classes "eacl/datalevin/core.cljc")
        (write-entry! classes forbidden-entry)
        (b/jar {:class-dir (.getPath classes)
                :jar-file (.getPath jar-file)})
        (let [failure
              (error-data
               #(module/assert-adapter-jar-isolation!
                 :eacl-datalevin (.getPath jar-file)))]
          (is (= :eacl.build/backend-isolation-failed (:type failure)))
          (is (= [forbidden-entry] (:entries failure)))))
      (finally
        (b/delete {:path (.getPath directory)})))))
