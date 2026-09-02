(ns eacl.relationships.endpoint-pair
  "Pure, backend-neutral representation of EACL's two endpoint relationship
  halves. Database adapters own index access and transaction semantics; this
  namespace only owns the value shape and its symmetry."
  (:require [eacl.relationships.storage :as storage]))

(def value-arity 4)

(defn forward-value
  [subject-type relation-eid resource-type resource-eid]
  [subject-type relation-eid resource-type resource-eid])

(defn reverse-value
  [resource-type relation-eid subject-type subject-eid]
  [resource-type relation-eid subject-type subject-eid])

(defn retractions
  "Both physical retractions for one logical relationship."
  [subject-type subject-eid relation-eid resource-type resource-eid]
  [[:db/retract subject-eid storage/forward-attribute
    (forward-value subject-type relation-eid resource-type resource-eid)]
   [:db/retract resource-eid storage/reverse-attribute
    (reverse-value resource-type relation-eid subject-type subject-eid)]])

(defn endpoint-value?
  "True for a stored endpoint value with the expected heterogeneous shape.
  Eids are non-negative integers in committed Datomic, Datahike, and
  DataScript database values."
  [value]
  (and (vector? value)
       (= value-arity (count value))
       (keyword? (nth value 0))
       (nat-int? (nth value 1))
       (keyword? (nth value 2))
       (nat-int? (nth value 3))))

(defn valid-prefix?
  "True for the complete typed three-component endpoint index prefix."
  [prefix]
  (and (vector? prefix)
       (= 3 (count prefix))
       (keyword? (nth prefix 0))
       (nat-int? (nth prefix 1))
       (keyword? (nth prefix 2))))

(defn value-prefix?
  "Whether a valid endpoint value begins with `prefix`. Oversized prefixes and
  malformed stored values never match. Compares component-wise, so a scan
  pays no allocation per datom."
  [value prefix]
  (let [n (count prefix)]
    (and (endpoint-value? value)
         (<= n value-arity)
         (loop [i 0]
           (or (== i n)
               (and (= (nth prefix i) (nth value i))
                    (recur (inc i))))))))

(defn decode-forward
  [subject-eid value]
  (when (endpoint-value? value)
    (let [[subject-type relation-eid resource-type resource-eid] value]
      {:subject-type subject-type
       :subject-eid subject-eid
       :relation-eid relation-eid
       :resource-type resource-type
       :resource-eid resource-eid})))

(defn decode-reverse
  [resource-eid value]
  (when (endpoint-value? value)
    (let [[resource-type relation-eid subject-type subject-eid] value]
      {:subject-type subject-type
       :subject-eid subject-eid
       :relation-eid relation-eid
       :resource-type resource-type
       :resource-eid resource-eid})))

(defn peer-half
  "Returns the exact peer endpoint and value for one decoded physical half."
  [direction endpoint-eid value]
  (case direction
    :forward
    (when-let [{:keys [subject-type subject-eid relation-eid
                       resource-type resource-eid] :as decoded}
               (decode-forward endpoint-eid value)]
      (assoc decoded
             :direction :reverse
             :endpoint-eid resource-eid
             :value (reverse-value resource-type relation-eid
                                   subject-type subject-eid)))

    :reverse
    (when-let [{:keys [subject-type subject-eid relation-eid
                       resource-type resource-eid] :as decoded}
               (decode-reverse endpoint-eid value)]
      (assoc decoded
             :direction :forward
             :endpoint-eid subject-eid
             :value (forward-value subject-type relation-eid
                                   resource-type resource-eid)))

    nil))

(defn dangling-report
  "Summarizes a dangling-half stream without retaining its scan head."
  [halves {:keys [sample-size] :or {sample-size 20}}]
  (when-not (and (integer? sample-size) (not (neg? sample-size)))
    (throw
     (ex-info
      ":sample-size must be a non-negative integer."
      {:type :eacl.integrity/invalid-options
       :eacl/error :eacl.integrity/invalid-options
       :sample-size sample-size})))
  (let [{:keys [count by-half sample]}
        (reduce
         (fn [{:keys [count] :as report} half]
           (cond-> (-> report
                       (assoc :count (inc count))
                       (update-in [:by-half (:half half)] (fnil inc 0)))
             (< count sample-size) (update :sample conj half)))
         {:count 0 :by-half {:forward 0 :reverse 0} :sample []}
         halves)]
    {:valid? (zero? count)
     :dangling-count count
     :by-half by-half
     :sample sample}))
