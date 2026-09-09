(ns eacl.authorization.result
  "Public permissionship projection of completed bounded evidence."
  (:require [eacl.authorization.evidence :as evidence]))

(defn result-policy
  "Absent policy preserves definite-only lookup/count compatibility."
  [request]
  (let [policy (get request :result-policy :definite)]
    (when-not (#{:definite :detailed} policy)
      (throw (ex-info "Result policy must be :definite or :detailed."
                      {:type :eacl.authorization/invalid-result-policy
                       :eacl/error :eacl.authorization/invalid-result-policy})))
    policy))

(defn count-result
  "Projects count categories only when requested, including empty results."
  [{:keys [count truncated? definite-count conditional-count]} limit policy]
  (cond-> {:count count :limit (or limit -1)}
    (some? limit) (assoc :truncated? truncated?)
    (= :detailed policy)
    (assoc :definite-count (or definite-count count)
           :conditional-count (or conditional-count 0))))

(defn check-result
  "Produces a detailed result without losing conditional evidence or faults.
   Residuals use the bounded canonical evidence encoding, including its format
   marker; callers should supply missing fields in a new check request."
  [value]
  (evidence/throw-if-fault! value)
  (let [membership (evidence/permissionship value)]
    (cond-> {:allowed? (evidence/has? value) :permissionship membership}
      (= :conditional-permission membership)
      (assoc :missing-fields (evidence/missing-fields value)
             :residual (evidence/encode value)))))

(defn cache-value?
  "Cache ingress accepts only canonical completed evidence without faults."
  [value]
  (try
    (and (string? value) (not (evidence/fault? (evidence/decode value))))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))

(defn lookup-result
  "Keeps the reusable object identity separate from its detailed decision."
  [object value]
  (assoc (check-result value) :object object))

(defn lookup-result-valid?
  "Closed detailed lookup transport; no denials, faults, or inconsistent residuals."
  [object? item]
  (try
    (and (map? item)
         (object? (:object item))
         (case (:permissionship item)
           :has-permission
           (= item (lookup-result (:object item) true))
           :conditional-permission
           (let [value (evidence/decode (:residual item))]
             (and (= :conditional-permission (evidence/permissionship value))
                  (= item (lookup-result (:object item) value))))
           false))
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ false)))
