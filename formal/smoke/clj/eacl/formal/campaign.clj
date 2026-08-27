(ns eacl.formal.campaign
  "Scheduled coherent differential campaign over the generated semantics."
  (:require [clojure.java.io :as io]
            [eacl.authorization-oracle :as oracle]
            [eacl.formal.differential-runner :as differential]
            [eacl.formal.generators :as generators]
            [eacl.formal.operator-campaign :as operator-campaign]
            [eacl.formal.semantics-bridge :as formal]))

(defn- mismatch
  [fixture]
  (try
    (let [normalize
          (fn [grants]
            (into
             #{}
             (map
              (fn [[subject permission resource]]
                [(select-keys subject [:type :id])
                 permission
                 (select-keys resource [:type :id])]))
             grants))
          report
          (differential/run-case
           {:seed (:seed fixture)
            :case-id :authorization-set
            :normalize normalize
            :metadata {:features (:features fixture)}
            :implementations
            [[:formal-semantics
              #(oracle/authorization-set fixture)]
             [:verified-generated-java
              #(formal/authorization-set fixture)]]})]
      (when (or (not (formal/well-formed? fixture))
                (= :failed (:status report)))
        {:differential report
         :well-formed? (formal/well-formed? fixture)}))
    (catch Throwable error
      {:exception {:class (str (class error))
                   :message (ex-message error)
                   :data (ex-data error)}})))

(defn- any-mismatch
  [fixture]
  (or (mismatch fixture)
      (operator-campaign/mismatch fixture)))

(defn- minimize-mismatch
  [fixture]
  (loop [current fixture]
    (if-let [smaller
             (first
              (sort-by
               (juxt #(count (:relationships %))
                     #(count (:objects %)))
               (filter any-mismatch (generators/shrink-graph current))))]
      (recur smaller)
      current)))

(defn- write-report!
  [path report]
  (io/make-parents path)
  (spit path (str (pr-str report) "\n"))
  report)

(defn run-campaign!
  "Runs `[start, start+count)` and writes reproducible success/failure metadata.
  A failure artifact includes the original seed and a coherence-preserving
  minimized fixture."
  [{:keys [start count output]
    :or {start 1
         count 200
         output "target/formal/campaign.edn"}}]
  (loop [seed start
         checked 0
         coverage #{}]
    (if (= checked count)
      (write-report!
       output
       {:schema-version 1
        :status :passed
        :seed-range [start (+ start count -1)]
        :checked checked
        :coverage coverage})
      (let [fixture (generators/coherent-schema seed)]
        (if-let [difference (any-mismatch fixture)]
          (let [minimized (minimize-mismatch fixture)
                failure
                {:schema-version 1
                 :status :failed
                 :seed seed
                 :difference difference
                 :fixture fixture
                 :minimized-fixture minimized}]
            (write-report! output failure)
            (throw
             (ex-info
              "Generated semantic campaign found a mismatch."
              {:seed seed :output output})))
          (recur (inc seed)
                 (inc checked)
                 (into coverage (:features fixture))))))))
