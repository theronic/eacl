(ns eacl.datascript.contract-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.cache :as cache]
            [eacl.contract-support :as contract]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.secure-format :as secure]))

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
    (contract/assert-v8-seeded-contracts! client)
    (contract/assert-v8-cache-disabled!
     (datascript/make-client conn {:cache cache/no-cache}))))

(deftest datascript-recursive-v8-contract-test
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/recursive-schema)
    (ds/transact! conn
                  (map-indexed
                   (fn [index {:keys [id]}]
                     {:db/id (- (inc index))
                      :eacl/id id})
                   contract/recursive-objects))
    (eacl/create-relationships! client contract/recursive-relationships)
    (contract/assert-v8-recursive-contracts! client)
    (doseq [limit-key [:max-derived-grants
                       :max-advanced-datoms
                       :max-queued-work]]
      (contract/assert-v8-recursive-safety-limit!
       (datascript/make-client
        conn
        {:cache cache/no-cache
         :recursive-traversal-limits {limit-key 1}})))))

(deftest datascript-delete-object-contract-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (eacl/create-relationships! client contract/smoke-relationships)
    (eacl/delete-object! client (contract/->user "user-1"))

    (testing "delete-object! removes touching relationships but retains the object"
      (is (some? (ds/entid (ds/db conn) [:eacl/id "user-1"])))
      (is (false? (eacl/can? client
                             (contract/->user "user-1")
                             :reboot
                             (contract/->server "server-1"))))
      (is (= []
             (:data
              (eacl/read-relationships client {:subject/id "user-1"})))))

    (testing "unrelated grants remain intact"
      (is (true? (eacl/can? client
                            (contract/->user "super-user")
                            :reboot
                            (contract/->server "server-1")))))))

(deftest datascript-large-relationship-cursor-proof-test
  (let [relationship-count 1505
        conn (datascript/create-conn)
        client (datascript/make-client conn {})
        user-ids (mapv #(str "bulk-user-" %) (range relationship-count))
        server-ids (mapv #(str "bulk-server-" %) (range relationship-count))
        object-ids (into user-ids server-ids)]
    (eacl/write-schema!
     client
     "definition user {}

      definition server {
        relation owner: user
      }")
    (ds/transact!
     conn
     (map-indexed
      (fn [index object-id]
        {:db/id (- (inc index))
         :eacl/id object-id})
      object-ids))
    (eacl/create-relationships!
     client
     (mapv (fn [index]
             (eacl/->Relationship
              (contract/->user (nth user-ids index))
              :owner
              (contract/->server (nth server-ids index))))
           (range relationship-count)))
    (let [proof-count (atom 0)
          canonical-records-digest secure/canonical-records-digest
          read-page
          (fn [filters]
            (reset! proof-count 0)
            (let [page
                  (with-redefs
                    [secure/canonical-records-digest
                     (fn [& args]
                       (swap! proof-count inc)
                       (apply canonical-records-digest args))]
                    (eacl/read-relationships client filters))]
              [page @proof-count]))
          [page-1 page-1-proof-count]
          (read-page {:subject/type :user
                      :first 1000})
          [page-2 page-2-proof-count]
          (read-page
           {:subject/type :user
            :first 1000
            :after (get-in page-1 [:page-info :end-cursor])})]
      (is (= 1000 (count (:data page-1))))
      (is (= 505 (count (:data page-2))))
      (is (false? (get-in page-2 [:page-info :has-next-page?])))
      (is (= 1 page-1-proof-count))
      (is (= 1 page-2-proof-count)))))

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

    (testing "list operations honor consistency but reject unsupported filters"
      (is (map?
           (eacl/lookup-resources
            client
            {:subject (contract/->user "user-1")
             :permission :view
             :resource/type :server
             :consistency :minimize-latency})))
      (is (= :eacl.pagination/unsupported-filter
             (:eacl/error
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :subject/relation :member}))))))))

(deftest v7-3-empty-first-page-test
  (let [conn   (datascript/create-conn)
        client (datascript/make-client conn {})
        query  {:subject (contract/->user "user-1")
                :permission :view
                :resource/type :server
                :first 100}]
    (eacl/write-schema! client contract/smoke-schema)
    (seed-objects! conn)
    (testing "an empty first page does not mint a boundary-less cursor"
      (let [page (eacl/lookup-resources client query)]
        (is (= [] (:data page)))
        (is (= {:start-cursor nil
                :end-cursor nil
                :has-next-page? false
                :has-previous-page? false}
               (:page-info page))))
      (is (= {:count 0 :limit -1}
             (select-keys
              (eacl/count-resources client (dissoc query :first))
              [:count :limit]))))))

(deftest v7-3-direction-scoped-frontier-test
  (let [client (seeded-client)
        query {:subject (contract/->user "user-1")
               :permission :view
               :resource/type :server
               :first 1}
        page-1 (eacl/lookup-resources client query)
        cursor-1 (get-in page-1 [:page-info :end-cursor])
        envelope (datascript/token->cursor cursor-1)
        page-2 (eacl/lookup-resources client
                                      (assoc query :after cursor-1))
        page-3 (eacl/lookup-resources client
                                      (assoc query
                                             :after
                                             (get-in page-2
                                                     [:page-info :end-cursor])))]
    (testing "v8 cursors retain direction-scoped shared-engine state"
      (is (= [(contract/->server "server-1")] (:data page-1)))
      (is (= [(contract/->server "server-2")] (:data page-2)))
      (is (empty? (:data page-3)))
      (is (= 9 (:v envelope)))
      (is (= :lookup-eid (get-in envelope [:edge :kind])))
      (is (= :asc (get-in envelope [:edge :frontier-direction]))))

    (testing "a forward cursor cannot be reused for reverse traversal"
      (is (= :query-mismatch
             (:reason
              (thrown-data #(eacl/lookup-subjects
                             client
                             {:resource (contract/->server "server-1")
                              :permission :view
                              :subject/type :user
                              :first 1
                              :after cursor-1}))))))))
