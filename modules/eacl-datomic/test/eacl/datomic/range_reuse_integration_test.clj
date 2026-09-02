(ns eacl.datomic.range-reuse-integration-test
  "Range answer reuse through the public Datomic client: a shorter page of
  the same walk is served from the longer resident page with content equal
  to its uncached computation, and its derived cursor continues correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.scan-cache-fixture :as fixture]))

(def ^:private config {:users 30 :groups 60 :groups-per-user 12 :seed 11})

(defn- seed-client!
  [conn]
  (let [client (datomic/make-client conn {})]
    (fixture/seed! client config
                   (fn [ids] @(d/transact conn (mapv (fn [id] {:eacl/id id}) ids))))
    client))

(defn- rich-user
  [client]
  (or (some (fn [u]
              (when (> (count (:data (fixture/page client u 100 :cache? false))) 12)
                u))
            (range (:users config)))
      0))

(defn- public-shape
  [page]
  (select-keys page [:data :page-info]))

(deftest shorter-page-is-derived-from-the-longer-resident-page-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          long-page (fixture/page client u 12)
          short-page (fixture/page client u 5)
          oracle (fixture/page client u 5 :cache? false)]
      (is (false? (:cached? long-page)))
      (testing "the shorter page is a cache-derived answer with identical content"
        (is (true? (:cached? short-page)))
        (is (= (:data oracle) (:data short-page)))
        (is (= (get-in oracle [:page-info :has-next-page?])
               (get-in short-page [:page-info :has-next-page?])))
        (is (= (get-in oracle [:page-info :has-previous-page?])
               (get-in short-page [:page-info :has-previous-page?]))))
      (testing "the derived end cursor continues exactly like the computed one"
        (let [derived-next (fixture/page client u 5 :cache? false
                                         :after (get-in short-page [:page-info :end-cursor]))
              oracle-next (fixture/page client u 5 :cache? false
                                        :after (get-in oracle [:page-info :end-cursor]))]
          (is (= (public-shape oracle-next) (public-shape derived-next)))))
      (testing "the range tier reports the derivation"
        (is (pos? (:hits (:range-reuse (datomic/cache-stats client)))))))))

(deftest longer-page-is-computed-and-supersedes-the-shorter-resident-page-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          _ (fixture/page client u 4)
          longer (fixture/page client u 9)
          oracle (fixture/page client u 9 :cache? false)]
      (is (false? (:cached? longer)) "a longer page than the resident one is computed")
      (is (= (:data oracle) (:data longer)))
      (let [derived (fixture/page client u 7)]
        (is (true? (:cached? derived)) "and then serves shorter pages")
        (is (= (:data (fixture/page client u 7 :cache? false)) (:data derived)))))))

(deftest last-window-pages-derive-as-suffixes-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          query (fn [n cache?] (eacl/lookup-resources
                                client {:subject (fixture/user u) :permission :view
                                        :resource/type :doc :last n :cache? cache?}))
          _ (query 10 true)
          derived (query 4 true)
          oracle (query 4 false)]
      (is (true? (:cached? derived)))
      (is (= (:data oracle) (:data derived)))
      (is (= (get-in oracle [:page-info :has-previous-page?])
             (get-in derived [:page-info :has-previous-page?]))))))

(deftest cache-disabled-requests-never-derive-test
  (with-mem-conn [conn datomic-schema/v7-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          _ (fixture/page client u 12)
          uncached (fixture/page client u 5 :cache? false)]
      (is (false? (:cached? uncached))))))
