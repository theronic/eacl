(ns eacl.datahike.temporal-seek-test
  "Pins the lazy `AsOfDB` seek path in `eacl.datahike.db`.

   Before it, every tuple-prefix scan on a temporal wrapper materialized the
   whole endpoint segment through Datahike's eager as-of post-processing
   (`filter-txInstant` re-reads one transaction's datoms per distinct
   transaction in the result, then everything is grouped into hash maps and
   sorted back). Measured on a 60k-tuple endpoint: ~92-119ms for the first
   element of a scan versus ~2us on the direct database — per scan command,
   which keyset pagination multiplies. The lazy path reads the merged
   temporal event stream (`datahike.db.search/temporal-seek-datoms` and its
   reverse twin) from the seek bound and resolves visibility per tuple in
   ~20-30us, realizing only the page.

   Two oracles pin its semantics:
   - unbounded scans must equal the eager wrapper fallback datom-for-datom
     (entity, value, AND transaction) at every revision of a history;
   - bounded scans must equal the direct database's native seek, because the
     bound-honoring contract comes from that path (the eager fallback ignores
     the bound and relies on callers re-applying their cursor)."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [datahike.datom :as dd]
            [datahike.db.search :as dh-search]
            [eacl.datahike.core :as datahike]
            [eacl.datahike.db :as ddb]
            [eacl.relationships.storage :as relationship-storage]))

(def ^:private fwd relationship-storage/forward-attribute)
(def ^:private pagination-arity 4)

(defn- sig
  "Comparable identity of a scan result: entity, tuple value and assertion
   transaction of each datom, in scan order."
  [datoms]
  (mapv (fn [d] [(:e d) (:v d) (dd/datom-tx d)]) datoms))

(defn- reference-avet-scan
  "The pre-lazy-path wrapper fallback, verbatim: every visible datom of the
   attribute, prefix-filtered, sorted by value then entity, reversed for
   :desc. The eager oracle the lazy as-of path must reproduce unbounded."
  [db attr arity prefix direction]
  (let [prefix (vec prefix)
        n (count prefix)]
    (cond->> (d/datoms db {:index :avet :components [attr]})
      true (filter (fn [{:keys [v]}]
                     (and (vector? v)
                          (= arity (count v))
                          (= prefix (subvec v 0 n)))))
      true (sort-by (juxt :v :e))
      (= :desc direction) reverse)))

(defn- reference-eavt-scan
  [db entity attr arity prefix direction]
  (let [prefix (vec prefix)
        n (count prefix)]
    (cond->> (d/datoms db {:index :eavt :components [entity attr]})
      true (filter (fn [{:keys [v]}]
                     (and (vector? v)
                          (= arity (count v))
                          (= prefix (subvec v 0 n)))))
      true (sort-by (juxt :v :e))
      (= :desc direction) reverse)))

(defn- drop-to-bound
  "Reference bound semantics on an already-ordered scan: the suffix at or
   beyond the cursor tail (tuple slot 3), per direction."
  [rows tail direction]
  (if (nil? tail)
    rows
    (case direction
      :asc (drop-while (fn [[_ v _]] (neg? (compare (nth v 3) tail))) rows)
      :desc (drop-while (fn [[_ v _]] (pos? (compare (nth v 3) tail))) rows))))

(defn- entities!
  "Transact `:eacl/id` entities and return their ids resolved to eids."
  [conn ids]
  (d/transact conn (mapv (fn [id] {:eacl/id id}) ids))
  (let [db (d/db conn)]
    (mapv (fn [id] (ddb/entid db [:eacl/id id])) ids)))

(defn- deterministic-history!
  "A small forward-tuple history with adds, retracts and re-adds across
   several endpoints. Returns the scan ingredients plus every revision."
  [conn]
  (let [[rel hub s2 s3 & resources]
        (entities! conn (into ["rel" "hub" "s2" "s3"]
                              (map #(str "r" %) (range 1 7))))
        [r1 r2 r3 r4 r5 r6] resources
        tup (fn [r] [:user rel :doc r])
        revs (atom [])
        tx! (fn [data]
              (d/transact conn data)
              (swap! revs conj (:max-tx (d/db conn))))]
    (tx! [[:db/add hub fwd (tup r1)] [:db/add hub fwd (tup r2)]
          [:db/add hub fwd (tup r3)] [:db/add s2 fwd (tup r1)]
          [:db/add s3 fwd (tup r2)]])
    (tx! [[:db/retract hub fwd (tup r2)]])
    (tx! [[:db/add hub fwd (tup r2)] [:db/retract s2 fwd (tup r1)]])
    (tx! [[:db/add hub fwd (tup r4)] [:db/retract hub fwd (tup r1)]])
    (tx! [[:db/add hub fwd (tup r5)] [:db/add s2 fwd (tup r6)]])
    {:rel rel :hub hub :s2 s2 :s3 s3 :resources resources :revs @revs}))

(deftest asof-scan-matches-eager-visibility-test
  ;; Unbounded, the lazy path must reproduce the eager wrapper semantics
  ;; exactly — same tuples, same assertion transactions, same order — at
  ;; every revision, both directions, across prefix arities.
  (let [conn (datahike/create-conn)]
    (try
      (let [{:keys [rel hub s2 s3 revs resources]} (deterministic-history! conn)
            [r2] (drop 1 resources)]
        (doseq [rev revs
                direction [:asc :desc]
                prefix [[:user rel :doc]
                        [:user rel]
                        [:user]
                        [:user rel :doc r2]]]
          (let [asof (d/as-of (d/db conn) rev)]
            (testing (pr-str {:rev rev :direction direction :prefix prefix})
              (is (= (sig (reference-avet-scan
                           asof fwd pagination-arity prefix direction))
                     (sig (ddb/avet-tuple-prefix
                           asof fwd pagination-arity prefix nil direction))))
              (doseq [entity [hub s2 s3]]
                (is (= (sig (reference-eavt-scan
                             asof entity fwd pagination-arity prefix direction))
                       (sig (ddb/eavt-tuple-prefix
                             asof entity fwd pagination-arity prefix
                             nil direction)))))))))
      (finally (d/release conn)))))

(deftest asof-scan-honors-cursor-bound-like-direct-test
  ;; The eager fallback ignored the cursor bound (callers drop past it); the
  ;; lazy path positions at it, exactly as the direct database does. At the
  ;; head revision the as-of view and the direct database agree on content,
  ;; so the direct scan is the bound oracle; at earlier revisions the bounded
  ;; scan must be the bound-suffix of its own unbounded result.
  (let [conn (datahike/create-conn)]
    (try
      (let [{:keys [rel hub revs resources]} (deterministic-history! conn)
            prefix [:user rel :doc]
            tails (into [nil 0 Long/MAX_VALUE] resources)
            head (:max-tx (d/db conn))
            head-asof (d/as-of (d/db conn) head)]
        (doseq [direction [:asc :desc]
                tail tails]
          (testing (pr-str {:direction direction :tail tail})
            (is (= (sig (ddb/avet-tuple-prefix
                         (d/db conn) fwd pagination-arity prefix
                         tail direction))
                   (sig (ddb/avet-tuple-prefix
                         head-asof fwd pagination-arity prefix
                         tail direction))))
            (is (= (sig (ddb/eavt-tuple-prefix
                         (d/db conn) hub fwd pagination-arity prefix
                         tail direction))
                   (sig (ddb/eavt-tuple-prefix
                         head-asof hub fwd pagination-arity prefix
                         tail direction))))))
        (doseq [rev revs
                direction [:asc :desc]
                tail (remove nil? tails)]
          (let [asof (d/as-of (d/db conn) rev)
                unbounded (sig (ddb/avet-tuple-prefix
                                asof fwd pagination-arity prefix
                                nil direction))]
            (testing (pr-str {:rev rev :direction direction :tail tail})
              (is (= (drop-to-bound unbounded tail direction)
                     (sig (ddb/avet-tuple-prefix
                           asof fwd pagination-arity prefix
                           tail direction))))))))
      (finally (d/release conn)))))

(deftest asof-randomized-history-matches-eager-test
  ;; A seeded random add/retract/re-add history, checked unbounded at every
  ;; revision. Catches version-interleaving corners a hand-written history
  ;; misses (adjacent re-adds, same-transaction mixes across endpoints).
  (let [conn (datahike/create-conn)]
    (try
      (let [[rel & endpoints] (entities! conn (into ["rel"]
                                                    (map #(str "s" %) (range 5))))
            resources (entities! conn (mapv #(str "r" %) (range 30)))
            rnd (java.util.Random. 42)
            tup (fn [r] [:user rel :doc r])
            live (atom #{})
            revs (atom [])]
        (dotimes [_ 45]
          (let [n-ops (inc (.nextInt rnd 4))
                ops (loop [k 0, acc [], seen #{}]
                      (if (= k n-ops)
                        acc
                        (let [s (nth endpoints (.nextInt rnd (count endpoints)))
                              r (nth resources (.nextInt rnd (count resources)))
                              pair [s r]]
                          (cond
                            (seen pair) (recur k acc seen)
                            (@live pair)
                            (if (.nextBoolean rnd)
                              (do (swap! live disj pair)
                                  (recur (inc k)
                                         (conj acc [:db/retract s fwd (tup r)])
                                         (conj seen pair)))
                              (recur k acc seen))
                            :else
                            (do (swap! live conj pair)
                                (recur (inc k)
                                       (conj acc [:db/add s fwd (tup r)])
                                       (conj seen pair)))))))]
            (when (seq ops)
              (d/transact conn ops)
              (swap! revs conj (:max-tx (d/db conn))))))
        (is (<= 30 (count @revs)) "the generator produced a real history")
        (doseq [rev @revs
                direction [:asc :desc]]
          (let [asof (d/as-of (d/db conn) rev)]
            (is (= (sig (reference-avet-scan
                         asof fwd pagination-arity [:user rel :doc] direction))
                   (sig (ddb/avet-tuple-prefix
                         asof fwd pagination-arity [:user rel :doc]
                         nil direction)))
                (pr-str {:rev rev :direction direction}))
            (is (= (sig (reference-eavt-scan
                         asof (first endpoints) fwd pagination-arity
                         [:user rel :doc] direction))
                   (sig (ddb/eavt-tuple-prefix
                         asof (first endpoints) fwd pagination-arity
                         [:user rel :doc] nil direction)))
                (pr-str {:rev rev :direction direction :eavt true})))))
      (finally (d/release conn)))))

(deftest attribute-refs-asof-scan-test
  ;; Under :attribute-refs? the seek components carry the attribute keyword
  ;; but datoms carry its numeric ref; the guard must compare through
  ;; `attr-repr` on the lazy path exactly as on the direct path.
  (let [conn (datahike/create-conn nil {:attribute-refs? true
                                        :keep-history? true})]
    (try
      (let [{:keys [rel hub revs]} (deterministic-history! conn)]
        (doseq [rev revs
                direction [:asc :desc]]
          (let [asof (d/as-of (d/db conn) rev)]
            (is (= (sig (reference-avet-scan
                         asof fwd pagination-arity [:user rel :doc] direction))
                   (sig (ddb/avet-tuple-prefix
                         asof fwd pagination-arity [:user rel :doc]
                         nil direction)))
                (pr-str {:rev rev :direction direction}))
            (is (= (sig (reference-eavt-scan
                         asof hub fwd pagination-arity [:user rel :doc]
                         direction))
                   (sig (ddb/eavt-tuple-prefix
                         asof hub fwd pagination-arity [:user rel :doc]
                         nil direction)))
                (pr-str {:rev rev :direction direction :eavt true})))))
      (finally (d/release conn)))))

(deftest same-transaction-add-and-retract-stays-visible-test
  ;; Datahike records both events at the same transaction and resolves the
  ;; assertion as the winner (its temporal comparators order the retraction
  ;; first). The lazy path's last-event rule must agree with the eager one.
  (let [conn (datahike/create-conn)]
    (try
      (let [[rel s r] (entities! conn ["rel" "s" "r"])
            tup [:user rel :doc r]]
        (d/transact conn [[:db/add s fwd tup] [:db/retract s fwd tup]])
        (doseq [direction [:asc :desc]]
          (let [asof (d/as-of (d/db conn) (:max-tx (d/db conn)))
                eager (sig (reference-avet-scan
                            asof fwd pagination-arity [:user rel :doc]
                            direction))
                lazy (sig (ddb/avet-tuple-prefix
                           asof fwd pagination-arity [:user rel :doc]
                           nil direction))]
            (testing (pr-str {:direction direction})
              (is (= eager lazy))
              (is (= 1 (count lazy)) "the same-transaction assertion wins")))))
      (finally (d/release conn)))))

(deftest non-asof-wrappers-keep-exact-fallback-test
  ;; Only an integer-time AsOfDB takes the lazy path. Date-based as-of,
  ;; since and history wrappers must keep the exact-datoms fallback
  ;; semantics unchanged (a date time point would need per-transaction
  ;; :db/txInstant resolution the lazy path deliberately does not do).
  (let [conn (datahike/create-conn)]
    (try
      (let [{:keys [rel hub revs]} (deterministic-history! conn)
            prefix [:user rel :doc]
            wrappers [(d/as-of (d/db conn) (java.util.Date.))
                      (d/since (d/db conn) (first revs))
                      (d/history (d/db conn))]]
        (doseq [wrapper wrappers
                direction [:asc :desc]]
          (testing (pr-str {:wrapper (type wrapper) :direction direction})
            (is (= (sig (reference-avet-scan
                         wrapper fwd pagination-arity prefix direction))
                   (sig (ddb/avet-tuple-prefix
                         wrapper fwd pagination-arity prefix nil direction))))
            (is (= (sig (reference-eavt-scan
                         wrapper hub fwd pagination-arity prefix direction))
                   (sig (ddb/eavt-tuple-prefix
                         wrapper hub fwd pagination-arity prefix
                         nil direction)))))))
      (finally (d/release conn)))))

(deftest asof-page-realizes-only-the-page-test
  ;; The regression guard for the 4,000x finding, in structural rather than
  ;; wall-clock terms: a 20-row page from the middle of a 2,000-tuple as-of
  ;; segment may realize only a page's worth of raw temporal events. The
  ;; eager fallback would realize every event of the segment (>= 2,000), so
  ;; a reintroduced materialization step trips the bound immediately.
  (let [conn (datahike/create-conn)]
    (try
      (let [[rel hub] (entities! conn ["rel" "hub"])
            resources (vec (mapcat #(entities! conn (mapv (fn [i] (str "p" % "-" i))
                                                          (range 500)))
                                   (range 4)))
            tup (fn [r] [:user rel :doc r])]
        (doseq [batch (partition-all 500 resources)]
          (d/transact conn (mapv (fn [r] [:db/add hub fwd (tup r)]) batch)))
        (d/transact conn (mapv (fn [r] [:db/retract hub fwd (tup r)])
                               (take-nth 10 resources)))
        (let [head (:max-tx (d/db conn))
              asof (d/as-of (d/db conn) head)
              prefix [:user rel :doc]
              cursor (nth resources 1000)
              segment-events (count (dh-search/temporal-seek-datoms
                                     (:origin-db asof) :avet [fwd]))]
          (is (<= 2000 segment-events)
              "the segment is big enough for the bound to mean something")
          (doseq [[direction seek-var] [[:asc #'dh-search/temporal-seek-datoms]
                                        [:desc #'dh-search/temporal-rseek-datoms]]]
            (let [realized (atom 0)
                  original @seek-var
                  page (with-redefs-fn
                         {seek-var (fn [db index-type components]
                                     (map (fn [datom]
                                            (swap! realized inc)
                                            datom)
                                          (original db index-type components)))}
                         #(doall (take 20 (ddb/avet-tuple-prefix
                                           asof fwd pagination-arity prefix
                                           cursor direction))))]
              (testing (pr-str {:direction direction})
                (is (= (sig (take 20 (ddb/avet-tuple-prefix
                                      (d/db conn) fwd pagination-arity prefix
                                      cursor direction)))
                       (sig page))
                    "the guarded page is still the direct page")
                (is (<= (count page) 20))
                (is (pos? (count page)))
                (is (<= @realized 150)
                    (str "a page must not realize the segment; realized="
                         @realized)))))))
      (finally (d/release conn)))))
