(ns eacl.datomic.lookahead-observer-test
  "Page lookahead and request I/O observation through the public Datomic
  client: the continuation becomes an exact hit after the background
  publication, the foreground page is unchanged, and the observer receives
  the request's exact meters with the right provenance."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.datomic.core :as datomic]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as datomic-schema]
            [eacl.scan-cache-fixture :as fixture]))

(def ^:private config {:users 20 :groups 40 :groups-per-user 8 :seed 7})

(defn- seed!
  [conn client]
  (fixture/seed! client config
                 (fn [ids] @(d/transact conn (mapv (fn [id] {:eacl/id id}) ids)))))

(defn- rich-user
  "A user with more than three docs, so a three-item page has a next page."
  [client]
  (or (some (fn [u]
              (when (> (count (:data (fixture/page client u 50 :cache? false))) 3)
                u))
            (range (:users config)))
      0))

(defn- await-observations
  "Waits until `pred` holds for the observed events or the deadline passes."
  [events pred]
  (loop [remaining 200]
    (cond
      (pred @events) true
      (zero? remaining) false
      :else (do (Thread/sleep 25) (recur (dec remaining))))))

(deftest lookahead-turns-the-continuation-into-an-exact-hit-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [events (atom [])
          client (datomic/make-client
                  conn {:lookahead {:pages 1 :max-inflight 2}
                        :io-observer (fn [event] (swap! events conj event))})
          _ (seed! conn client)
          u (rich-user client)
          page (fixture/page client u 3)
          cursor (get-in page [:page-info :end-cursor])]
      (is (true? (get-in page [:page-info :has-next-page?])))
      (is (false? (:cached? page)) "the foreground page was computed")
      (is (await-observations
           events
           (fn [observed]
             (some #(and (= :lookahead (:provenance %))
                         (= :completed (:outcome %)))
                   observed)))
          "the lookahead operation completed and was observed")
      (let [continuation (fixture/page client u 3 :after cursor)
            oracle (fixture/page client u 3 :after cursor :cache? false)]
        (testing "the caller's continuation is an exact hit with identical content"
          (is (true? (:cached? continuation)))
          (is (= (:data oracle) (:data continuation)))
          (is (= (dissoc (:page-info oracle) :start-cursor :end-cursor)
                 (dissoc (:page-info continuation) :start-cursor :end-cursor)))))
      (testing "observer events carry provenance and exact meters"
        (let [foreground (first (filter #(= :request (:provenance %)) @events))
              background (first (filter #(= :lookahead (:provenance %)) @events))]
          (is (= :lookup-resources (:operation foreground)))
          (is (pos? (get-in foreground [:meters :commands])))
          (is (pos? (:elapsed-nanos foreground)))
          (is (= :lookup-resources (:operation background)))
          (is (pos? (get-in background [:meters :commands]))))))))

(deftest lookahead-is-off-by-default-and-observer-is-optional-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (datomic/make-client conn {})
          _ (seed! conn client)
          u (rich-user client)
          page (fixture/page client u 3)
          cursor (get-in page [:page-info :end-cursor])
          _ (Thread/sleep 100)
          continuation (fixture/page client u 3 :after cursor)]
      (is (false? (:cached? continuation))
          "without lookahead the continuation is computed on demand"))))

(deftest observer-failures-never-change-results-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (let [client (datomic/make-client
                  conn {:io-observer (fn [_] (throw (ex-info "observer broke" {})))})
          _ (seed! conn client)
          u (rich-user client)
          oracle-client (datomic/make-client conn {})]
      (is (= (:data (fixture/page oracle-client u 5 :cache? false))
             (:data (fixture/page client u 5)))))))

(deftest lookahead-and-observer-options-are-validated-test
  (with-mem-conn [conn datomic-schema/v8-schema]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":lookahead"
                          (datomic/make-client conn {:lookahead {:pages 1}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":io-observer"
                          (datomic/make-client conn {:io-observer :not-a-function})))))
