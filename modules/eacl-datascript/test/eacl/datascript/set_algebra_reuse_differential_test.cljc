(ns eacl.datascript.set-algebra-reuse-differential-test
  "Cache-on/cache-off differential for set-algebra result reuse. Each case
  has a random schema of operator permissions and a guarded member over
  relationships that are plain, expiring or caveated. One caching client
  serves random walks (definite and detailed, paged), counts and checks under
  random caveat contexts while a clock advances past the relationships'
  deadlines, and midway the client is replaced by one restored from its
  exported cache. Every result must equal the same request with `:cache?
  false` at the same time."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.string :as str]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.engine.leveled-membership-refinement-test :as leveled]
            [eacl.engine.memoized-membership-refinement-test :as plain]
            [eacl.engine.stable-route :as stable-route]
            [eacl.relationships.staged :as staged]))

(def ^:private users ["u0" "u1"])

(def ^:private operator-templates
  "Operator permissions over the recursive union-only `granted` and
  `readable`: intersections, an exclusion, unions at an operator's root, and
  an arrow to an operator permission."
  [[:removable "granted & readable"]
   [:removable_top "deleter + (granted & readable)"]
   [:prunable "granted - readable"]
   [:either "(granted + readable) & (reader + deleter + eligible)"]
   [:gated "eligible & readable"]
   [:parent_removable "deleter + parent->removable"]])

(def ^:private guarded-templates
  "Members that recurse through a linear guard."
  ["reader + (parent->guarded & eligible)"
   "reader + (parent->guarded - blocked)"
   "owner + reader + ((parent->guarded & eligible) - blocked)"])

(defn- random-program [state]
  (let [chosen (filterv (fn [_] (plain/chance? state 60)) operator-templates)
        chosen (if (seq chosen) chosen [(first operator-templates)])
        ;; `parent_removable` reads `removable`.
        chosen (if (and (some #{:parent_removable} (map first chosen))
                        (not (some #{:removable} (map first chosen))))
                 (conj chosen (first operator-templates))
                 chosen)]
    (into {:guarded (plain/pick state guarded-templates)} chosen)))

(defn- render-schema [program]
  (str "caveat enabled(flag bool) { flag }\n"
       "definition user {}\n"
       "definition folder {\n"
       "  relation parent: folder\n"
       "  relation reader: user\n"
       "  relation owner: user\n"
       "  relation deleter: user\n"
       "  relation eligible: user\n"
       "  relation blocked: user\n"
       "  permission readable = reader + parent->readable\n"
       "  permission granted = deleter + parent->granted\n"
       (apply str (for [[permission body] (sort-by key program)]
                    (str "  permission " (name permission) " = " body "\n")))
       "}\n"))

(defn- random-relationships [state folders]
  (vec
   (distinct
    (concat
     (for [child folders parent folders :when (plain/chance? state 25)]
       [:folder parent :parent :folder child])
     (for [folder folders user users
           relation [:reader :owner :deleter :eligible :blocked]
           :when (plain/chance? state 25)]
       [:user user relation :folder folder])))))

(def ^:private caveatable #{:reader :eligible :blocked})

(defn- store!
  "A DataScript store with the case's schema and relationships, each written
  with a random qualifier: plain, already expired, expiring at 1100, 1200 or
  1300, or, on the caveatable relations, caveated."
  [state program folders relationships]
  (let [conn (datascript/create-conn)
        writer-client (datascript/make-client conn {})]
    (eacl/write-schema! writer-client (render-schema program))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (concat users folders)))
    (let [db (ds/db conn)
          eid #(ds/entid db [:eacl/id %])
          relation-eid (fn [relation subject-type]
                         (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                                       [:folder relation subject-type]]))
          caveat (ds/entid db [:eacl.caveat/name "enabled"])
          writer (qualifiers/writer conn)]
      (ds/transact! conn (vec (for [relation caveatable]
                                {:db/id (relation-eid relation :user)
                                 :eacl.relation/caveats [caveat]
                                 :eacl.relation/allows-unqualified? true})))
      (doseq [[subject-type subject relation resource-type resource] relationships]
        (staged/write! writer :create
                       [subject-type (eid subject) (relation-eid relation subject-type)
                        resource-type (eid resource)]
                       (leveled/random-qualifier
                        state (and (= :user subject-type) (contains? caveatable relation)) caveat)))
      conn)))

(defn- walk
  "Every item of a complete walk, page by page."
  [client query]
  (loop [after nil items []]
    (let [page (eacl/lookup-resources client (cond-> query after (assoc :after after)))
          items (into items (:data page))]
      (if (get-in page [:page-info :has-next-page?])
        (recur (get-in page [:page-info :end-cursor]) items)
        items))))

(defn- decision [result]
  (select-keys result [:permissionship :missing-fields :residual]))

(defn- random-request
  "One request as `(fn [client cache?] result)`, with a description."
  [state permissions folders]
  (let [subject (eacl/spice-object :user (plain/pick state users))
        permission (plain/pick state permissions)
        context (plain/pick state [{} {"flag" true} {"flag" false}])]
    (case (plain/next-int! state 3)
      0 (let [query {:subject subject :permission permission :resource/type :folder
                     :first (plain/pick state [1 2 100]) :caveat-context context
                     :result-policy (plain/pick state [:definite :detailed])}]
          [[:walk query] (fn [client cache?] (walk client (assoc query :cache? cache?)))])
      1 (let [query {:subject subject :permission permission :caveat-context context
                     :resource (eacl/spice-object :folder (plain/pick state folders))}]
          [[:check query]
           (fn [client cache?] (decision (eacl/check-permission client (assoc query :cache? cache?))))])
      2 (let [query {:subject subject :permission permission :resource/type :folder
                     :caveat-context context}]
          [[:count query]
           (fn [client cache?] (:count (eacl/count-resources client (assoc query :cache? cache?))))]))))

(defn- denotation-hits [client]
  (get-in (datascript/cache-stats client) [:subproblems :denotation-hits] 0))

(defn run-case
  "Runs one seeded case; returns its counters, with `:failure` on the first
  divergence."
  [seed]
  (let [state (atom seed)
        program (random-program state)
        folders (mapv #(str "f" %) (range (+ 3 (plain/next-int! state 4))))
        conn (store! state program folders (random-relationships state folders))
        clock (atom 1000)
        options {:clock #(deref clock)
                 :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))}
        permissions (into [:readable :granted] (keys program))
        steps (+ 20 (plain/next-int! state 20))
        restore-at (quot steps 2)
        membership (atom {})]
    (binding [stable-route/*membership-stats* membership]
      (loop [step 0
             client (datascript/make-client conn options)
             counters {:requests 0 :restores 0 :hits 0}]
        (cond
          (= step steps)
          (-> counters
              (update :hits + (denotation-hits client))
              (assoc :reused (:reused @membership 0)))

          (= step restore-at)
          (let [bounds {:max-entries 4096}
                snapshot (datascript/export-cache-snapshot client bounds)
                restored (datascript/make-client conn options)]
            (datascript/restore-cache-snapshot! restored snapshot bounds)
            (recur (inc step) restored
                   (-> counters
                       (update :hits + (denotation-hits client))
                       (update :restores inc))))

          :else
          (do
            (when (plain/chance? state 30)
              (swap! clock + (plain/pick state [1 50 100 150 250])))
            (let [[description request] (random-request state permissions folders)
                  outcome (fn [cache?]
                            (try (request client cache?)
                                 (catch #?(:clj Exception :cljs :default) error
                                   [:error (:type (ex-data error))])))
                  cached (outcome true)
                  fresh (outcome false)]
              (if (= fresh cached)
                (recur (inc step) client (update counters :requests inc))
                {:failure {:seed seed :step step :time @clock :request description
                           :cached cached :fresh fresh :schema (render-schema program)}}))))))))

(defn run-campaign
  "Runs cases seeded `first-seed`..; stops at the first divergence, returned
  under `:failure`."
  [first-seed cases]
  (reduce (fn [totals seed]
            (let [result (run-case seed)]
              (if-let [failure (:failure result)]
                (reduced (assoc totals :failure failure))
                (-> (merge-with + totals result) (update :cases inc)))))
          {:cases 0}
          (range first-seed (+ first-seed cases))))

(deftest cached-results-equal-fresh-results-test
  (let [report (run-campaign 1 #?(:clj 40 :cljs 8))]
    (is (nil? (:failure report)) (pr-str (:failure report)))
    (testing "the campaign reuses operand and operator decisions, and restores"
      (is (pos? (:hits report)))
      (is (pos? (:reused report)))
      (is (pos? (:restores report))))))
