(ns eacl.bench.recursive-performance-gate-test
  "Matched-v7 latency acceptance gate over populated recursion.

  Reads formal/baselines/explorer-v8-recursive-performance.edn and,
  per scenario/op:
  - :enforced          — warmed v8 median must be within
                         :maximum-ratio-to-v7 of the recorded v7 median;
  - :known-regression  — v8 must complete; the observed ratio is
                         reported (stderr) but does not fail the build.
                         Fix groups flip these to :enforced;
  - :v8-completes-where-v7-cannot — completion IS the gate.

  ^:benchmark — runs in the formal workflow's heavy job (fresh bounded
  JVM, measured before CLJS compiles, per FORMAL-048), never in the
  per-push suite. Logical-work gates (recursive-op-count tests) remain
  authoritative for regressions with matching latency."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.bench.recursive-fixture :as rf]
            [eacl.core :as eacl]
            [eacl.datomic.core :as dc]
            [eacl.datomic.impl :as dimpl]
            [eacl.datomic.schema :as dschema]))

(def ^:private manifest
  (-> (io/file "formal/baselines/explorer-v8-recursive-performance.edn")
      slurp
      edn/read-string))

(defn- median [xs]
  (let [s (vec (sort xs))]
    (nth s (quot (count s) 2))))

(defn- bench-ms [f warmups samples]
  (dotimes [_ warmups] (f))
  (median (repeatedly samples
                      #(let [t0 (System/nanoTime)]
                         (f)
                         (/ (- (System/nanoTime) t0) 1e6)))))

(defn- seed! [config]
  (let [uri (str "datomic:mem://recursive-gate-" (java.util.UUID/randomUUID))
        _ (assert (d/create-database uri))
        conn (d/connect uri)]
    (dschema/install! conn)
    (let [client (dc/make-client
                  conn
                  {:entid->object-id
                   (fn [snapshot internal-id]
                     (:eacl/id (d/entity snapshot internal-id)))})]
      (eacl/write-schema! client (rf/schema-for config))
      (doseq [batch (partition-all 500 (rf/object-transactions config))]
        @(d/transact conn (vec batch)))
      (doseq [batch (rf/relationship-batches config)]
        (eacl/create-relationships! client (vec batch)))
      {:uri uri :conn conn :db (d/db conn)})))

(defn- op-thunk [db accounts op]
  (let [eid (fn [x] (d/entid db [:eacl/id x]))
        u1 {:type :user :id (eid "user-1")}
        deep {:type :account :id (eid (rf/account-id (int (* 0.75 accounts))))}]
    (case op
      :can-deep #(dimpl/can? db u1 :view deep)
      :first-50 #(dimpl/lookup-resources
                  db {:subject u1 :permission :view
                      :resource/type :account :first 50})
      :exact-count #(dimpl/count-resources
                     db {:subject u1 :permission :view
                         :resource/type :account}))))

(defn- run-scenario! [[scenario-key {:keys [fixture ops]}]]
  (let [{:keys [warmups samples]} (:recorded manifest)
        bound (get-in manifest [:latency-gate :maximum-ratio-to-v7])
        {:keys [uri conn db]} (seed! fixture)]
    (try
      (doseq [[op-key {:keys [v7-median-ms status]}] ops]
        (testing (str (name scenario-key) "/" (name op-key))
          (let [thunk (op-thunk db (:accounts fixture) op-key)
                observed (bench-ms thunk warmups samples)]
            (binding [*out* *err*]
              (println (format "%s/%s v8=%.3fms v7=%s status=%s"
                               (name scenario-key) (name op-key)
                               observed (str v7-median-ms) (name status))))
            (case status
              :enforced
              (is (<= observed (* bound v7-median-ms))
                  (format "%s/%s regressed: v8 %.3fms > %.1fx of v7 %.3fms"
                          (name scenario-key) (name op-key)
                          observed bound (double v7-median-ms)))
              (:known-regression :v8-completes-where-v7-cannot)
              (is (number? observed)
                  "v8 must complete the scenario")))))
      (finally
        (d/release conn)
        (d/delete-database uri)))))

(deftest ^:benchmark ^:acceptance recursive-performance-gate-test
  (doseq [scenario (:scenarios manifest)]
    (run-scenario! scenario)))
