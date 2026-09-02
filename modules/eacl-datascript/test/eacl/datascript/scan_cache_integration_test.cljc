(ns eacl.datascript.scan-cache-integration-test
  "Cross-runtime parity of the exact scan-response cache on DataScript:
  identical pages, elided commands across requests, and the request-local
  memo under `:cache? false`."
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.backend.v8 :as backend]
            [eacl.datascript.core :as datascript]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.scan-cache-fixture :as fixture]))

(def ^:private config {:users 40 :groups 60 :groups-per-user 6 :seed 42})

(defn- seed-client!
  [client-options]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn client-options)]
    (fixture/seed! client config
                   (fn [ids]
                     (ds/transact! conn (mapv (fn [id] {:eacl/id id}) ids))))
    client))

(defn- scan-commands
  [f]
  (let [commands (atom 0)]
    (binding [backend/*invoke-observer*
              (fn [{:keys [phase operation]}]
                (when (and (= :after phase)
                           (contains? #{:subject->resources :resource->subjects}
                                      operation))
                  (swap! commands inc)))]
      (let [result (f)]
        [result @commands]))))

(defn- sharing-user
  [client]
  (or (some (fn [u]
              (let [[page commands]
                    (scan-commands #(fixture/page client u 5 :cache? false))]
                (when (and (seq (:data page)) (> commands 3)) u)))
            (range (:users config)))
      0))

(deftest shared-tier-elides-commands-across-requests-test
  (let [client (seed-client! {})
        u (sharing-user client)
        [first-page first-commands] (scan-commands #(fixture/page client u 20))
        [second-page second-commands] (scan-commands #(fixture/page client u 19))
        stats (:scan-cache (datascript/cache-stats client))]
    (testing "a different page size of the same walk reuses the scans"
      (is (= (vec (take 19 (:data first-page))) (:data second-page)))
      (is (pos? first-commands))
      (is (< second-commands first-commands)))
    (testing "meters"
      (is (pos? (:hits stats)))
      (is (pos? (:entry-count stats))))))

(deftest cache-disabled-requests-keep-the-request-local-memo-test
  (let [client (seed-client! {})
        u (sharing-user client)
        [memo-page memo-commands]
        (scan-commands #(fixture/page client u 20 :cache? false))
        [plain-page plain-commands]
        (binding [scan-cache/*memo-disabled?* true]
          (scan-commands #(fixture/page client u 20 :cache? false)))
        stats (:scan-cache (datascript/cache-stats client))]
    (is (= (:data memo-page) (:data plain-page)))
    (is (<= memo-commands plain-commands))
    (is (zero? (:hits stats)))
    (is (zero? (:deposits stats)))))

(deftest disabled-tier-reports-no-statistics-test
  (let [client (seed-client! {:scan-cache false})
        u (sharing-user client)]
    (fixture/page client u 20)
    (fixture/page client u 19)
    (is (nil? (:scan-cache (datascript/cache-stats client))))))
