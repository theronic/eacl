(ns eacl.formal.differential-runner
  "One reproducible comparison boundary for formal, generated, legacy,
  adapter, cache-mode, public-client, and cross-runtime observations."
  (:require [eacl.secure-format :as secure]))

(defn- throwable-observation
  [error]
  {:status :thrown
   :error
   {:type (:type (ex-data error))
    :eacl/error (:eacl/error (ex-data error))
    :reason (:reason (ex-data error))
    :class #?(:clj (str (class error))
              :cljs (str (type error)))} })

(defn observe
  [implementation normalize]
  (try
    {:status :returned
     :value (normalize (implementation))}
    (catch #?(:clj Throwable :cljs :default) error
      (throwable-observation error))))

(defn run-case
  "Runs ordered `[label thunk]` implementations and returns a portable report.

  `seed` and `case-id` are always retained so a mismatch can be replayed.
  Implementations agree only when their normalized return/typed-error
  observations are exactly equal."
  [{:keys [seed case-id implementations normalize metadata]
    :or {normalize identity}}]
  (when-not (and (sequential? implementations)
                 (<= 2 (count implementations))
                 (every?
                  (fn [[label implementation]]
                    (and (keyword? label)
                         (fn? implementation)))
                  implementations))
    (throw
     (ex-info
      "Differential cases require at least two labeled implementations."
      {:type :eacl.formal/invalid-differential-case
       :seed seed
       :case-id case-id})))
  (let [observations
        (mapv
         (fn [[label implementation]]
           [label (observe implementation normalize)])
         implementations)
        values (mapv second observations)
        agreed? (apply = values)
        report
        {:schema-version 1
         :seed seed
         :case-id case-id
         :status (if agreed? :passed :failed)
         :implementations (mapv first observations)
         :observations (into {} observations)
         :metadata metadata
         :case-digest
         (secure/canonical-digest
          "eacl/formal/differential-case/v1"
          [seed case-id metadata])}]
    report))

(defn assert-equivalent!
  [options]
  (let [report (run-case options)]
    (when (= :failed (:status report))
      (throw
       (ex-info
        "Differential implementations disagree."
        {:type :eacl.formal/differential-mismatch
         :seed (:seed report)
         :case-id (:case-id report)
         :report report})))
    report))

(defn compare-values!
  [{:keys [values] :as options}]
  (assert-equivalent!
   (-> options
       (dissoc :values)
       (assoc
        :implementations
        (mapv (fn [[label value]]
                [label (constantly value)])
              values)))))
