(ns eacl.caveats.portable.spicedb-test
  "Compares both Caveat evaluators with SpiceDB's answers to the shared corpus.

  formal/fixtures/caveat-comprehensions records SpiceDB v1.56.0's answer for
  every corpus case (see its README): a check against a relationship whose
  Caveat is the case's source, with the case's bound and request contexts,
  or, for a case EACL rejects, a schema write with that Caveat. This test
  derives the same requests from the corpus, so a changed case fails until it
  is recaptured, and compares the answers:

  - true, false and conditional (with missing fields) with SpiceDB's
    permissionship and missing context;
  - a missing map key with SpiceDB's `no such key` evaluation error;
  - a rejected definition with a rejected schema write.

  The cases where EACL deliberately differs are listed in `divergences`; any
  other difference, or a listed one that no longer differs, fails."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as caveat-evaluator]
            [eacl.caveats.jvm :as jvm]
            [eacl.caveats.portable :as portable]
            [eacl.test-support.repo :as repo]))

(def divergences
  "Corpus cases whose EACL outcome is not SpiceDB's, and why."
  {;; EACL's fail-closed order: without a deciding element a fault wins over
   ;; a missing field, in a fold as in `&&` and `||`. cel-go prefers unknown.
   :fault-before-missing-field :fault-before-missing-field
   ;; cel-go marks the missing parameter x unknown, and with it the
   ;; comprehension variable x that hides it, so SpiceDB reports x missing
   ;; although `exists` is false for every x.
   :variable-shadows-parameter :spicedb-unknown-reaches-hidden-variable
   ;; The work preflight charges an absent list at its declared maximum size,
   ;; here once per element; SpiceDB has no such bound.
   :absent-list-charged-per-element :work-preflight-charges-absent-operands
   ;; Admission accepts only the declared type; SpiceDB converts the string
   ;; "2" to the int parameter.
   :wrong-supplied-type :eacl-admits-only-declared-types
   ;; Profile exclusions that SpiceDB accepts. (SpiceDB rejects
   ;; :repeated-not-excluded too, because its Caveat has no parameters.)
   :bad-overload :profile-excludes
   :regex-excluded :profile-excludes
   :arithmetic-excluded :profile-excludes
   :exists-one-excluded :profile-excludes
   :map-macro-excluded :profile-excludes
   ;; SpiceDB accepts it but reads __result__ as cel-go's accumulator, not as
   ;; the parameter, inside the comprehension.
   :accumulator-parameter-rejected :spicedb-reads-accumulator})

(defn- fixture-file [name]
  (io/file (repo/file "formal" "fixtures" "caveat-comprehensions") name))

(defn corpus []
  (:cases (edn/read-string (slurp (io/resource "eacl/caveats/corpus.edn")))))

(defn- spicedb-type [type]
  (cond (keyword? type) (name type)
        (= :list (first type)) (str "list<" (spicedb-type (second type)) ">")
        :else (str "map<" (spicedb-type (nth type 2)) ">")))

(defn- spicedb-value
  "A context value as SpiceDB's JSON context expects it."
  [type value]
  (cond (= :timestamp type) (str (java.time.Instant/ofEpochMilli (second value)))
        (keyword? type) value
        (= :list (first type)) (mapv #(spicedb-value (second type) %) value)
        :else (into (sorted-map) (map (fn [[k v]] [k (spicedb-value (nth type 2) v)])) value)))

(defn- spicedb-context [parameters context]
  (into (sorted-map) (map (fn [[k v]] [k (spicedb-value (get parameters k) v)])) context))

(defn- spicedb-name [id] (str/replace (name id) "-" "_"))

(defn request
  "The SpiceDB request for one corpus case."
  [{:keys [id source parameters context bound reject]}]
  (cond-> {:id (name id)
           :op (if reject "write-schema" "check")
           :caveat (str "c_" (spicedb-name id))
           :parameters (str/join ", " (map (fn [[k t]] (str k " " (spicedb-type t))) (sort parameters)))
           :source source}
    (not reject) (assoc :resource (spicedb-name id)
                        :context (spicedb-context parameters context))
    (and (not reject) (seq bound)) (assoc :bound (spicedb-context parameters bound))))

(defn- json [value]
  (cond (string? value)
        (str "\"" (apply str (map (fn [c] (cond (= c \") "\\\"" (= c \\) "\\\\"
                                                (< (int c) 32) (format "\\u%04x" (int c)) :else c))
                                  value))
             "\"")
        (keyword? value) (json (name value))
        (map? value) (str "{" (str/join ", " (map (fn [[k v]] (str (json k) ": " (json v))) value)) "}")
        (sequential? value) (str "[" (str/join ", " (map json value)) "]")
        :else (str value)))

(defn write-requests!
  "Regenerates requests.json, capture.py's input, from the corpus."
  []
  (spit (fixture-file "requests.json")
        (str "[" (str/join ",\n " (map (comp json request) (corpus))) "]\n")))

(def ^:private engines {"JVM" (jvm/evaluator) "portable" (portable/evaluator)})

(defn- eacl-answer
  "The case's outcome in the fixture's terms, or its rejection."
  [engine {:keys [source parameters context bound]}]
  (let [entity (try (definition/entity "c" parameters source)
                    (catch clojure.lang.ExceptionInfo _ nil))]
    (if-not entity
      {:error :rejected}
      (let [{:keys [outcome missing-fields reason]} (caveat-evaluator/evaluate engine entity context bound)]
        (case outcome
          :true {:permissionship :has-permission}
          :false {:permissionship :no-permission}
          :conditional {:permissionship :conditional-permission :missing-fields (vec (sort missing-fields))}
          :error {:error reason})))))

(defn- spicedb-answer
  "SpiceDB's recorded answer in the same terms."
  [{:keys [permissionship missing-fields error written]}]
  (cond
    permissionship (cond-> {:permissionship permissionship}
                     missing-fields (assoc :missing-fields missing-fields))
    written {:written true}
    (= "ERROR_REASON_SCHEMA_PARSE_ERROR" (:reason error)) {:error :rejected}
    (str/starts-with? (str (:detail error)) "no such key") {:error :missing-map-key}
    :else {:error (:reason error)}))

(deftest recorded-requests-are-the-corpus
  (let [recorded (edn/read-string (slurp (fixture-file "spicedb-results.edn")))]
    (is (= (mapv request (corpus)) (mapv :request recorded))
        "a changed corpus needs a new capture (see the fixture README)")))

(deftest evaluators-answer-as-spicedb-except-where-listed
  (let [recorded (into {} (map (juxt (comp keyword :id :request) :spicedb))
                       (edn/read-string (slurp (fixture-file "spicedb-results.edn"))))]
    (doseq [{:keys [id] :as case} (corpus)
            [label engine] engines]
      (testing (str (name id) " / " label)
        (let [expected (spicedb-answer (get recorded id))
              actual (eacl-answer engine case)]
          (if (contains? divergences id)
            (is (not= expected actual) "a listed divergence must still differ")
            (is (= expected actual))))))))

(deftest every-comprehension-case-is-recorded
  (let [recorded (set (map (comp keyword :id :request)
                           (edn/read-string (slurp (fixture-file "spicedb-results.edn")))))]
    (is (= (set (map :id (corpus))) recorded))
    (is (<= 30 (count (filter #(re-find #"\.(exists|all|exists_one|map)\(" (:source %)) (corpus)))))))
