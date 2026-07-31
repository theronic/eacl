(ns eacl.datomic.watermark-test
  "Soundness and precision of log-verified cache epochs.

  The exact-result cache used to key on Datomic `basis-t`, so any transaction
  anywhere minted a new key. The tempting shortcut — key on the relationship
  coordinator's `observed-t` instead — is unsound, and
  `another-connections-write-is-not-served-from-cache-test` is that exact
  scenario: it passes only because the epoch is verified against the database
  rather than trusted from a process-local counter."
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.core :as eacl :refer [->Relationship spice-object]]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn with-mem-conns]]
            [eacl.datomic.impl :as impl]
            [eacl.datomic.schema :as schema]
            [eacl.datomic.watermark :as watermark]))

(def ^:private direct-schema
  "definition user {}
   definition doc {
     relation owner: user
     permission view = owner
   }")

(defn- exact-client [conn]
  (core/make-client conn {:cache {:exact-results? true}
                          :page-token-key "watermark-test"}))

(defn- seed! [conn acl]
  (eacl/write-schema! acl direct-schema)
  @(d/transact conn [{:eacl/id "alice"} {:eacl/id "bob"}
                     {:eacl/id "d1"} {:eacl/id "d2"}]))

(defmacro ^:private counting-can?
  "Runs body with a counter of actual can? evaluations."
  [calls & body]
  `(let [original# impl/can?]
     (with-redefs [impl/can? (fn [db# s# p# r#]
                               (swap! ~calls inc)
                               (original# db# s# p# r#))]
       ~@body)))

;; --- precision: the whole point --------------------------------------------

(deftest unrelated-transactions-keep-the-exact-cache-hot-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "application writes that touch no EACL data"
          (dotimes [n 25]
            @(d/transact conn [{:db/doc (str "unrelated " n)}])
            (is (true? (eacl/can? acl alice :view d1))))
          (is (= 1 @calls)
              "25 intervening transactions, still one evaluation"))))))

;; --- soundness: every way EACL data can change -----------------------------

(deftest relationship-writes-move-the-epoch-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? acl alice :view d1)))
        (is (= 1 @calls))

        (testing "a create is observed"
          (eacl/create-relationship! acl (->Relationship alice :owner d1))
          (is (true? (eacl/can? acl alice :view d1)))
          (is (= 2 @calls)))

        (testing "a retraction is observed"
          (eacl/delete-relationship! acl (->Relationship alice :owner d1))
          (is (false? (eacl/can? acl alice :view d1)))
          (is (= 3 @calls)))))))

(deftest raw-transact-of-relationship-tx-data-moves-the-epoch-test
  ;; tx-relationship is public and documented for callers who want to write a
  ;; relationship inside their own transaction. A coordinator cannot see this;
  ;; the log can.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? acl alice :view d1)))
        @(d/transact conn (impl/tx-relationship
                           (d/db conn)
                           (->Relationship alice :owner d1)))
        (is (true? (eacl/can? acl alice :view d1))
            "a relationship written outside the EACL client still takes effect")
        (is (= 2 @calls))))))

(deftest another-connections-write-is-not-served-from-cache-test
  ;; THE loophole. Two connections to one database is what two app servers look
  ;; like. A process-local watermark stays at its initial value here and the
  ;; reader serves a stale answer; a log-verified epoch does not.
  (with-mem-conns [reader-conn writer-conn schema/v7-schema]
    (let [reader (exact-client reader-conn)
          writer (core/make-client writer-conn {})
          _ (seed! reader-conn reader)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (counting-can? calls
        (is (false? (eacl/can? reader alice :view d1)))
        (is (= 1 @calls))

        (eacl/create-relationship! writer (->Relationship alice :owner d1))

        (is (true? (eacl/can? reader alice :view d1))
            "a write from another connection must invalidate the reader's cache")
        (is (= 2 @calls))

        (testing "and a revoke from that other connection too"
          (eacl/delete-relationship! writer (->Relationship alice :owner d1))
          (is (false? (eacl/can? reader alice :view d1)))
          (is (= 3 @calls)))))))

(deftest schema-writes-move-the-epoch-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (exact-client conn)
          _ (seed! conn acl)
          alice (spice-object :user "alice")
          d1 (spice-object :doc "d1")
          calls (atom 0)]
      (eacl/create-relationship! acl (->Relationship alice :owner d1))
      (counting-can? calls
        (is (true? (eacl/can? acl alice :view d1)))
        (eacl/write-schema! acl "definition user {}
                                 definition doc { relation owner: user
                                                  relation editor: user
                                                  permission view = editor }")
        (is (false? (eacl/can? acl alice :view d1))
            "view no longer includes owner")
        (is (= 2 @calls))))))

;; --- the epoch component itself ---------------------------------------------

(deftest epoch-is-stable-across-unrelated-transactions-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache false})
          _ (seed! conn acl)
          epoch (watermark/log-cache-epoch conn)
          basis (fn [] (d/basis-t (d/db conn)))
          e0 (watermark/epoch-for epoch (basis))]
      (dotimes [_ 3] @(d/transact conn [{:db/doc "x"}]))
      (is (= e0 (watermark/epoch-for epoch (basis)))
          "unrelated transactions do not move the epoch")

      (eacl/create-relationship! acl (->Relationship (spice-object :user "alice")
                                                     :owner (spice-object :doc "d1")))
      (let [e1 (watermark/epoch-for epoch (basis))]
        (is (not= e0 e1) "an EACL write moves it")

        (dotimes [_ 3] @(d/transact conn [{:db/doc "y"}]))
        (is (= e1 (watermark/epoch-for epoch (basis)))
            "and it settles again")))))

(deftest historical-bases-are-their-own-epoch-test
  ;; EACL targets the current database. A read pinned to an older basis gets
  ;; its own basis as its epoch, so it shares nothing and is never made hot —
  ;; deliberately, since a cache per point in time is a non-goal.
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache false})
          _ (seed! conn acl)
          old-basis (d/basis-t (d/db conn))
          epoch (watermark/log-cache-epoch conn)]
      (dotimes [_ 3] @(d/transact conn [{:db/doc "x"}]))
      (watermark/epoch-for epoch (d/basis-t (d/db conn)))
      (is (= old-basis (watermark/epoch-for epoch old-basis))))))

(deftest epoch-built-before-the-eacl-schema-still-detects-writes-test
  ;; Watched attributes were resolved once at construction. A client built
  ;; before the EACL schema is installed — which this repo's own benchmark
  ;; seeding does — then held an empty watched set and detected NOTHING, so the
  ;; epoch never moved and answers went stale permanently.
  (with-mem-conn [conn []]
    (let [epoch (watermark/log-cache-epoch conn)]      ;; no EACL attributes yet
      @(d/transact conn schema/v7-schema)
      (let [acl (core/make-client conn {:cache false})
            _ (seed! conn acl)
            basis #(d/basis-t (d/db conn))
            e0 (watermark/epoch-for epoch (basis))]
        (eacl/create-relationship! acl (->Relationship (spice-object :user "alice")
                                                       :owner (spice-object :doc "d1")))
        (let [e1 (watermark/epoch-for epoch (basis))]
          (is (not= e0 e1)
              "a write must be seen even though the attributes did not exist
               when the epoch was created")
          (dotimes [_ 3] @(d/transact conn [{:db/doc "x"}]))
          (is (= e1 (watermark/epoch-for epoch (basis)))
              "and precision is not lost in the process"))))))

(deftest a-long-window-degrades-to-a-miss-not-a-scan-test
  (with-mem-conn [conn schema/v7-schema]
    (let [acl (core/make-client conn {:cache false})
          _ (seed! conn acl)
          epoch (watermark/log-cache-epoch conn {:max-scanned-transactions 4})
          e0 (watermark/epoch-for epoch (d/basis-t (d/db conn)))]
      (dotimes [_ 40] @(d/transact conn [{:db/doc "x"}]))
      (is (not= e0 (watermark/epoch-for epoch (d/basis-t (d/db conn))))
          "past the scan bound the epoch advances rather than proving anything"))))

(deftest epoch-failure-degrades-to-basis-keying-test
  (let [broken (reify watermark/CacheEpoch
                 (epoch-for [_ _] (throw (ex-info "log unavailable" {}))))]
    (is (= 1234 (watermark/safe-epoch-for broken 1234))
        "a failing epoch falls back to the basis, which is what the cache used
         before epochs existed — a miss, never a wrong answer"))
  (is (= 99 (watermark/safe-epoch-for nil 99))))
