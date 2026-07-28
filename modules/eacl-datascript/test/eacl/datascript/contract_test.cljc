(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as impl]))

(defn- seed-objects!
  [conn]
  (ds/transact! conn
                (map-indexed (fn [idx {:keys [id]}]
                               {:db/id (- (inc idx))
                                :eacl/id id})
                             contract/smoke-objects)))

(deftest datascript-contract-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (contract/assert-seeded-contracts! client)))

(defn- seeded-client
  []
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    client))

(defn- thrown-data
  [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) ex
      (ex-data ex))))

(deftest v7-3-parser-hardening-test
  (testing "identifiers that merely start with reserved words remain legal"
    (let [client (datascript/make-client (datascript/create-conn) {})
          schema "definition user {}

                  definition allocation {
                    relation relationship: user
                    permission allowed = relationship
                  }"]
      (eacl/write-schema! client schema)
      (let [{:keys [relations permissions]} (eacl/read-schema client)]
        (is (= #{[:allocation :relationship]}
               (set (map (juxt :eacl.relation/resource-type
                               :eacl.relation/relation-name)
                         relations))))
        (is (= #{[:allocation :allowed]}
               (set (map (juxt :eacl.permission/resource-type
                               :eacl.permission/permission-name)
                         permissions)))))))

  (testing "duplicate permissions fail closed instead of silently unioning"
    (let [client (datascript/make-client (datascript/create-conn) {})
          schema "definition user {}

                  definition document {
                    relation reader: user
                    permission view = reader
                    permission view = reader
                  }"]
      (is (= :eacl.schema/duplicate-permission
             (:type (thrown-data #(eacl/write-schema! client schema))))))))

(deftest v7-3-query-validation-test
  (let [client (seeded-client)]
    (testing "relationship reads require a known anchor and reject broadened scans"
      (is (= :eacl.filters/missing-anchor
             (:eacl/error
              (thrown-data #(eacl/read-relationships client {})))))
      (is (= :eacl.filters/unknown-filter
             (:eacl/error
              (thrown-data #(eacl/read-relationships
                             client
                             {:resource/type :server
                              :resouce/id "typo"})))))
      (is (= :eacl.pagination/unsupported-filter
             (:eacl/error
              (thrown-data #(eacl/read-relationships
                             client
                             {:resource/type :server
                              :resource/id-prefix "server-"}))))))

    (testing "list operations reject consistency and subject filters they cannot honor"
      (is (= :eacl/unsupported-consistency
             (:type
              (thrown-data #(eacl/lookup-resources
                             client
                             {:subject (contract/->user "user-1")
                              :permission :view
                              :resource/type :server
                              :consistency :minimize-latency})))))
      (is (= :eacl.pagination/unsupported-filter
             (:eacl/error
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :subject/relation :member}))))))))

(deftest v7-3-direction-scoped-frontier-test
  (let [client (seeded-client)
        query {:subject (contract/->user "user-1")
               :permission :view
               :resource/type :server
               :limit 1}
        page-1 (eacl/lookup-resources client query)
        cursor-1 (datascript/token->cursor (:cursor page-1))
        page-2 (eacl/lookup-resources client
                                      (assoc query :cursor (:cursor page-1)))
        page-3 (eacl/lookup-resources client
                                      (assoc query :cursor (:cursor page-2)))
        exhausted-path-page
        (eacl/lookup-resources client
                               {:subject (contract/->user "user-1")
                                :permission :admin
                                :resource/type :account
                                :limit 1})
        exhausted-path-cursor
        (datascript/token->cursor (:cursor exhausted-path-page))]
    (testing "frontiers use stable path identities and record exhaustion"
      (is (= [(contract/->server "server-1")] (:data page-1)))
      (is (= [(contract/->server "server-2")] (:data page-2)))
      (is (empty? (:data page-3)))
      (is (= (:cursor page-2) (:cursor page-3)))
      (is (= 1 (:frontier-version cursor-1)))
      (is (= :forward (:frontier-direction cursor-1)))
      (is (every? string? (keys (:p cursor-1))))
      (is (contains? (set (vals (:p exhausted-path-cursor))) :exhausted)))

    (testing "exhausted paths are skipped on later pages"
      (let [calls (atom 0)
            original impl/subject->resources]
        (with-redefs [impl/subject->resources
                      (fn [& args]
                        (swap! calls inc)
                        (apply original args))]
          (eacl/lookup-resources
           client
           {:subject (contract/->user "user-1")
            :permission :admin
            :resource/type :account
            :limit 1
            :cursor (:cursor exhausted-path-page)})
          ;; Only the direct :owner path scans. The exhausted platform arrow is
          ;; pruned before it reaches the backend.
          (is (= 1 @calls)))))

    (testing "a forward cursor cannot be reused for reverse traversal"
      (is (= :wrong-frontier-direction
             (:reason
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :cursor (:cursor page-1)}))))))

    (testing "pre-v7.3 positional cursor frontiers remain resumable"
      (let [legacy-cursor (-> cursor-1
                              (dissoc :frontier-version :frontier-direction)
                              (assoc :p {0 (first (vals (:p cursor-1)))}))
            legacy-page (eacl/lookup-resources
                         client
                         (assoc query :cursor legacy-cursor))]
        (is (= [(contract/->server "server-2")] (:data legacy-page)))))))
