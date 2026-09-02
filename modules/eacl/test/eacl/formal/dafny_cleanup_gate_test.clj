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

(defn- entry-point-exists? [entry-point]
  (cond
    (and (symbol? entry-point) (namespace entry-point))
    (if (str/starts-with? (namespace entry-point) "eacl.")
      (or (some? (try
                   (requiring-resolve entry-point)
                   (catch java.io.FileNotFoundException _ nil)))
          ;; A defrecord named directly resolves as its generated class.
          (some? (try
                   (Class/forName (str (munge (namespace entry-point))
                                       "." (name entry-point)))
                   (catch Throwable _ nil)))
          ;; A CLJS-only namespace cannot load on the JVM; accept it when
          ;; its ClojureScript source exists.
          (.exists
           (io/file
            (str "modules/eacl/src/"
                 (-> (namespace entry-point)
                     (str/replace "." "/")
                     (str/replace "-" "_"))
                 ".cljs"))))
      (some? (try
               (Class/forName (namespace entry-point))
               (catch ClassNotFoundException _ nil))))

    (symbol? entry-point)
    (try
      (require entry-point)
      true
      (catch java.io.FileNotFoundException _ false))

    (string? entry-point)
    (or (.exists (io/file entry-point))
        ;; The contract also writes var/class members as strings.
        (let [sym (symbol entry-point)]
          (and (some? (namespace sym))
               (entry-point-exists? sym))))

    :else false))

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
        ;; Decorative entry-points rotted silently before this: every
        ;; listed entry must denote something that exists - a resolvable
        ;; eacl var, a loadable namespace, a loadable generated Java
        ;; class member, or an existing repository file.
        (doseq [entry-point entry-points]
          (is (entry-point-exists? entry-point)
              (str "assurance-contract entry-point does not resolve: "
                   entry-point)))
        (is (seq dafny))))))

(deftest retired-dafny-surface-is-absent-test
  (is (not (.exists (io/file (repo/file "formal" "dafny")
                             "Pagination.dfy"))))
  (doseq [file (mapcat file-seq active-source-roots)
          :when (active-source-file? file)]
    ;; One read per file; the marker loop previously re-slurped every
    ;; file once per marker.
    (let [source (slurp file)]
      (doseq [marker retired-markers]
        (is (not (str/includes? source marker))
            (str marker " remains in " (file-path file)))))))
