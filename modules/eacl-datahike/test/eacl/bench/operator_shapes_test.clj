(ns eacl.bench.operator-shapes-test
  "Acceptance gate for the operator shapes of
  openspec/changes/2026-09-23-faster-qualified-and-recursive-operators:
  expiring shares of recursive operands, a union at an operator's root, and
  linearly guarded recursion through an intersection.

  Tagged `:benchmark`: the ordinary suites exclude it. Run it on an otherwise
  idle JVM. Every case interleaves the measured permission with its reference
  arms on one warm snapshot and asserts the budgets below, which were fixed
  before any sampling of the implementation. Each sample also asserts its
  answer. Raw samples are written under ignored
  target/benchmarks/operator-shapes/."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.test :refer [deftest is testing]]
            [eacl.bench.operator-shapes-fixture :as fixture]
            [eacl.bench.paired :as paired]
            [eacl.core :as eacl]))

(def budgets
  "The capability's budgets (spec `operand-bounded-set-algebra`), authored
  before sampling: the measured median is at most `factor` times the sum of
  its reference arms' medians."
  {:expiring-lookup 2.0
   :expiring-check 2.0
   :union-root-lookup 1.25
   :guarded-lookup 2.0
   :guarded-check 2.0})

(def shapes
  "636 accounts at depth 4 and 2,077 at depth 5."
  [[5 6 5 3] [6 5 4 4 3]])

(def ^:private run-options
  {:lookup {:warmups 10 :samples 31}
   :check {:warmups 200 :samples 301}})

(defn- lookup [subject permission]
  #(fixture/lookup-ids % subject permission))

(defn- check [subject permission path]
  #(fixture/check % subject permission path))

(defn- union-root-answer
  "`deleter_only ∪ (delete_granted ∩ read_account)` for a subject, from
  separate walks of the same snapshot."
  [snapshot subject]
  (let [readable (set (fixture/lookup-ids snapshot subject :read_account))]
    (set (concat (filter readable (fixture/lookup-ids snapshot subject :delete_granted))
                 (fixture/lookup-ids snapshot subject :deleter_only)))))

(defn- cases
  "[label budget-key kind measured-arm reference-arms correct?] per case.
  `correct?` receives the snapshot and every arm's answer, measured first."
  [store]
  (let [leaf (first (last (:nodes store)))
        split-leaf-in "root.3.1.0.0"
        split-leaf-out "root.3.1.1.0"
        intersection (fn [_ [measured granted readable]]
                       (= measured (filterv (set readable) granted)))
        conjunction (fn [_ [measured granted readable]]
                      (= measured (if (= :has-permission granted readable)
                                    :has-permission
                                    :no-permission)))
        union-root (fn [subject]
                     (fn [snapshot [measured]]
                       (= (union-root-answer snapshot subject) (set measured))))
        inherited (fn [subject]
                    (fn [_ [measured]]
                      (= (fixture/expected-inherited store subject) (set measured))))
        permissionship (fn [expected] (fn [_ [measured]] (= expected measured)))]
    [["expiring whole-chart share lookup" :expiring-lookup :lookup
      [:delete (lookup "rootsharee" :delete)]
      [[:delete-granted (lookup "rootsharee" :delete_granted)]
       [:read-account (lookup "rootsharee" :read_account)]]
      intersection]
     ["expiring subtree share lookup" :expiring-lookup :lookup
      [:delete (lookup "sharee" :delete)]
      [[:delete-granted (lookup "sharee" :delete_granted)]
       [:read-account (lookup "sharee" :read_account)]]
      intersection]
     ["expiring whole-chart share check, leaf" :expiring-check :check
      [:delete (check "rootsharee" :delete leaf)]
      [[:delete-granted (check "rootsharee" :delete_granted leaf)]
       [:read-account (check "rootsharee" :read_account leaf)]]
      conjunction]
     ["owner union-root lookup" :union-root-lookup :lookup
      [:delete-top (lookup "owner" :delete_top)]
      [[:delete (lookup "owner" :delete)]]
      (union-root "owner")]
     ["split union-root lookup" :union-root-lookup :lookup
      [:delete-top (lookup "split" :delete_top)]
      [[:delete (lookup "split" :delete)]]
      (union-root "split")]
     ["owner guarded-recursion lookup" :guarded-lookup :lookup
      [:inherited (lookup "owner" :inherited)]
      [[:reachable (lookup "owner" :reachable)]
       [:eligible-only (lookup "owner" :eligible_only)]]
      (inherited "owner")]
     ["split guarded-recursion lookup" :guarded-lookup :lookup
      [:inherited (lookup "split" :inherited)]
      [[:reachable (lookup "split" :reachable)]
       [:eligible-only (lookup "split" :eligible_only)]]
      (inherited "split")]
     ["owner guarded-recursion check, leaf" :guarded-check :check
      [:inherited (check "owner" :inherited leaf)]
      [[:reachable (check "owner" :reachable leaf)]
       [:eligible-only (check "owner" :eligible_only leaf)]]
      (permissionship :has-permission)]
     ["split guarded-recursion check, admitted leaf" :guarded-check :check
      [:inherited (check "split" :inherited split-leaf-in)]
      [[:reachable (check "split" :reachable split-leaf-in)]
       [:eligible-only (check "split" :eligible_only split-leaf-in)]]
      (permissionship :has-permission)]
     ["split guarded-recursion check, guarded-out leaf" :guarded-check :check
      [:inherited (check "split" :inherited split-leaf-out)]
      [[:reachable (check "split" :reachable split-leaf-out)]
       [:eligible-only (check "split" :eligible_only split-leaf-out)]]
      (permissionship :no-permission)]]))

(defn- measure
  [snapshot [label budget-key kind [measured-arm measured-f] references correct?]]
  (let [arms (into [[measured-arm measured-f]] references)
        answers (mapv (fn [[_ f]] (f snapshot)) arms)
        {:keys [warmups samples]} (get run-options kind)
        result (paired/run-paired!
                {:arms (mapv (fn [[arm f] answer]
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
        reference (reduce + (map (comp p50 first) references))
        factor (get budgets budget-key)]
    {:case label
     :kind kind
     :budget-key budget-key
     :correct? (boolean (correct? snapshot answers))
     :measured-us (p50 measured-arm)
     :references-us (into {} (map (fn [[arm]] [arm (p50 arm)])) references)
     :ratio (/ (p50 measured-arm) reference)
     :budget-us (* factor reference)
     :within-budget? (<= (p50 measured-arm) (* factor reference))
     :samples (into {} (map (fn [arm] [arm (get-in result [:arms arm :latency-us])]))
                    (map first arms))}))

(defn- write-results!
  [results]
  (let [file (io/file "target/benchmarks/operator-shapes"
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
                                 :raw (mapv #(select-keys % [:accounts :case :samples])
                                            results)})))
    (str file)))

(defn run-gate
  "Measures every case on both charts; returns the results and the report
  file."
  []
  (let [results
        (vec
         (for [shape shapes
               :let [store (fixture/setup shape)
                     snapshot (fixture/warm-snapshot store)]
               measured (try
                          (mapv #(measure snapshot %) (cases store))
                          (finally (eacl/release! snapshot)))]
           (assoc measured :accounts (count (:nodes store)))))]
    {:results results :report (write-results! results)}))

(deftest ^:benchmark operator-shapes-cost-what-their-operands-cost
  (let [{:keys [results report]} (run-gate)]
    (doseq [{:keys [accounts case correct? within-budget?
                    measured-us budget-us ratio]} results]
      (testing (format "%d accounts, %s: %.0f µs, budget %.0f µs (%.2f× reference)"
                       accounts case measured-us budget-us ratio)
        (is correct?)
        (is within-budget? (str "see " report))))))
