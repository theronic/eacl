(ns eacl.datomic.scan-cache-integration-test
  "The exact scan-response cache observed through the public client on
  Datomic: identical pages, elided adapter commands across requests, the
  request-local memo under `:cache? false`, and invalidation by a write to
  the scanned relation."
  (:require [clojure.set]
            [clojure.test :refer [deftest is testing]]
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

(defn- sharing-pair
  "Two users that share at least one group, so the second user's walk
  re-reads group edges the first user's walk already fetched."
  []
  (let [members (fixture/memberships config)
        groups-of (fn [u] (set (take (:groups-per-user config) (get members u))))]
    (first (for [a (range (:users config))
                 b (range (:users config))
                 :when (and (< a b) (seq (clojure.set/intersection (groups-of a) (groups-of b))))]
             [a b]))))

(deftest shared-tier-elides-commands-across-requests-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn {})
          disabled (datomic/make-client conn {:scan-cache false})
          [a b] (sharing-pair)
          [_ a-commands] (scan-commands #(fixture/page client a 20))
          [b-page b-commands] (scan-commands #(fixture/page client b 20))
          [b-plain plain-commands] (scan-commands #(fixture/page disabled b 20))
          stats (:scan-cache (datomic/cache-stats client))]
      (testing "a second user's walk reuses the group scans the first fetched"
        (is (= (:data b-plain) (:data b-page)))
        (is (pos? a-commands))
        (is (< b-commands plain-commands)
            (str "with the tier " b-commands " commands, without it " plain-commands)))
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

(deftest cursor-recovery-on-an-older-basis-never-poisons-the-tier-test
  ;; A continuation whose cursor predates a relevant write is recovered on
  ;; the older basis. Its scans read that older slice under the current
  ;; generation of the same relation, so admitting them into the shared tier
  ;; would serve pre-write prefixes to later requests on the new basis.
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn {})
          u (sharing-user client)
          page-1 (fixture/page client u 3)
          cursor (get-in page-1 [:page-info :end-cursor])
          new-group (fixture/group 998)
          new-doc (fixture/doc 998 0)
          _ @(d/transact conn [{:eacl/id "grp-998"} {:eacl/id "doc-998-0"}])
          _ (eacl/create-relationships!
             client
             [(eacl/->Relationship new-group :group new-doc)
              (eacl/->Relationship (fixture/user u) :member new-group)])
          recovered (try (fixture/page client u 3 :after cursor)
                         (catch clojure.lang.ExceptionInfo error (ex-data error)))
          fresh (fixture/page client u 500)
          oracle (fixture/page client u 500 :cache? false)]
      (is (some? recovered))
      (is (= (:data oracle) (:data fresh))
          "a fresh enumeration after the recovery equals its uncached computation")
      (is (some #(= new-doc %) (:data fresh)) "the new grant is visible"))))

(deftest limits-fail-identically-with-every-scan-served-from-cache-test
  ;; Served values pass through the same limit accounting as fetched values:
  ;; a traversal that exceeds a ceiling fails with the same typed error and
  ;; the same reported ceiling whether its scans come from the adapter, the
  ;; request memo, or the shared tier.
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [seeded (seed-client! conn {})
          tight (datomic/make-client conn {:recursive-traversal-limits
                                           {:max-advanced-datoms 8}})
          u (sharing-user seeded)
          failure (fn [f] (try (f) nil
                               (catch clojure.lang.ExceptionInfo error
                                 (select-keys (ex-data error)
                                              [:eacl/error :limit-kind :limit]))))
          cold (failure #(fixture/page tight u 200))
          warm (failure #(fixture/page tight u 200))
          memo-free (binding [scan-cache/*memo-disabled?* true]
                      (failure #(fixture/page tight u 200 :cache? false)))
          shared-free (binding [scan-cache/*shared-disabled?* true]
                        (failure #(fixture/page tight u 200)))]
      (is (= :eacl.recursive-traversal/limit-exceeded (:eacl/error cold)))
      (is (= cold warm) "the second run, served from the tier, fails identically")
      (is (= cold memo-free))
      (is (= cold shared-free)))))
