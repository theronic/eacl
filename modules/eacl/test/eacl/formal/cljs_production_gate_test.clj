(ns eacl.formal.cljs-production-gate-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.file Files)
           (java.util.zip GZIPOutputStream)))

(def ledger-path
  (repo/file "formal" "verification" "cljs-production.edn"))

(defn- byte-size [file]
  (Files/size (.toPath file)))

(defn- gzip-size [file]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. out)]
      (.write gzip (Files/readAllBytes (.toPath file))))
    (.size out)))

(deftest production-cljs-bundle-is-native-small-and-oracle-free-test
  (let [{:keys [bundle generated-oracle]} (edn/read-string (slurp ledger-path))
        empty-bundle (repo/file "target" "formal" "cljs-empty-bundle.js")
        kernel-bundle
        (repo/file "target" "formal" "cljs-portable-kernel-bundle.js")
        production-bundle
        (repo/file "target" "formal" "cljs-production-bundle.js")
        empty-bytes (byte-size empty-bundle)
        kernel-bytes (byte-size kernel-bundle)
        empty-gzip (gzip-size empty-bundle)
        kernel-gzip (gzip-size kernel-bundle)
        incremental (- kernel-bytes empty-bytes)
        incremental-gzip (- kernel-gzip empty-gzip)
        production-source (slurp production-bundle)]
    (testing "recorded absolute engine-payload budgets"
      (is (= (:empty-runtime-bytes bundle) empty-bytes))
      (is (= (:portable-kernel-bytes bundle) kernel-bytes))
      (is (= (:empty-runtime-gzip-bytes bundle) empty-gzip))
      (is (= (:portable-kernel-gzip-bytes bundle) kernel-gzip))
      (is (= (:incremental-engine-bytes bundle) incremental))
      (is (= (:incremental-engine-gzip-bytes bundle) incremental-gzip))
      (is (<= incremental (:maximum-incremental-engine-bytes bundle)))
      (is (<= incremental-gzip
              (:maximum-incremental-engine-gzip-bytes bundle)))
      (is (< (* 10 incremental) (:retired-generated-iife-bytes bundle))))
    (testing "production graph contains no generated/BigNumber runtime"
      (doseq [marker (:forbidden-production-bundle-markers bundle)]
        (is (not (str/includes? production-source marker)) marker))
      (is (false? (:production-classpath generated-oracle)))
      (is (.isFile (repo/file (:adapter generated-oracle)))))))
