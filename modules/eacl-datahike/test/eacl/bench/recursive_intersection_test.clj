(ns eacl.bench.recursive-intersection-test
  "Acceptance gate for `&` over two recursive permissions.

  Tagged `:benchmark`: the ordinary suites exclude it. Run it on an otherwise
  idle JVM. Every case interleaves the intersection with both operands in one
  paired run on one warm snapshot and asserts the budgets below, which were
  fixed before any sample was taken. Each sample also asserts its answer, so
  no arm can pass by computing something else. Raw samples are written under
  ignored target/benchmarks/recursive-intersection/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing]]
            [eacl.bench.paired :as paired]
            [eacl.bench.recursive-intersection-fixture :as fixture]
            [eacl.core :as eacl]))

(def budgets
  "The brief's acceptance budgets, authored before sampling: warm, on both
  fixtures at 636 and 2,077 accounts, the median intersection lookup (a full
  cursor walk) is at most `:lookup-factor` times the sum of its two operands'
  median lookups, and the median intersection check at most `:check-factor`
  times the sum of its operands' median checks."
  {:lookup-factor 2.0
   :check-factor 2.0})

(def shapes
  "636 accounts at depth 4 and 2,077 at depth 5."
  [[5 6 5 3] [6 5 4 4 3]])

(def ^:private run-options
  {:lookup {:warmups 10 :samples 31}
   :check {:warmups 200 :samples 301}})

(defn- cases
  "[label kind arms expected] per measured comparison. The first arm is the
  intersection; the others are its operands."
  [{:keys [kind leaf]}]
  (let [owner (fixture/user "owner")
        split (fixture/user "split")
        ;; split may delete and read this account (both fixtures) ...
        readable (fixture/account (case kind :minimal "root.3.0" :ledger "root.3.1.0"))
        ;; ... and may delete but not read this one.
        unreadable (fixture/account (case kind :minimal "root.0.0" :ledger "root.3.0.0"))
        lookup (fn [subject]
                 [[:intersection #(fixture/lookup-ids % subject :delete)]
                  [:delete-granted #(fixture/lookup-ids % subject :delete_granted)]
                  [:read-account #(fixture/lookup-ids % subject :read_account)]])
        check (fn [subject resource]
                [[:intersection #(fixture/check % subject :delete resource)]
                 [:delete-granted #(fixture/check % subject :delete_granted resource)]
                 [:read-account #(fixture/check % subject :read_account resource)]])]
    [["owner lookup" :lookup (lookup owner)]
     ["split lookup" :lookup (lookup split)]
     ["owner check, leaf" :check (check owner (fixture/account leaf))]
     ["split check, readable" :check (check split readable)]
     ["split check, unreadable" :check (check split unreadable)]]))

(defn- expected-intersection
  "The intersection's answer from its operands' answers: the operand walk's
  order filtered by the other operand's set, or the conjunction of checks."
  [kind [_ granted] [_ readable]]
  (case kind
    :lookup (filterv (set readable) granted)
    :check (and granted readable)))

(defn- measure
  [snapshot [label kind arms]]
  (let [answers (mapv (fn [[arm f]] [arm (f snapshot)]) arms)
        [[_ intersection] granted readable] answers
        expected (expected-intersection kind granted readable)
        {:keys [warmups samples]} (get run-options kind)
        result (paired/run-paired!
                {:arms (mapv (fn [[arm f] [_ answer]]
                               [arm (fn [_]
                                      (let [value (f snapshot)]
                                        (when-not (= answer value)
                                          (throw (ex-info "A measured arm changed its answer."
                                                          {:case label :arm arm})))
                                        value))])
                             arms answers)
                 :warmups warmups
                 :samples samples})
        p50 #(get-in result [:arms % :latency-us :p50])
        operands (+ (p50 :delete-granted) (p50 :read-account))
        factor (get budgets (case kind :lookup :lookup-factor :check :check-factor))]
    {:case label
     :kind kind
     :correct? (= expected intersection)
     :intersection-us (p50 :intersection)
     :operands-us {:delete-granted (p50 :delete-granted)
                   :read-account (p50 :read-account)}
     :ratio (/ (p50 :intersection) operands)
     :budget-us (* factor operands)
     :within-budget? (<= (p50 :intersection) (* factor operands))
     :samples (into {} (map (fn [arm] [arm (get-in result [:arms arm :latency-us])]))
                    (map first arms))}))

(defn- write-results!
  [results]
  (let [file (io/file "target/benchmarks/recursive-intersection"
                      (str "gate-" (System/currentTimeMillis) ".edn"))]
    (io/make-parents file)
    (spit file (with-out-str
                 (pprint/pprint {:budgets budgets
                                 :environment (paired/environment)
                                 :results (mapv #(update % :samples
                                                         (fn [samples]
                                                           (update-vals samples
                                                                        (fn [summary] (dissoc summary :raw)))))
                                                results)
                                 :raw (mapv #(select-keys % [:fixture :accounts :case :samples])
                                            results)})))
    (str file)))

(deftest ^:benchmark recursive-intersection-costs-what-its-operands-cost
  (let [results
        (vec
         (for [kind [:minimal :ledger]
               shape shapes
               :let [store (fixture/setup kind shape)
                     snapshot (fixture/warm-snapshot store)]
               measured (try
                          (mapv #(measure snapshot %) (cases store))
                          (finally (eacl/release! snapshot)))]
           (assoc measured :fixture kind :accounts (count (:nodes store)))))
        report (write-results! results)]
    (doseq [{:keys [fixture accounts case correct? within-budget?
                    intersection-us budget-us ratio]} results]
      (testing (format "%s, %d accounts, %s: %.0f µs, budget %.0f µs (%.2f× operands)"
                       (name fixture) accounts case intersection-us budget-us ratio)
        (is correct?)
        (is within-budget? (str "see " report))))))
