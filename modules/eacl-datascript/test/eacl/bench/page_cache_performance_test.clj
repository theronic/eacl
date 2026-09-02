(ns eacl.bench.page-cache-performance-test
  "Source-bound mechanism benchmarks for successful-result identity and Relay
  page-cache publication. Tagged out of ordinary correctness runs."
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.bench.paired :as paired]
            [eacl.cache-identity :as cache-identity]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.relay :as relay]))

(def baseline-source-sha
  "PR 160 source at which the two reproduced mechanisms were measured."
  "858a73a62dfcdf05a5341787f806796d55fd2aff")

(def capacities [64 512 2048])
(def publication-block-size 8)
(def minimum-premeasurement-churn-multiple 4)

(defn- legacy-remove-index-value
  [index request-key]
  (into {}
        (remove (fn [[_ indexed-key]]
                  (= request-key indexed-key)))
        index))

(defn- legacy-evict-page-request
  [state request-key]
  (-> state
      (update :entries dissoc request-key)
      (update :by-start legacy-remove-index-value request-key)
      (update :by-end legacy-remove-index-value request-key)))

(defn- legacy-put-page-request
  "Exact pre-change `put-page-request` transition from baseline-source-sha."
  [state request-key page max-entries]
  (let [order
        (conj
         (into [] (remove #(= request-key %)) (:order state))
         request-key)
        state
        (assoc state
               :order order
               :entries (assoc (:entries state) request-key page))
        overflow (- (count order) max-entries)]
    (if-not (pos? overflow)
      state
      (let [victims (take overflow order)]
        (reduce legacy-evict-page-request
                (assoc state :order (vec (drop overflow order)))
                victims)))))

(defn- legacy-publish
  [state request-key page start-boundary end-boundary max-entries]
  (cond->
   (legacy-put-page-request state request-key page max-entries)
    start-boundary (assoc-in [:by-start start-boundary] request-key)
    end-boundary (assoc-in [:by-end end-boundary] request-key)))

(defn- candidate-transition
  []
  (or (ns-resolve 'eacl.relay 'put-page-request)
      (throw
       (ex-info
        "Production page-cache transition is unavailable."
        {:type :eacl.bench/missing-production-consumer}))))

(defn- page-value
  [ordinal]
  {:data [ordinal]
   :page-info {:start-cursor [:start ordinal]
               :end-cursor [:end ordinal]
               :has-next-page? true
               :has-previous-page? true}})

(defn- legacy-empty-state
  []
  {:order [] :entries {} :by-start {} :by-end {}})

(defn- prefill-legacy
  [capacity]
  (reduce
   (fn [state ordinal]
     (legacy-publish
      state [:request ordinal] (page-value ordinal)
      [:start ordinal] [:end ordinal] capacity))
   (legacy-empty-state)
   (range capacity)))

(defn- prefill-candidate
  [capacity]
  (let [put! (candidate-transition)]
    (reduce
     (fn [state ordinal]
       (put! state [:request ordinal] (page-value ordinal)
             [:start ordinal] [:end ordinal] false capacity))
     @(:state (relay/page-navigation-cache {:max-entries capacity}))
     (range capacity))))

(defn- transition-block!
  [state counter transition capacity]
  (let [first-ordinal (swap! counter + publication-block-size)
        next-state
        (loop [state @state
               offset 0]
          (if (= publication-block-size offset)
            state
            (let [ordinal (+ first-ordinal offset)]
              (recur
               (transition
                state [:request ordinal] (page-value ordinal)
                [:start ordinal] [:end ordinal] capacity)
               (inc offset)))))]
    (vreset! state next-state)
    [(count (:entries next-state))
     (count (:by-start next-state))
     (count (:by-end next-state))]))

(defn- publication-capacity-report!
  [capacity {:keys [warmups samples]
             :or {warmups 20 samples 80}}]
  (let [legacy-state (volatile! (prefill-legacy capacity))
        candidate-state (volatile! (prefill-candidate capacity))
        legacy-counter (atom capacity)
        candidate-counter (atom capacity)
        put! (candidate-transition)
        premeasurement-churn-transitions
        (* minimum-premeasurement-churn-multiple capacity)
        churn-blocks
        (quot premeasurement-churn-transitions publication-block-size)
        _
        (dotimes [_ churn-blocks]
          (transition-block!
           legacy-state legacy-counter
           (fn [state request-key page start-boundary end-boundary cap]
             (legacy-publish
              state request-key page start-boundary end-boundary cap))
           capacity)
          (transition-block!
           candidate-state candidate-counter
           (fn [state request-key page start-boundary end-boundary cap]
             (put! state request-key page
                   start-boundary end-boundary false cap))
           capacity))
        report
        (paired/run-paired!
         {:arms
          [[:legacy-vector-and-index-scans
            (fn [_]
              (transition-block!
               legacy-state legacy-counter
               (fn [state request-key page start-boundary end-boundary cap]
                 (legacy-publish
                  state request-key page start-boundary end-boundary cap))
               capacity))]
           [:stamped-direct-index
            (fn [_]
              (transition-block!
               candidate-state candidate-counter
               (fn [state request-key page start-boundary end-boundary cap]
                 (put! state request-key page
                       start-boundary end-boundary false cap))
               capacity))]]
          :warmups warmups
          :samples samples
          :comparisons
          [{:baseline :legacy-vector-and-index-scans
            :candidate :stamped-direct-index
            :minimum-latency-reduction
            (when (= 2048 capacity) 0.50)}]})
        candidate @candidate-state]
    {:capacity capacity
     :publication-block-size publication-block-size
     :premeasurement-churn-transitions premeasurement-churn-transitions
     :report report
     :candidate-structure
     {:entries (count (:entries candidate))
      :stamps (count (:stamps candidate))
      :boundary-owners (count (:boundaries candidate))
      :start-boundaries (count (:by-start candidate))
      :end-boundaries (count (:by-end candidate))
      :order-records (count (:queue candidate))
      :metrics (:metrics candidate)}}))

(defn- direct-hit-capacity-report!
  [capacity {:keys [warmups samples batch-size]
             :or {warmups 20 samples 80 batch-size 4096}}]
  (let [legacy-state (prefill-legacy capacity)
        candidate-state (prefill-candidate capacity)
        request-key [:request (dec capacity)]
        report
        (paired/run-paired!
         {:arms
          (mapv
           (fn [[arm state]]
             [arm
              (fn [_]
                (loop [remaining batch-size
                       checksum 0]
                  (if (zero? remaining)
                    checksum
                    (recur
                     (dec remaining)
                     (+ checksum
                        (long
                         (first
                          (:data
                           (get-in state [:entries request-key])))))))))])
           [[:legacy-direct-hit legacy-state]
            [:stamped-direct-hit candidate-state]])
          :warmups warmups
          :samples samples
          :comparisons
          [{:baseline :legacy-direct-hit
            :candidate :stamped-direct-hit
            :minimum-latency-reduction -0.10}]})]
    {:capacity capacity
     :batch-size batch-size
     :report report}))

(def ^:private mechanism-schema
  "definition user {}
   definition document {
     relation reader: user
     permission view = reader
   }")

(defn- seed-mechanism-client!
  [suffix]
  (let [conn (datascript/create-conn)
        client
        (datascript/make-client
         conn {:security-key
               (str "cache-mechanism-" suffix "-000000000000000")})
        user (eacl/spice-object :user "benchmark-user")
        documents
        (mapv #(eacl/spice-object :document (str "document-" %))
              (range 256))]
    (eacl/write-schema! client mechanism-schema)
    (ds/transact!
     conn
     (into [{:eacl/id (:id user)}]
           (map (fn [document] {:eacl/id (:id document)}) documents)))
    (eacl/create-relationships!
     client
     (mapv #(eacl/->Relationship user :reader %) documents))
    {:client client
     :query {:subject user
             :permission :view
             :resource/type :document
             :first 128}
     :expected documents}))

(defn- invoke-mechanism-page!
  [{:keys [client query expected]} timeout-ms]
  (let [page
        (eacl/lookup-resources
         client (assoc query :timeout-ms timeout-ms))]
    (when-not (= (subvec expected 0 128) (:data page))
      (throw
       (ex-info
        "Cache identity mechanism benchmark changed the page."
        {:type :eacl.bench/semantic-drift
         :timeout-ms timeout-ms
         :actual-count (count (:data page))})))
    [(count (:data page))
     (:id (first (:data page)))
     (:id (peek (:data page)))
     (boolean (:cached? page))]))

(defn varying-timeout-mechanism-report!
  ([] (varying-timeout-mechanism-report! {}))
  ([{:keys [warmups samples]
     :or {warmups 10 samples 40}}]
   (let [legacy (seed-mechanism-client! "legacy")
         candidate (seed-mechanism-client! "candidate")
         legacy-observations (atom [])
         candidate-observations (atom [])
         report
         (paired/run-paired!
          {:arms
           [[:legacy-timeout-bearing-identity
             (fn [iteration]
               (let [result
                     (with-redefs
                      [cache-identity/successful-result-query identity]
                       (invoke-mechanism-page!
                        legacy (+ 100000 iteration)))]
                 (when (>= iteration warmups)
                   (swap! legacy-observations conj result))
                 (pop result)))]
            [:canonical-successful-result-identity
             (fn [iteration]
               (let [result
                     (invoke-mechanism-page!
                      candidate (+ 100000 iteration))]
                 (when (>= iteration warmups)
                   (swap! candidate-observations conj result))
                 (pop result)))]]
           :warmups warmups
           :samples samples
           :comparisons
           [{:baseline :legacy-timeout-bearing-identity
             :candidate :canonical-successful-result-identity
             :minimum-latency-reduction 0.25}]})
         legacy-cached (mapv peek @legacy-observations)
         candidate-cached (mapv peek @candidate-observations)]
     {:baseline-source-sha baseline-source-sha
      :fixture {:documents 256 :page-size 128}
      :warmups warmups
      :samples samples
      :legacy-all-misses? (every? false? legacy-cached)
      :candidate-all-hits? (every? true? candidate-cached)
      :semantic-results-equal?
      (= (mapv pop @legacy-observations)
         (mapv pop @candidate-observations))
      :report report})))

(defn run-benchmark!
  ([] (run-benchmark! {}))
  ([options]
   (let [public-report (varying-timeout-mechanism-report! options)
         publications
         (mapv #(publication-capacity-report! % options) capacities)
         hits (mapv #(direct-hit-capacity-report! % options) capacities)
         publication-p50
         (fn [capacity]
           (->> publications
                (filter #(= capacity (:capacity %)))
                first
                :report :arms :stamped-direct-index :latency-us :p50))
         hit-p50
         (fn [capacity]
           (->> hits
                (filter #(= capacity (:capacity %)))
                first
                :report :arms :stamped-direct-hit :latency-us :p50))
         publication-scale-ratio
         (/ (publication-p50 2048) (publication-p50 64))
         hit-scale-ratio (/ (hit-p50 2048) (hit-p50 64))]
     {:benchmark :eacl-cache-identity-and-page-publication
      :baseline-source-sha baseline-source-sha
      :capacities capacities
      :public-mechanism public-report
      :publications publications
      :direct-hits hits
      :publication-scale-ratio publication-scale-ratio
      :hit-scale-ratio hit-scale-ratio
      :gates
      {:public-mechanism
       (true? (get-in public-report [:report :comparisons 0 :passed?]))
       :public-semantic-parity (:semantic-results-equal? public-report)
       :legacy-fragmentation-reproduced (:legacy-all-misses? public-report)
       :candidate-reuse-observed (:candidate-all-hits? public-report)
       :capacity-2048-improvement
       (true?
        (get-in (last publications) [:report :comparisons 0 :passed?]))
       :hit-non-regression
       (every?
        #(true? (get-in % [:report :comparisons 0 :passed?]))
        hits)
       :publication-scale (<= publication-scale-ratio 2.5)
       :hit-scale (<= hit-scale-ratio 2.5)}})))

(deftest ^:benchmark cache-identity-and-page-publication-performance-gate-test
  (let [report (run-benchmark!)]
    (prn report)
    (doseq [[gate passed?] (:gates report)]
      (is passed? (str "failed cache performance gate " gate)))))
