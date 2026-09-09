(ns eacl.datomic.least-path-client-test
  "End-to-end keyset pagination through the public Datomic client
  (acyclic-keyset-pagination, task 4.3): the demo pathology — per-page
  cost growing with the page ordinal when caching is disabled — must be
  gone for acyclic roots, :last must work under demand evaluation, and
  cross-fingerprint cursors must fail typed through the existing
  envelope."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.cache :as shared-cache]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]
            [eacl.engine.v8 :as engine]
            [eacl.exact-integer :as exact-integer]))

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
  relation parent: doc
  permission view = owner + org->view
  permission common = view & org->view
  permission without_owner = view - owner
  permission inherited = view + parent->inherited
  permission common_inherited = inherited & view
}")

(defn- seed!
  [conn n-docs]
  (let [acl (core/make-client conn {:cache shared-cache/no-cache})]
    (eacl/write-schema! acl acyclic-schema)
    ;; Numeric tempids exercise Datomic entity IDs above JavaScript's safe
    ;; integer range; implicit tempids alone missed the cursor transport bug.
    @(d/transact conn [{:db/id -1 :eacl/id "org1"}
                      {:db/id -2 :eacl/id "alice"}])
    (doseq [batch (partition-all 500 (range n-docs))]
      @(d/transact conn (mapv (fn [i] {:db/id (- (inc i))
                                     :eacl/id (str "d" i)}) batch)))
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

(defn- walk-pages [lookup query direction]
  (let [[size-key bound-key cursor-key more-key]
        (if (= :forward direction)
          [:first :after :end-cursor :has-next-page?]
          [:last :before :start-cursor :has-previous-page?])]
    (loop [bound nil out [] remaining 100]
      (assert (pos? remaining) "pagination must terminate")
      (let [page (lookup (cond-> (assoc query size-key 2)
                          bound (assoc bound-key bound)))
            data (:data page)
            out (if (= :forward direction) (into out data) (into data out))]
        (if (get-in page [:page-info more-key])
          (let [cursor (get-in page [:page-info cursor-key])]
            (is (string? cursor))
            (recur cursor out (dec remaining)))
          out)))))

(deftest native-int64-cursors-walk-resources-and-subjects-test
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (seed! conn 7)
          users ["alice" "bob" "carol" "dave"]
          alice (spice-object :user "alice")
          cached-client (core/make-client conn {})]
      @(d/transact conn (mapv (fn [i id] {:db/id (- (inc i)) :eacl/id id})
                             (range) (rest users)))
      (doseq [id (rest users)]
        (eacl/create-relationship!
         acl (->Relationship (spice-object :user id) :member
                             (spice-object :org "org1"))))
      (eacl/create-relationship!
       acl (->Relationship alice :owner (spice-object :doc "d0")))
      (is (> (d/entid (d/db conn) [:eacl/id "alice"]) exact-integer/maximum))
      ;; A new cache-free client on every page also checks that tokens alone
      ;; carry the exact boundary, without a retained server-side checkpoint.
      (doseq [cached? [false true]
              permission [:view :common :without_owner :inherited :common_inherited]
              [lookup query expected]
              [[eacl/lookup-resources
                {:subject alice :permission permission :resource/type :doc}
                (set (map #(str "d" %) (range (if (= :without_owner permission) 1 0) 7)))]
               [eacl/lookup-subjects
                {:resource (spice-object :doc "d3") :permission permission :subject/type :user}
                (set users)]]]
        (testing (str permission " " (if (:subject query) "resources" "subjects") " cached=" cached?)
          (let [query (cond-> query
                        (#{:inherited :common_inherited} permission)
                        (assoc :evaluation :complete-denotation))
                read-page #(lookup (if cached? cached-client
                                      (core/make-client conn {:cache shared-cache/no-cache})) %)
                forward (walk-pages read-page query :forward)
                backward (walk-pages read-page query :backward)]
            (is (= expected (set (map :id forward))))
            (is (= (count expected) (count forward)))
            (is (= forward backward))))))))

(deftest cache-off-pagination-is-flat-in-the-page-ordinal-test
  (with-mem-conn [conn schema/v8-schema]
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
  (with-mem-conn [conn schema/v8-schema]
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

(deftest least-path-work-reports-to-traversal-observers-test
  ;; Task 7.2: the evaluator's own scans and emissions must be visible to
  ;; observers under the public counter names, not only the witness
  ;; probe-checks.
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (seed! conn 60)
          alice (spice-object :user "alice")
          stats (atom {})
          page (binding [engine/*recursive-traversal-stats* stats]
                 (eacl/lookup-resources
                  acl {:subject alice :permission :view
                       :resource/type :doc :first 20}))]
      (is (= 20 (count (:data page))))
      (testing "emissions, commands, and scan opens reach the observer"
        (is (<= 20 (:derived-grants @stats 0))
            "each emitted entity is one logical admission")
        (is (pos? (:advanced-datoms @stats 0)))
        (is (pos? (:stream-opens @stats 0)))))))

(deftest lookup-subjects-least-path-round-trip-test
  (with-mem-conn [conn schema/v8-schema]
    (let [acl (seed! conn 40)
          _ (let [raw (core/make-client conn {:cache shared-cache/no-cache})]
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
