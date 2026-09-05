(ns eacl.security.mutation-test
  "Focused killed controls: unsafe alternatives must fail the public contracts."
  (:require [clojure.test :as t :refer [deftest is]]
            [eacl.core :as eacl]
            [eacl.secure-format :as secure]
            [eacl.security.protocols :as protocols]
            [eacl.security.keyring-test :as state-test]
            [eacl.security.format-test :as format-test]))

(def validation-cases
  [[:key-count {:keys {} :active-kid :a}]
   [:active-key-unavailable {:keys {:a (state-test/material 1)} :active-kid :missing}]
   [:invalid-options {:keys {:a (state-test/material 1)} :active-kid :a :extra true}]
   [:invalid-key-id {:keys {"" (state-test/material 1)} :active-kid ""}]
   [:invalid-key-id {:keys {(apply str (repeat 1025 "a")) (state-test/material 1)} :active-kid :a}]
   [:invalid-key {:keys {:a [1 2]} :active-kid :a}]
   [:invalid-key {:keys {:a (repeat 4097 1)} :active-kid :a}]
   [:invalid-key {:keys {:a (repeat 32 256)} :active-kid :a}]
   [:invalid-limit {:keys {:a (state-test/material 1)} :active-kid :a :max-keys 0}]
   [:key-count {:keys {:a (state-test/material 1) :b (state-test/material 2)} :active-kid :a :max-keys 1}]])

(deftest construction-errors-identify-the-rejected-boundary
  (doseq [[reason input] validation-cases]
    (is (= {:type :eacl.keyring/invalid :reason reason}
           (state-test/outcome #(eacl/security-keyring input))))))

(defn detected? [replacements test-vars]
  (let [events (atom [])]
    (binding [t/*report-counters* (ref t/*initial-report-counters*)]
      (with-redefs-fn
        (assoc replacements #'t/report #(swap! events conj %))
        #(doseq [test-var test-vars] (t/test-var test-var))))
    (boolean (some #(#{:fail :error} (:type %)) @events))))

(deftest each-construction-validation-has-a-killed-control
  (let [failure-var (ns-resolve 'eacl.security.keyring 'failure!)
        original @failure-var]
    (doseq [reason (distinct (map first validation-cases))]
      (is (detected? {failure-var (fn [r] (when-not (= reason r) (original r)))}
                     [#'construction-errors-identify-the-rejected-boundary])
          (str "omitted " reason " must be detected")))))

(deftest state-and-secret-controls-are-killed
  (let [failure-var (ns-resolve 'eacl.security.keyring 'failure!)
        original @failure-var
        conflict-var (ns-resolve 'eacl.security.keyring 'conflict!)]
    (is (detected? {failure-var (fn [reason]
                                  (when-not (#{:active-key-retirement :active-key-unavailable} reason)
                                    (original reason)))}
                   [#'state-test/separate-install-activation-retirement-and-no-id-revival]))
    (is (detected? {conflict-var (fn [_] nil)}
                   [#'state-test/complete-replacement-is-atomic-guarded-and-bounded]))
    (is (detected? {failure-var (fn [reason]
                                  (throw (ex-info "keyring-canary-material-never-in-diagnostics-0123456789"
                                                  {:type :eacl.keyring/invalid :reason reason})))}
                   [#'state-test/diagnostics-do-not-retain-input-material-or-conversion-causes]))))

(deftest unauthenticated-key-id-control-is-killed
  (let [encode secure/encode-canonical
        omit (fn [value]
               (if (and (map? value) (= #{:v :kid :payload} (set (keys value))))
                 (dissoc value :kid) value))
        mutant (fn [value & args] (apply encode (omit value) args))]
    (is (detected? {#'secure/encode-canonical mutant}
                   [#'format-test/key-id-is-authenticated-even-when-two-ids-have-the-same-material]))))

(deftest ring-wide-fallback-control-is-killed
  (let [decode secure/decode-authenticated-envelope
        trial (fn [opts token]
                (try (decode opts token)
                     (catch clojure.lang.ExceptionInfo error
                       (or (some (fn [root]
                                   (try (decode (assoc (dissoc opts :keyring-controller :keyring-snapshot)
                                                       :keyring {:old root}) token)
                                        (catch clojure.lang.ExceptionInfo _ nil)))
                                 (vals (:keys (protocols/-snapshot (:keyring-controller opts)))))
                           (throw error)))))]
    (is (detected? {#'secure/decode-authenticated-envelope trial}
                   [#'format-test/dedicated-scope-never-falls-back-to-another-accepted-key]))))

