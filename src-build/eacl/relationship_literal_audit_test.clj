(ns eacl.relationship-literal-audit-test
  "Static representation gate, including code quoted for native tx functions."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]))

(def source-roots
  ["modules/eacl/src" "modules/eacl-datomic/src" "modules/eacl-datahike/src"
   "modules/eacl-datascript/src" "modules/eacl-datalevin/src"])

(def codecs #{"endpoint_pair.cljc" "legacy_v7.cljc"})

(defn- endpoint-literal? [value]
  (and (vector? value) (#{4 5} (count value))
       (not (contains? #{:db/add :db/retract :db/cas :db.fn/cas} (first value)))
       (let [[owner relation endpoint] value
             type? #(or (keyword? %) (and (symbol? %) (str/ends-with? (name %) "type")))]
         (and (type? owner) (type? endpoint)
              (or (number? relation)
                  (and (symbol? relation)
                       (re-matches #"(?:.*relation-(?:eid|id)|r-eid)" (name relation))))))))

(defn literals
  "Returns endpoint constructor literals; parameter/destructuring vectors are
  bindings, not values. Quoted transaction code is intentionally traversed."
  [form]
  (letfn [(visit [form]
            (let [parts (when (seq? form) (vec form))
                  head (when (symbol? (first parts)) (name (first parts)))
                  bindings? (contains? #{"let" "let*" "loop" "loop*" "for" "doseq"
                                         "binding" "if-let" "when-let" "when-some" "with-open"} head)
                  function? (contains? #{"fn" "fn*" "defn" "defn-" "defmacro" "defmethod"} head)
                  literal (if (= "vector" head) (subvec parts 1) form)]
              (concat
               (when (endpoint-literal? literal) [literal])
               (cond
                 bindings? (mapcat visit (concat (take-nth 2 (rest (second parts)))
                                                (drop 2 parts)))
                 function? (let [index (first (keep-indexed #(when (vector? %2) %1) parts))]
                             (mapcat visit (if index
                                             (concat (take index parts) (drop (inc index) parts))
                                             (rest parts))))
                 (and parts (vector? (first parts))) (mapcat visit (rest parts))
                 (map? form) (mapcat visit (mapcat identity form))
                 (coll? form) (mapcat visit form)))))]
    (vec (visit form))))

(defn- install-reader-aliases! [form]
  (when (and (seq? form) (= 'ns (first form)))
    (walk/prewalk
     (fn [value]
       (when (and (vector? value) (symbol? (first value)))
         (doseq [[key alias-name] (partition 2 (rest value))
                 :when (and (= :as key) (symbol? alias-name))]
           (alias alias-name (or (find-ns (first value)) (create-ns (first value))))))
       value)
     form)))

(defn- read-literals [reader features]
  (loop [result []]
    (let [form (read {:eof ::eof :read-cond :allow :features features} reader)]
      (if (= ::eof form)
        result
        (do
          (install-reader-aliases! form)
          (recur (into result (map #(hash-map :line (:line (meta form)) :value %) (literals form)))))))))

(defn source-literals
  ([source] (source-literals source #{:clj}))
  ([source features]
   (let [temporary (create-ns (gensym "relationship-audit-"))]
     (try
       (binding [*ns* temporary *read-eval* false
                 *default-data-reader-fn* (fn [_ value] value)]
         (with-open [reader (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. source))]
           (read-literals reader features)))
       (finally (remove-ns (ns-name temporary)))))))

(deftest detects-relationship-constructors-test
  (doseq [source ["(defn bad [] [subject-type relation-eid resource-type resource-eid])"
                  "(defn bad [] [subject-type relation-eid resource-type resource-eid nil])"
                  "(def tx '(fn [db] (vector subject-type relation-eid resource-type resource-eid qualifier-eid)))"
                  "(def tx [[:db/add 1 forward-attribute [:user 10 :document 20 nil]]])"]]
    (is (= 1 (count (source-literals source))) source))
  (is (empty? (source-literals "(defn good [subject-type relation-eid resource-type resource-eid] (pair/forward-value subject-type relation-eid resource-type resource-eid))")))
  (is (empty? (source-literals "(defn good [value] (let [[subject-type relation-eid resource-type resource-eid qualifier-eid] value] qualifier-eid))"))))

(deftest relationship-values-have-one-source-of-truth-test
  (doseq [root source-roots
          file (file-seq (io/file root))
          :when (and (re-find #"\.clj[cs]?$" (.getName file))
                     (not (codecs (.getName file))))
          features (if (str/ends-with? (.getName file) ".cljc") [#{:clj} #{:cljs}] [#{:clj}])]
    (testing (str (.getPath file) " " features)
      (is (empty? (source-literals (slurp file) features))))))
