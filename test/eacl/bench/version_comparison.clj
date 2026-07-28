(ns eacl.bench.version-comparison
  "Reproducible public-API reference benchmark for cross-version comparisons.

  This namespace intentionally targets the v6 :cursor/:limit API. Run it from
  a fresh nREPL JVM so Datomic and JIT state are isolated from other versions."
  (:require [datomic.api :as d]
            [eacl.bench.pagination-test :as pagination]
            [eacl.core :as eacl]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.fixtures :refer [->user]])
  (:import [ch.qos.logback.classic Level Logger]
           [org.slf4j LoggerFactory]))

(def default-config
  {:num-accounts 300
   :teams-per-acct 4
   :vpcs-per-acct 2
   :servers-per-acct 500
   :page-size 50
   :sample-pages [1 500 1000 2000 3000]
   :warmup-iterations 20
   :walk-iterations 3
   :sample-iterations 31})

(defn- median
  [xs]
  (let [sorted (vec (sort xs))
        n (count sorted)
        midpoint (quot n 2)]
    (if (odd? n)
      (nth sorted midpoint)
      (/ (+ (nth sorted (dec midpoint))
            (nth sorted midpoint))
         2.0))))

(defn- elapsed
  [f]
  (let [started-at (System/nanoTime)
        value (f)]
    {:elapsed-ms (/ (double (- (System/nanoTime) started-at)) 1e6)
     :value value}))

(defn- silence-debug-logging!
  []
  (.setLevel ^Logger (LoggerFactory/getLogger "eacl.datomic.core") Level/WARN))

(defn- public-query
  [page-size cursor]
  {:subject (->user "super-user")
   :permission :view
   :resource/type :server
   :cursor cursor
   :limit page-size})

(defn- measure-page
  [acl query warmup-iterations sample-iterations]
  (dotimes [_ warmup-iterations]
    (eacl/lookup-resources acl query))
  (let [times (repeatedly sample-iterations
                          (fn []
                            (:elapsed-ms
                             (elapsed #(eacl/lookup-resources acl query)))))]
    {:median-ms (median times)
     :min-ms (apply min times)
     :max-ms (apply max times)}))

(defn- walk-results
  [acl {:keys [page-size sample-pages total-resources]}]
  (let [page-count (quot total-resources page-size)
        sampled-pages (set sample-pages)]
    (loop [page-number 1
           cursor nil
           elapsed-ms 0.0
           boundaries {}
           seen-ids #{}]
      (let [query (public-query page-size cursor)
            {:keys [value] page-ms :elapsed-ms} (elapsed #(eacl/lookup-resources acl query))
            ids (mapv :id (:data value))
            boundaries' (cond-> boundaries
                          (contains? sampled-pages page-number)
                          (assoc page-number cursor))
            seen-ids' (into seen-ids ids)]
        (when-not (= page-size (count ids))
          (throw (ex-info "Unexpected page size."
                          {:page page-number
                           :expected page-size
                           :actual (count ids)})))
        (if (= page-number page-count)
          {:page-count page-count
           :full-walk-ms (+ elapsed-ms page-ms)
           :boundaries boundaries'
           :result-count (* page-count page-size)
           :unique-result-count (count seen-ids')
           :last-page-cursor (:cursor value)}
          (recur (inc page-number)
                 (:cursor value)
                 (+ elapsed-ms page-ms)
                 boundaries'
                 seen-ids'))))))

(defn run-reference!
  "Runs the expanded v6 reference benchmark and returns an EDN result map."
  ([]
   (run-reference! default-config))
  ([{:keys [num-accounts
            teams-per-acct
            vpcs-per-acct
            servers-per-acct
            page-size
            sample-pages
            warmup-iterations
            walk-iterations
            sample-iterations]
     :as config}]
   (silence-debug-logging!)
   (with-mem-conn [conn []]
     (let [total-resources (* num-accounts servers-per-acct)
           seed-config (select-keys config
                                    [:num-accounts
                                     :teams-per-acct
                                     :vpcs-per-acct
                                     :servers-per-acct])
           acl (pagination/seed-multipath! conn seed-config)
           first-page-query (public-query page-size nil)]
       ;; Warm the public path before measuring the one-query-per-page walk.
       (dotimes [_ warmup-iterations]
         (eacl/lookup-resources acl first-page-query))
       (let [walks (mapv (fn [_]
                           (walk-results acl {:page-size page-size
                                              :sample-pages sample-pages
                                              :total-resources total-resources}))
                         (range walk-iterations))
             {:keys [page-count
                     boundaries
                     result-count
                     unique-result-count]} (last walks)
             full-walk-samples-ms (mapv :full-walk-ms walks)
             full-walk-ms (median full-walk-samples-ms)
             samples (into (sorted-map)
                           (map (fn [page-number]
                                  [page-number
                                   (measure-page acl
                                                 (public-query page-size
                                                               (get boundaries page-number))
                                                 warmup-iterations
                                                 sample-iterations)]))
                           sample-pages)
             result {:implementation :v6-cursor-tree
                     :baseline-ref "f8a1a98"
                     :config config
                     :intermediate-resources
                     (+ num-accounts
                        (* num-accounts teams-per-acct)
                        (* num-accounts vpcs-per-acct))
                     :page-count page-count
                     :result-count result-count
                     :unique-result-count unique-result-count
                     :full-walk-samples-ms full-walk-samples-ms
                     :full-walk-ms full-walk-ms
                     :pages-per-second (/ (* 1000.0 page-count) full-walk-ms)
                     :samples samples}]
         (when-not (= total-resources result-count unique-result-count)
           (throw (ex-info "Pagination correctness failure." result)))
         (prn result)
         result)))))
