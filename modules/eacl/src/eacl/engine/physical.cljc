(ns eacl.engine.physical
  "Width-one physical execution support for stable discovery
  (adopt-stable-discovery-enumeration, section 7): three-outcome read
  classification with cause codes, retry with the original absolute
  deadline, service-edge admission with slot-hold and the replay ledger,
  the closed topology capability record, and per-layer telemetry.

  Outcomes: a read either realizes COMPLETE (possibly empty) values, or
  throws a classified FAILURE (:retryable | :terminal, with a cause code;
  any partially realized output is discarded because realization happens
  inside the classification boundary), or throws CANCELLED. Nil is never
  an outcome."
  (:require [eacl.execution :as execution]))

;; ---------------------------------------------------------------------------
;; Three-outcome classification (task 7.1)
;; ---------------------------------------------------------------------------

(defn scan-failure?
  [error]
  (= :eacl.scan/failure (:eacl/error (ex-data error))))

(defn- classification-of
  [throwable]
  (let [data (ex-data throwable)]
    (cond
      (:classification data) (:classification data)
      #?(:clj (instance? InterruptedException throwable) :cljs false)
      :cancelled
      ;; Unclassified adapter exceptions default retryable: a terminal
      ;; verdict requires positive knowledge, and misclassifying terminal
      ;; as retryable costs bounded attempts while the reverse loses reads.
      :else :retryable)))

(defn classified-fetch-fn
  "Wraps a read-demand fetch so every outcome is one of the three classes.
  The chunk is fully realized inside the boundary, so a mid-stream failure
  discards all partial output atomically and reducer state is untouched."
  [fetch-fn]
  (fn [descriptor]
    (try
      (vec (fetch-fn descriptor))
      (catch #?(:clj Throwable :cljs :default) failure
        (if (scan-failure? failure)
          (throw failure)
          (throw (ex-info "Adapter read failed."
                          {:eacl/error :eacl.scan/failure
                           :classification (classification-of failure)
                           :cause-class #?(:clj (.getName (class failure))
                                           :cljs (str (type failure)))
                           :operation (:operation descriptor)}
                          failure)))))))

(defn retrying-fetch-fn
  "Retries classified :retryable failures for the same exact descriptor
  under the ORIGINAL absolute deadline; attempts are counted separately
  from logical commands via `attempts` (an atom). Terminal and cancelled
  classifications never retry; partial output never survives an attempt."
  [fetch-fn {:keys [max-attempts deadline-nanos attempts]
             :or {max-attempts 3}}]
  (let [classified (classified-fetch-fn fetch-fn)]
    (fn [descriptor]
      (loop [attempt 1]
        (when attempts (swap! attempts inc))
        (let [outcome
              (try
                {:values (classified descriptor)}
                (catch #?(:clj clojure.lang.ExceptionInfo
                          :cljs cljs.core/ExceptionInfo) failure
                  (if (and (scan-failure? failure)
                           (= :retryable
                              (:classification (ex-data failure)))
                           (< attempt max-attempts)
                           (or (nil? deadline-nanos)
                               (< (execution/now-nanos) deadline-nanos)))
                    {:retry failure}
                    (throw failure))))]
          (if (contains? outcome :values)
            (:values outcome)
            (recur (inc attempt))))))))

;; ---------------------------------------------------------------------------
;; Service-edge admission and the replay ledger (task 7.5)
;; ---------------------------------------------------------------------------

(defn make-service-admission
  "Bounded service-edge admission: at most `:max-concurrent` enumerations
  hold slots, and the replay ledger bounds total and per-key concurrent
  replays. Slots are held for the full synchronous duration of the work —
  at width one the enumeration IS the physical call chain, so a cancelled
  request's slot is not released until its backend call physically
  returns."
  ([] (make-service-admission {}))
  ([{:keys [max-concurrent max-replays max-replays-per-key]
     :or {max-concurrent 64 max-replays 16 max-replays-per-key 2}}]
   (atom {:active 0
          :replays {}
          :total-replays 0
          :max-concurrent max-concurrent
          :max-replays max-replays
          :max-replays-per-key max-replays-per-key})))

(defn- admission-rejected! [kind data]
  (throw (ex-info "Service admission rejected."
                  (assoc data :eacl/error kind))))

(defn with-admission
  "Runs `work` holding one enumeration slot; rejects typed when the bound
  is reached. The slot releases only when `work` returns or throws — never
  early on logical cancellation."
  [admission work]
  (if (nil? admission)
    (work)
    (do
      (swap! admission
             (fn [{:keys [active max-concurrent] :as state}]
               (if (>= active max-concurrent)
                 (admission-rejected! :eacl.service/admission-rejected
                                      {:active active
                                       :max-concurrent max-concurrent})
                 (update state :active inc))))
      (try
        (work)
        (finally
          (swap! admission update :active dec))))))

(defn with-replay-admission
  "Runs `work` under the replay ledger for one continuation key; rejects
  typed when total or per-key replay quotas are exhausted."
  [admission key work]
  (if (nil? admission)
    (work)
    (do
      (swap! admission
             (fn [{:keys [replays total-replays max-replays
                          max-replays-per-key] :as state}]
               (cond
                 (>= total-replays max-replays)
                 (admission-rejected! :eacl.service/replay-rejected
                                      {:total-replays total-replays
                                       :max-replays max-replays})
                 (>= (get replays key 0) max-replays-per-key)
                 (admission-rejected! :eacl.service/replay-rejected
                                      {:key key
                                       :max-replays-per-key
                                       max-replays-per-key})
                 :else
                 (-> state
                     (update :total-replays inc)
                     (update-in [:replays key] (fnil inc 0))))))
      (try
        (work)
        (finally
          (swap! admission
                 (fn [state]
                   (let [remaining (dec (get-in state [:replays key] 1))
                         state (update state :total-replays dec)]
                     ;; A key at zero leaves the ledger, so a long-lived
                     ;; admission atom does not grow with every distinct
                     ;; continuation key it has ever seen.
                     (if (pos? remaining)
                       (assoc-in state [:replays key] remaining)
                       (update state :replays dissoc key))))))))))

(defn execution-cut-point
  "Builds a reducer cut-point hook from a normalized execution context:
  checks cancellation and the original absolute deadline at every bounded
  reducer transition (task 7.6)."
  [execution-context]
  (fn [_state]
    (execution/check! execution-context :reducer-transition)))

;; ---------------------------------------------------------------------------
;; Closed topology capability record (task 7.7)
;; ---------------------------------------------------------------------------

(def capability-keys
  #{:immutable-basis? :strict-scan-order? :unique-scan-values?
    :replayable-scans? :strict-progress? :atomic-response?
    :failure-classification? :physically-cancellable?
    :termination-on-return? :nested-retry-exposure
    :semantic-concurrent-read-safe? :deployment-width
    :exact-basis-selection?})

(def conservative-capabilities
  "Missing capabilities default to the most conservative safe policy; the
  deployment width is one for every topology in this change, independent
  of semantic concurrent-read safety (the future concurrency change's SPI
  seam)."
  {:immutable-basis? false
   :strict-scan-order? false
   :unique-scan-values? false
   :replayable-scans? false
   :strict-progress? false
   :atomic-response? false
   :failure-classification? false
   :physically-cancellable? false
   :termination-on-return? true
   :nested-retry-exposure :unknown
   :semantic-concurrent-read-safe? false
   :deployment-width 1
   :exact-basis-selection? false})

(defn topology-capabilities
  "Validates and completes one topology's closed capability record.
  Unknown keys are rejected; the deployment width is pinned to one."
  [declared]
  (when-let [unknown (seq (remove capability-keys (keys declared)))]
    (throw (ex-info "Unknown topology capability keys."
                    {:eacl/error :eacl.topology/invalid-capabilities
                     :unknown (vec unknown)})))
  (when (and (contains? declared :deployment-width)
             (not= 1 (:deployment-width declared)))
    (throw (ex-info "Deployment width is one for every topology in this change."
                    {:eacl/error :eacl.topology/invalid-capabilities
                     :deployment-width (:deployment-width declared)})))
  (merge conservative-capabilities declared))

(defn stable-discovery-qualified?
  "A topology is qualified for stable discovery only when the semantic
  scan contract and failure classification hold."
  [capabilities]
  (every? capabilities [:immutable-basis? :strict-scan-order?
                        :unique-scan-values? :replayable-scans?
                        :strict-progress? :atomic-response?
                        :failure-classification?]))

;; ---------------------------------------------------------------------------
;; Per-layer telemetry (task 7.8)
;; ---------------------------------------------------------------------------

(defn telemetry
  "Separates every observable cost layer of one finished run: canonical
  reducer transitions, logical scan commands, values fetched versus
  admitted logical work, and adapter attempts when a counting retry
  wrapper was installed. Node-cache and remote-operation counters remain
  storage-layer observations and are never inferred here."
  [finished-state & [{:keys [attempts]}]]
  (cond-> {:reducer-transitions (:transitions finished-state)
           :logical-scan-commands (:commands finished-state)
           :values-fetched (:fetched-values finished-state)
           :logical-admissions (:admissions finished-state)
           :results-discovered (:discovered finished-state)
           :maximum-stack (:maximum-stack finished-state)
           :maximum-retained-buffers (:maximum-sidecar-buffers finished-state)
           :maximum-retained-values (:maximum-sidecar-values finished-state)}
    attempts (assoc :adapter-attempts @attempts)))
