(ns eacl.datomic.range-reuse-integration-test
  "Range answer reuse through the public Datomic client: a shorter page of
  the same walk is served from the longer resident page with content equal
  to its uncached computation, and its derived cursor continues correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
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
  (with-mem-conn [conn datomic-schema/v8-schema]
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

(defn- commands-of
  [f]
  (let [commands (atom 0)]
    (binding [backend/*invoke-observer*
              (fn [{:keys [phase operation]}]
                (when (and (= :after phase)
                           (contains? #{:subject->resources :resource->subjects}
                                      operation))
                  (swap! commands inc)))]
      (let [result (f)]
        [result @commands]))))

(deftest longer-page-composes-the-resident-page-with-one-continuation-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          _ (fixture/page client u 4)
          partial-hits (fn [] (:partial-hits (:range-reuse (datomic/cache-stats client))))
          hits-before (partial-hits)
          [longer composed-commands] (commands-of #(fixture/page client u 9))
          [oracle uncached-commands] (commands-of #(fixture/page client u 9 :cache? false))]
      (is (= (inc hits-before) (partial-hits))
          "a longer page than the resident one is its tail plus one continuation")
      (is (false? (:cached? longer)) "a composed page ran a traversal")
      (is (= (public-shape oracle) (public-shape longer)))
      (is (< composed-commands uncached-commands)
          "only the remainder is traversed")
      (let [derived (fixture/page client u 7)]
        (is (true? (:cached? derived)) "the composed page extends the segment")
        (is (= (:data (fixture/page client u 7 :cache? false)) (:data derived))))
      (testing "the composition's cursors continue exactly like computed ones"
        (let [composed-next (fixture/page client u 3 :cache? false
                                          :after (get-in longer [:page-info :end-cursor]))
              oracle-next (fixture/page client u 3 :cache? false
                                        :after (get-in oracle [:page-info :end-cursor]))]
          (is (= (public-shape oracle-next) (public-shape composed-next))))))))

(deftest continuations-inside-the-resident-page-are-served-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          resident (fixture/page client u 12)
          oracle-pages (loop [after nil acc []]
                         (let [page (fixture/page client u 5 :cache? false :after after)
                               acc (conj acc page)]
                           (if (and (:has-next-page? (:page-info page)) (< (count acc) 4))
                             (recur (get-in page [:page-info :end-cursor]) acc)
                             acc)))
          served (loop [after nil acc []]
                   (let [[page commands] (commands-of #(fixture/page client u 5 :after after))
                         acc (conj acc [page commands])]
                     (if (and (:has-next-page? (:page-info page)) (< (count acc) 4))
                       (recur (get-in page [:page-info :end-cursor]) acc)
                       acc)))]
      (is (false? (:cached? resident)))
      (is (= (mapv public-shape oracle-pages) (mapv (comp public-shape first) served)))
      (testing "the first two five-result pages lie inside the twelve-result page"
        (is (= [true true] (mapv (comp :cached? first) (take 2 served))))
        (is (= [0 0] (mapv second (take 2 served))) "no adapter command runs"))
      (testing "the third window runs past the segment and composes the remainder"
        (let [[page commands] (nth served 2)]
          (is (false? (:cached? page)))
          (is (pos? commands))
          (is (pos? (:partial-hits (:range-reuse (datomic/cache-stats client))))))))))

(deftest windows-from-any-retained-boundary-are-served-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          _ (fixture/page client u 12)
          third (fixture/page client u 3 :cache? false)
          boundary (get-in third [:page-info :end-cursor])
          [window commands] (commands-of #(fixture/page client u 4 :after boundary))
          oracle (fixture/page client u 4 :cache? false :after boundary)]
      (is (true? (:cached? window)) "a boundary computed elsewhere is found among the retained edges")
      (is (zero? commands))
      (is (= (public-shape oracle) (public-shape window)))
      (is (= (public-shape (fixture/page client u 4 :cache? false
                                         :after (get-in oracle [:page-info :end-cursor])))
             (public-shape (fixture/page client u 4 :cache? false
                                         :after (get-in window [:page-info :end-cursor]))))
          "the served window's end cursor continues like the computed one"))))

(deftest last-window-pages-derive-as-suffixes-test
  (with-mem-conn [conn datomic-schema/v8-schema]
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
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (seed-client! conn)
          u (rich-user client)
          _ (fixture/page client u 12)
          uncached (fixture/page client u 5 :cache? false)]
      (is (false? (:cached? uncached))))))
