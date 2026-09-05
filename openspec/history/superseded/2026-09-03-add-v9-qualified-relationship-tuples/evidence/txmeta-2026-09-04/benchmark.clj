(ns v9-txmeta-benchmark
  (:require [datomic.api :as d]
            [clojure.pprint :as pp])
  (:import [java.util HashMap Arrays]))

(def out-dir "target/v9-txmeta-benchmark-2026-09-04/")
(def tuple4 [:db.type/keyword :db.type/ref :db.type/keyword :db.type/ref])
(def tuple8 (into tuple4 [:db.type/ref :db.type/ref :db.type/long :db.type/long]))
(def schema
  (into [{:db/ident :bench/resource :db/valueType :db.type/boolean :db/cardinality :db.cardinality/one}
         {:db/ident :bench/from :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
         {:db/ident :bench/until :db/valueType :db.type/long :db/cardinality :db.cardinality/one}]
        (for [[a types] [[:bench/f4 tuple4] [:bench/r4 tuple4] [:bench/f8 tuple8] [:bench/r8 tuple8]]]
          {:db/ident a :db/valueType :db.type/tuple :db/tupleTypes types
           :db/cardinality :db.cardinality/many :db/index true})))

(defn save! [name x] (spit (str out-dir name ".edn") (with-out-str (pp/pprint x))) x)
(defn ms-since [t] (/ (- (System/nanoTime) t) 1e6))
(defn edge-class [i] (case (mod i 40) 0 :expired 20 :future :permanent))
(defn bounds [kind] (case kind :expired [nil 900] :future [1100 nil] :permanent [nil nil]))
(defn active? [from until t]
  (and (or (nil? from) (<= (long from) (long t)))
       (or (nil? until) (< (long t) (long until)))))
(defn inline-active? [dt t] (let [v (:v dt)] (active? (nth v 6) (nth v 7) t)))

(defonce state (atom nil))
(defn seed! [n]
  (assert (zero? (mod n 2000)))
  (let [start (System/nanoTime)
        uri (str "datomic:dev://localhost:4334/v9-txmeta-bench-" (random-uuid))
        _ (d/create-database uri)
        conn (d/connect uri)]
    (reset! state {:uri uri :conn conn})
    (save! "database" {:uri uri :n n :status :seeding})
    @(d/transact conn schema)
    @(d/transact conn [{:db/ident :bench/hub} {:db/ident :bench/relation}])
    (doseq [base (range 0 n 2000)]
      @(d/transact conn (mapv (fn [i] {:db/id (str "r-" i) :bench/resource true}) (range base (+ base 2000))))
      (when (zero? (mod base 200000)) (println :allocated (+ base 2000)) (flush)))
    (let [db (d/db conn)
          ids (mapv :e (d/datoms db :aevt :bench/resource))
          s (d/entid db :bench/hub)
          rel (d/entid db :bench/relation)]
      (assert (= n (count ids)))
      (assert (every? (fn [[x y]] (< (long x) (long y))) (partition 2 1 ids)))
      (doseq [base (range 0 n 2000)]
        (doseq [kind [:permanent :expired :future]]
          (let [[from until] (bounds kind)
                metadata (cond-> {:db/id "datomic.tx"} from (assoc :bench/from from) until (assoc :bench/until until))
                data (into [metadata]
                           (mapcat (fn [i]
                                     (let [r (nth ids i) f [:user rel :resource r] rev [:resource rel :user s]]
                                       [[:db/add s :bench/f4 f] [:db/add r :bench/r4 rev]
                                        [:db/add s :bench/f8 (into f [nil nil from until])]
                                        [:db/add r :bench/r8 (into rev [nil nil from until])]])))
                           (filter #(= kind (edge-class %)) (range base (+ base 2000))))]
            @(d/transact conn data)))
        (when (zero? (mod base 100000)) (println :seeded (+ base 2000) :elapsed-ms (ms-since start)) (flush)))
      (let [db (d/db conn)
            attrs (into {} (for [a [:bench/f4 :bench/r4 :bench/f8 :bench/r8]] [a (d/entid db a)]))
            result {:uri uri :n n :basis-t (d/basis-t db) :seed-ms (ms-since start)
                    :permanent (* n 95/100) :expired (* n 25/1000) :future (* n 25/1000)
                    :relationship-assertion-transactions (* 3 (/ n 2000))
                    :java (System/getProperty "java.version") :clojure (clojure-version)}]
        (reset! state {:uri uri :conn conn :db db :ids ids :s s :rel rel :attrs attrs :n n :shared-cache (HashMap.)})
        (save! "fixture" result)))))

(def variants [:raw-four :inline-eight :filter-inline-eight :filter-tx-naive :filter-tx-request-memo :filter-tx-shared-memo :filter-tx-sparse-bounds])
(def correct-variants (vec (rest variants)))
(defn build-sparse! []
  (let [start (System/nanoTime) db (:db @state) table (HashMap.) inspected (volatile! 0)]
    (doseq [[attr position] [[:bench/from 0] [:bench/until 1]] dt (d/datoms db :aevt attr)]
      (vswap! inspected inc)
      (.put table (:e dt) (assoc (or (.get table (:e dt)) [nil nil]) position (:v dt))))
    (swap! state assoc :sparse-bounds table)
    {:elapsed-ms (ms-since start) :metadata-datoms @inspected :qualified-transactions (.size table)}))
(defn view [variant t]
  (let [{:keys [db attrs shared-cache sparse-bounds]} @state
        wide? (#{:inline-eight :filter-inline-eight} variant)
        attr (attrs (if wide? :bench/f8 :bench/f4))
        relevant (set (map attrs (if wide? [:bench/f8 :bench/r8] [:bench/f4 :bench/r4])))
        cache (case variant :filter-tx-request-memo (HashMap.) :filter-tx-shared-memo shared-cache nil)
        pred (case variant
               (:raw-four :inline-eight) nil
               :filter-inline-eight (fn [_ dt] (or (not (relevant (:a dt))) (inline-active? dt t)))
               :filter-tx-sparse-bounds
               (fn [_ dt]
                 (or (not (relevant (:a dt)))
                     (let [pair (.get ^HashMap sparse-bounds (:tx dt))]
                       (or (nil? pair) (active? (nth pair 0) (nth pair 1) t)))))
               (:filter-tx-naive :filter-tx-request-memo :filter-tx-shared-memo)
               (fn [raw dt]
                 (or (not (relevant (:a dt)))
                     (let [tx (:tx dt)
                           pair (if cache
                                  (or (.get ^HashMap cache tx)
                                      (let [e (d/entity raw tx) b [(:bench/from e) (:bench/until e)]]
                                        (.put ^HashMap cache tx b) b))
                                  (let [e (d/entity raw tx)] [(:bench/from e) (:bench/until e)]))]
                       (active? (nth pair 0) (nth pair 1) t)))))
        filtered (if pred (d/filter db pred) db)]
    {:db filtered :attr attr :wide? wide? :manual? (= variant :inline-eight)}))

(defn page [variant idx t]
  (let [{:keys [ids s rel]} @state
        {:keys [db attr wide? manual?]} (view variant t)
        lower (cond-> [:user rel :resource (nth ids idx)] wide? (into [nil nil nil nil]))
        rows (d/seek-datoms db :eavt s attr lower)]
    (loop [xs (seq rows) acc (transient []) n 0]
      (if (or (nil? xs) (= n 20)) (persistent! acc)
          (let [dt (first xs) v (:v dt)]
            (if (and (= s (:e dt)) (= attr (:a dt)) (= :user (nth v 0)) (= rel (nth v 1)) (= :resource (nth v 2)))
              (if (or (not manual?) (inline-active? dt t))
                (recur (next xs) (conj! acc (nth v 3)) (inc n))
                (recur (next xs) acc n))
              (persistent! acc)))))))

(defn point [variant idx t]
  (let [{:keys [ids s rel]} @state
        {:keys [db attr wide? manual?]} (view variant t)
        identity [:user rel :resource (nth ids idx)]
        dt (first (if wide? (d/seek-datoms db :eavt s attr (into identity [nil nil nil nil]))
                      (d/datoms db :eavt s attr identity)))]
    (boolean (and dt (= s (:e dt)) (= attr (:a dt)) (= identity (subvec (:v dt) 0 4))
                  (or (not manual?) (inline-active? dt t))))))

(defn full-count [variant t]
  (let [{:keys [s]} @state
        {:keys [db attr manual?]} (view variant t)]
    (reduce (fn [n dt] (if (or (not manual?) (inline-active? dt t)) (inc n) n)) 0 (d/datoms db :eavt s attr))))

(defn warm-shared! []
  (let [t0 (System/nanoTime) n (full-count :filter-tx-shared-memo 1000)]
    {:count n :elapsed-ms (ms-since t0) :cached-transactions (.size ^HashMap (:shared-cache @state))}))

(defn percentile [sorted-values p]
  (nth sorted-values (min (dec (count sorted-values)) (max 0 (dec (int (Math/ceil (* p (count sorted-values)))))))))
(defn summary-stats [xs]
  (let [xs (vec (sort xs))]
    {:n (count xs) :median-ms (percentile xs 0.5) :p95-ms (percentile xs 0.95)
     :min-ms (first xs) :max-ms (peek xs)}))

(defn verify! []
  (let [{:keys [ids n db attrs]} @state
        counts (into {} (for [a [:bench/f4 :bench/r4 :bench/f8 :bench/r8]]
                         [a (reduce (fn [c _] (inc c)) 0 (d/datoms db :aevt (attrs a)))]))
        _ (assert (every? #(= n %) (vals counts)))
        tests (atom 0)]
    (doseq [t [1000 1200] idx (concat (range 80) [123400 444440 777760 (- n 80)])]
      (let [expected (->> (range idx n)
                          (filter #(let [[from until] (bounds (edge-class %))] (active? from until t)))
                          (take 20) (mapv #(nth ids %)))
            [from until] (bounds (edge-class idx))]
        (doseq [variant correct-variants]
          (assert (= expected (page variant idx t)) (str [:page variant idx t]))
          (assert (= (active? from until t) (point variant idx t)) (str [:point variant idx t]))
          (swap! tests + 2))))
    (save! "verification" {:physical-counts counts :page-and-point-assertions @tests :status :passed})))

(defn instrument-page [idx t]
  (let [{:keys [db ids s rel attrs]} @state
        attr (attrs :bench/f4) calls (atom 0) txs (atom #{})
        filtered (d/filter db (fn [raw dt]
                               (if (not (= attr (:a dt))) true
                                   (do (swap! calls inc) (swap! txs conj (:tx dt))
                                       (let [e (d/entity raw (:tx dt))]
                                         (active? (:bench/from e) (:bench/until e) t))))))
        result (mapv (comp #(nth % 3) :v)
                     (take 20 (d/seek-datoms filtered :eavt s attr [:user rel :resource (nth ids idx)])))
        last-index (java.util.Collections/binarySearch ids (peek result))]
    {:idx idx :results (count result) :logical-candidates-through-last-result (inc (- last-index idx))
     :filter-predicate-calls @calls :distinct-assertion-transactions (count @txs)}))

(defn run-latencies! [workload rounds per-round warmup]
  (let [{:keys [n]} @state
        op (case workload :page page :point point)
        idx-for (fn [i] (mod (* (long i) 9973) (- n 80)))
        observations (atom (zipmap variants (repeat [])))
        checksum (volatile! 0)]
    (doseq [variant variants i (range warmup)]
      (op variant (idx-for i) 1000))
    (doseq [round (range rounds)]
      (doseq [variant (take (count variants) (drop round (cycle variants)))]
        (let [latencies (mapv (fn [j]
                               (let [idx (idx-for (+ (* round per-round) j))
                                     start (System/nanoTime)
                                     result (op variant idx 1000)
                                     elapsed (ms-since start)]
                                 (vswap! checksum + (if (vector? result) (count result) (if result 1 0)))
                                 elapsed)) (range per-round))]
          (swap! observations update variant into latencies)))
      (println :timed workload :round round) (flush))
    (let [result {:workload workload :valid-at 1000 :warmup-per-variant warmup
                  :rounds rounds :per-round per-round :checksum @checksum
                  :variants (into {} (map (fn [[variant xs]] [variant (summary-stats xs)]) @observations))}]
      (save! (str (name workload) "-latencies-raw") @observations)
      (save! (str (name workload) "-latencies") result))))

(defn run-full-counts! [rounds t]
  (let [results (atom (zipmap variants (repeat []))) n (:n @state)]
    (doseq [round (range rounds)]
      (doseq [variant (take (count variants) (drop round (cycle variants)))]
        (let [start (System/nanoTime) result (full-count variant t) elapsed (ms-since start)
              expected (if (= :raw-four variant) n (if (= t 1000) (* n 95/100) (* n 975/1000)))]
          (assert (= expected result) (str [variant t result expected]))
          (swap! results update variant conj elapsed)
          (println :full-count variant :round round :count result :ms elapsed) (flush))))
    (save! (str "full-count-" t) {:valid-at t :rounds rounds :raw @results
                                 :variants (into {} (map (fn [[variant xs]] [variant (summary-stats xs)]) @results))})))
