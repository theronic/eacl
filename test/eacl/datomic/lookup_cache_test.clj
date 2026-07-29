(ns eacl.datomic.lookup-cache-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]))

(def ^:private direct-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }")

(def ^:private arrow-schema
  "definition user {}
   definition account {
     relation owner: user
     relation auditor: user
     permission admin = owner
   }
   definition server {
     relation account: account
     permission view = account->admin
   }")

(defn- seed-direct!
  [conn client]
  (eacl/write-schema! client direct-schema)
  @(d/transact conn [{:eacl/id "alice"}
                     {:eacl/id "bob"}
                     {:eacl/id "a-1"}
                     {:eacl/id "a-2"}])
  (eacl/create-relationship!
   client
   (->Relationship (spice-object :user "alice")
                   :owner
                   (spice-object :account "a-1"))))

(deftest live-non-recursive-pages-survive-unrelated-transactions-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:live-lookups? true}})
          forward-query {:subject (spice-object :user "alice")
                         :permission :admin
                         :resource/type :account}
          reverse-query {:resource (spice-object :account "a-1")
                         :permission :admin
                         :subject/type :user}
          forward-calls (atom 0)
          reverse-calls (atom 0)
          original-forward impl/lookup-resources
          original-reverse impl/lookup-subjects]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db query]
                      (swap! forward-calls inc)
                      (original-forward db query))
                    impl/lookup-subjects
                    (fn [db query]
                      (swap! reverse-calls inc)
                      (original-reverse db query))]
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client forward-query)))))
        (is (= ["alice"] (mapv :id (:data (eacl/lookup-subjects client reverse-query)))))

        (dotimes [n 10]
          @(d/transact conn [{:eacl/id (str "unrelated-" n)}]))

        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client forward-query)))))
        (is (= ["alice"] (mapv :id (:data (eacl/lookup-subjects client reverse-query)))))
        (is (= 1 @forward-calls) "basis changes do not invalidate EACL results")
        (is (= 1 @reverse-calls) "reverse lookup uses the same logical generation")))))

(deftest relationship-write-invalidates-live-pages-but-no-op-does-not-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:live-lookups? true}})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          calls (atom 0)
          original impl/lookup-resources
          second-rel (->Relationship (spice-object :user "alice")
                                     :owner
                                     (spice-object :account "a-2"))]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query]
                      (swap! calls inc)
                      (original db internal-query))]
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls))

        (eacl/create-relationship!
         client
         (->Relationship (spice-object :user "bob")
                         :auditor
                         (spice-object :account "a-2")))
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls)
            "a relationship outside the permission dependency set keeps the page hot")

        (eacl/create-relationship! client second-rel)
        (is (= #{"a-1" "a-2"}
               (set (map :id (:data (eacl/lookup-resources client query))))))
        (is (= 2 @calls) "an actual relationship write selects a new generation")

        (eacl/write-relationship!
         client
         {:operation :touch
          :subject (:subject second-rel)
          :relation (:relation second-rel)
          :resource (:resource second-rel)})
        (is (= #{"a-1" "a-2"}
               (set (map :id (:data (eacl/lookup-resources client query))))))
        (is (= 2 @calls) "a relationship no-op keeps the hot generation")))))

(deftest process-shared-coordinator-invalidates-other-clients-test
  (with-mem-conn [conn schema/v7-schema]
    (let [writer (core/make-client conn {:cache false})
          reader (core/make-client conn {:cache {:live-lookups? true}})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}
          calls (atom 0)
          original impl/lookup-resources]
      (seed-direct! conn writer)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query]
                      (swap! calls inc)
                      (original db internal-query))]
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources reader query)))))
        (is (= 1 @calls))
        (eacl/create-relationship!
         writer
         (->Relationship (spice-object :user "alice")
                         :owner
                         (spice-object :account "a-2")))
        (is (= #{"a-1" "a-2"}
               (set (map :id (:data (eacl/lookup-resources reader query))))))
        (is (= 2 @calls)
            "a writer whose own cache is disabled still advances the shared coordinator")))))

(deftest live-page-dependencies-include-arrow-relations-and-target-permissions-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:live-lookups? true}})
          alice (spice-object :user "alice")
          bob (spice-object :user "bob")
          account (spice-object :account "account")
          server (spice-object :server "server")
          owner-rel (->Relationship alice :owner account)
          account-rel (->Relationship account :account server)
          query {:subject alice
                 :permission :view
                 :resource/type :server}
          calls (atom 0)
          original impl/lookup-resources]
      (eacl/write-schema! client arrow-schema)
      @(d/transact conn [{:eacl/id "alice"}
                         {:eacl/id "bob"}
                         {:eacl/id "account"}
                         {:eacl/id "server"}])
      (eacl/create-relationships! client [owner-rel account-rel])
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query]
                      (swap! calls inc)
                      (original db internal-query))]
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (eacl/create-relationship!
         client
         (->Relationship bob :auditor account))
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (is (= 1 @calls)
            "an unrelated relation leaves the arrow permission page hot")

        (eacl/delete-relationship! client owner-rel)
        (is (empty? (:data (eacl/lookup-resources client query))))
        (is (= 2 @calls)
            "a target-permission relation invalidates the arrow lookup")

        (eacl/create-relationship! client owner-rel)
        (is (= ["server"]
               (mapv :id (:data (eacl/lookup-resources client query)))))
        (eacl/delete-relationship! client account-rel)
        (is (empty? (:data (eacl/lookup-resources client query))))
        (is (= 4 @calls)
            "the arrow's source relation is also a dependency")))))

(deftest cached-pages-store-eids-and-reapply-current-id-coercion-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:cache {:live-lookups? true}})
          original impl/lookup-resources
          calls (atom 0)]
      (seed-direct! conn client)
      (with-redefs [impl/lookup-resources
                    (fn [db internal-query]
                      (swap! calls inc)
                      (original db internal-query))]
        (is (= ["a-1"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       {:subject (spice-object :user "alice")
                        :permission :admin
                        :resource/type :account})))))
        @(d/transact conn [{:db/id [:eacl/id "a-1"]
                            :eacl/id "renamed-account"}
                           {:db/id [:eacl/id "alice"]
                            :eacl/id "renamed-alice"}])
        (is (= ["renamed-account"]
               (mapv :id
                     (:data
                      (eacl/lookup-resources
                       client
                       {:subject (spice-object :user "renamed-alice")
                        :permission :admin
                        :resource/type :account})))))
        (is (= 1 @calls)
            "the internal-EID page remains valid while ID coercion observes the current db")))))

(deftest disabled-and-failing-caches-use-the-indexed-path-test
  (with-mem-conn [conn schema/v7-schema]
    (let [disabled (core/make-client conn {:cache false})
          broken-store
          (reify cache/CacheStore
            (lookup [_ _] (throw (ex-info "unavailable" {})))
            (store! [_ _ _ _ _] (throw (ex-info "unavailable" {})))
            (evict! [_ _] nil)
            (clear! [_] nil)
            (stats [_] {}))
          failing (core/make-client conn {:cache {:store broken-store
                                                  :live-lookups? true}})
          query {:subject (spice-object :user "alice")
                 :permission :admin
                 :resource/type :account}]
      (seed-direct! conn disabled)
      (testing "both clients return the same correct data"
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources disabled query)))))
        (is (= ["a-1"] (mapv :id (:data (eacl/lookup-resources failing query)))))))))

(deftest cache-config-is-validated-test
  (with-mem-conn [conn schema/v7-schema]
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache :yes})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:unknown true}})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (core/make-client conn {:cache {:ttl-ms 0}})))))
