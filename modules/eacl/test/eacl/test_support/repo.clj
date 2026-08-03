(ns eacl.test-support.repo
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private optional-module-resource-by-namespace-prefix
  [["eacl.datomic." "eacl/datomic/core.clj"]
   ["eacl.bench." "eacl/datomic/core.clj"]
   ["eacl.datahike." "eacl/datahike/core.clj"]
   ["eacl.datascript." "eacl/datascript/core.cljc"]])

(defn file
  "Resolves a repository-relative test artifact from either the repository root
  or an isolated module working directory."
  [& path]
  (loop [directory (.getCanonicalFile (io/file "."))]
    (let [candidate (apply io/file directory path)]
      (cond
        (.exists candidate)
        candidate

        (.getParentFile directory)
        (recur (.getParentFile directory))

        :else
        (throw
         (ex-info
          "Repository test artifact does not exist."
          {:path (vec path)}))))))

(defn evidence-namespace-available?
  "Returns false only when a symbol belongs to a known optional backend module
  that is absent from the current isolated classpath. Unknown namespaces and
  namespaces for present modules remain resolvable obligations, so typos fail
  instead of being silently skipped."
  [qualified-symbol]
  (let [namespace-name (namespace qualified-symbol)]
    (if-let [[_ resource]
             (some
              (fn [[prefix resource]]
                (when (and namespace-name
                           (str/starts-with? namespace-name prefix))
                  [prefix resource]))
              optional-module-resource-by-namespace-prefix)]
      (some? (io/resource resource))
      true)))
