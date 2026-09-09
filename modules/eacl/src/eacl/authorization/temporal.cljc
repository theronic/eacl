(ns eacl.authorization.temporal
  "Completed point-answer intervals, independent of retention and wall timers."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.caveats.values :as values]))

(def point-format :eacl.authorization/temporal-point-v1)
(def collection-format :eacl.authorization/temporal-collection-v2)

(defn point-answer [time proof]
  (evidence/throw-if-fault! proof)
  {:format point-format :start-ms time
   :valid-until-ms (evidence/valid-until proof)
   :complete? (evidence/complete? proof)
   :kind (evidence/permissionship proof)
   :value (evidence/encode proof)})

(defn point-answer-valid? [answer]
  (try
    (and (map? answer)
         (= #{:format :start-ms :valid-until-ms :complete? :kind :value} (set (keys answer)))
         (= point-format (:format answer))
         (values/valid-time? (:start-ms answer))
         (evidence/before? (:start-ms answer) (:valid-until-ms answer))
         (let [proof (evidence/decode (:value answer))]
           (and (not (evidence/fault? proof))
                (= (:kind answer) (evidence/permissionship proof))
                (= (:valid-until-ms answer) (evidence/valid-until proof))
                (= (:complete? answer) (evidence/complete? proof)))))
    (catch #?(:clj Exception :cljs :default) _ false)))

(defn reusable?
  "Checks a validated resident entry at the captured time. Missing completeness
   permits only its original time and exact basis, never a temporal promotion."
  [answer time exact-basis?]
  (and (values/valid-time? time)
       (<= (:start-ms answer) time)
       (evidence/before? time (:valid-until-ms answer))
       (or (:complete? answer) (and exact-basis? (= time (:start-ms answer))))))

(defn answer-interval
  "Extracts the interval of an admitted point, collection, or rendered answer."
  [answer]
  (if (= point-format (:format answer)) answer (:qualification-certificate answer)))

(defn answer-reusable? [answer time exact-basis?]
  (when-let [certificate (answer-interval answer)]
    (reusable? certificate time exact-basis?)))

(defn supersedes?
  "A later computation can replace a resident interval it could not reuse.
   Older pinned computations never evict a newer answer under the same key."
  [prior next]
  (let [before (answer-interval prior) after (answer-interval next)]
    (and (= (:format prior) (:format next)) before after
         (< (:start-ms before) (:start-ms after))
         (not (reusable? before (:start-ms after) true)))))

(defn interval [time end complete?]
  {:start-ms time :valid-until-ms end :complete? complete?})

(defn interval-valid? [value]
  (and (map? value)
       (= #{:start-ms :valid-until-ms :complete?} (set (keys value)))
       (values/valid-time? (:start-ms value))
       (or (nil? (:valid-until-ms value)) (values/valid-time? (:valid-until-ms value)))
       (boolean? (:complete? value))
       (evidence/before? (:start-ms value) (:valid-until-ms value))))

(defn intersect-intervals
  "Combines certificates for work used together. A missing certificate cannot
   certify retained work; callers keep that path at its original time."
  [left right]
  (when (and (interval-valid? left) (interval-valid? right))
    (let [result (interval (max (:start-ms left) (:start-ms right))
                           (evidence/meet (:valid-until-ms left) (:valid-until-ms right))
                           (and (:complete? left) (:complete? right)))]
      (when (and (interval-valid? result)
                 (reusable? left (:start-ms result) true)
                 (reusable? right (:start-ms result) true)) result))))

(defn cursor-certificate
  "A resumed prefix starts at its new evaluation time while retaining the
   series' original time and every prior exclusive deadline. Missing retained
   evidence explicitly prevents cross-time reuse; no graph walk repairs it."
  [mode time prior current]
  (assoc (interval time (evidence/meet (:valid-until-ms prior) (:valid-until-ms current))
                   (and (:complete? current) (or (nil? prior) (:complete? prior))))
         :mode mode :original-time-ms (get prior :original-time-ms time)))

(defn cursor-certificate-valid? [value]
  (and (map? value)
       (= #{:mode :original-time-ms :start-ms :valid-until-ms :complete?} (set (keys value)))
       (contains? #{:pinned :live} (:mode value))
       (values/valid-time? (:original-time-ms value))
       (interval-valid? (dissoc value :mode :original-time-ms))
       (<= (:original-time-ms value) (:start-ms value))
       (or (= :live (:mode value)) (= (:original-time-ms value) (:start-ms value)))))

(defn cursor-time-valid? [certificate time]
  (if (= :pinned (:mode certificate))
    (= time (:original-time-ms certificate))
    (reusable? certificate time true)))
