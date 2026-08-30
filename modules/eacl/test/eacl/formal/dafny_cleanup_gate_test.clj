(ns eacl.formal.dafny-cleanup-gate-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.test-support.repo :as repo]))

(def active-source-roots
  [(repo/file "formal" "dafny")
   (repo/file "formal" "smoke" "cljs")
   (repo/file "formal" "smoke" "js")
   (repo/file "modules" "eacl" "src")])

(def retired-markers
  ["module Pagination"
   "CursorBoundRebase"
   "RebaseCursorBound"
   "PaginateRelationshipContinuation"
   "datatype AfterCursor"
   "RenderBackwardPage"
   ":backward-page"])

(defn- file-path [^java.io.File file]
  (.getPath file))

(defn- active-source-file? [^java.io.File file]
  (let [path (.getPath file)]
    (and (.isFile file)
         (not (str/includes? path "node_modules"))
         (some #(str/ends-with? path %)
               [".clj" ".cljc" ".cljs" ".dfy" ".mjs" ".cjs" ".html"]))))

(deftest every-dafny-model-has-an-explicit-consumer-test
  (let [contract
        (load-file (str (repo/file "formal" "assurance_contract.clj")))
        operations (:operation-contracts contract)
        ^java.io.File dafny-directory (repo/file "formal" "dafny")
        actual
        (->> (.listFiles dafny-directory)
             (filter (fn [^java.io.File file]
                       (str/ends-with? (.getName file) ".dfy")))
             (map (fn [^java.io.File file]
                    (str "formal/dafny/" (.getName file))))
             set)
        covered
        (->> operations
             (mapcat :dafny)
             (filter #(str/starts-with? % "formal/dafny/"))
             set)]
    (is (= actual covered))
    (doseq [{:keys [operation entry-points dafny]} operations]
      (testing (name operation)
        (is (keyword? operation))
        (is (seq entry-points))
        (is (seq dafny))))))

(deftest retired-dafny-surface-is-absent-test
  (is (not (.exists (io/file (repo/file "formal" "dafny")
                             "Pagination.dfy"))))
  (doseq [file (mapcat file-seq active-source-roots)
          :when (active-source-file? file)
          marker retired-markers]
    (is (not (str/includes? (slurp file) marker))
        (str marker " remains in " (file-path file)))))
