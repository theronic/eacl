(ns eacl.bench.set-algebra-reuse-test
  "Acceptance gate for
  openspec/changes/2026-09-24-reuse-set-algebra-results-in-the-client-cache:
  later requests on one client reuse the operand and operator decisions an
  earlier request cached, across an evaluation time 60 seconds later and
  across an exported and restored cache.

  Tagged `:benchmark`: the ordinary suites exclude it. Run it on an otherwise
  idle JVM. Each sample builds fresh clients over the chart's database. Both
  arms compile their plans untimed with `:cache? false` walks. The measured
  arm then runs its setup at t0, and both arms time the same request at t0
  plus 60 seconds. Arms alternate order per sample. Every measured result is
  compared with an uncached evaluation at the same time. The budgets below
  were fixed before any sampling of the implementation. Raw samples are
  written under ignored target/benchmarks/set-algebra-reuse/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing]]
            [eacl.bench.operator-shapes-fixture :as fixture]
            [eacl.bench.paired :as paired]
            [eacl.bench.recursive-intersection-fixture :as chart]
            [eacl.core :as eacl]
            [eacl.datahike.core :as eacl.datahike]
            [eacl.engine.stable-route :as route]
            [eacl.operator.recursive :as recursive]))

(def budgets
  "The capability's budgets (spec `set-algebra-result-reuse`), authored before
  sampling: the measured median is at most `factor` times the reference
  arm's median."
  {:operator-check-after-walk 0.5
   :operand-check-after-walk 0.6
   :sibling-walk-after-walk 0.75
   :guarded-check-after-walk 0.5
   :restored-check 0.5
   :first-cached-walk 1.25})

(def shapes
  "636 accounts at depth 4 and 2,077 at depth 5."
  [[5 6 5 3] [6 5 4 4 3]])

(def ^:private step-ms
  "The time between a setup request and the measured one: well inside every
  share's hour-long certificate."
  60000)

(def ^:private sample-counts {:check 31 :walk 15})

(def ^:private warmup-samples 3)

(def ^:dynamic *uncached*
  "Bound while computing an expected answer; requests ignore their `cache?`
  argument and bypass every cache."
  false)

(defn- walk
  "Every resource id of a complete cursor walk, in walk order."
  [acl subject permission cache?]
  (loop [after nil acc []]
    (let [page (eacl/lookup-resources
                acl (cond-> {:subject (chart/user subject) :permission permission
                             :resource/type :account :first 1000
                             :cache? (and cache? (not *uncached*))}
                      after (assoc :after after)))
          acc (into acc (map :id) (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) acc)
        acc))))

(defn- check [acl subject permission path cache?]
  (:permissionship
   (eacl/check-permission acl {:subject (chart/user subject) :permission permission
                               :resource (chart/account path)
                               :cache? (and cache? (not *uncached*))})))

(def ^:private denotation-capacity
  "Room for every decision a case stores: one root and two operand decisions
  per account of the larger chart. The default capacity, 4,096, holds those
  of about 1,300 accounts; beyond that a later request reuses the decisions
  the store retained and recomputes the rest."
  16384)

(defn- client
  "A read-only client over the chart's database value, with the chart's source
  lifecycle and a clock read from `clock`. The chart maps ids with custom
  converters, so its clients declare the stable adapter fingerprint that a
  host restoring snapshots across clients must declare; without it each
  client's cache entries stay local to that client."
  [{:keys [conn lifecycle]} clock]
  (eacl.datahike/make-client (atom @conn)
                             (assoc fixture/client-options
                                    :read-only? true
                                    :source-lifecycle lifecycle
                                    :clock #(deref clock)
                                    :adapter-fingerprint {:codec :eacl.bench/app-id-lookup-ref-v1}
                                    :adapter-deterministic? true
                                    :cache {:denotation-max-entries denotation-capacity})))

(defn- compile-plans!
  "Seals every permission a case touches, without touching the reuse caches."
  [acl subject permissions]
  (doseq [permission permissions]
    (walk acl subject permission false)))

(defn- denotation-hits [acl]
  (get-in (eacl.datahike/cache-stats acl) [:subproblems :denotation-hits] 0))

(defn- observed
  "Runs `f` on `acl`, returning its value, elapsed microseconds, membership
  searches and reuses, tabled questions, and denotation hits."
  [acl f]
  (let [membership (atom {})
        tabled (atom {})
        hits (denotation-hits acl)
        start (System/nanoTime)
        value (binding [route/*membership-stats* membership
                        recursive/*recursive-stats* tabled]
                (f))
        elapsed (/ (- (System/nanoTime) start) 1e3)]
    {:value value
     :us elapsed
     :searched (:searched @membership 0)
     :reused (:reused @membership 0)
     :questions (:questions @tabled 0)
     :denotation-hits (- (denotation-hits acl) hits)}))

(defn- cases
  "Each case: [label budget-key kind subject touched setup measured], where
  `setup` prepares the measured client at t0 (or is nil), `measured` is the
  timed request, and `touched` lists the permissions whose plans both arms
  compile. `:restored-check` builds its measured client from a snapshot."
  [store]
  (let [leaf (:leaf store)]
    (vec
     (concat
      (for [subject ["owner" "rootsharee"]]
        [(str subject " delete check after a delete walk") :operator-check-after-walk :check subject
         [:delete]
         #(walk % subject :delete true)
         #(check % subject :delete leaf true)])
      (for [subject ["owner" "rootsharee"]]
        [(str subject " read_account check after a delete walk") :operand-check-after-walk :check subject
         [:delete :read_account]
         #(walk % subject :delete true)
         #(check % subject :read_account leaf true)])
      (for [subject ["owner" "rootsharee"]]
        [(str subject " delete_top walk after a delete walk") :sibling-walk-after-walk :walk subject
         [:delete :delete_top]
         #(walk % subject :delete true)
         #(walk % subject :delete_top true)])
      [["owner inherited check after an inherited walk" :guarded-check-after-walk :check "owner"
        [:inherited]
        #(walk % "owner" :inherited true)
        #(check % "owner" :inherited leaf true)]]
      (for [subject ["owner" "rootsharee"]]
        [(str subject " delete check on a restored cache") :restored-check :check subject
         [:delete]
         #(walk % subject :delete true)
         #(check % subject :delete leaf true)])
      (for [subject ["owner" "rootsharee"]]
        [(str subject " first cached delete walk") :first-cached-walk :walk subject
         [:delete]
         nil
         #(walk % subject :delete true)])))))

(defn- measure-once
  "One measured and one reference observation at `t1`."
  [store [_ budget-key _ subject touched setup measured] t0 order]
  (let [measured-arm
        (fn []
          (let [clock (atom t0)
                acl
                (if (= :restored-check budget-key)
                  (let [source (client store clock)
                        _ (compile-plans! source subject touched)
                        _ (setup source)
                        snapshot (eacl.datahike/export-cache-snapshot source {:max-entries 65536})
                        target (client store clock)]
                    ;; Restoring installs a fresh derived-schema store, so the
                    ;; target compiles its plans after the restore.
                    (eacl.datahike/restore-cache-snapshot! target snapshot {:max-entries 65536})
                    (compile-plans! target subject touched)
                    target)
                  (let [acl (client store clock)]
                    (compile-plans! acl subject touched)
                    (when setup (setup acl))
                    acl))]
            (reset! clock (+ t0 step-ms))
            (observed acl #(measured acl))))
        reference-arm
        (fn []
          (let [clock (atom (+ t0 step-ms))
                acl (client store clock)]
            (compile-plans! acl subject touched)
            (if (= :first-cached-walk budget-key)
              (observed acl #(walk acl subject :delete false))
              (observed acl #(measured acl)))))]
    (if (even? order)
      (let [m (measured-arm) r (reference-arm)] [m r])
      (let [r (reference-arm) m (measured-arm)] [m r]))))

(defn- expected-answer
  "The uncached answer at `t1` on a fresh client."
  [store [_ _ kind subject _ _ measured] t1]
  (let [acl (client store (atom t1))]
    (binding [*uncached* true]
      (measured acl))))

(defn- median [xs] (nth (sort xs) (quot (count xs) 2)))

(defn- measure-case
  [store [label budget-key kind :as case]]
  (let [t0 fixture/now
        t1 (+ t0 step-ms)
        expected (expected-answer store case t1)
        n (get sample-counts kind)
        observations (vec (for [i (range (+ warmup-samples n))]
                            (measure-once store case t0 i)))
        kept (drop warmup-samples observations)
        measured (mapv first kept)
        reference (mapv second kept)
        factor (get budgets budget-key)
        m (median (map :us measured))
        r (median (map :us reference))]
    {:case label
     :budget-key budget-key
     :correct? (every? #(= expected (:value %)) (concat measured reference))
     :measured-us m
     :reference-us r
     :ratio (/ m r)
     :within-budget? (<= m (* factor r))
     :searched (median (map :searched measured))
     :reused (median (map :reused measured))
     :questions (median (map :questions measured))
     :denotation-hits (median (map :denotation-hits measured))
     :samples {:measured (mapv #(dissoc % :value) measured)
               :reference (mapv #(dissoc % :value) reference)}}))

(defn- reuse-shown?
  "The case's count requirement: what the measured request must not
  recompute."
  [{:keys [budget-key searched questions denotation-hits]}]
  (case budget-key
    :operator-check-after-walk (and (zero? searched) (zero? questions) (pos? denotation-hits))
    :operand-check-after-walk (and (zero? searched) (pos? denotation-hits))
    :sibling-walk-after-walk (zero? searched)
    :guarded-check-after-walk (and (zero? searched) (zero? questions) (pos? denotation-hits))
    :restored-check (and (zero? searched) (pos? denotation-hits))
    :first-cached-walk true))

(defn- write-results!
  [results]
  (let [file (io/file "target/benchmarks/set-algebra-reuse"
                      (str "gate-" (System/currentTimeMillis) ".edn"))]
    (io/make-parents file)
    (spit file (with-out-str
                 (pprint/pprint {:budgets budgets
                                 :environment (paired/environment)
                                 :results results})))
    (str file)))

(defn run-gate
  "Measures every case on both charts; returns the results and the report
  file."
  []
  (let [results
        (vec
         (for [shape shapes
               :let [store (assoc (fixture/setup shape) :lifecycle (random-uuid))
                     store (assoc store :leaf (first (last (:nodes store))))]
               case (cases store)]
           (assoc (measure-case store case) :accounts (count (:nodes store)))))]
    {:results results :report (write-results! results)}))

(deftest ^:benchmark set-algebra-results-are-reused-within-budget
  (let [{:keys [results report]} (run-gate)]
    (doseq [{:keys [accounts case correct? within-budget? measured-us reference-us ratio]
             :as result} results]
      (testing (format "%d accounts, %s: %.0f µs against %.0f µs (%.2f×)"
                       accounts case measured-us reference-us ratio)
        (is correct?)
        (is (reuse-shown? result) (str "see " report))
        (is within-budget? (str "see " report))))))
