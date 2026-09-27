(ns eacl.caveats.portable
  "Bounded EACL CEL profile 1 evaluator in portable Clojure, for ClojureScript
   and the JVM. Requiring this optional module registers the process default,
   unless one is already registered; qualified serving is a separate capability.

   Complete and incomplete contexts both use core's portable plan evaluator,
   after the admission, work preflight and bound-over-request merge that the
   JVM module performs. No library, host interop or second evaluator is used."
  (:require [eacl.cache.standard-lru :as lru]
            [eacl.caveats.definition :as definition]
            [eacl.caveats.evaluator :as caveat-evaluator]
            [eacl.caveats.partial :as partial]
            [eacl.caveats.values :as values]
            [eacl.secure-format :as secure]))

(def implementation
  "Semantic identity of this adapter. It pins no library artifact because
   evaluation runs only core's portable plan evaluator."
  {:adapter "eacl.caveats.portable/1" :evaluation "portable-plan/1"
   :host-values "canonical/1" :artifacts []})

(def capability
  {:capability-version 1 :profile values/profile-id
   :profile-fingerprint caveat-evaluator/profile-fingerprint
   :fingerprint (secure/canonical-digest "eacl.caveat/evaluator"
                                         [caveat-evaluator/profile-fingerprint implementation])})

(defn- canonical-value
  "Rebuilds one admitted value in the canonical form of its declared type, as
   the JVM module builds cel-parser bindings. A JVM Boolean object becomes its
   primitive value and a map becomes a plain map, so a caller's sorted-map
   comparator cannot change which string keys match."
  [type value]
  (case type
    :bool (boolean value)
    :int (long value)
    :string value
    :timestamp [:timestamp (long (second value))]
    (if (= :list (first type))
      (mapv #(canonical-value (second type) %) value)
      (into {} (map (fn [[k v]] [k (canonical-value (nth type 2) v)])) value))))

(defn- canonical-context [types context]
  (reduce-kv (fn [m name value] (assoc m name (canonical-value (get types name) value)))
             {} context))

(defn- plan-cache
  [{:keys [max-entries] :or {max-entries (:program-cache-entries values/limits)}}]
  (when-not (and (integer? max-entries) (<= 1 max-entries (:program-cache-entries values/limits)))
    (values/error! :resource-limit {:limit :program-cache}))
  {:store (lru/store max-entries)
   :counters (atom {:hits 0 :builds 0 :failed-builds 0})})

(defn- decoded-plan!
  "Returns the compiled plan for validated definition content. Failed decodes
   are rethrown and never retained. Concurrent JVM misses may decode twice."
  [{:keys [store counters]} content entity]
  (let [key [::portable-plan (:fingerprint capability) content]
        cached (lru/lookup! store key)]
    (if (:found? cached)
      (do (swap! counters update :hits inc) (:value cached))
      (let [_ (swap! counters update :builds inc)
            compiled (try (definition/decode-entity entity)
                          (catch #?(:clj Throwable :cljs :default) error
                            (swap! counters update :failed-builds inc)
                            (throw error)))]
        (lru/put-if-absent! store key compiled)
        compiled))))

(defn- evaluate-definition [plans entity request bound]
  (try
    (let [;; Validate the complete current entity before using content identity.
          content (definition/content-identity entity)
          {:keys [parameters plan]} (decoded-plan! plans content entity)
          prepared (partial/prepare-evaluation parameters plan
                                               (if (nil? request) {} request)
                                               (if (nil? bound) {} bound))]
      ;; Admission and work are settled on the supplied values; only their
      ;; host representation changes here.
      (partial/evaluate-prepared
       (update prepared :context #(canonical-context (:types prepared) %))))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
      {:outcome :error :reason (if (= :eacl.caveat/invalid (:type (ex-data e)))
                                 (:reason (ex-data e)) :evaluator-exception)})
    #?(:clj (catch Exception _ {:outcome :error :reason :evaluator-exception}))))

(defrecord PortableEvaluator [plans]
  caveat-evaluator/Evaluator
  (descriptor [_] capability)
  (-evaluate [_ entity request bound]
    (evaluate-definition plans entity request bound)))

(defn evaluator
  "Creates an independent evaluator with its own bounded plan cache, for a
   client's :caveat-evaluator option. Optional :max-entries (1 to 256) lowers
   the profile's cache capacity."
  ([] (evaluator {}))
  ([options] (->PortableEvaluator (plan-cache options))))

(defn cache-stats [evaluator]
  (let [{:keys [store counters]} (:plans evaluator)]
    (assoc @counters :entries (lru/entry-count store))))

(defonce ^:private process-default (evaluator))

;; An evaluator registered earlier keeps priority: an application's explicit
;; choice, or the certified JVM module, whatever the namespace load order.
(when (nil? (caveat-evaluator/default-evaluator))
  (caveat-evaluator/register-default! process-default))
