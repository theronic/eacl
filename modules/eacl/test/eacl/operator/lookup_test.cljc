(ns eacl.operator.lookup-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.impl :as datascript-impl]
            [eacl.datascript.schema :as datascript-schema]
            [eacl.execution :as execution]
            [eacl.operator.lookup :as lookup]
            [eacl.operator.plan :as plan]
            [eacl.operator.seekable :as seekable]))

(def schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission view = (reader & writer) - banned
   }")

(def exact-child-order-schema
  "definition user {}
   definition document {
     relation a: user
     relation b: user
     relation c: user
     relation d: user
     permission view = (a - b) + (c & d)
   }")

(def arrow-schema
  "definition user {}
   definition group {
     relation member: user
     relation disabled: user
     permission active = member - disabled
   }
   definition document {
     relation reader: user
     relation parent: group
     permission view = reader & parent->active
   }")

(def direct-exclusion-schema
  "definition user {}
   definition document {
     relation reader: user
     relation banned: user
     permission view = reader - banned
   }")

(def direct-nary-schema
  "definition user {}
   definition document {
     relation a_dense: user
     relation b_identity: user
     relation z_sparse: user
     permission view = a_dense & b_identity & z_sparse
   }")

(defn- object [type id]
  (eacl/spice-object type [:eacl/id id]))

(defn- fixture []
  (let [conn (datascript/create-conn)
        users (mapv #(object :user (str "u" %)) (range 4))
        documents (mapv #(object :document (str "d" %)) (range 16))
        objects (into users documents)
        u0 (nth users 0)
        d2 (nth documents 2)
        u0-relationships
        (mapcat
         (fn [[index document]]
           (cond-> [(eacl/->Relationship u0 :reader document)]
             (even? index)
             (conj (eacl/->Relationship u0 :writer document))
             (zero? (mod index 4))
             (conj (eacl/->Relationship u0 :banned document))))
         (map-indexed vector documents))
        reverse-relationships
        (concat
         (mapcat (fn [user]
                   [(eacl/->Relationship user :reader d2)
                    (eacl/->Relationship user :writer d2)])
                 (subvec users 1 4))
         [(eacl/->Relationship (nth users 3) :banned d2)])]
    (datascript-schema/write-schema! conn schema)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  objects))
    (doseq [relationship (concat u0-relationships reverse-relationships)]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)
          eid #(ds/entid db (:id %))
          adapter (datascript-backend/basis-adapter db {})]
      {:adapter adapter
       :plan (plan/seal-plan adapter [:document :view])
       :users users
       :documents documents
       :eid eid})))

(defn- page-values [page]
  (mapv :value (:emissions page)))

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(defn- custom-fixture [schema-source objects relationships]
  (let [conn (datascript/create-conn)]
    (datascript-schema/write-schema! conn schema-source)
    (ds/transact!
     conn
     (map-indexed (fn [index value]
                    {:db/id (- (inc index))
                     :eacl/id (second (:id value))})
                  objects))
    (doseq [relationship relationships]
      (ds/transact!
       conn
       (datascript-impl/tx-update-relationship
        (ds/db conn) {:operation :touch :relationship relationship})))
    (let [db (ds/db conn)]
      {:adapter (datascript-backend/basis-adapter db {})
       :eid #(ds/entid db (:id %))})))

(defn- drain-pages [options]
  (loop [boundary nil values [] steps 0]
    (when (> steps 128)
      (throw (ex-info "Operator pagination did not make bounded progress."
                      {:type :test/no-progress})))
    (let [page (lookup/lookup-page
                (cond-> options boundary (assoc :boundary boundary)))
          values (into values (page-values page))]
      (if (:has-more? page)
        (recur (:resume-coords page) values (inc steps))
        values))))

(deftest forward-reverse-order-and-counts-are-exact-test
  (let [{:keys [adapter plan users documents eid]} (fixture)
        forward {:adapter adapter :plan plan :traversal :forward
                 :subject-type :user :anchor-eid (eid (first users))}
        reverse-options {:adapter adapter :plan plan :traversal :reverse
                         :subject-type :user
                         :anchor-eid (eid (nth documents 2))}
        expected-forward (mapv #(eid (nth documents %)) (range 2 16 4))
        expected-reverse (mapv eid (subvec users 0 3))
        asc-forward (page-values
                     (lookup/lookup-page (assoc forward :page-size 64)))
        desc-forward (page-values
                      (lookup/lookup-page
                       (assoc forward :page-size 64
                              :order-direction :desc)))]
    (is (= expected-forward asc-forward))
    (is (= (vec (reverse expected-forward)) desc-forward))
    (is (= expected-reverse
           (page-values
            (lookup/lookup-page
             (assoc reverse-options :page-size 64)))))
    (is (= {:count 4 :limit -1 :truncated? false :exhaustive? true}
           (select-keys (lookup/count-results forward)
                        [:count :limit :truncated? :exhaustive?])))
    (is (= {:count 2 :limit 2 :truncated? true :exhaustive? false}
           (select-keys (lookup/count-results
                         (assoc forward :count-limit 2))
                        [:count :limit :truncated? :exhaustive?])))))

(deftest resumed-and-bounded-pages-compose-to-uninterrupted-order-test
  (let [{:keys [adapter plan users eid]} (fixture)
        base {:adapter adapter :plan plan :traversal :forward
              :subject-type :user :anchor-eid (eid (first users))}
        uninterrupted (page-values
                       (lookup/lookup-page (assoc base :page-size 64)))
        paged (drain-pages (assoc base :page-size 1))
        bounded (drain-pages
                 (assoc base :page-size 1 :candidate-window 1))]
    (is (= uninterrupted paged))
    (is (= uninterrupted bounded))))

(deftest sentinel-overread-does-not-advance-logical-resume-test
  (let [{:keys [adapter plan users eid]} (fixture)
        options {:adapter adapter :plan plan :traversal :forward
                 :subject-type :user :anchor-eid (eid (first users))
                 :page-size 1}
        first-page (lookup/lookup-page options)
        second-page (lookup/lookup-page
                     (assoc options :boundary
                            (:resume-coords first-page)))]
    (is (= 1 (count (:emissions first-page))))
    (is (pos? (:physical-overread first-page)))
    (is (not= (:resume-coords first-page)
              (:last-examined-coords first-page)))
    (is (not= (page-values first-page) (page-values second-page)))
    (is (= (drain-pages options)
           (into (page-values first-page)
                 (drain-pages
                  (assoc options :boundary
                         (:resume-coords first-page))))))))

(deftest union-order-uses-exact-child-not-raw-child-cover-test
  (let [user (object :user "u")
        x (object :document "x")
        y (object :document "y")
        z (object :document "z")
        env
        (custom-fixture
         exact-child-order-schema [user x y z]
         [(eacl/->Relationship user :a x)
          (eacl/->Relationship user :b x)
          (eacl/->Relationship user :c x)
          (eacl/->Relationship user :d x)
          (eacl/->Relationship user :a y)
          (eacl/->Relationship user :c z)
          (eacl/->Relationship user :d z)])
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        page
        (lookup/lookup-page
         {:adapter (:adapter env) :plan operator-plan
          :traversal :forward :subject-type :user
          :anchor-eid ((:eid env) user) :page-size 10})]
    ;; x occurs in the early exclusion's RAW a-cover but is rejected there.
    ;; It therefore belongs at the later exact intersection derivation, after
    ;; y, rather than being incorrectly emitted at a's earlier coordinates.
    (is (= (mapv (:eid env) [y x z]) (page-values page)))
    (is (= [4 5 5]
           (mapv #(first (:coords %)) (:emissions page))))))

(deftest arrows-consume-an-exact-operator-child-generator-test
  (let [u1 (object :user "u1")
        u2 (object :user "u2")
        g1 (object :group "g1")
        g2 (object :group "g2")
        d1 (object :document "d1")
        d2 (object :document "d2")
        env
        (custom-fixture
         arrow-schema [u1 u2 g1 g2 d1 d2]
         [(eacl/->Relationship u1 :reader d1)
          (eacl/->Relationship u1 :reader d2)
          (eacl/->Relationship u1 :member g1)
          (eacl/->Relationship u1 :disabled g1)
          (eacl/->Relationship u1 :member g2)
          (eacl/->Relationship u2 :member g1)
          (eacl/->Relationship g1 :parent d1)
          (eacl/->Relationship g2 :parent d2)])
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        page (fn [options]
               (page-values
                (lookup/lookup-page
                 (merge {:adapter (:adapter env) :plan operator-plan
                         :subject-type :user :page-size 10}
                        options))))]
    (is (= [((:eid env) d2)]
           (page {:traversal :forward :anchor-eid ((:eid env) u1)})))
    (is (= []
           (page {:traversal :reverse :anchor-eid ((:eid env) d1)})))
    (is (= [((:eid env) u1)]
           (page {:traversal :reverse :anchor-eid ((:eid env) d2)})))))

(deftest bounded-count-starts-from-result-demand-test
  (let [{:keys [adapter plan users eid]} (fixture)
        widths (atom [])
        original-page seekable/page]
    (with-redefs [seekable/page
                  (fn [options]
                    (swap! widths conj (:width options))
                    (original-page options))]
      (is (= {:count 0 :limit 0 :truncated? true :exhaustive? false}
             (select-keys
              (lookup/count-results
               {:adapter adapter :plan plan :traversal :forward
                :subject-type :user :anchor-eid (eid (first users))
                :count-limit 0})
              [:count :limit :truncated? :exhaustive?]))))
    (is (= 1 (first @widths))
        "a zero-limit bounded count asks only for its one lookahead result")))

(deftest cancellation-deadline-and-fetch-cut-points-fail-atomically-test
  (let [{:keys [adapter plan users eid]} (fixture)
        options {:adapter adapter :plan plan :traversal :forward
                 :subject-type :user :anchor-eid (eid (first users))
                 :page-size 2}
        token (execution/cancellation-token)
        cancelled-contract
        (execution/normalize {} :lookup-resources
                             {:first 2 :cancellation-token token})]
    (execution/cancel! token)
    (is (= :eacl.execution/cancelled
           (:type
            (error-data
             #(binding [execution/*contract* cancelled-contract]
                (lookup/lookup-page options))))))
    (let [clock (atom 0)
          contract
          (binding [execution/*monotonic-nanos* #(deref clock)]
            (execution/normalize {} :lookup-resources
                                 {:first 2 :timeout-ms 1}))]
      (reset! clock 2000000)
      (is (= :eacl.execution/deadline-exceeded
             (:type
              (error-data
               (fn []
                 (binding [execution/*monotonic-nanos* #(deref clock)
                           execution/*contract* contract]
                   (lookup/lookup-page options))))))))
    (is (= :test/cut-point
           (:type
            (error-data
             #(lookup/lookup-page
               (assoc options :cut-point!
                      (fn [_]
                        (throw (ex-info "cut" {:type :test/cut-point})))))))))))

(deftest seekable-intersection-matches-generic-sequence-and-resume-test
  (let [{:keys [adapter plan users documents eid]} (fixture)
        cases
        [{:traversal :forward :subject-type :user
          :anchor-eid (eid (first users))}
         {:traversal :reverse :subject-type :user
          :anchor-eid (eid (nth documents 2))}]]
    (doseq [case-options cases
            order-direction [:asc :desc]
            page-size [1 2 7]]
      (let [options (merge {:adapter adapter :plan plan
                            :order-direction order-direction
                            :page-size page-size}
                           case-options)
            generic (drain-pages
                     (assoc options :direct-specializations? false))
            specialized (drain-pages options)]
        (is (= generic specialized)
            (str case-options " " order-direction " " page-size))))))

(deftest seekable-exclusion-matches-generic-sequence-and-boundaries-test
  (let [user (object :user "u")
        documents (mapv #(object :document (str "d" %)) (range 24))
        env
        (custom-fixture
         direct-exclusion-schema (into [user] documents)
         (mapcat
          (fn [[index document]]
            (cond-> [(eacl/->Relationship user :reader document)]
              (zero? (mod index 3))
              (conj (eacl/->Relationship user :banned document))))
          (map-indexed vector documents)))
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        base {:adapter (:adapter env) :plan operator-plan
              :traversal :forward :subject-type :user
              :anchor-eid ((:eid env) user)}]
    (doseq [order-direction [:asc :desc]
            page-size [1 5 30]]
      (let [options (assoc base :order-direction order-direction
                           :page-size page-size)]
        (is (= (drain-pages
                (assoc options :direct-specializations? false))
               (drain-pages options)))))))

(deftest max-head-nary-specialization-jumps-over-nonselective-operands-test
  (let [user (object :user "u")
        documents (mapv #(object :document (str "d" %)) (range 60))
        env
        (custom-fixture
         direct-nary-schema (into [user] documents)
         (mapcat
          (fn [[index document]]
            (cond-> [(eacl/->Relationship user :a_dense document)
                     (eacl/->Relationship user :b_identity document)]
              (zero? (mod index 20))
              (conj (eacl/->Relationship user :z_sparse document))))
          (map-indexed vector documents)))
        operator-plan (plan/seal-plan (:adapter env) [:document :view])
        options {:adapter (:adapter env) :plan operator-plan
                 :traversal :forward :subject-type :user
                 :anchor-eid ((:eid env) user)
                 :page-size 2 :candidate-window 200}
        generic-stats (atom {})
        seek-stats (atom {})
        generic
        (binding [lookup/*lookup-stats* generic-stats]
          (lookup/lookup-page
           (assoc options :direct-specializations? false)))
        specialized
        (binding [lookup/*lookup-stats* seek-stats
                  seekable/*seek-stats* seek-stats]
          (lookup/lookup-page options))]
    (is (= (page-values generic) (page-values specialized)))
    (is (< (:anchor-rounds @seek-stats)
           (:logical-candidates @generic-stats)))
    (is (<= (:driver-reseeks @seek-stats)
            (:anchor-rounds @seek-stats)))))

(deftest zero-specialization-demand-opens-no-stream-test
  (is (= {:emissions [] :has-more? nil :exhausted? false
          :counters {:commands 0 :fetched-values 0
                     :stream-opens 0 :emissions 0}}
         (seekable/page {:width 0}))))
