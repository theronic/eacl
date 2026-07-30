(ns eacl.datomic.recursive-cache-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.impl.indexed :as idx]
            [eacl.datomic.schema :as schema]))

(def ^:private recursive-schema
  "definition user {}
   definition account {
     relation parent: account
     relation reader: user
     permission read = reader + parent->read
   }")

(defn- account-id [n]
  (str "account-" n))

(defn- user-id [n]
  (str "user-" n))

(defn- seed-recursive!
  [conn client account-count user-count]
  (eacl/write-schema! client recursive-schema)
  @(d/transact conn
               (concat
                (for [n (range account-count)]
                  {:eacl/id (account-id n)})
                (for [n (range user-count)]
                  {:eacl/id (user-id n)})))
  (eacl/create-relationships!
   client
   (concat
    (for [n (range (dec account-count))]
      (->Relationship (spice-object :account (account-id n))
                      :parent
                      (spice-object :account (account-id (inc n)))))
    (for [n (range user-count)]
      (->Relationship (spice-object :user (user-id n))
                      :reader
                      (spice-object :account (account-id 0)))))))

(defn- page-end-cursor [page]
  (get-in page [:page-info :end-cursor]))

(defn- page-start-cursor [page]
  (get-in page [:page-info :start-cursor]))

(defn- collect-forward
  [client query]
  (loop [after nil
         data []
         derived 0]
    (let [stats (atom {})
          page (binding [idx/*recursive-traversal-stats* stats]
                 (eacl/lookup-resources
                  client
                  (cond-> query after (assoc :after after))))
          data' (into data (:data page))
          derived' (+ derived (get @stats :derived-grants 0))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (page-end-cursor page) data' derived')
        {:data data' :derived-grants derived'}))))

(deftest forward-recursive-pagination-resumes-and-miss-fails-closed-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-forward-cache"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn cached-client 30 1)
      (let [disabled-client (core/make-client conn {:page-token-key token-key
                                                    :cache false})
            alternate-client (core/make-client conn {:page-token-key token-key})
            page1 (eacl/lookup-resources cached-client query)
            cursor (page-end-cursor page1)
            hit-stats (atom {})
            miss-stats (atom {})
            hit-page2 (binding [idx/*recursive-traversal-stats* hit-stats]
                        (eacl/lookup-resources cached-client (assoc query :after cursor)))
            miss-page2 (binding [idx/*recursive-traversal-stats* miss-stats]
                         (eacl/lookup-resources disabled-client (assoc query :after cursor)))
            retry-stats (atom {})
            previous-stats (atom {})
            previous-page (binding [idx/*recursive-traversal-stats* previous-stats]
                            (eacl/lookup-resources
                             cached-client
                             (-> query
                                 (dissoc :first)
                                 (assoc :last 5
                                        :before (page-start-cursor hit-page2)))))]
        (is (= (mapv account-id (range 0 5))
               (mapv :id (:data page1))))
        (is (= (mapv account-id (range 5 10))
               (mapv :id (:data hit-page2))))
        (is (= (:data hit-page2) (:data miss-page2)))
        (doseq [[label call]
                [["alternate cache"
                  #(eacl/lookup-resources alternate-client
                                          (assoc query :after cursor))]
                 ["consumed continuation retry"
                  #(binding [idx/*recursive-traversal-stats* retry-stats]
                     (eacl/lookup-resources cached-client
                                            (assoc query :after cursor)))]]]
          (try
            (call)
            (is false (str label " should fail closed"))
            (catch clojure.lang.ExceptionInfo e
              (is (= :eacl.pagination/cursor-expired
                     (:type (ex-data e)))
                  label))))
        (is (= (:data page1) (:data previous-page)))
        (is (= 1 (:continuation-hits @hit-stats)))
        (is (nil? (:continuation-hits @miss-stats)))
        (is (= 1 (:continuation-misses @retry-stats))
            "a consumed continuation cannot silently replay")
        (is (= 1 (:recursive-page-hits @previous-stats))
            "back navigation reuses the already produced immutable page")
        (is (< (:derived-grants @hit-stats)
               (:derived-grants @miss-stats))
            "cache hit advances only new work; cache miss replays the prefix")))))

(deftest reverse-recursive-pagination-resumes-test
  (with-mem-conn [conn schema/v7-schema]
    (let [client (core/make-client conn {:page-token-key "recursive-reverse-cache"})
          query {:resource (spice-object :account (account-id 4))
                 :permission :read
                 :subject/type :user
                 :first 4}]
      (seed-recursive! conn client 5 20)
      (let [page1 (eacl/lookup-subjects client query)
            stats (atom {})
            page2 (binding [idx/*recursive-traversal-stats* stats]
                    (eacl/lookup-subjects
                     client
                     (assoc query :after (page-end-cursor page1))))]
        (is (= 4 (count (:data page1))))
        (is (= 4 (count (:data page2))))
        (is (empty? (set/intersection
                     (set (map :id (:data page1)))
                     (set (map :id (:data page2))))))
        (is (= 1 (:continuation-hits @stats)))))))

(deftest historical-cursor-becomes-unavailable-after-relevant-write-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-historical-cache"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 5}]
      (seed-recursive! conn cached-client 10 1)
      (let [replay-client (core/make-client conn {:page-token-key token-key
                                                  :cache false})
            page1 (eacl/lookup-resources cached-client query)
            cursor (page-end-cursor page1)]
        @(d/transact conn [{:eacl/id "new-live-account"}])
        (eacl/create-relationship!
         cached-client
         (->Relationship (spice-object :account (account-id 9))
                         :parent
                         (spice-object :account "new-live-account")))
        (doseq [client [cached-client replay-client]]
          (try
            (eacl/lookup-resources client (assoc query :after cursor))
            (is false "a changed relationship proof cannot fall forward")
            (catch clojure.lang.ExceptionInfo e
              (is (= :eacl.consistency/snapshot-unavailable
                     (:type (ex-data e)))))))))))

(deftest oversized-continuation-is-rejected-and-cursor-expires-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-rejected-cache"
          client (core/make-client
                  conn
                  {:page-token-key token-key
                   :cache {:max-weight 4096
                           :max-entry-weight 1
                           :max-entries 4}})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 3}]
      (seed-recursive! conn client 12 1)
      (let [page1 (eacl/lookup-resources client query)
            stats (atom {})]
        (try
          (binding [idx/*recursive-traversal-stats* stats]
            (eacl/lookup-resources
             client
             (assoc query :after (page-end-cursor page1))))
          (is false "a rejected continuation must not silently replay")
          (catch clojure.lang.ExceptionInfo e
            (is (= :eacl.pagination/cursor-expired
                   (:type (ex-data e))))))
        (is (= 1 (:continuation-misses @stats)))))))

(deftest complete-recursive-enumeration-is-linear-on-continuation-hits-test
  (with-mem-conn [conn schema/v7-schema]
    (let [token-key "recursive-linear-walk"
          cached-client (core/make-client conn {:page-token-key token-key})
          query {:subject (spice-object :user (user-id 0))
                 :permission :read
                 :resource/type :account
                 :first 10}]
      (seed-recursive! conn cached-client 80 1)
      (let [disabled-client (core/make-client conn {:page-token-key token-key
                                                    :cache false})
            cached (collect-forward cached-client query)
            replayed (collect-forward disabled-client query)]
        (is (= (:data replayed) (:data cached)))
        (is (= 80 (count (:data cached))))
        (is (< (:derived-grants cached)
               (/ (:derived-grants replayed) 2))
            "cached traversal advances near-linearly while disabled mode remains correct")))))
