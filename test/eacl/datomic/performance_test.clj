(ns eacl.datomic.performance-test
  "Performance benchmarks for EACL optimizations"
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->server ->account ->vpc]]
            [eacl.core :as eacl :refer [spice-object]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.impl :as impl :refer [Relation Relationship Permission]]
            [eacl.datomic.rules :as original-rules]
            [eacl.datomic.rules-optimized :as optimized-rules]))

(defn measure-time
  "Measure execution time of a function in milliseconds"
  [f]
  (let [start (System/nanoTime)
        result (f)
        end (System/nanoTime)
        duration-ms (/ (- end start) 1000000.0)]
    {:result result
     :time-ms duration-ms}))

(defn generate-test-data
  "Generate test data with specified number of users, accounts, and servers"
  [conn num-users num-accounts num-servers prefix]
  (let [users (for [i (range num-users)]
                {:db/id (str prefix "-user-" i)
                 :entity/id (str prefix "-user-" i)
                 :resource/type :user})
        
        accounts (for [i (range num-accounts)]
                  {:db/id (str prefix "-account-" i)
                   :entity/id (str prefix "-account-" i)
                   :resource/type :account})
        
        servers (for [i (range num-servers)]
                 {:db/id (str prefix "-server-" i)
                  :entity/id (str prefix "-server-" i)
                  :resource/type :server})
        
        ;; Create relationships
        ;; Each user owns an account
        user-account-rels (for [i (range (min num-users num-accounts))]
                            (Relationship (str prefix "-user-" i) :owner (str prefix "-account-" i)))
        
        ;; Each account has servers
        servers-per-account (quot num-servers num-accounts)
        account-server-rels (for [i (range num-accounts)
                                 j (range servers-per-account)]
                             (Relationship (str prefix "-account-" i) :account 
                                         (str prefix "-server-" (+ (* i servers-per-account) j))))]
    
    @(d/transact conn (concat users accounts servers 
                             user-account-rels account-server-rels))))

(defn run-performance-comparison
  "Run performance comparison between original and optimized rules"
  [db test-name query-fn original-rules optimized-rules]
  (println (str "\n" test-name ":"))
  
  ;; Test with original rules
  (let [{:keys [time-ms result]} (measure-time #(query-fn db original-rules))]
    (println (format "  Original: %.2f ms (found %d results)" 
                    time-ms (count result))))
  
  ;; Test with optimized rules
  (let [{:keys [time-ms result]} (measure-time #(query-fn db optimized-rules))]
    (println (format "  Optimized: %.2f ms (found %d results)" 
                    time-ms (count result)))))

(deftest performance-comparison-test
  (testing "Performance comparison between original and optimized rules"
    (with-mem-conn [conn schema/v4-schema]
      ;; Set up base schema
      @(d/transact conn fixtures/base-fixtures)
      
      ;; Generate larger test dataset
      (println "\nGenerating test data...")
      (generate-test-data conn 100 20 500 "perf1")
      
      (let [db (d/db conn)]
        
        ;; Test can? performance
        (run-performance-comparison 
          db "can? check (direct permission)"
          (fn [db rules]
            (d/q '[:find ?subject .
                   :in $ % ?subject ?perm ?resource
                   :where
                   (has-permission ?subject ?perm ?resource)]
                 db rules
                 [:entity/id "perf1-user-0"]
                 :view
                 [:entity/id "perf1-server-0"]))
          original-rules/check-permission-rules
          optimized-rules/check-permission-rules)
        
        ;; Test lookup-subjects performance
        (run-performance-comparison
          db "lookup-subjects (find who can view server-0)"
          (fn [db rules]
            (d/q '[:find [?subject ...]
                   :in $ % ?subject-type ?permission ?resource-eid
                   :where
                   (has-permission ?subject-type ?subject ?permission ?resource-eid)
                   [(not= ?subject ?resource-eid)]]
                 db rules
                 :user
                 :view
                 [:entity/id "perf1-server-0"]))
          original-rules/rules-lookup-subjects
          optimized-rules/rules-lookup-subjects)
        
        ;; Test lookup-resources performance (highest priority)
        (run-performance-comparison
          db "lookup-resources (find servers user-0 can view)"
          (fn [db rules]
            (d/q '[:find [?resource ...]
                   :in $ % ?subject-type ?subject-eid ?permission ?resource-type
                   :where
                   (has-permission ?subject-eid ?permission ?resource-type ?resource)
                   [?resource :resource/type ?resource-type]]
                 db rules
                 :user
                 [:entity/id "perf1-user-0"]
                 :view
                 :server))
          original-rules/rules-lookup-resources
          optimized-rules/rules-lookup-resources)
        
        ;; Test with larger dataset
        (println "\n\nGenerating larger test dataset...")
        (generate-test-data conn 500 100 2000 "perf2")
        (let [db-large (d/db conn)]
          
          (println "\nLarger dataset tests:")
          
          ;; Test lookup-resources with larger dataset
          (run-performance-comparison
            db-large "lookup-resources on larger dataset"
            (fn [db rules]
              (d/q '[:find [?resource ...]
                     :in $ % ?subject-type ?subject-eid ?permission ?resource-type
                     :where
                     (has-permission ?subject-eid ?permission ?resource-type ?resource)
                     [?resource :resource/type ?resource-type]]
                   db rules
                   :user
                   [:entity/id "perf2-user-10"]
                   :view
                   :server))
            original-rules/rules-lookup-resources
            optimized-rules/rules-lookup-resources))))))

(deftest staged-lookup-resources-test
  (testing "Staged lookup-resources implementation"
    (with-mem-conn [conn schema/v4-schema]
      @(d/transact conn fixtures/base-fixtures)
      (generate-test-data conn 100 20 500 "staged")
      
      (let [db (d/db conn)]
        (println "\n\nStaged lookup-resources test:")
        
        ;; Test original implementation
        (let [{:keys [time-ms result]} 
              (measure-time 
                #(impl/lookup-resources db {:resource/type :server
                                          :permission :view
                                          :subject (->user "staged-user-0")
                                          :limit 50}))]
          (println (format "  Full implementation: %.2f ms (found %d results)" 
                          time-ms (count result))))))))

;; Run with: clj -M:test -n eacl.datomic.performance-test 