(ns eacl.performance.release-win
  "Frozen public release-win lane and baseline-only variance pilot."
  (:require [datascript.core :as ds]
            [eacl.bench.paired :as paired]
            [eacl.bench.recursive-fixture :as fixture]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as ds-backend]
            [eacl.datascript.core :as datascript]
            [eacl.request.counters :as counters]))

(def lane-id :recursive-star-exact-count)
(def default-config {:shape :star :accounts 4096})

(defn seed!
  ([] (seed! default-config))
  ([config]
   (let [conn (datascript/create-conn)
         client (datascript/make-client conn {})]
     (eacl/write-schema! client (fixture/schema-for config))
     (ds/transact! conn (vec (fixture/object-transactions config)))
     (doseq [batch (fixture/relationship-batches config)]
       (eacl/create-relationships! client (vec batch)))
     {:config config
      :connection conn
      :client client
      :expected-count (fixture/expected-view-count config fixture/user-1)})))

(defn invoke!
  [{:keys [config client expected-count]}]
  (let [result
        (eacl/count-resources
         client
         (assoc (fixture/count-query config fixture/user-1)
                :cache? false))]
    (when-not (and (= expected-count (:count result))
                   (= -1 (:limit result))
                   (not (:truncated? result)))
      (throw
       (ex-info
        "Release-win count result changed."
        {:type :eacl.performance/release-win-mismatch
         :expected expected-count
         :actual result})))
    [(:count result) (:limit result) (boolean (:truncated? result))]))

(defn observed-invocation!
  [state]
  (let [ledger (counters/make-ledger)
        backend-operations (atom {})
        started (System/nanoTime)
        result
        (binding [counters/*ledger* ledger
                  backend/*backend-op-stats* backend-operations]
          (invoke! state))
        response-ended (System/nanoTime)
        counters-at-response (counters/snapshot ledger)
        backend-at-response @backend-operations
        _ (Thread/sleep 10)
        counters-after-window (counters/snapshot ledger)
        backend-after-window @backend-operations]
    {:result result
     :elapsed-nanos (- response-ended started)
     :response-boundary :fully-realized-public-result
     :mandatory-counters counters-at-response
     :backend-operations backend-at-response
     :post-response
     {:observation-window-nanos 10000000
      :counter-delta
      (into {}
            (keep (fn [[key value]]
                    (let [delta (- value (get counters-at-response key 0))]
                      (when (pos? delta) [key delta]))))
            counters-after-window)
      :backend-operation-delta
      (into {}
            (keep (fn [[key value]]
                    (let [delta (- value (get backend-at-response key 0))]
                      (when (pos? delta) [key delta]))))
            backend-after-window)}}))

(defn- baseline-pilot-for-state
  [state warmups samples]
  (let [config (:config state)
        report
         (paired/run-paired!
          {:arms [[:baseline-a (fn [_] (invoke! state))]
                  [:baseline-b (fn [_] (invoke! state))]]
           :warmups warmups
           :samples samples})
        medians
        (mapv #(get-in report [:arms % :latency-us :p50])
              [:baseline-a :baseline-b])
        relative-gap
        (/ (Math/abs (- (double (first medians))
                        (double (second medians))))
           (max (double (first medians))
                (double (second medians))))]
    {:lane lane-id
     :config config
     :expected-count (:expected-count state)
     :basis {:source-id
             (ds-backend/connection-source-id (:connection state))
             :max-tx (:max-tx @(:connection state))
             :kind :ordinary}
     :cache-regime :completed-disabled-derived-warm
     :operation-boundary :fully-realized-public-result
     :relative-median-gap relative-gap
     :report report}))

(defn baseline-variance-pilot!
  ([] (baseline-variance-pilot! {}))
  ([{:keys [config warmups samples]
     :or {config default-config warmups 7 samples 21}}]
   (baseline-pilot-for-state (seed! config) warmups samples)))

(defn baseline-capture!
  ([] (baseline-capture! {}))
  ([{:keys [config warmups samples]
     :or {config default-config warmups 10 samples 40}}]
   (let [state (seed! config)]
     (assoc (baseline-pilot-for-state state warmups samples)
            :observed-invocation (observed-invocation! state)))))
