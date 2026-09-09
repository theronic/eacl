(ns eacl.caveats.jvm.program-cache
  "Bounded successful-program retention and same-key construction coalescing."
  (:require [eacl.cache.standard-lru :as lru]
            [eacl.caveats.values :as values]))

(defrecord ProgramCache [resident flights lock max-builds counters])

(defn store [{:keys [max-entries max-builds]
              :or {max-entries (:program-cache-entries values/limits)
                   max-builds (:program-build-concurrency values/limits)}}]
  (when-not (and (integer? max-entries) (<= 1 max-entries (:program-cache-entries values/limits))
                 (integer? max-builds) (<= 1 max-builds (:program-build-concurrency values/limits)))
    (values/error! :resource-limit {:limit :program-cache}))
  (->ProgramCache (lru/store max-entries) (atom {}) (Object.) max-builds
                  (atom {:hits 0 :builds 0 :coalesced 0 :failed-builds 0})))

(defn stats [{:keys [resident flights lock counters]}]
  (locking lock (assoc @counters :entries (lru/entry-count resident) :in-flight (count @flights))))

(defn- claim [{:keys [resident flights lock max-builds counters]} key]
  (locking lock
    (loop []
      (let [{:keys [found? value]} (lru/lookup! resident key)]
        (cond
          found? (do (swap! counters update :hits inc) [:hit value])
          (get @flights key) (do (swap! counters update :coalesced inc) [:wait (get @flights key)])
          (< (count @flights) max-builds)
          (let [result (promise)]
            (swap! flights assoc key result)
            (swap! counters update :builds inc)
            [:build result])
          ;; Retain no pending-key queue. Waiting callers own their inputs;
          ;; eviction and concurrent misses cannot turn a valid check into a fault.
          :else (do (.wait ^Object lock) (recur)))))))

(defn get-or-build!
  "Builds once per concurrent key. Failures wake all waiters and are not cached.
   At most max-builds distinct constructions run; no loader runs under a lock."
  [{:keys [resident flights lock counters] :as store} key build]
  (let [[action value] (claim store key)]
    (case action
      :hit value
      :wait (let [[status result] @value] (if (= :ok status) result (throw result)))
      :build
      (try
        (let [result (build)]
          (locking lock
            (lru/put-if-absent! resident key result)
            (lru/entry-count resident)
            (deliver value [:ok result])
            (swap! flights dissoc key)
            (.notifyAll ^Object lock))
          result)
        (catch Throwable error
          ;; Even a fatal error must release the coalesced callers; it is
          ;; rethrown unchanged, never classified as a successful evaluation.
          (locking lock
            (swap! counters update :failed-builds inc)
            (deliver value [:error error])
            (swap! flights dissoc key)
            (.notifyAll ^Object lock))
          (throw error))))))
