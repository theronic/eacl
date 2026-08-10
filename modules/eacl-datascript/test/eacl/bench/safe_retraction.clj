(ns eacl.bench.safe-retraction
  "Reproducible, non-gating safe-retraction benchmark.

  Structural operation-count assertions live in the ordinary test suites. This
  runner records qualified wall-clock evidence without imposing absolute
  latency thresholds on CI."
  (:require [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.core :as core]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.safe-retraction :as safe-datascript]
            [eacl.datascript.schema :as schema]
            [eacl.relationships.safe-retraction :as safe]))

(def benchmark-schema
  "definition user {}
   definition account {
     relation owner: user
   }")

(defn- round-3
  [value]
  (/ (Math/round (* 1000.0 value)) 1000.0))

(defn- percentile
  [values fraction]
  (let [ordered (vec (sort values))
        index (min (dec (count ordered))
                   (long (Math/floor (* fraction (dec (count ordered))))))]
    (nth ordered index)))

(defn- measure
  [iterations f]
  (let [samples
        (mapv
         (fn [_]
           (let [started (System/nanoTime)]
             (f)
             (/ (double (- (System/nanoTime) started)) 1000.0)))
         (range iterations))]
    {:iterations iterations
     :median-us (round-3 (percentile samples 0.5))
     :p95-us (round-3 (percentile samples 0.95))}))

(defn- elapsed-us
  [f]
  (let [started (System/nanoTime)]
    (f)
    (round-3 (/ (double (- (System/nanoTime) started)) 1000.0))))

(defn- fixture
  [degree]
  (let [conn (schema/create-conn)
        client (core/make-client conn {})
        target (eacl/spice-object :account "target")
        subjects
        (mapv (fn [index]
                (eacl/spice-object :user (str "subject-" index)))
              (range degree))]
    (eacl/write-schema! client benchmark-schema)
    (ds/transact!
     conn
     (into [{:eacl/id (:id target)}]
           (map (fn [subject] {:eacl/id (:id subject)}))
           subjects))
    (when (seq subjects)
      (eacl/create-relationships!
       client
       (mapv #(eacl/->Relationship % :owner target) subjects)))
    (safe-datascript/install! conn)
    {:db (ds/db conn)
     :target [:eacl/id (:id target)]}))

(defn- benchmark-degree
  [degree]
  (let [{:keys [db target]} (fixture degree)
        target-eid (ds/entid db target)
        installed-function (:db/fn (ds/entity db safe/function-ident))
        atomic-expansion #(installed-function db target)
        legacy-expansion #(impl/tx-delete-object db target-eid)
        first-use-us (elapsed-us atomic-expansion)
        _ (dotimes [_ 20]
            (atomic-expansion)
            (legacy-expansion))
        expansion-iterations (if (< degree 1000) 200 50)
        commit-iterations (cond
                            (< degree 10) 30
                            (< degree 100) 20
                            (< degree 1000) 10
                            :else 5)
        atomic-ops (count (atomic-expansion))
        legacy-ops (count (legacy-expansion))]
    {:degree degree
     :operation-counts
     {:atomic atomic-ops
      :legacy-delete-object-generation legacy-ops}
     :first-use-expansion-us first-use-us
     :warmed-expansion
     {:atomic (measure expansion-iterations atomic-expansion)
      :legacy-delete-object-generation
      (measure expansion-iterations legacy-expansion)}
     :commit
     {:atomic
      (measure
       commit-iterations
       (fn []
         (let [conn (ds/conn-from-db db)]
           (ds/transact!
            conn
            (safe-datascript/retract-entity-tx-data
             target)))))
      :legacy-cleanup-plus-native-retract
      (measure
       commit-iterations
       (fn []
         (let [conn (ds/conn-from-db db)
               tx-data (conj (impl/tx-delete-object (ds/db conn) target-eid)
                             [:db.fn/retractEntity target-eid])]
           (ds/transact! conn tx-data))))}}))

(defn run-benchmark!
  ([] (run-benchmark! [0 1 10 100 1000]))
  ([degrees]
   {:benchmark :safe-retraction
    :backend :datascript
    :runtime
    {:java-version (System/getProperty "java.version")
     :java-vm (System/getProperty "java.vm.name")
     :os (System/getProperty "os.name")
     :os-version (System/getProperty "os.version")
     :arch (System/getProperty "os.arch")
     :processors (.availableProcessors (Runtime/getRuntime))}
    :qualification
    {:absolute-latency-gate? false
     :commit-comparison
     "The comparison combines EACL peer cleanup with native entity retraction in one DataScript commit. Both paths use native relation generations and no mutation journal."
     :high-degree-guidance
     "Use atomic safe retraction only while one serialized transaction is operationally acceptable; use batched delete-object! before native entity deletion above that deployment-specific crossover."}
    :results (mapv benchmark-degree degrees)}))

(defn -main
  [& _]
  (prn (run-benchmark!)))
