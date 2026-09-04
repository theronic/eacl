(ns eacl.exploration.caveats.corpus-test
  "Independent expected values, pinned before a production adapter exists."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [exoscale.cel.expr :as expr]
            [exoscale.cel.parser :as cel])
  (:import [java.security MessageDigest]
           [java.math BigInteger]))

(def exploration-root
  (.getParentFile (io/file *file*)))

(defn native-binding [type value]
  (cond
    (= :timestamp type) (expr/->TimestampType (java.sql.Timestamp. (second value)))
    (and (vector? type) (= :list (first type)))
    (mapv #(native-binding (second type) %) value)
    (and (vector? type) (= :map (first type)))
    (into {} (map (fn [[k v]] [k (native-binding (nth type 2) v)])) value)
    :else value))

(defn native-outcome [source bindings]
  (try
    (let [result (cel/eval-for (cel/make-program source) bindings
                               {:translate-result? false})]
      (cond (expr/error? result) :error
            (expr/bool? result) (expr/val result)
            :else :non-boolean))
    (catch Exception _ :error)))

(deftest pinned-native-corpus
  (let [corpus (edn/read-string (slurp (io/file exploration-root "corpus.edn")))]
    (is (= 24 (count (:cases corpus))))
    (doseq [{:keys [id source parameters context bound native]} (:cases corpus)]
      (testing (name id)
        (is (= native
               (native-outcome
                source
                (into {} (map (fn [[k v]] [(keyword k) (native-binding (get parameters k) v)]))
                      (merge context bound)))))))))

(deftest literal-binding-boundary
  ;; These values bypass the candidate's unescape code. Expected equality is
  ;; host string equality, independent of the future plan renderer.
  (doseq [s ["" "\\n" "\\u0061" "a\nb" "a\rb" "a\tb" "\\\\" "\"" "😀" "\u0000"]]
    (is (true? (native-outcome "__eacl_l0 == value" {:__eacl_l0 s :value s})))
    (is (false? (native-outcome "__eacl_l0 == value" {:__eacl_l0 s :value (str s "x")})))))

(deftest pinned-artifact-bytes
  (let [inputs (edn/read-string (slurp (io/file exploration-root "inputs.edn")))]
    (doseq [{:keys [coordinate version jar-sha256]} (:artifacts inputs)]
      (let [group (str/replace (namespace coordinate) "." "/")
            artifact (name coordinate)
            jar (io/file (System/getProperty "user.home") ".m2/repository" group artifact version
                         (str artifact "-" version ".jar"))
            digest (MessageDigest/getInstance "SHA-256")]
        (with-open [input (io/input-stream jar)]
          (let [buf (byte-array 8192)]
            (loop []
              (let [n (.read input buf)]
                (when (pos? n) (.update digest buf 0 n) (recur))))))
        (is (= jar-sha256 (format "%064x" (BigInteger. 1 (.digest digest))))
            (str coordinate))))))
