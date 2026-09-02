(ns eacl.formal.public-source-closure-test
  (:require
   [clojure.java.io :as io]
   [clojure.set :as set]
   [clojure.test :refer [deftest is testing]]
   [clojure.walk :as walk]
   [eacl.backend.v8 :as backend]))

(defn- repository-root
  []
  (loop [candidate (.getCanonicalFile (io/file "."))]
    (cond
      (and (.isDirectory (io/file candidate "formal"))
           (.isDirectory (io/file candidate "modules")))
      candidate

      (nil? (.getParentFile candidate))
      (throw
       (ex-info
        "Could not locate the EACL repository root."
        {:start (.getCanonicalPath (io/file "."))}))

      :else
      (recur (.getParentFile candidate)))))

(defn- source-files
  [root source-roots]
  (->> source-roots
       (map #(io/file root %))
       (mapcat file-seq)
       (filter #(.isFile %))
       (filter #(re-find #"\.clj[cs]?$" (.getName %)))
       (sort-by #(.getCanonicalPath %))))

(defn- read-forms
  [source features]
  (with-open [reader
              (java.io.PushbackReader.
               (java.io.StringReader. source))]
    (loop [forms []]
      (let [form
            (read
             {:eof ::eof
              :read-cond :allow
              :features features}
             reader)]
        (if (= ::eof form)
          forms
          (recur (conj forms form)))))))

(def invoke-symbols
  '#{backend/invoke eacl.backend.v8/invoke})

(defn- invoke-calls
  [[file source] features]
  (mapcat
   (fn [form]
     (let [calls (atom [])]
       (walk/prewalk
        (fn [node]
          (when (and (seq? node)
                     (contains? invoke-symbols (first node)))
            (swap!
             calls
             conj
             {:file (.getPath file)
              :operation (nth node 2 ::missing)
              :form node}))
          node)
        form)
       @calls))
   (read-forms source features)))

(deftest every-backend-dispatch-key-is-closed-over-the-required-contract
  (let [root (repository-root)
        source-roots ["modules/eacl/src"
                      "modules/eacl-datomic/src"
                      "modules/eacl-datahike/src"
                      "modules/eacl-datascript/src"]
        ;; One read per file; both reader-feature passes parse from the
        ;; same string.
        files (mapv (juxt identity slurp) (source-files root source-roots))
        required-operations
        (into (conj backend/required-snapshot-operations :proof-frame)
              backend/optional-snapshot-operations)]
    (doseq [[runtime features] [[:clj #{:clj}]
                               [:cljs #{:cljs}]]]
      (testing (name runtime)
        (let [calls (mapcat #(invoke-calls % features) files)
              nonliteral
              (vec (remove #(keyword? (:operation %)) calls))
              observed (set (map :operation calls))]
          (is (empty? nonliteral)
              (str "dynamic backend dispatch escaped the operation ledger: "
                   (pr-str nonliteral)))
          (is (= required-operations observed))
          (is (empty?
               (set/difference
                 observed
                 (set (keys backend/basis-adapter-obligations))))))))))
