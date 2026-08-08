(ns eacl.datahike.enumeration-routing-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [eacl.bench.explorer-fixture :as fixture]
            [eacl.core :as eacl]
            [eacl.datahike.core :as datahike]
            [eacl.engine.v8 :as engine]))

(def small-shape
  (assoc fixture/default-shape
         :accounts 3
         :servers-per-account 24
         :user-1-account-count 2))

(defn- seed-client!
  ([]
   (seed-client! fixture/schema))
  ([schema]
  (let [conn (datahike/create-conn)
        client
        (datahike/make-client
         conn
         {:cache {:remember-answers false}})]
    (eacl/write-schema! client schema)
    (d/transact conn (vec (fixture/object-transactions small-shape)))
    (doseq [batch (fixture/relationship-batches small-shape)]
      (eacl/create-relationships! client batch))
    {:conn conn :client client})))

(defn- observed-page
  [client query]
  (let [acyclic (atom {})
        recursive (atom {})
        page
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* recursive]
          (eacl/lookup-resources client query))]
    {:page page
     :acyclic @acyclic
     :recursive @recursive}))

(defn- observed-subject-page
  [client query]
  (let [acyclic (atom {})
        recursive (atom {})
        page
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* recursive]
          (eacl/lookup-subjects client query))]
    {:page page
     :acyclic @acyclic
     :recursive @recursive}))

(deftest datahike-reverse-continuations-resume-and-isolate-private-state-test
  (let [{:keys [conn client]} (seed-client!)
        query
        {:resource
         (fixture/object :server (fixture/server-id 0 0))
         :permission :view
         :subject/type :user
         :first 2}
        first-page (eacl/lookup-subjects client query)
        page-2
        (observed-subject-page
         client
         (assoc query :after
                (get-in first-page [:page-info :end-cursor])))
        page-3
        (observed-subject-page
         client
         (assoc query :after
                (get-in page-2 [:page :page-info :end-cursor])))
        isolated-client
        (datahike/make-client
         conn
        {:cache {:remember-answers false}})
        replay
        (observed-subject-page
         isolated-client
         (assoc query :after
                (get-in first-page [:page-info :end-cursor])))]
    (testing "continuation hits keep later page work bounded"
      (is (= 1 (get-in page-2 [:acyclic :continuation-hits])))
      (is (= 1 (get-in page-3 [:acyclic :continuation-hits])))
      (is (<= (get-in page-3 [:acyclic :backend-scans])
              (+ 2 (get-in page-2 [:acyclic :backend-scans]))))
      (is (empty? (:recursive page-2)))
      (is (empty? (:recursive page-3))))
    (testing "a cross-client miss replays the authenticated public boundary"
      (is (= (get-in page-2 [:page :data])
             (get-in replay [:page :data])))
      (is (= 1 (get-in replay [:acyclic :continuation-misses])))
      (is (zero?
           (get-in (datahike/cache-stats isolated-client)
                   [:continuations :hits]
                   0))))))

(deftest datahike-acyclic-count-does-not-enter-recursive-engine-test
  (let [{:keys [client]} (seed-client!)
        acyclic (atom {})
        recursive (atom {})
        result
        (binding [engine/*acyclic-work-stats* acyclic
                  engine/*recursive-traversal-stats* recursive]
          (eacl/count-resources
           client
           (fixture/count-query fixture/super-user :view)))]
    (is (= 72 (:count result)))
    (is (= 1 (:routed-acyclic @acyclic)))
    (is (pos? (:backend-scans @acyclic)))
    (is (empty? @recursive))))

(deftest datahike-empty-and-populated-cycle-guards-select-exact-routes-test
  (let [{:keys [client]}
        (seed-client! fixture/recursive-schema)
        query (fixture/count-query fixture/super-user :view)
        empty-guard-result
        (let [acyclic (atom {})
              recursive (atom {})
              result
              (binding [engine/*acyclic-work-stats* acyclic
                        engine/*recursive-traversal-stats* recursive]
                (eacl/count-resources client query))]
          {:result result
           :acyclic @acyclic
           :recursive @recursive})]
    (is (= 72 (get-in empty-guard-result [:result :count])))
    (is (= 1 (get-in empty-guard-result [:acyclic :routed-acyclic])))
    (is (empty? (:recursive empty-guard-result)))
    (eacl/create-relationship!
     client
     (eacl/->Relationship
      (fixture/object :server (fixture/server-id 0 0))
      :parent
      (fixture/object :server (fixture/server-id 0 1))))
    (let [acyclic (atom {})
          recursive (atom {})
          result
          (binding [engine/*acyclic-work-stats* acyclic
                    engine/*recursive-traversal-stats* recursive]
            (eacl/count-resources
             client
             (assoc query :count-limit 1)))]
      (is (= {:count 1 :limit 1 :truncated? true}
             (select-keys result [:count :limit :truncated?])))
      (is (empty? @acyclic))
      (is (pos? (:advanced-stream-datoms @recursive 0))))))
