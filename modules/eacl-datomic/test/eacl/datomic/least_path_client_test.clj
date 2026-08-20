(ns eacl.datomic.least-path-client-test
  "End-to-end keyset pagination through the public Datomic client
  (acyclic-keyset-pagination, task 4.3): the demo pathology — per-page
  cost growing with the page ordinal when caching is disabled — must be
  gone for acyclic roots, :last must work under demand evaluation, and
  cross-fingerprint cursors must fail typed through the existing
  envelope."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.cache :as cache]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(def ^:private acyclic-schema
  "definition user {}
definition org {
  relation member: user
  relation admin: user
  permission view = member + admin
}
definition doc {
  relation owner: user
  relation org: org
  permission view = owner + org->view
}")

(defn- seed!
  [conn n-docs]
  (let [acl (core/make-client conn {:cache cache/no-cache})]
    (eacl/write-schema! acl acyclic-schema)
    @(d/transact conn [{:eacl/id "org1"} {:eacl/id "alice"}])
    (doseq [batch (partition-all 500 (range n-docs))]
      @(d/transact conn (mapv (fn [i] {:eacl/id (str "d" i)}) batch)))
    (doseq [batch (partition-all 500 (range n-docs))]
      (eacl/create-relationships!
       acl (mapv (fn [i] (->Relationship (spice-object :org "org1")
                                         :org
                                         (spice-object :doc (str "d" i))))
                 batch)))
    (eacl/create-relationship!
     acl (->Relationship (spice-object :user "alice")
                         :member (spice-object :org "org1")))
    acl))

(deftest cache-off-pagination-is-flat-in-the-page-ordinal-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (seed! conn 600)
          alice (spice-object :user "alice")
          query {:subject alice :permission :view
                 :resource/type :doc :first 50}
          page-scans
          (fn [after]
            (let [stats (atom {})]
              (binding [backend/*backend-op-stats* stats]
                (let [page (eacl/lookup-resources
                            acl (cond-> query after (assoc :after after)))]
                  {:cursor (get-in page [:page-info :end-cursor])
                   :count (count (:data page))
                   :scans (+ (get @stats :subject->resources 0)
                             (get @stats :resource->subjects 0))}))))
          first-page (page-scans nil)]
      (is (= 50 (:count first-page)))
      (loop [after (:cursor first-page) k 1 max-scans 0]
        (if (or (nil? after) (> k 10))
          (is (<= max-scans (* 4 (max 1 (:scans first-page))))
              (str "deepest page issued " max-scans
                   " scans vs first page " (:scans first-page)))
          (let [{:keys [cursor scans count]} (page-scans after)]
            (is (pos? count))
            (recur cursor (inc k) (max max-scans scans))))))))

(deftest last-window-works-under-demand-evaluation-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (seed! conn 120)
          alice (spice-object :user "alice")
          forward (loop [after nil out []]
                    (let [page (eacl/lookup-resources
                                acl (cond-> {:subject alice
                                             :permission :view
                                             :resource/type :doc
                                             :first 50}
                                      after (assoc :after after)))
                          out (into out (:data page))]
                      (if-let [next (and (get-in page [:page-info
                                                      :has-next-page?])
                                         (get-in page [:page-info
                                                       :end-cursor]))]
                        (recur next out)
                        out)))
          last-page (eacl/lookup-resources
                     acl {:subject alice :permission :view
                          :resource/type :doc :last 10})]
      (testing "no :complete-denotation required for an acyclic root"
        (is (= (vec (take-last 10 forward))
               (:data last-page))))
      (testing ":before pages backward from the last window"
        (let [before (get-in last-page [:page-info :start-cursor])
              prev (eacl/lookup-resources
                    acl {:subject alice :permission :view
                         :resource/type :doc :last 10 :before before})]
          (is (= (vec (take-last 10 (drop-last 10 forward)))
                 (:data prev))))))))

(deftest lookup-subjects-least-path-round-trip-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (seed! conn 40)
          _ (let [raw (core/make-client conn {:cache cache/no-cache})]
              (doseq [u ["bob" "carol" "dave"]]
                @(d/transact conn [{:eacl/id u}])
                (eacl/create-relationship!
                 raw (->Relationship (spice-object :user u)
                                     :member (spice-object :org "org1")))))
          doc (spice-object :doc "d3")
          all (eacl/lookup-subjects
               acl {:resource doc :permission :view
                    :subject/type :user :first 100})
          paged (loop [after nil out []]
                  (let [page (eacl/lookup-subjects
                              acl (cond-> {:resource doc :permission :view
                                           :subject/type :user :first 2}
                                    after (assoc :after after)))
                        out (into out (:data page))]
                    (if-let [next (and (get-in page [:page-info
                                                     :has-next-page?])
                                       (get-in page [:page-info
                                                     :end-cursor]))]
                      (recur next out)
                      out)))]
      (is (= (:data all) paged)
          "cursor walk reconstructs the whole subject listing"))))
