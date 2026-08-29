(ns eacl.performance.release-win-cljs
  "Source-isolated Node lane for the portable release-win implementation."
  (:require [goog.object :as gobj]
            [datascript.core :as ds]
            [eacl.bench.recursive-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]))

(def config {:shape :star :accounts 4096})
(defonce benchmark-state (atom nil))

(defn- seed!
  []
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client (fixture/schema-for config))
    (ds/transact! conn (vec (fixture/object-transactions config)))
    (doseq [batch (fixture/relationship-batches config)]
      (eacl/create-relationships! client (vec batch)))
    {:client client
     :expected-count
     (fixture/expected-view-count config fixture/user-1)}))

(defn- invoke!
  [{:keys [client expected-count]}]
  (let [result
        (eacl/count-resources
         client
         (assoc (fixture/count-query config fixture/user-1)
                :cache? false))]
    (when-not (and (= expected-count (:count result))
                   (= -1 (:limit result))
                   (not (:truncated? result)))
      (throw (ex-info "Portable release-win result changed."
                      {:expected expected-count :actual result})))
    [(:count result) (:limit result) (boolean (:truncated? result))]))

(defn- sample!
  []
  (let [started (.now js/performance)
        result (invoke! @benchmark-state)
        ended (.now js/performance)]
    {:elapsed-nanos (js/Math.round (* 1000000 (- ended started)))
     :checksum (hash result)
     :result result}))

(defn- emit!
  [value]
  (println (pr-str value)))

(defn- main
  []
  (reset! benchmark-state (seed!))
  (dotimes [_ 10] (invoke! @benchmark-state))
  (let [readline (js/require "readline")
        create-interface (gobj/get readline "createInterface")
        input (create-interface
               #js {:input (.-stdin js/process) :terminal false})]
    (.on input "line"
         (fn [line]
           (case line
             "sample" (emit! (sample!))
             "stop" (do (.close input) (.exit js/process 0))
             (emit! {:error :unknown-command :command line}))))
    (emit! {:status :ready :warmups 10 :expected-count 4096})))

(set! *main-cli-fn* main)
