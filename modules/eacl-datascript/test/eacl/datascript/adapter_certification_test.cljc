(ns eacl.datascript.adapter-certification-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.adapter-certification :as certification]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as v8]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.datascript.core :as datascript]))

(deftest unmanaged-connection-source-id-is-atomically-attached-test
  (let [conn (ds/create-conn {})
        ids
        #?(:clj
           (let [gate (promise)
                 workers
                 (repeatedly
                  32
                  #(future
                     @gate
                     (datascript-backend/connection-source-id conn)))]
             (deliver gate true)
             (mapv deref workers))
           :cljs
           [(datascript-backend/connection-source-id conn)
            (datascript-backend/connection-source-id conn)])]
    (is (= 1 (count (set ids))))
    (is (= (first ids) (:eacl.datascript/source-id (meta conn))))))

(defn- seed-adapter
  [fixture]
  (let [conn (datascript/create-conn)
        client (datascript/make-client conn {})]
    (eacl/write-schema! client (:schema fixture))
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index))
         :eacl/id id})
      (:objects fixture)))
    (eacl/create-relationships! client (:relationships fixture))
    (let [db (ds/db conn)]
      (datascript-backend/basis-adapter
       db
       {:object-id->entid
        (fn [snapshot object-id]
          (ds/entid snapshot [:eacl/id object-id]))
        :entid->object-id
        (fn [snapshot internal-id]
          (:eacl/id (ds/entity snapshot internal-id)))}))))

(deftest datascript-adapter-certification-test
  (doseq [fixture (certification/coherent-fixtures [820084])]
    (testing (str "seed " (:seed fixture))
      (let [adapter (seed-adapter fixture)
            report
            (certification/certify
             {:adapter adapter
              :fixture fixture
              :runtime #?(:clj :clj :cljs :cljs)})]
        (is (some? (v8/invoke adapter :schema-generation)))
        (is (= :certified-scalar-fallback-v1
               (get-in (v8/operator-capability-identity adapter)
                       [:direct-membership :mode])))
        (is (:passed? report)
            (pr-str (:checks report)))))))

(deftest datascript-ordered-generation-transition-certification-test
  (let [fixture (certification/coherent-fixture 820084)
        conn (datascript/create-conn)
        client (datascript/make-client conn {})
        adapter-for
        (fn []
          (datascript-backend/basis-adapter
           (ds/db conn)
           {:object-id->entid
            (fn [snapshot object-id]
              (ds/entid snapshot [:eacl/id object-id]))
            :entid->object-id
            (fn [snapshot internal-id]
              (:eacl/id (ds/entity snapshot internal-id)))}))]
    (eacl/write-schema! client (:schema fixture))
    (ds/transact!
     conn
     (map-indexed
      (fn [index {:keys [id]}]
        {:db/id (- (inc index)) :eacl/id id})
      (:objects fixture)))
    (eacl/create-relationships! client (:relationships fixture))
    (let [before (adapter-for)
          relation-ids
          (->> (:relations fixture)
               (mapcat
                (fn [{:keys [resource-type relation-name]}]
                  (v8/invoke
                   before :relation-defs resource-type relation-name)))
               (map :relation-id)
               sort
               vec)
          affected
          (:relation-id
           (first (v8/invoke before :relation-defs :group :member)))]
      (eacl/delete-relationship! client (first (:relationships fixture)))
      (is (= :certified
             (:status
              (certification/certify-ordered-generation-transition!
               {:before-adapter before
                :after-adapter (adapter-for)
                :relation-ids relation-ids
                :affected-relation-ids [affected]})))))))

(deftest datascript-live-source-identity-certification-test
  (let [first-conn (datascript/create-conn)
        second-conn (datascript/create-conn)]
    (is (= :certified
           (:status
            (certification/certify-live-source-identity!
             {:backend :datascript
              :durability :non-durable
              :first-scope
              (datascript-backend/database-source-scope (ds/db first-conn))
              :second-scope
              (datascript-backend/database-source-scope
               (ds/db second-conn))}))))))

(deftest current-db-reference-identity-test
  (testing "the exact-basis cache can use immutable DB object identity"
    (let [conn (datascript/create-conn)
          before-1 (ds/db conn)
          before-2 (ds/db conn)
          _ (ds/transact!
             conn
             [{:eacl/id "datascript-reference-identity"}])
          after-1 (ds/db conn)
          after-2 (ds/db conn)]
      (is (identical? before-1 before-2)
          "an unchanged connection must return the same immutable DB object")
      (is (not (identical? before-1 after-1))
          "a committed transaction must replace the immutable DB object")
      (is (identical? after-1 after-2)
          "the replacement remains stable until the next commit"))))

(deftest live-source-reuses-only-the-identical-immutable-basis-adapter-test
  (let [conn (datascript/create-conn)
        live-source
        (datascript-backend/source
         conn
         {:native-source-id :adapter-reuse-test
          :source-lifecycle :adapter-reuse-test})
        first-selected (source/acquire! live-source :current)
        first-adapter (source/adapter first-selected)
        _ (source/release! first-selected)
        second-selected (source/acquire! live-source :current)
        second-adapter (source/adapter second-selected)
        _ (source/release! second-selected)
        _ (ds/transact! conn [{:eacl/id "rotated-basis"}])
        third-selected (source/acquire! live-source :current)
        third-adapter (source/adapter third-selected)]
    (try
      (is (identical? first-adapter second-adapter))
      (is (not (identical? second-adapter third-adapter)))
      (finally
        (source/release! third-selected)))))
