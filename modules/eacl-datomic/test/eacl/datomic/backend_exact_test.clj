(ns eacl.datomic.backend-exact-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.source :as source]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(defn- waiter
  [blocking-value cancelled?]
  (reify
    clojure.lang.IDeref
    (deref [_]
      (if (fn? blocking-value) (blocking-value) blocking-value))

    clojure.lang.IBlockingDeref
    (deref [_ _timeout-ms timeout-value]
      (cond
        (= ::timeout blocking-value) timeout-value
        (fn? blocking-value) (blocking-value)
        :else blocking-value))

    java.util.concurrent.Future
    (cancel [_ _may-interrupt?]
      (reset! cancelled? true)
      true)
    (isCancelled [_] @cancelled?)
    (isDone [_] false)
    (get [_]
      (if (fn? blocking-value) (blocking-value) blocking-value))
    (get [_ _timeout _unit]
      (if (fn? blocking-value) (blocking-value) blocking-value))))

(defn- error-data
  [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo error
      (assoc (ex-data error)
             :cause (ex-cause error)
             :caller-thread-interrupted?
             (.isInterrupted (Thread/currentThread))))))

(defn- basis-source
  [conn]
  (datomic-backend/source
   conn
   {:source-lifecycle "datomic-exact-source-test"}))

(deftest exact-acquisition-reuses-one-covered-local-observation-test
  (with-mem-conn [conn schema/v8-schema]
    @(d/transact conn [{:db/ident :eacl.test/exact-selection}])
    (let [head (d/db conn)
          head-t (d/basis-t head)
          basis-source (basis-source conn)
          sync-calls (atom [])
          db-calls (atom 0)
          as-of-calls (atom [])
          cancelled? (atom false)
          original-as-of d/as-of]
      (with-redefs [d/db (fn [_]
                           (swap! db-calls inc)
                           head)
                    d/sync (fn [actual-conn t]
                             (swap! sync-calls conj [actual-conn t])
                             (waiter head cancelled?))
                    d/as-of (fn [db t]
                              (swap! as-of-calls conj [db t])
                              (original-as-of db t))]
        (let [selected
              (source/acquire!
               basis-source :exact
               {:revision head-t :exact-locator head-t}
               50)]
          (try
            (is (= 1 @db-calls)
                "exact acquisition captures the local Peer DB once")
            (is (empty? @sync-calls)
                "a locally covered locator performs no synchronization")
            (is (= [[head head-t]] @as-of-calls))
            (is (false? @cancelled?))
            (is (= {:revision head-t :exact-locator head-t}
                   (backend/invoke
                    (source/adapter selected) :native-revision)))
            (finally
              (source/release! selected))))))))

(deftest exact-acquisition-synchronizes-once-only-when-local-is-behind-test
  (with-mem-conn [conn schema/v8-schema]
    (let [before (d/db conn)
          _ @(d/transact conn [{:db/ident :eacl.test/exact-behind}])
          head (d/db conn)
          target (d/basis-t head)
          basis-source (basis-source conn)
          db-calls (atom 0)
          sync-calls (atom [])
          as-of-calls (atom [])
          original-as-of d/as-of]
      (is (< (d/basis-t before) target))
      (with-redefs [d/db (fn [_]
                           (swap! db-calls inc)
                           before)
                    d/sync (fn [actual-conn requested]
                             (swap! sync-calls conj [actual-conn requested])
                             (future head))
                    d/as-of (fn [db requested]
                              (swap! as-of-calls conj [db requested])
                              (original-as-of db requested))]
        (let [selected
              (source/acquire!
               basis-source :exact
               {:revision target :exact-locator target}
               50)]
          (try
            (is (= 1 @db-calls))
            (is (= [[conn target]] @sync-calls))
            (is (= [[head target]] @as-of-calls))
            (is (= target
                   (:revision
                    (backend/invoke
                     (source/adapter selected) :native-revision))))
            (finally
              (source/release! selected))))))))

(deftest exact-acquisition-cancels-bounded-waits-test
  (with-mem-conn [conn schema/v8-schema]
    (let [local (d/db conn)
          local-t (d/basis-t local)
          target (inc local-t)
          basis-source (basis-source conn)]
      (testing "timeout cancels the Datomic future and never applies as-of"
        (let [cancelled? (atom false)
              as-of-calls (atom 0)
              data
              (with-redefs [d/sync (fn [_ _]
                                     (waiter ::timeout cancelled?))
                            d/as-of (fn [& _]
                                      (swap! as-of-calls inc)
                                      local)]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision target :exact-locator target}
                   1)))]
          (is (= :eacl.consistency/freshness-unavailable (:type data)))
          (is (= (:type data) (:eacl/error data)))
          (is (= :freshness-timeout (:reason data)))
          (is (= target (:requested-order-hint data)))
          (is (= 1 (:timeout-ms data)))
          (is @cancelled?)
          (is (zero? @as-of-calls))))

      (testing "interruption cancels, remains classified, and restores the flag"
        (let [cancelled? (atom false)
              data
              (with-redefs [d/sync
                            (fn [_ _]
                              (waiter
                               #(throw (InterruptedException. "stop"))
                               cancelled?))]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision target :exact-locator target}
                   50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= (:type data) (:eacl/error data)))
          (is (= :cancelled (:classification data)))
          (is (= :exact-sync (:phase data)))
          (is @cancelled?)
          (is (:caller-thread-interrupted? data))
          (Thread/interrupted))))))

(deftest exact-acquisition-failures-stop-before-history-selection-test
  (with-mem-conn [conn schema/v8-schema]
    (let [local (d/db conn)
          target (inc (d/basis-t local))
          basis-source (basis-source conn)]
      (testing "local observation failure is a selection failure"
        (let [sync-calls (atom 0)
              as-of-calls (atom 0)
              data
              (with-redefs [d/db (fn [_]
                                   (throw (ex-info "db unavailable" {})))
                            d/sync (fn [& _] (swap! sync-calls inc))
                            d/as-of (fn [& _] (swap! as-of-calls inc))]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision target :exact-locator target} 50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= :retryable (:classification data)))
          (is (= :exact-sync (:phase data)))
          (is (zero? @sync-calls))
          (is (zero? @as-of-calls))))

      (testing "targeted sync provider failure is a selection failure"
        (let [as-of-calls (atom 0)
              provider (ex-info "sync unavailable" {})
              data
              (with-redefs [d/db (constantly local)
                            d/sync (fn [_ _] (throw provider))
                            d/as-of (fn [& _] (swap! as-of-calls inc))]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision target :exact-locator target} 50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= :retryable (:classification data)))
          (is (= :exact-sync (:phase data)))
          (is (identical? provider (:cause data)))
          (is (zero? @as-of-calls))))

      (testing "successful sync evidence below T is freshness unavailable"
        (let [as-of-calls (atom 0)
              data
              (with-redefs [d/db (constantly local)
                            d/sync (fn [_ _] (future local))
                            d/as-of (fn [& _] (swap! as-of-calls inc))]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision target :exact-locator target} 50)))]
          (is (= :eacl.consistency/freshness-unavailable (:type data)))
          (is (= :head-behind (:reason data)))
          (is (= target (:requested-order-hint data)))
          (is (zero? @as-of-calls))))

      (testing "history provider failure is classified after coverage only"
        (let [failure (ex-info "history unavailable" {})
              data
              (with-redefs [d/db (constantly local)
                            d/as-of (fn [& _] (throw failure))]
                (error-data
                 #(source/acquire!
                   basis-source :exact
                   {:revision (d/basis-t local)
                    :exact-locator (d/basis-t local)} 50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= :retryable (:classification data)))
          (is (= :exact-as-of (:phase data)))
          (is (identical? failure (:cause data))))))))

(deftest exact-acquisition-validates-before-storage-test
  (with-mem-conn [conn schema/v8-schema]
    (let [local-t (d/basis-t (d/db conn))
          basis-source (basis-source conn)
          storage-calls (atom 0)]
      (with-redefs [d/sync (fn [& _]
                             (swap! storage-calls inc)
                             (promise))
                    d/as-of (fn [& _]
                              (swap! storage-calls inc)
                              nil)]
        (doseq [[payload reason]
                [[{:revision local-t :exact-locator "not-a-t"} :malformed]
                 [{:revision local-t :exact-locator (inc local-t)}
                  :contradictory-native-revision]]]
          (let [data
                (error-data
                 #(source/acquire! basis-source :exact payload 10))]
            (is (= :eacl/invalid-zed-token (:type data)))
            (is (= (:type data) (:eacl/error data)))
            (is (= reason (:reason data)))))
        (is (zero? @storage-calls))))))

(deftest every-bounded-source-wait-is-cancelled-test
  (with-mem-conn [conn schema/v8-schema]
    (let [local (d/db conn)
          target (inc (d/basis-t local))
          basis-source (basis-source conn)]
      (doseq [[kind sync-fn args]
              [[:at-least (fn [_ _] nil) [{:revision target} 1]]
               [:authoritative (fn [_] nil) [1]]]]
        (testing (name kind)
          (let [cancelled? (atom false)
                data
                (with-redefs [d/sync
                              (fn [& sync-args]
                                (apply sync-fn sync-args)
                                (waiter ::timeout cancelled?))]
                  (error-data
                   #(apply source/acquire! basis-source kind args)))]
            (is (= :eacl.consistency/freshness-unavailable (:type data)))
            (is (= (:type data) (:eacl/error data)))
            (is (= :freshness-timeout (:reason data)))
            (is @cancelled?)))))))
