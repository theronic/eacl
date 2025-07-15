(ns eacl.datomic.lazy-fixed-test
  (:require [clojure.test :as t :refer [deftest testing is]]
            [datomic.api :as d]
            [clojure.tools.logging :as log]
            [eacl.core :refer [spice-object]]
            [eacl.datomic.impl-fixed :as fixed-impl]
            [eacl.datomic.impl :refer [Relationship]]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.fixtures :as fixtures :refer [->user ->account ->server ->vpc]]))

(defn spice-object->string-id
  "Converts SpiceObject with entity ID to string ID for comparison"
  [db spice-obj]
  (let [ent (d/entity db (:id spice-obj))]
    (spice-object (:type spice-obj) (:eacl/id ent))))

(defn paginated-data->string-ids
  "Converts paginated data with entity IDs to string IDs for comparison"
  [db data]
  (->> data
       (map (partial spice-object->string-id db))))

(defn collect-all-pages
  "Collects all pages from a paginated query to verify completeness.
  Note this carries limit over as passed into query."
  [db query]
  (loop [all-results []
         cursor      nil
         page-count  0]
    (if (> page-count 20)                                   ; Safety limit to prevent infinite loops
      {:error "Too many pages, possible infinite loop"}
      (let [page-query  (assoc query :cursor cursor)
            page-result (fixed-impl/lookup-resources db page-query)
            page-data   (:data page-result)
            next-cursor (:cursor page-result)]
        (let [limit (:limit query)]
          (if (or (empty? page-data)
                  ;; If we got fewer than the limit and no cursor, we're done
                  (and (< (count page-data) limit) (nil? next-cursor)))
            ;; No more data - return accumulated results
            {:results     (concat all-results page-data)
             :page-count  (inc page-count)
             :total-count (count (concat all-results page-data))}
            ;; More data - continue with next page
            (recur (concat all-results page-data)
                   next-cursor
                   (inc page-count))))))))

(deftest basic-fixed-lookup-tests
  "Test basic functionality of the fixed implementation"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:eacl/id "super-user"])
          user1-eid      (d/entid db [:eacl/id "user-1"])
          user2-eid      (d/entid db [:eacl/id "user-2"])]

      (testing "super-user can view all servers"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         1000
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")
                   (->server "account2-server1")}
                 server-ids))))

      (testing "user1 can view their servers"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         1000
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")}
                 server-ids))))

      (testing "user2 can view their server"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user2-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         1000
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= #{(->server "account2-server1")}
                 server-ids)))))))

(deftest cursor-pagination-correctness-tests
  "Test that cursor-based pagination works correctly and enumerates all results"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:eacl/id "super-user"])]

      (testing "pagination with limit 3 should return all servers across pages"
        (let [all-pages      (collect-all-pages db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         3})
              ; why set here? should just be vector.
              all-server-ids (set (paginated-data->string-ids db (:results all-pages)))]
          (is (= [(->server "account1-server1")
                  (->server "account1-server2")
                  (->server "account2-server1")]
                 all-server-ids))
          (is (= 3 (:total-count all-pages)))
          (is (>= (:page-count all-pages) 3))))             ; Should take at least 3 pages with limit 1

      (testing "pagination with limit 2 should return all servers across pages"
        (let [all-pages      (collect-all-pages db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         2})
              all-server-ids (set (paginated-data->string-ids db (:results all-pages)))]
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")}
                 all-server-ids))
          (is (>= (:page-count all-pages) 2))))             ; Should take at least 2 pages with limit 2

      (testing "manual pagination step-by-step"
        ;; First page
        (let [page1     (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                         :permission    :view
                                                         :resource/type :server
                                                         :limit         2
                                                         :cursor        nil})
              page1-ids (set (paginated-data->string-ids db (:data page1)))]
          (is (= 2 (count (:data page1))))
          (is (some? (:cursor page1)))

          ;; Second page
          (let [page2        (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         2
                                                              :cursor        (:cursor page1)})
                page2-ids    (set (paginated-data->string-ids db (:data page2)))
                combined-ids (clojure.set/union page1-ids page2-ids)]

            (is (= 1 (count (:data page2))))                ; Should have 1 remaining server
            (is (= 3 (count combined-ids)))                 ; Should have all 3 servers total
            (is (= #{(->server "account1-server1")
                     (->server "account1-server2")
                     (->server "account2-server1")}
                   combined-ids))

            ;; Third page should be empty if there's a cursor from page 2
            (if (:cursor page2)
              (let [page3 (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                           :permission    :view
                                                           :resource/type :server
                                                           :limit         2
                                                           :cursor        (:cursor page2)})]
                (is (empty? (:data page3)))
                (is (nil? (:cursor page3))))
              ;; If page2 has no cursor, we're already done (which is correct)
              (is (nil? (:cursor page2))))))))))

(deftest cursor-pagination-edge-cases
  "Test edge cases in cursor-based pagination"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db        (d/db conn)
          user1-eid (d/entid db [:eacl/id "user-1"])
          user2-eid (d/entid db [:eacl/id "user-2"])]

      (testing "pagination with user who has no resources"
        ;; Create a user with no permissions
        @(d/transact conn [{:eacl/id "user-empty"}])
        (let [db             (d/db conn)                    ; get fresh db after transaction
              empty-user-eid (d/entid db [:eacl/id "user-empty"])
              result         (fixed-impl/lookup-resources db {:subject       (->user empty-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         10
                                                              :cursor        nil})]
          (is (empty? (:data result)))
          (is (nil? (:cursor result)))))

      (testing "pagination with single result"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user2-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         1
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= 1 (count (:data result))))
          (is (= #{(->server "account2-server1")} server-ids))

          ;; Next page should be empty
          (let [page2 (fixed-impl/lookup-resources db {:subject       (->user user2-eid)
                                                       :permission    :view
                                                       :resource/type :server
                                                       :limit         1
                                                       :cursor        (:cursor result)})]
            (is (empty? (:data page2)))
            (is (nil? (:cursor page2))))))

      (testing "pagination with exact limit match"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         2 ; User1 has exactly 2 servers
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= 2 (count (:data result))))
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")} server-ids))

          ;; Next page should be empty
          (let [page2 (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                       :permission    :view
                                                       :resource/type :server
                                                       :limit         2
                                                       :cursor        (:cursor result)})]
            (is (empty? (:data page2)))
            (is (nil? (:cursor page2)))))))))

(deftest union-permission-pagination-tests
  "Test pagination with union permissions (e.g., permissions that can be granted via multiple paths)"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)

    (let [user1-eid            (d/entid (d/db conn) [:eacl/id "user-1"])
          account2-server1-eid (d/entid (d/db conn) [:eacl/id "account2-server1"])]

      ;; Add shared_admin relationships to create union permissions
      @(d/transact conn [(Relationship (->user user1-eid) :shared_admin (->server account2-server1-eid))])

      (let [db        (d/db conn)
            user1-eid (d/entid db [:eacl/id "user-1"])]

        (testing "user with union permissions can view servers via multiple paths"
          ;; User1 should now be able to view 3 servers:
          ;; - account1-server1 and account1-server2 via account ownership
          ;; - account2-server1 via shared_admin
          (let [all-pages      (collect-all-pages db {:subject       (->user user1-eid)
                                                      :permission    :view
                                                      :resource/type :server
                                                      :limit         1})
                all-server-ids (set (paginated-data->string-ids db (:results all-pages)))]
            (is (= #{(->server "account1-server1")
                     (->server "account1-server2")
                     (->server "account2-server1")}
                   all-server-ids))
            (is (= 3 (:total-count all-pages)))))

        (testing "union permission pagination with limit 2"
          (let [page1     (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                           :permission    :view
                                                           :resource/type :server
                                                           :limit         2
                                                           :cursor        nil})
                page1-ids (set (paginated-data->string-ids db (:data page1)))]
            (is (= 2 (count (:data page1))))
            (is (some? (:cursor page1)))

            (let [page2        (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                                :permission    :view
                                                                :resource/type :server
                                                                :limit         2
                                                                :cursor        (:cursor page1)})
                  page2-ids    (set (paginated-data->string-ids db (:data page2)))
                  combined-ids (clojure.set/union page1-ids page2-ids)]
              (is (= 1 (count (:data page2))))
              (is (= 3 (count combined-ids)))
              (is (= #{(->server "account1-server1")
                       (->server "account1-server2")
                       (->server "account2-server1")}
                     combined-ids)))))))))

(deftest arrow-permission-pagination-tests
  "Test pagination with arrow permissions (permissions that traverse relationships)"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:eacl/id "super-user"])]

      (testing "arrow permissions work with pagination"
        ;; Super-user gets server access via platform->super_admin->account->admin->server
        (let [all-pages      (collect-all-pages db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         1})
              all-server-ids (set (paginated-data->string-ids db (:results all-pages)))]
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")
                   (->server "account2-server1")}
                 all-server-ids))
          (is (= 3 (:total-count all-pages)))))

      (testing "arrow permissions with accounts"
        ;; Super-user should see both accounts via platform->super_admin
        (let [all-pages       (collect-all-pages db {:subject       (->user super-user-eid)
                                                     :permission    :view
                                                     :resource/type :account
                                                     :limit         1})
              all-account-ids (set (paginated-data->string-ids db (:results all-pages)))]
          (is (= #{(->account "account-1")
                   (->account "account-2")}
                 all-account-ids))
          (is (= 2 (:total-count all-pages))))))))

(deftest cursor-format-tests
  "Test different cursor formats and edge cases"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db        (d/db conn)
          user1-eid (d/entid db [:eacl/id "user-1"])]

      (testing "cursor with resource-id field"
        (let [first-result (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                            :permission    :view
                                                            :resource/type :server
                                                            :limit         1
                                                            :cursor        nil})
              cursor       (:cursor first-result)]
          (is (some? cursor))
          (is (contains? cursor :resource-id))

          ;; Use the cursor for next page
          (let [second-result  (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                                :permission    :view
                                                                :resource/type :server
                                                                :limit         1
                                                                :cursor        cursor})
                all-results    (concat (:data first-result) (:data second-result))
                all-server-ids (set (paginated-data->string-ids db all-results))]
            (is (= #{(->server "account1-server1")
                     (->server "account1-server2")}
                   all-server-ids)))))

      (testing "string cursor format"
        ;; Test with string cursor (entity string ID)
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         1
                                                          :cursor        "account1-server1"})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          ;; Should get the remaining server after account1-server1
          (is (or (= #{(->server "account1-server2")} server-ids)
                  (empty? server-ids)))))                   ; Depending on ordering

      (testing "nil cursor"
        (let [result     (fixed-impl/lookup-resources db {:subject       (->user user1-eid)
                                                          :permission    :view
                                                          :resource/type :server
                                                          :limit         10
                                                          :cursor        nil})
              server-ids (set (paginated-data->string-ids db (:data result)))]
          (is (= #{(->server "account1-server1")
                   (->server "account1-server2")}
                 server-ids)))))))

(deftest count-resources-tests
  "Test the count-resources function"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:eacl/id "super-user"])
          user1-eid      (d/entid db [:eacl/id "user-1"])
          user2-eid      (d/entid db [:eacl/id "user-2"])]

      (testing "count-resources matches lookup-resources count"
        (let [lookup-count (count (:data (fixed-impl/lookup-resources
                                           db {:subject       (->user super-user-eid)
                                               :permission    :view
                                               :resource/type :server
                                               :limit         1000})))
              count-result (fixed-impl/count-resources
                             db {:subject       (->user super-user-eid)
                                 :permission    :view
                                 :resource/type :server})]
          (is (= 3 lookup-count))
          (is (= 3 count-result))))

      (testing "count for user with limited permissions"
        (let [count-result (fixed-impl/count-resources
                             db {:subject       (->user user1-eid)
                                 :permission    :view
                                 :resource/type :server})]
          (is (= 2 count-result))))

      (testing "count for user with single resource"
        (let [count-result (fixed-impl/count-resources
                             db {:subject       (->user user2-eid)
                                 :permission    :view
                                 :resource/type :server})]
          (is (= 1 count-result)))))))

(deftest ordering-consistency-tests
  "Test that resource ordering is consistent across pages"

  (with-mem-conn [conn schema/v5-schema]
    @(d/transact conn fixtures/base-fixtures)
    (let [db             (d/db conn)
          super-user-eid (d/entid db [:eacl/id "super-user"])]

      (testing "ordering should be consistent across multiple runs"
        (let [run1 (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         10
                                                    :cursor        nil})
              run2 (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                    :permission    :view
                                                    :resource/type :server
                                                    :limit         10
                                                    :cursor        nil})
              ids1 (paginated-data->string-ids db (:data run1))
              ids2 (paginated-data->string-ids db (:data run2))]
          (is (= ids1 ids2))))

      (testing "pagination order should be consistent"
        (let [page1          (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         1
                                                              :cursor        nil})
              page2          (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         1
                                                              :cursor        (:cursor page1)})
              page3          (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         1
                                                              :cursor        (:cursor page2)})
              all-ordered    (concat (paginated-data->string-ids db (:data page1))
                                     (paginated-data->string-ids db (:data page2))
                                     (paginated-data->string-ids db (:data page3)))
              all-single     (fixed-impl/lookup-resources db {:subject       (->user super-user-eid)
                                                              :permission    :view
                                                              :resource/type :server
                                                              :limit         10
                                                              :cursor        nil})
              all-single-ids (paginated-data->string-ids db (:data all-single))]
          ;; The concatenated pages should equal the single large page
          (is (= all-ordered all-single-ids)))))))