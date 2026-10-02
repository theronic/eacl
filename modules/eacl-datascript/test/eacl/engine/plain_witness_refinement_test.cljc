(ns eacl.engine.plain-witness-refinement-test
  "Executable refinement for plain cover witnesses (design D4 of
  openspec/changes/2026-10-01-backport-rust-engine-performance).

  A case is a random operator schema and store of the operand-order
  campaign: relationships are plain, already expired, expiring later,
  conditional on a Caveat, or guarded by one that can fault. The cover of a
  recursive operator lookup hands the evaluator `true` at the generator node
  for each candidate it first discovered through plain edges only, and the
  evaluator decides only the other operands. At two evaluation times and two
  contexts, for every folder permission, in both directions, the campaign
  requires:

  - every witness to be `true`, and exact evaluation of its node for the
    candidate, with no witness, to grant it;
  - exact evaluation to grant the same node to the same candidate in a second
    store that holds only the case's plain relationships, so the witness
    rests on no qualified edge;
  - detailed lookups and counts to answer the same with every witness
    withheld from the evaluator."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.set :as set]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification-test :as fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.operand-order-refinement-test :as order]
            [eacl.operator.delegation-refinement-test :as delegation]
            [eacl.operator.evaluator :as evaluator]
            [eacl.operator.recursive :as operator-recursive]
            [eacl.operator.vector-evaluator :as vector-evaluator]
            [eacl.relationships.staged :as staged]))

(def ^:private random-folder-permissions @#'delegation/random-folder-permissions)
(def ^:private random-relationships @#'order/random-relationships)
(def ^:private case-schema @#'order/case-schema)
(def ^:private store! @#'order/store!)
(def ^:private public-collection @#'order/public-collection)
(def ^:private residual-values @#'order/residual-values)
(def ^:private users @#'order/users)

(def ^:private times [1000 1250])

(def ^:private contexts [nil {"flag" true "m" {"x" false}}])

(defn- plain-store!
  "A store with the case's schema and objects that holds only the tuples
  `qualifiers` leaves plain."
  [permissions {:keys [folders teams tuples]} qualifiers]
  (let [conn (schema/create-conn)
        _ (eacl/write-schema! (api/make-client conn {:caveat-evaluator (fixtures/portable-evaluator (atom 0))})
                              (case-schema permissions))
        _ (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users folders teams)))
        db (ds/db conn)
        eid #(ds/entid db [:eacl/id %])
        relation-eid (fn [resource-type relation subject-type]
                       (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                     [resource-type relation subject-type]]))
        writer (qualifiers/writer conn)]
    (doseq [[subject-type subject relation resource-type resource :as tuple] tuples
            :when (nil? (get qualifiers tuple))]
      (staged/write! writer :create
                     [subject-type (eid subject) (relation-eid resource-type relation subject-type)
                      resource-type (eid resource)]
                     nil))
    conn))

(defn- client [conn clock]
  (api/make-client conn {:clock #(deref clock)
                         :caveat-evaluator (fixtures/portable-evaluator (atom 0))}))

(defn- exact-node
  "The exact decision of plan node `[permission node-id]` for one candidate
  of a delegated evaluation, with no witness."
  [{:keys [adapter plan qualification delegate]} candidate [permission node-id]]
  (evaluator/check-eids
   {:adapter adapter :plan plan :permission permission :node-id node-id
    :subject-type (:subject-type candidate) :subject-eid (:subject-eid candidate)
    :resource-eid (:resource-eid candidate)
    :qualification qualification :delegate delegate}))

(defn- point
  "A candidate named by its objects' ids."
  [db candidate]
  [(:eacl/id (ds/entity db (:subject-eid candidate)))
   (:eacl/id (ds/entity db (:resource-eid candidate)))])

(defn- with-witnesses-observed
  "Runs `f`; returns its value with the witnessed points the delegated
  evaluator received, `[subject resource node]`, and those among them whose
  witness is not `true` or whose node exact evaluation does not grant."
  [db f]
  (let [trusted vector-evaluator/check-many-trusted
        witnessed (atom #{})
        refuted (atom #{})
        value (with-redefs [vector-evaluator/check-many-trusted
                            (fn [options]
                              (let [decisions (trusted options)]
                                (doseq [candidate (:candidates options)
                                        [node value] (:evidence-witnesses candidate)
                                        :let [witness (conj (point db candidate) node)]]
                                  (swap! witnessed conj witness)
                                  (when-not (and (true? value)
                                                 (evidence/has? (exact-node options candidate node)))
                                    (swap! refuted conj witness)))
                                decisions))]
                (f))]
    {:value value :witnessed @witnessed :refuted @refuted}))

(defn- without-witnesses
  "Runs `f` with every witness withheld from the evaluator."
  [f]
  (let [cached-many operator-recursive/evaluate-cached-many]
    (with-redefs [operator-recursive/evaluate-cached-many
                  (fn [options] (cached-many (dissoc options :witnesses)))]
      (f))))

(defn- plainly-granted
  "The `witnesses` whose node exact evaluation grants to their candidate in
  the plain store `db`, among the candidates `f`'s evaluation examines
  there."
  [db witnesses f]
  (let [trusted vector-evaluator/check-many-trusted
        by-point (group-by #(subvec % 0 2) witnesses)
        granted (atom #{})]
    (with-redefs [vector-evaluator/check-many-trusted
                  (fn [options]
                    (let [decisions (trusted options)]
                      (doseq [candidate (:candidates options)
                              [_ _ node :as witness] (get by-point (point db candidate))
                              :when (evidence/has? (exact-node options candidate node))]
                        (swap! granted conj witness))
                      decisions))]
      (f))
    @granted))

(defn- run-case
  "{:stats ...} when every witness of the case is exact and plain and no
  answer depends on one, else {:divergence ...}."
  [seed]
  (let [state (atom seed)
        permissions (random-folder-permissions state)
        data (random-relationships state)
        {:keys [conn qualifiers]} (store! state permissions data)
        plain-conn (plain-store! permissions data qualifiers)
        db (ds/db conn)
        plain-db (ds/db plain-conn)
        clock (atom (first times))
        full (client conn clock)
        plain (client plain-conn clock)
        stats (atom {})
        count! (fn [k n] (swap! stats update k (fnil + 0) n))
        fail (fn [what detail]
               (merge {:seed seed :what what :schema (case-schema permissions)
                       :qualified (into (sorted-map) (filter (comp some? val)) qualifiers)
                       :plain (sort (keep (fn [[tuple q]] (when-not q tuple)) qualifiers))}
                      detail))]
    (if-let [divergence
             (first
              (for [time times
                    :let [_ (reset! clock time)]
                    context contexts
                    permission (sort (keys permissions))
                    [direction id] (concat (for [user users] [:forward user])
                                           (for [folder (:folders data)] [:reverse folder]))
                    :let [where {:permission permission :time time :context context direction id}
                          collect #(public-collection % direction id permission context false)
                          {:keys [value witnessed refuted]}
                          (with-witnesses-observed db #(collect full))
                          withheld (without-witnesses #(collect full))
                          granted (if (seq witnessed)
                                    (plainly-granted plain-db witnessed #(collect plain))
                                    #{})
                          _ (count! :collections 1)
                          _ (count! direction (count witnessed))
                          _ (count! :members (if (:error (:lookup value)) 0 (count (:lookup value))))]
                    divergence
                    (concat
                     (when (seq refuted)
                       [(fail :witness-not-exact (assoc where :witnesses refuted))])
                     (when-let [qualified (seq (set/difference witnessed granted))]
                       [(fail :witness-not-plain (assoc where :witnesses (set qualified)))])
                     (when (not= (residual-values value) (residual-values withheld))
                       [(fail :answer-depends-on-a-witness
                              (assoc where :actual value :withheld withheld))]))]
                divergence))]
      {:divergence divergence}
      {:stats @stats})))

(defn run-campaign
  "Runs `n` cases from seed `start`; returns the first divergence, or how many
  collections, members and witnesses in each direction were checked."
  [start n]
  (loop [seed start totals {:cases 0}]
    (if (= seed (+ start n))
      totals
      (let [{:keys [divergence stats]} (run-case seed)]
        (if divergence
          {:divergence divergence :cases (:cases totals)}
          (recur (inc seed) (-> (merge-with + totals stats) (update :cases inc))))))))

(deftest plain-witnesses-are-exact-and-plain-test
  ;; Cases whose plans have a generator node and whose stores have plain paths
  ;; to it: about a quarter of the generator's cases do.
  (let [[start n] #?(:clj [6 4] :cljs [8 2])
        {:keys [cases divergence] :as result} (run-campaign start n)]
    (is (nil? divergence) (pr-str divergence))
    (is (= n cases))
    (testing "the campaign reaches witnesses in both directions"
      (doseq [k [:forward :reverse :members]]
        (is (pos? (get result k 0)) (pr-str [k result]))))))
