(ns eacl.caveats.evaluator
  "Portable evaluator capability. Registration alone never enables serving."
  (:require [eacl.caveats.values :as values]
            [eacl.secure-format :as secure]))

(def profile-fingerprint
  (secure/canonical-digest "eacl.caveat/profile"
                           [values/profile-id values/format-version values/limits
                            :bound-wins :four-outcomes :literal-bindings-v1]))

(defprotocol Evaluator
  (descriptor [this] "Portable profile and implementation identity.")
  (-evaluate [this definition request bound] "Evaluate an admitted named definition."))

(defn require-matching!
  "Admission guard for a future serving client or a supplied implementation.
   An implementation must be independently certified for the exact profile."
  [evaluator expected-profile]
  (when-not (and (satisfies? Evaluator evaluator)
                 (= expected-profile (:profile-fingerprint (descriptor evaluator)))
                 (= values/profile-id (:profile (descriptor evaluator)))
                 (= 1 (:capability-version (descriptor evaluator)))
                 (string? (:fingerprint (descriptor evaluator)))
                 (seq (:fingerprint (descriptor evaluator))))
    (throw (ex-info "A matching Caveat evaluator is required."
                    {:type :eacl.caveat/evaluator-unavailable :eacl/error :eacl.caveat/evaluator-unavailable
                     :profile-fingerprint expected-profile})))
  evaluator)

(defn evaluate [evaluator definition request bound]
  (-evaluate (require-matching! evaluator profile-fingerprint) definition request bound))

(defonce ^:private registered-default (atom nil))

(defn register-default!
  "Explicitly installs a certified evaluator in this process. Core never loads
   a platform implementation or discovers dependencies through reflection."
  [evaluator]
  (reset! registered-default (require-matching! evaluator profile-fingerprint)))

(defn default-evaluator [] @registered-default)
