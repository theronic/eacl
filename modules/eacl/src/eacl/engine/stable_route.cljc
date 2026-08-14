(ns eacl.engine.stable-route
  "Operation-appropriate routes on the stable-discovery engine
  (adopt-stable-discovery-enumeration, tasks 8.1-8.2).

  - Point checks are anchored to the known resource: reverse traversal
    from the resource with early termination on the subject's first
    admission — the root universe is never enumerated to answer one
    known-resource question.
  - Exact count exhausts the history-free reducer; its scalar discovered
    count equals the denotation cardinality. An order-insensitive
    specialization remains permitted only behind an independent
    denotation-equivalence proof (none exists yet)."
  (:require [eacl.backend.v8 :as backend]
            [eacl.engine.stable-reducer :as reducer]))

(def exhaustion-target 1000000)

(defn- found! []
  (throw (ex-info "found" {::found true})))

(defn check-eids
  "Anchored point check over pre-resolved internal ids: does the subject
  hold the plan's root permission on the resource? Terminates on the
  subject's first admission."
  [{:keys [adapter plan subject-type subject-eid resource-eid] :as options}]
  (if (or (nil? subject-eid) (nil? resource-eid))
    false
    (let [seen (volatile! 0)
          caller-cut-point! (:cut-point! options)
          watch (fn [state]
                  (when caller-cut-point! (caller-cut-point! state))
                  (let [results (:results state)
                        n (count results)]
                    (when (> n @seen)
                      (vreset! seen n)
                      (when (= subject-eid (nth results (dec n)))
                        (found!)))))]
      (try
        (let [finished (reducer/run-reverse
                        (merge (select-keys options
                                            [:adapter :fetch-fn :plan
                                             :subject-type
                                             :physical-chunk-size
                                             :sidecar-cap :max-admissions
                                             :max-commands
                                             :max-transitions])
                               {:resource-eid resource-eid
                                :target exhaustion-target
                                :cut-point! watch}))]
          (boolean (some #{subject-eid} (:results finished))))
        (catch #?(:clj clojure.lang.ExceptionInfo
                  :cljs cljs.core/ExceptionInfo) error
          (if (::found (ex-data error))
            true
            (throw error)))))))

(defn check
  "Anchored point check over external ids; see check-eids."
  [{:keys [adapter subject-id resource-id] :as options}]
  (check-eids
   (assoc options
          :subject-eid (backend/invoke adapter :object-id->internal
                                       subject-id)
          :resource-eid (backend/invoke adapter :object-id->internal
                                        resource-id))))

(defn count-resources
  "Exact count by exhausting the reducer; :count-limit truncates with an
  explicit marker exactly like the current public contract."
  [{:keys [adapter plan subject-type subject-id count-limit] :as options}]
  (let [subject-eid (backend/invoke adapter :object-id->internal subject-id)
        target (if count-limit (inc count-limit) exhaustion-target)]
    (if (nil? subject-eid)
      {:count 0 :limit (or count-limit -1) :truncated? false}
      (let [finished (reducer/run-forward
                      (merge (select-keys options
                                          [:adapter :fetch-fn :plan
                                           :subject-type :cut-point!
                                           :physical-chunk-size :sidecar-cap
                                           :max-admissions :max-commands
                                           :max-transitions])
                             {:subject-eid subject-eid
                              :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        {:count (if truncated? count-limit discovered)
         :limit (or count-limit -1)
         :truncated? truncated?}))))

(defn count-subjects
  "Exact reverse count by exhaustion, mirroring count-resources."
  [{:keys [adapter plan subject-type resource-id count-limit] :as options}]
  (let [resource-eid (backend/invoke adapter :object-id->internal resource-id)
        target (if count-limit (inc count-limit) exhaustion-target)]
    (if (nil? resource-eid)
      {:count 0 :limit (or count-limit -1) :truncated? false}
      (let [finished (reducer/run-reverse
                      (merge (select-keys options
                                          [:adapter :fetch-fn :plan
                                           :subject-type :cut-point!
                                           :physical-chunk-size :sidecar-cap
                                           :max-admissions :max-commands
                                           :max-transitions])
                             {:resource-eid resource-eid
                              :target target}))
            discovered (:discovered finished)
            truncated? (boolean (and count-limit
                                     (> discovered count-limit)))]
        {:count (if truncated? count-limit discovered)
         :limit (or count-limit -1)
         :truncated? truncated?}))))
