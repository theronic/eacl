(ns eacl.datomic.backend-exact-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.api :as d]
            [eacl.backend.v8 :as backend]
            [eacl.datomic.backend :as datomic-backend]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.schema :as schema]))

(defn- waiter
  [blocking-value cancelled?]
  (reify
    clojure.lang.IDeref
    (deref [_]
      (if (fn? blocking-value)
        (blocking-value)
        blocking-value))

    clojure.lang.IBlockingDeref
    (deref [_ _timeout-ms timeout-value]
      (if (= ::timeout blocking-value)
        timeout-value
        (if (fn? blocking-value)
          (blocking-value)
          blocking-value)))

    java.util.concurrent.Future
    (cancel [_ _may-interrupt?]
      (reset! cancelled? true)
      true)
    (isCancelled [_] @cancelled?)
    (isDone [_] false)
    (get [_]
      (if (fn? blocking-value)
        (blocking-value)
        blocking-value))
    (get [_ _timeout _unit]
      (if (fn? blocking-value)
        (blocking-value)
        blocking-value))))

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

(deftest exact-selection-skips-or-targets-sync-test
  (with-mem-conn [conn schema/v7-schema]
    @(d/transact conn [{:db/ident :eacl.test/exact-selection}])
    (let [head (d/db conn)
          head-t (d/basis-t head)
          adapter (datomic-backend/snapshot-adapter
                   head {:conn conn :source-lifecycle "exact-effects"})
          original-as-of d/as-of]
      (testing "an already-local basis skips synchronization and applies as-of once"
        (let [as-of-calls (atom [])]
          (with-redefs [d/sync (fn [& _]
                                (throw (ex-info "must not synchronize" {})))
                        d/as-of (fn [db t]
                                  (swap! as-of-calls conj [db t])
                                  (original-as-of db t))]
            (let [selected (backend/invoke
                            adapter :select-exact
                            {:revision head-t :exact-locator head-t}
                            50)]
              (is (= [[head head-t]] @as-of-calls))
              (is (= {:revision head-t :exact-locator head-t}
                     (backend/invoke selected :native-revision)))))))

      (testing "a locally future basis performs bounded targeted sync then as-of"
        (let [local-t (dec head-t)
              sync-calls (atom [])
              as-of-calls (atom [])
              cancelled? (atom false)
              original-basis-t d/basis-t]
          (with-redefs [d/db (constantly head)
                        d/basis-t (fn [db]
                                    (if (identical? db head)
                                      local-t
                                      (original-basis-t db)))
                        d/sync (fn [actual-conn t]
                                 (swap! sync-calls conj [actual-conn t])
                                 (waiter (original-as-of head head-t)
                                         cancelled?))
                        d/as-of (fn [db t]
                                  (swap! as-of-calls conj [db t])
                                  (original-as-of head t))]
            (let [selected (backend/invoke
                            adapter :select-exact
                            {:revision head-t :exact-locator head-t}
                            50)]
              (is (= [[conn head-t]] @sync-calls))
              (is (= 1 (count @as-of-calls)))
              (is (= head-t (second (first @as-of-calls))))
              (is (false? @cancelled?))
              (is (= {:revision head-t :exact-locator head-t}
                     (backend/invoke selected :native-revision))))))))))

(deftest exact-selection-cancels-bounded-waits-test
  (with-mem-conn [conn schema/v7-schema]
    (let [local (d/db conn)
          local-t (d/basis-t local)
          target (inc local-t)
          adapter (datomic-backend/snapshot-adapter
                   local {:conn conn :source-lifecycle "exact-cancellation"})]
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
                 #(backend/invoke
                   adapter :select-exact
                   {:revision target :exact-locator target}
                   1)))]
          (is (= :eacl.consistency/freshness-unavailable (:type data)))
          (is (= :freshness-timeout (:reason data)))
          (is (= target (:requested-order-hint data)))
          (is (= local-t (:observed-order-hint data)))
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
                 #(backend/invoke
                   adapter :select-exact
                   {:revision target :exact-locator target}
                   50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= :cancelled (:classification data)))
          (is (= :exact-sync (:phase data)))
          (is @cancelled?)
          (is (:caller-thread-interrupted? data)
              "the caller observes the restored flag at the catch boundary")
          ;; Do not leak the deliberate test interruption into later tests.
          (Thread/interrupted))))))

(deftest exact-selection-rejects-invalid-or-failing-providers-test
  (with-mem-conn [conn schema/v7-schema]
    (let [local (d/db conn)
          local-t (d/basis-t local)
          target (inc local-t)
          adapter (datomic-backend/snapshot-adapter
                   local {:conn conn :source-lifecycle "exact-errors"})]
      (testing "malformed and contradictory locators fail before storage work"
        (let [storage-calls (atom 0)]
          (with-redefs [d/db (fn [_]
                               (swap! storage-calls inc)
                               local)
                        d/sync (fn [& _]
                                 (swap! storage-calls inc)
                                 (promise))]
            (doseq [[payload reason]
                    [[{:revision local-t :exact-locator "not-a-t"} :malformed]
                     [{:revision local-t :exact-locator (inc local-t)}
                      :contradictory-native-revision]]]
              (let [data (error-data
                          #(backend/invoke
                            adapter :select-exact payload 10))]
                (is (= :eacl/invalid-zed-token (:type data)))
                (is (= reason (:reason data)))))
            (is (zero? @storage-calls)))))

      (testing "a below-floor sync result is freshness failure, never as-of"
        (let [cancelled? (atom false)
              as-of-calls (atom 0)
              data
              (with-redefs [d/sync (fn [_ _] (waiter local cancelled?))
                            d/as-of (fn [& _]
                                      (swap! as-of-calls inc)
                                      local)]
                (error-data
                 #(backend/invoke
                   adapter :select-exact
                   {:revision target :exact-locator target}
                   50)))]
          (is (= :eacl.consistency/freshness-unavailable (:type data)))
          (is (= :head-behind (:reason data)))
          (is (= target (:requested-order-hint data)))
          (is (= local-t (:observed-order-hint data)))
          (is (zero? @as-of-calls))))

      (testing "unexpected provider failure retains phase and cause"
        (let [provider (ex-info "peer down" {:provider :datomic})
              data
              (with-redefs [d/sync (fn [_ _] (throw provider))]
                (error-data
                 #(backend/invoke
                   adapter :select-exact
                   {:revision target :exact-locator target}
                   50)))]
          (is (= :eacl.basis/selection-failure (:type data)))
          (is (= :retryable (:classification data)))
          (is (= :exact-sync (:phase data)))
          (is (identical? provider (:cause data))))))))
