(ns eacl.performance.evidence
  "Source-bound performance evidence validation and deterministic analysis."
  (:require [clojure.set :as set])
  (:import [java.security MessageDigest]))

(def evidence-format :eacl.performance/evidence-v1)
(def acceptance-format :eacl.performance/acceptance-v1)
(def metric-capability-format :eacl.performance/metric-capability-v1)
(def release-acceptance-format :eacl.performance/release-acceptance-v1)

(def required-identity-fields
  #{:source :dependencies :runtime :environment :backend :fixture :basis
    :command :cache-regime :operation-boundary :estimator})

(def ^:private volatile-keys
  #{:captured-at :timestamp :pid :process-id :path :cwd :worktree-path})

(defn canonical-form
  [value]
  (cond
    (map? value)
    (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
          (map (fn [[key child]] [key (canonical-form child)]))
          value)

    (set? value)
    [:eacl.performance/set
     (mapv canonical-form (sort-by pr-str value))]

    (sequential? value)
    (mapv canonical-form value)

    :else value))

(defn sha256
  [value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (pr-str (canonical-form value)) "UTF-8")]
    (.update digest bytes)
    (apply str (map #(format "%02x" (bit-and 0xff %)) (.digest digest)))))

(defn raw-digest
  [raw]
  (sha256 raw))

(defn- invalid!
  [reason data]
  (throw
   (ex-info
    "Invalid EACL performance evidence."
    (merge {:type :eacl.performance/invalid-evidence
            :eacl/error :eacl.performance/invalid-evidence
            :reason reason}
           data))))

(defn- finite-number?
  [value]
  (and (number? value)
       (or (not (instance? Double value))
           (Double/isFinite ^double value))
       (or (not (instance? Float value))
           (Float/isFinite ^float value))))

(defn- require-samples!
  [metric samples]
  (when-not (and (vector? samples)
                 (seq samples)
                 (every? finite-number? samples))
    (invalid! :invalid-samples {:metric metric :samples samples})))

(defn- validate-supported-metric!
  [metric value]
  (when-not (and (map? value)
                 (keyword? (:unit value))
                 (not= :unsupported (:status value)))
    (invalid! :invalid-supported-metric {:metric metric :value value}))
  (require-samples! metric (:samples value)))

(defn- validate-optional-metric!
  [metric value]
  (if (= :unsupported (:status value))
    (when-not (and (keyword? (:reason value))
                   (not (contains? value :samples)))
      (invalid! :invalid-unsupported-metric {:metric metric :value value}))
    (validate-supported-metric! metric value)))

(defn validate-evidence!
  [evidence]
  (when-not (= evidence-format (:format evidence))
    (invalid! :unsupported-format {:format (:format evidence)}))
  (when-not (contains? #{:golden :baseline :candidate :confirmation}
                       (:phase evidence))
    (invalid! :invalid-phase {:phase (:phase evidence)}))
  (let [identity (:identity evidence)
        fields (set (keys identity))]
    (when-not (and (map? identity)
                   (every? #(some? (get identity %))
                           required-identity-fields)
                   (empty? (set/difference
                            required-identity-fields fields)))
      (invalid! :incomplete-identity
                {:missing (set/difference
                           required-identity-fields fields)})))
  (let [{:keys [elapsed mandatory optional]} (:metrics evidence)]
    (validate-supported-metric! :elapsed elapsed)
    (when-not (map? mandatory)
      (invalid! :invalid-mandatory-metrics {:mandatory mandatory}))
    (doseq [[metric value] mandatory]
      (validate-supported-metric! metric value))
    (when-not (map? optional)
      (invalid! :invalid-optional-metrics {:optional optional}))
    (doseq [[metric value] optional]
      (validate-optional-metric! metric value)))
  (when-not (and (vector? (:raw evidence)) (seq (:raw evidence)))
    (invalid! :empty-raw-evidence {}))
  (when-not (= (:raw-digest evidence) (raw-digest (:raw evidence)))
    (invalid! :raw-digest-mismatch
              {:expected (raw-digest (:raw evidence))
               :actual (:raw-digest evidence)}))
  evidence)

(defn validate-acceptance!
  [acceptance]
  (when-not (= acceptance-format (:format acceptance))
    (invalid! :unsupported-acceptance-format
              {:format (:format acceptance)}))
  (when-not (true? (:frozen? acceptance))
    (invalid! :acceptance-not-frozen {}))
  (when-not (= evidence-format (:evidence-format acceptance))
    (invalid! :acceptance-evidence-format-mismatch {}))
  (when-not (= required-identity-fields
               (:required-identity-fields acceptance))
    (invalid! :acceptance-identity-fields-mismatch {}))
  (when-not (and (set? (:required-mandatory-metrics acceptance))
                 (seq (:required-mandatory-metrics acceptance)))
    (invalid! :empty-required-metrics {}))
  (when-not (and (vector? (:canonical-decision-fields acceptance))
                 (seq (:canonical-decision-fields acceptance)))
    (invalid! :empty-canonical-decision-fields {}))
  (when-not (map? (:identity-constraints acceptance))
    (invalid! :invalid-identity-constraints {}))
  acceptance)

(defn- deep-match?
  [expected actual]
  (if (map? expected)
    (and (map? actual)
         (every? (fn [[key value]]
                   (and (contains? actual key)
                        (deep-match? value (get actual key))))
                 expected))
    (= expected actual)))

(defn validate-metric-capability!
  [capability]
  (when-not (= metric-capability-format (:format capability))
    (invalid! :unsupported-metric-capability-format {}))
  (when-not (and (keyword? (:metric capability))
                 (map? (:instrument capability))
                 (keyword? (get-in capability [:instrument :id]))
                 (keyword? (get-in capability [:instrument :runtime]))
                 (string? (get-in capability [:instrument :version]))
                 (contains? #{:supported :unsupported} (:status capability)))
    (invalid! :invalid-metric-capability {:capability capability}))
  (if (= :supported (:status capability))
    (when-not (and (keyword? (:unit capability))
                   (true? (get-in capability [:calibration :monotonic?]))
                   (finite-number?
                    (get-in capability [:calibration :overhead-per-read]))
                   (not (neg?
                         (get-in capability
                                 [:calibration :overhead-per-read])))
                   (keyword?
                    (get-in capability [:calibration :overhead-unit]))
                   (map? (get-in capability [:calibration :control]))
                   (= :after-full-public-result-realization
                      (get-in capability
                              [:attribution :response-boundary]))
                   (= :separate-lane
                      (get-in capability
                              [:attribution :deferred-work])))
      (invalid! :invalid-metric-calibration {:capability capability}))
    (when-not (keyword? (:reason capability))
      (invalid! :unsupported-metric-without-reason
                {:capability capability})))
  capability)

(defn validate-release-acceptance!
  [acceptance]
  (when-not (= release-acceptance-format (:format acceptance))
    (invalid! :unsupported-release-acceptance-format {}))
  (when-not (and (true? (:frozen? acceptance))
                 (true? (:frozen-after-baseline-only-pilot? acceptance))
                 (false? (:candidate-data-observed-before-freeze? acceptance))
                 (keyword? (get-in acceptance [:release-win :id]))
                 (keyword? (get-in acceptance [:release-win :public-api]))
                 (seq (get-in acceptance [:release-win
                                          :retained-mechanisms]))
                 (pos-int? (get-in acceptance [:sampling :paired-blocks]))
                 (pos-int? (get-in acceptance [:confidence :resamples]))
                 (< 0.5 (get-in acceptance [:confidence :level]) 1.0)
                 (pos? (get-in acceptance
                               [:release-win :effect
                                :minimum-practical-reduction]))
                 (vector? (:safety-lanes acceptance))
                 (seq (:safety-lanes acceptance))
                 (= (count (:safety-lanes acceptance))
                    (count (distinct (map :id (:safety-lanes acceptance))))))
    (invalid! :invalid-release-acceptance {}))
  (let [expected (sha256 (dissoc acceptance :record-digest))]
    (when-not (= expected (:record-digest acceptance))
      (invalid! :release-acceptance-digest-mismatch
                {:expected expected :actual (:record-digest acceptance)})))
  acceptance)

(defn- strip-volatile
  [value]
  (cond
    (map? value)
    (into {}
          (keep (fn [[key child]]
                  (when-not (contains? volatile-keys key)
                    [key (strip-volatile child)])))
          value)

    (sequential? value) (mapv strip-volatile value)
    (set? value) (set (map strip-volatile value))
    :else value))

(defn- median
  [samples]
  (let [ordered (vec (sort samples))
        size (count ordered)
        middle (quot size 2)]
    (if (odd? size)
      (nth ordered middle)
      (/ (+ (nth ordered (dec middle)) (nth ordered middle)) 2.0))))

(defn- metric-summary
  [value]
  (if (= :unsupported (:status value))
    (select-keys value [:status :reason])
    {:unit (:unit value)
     :samples (count (:samples value))
     :minimum (apply min (:samples value))
     :median (median (:samples value))
     :maximum (apply max (:samples value))}))

(defn analyze
  [acceptance evidence]
  (validate-acceptance! acceptance)
  (validate-evidence! evidence)
  (when-not (deep-match? (:identity-constraints acceptance)
                         (:identity evidence))
    (invalid! :identity-mismatch
              {:expected (:identity-constraints acceptance)}))
  (let [required (:required-mandatory-metrics acceptance)
        actual (set (keys (get-in evidence [:metrics :mandatory])))]
    (when-not (set/subset? required actual)
      (invalid! :missing-required-mandatory-metrics
                {:missing (set/difference required actual)})))
  (let [report
        {:format :eacl.performance/report-v1
         :phase (:phase evidence)
         :identity (strip-volatile (:identity evidence))
         :raw-digest (:raw-digest evidence)
         :elapsed (metric-summary (get-in evidence [:metrics :elapsed]))
         :mandatory
         (into (sorted-map)
               (map (fn [[metric value]]
                      [metric (metric-summary value)]))
               (get-in evidence [:metrics :mandatory]))
         :optional
         (into (sorted-map)
               (map (fn [[metric value]]
                      [metric (metric-summary value)]))
               (get-in evidence [:metrics :optional]))}]
    (assoc report :report-digest (sha256 report))))

(defn paired-confidence
  "Deterministic paired bootstrap interval for a direction-normalized effect.

  `effect` receives one baseline and candidate sample. The caller freezes the
  seed, resample count, and confidence level before candidate measurement."
  [baseline candidate {:keys [effect confidence resamples seed]}]
  (when-not (and (vector? baseline)
                 (= (count baseline) (count candidate))
                 (pos? (count baseline))
                 (every? #(and (finite-number? %) (pos? %)) baseline)
                 (every? #(and (finite-number? %) (pos? %)) candidate)
                 (fn? effect)
                 (finite-number? confidence)
                 (< 0.5 confidence 1.0)
                 (pos-int? resamples)
                 (integer? seed))
    (invalid! :invalid-paired-comparison {}))
  (let [effects (mapv effect baseline candidate)
        size (count effects)
        random (java.util.Random. (long seed))
        estimates
        (vec
         (repeatedly
          resamples
          #(median
            (vec
             (repeatedly size
                         (fn []
                           (nth effects (.nextInt random size))))))))
        ordered (vec (sort estimates))
        lower-index
        (long (Math/floor (* (- 1.0 confidence) (dec resamples))))
        upper-index
        (long (Math/ceil (* confidence (dec resamples))))]
    {:pairs size
     :confidence confidence
     :resamples resamples
     :seed seed
     :estimate (median effects)
     :lower (nth ordered lower-index)
     :upper (nth ordered upper-index)}))

(defn latency-reduction
  [baseline candidate]
  (- 1.0 (/ (double candidate) (double baseline))))

(defn degradation
  [baseline candidate]
  (- (/ (double candidate) (double baseline)) 1.0))

(defn validate-lane-support!
  [baseline candidate]
  (when (and (= :supported (:status baseline))
             (= :unsupported (:status candidate)))
    (invalid! :baseline-supported-lane-labeled-unsupported
              {:baseline baseline :candidate candidate}))
  candidate)
