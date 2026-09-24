(ns eacl.authorization.point-reuse
  "Completed point decisions in the client's subproblem store, shared by the
   operator evaluators and the membership search. An unqualified decision is
   a Boolean. A qualified decision is stored with the interval its evidence
   certifies and keyed without its evaluation time, so a later request reuses
   it while that interval admits the later time. Lookups and publications
   run once per batch of candidates."
  (:require [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.temporal :as temporal]
            [eacl.subproblem-cache :as subproblem]))

(def ^:private boolean-options {:valid? boolean?})

(defn scope
  "The qualification component of a point key: nil without a qualification."
  [qualification]
  (when qualification (qualification/certified-denotation-scope qualification)))

(defn scoped-key
  "`key` for a request with certified `scope`: a qualified point key ends
   with its certified scope, an unqualified one is `key` itself."
  [key scope]
  (if scope (conj key scope) key))

(defn certified-key?
  "True for the point key of a qualified decision, whose value records the
   interval its evidence certifies."
  [key]
  (let [scope (when (vector? key) (peek key))]
    (and (vector? scope) (= :certified-point (first scope)))))

(defn stored-value-valid?
  "Validates a point decision entering the store from outside the request
   that computed it: under a certified key, a point answer whose canonical
   evidence agrees with its interval; under any other key, a Boolean."
  [key value]
  (if (certified-key? key)
    (temporal/point-answer-valid? value)
    (boolean? value)))

(defn reuse!
  "The decisions stored under `keys` that the request may reuse at its time,
   as a vector aligned with `keys`: each reused decision, observed on the
   request's qualification, or `absent`. Records one avoided backend
   operation per reused decision."
  [qualification keys absent]
  (if (empty? keys)
    []
    (let [time (:time qualification)
          reused (volatile! 0)
          decisions
          (mapv (fn [value]
                  (let [decision (when-not (identical? absent value)
                                   (if qualification (temporal/reusable-point value time) value))]
                    (if (some? decision)
                      (do (vswap! reused inc)
                          (if qualification
                            (qualification/observe-evidence! qualification decision)
                            decision))
                      absent)))
                (subproblem/lookup-denotations! keys absent))]
      (subproblem/record-avoided-backend-operation! subproblem/*store* @reused)
      decisions)))

(defn publish!
  "Publishes each `[key decision]` entry of a populating request: a qualified
   decision with the interval its evidence certifies, an unqualified one as
   its Boolean. Faults are never published."
  [qualification entries]
  (when subproblem/*populate?*
    (let [entries (remove #(evidence/fault? (second %)) entries)]
      (if qualification
        (let [time (:time qualification)]
          (subproblem/publish-denotations!
           temporal/point-publication-options
           (map (fn [[key decision]] [key (temporal/point-answer time decision)]) entries)))
        (subproblem/publish-denotations! boolean-options entries)))))
