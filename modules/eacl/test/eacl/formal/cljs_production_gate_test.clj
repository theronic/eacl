(ns eacl.formal.cljs-production-gate-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo])
  (:import (java.io ByteArrayOutputStream)
           (java.nio.file Files)
           (java.util.zip GZIPOutputStream)))

(def policy-path
  (repo/file "formal" "policy" "cljs-production.edn"))

(defn- byte-size [file]
  (Files/size (.toPath file)))

(defn- gzip-size [file]
  (let [out (ByteArrayOutputStream.)]
    (with-open [gzip (GZIPOutputStream. out)]
      (.write gzip (Files/readAllBytes (.toPath file))))
    (.size out)))

(deftest ^:formal-artifact
  production-cljs-bundle-is-native-small-and-oracle-free-test
  (let [{:keys [bundle generated-oracle]} (edn/read-string (slurp policy-path))
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
    (testing "platform-independent absolute and incremental payload ceilings"
      ;; Advanced output and its gzip representation are not byte-stable across
      ;; the supported CI/local build environments. Observations are evidence;
      ;; ceilings and forbidden-runtime markers are the release invariants.
      (is (pos? empty-bytes))
      (is (> kernel-bytes empty-bytes))
      (is (<= empty-bytes (:maximum-empty-runtime-bytes bundle)))
      (is (<= kernel-bytes (:maximum-portable-kernel-bytes bundle)))
      (is (<= empty-gzip (:maximum-empty-runtime-gzip-bytes bundle)))
      (is (<= kernel-gzip (:maximum-portable-kernel-gzip-bytes bundle)))
      (is (pos? incremental))
      (is (pos? incremental-gzip))
      (is (false?
           (get-in bundle
                   [:representation-policy :cross-platform-byte-equality])))
      (is (= #{:absolute-payload-ceilings
               :incremental-engine-ceilings
               :forbidden-production-runtime-markers}
             (set (get-in bundle
                          [:representation-policy :release-invariants]))))
      (is (<= incremental (:maximum-incremental-engine-bytes bundle)))
      (is (<= incremental-gzip
              (:maximum-incremental-engine-gzip-bytes bundle)))
      (is (< (* 10 incremental) (:retired-generated-iife-bytes bundle))))
    (testing "reference observations are internally consistent and bounded"
      (doseq [observation (:reference-observations bundle)]
        (let [label (pr-str (:environment observation))]
          (is (= (:incremental-engine-bytes observation)
                 (- (:portable-kernel-bytes observation)
                    (:empty-runtime-bytes observation)))
              label)
          (is (= (:incremental-engine-gzip-bytes observation)
                 (- (:portable-kernel-gzip-bytes observation)
                    (:empty-runtime-gzip-bytes observation)))
              label)
          (is (<= (:empty-runtime-bytes observation)
                  (:maximum-empty-runtime-bytes bundle))
              label)
          (is (<= (:portable-kernel-bytes observation)
                  (:maximum-portable-kernel-bytes bundle))
              label)
          (is (<= (:empty-runtime-gzip-bytes observation)
                  (:maximum-empty-runtime-gzip-bytes bundle))
              label)
          (is (<= (:portable-kernel-gzip-bytes observation)
                  (:maximum-portable-kernel-gzip-bytes bundle))
              label))))
    (testing "production graph contains no generated/BigNumber runtime"
      (doseq [marker (:forbidden-production-bundle-markers bundle)]
        (is (not (str/includes? production-source marker)) marker))
      (is (false? (:production-classpath generated-oracle)))
      (is (.isFile (repo/file (:adapter generated-oracle)))))))
