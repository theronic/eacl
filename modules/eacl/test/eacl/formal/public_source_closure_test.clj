(ns eacl.formal.public-source-closure-test
  (:require
   [clojure.edn :as edn]
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
  [file features]
  (with-open [reader
              (java.io.PushbackReader.
               (io/reader file))]
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
  [file features]
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
   (read-forms file features)))

(deftest every-backend-dispatch-key-is-closed-over-the-required-contract
  (let [root (repository-root)
        report
        (edn/read-string
         (slurp
          (io/file
           root
           "formal/verification/backend-dispatch.edn")))
        files (source-files root (:source-roots report))]
    (doseq [[runtime features] [[:clj #{:clj}]
                               [:cljs #{:cljs}]]]
      (testing (name runtime)
        (let [calls (mapcat #(invoke-calls % features) files)
              nonliteral
              (vec (remove #(keyword? (:operation %)) calls))
              observed (set (map :operation calls))
              expected (get-in report [:runtime-passes runtime])]
          (is (empty? nonliteral)
              (str "dynamic backend dispatch escaped the operation ledger: "
                   (pr-str nonliteral)))
          (is (= (:invoke-call-count expected)
                 (count calls)))
          (is (= (:operations expected)
                 observed))
          (is (= (into
                  (conj backend/required-snapshot-operations :proof-frame)
                  backend/optional-snapshot-operations)
                 observed))
          (is (empty?
               (set/difference
                 observed
                 (set (keys backend/basis-adapter-obligations))))))))))

(deftest external-certification-gate-names-every-open-refinement-test
  (let [root (repository-root)
        manifest
        (edn/read-string
         (slurp
          (io/file root "formal/verification/manifest.edn")))
        unmet
        (set
         (get-in manifest
                 [:release-gate :unmet-required-obligations]))
        generated-boundary
        (edn/read-string
         (slurp
          (io/file root "formal/verification/generated-boundary.edn")))
        required-open-obligations
        #{:mechanized-host-control-source-refinement
          :mechanized-clj-cache-transition-source-refinement
          :mechanized-cljs-production-authority-refinement
          :mechanized-backend-adapter-conversion-refinement
          :independent-security-formal-review}]
    (is (false?
         (get-in manifest
                 [:release-gate :verified-status-allowed?])))
    (is (= {:status :unsigned
            :procedure
            "formal/verification/external-certifier-procedure.md"}
           (:external-certification manifest)))
    (is (.isFile
         (io/file root
                  (get-in manifest
                          [:external-certification :procedure]))))
    (is (set/subset? required-open-obligations unmet))
    (is (= {:source "formal/dafny/CacheKernel.dfy"
            :runtime-operation :none
            :reason :proof-only-model
            :production-assurance-contribution :none}
           (first (:proof-only-exclusions manifest))))
    (is (not (some #{:cache-validation}
                   (:production-routed-decisions generated-boundary))))
    (is (empty? (:proof-only-generated-decisions generated-boundary)))
    (is (= :conditionally-verified (:assurance-status manifest)))))
