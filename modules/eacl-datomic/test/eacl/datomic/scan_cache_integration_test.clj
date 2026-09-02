(ns eacl.datomic.scan-cache-integration-test
  "The exact scan-response cache observed through the public client on
  Datomic: identical pages, elided adapter commands across requests, the
  request-local memo under `:cache? false`, and invalidation by a write to
  the scanned relation."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.engine.scan-cache :as scan-cache]
            [eacl.scan-cache-fixture :as fixture]))

(def ^:private config {:users 40 :groups 60 :groups-per-user 6 :seed 42})

(defn- seed-client!
  [conn client-options]
  (let [client (datomic/make-client conn client-options)]
    (fixture/seed! client config
                   (fn [ids]
                     @(d/transact conn (mapv (fn [id] {:eacl/id id}) ids))))
    client))

(defn- scan-commands
  "Runs `f` and returns [result adapter-scan-command-count]."
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
  "A user whose page issues more than one scan."
  [client]
  (or (some (fn [u]
              (let [[page commands] (scan-commands #(fixture/page client u 5 :cache? false))]
                (when (and (seq (:data page)) (> commands 3)) u)))
            (range (:users config)))
      0))

(deftest shared-tier-elides-commands-across-requests-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn {})
          u (sharing-user client)
          [first-page first-commands] (scan-commands #(fixture/page client u 20))
          [second-page second-commands] (scan-commands #(fixture/page client u 19))
          stats (:scan-cache (datomic/cache-stats client))]
      (testing "a different page size of the same walk reuses the scans"
        (is (= (vec (take 19 (:data first-page))) (:data second-page)))
        (is (pos? first-commands))
        (is (< second-commands first-commands)
            (str "second page issued " second-commands " of " first-commands)))
      (testing "the tier's meters are exposed through the client statistics"
        (is (pos? (:hits stats)))
        (is (pos? (:entry-count stats)))))))

(deftest cache-disabled-requests-keep-the-request-local-memo-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn {})
          u (sharing-user client)
          [memo-page memo-commands] (scan-commands #(fixture/page client u 20 :cache? false))
          [plain-page plain-commands]
          (binding [scan-cache/*memo-disabled?* true]
            (scan-commands #(fixture/page client u 20 :cache? false)))
          stats (:scan-cache (datomic/cache-stats client))]
      (is (= (:data memo-page) (:data plain-page)))
      (is (<= memo-commands plain-commands)
          "memo-free execution issues every command the memoized run issued")
      (is (zero? (:hits stats)) "cache-disabled requests never read the shared tier")
      (is (zero? (:deposits stats)) "cache-disabled requests never write the shared tier"))))

(deftest write-to-the-scanned-relation-invalidates-the-tier-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn {})
          u (sharing-user client)
          _ (fixture/page client u 20)
          new-group (fixture/group 999)
          new-doc (fixture/doc 999 0)
          _ @(d/transact conn [{:eacl/id "grp-999"} {:eacl/id "doc-999-0"}])
          _ (eacl/create-relationships!
             client
             [(eacl/->Relationship new-group :group new-doc)
              (eacl/->Relationship (fixture/user u) :member new-group)])
          after (fixture/page client u 200)
          oracle (fixture/page client u 200 :cache? false)]
      (is (= (:data oracle) (:data after)) "the page after the write equals its uncached computation")
      (is (some #(= new-doc %) (:data after)) "the new grant is visible"))))

(deftest scan-cache-option-is-validated-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":scan-cache"
                          (datomic/make-client conn {:scan-cache {:max-entries 0}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":scan-cache"
                          (datomic/make-client conn {:scan-cache {:size 3}})))
    (let [client (seed-client! conn {:scan-cache false})
          u (sharing-user client)]
      (fixture/page client u 20)
      (fixture/page client u 19)
      (is (nil? (:scan-cache (datomic/cache-stats client)))
          "a disabled tier reports no statistics"))))
