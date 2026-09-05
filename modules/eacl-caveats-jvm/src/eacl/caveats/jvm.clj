(ns eacl.caveats.jvm
  "Certified, bounded cel-parser adapter. Requiring this optional module
   registers the process default; qualified serving is a separate capability."
  (:require [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as evaluator]
            [eacl.caveats.jvm.program-cache :as cache]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.values :as values]
            [eacl.secure-format :as secure]
            [exoscale.cel.expr :as expr]
            [exoscale.cel.parser :as cel]))

(def implementation
  {:adapter "eacl.caveats.jvm/3" :literal-lowering "bindings/1" :error-propagation "overloads-and-not/1"
   :artifacts [["com.exoscale/cel-parser" "0.1.8" "2554b657e335524115c29f45f9f2b45d1a868a495ddb24d5ac8758acf2aa982d"]
               ["com.exoscale/antlr-cel" "0.1.1" "d8f3012b5f24d89d87dea9e1de826dd578d7b3a47e2b9038256af6e821929d99"]
               ["org.antlr/antlr4-runtime" "4.9.2" "120053628dd598d43cb7ac6b9ecc72529dfa5a5fd3292d37cf638a81cc0075f6"]]})

(def capability
  {:capability-version 1 :profile values/profile-id
   :profile-fingerprint evaluator/profile-fingerprint
   :fingerprint (secure/canonical-digest "eacl.caveat/evaluator"
                                         [evaluator/profile-fingerprint implementation])})

(defn- native-value [type value]
  (case type
    :int (long value)
    :timestamp (expr/->TimestampType (java.sql.Timestamp. (long (second value))))
    (:bool :string) value
    (if (= :list (first type))
      (mapv #(native-value (second type) %) value)
      (into {} (map (fn [[k v]] [k (native-value (nth type 2) v)])) value))))

(def ^:private operators
  {:and "&&" :or "||" :eq "==" :ne "!=" :lt "<" :le "<=" :gt ">" :ge ">=" :in "in"})
(def ^:private string-methods {:contains "contains" :starts-with "startsWith" :ends-with "endsWith"})

(def ^:private profile-overloads
  ;; The candidate otherwise replaces operand errors with overload failures.
  ;; Its hard-coded unary visitor does so too; lower that one operation to a
  ;; reserved internal call, preserving one evaluation and the original fault.
  (let [propagate {:guard (fn [args] (some expr/error? args))
                   :handler (fn [& args] (first (filter expr/error? args)))}]
    (update-vals (assoc expr/overloads :__eacl_not [{:on [expr/bool?] :handler expr/bool-not}])
                 #(cons propagate %))))

(defn- build-program [{:keys [parameters plan]}]
  (let [names (into {} (map-indexed (fn [i [name _]] [name (str "__eacl_p" i)]) parameters))
        literals (volatile! {})
        render
        (fn render [[op a b]]
          (case op
            :param (get names a)
            :literal (let [name (str "__eacl_l" (count @literals))]
                       ;; Never ask the dependency to unescape source literals.
                       (vswap! literals assoc (keyword name) [a b]) name)
            :not (str "__eacl_not(" (render a) ")")
            :index (str "(" (render a) ")[" (render b) "]")
            (if-let [method (get string-methods op)]
              (str "(" (render a) ")." method "(" (render b) ")")
              (str "(" (render a) " " (get operators op) " " (render b) ")"))))
        source (render plan)]
    {:program (cel/make-program source) :names names :literals @literals}))

(defn- classify [result]
  (cond
    (expr/error? result)
    {:outcome :error :reason (case (expr/val result)
                               "no such key" :missing-map-key
                               "no such overload" :unsupported-overload
                               :evaluator-error)}
    (expr/bool? result) {:outcome (if (true? (expr/val result)) :true :false)}
    :else {:outcome :error :reason :non-boolean-result}))

(defn- complete-evaluation [program-cache compiled {:keys [context types]}]
  (let [identity [(:fingerprint capability) (:name compiled) (:parameters compiled) (:source compiled)]
        {:keys [program names literals]} (cache/get-or-build! program-cache identity #(build-program compiled))
        bindings (reduce-kv (fn [m name value]
                              (assoc m (keyword (get names name)) (native-value (get types name) value)))
                            (into {} (map (fn [[k [t v]]] [k (native-value t v)])) literals) context)]
    (classify (cel/eval-for program bindings {:translate-result? false :throw-on-error? false
                                              :overloads profile-overloads}))))

(defn- evaluate-definition [program-cache entity request bound]
  (try
    (let [content (definition/content-identity entity)
          ;; Validate the complete current entity before using content identity.
          ;; Portable plans and native programs share one bounded capacity;
          ;; partial requests build only the portable plan.
          {:keys [parameters plan] :as compiled}
          (cache/get-or-build! program-cache [::portable-plan (:fingerprint capability) content]
                               #(definition/decode-entity entity))
          prepared (partial/prepare-evaluation parameters plan
                                               (if (nil? request) {} request)
                                               (if (nil? bound) {} bound))]
      (if (every? #(contains? (:context prepared) (first %)) parameters)
        (complete-evaluation program-cache compiled prepared)
        (partial/evaluate-prepared prepared)))
    (catch clojure.lang.ExceptionInfo e
      {:outcome :error :reason (if (= :eacl.caveat/invalid (:type (ex-data e)))
                                 (:reason (ex-data e)) :evaluator-exception)})
    (catch InterruptedException _
      (.interrupt (Thread/currentThread))
      {:outcome :error :reason :evaluation-interrupted})
    (catch Exception _ {:outcome :error :reason :evaluator-exception})))

(defrecord JvmEvaluator [program-cache]
  evaluator/Evaluator
  (descriptor [_] capability)
  (-evaluate [_ entity request bound]
    (evaluate-definition program-cache entity request bound)))

(defn evaluator
  "Creates an independent bounded evaluator, useful for client-owned lifetimes."
  ([] (evaluator {}))
  ([cache-options] (->JvmEvaluator (cache/store cache-options))))

(defn cache-stats [evaluator] (cache/stats (:program-cache evaluator)))

(defonce ^:private process-default (evaluator))
(evaluator/register-default! process-default)
