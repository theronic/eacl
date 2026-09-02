(ns eacl.engine.stable-discovery-gate-test
  "Production gates for the sealed planner and stable reducer
  (tasks 3.3 CLJ, 3.4 engine-side controls, 5.5 CLJ).

  Three families:

  1. Independent-oracle equality: a naive name-based fixpoint evaluator —
     sharing only the adapter's schema-definition reads with the compiler,
     and nothing with the reducer's plan/order/admission machinery —
     recomputes every fixture denotation in both directions.

  2. Sealed-plan bridge: fingerprints are invariant under schema clause
     order; certified ranks match an independent Bellman-Ford oracle; the
     linear certificate checker kills mutated certificates.

  3. Mutation controls: deliberately wrong engine variants (entity-only
     interior keys, per-rule root keys, unreversed push order, eager
     multi-value release, host-order buckets, fetched-end resume) must be
     killed by the oracle/order gates — proving the gates detect exactly
     the corruption classes the specs forbid."
  (:require [clojure.string :as string]
            [clojure.test :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.baseline.capture :as capture]
            [eacl.backend.v8 :as backend]
            [eacl.core :as eacl]
            [eacl.datascript.backend :as datascript-backend]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]))

;; ---------------------------------------------------------------------------
;; Shared fixture plumbing
;; ---------------------------------------------------------------------------

(defn- seeded-adapter
  [fixture]
  (let [{:keys [conn]} (capture/seed-client! fixture)
        db (ds/db conn)]
    {:db db
     :adapter (datascript-backend/basis-adapter
               db
               {:object-id->entid
                (fn [snapshot object-id]
                  (ds/entid snapshot [:eacl/id object-id]))
                :entid->object-id
                (fn [snapshot internal-id]
                  (:eacl/id (ds/entity snapshot internal-id)))})}))

(defn- eid [db external-id] (ds/entid db [:eacl/id external-id]))
(defn- external [db internal-id] (:eacl/id (ds/entity db internal-id)))

(defn- run-forward-ids
  [{:keys [db adapter]} plan subject-id options]
  (if-let [subject-eid (eid db subject-id)]
    (mapv #(external db %)
          (:results (reducer/run-forward
                     (merge {:adapter adapter :plan plan
                             :subject-type :user :subject-eid subject-eid
                             :target 100000}
                            options))))
    []))

(defn- run-reverse-ids
  [{:keys [db adapter]} plan resource-id options]
  (if-let [resource-eid (eid db resource-id)]
    (mapv #(external db %)
          (:results (reducer/run-reverse
                     (merge {:adapter adapter :plan plan
                             :subject-type :user :resource-eid resource-eid
                             :target 100000}
                            options))))
    []))

;; ---------------------------------------------------------------------------
;; 1. Independent naive-fixpoint oracle (task 5.5)
;; ---------------------------------------------------------------------------

(defn- name-rules
  "Name-based rules per node, read from the adapter's definition ops (the
  same fail-closed reads the compiler uses) but keeping relation NAMES.
  Evaluation below never touches the sealed plan, ranks, or admission."
  [adapter [resource-type permission :as node]]
  (mapcat
   (fn [{:keys [source-relation-name target-type target-name]}]
     (cond
       (and (= :self source-relation-name) (= :relation target-type))
       [{:kind :relation :relation target-name}]

       (and (= :self source-relation-name) (= :permission target-type))
       [{:kind :self :target-node [resource-type target-name]}]

       (= :permission target-type)
       (for [{:keys [subject-type]}
             (backend/invoke adapter :relation-defs resource-type
                             source-relation-name)]
         {:kind :arrow-permission :via source-relation-name
          :target-node [subject-type target-name]})

       :else
       [{:kind :arrow-relation :via source-relation-name
         :target-relation target-name}]))
   (backend/invoke adapter :permission-defs resource-type permission)))

(defn- oracle-grants
  "Naive least-fixpoint: the set of [node resource-ref] grants the subject
  reaches, iterating all reachable rules over the raw relationship list
  until nothing changes. Relationship refs are [type id] pairs."
  [adapter relationships subject root-node]
  (let [rel-index (group-by (fn [{:keys [relation resource]}]
                              [[(:type resource) (:id resource)] relation])
                            relationships)
        subjects-of (fn [resource-ref relation]
                      (map (fn [{:keys [subject]}]
                             [(:type subject) (:id subject)])
                           (rel-index [resource-ref relation])))
        subject-ref [(:type subject) (:id subject)]
        all-refs (into #{} (mapcat (fn [{:keys [subject resource]}]
                                     [[(:type subject) (:id subject)]
                                      [(:type resource) (:id resource)]]))
                       relationships)
        nodes (loop [frontier [root-node] seen #{}]
                (if-let [node (first frontier)]
                  (if (seen node)
                    (recur (rest frontier) seen)
                    (recur (into (rest frontier)
                                 (keep :target-node
                                       (name-rules adapter node)))
                           (conj seen node)))
                  seen))
        rules-by-node (into {} (map (fn [node] [node (name-rules adapter node)]))
                            nodes)]
    (loop [grants #{}]
      (let [next
            (into grants
                  (for [node nodes
                        :let [[resource-type _] node]
                        resource-ref all-refs
                        :when (= resource-type (first resource-ref))
                        rule (rules-by-node node)
                        :when (case (:kind rule)
                                :relation
                                (some #{subject-ref}
                                      (subjects-of resource-ref
                                                   (:relation rule)))
                                :self
                                (contains? grants
                                           [(:target-node rule) resource-ref])
                                :arrow-permission
                                (some (fn [intermediate]
                                        (contains? grants
                                                   [(:target-node rule)
                                                    intermediate]))
                                      (subjects-of resource-ref (:via rule)))
                                :arrow-relation
                                (some (fn [intermediate]
                                        (some #{subject-ref}
                                              (subjects-of
                                               intermediate
                                               (:target-relation rule))))
                                      (subjects-of resource-ref (:via rule))))]
                    [node resource-ref]))]
        (if (= next grants) grants (recur next))))))

(defn- oracle-forward
  [adapter fixture subject]
  (let [root [(:resource-type fixture) (:permission fixture)]]
    (->> (oracle-grants adapter (:relationships fixture) subject root)
         (keep (fn [[node [_ id]]] (when (= node root) id)))
         sort vec)))

(defn- oracle-reverse
  [adapter fixture resource]
  (let [root [(:resource-type fixture) (:permission fixture)]
        users (into #{} (comp (mapcat (juxt :subject :resource))
                              (filter #(= :user (:type %))))
                    (:relationships fixture))]
    (->> users
         (filter (fn [user]
                   (contains? (oracle-grants adapter
                                             (:relationships fixture)
                                             user root)
                              [root [(:type resource) (:id resource)]])))
         (map :id)
         sort vec)))

(deftest independent-oracle-equality-test
  (doseq [fixture-key (keys capture/fixtures)]
    (testing (str fixture-key)
      (let [fixture ((get capture/fixtures fixture-key))
            seeded (seeded-adapter fixture)
            plan (sealed-plan/seal-plan
                  (:adapter seeded)
                  [(:resource-type fixture) (:permission fixture)])]
        (doseq [[principal-key principal] (:principals fixture)]
          (is (= (oracle-forward (:adapter seeded) fixture principal)
                 (vec (sort (run-forward-ids seeded plan (:id principal) {}))))
              (str fixture-key " forward " principal-key)))
        (doseq [[label resource] (:reverse-resources fixture)]
          (is (= (oracle-reverse (:adapter seeded) fixture resource)
                 (vec (sort (run-reverse-ids seeded plan (:id resource) {}))))
              (str fixture-key " reverse " label)))))))

;; ---------------------------------------------------------------------------
;; 2. Sealed-plan bridge (task 3.3, CLJ)
;; ---------------------------------------------------------------------------

(defn- reorder-schema
  "Reverses the definition blocks and the union branches inside each
  permission line: structurally identical schema, different clause order."
  [schema]
  (let [blocks (string/split schema #"\n\n")
        flip-unions (fn [block]
                      (string/replace
                       block
                       #"permission (\w+) = ([^\n]+)"
                       (fn [[_ name body]]
                         (str "permission " name " = "
                              (string/join " + "
                                           (reverse (map string/trim
                                                         (string/split body #"\+"))))))))]
    (string/join "\n\n" (reverse (mapv flip-unions blocks)))))

(deftest fingerprint-is-invariant-under-schema-clause-order-test
  (doseq [fixture-key [:explorer-acyclic :broad-union :mutual-mixed]]
    (testing (str fixture-key)
      (let [fixture ((get capture/fixtures fixture-key))
            reordered (assoc fixture :schema (reorder-schema (:schema fixture)))
            root [(:resource-type fixture) (:permission fixture)]
            plan-a (sealed-plan/seal-plan (:adapter (seeded-adapter fixture))
                                          root)
            plan-b (sealed-plan/seal-plan (:adapter (seeded-adapter reordered))
                                          root)]
        (is (= (mapv #(dissoc % :relation-eid :via-relation-eid
                              :target-relation-eid)
                     (:rules plan-a))
               (mapv #(dissoc % :relation-eid :via-relation-eid
                              :target-relation-eid)
                     (:rules plan-b)))
            "semantic rules, ordinals, and ranks are clause-order invariant")))))

(defn- bellman-ford
  "Independent shortest-distance oracle over the plan's permission edges."
  [node-count root-index edges]
  (loop [distance (assoc (vec (repeat node-count nil)) root-index 0)
         iteration 0]
    (if (> iteration node-count)
      distance
      (let [next (reduce (fn [distance {:keys [from to cost]}]
                           (let [dt (distance to)
                                 candidate (when dt (+ dt cost))
                                 current (distance from)]
                             (if (and candidate (or (nil? current)
                                                    (< candidate current)))
                               (assoc distance from candidate)
                               distance)))
                         distance edges)]
        (if (= next distance) distance (recur next (inc iteration)))))))

(deftest rank-certificate-matches-independent-oracle-test
  (doseq [fixture-key (keys capture/fixtures)]
    (testing (str fixture-key)
      (let [fixture ((get capture/fixtures fixture-key))
            plan (sealed-plan/seal-plan
                  (:adapter (seeded-adapter fixture))
                  [(:resource-type fixture) (:permission fixture)])]
        (is (= (bellman-ford (count (:nodes plan)) (:root-index plan)
                             (:edges plan))
               (get-in plan [:certificate :distance]))
            "certified distances equal Bellman-Ford")
        (testing "the linear checker kills mutated certificates"
          (let [certificate (:certificate plan)
                node-count (count (:nodes plan))
                root-index (:root-index plan)
                edges (:edges plan)
                reachable (keep-indexed
                           (fn [i d] (when (and d (not= i root-index)) i))
                           (:distance certificate))]
            (when-let [victim (first reachable)]
              (is (not (sealed-plan/valid-certificate?
                        (update-in certificate [:distance victim]
                                   (fnil dec 1))
                        node-count root-index edges))
                  "understated distance is killed")
              (is (not (sealed-plan/valid-certificate?
                        (assoc-in certificate [:witness-edge victim]
                                  (count edges))
                        node-count root-index edges))
                  "missing witness is killed")
              (is (not (sealed-plan/valid-certificate?
                        (assoc-in certificate [:hops victim] node-count)
                        node-count root-index edges))
                  "oversized hop is killed"))))))))

;; ---------------------------------------------------------------------------
;; 3. Mutation controls (task 3.4, engine-side)
;; ---------------------------------------------------------------------------

(defn- alias-seeded []
  (let [owner (eacl/spice-object :user "owner-1")
        account (eacl/spice-object :account "alias-account")
        fixture {:schema "definition user {}

   definition account {
     relation owner: user
     permission base = owner
     permission left = base + right
     permission right = left
   }"
                 :objects [owner account]
                 :relationships [(eacl/->Relationship owner :owner account)]
                 :resource-type :account
                 :permission :left
                 :principals {:owner owner}
                 :reverse-resources {}}]
    (assoc (seeded-adapter fixture) :fixture fixture)))

(def ^:private mutation-fixtures
  "Must include a fixture with several simultaneously productive branches
  (explorer-acyclic: account, team, and vpc arrows all reach every server,
  with consecutive eids) — sibling-order and resume-bound corruption is
  invisible on single-productive-branch principals."
  [:explorer-acyclic :explorer-recursive :group-star :mutual-mixed
   :broad-union])

(defn- reference-sequences []
  (vec (for [fixture-key mutation-fixtures]
         (let [fixture ((get capture/fixtures fixture-key))
               seeded (seeded-adapter fixture)
               plan (sealed-plan/seal-plan
                     (:adapter seeded)
                     [(:resource-type fixture) (:permission fixture)])
               principal (val (first (:principals fixture)))]
           {:fixture-key fixture-key :seeded seeded :plan plan
            :principal principal
            :reference (run-forward-ids seeded plan (:id principal) {})}))))

(defn- mutant-killed-somewhere?
  "Runs every mutation fixture under `with-mutation` and reports whether at
  least one fixture's output diverges from the reference (sequence or set),
  or the engine's own invariants abort the run."
  [references with-mutation]
  (boolean
   (some (fn [{:keys [seeded plan principal reference]}]
           (try
             (not= reference
                   (with-mutation
                     #(run-forward-ids seeded plan (:id principal) {})))
             (catch clojure.lang.ExceptionInfo _ true)))
         references)))

(deftest mutation-controls-test
  (let [references (reference-sequences)]
    (testing "entity-only interior admission key loses aliased results"
      (let [seeded (alias-seeded)
            plan (sealed-plan/seal-plan (:adapter seeded) [:account :left])
            original reducer/work-id]
        (with-redefs [reducer/work-id
                      (fn [item]
                        (if (= :grant (:kind item))
                          [:grant (:resource-eid item)]
                          (original item)))]
          (is (not= ["alias-account"]
                    (try (run-forward-ids seeded plan "owner-1" {})
                         (catch clojure.lang.ExceptionInfo _ ::aborted)))
              "the alias fixture kills the entity-only key"))))
    (testing "per-rule root admission key double-emits union arms"
      (let [original reducer/work-id
            killed? (mutant-killed-somewhere?
                     references
                     (fn [run]
                       (with-redefs [reducer/work-id
                                     (fn [item]
                                       (if (= :grant (:kind item))
                                         [:grant (:ordinal (:rule item))
                                          (:resource-eid item)]
                                         (original item)))]
                         (run))))]
        (is killed? "per-rule grant keys are killed")))
    (testing "unreversed successor push order corrupts the sequence"
      (let [original @#'reducer/schedule
            killed? (mutant-killed-somewhere?
                     references
                     (fn [run]
                       (with-redefs [reducer/schedule
                                     (fn [state residual new-work]
                                       (original state residual
                                                 (reverse new-work)))]
                         (run))))]
        (is killed? "host push order is killed")))
    (testing "host-ordered alternative buckets corrupt the sequence"
      (let [original @#'reducer/grant-successors
            killed? (mutant-killed-somewhere?
                     references
                     (fn [run]
                       (with-redefs [reducer/grant-successors
                                     (fn [plan node eid]
                                       (vec (reverse (original plan node
                                                               eid))))]
                         (run))))]
        (is killed? "bucket order corruption is killed")))
    (testing "fetched-end resume skips released values"
      ;; Exercised only when values actually come from refetch: force
      ;; physical width one with no buffer retention (output-identical for
      ;; the correct engine by the invariance gate).
      (let [original @#'reducer/release-one
            forced {:physical-chunk-size 1 :sidecar-cap 0}
            killed?
            (boolean
             (some (fn [{:keys [seeded plan principal reference]}]
                     (try
                       (not= reference
                             (with-redefs [reducer/release-one
                                           (fn [state item descriptor]
                                             (let [[state value residual]
                                                   (original state item
                                                             descriptor)]
                                               [state value
                                                (when residual
                                                  (update residual :bound-eid
                                                          (fnil inc 0)))]))]
                               (run-forward-ids seeded plan (:id principal)
                                                forced)))
                       (catch clojure.lang.ExceptionInfo _ true)))
                   references))]
        (is killed? "wrong resume bound is killed")))
    (testing "eager whole-chunk release changes discovery order"
      ;; The refuted eager-admission pattern needs the overlap geometry:
      ;; the first scan value's subtree reaches a LATER value of the same
      ;; scan. One-value release discovers f-c inside f-a's subtree
      ;; ([a c b ...]); eager whole-chunk admission pre-admits f-c as a
      ;; sibling ([a b c ...]).
      (let [alice (eacl/spice-object :user "alice")
            folders (mapv #(eacl/spice-object :folder (str "f-" %))
                          ["a" "b" "c" "d"])
            fixture {:schema "definition user {}

   definition folder {
     relation parent: folder
     relation member: user
     permission view = member + parent->view
   }"
                     :objects (into [alice] folders)
                     :relationships
                     [(eacl/->Relationship alice :member (folders 0))
                      (eacl/->Relationship alice :member (folders 1))
                      (eacl/->Relationship alice :member (folders 2))
                      ;; f-c inherits from f-a: a's subtree reaches c
                      (eacl/->Relationship (folders 0) :parent (folders 2))
                      ;; and f-d only via the subtree
                      (eacl/->Relationship (folders 0) :parent (folders 3))]
                     :resource-type :folder
                     :permission :view
                     :principals {:alice alice}
                     :reverse-resources {}}
            seeded (seeded-adapter fixture)
            plan (sealed-plan/seal-plan (:adapter seeded) [:folder :view])
            reference (run-forward-ids seeded plan "alice" {})
            original @#'reducer/scan-transition
            eager (with-redefs
                   [reducer/scan-transition
                    (fn [state item descriptor value->successors]
                      ;; release the whole available chunk in one transition
                      (loop [state state
                             item item
                             successors []]
                        (let [[state value residual]
                              (@#'reducer/release-one state item descriptor)]
                          (cond
                            (nil? value)
                            (@#'reducer/schedule state residual successors)
                            (nil? residual)
                            (@#'reducer/schedule
                             state nil (into successors
                                             (if value->successors
                                               (value->successors value)
                                               [(@#'reducer/scan-successor
                                                 item value)])))
                            :else
                            (recur state residual
                                   (into successors
                                         (if value->successors
                                           (value->successors value)
                                           [(@#'reducer/scan-successor
                                             item value)])))))))
                    (run-forward-ids seeded plan "alice" {})])]
        (is (= ["f-a" "f-c" "f-d" "f-b"] reference)
            "one-value release discovers the subtree before later siblings")
        (is (not= reference eager) "eager whole-chunk release is killed")))))
