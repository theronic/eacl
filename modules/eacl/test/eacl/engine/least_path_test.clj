(ns eacl.engine.least-path-test
  "Property harness for the least-path evaluator
  (acyclic-keyset-pagination, task 3.4). Runs BEFORE any engine routing:

  - result-set equality against the stable-discovery reducer
    (`run-forward`/`run-reverse`) on the same sealed plan and tuples;
  - order and coordinate equality against an independent naive oracle
    that materializes every derivation, keeps each entity's least, and
    sorts;
  - resume-from-every-boundary equals the suffix; descending equals the
    reverse of ascending; random page walks reconstruct the full
    sequence;
  - stream-opens per resumed page bounded by traversal work, never by
    the boundary ordinal (the demo pathology regression check)."
  (:require [clojure.test :refer [deftest is testing]]
            [eacl.backend.v8 :as backend]
            [eacl.engine.least-path :as lp]
            [eacl.engine.sealed-plan :as sealed-plan]
            [eacl.engine.stable-reducer :as reducer]))

;; ---------------------------------------------------------------------------
;; Synthetic snapshot adapter over an in-memory tuple store
;; ---------------------------------------------------------------------------

(defn- scan-slice
  [eids {:keys [direction bound-eid inclusive-bound?]}]
  (let [sorted (vec (sort eids))
        sorted (if (= :desc direction) (vec (rseq sorted)) sorted)
        beyond? (if (= :desc direction)
                  (if inclusive-bound? #(> % bound-eid) #(>= % bound-eid))
                  (if inclusive-bound? #(< % bound-eid) #(<= % bound-eid)))]
    (if (some? bound-eid) (vec (drop-while beyond? sorted)) sorted)))

(defn synthetic-adapter
  "A complete v8 adapter over {:relations [...] :permissions [...]}
  definitions and a tuple set #{[subject-type s rel-id resource-type r]}."
  [{:keys [relations permissions tuples]}]
  (let [tuples (set tuples)]
    (backend/make-adapter
     {:id :synthetic
      :capabilities {:consistency #{:minimize-latency}
                     :snapshots #{:current}
                     :source #{:stable-scope :source-lifecycle
                               :native-revision :order-hint}
                     :cursor #{:forward :reverse}
                     :transactions #{}
                     :cache-proofs #{}
                     :runtime #{:clj}}
      :identity-contract :synthetic-test
      :operations
      {:snapshot-id (fn [] {:database-id "synthetic" :basis-t 1})
       :source-scope (fn [] {:source-id {:database-id "synthetic"}
                             :branch nil})
       :source-lifecycle (fn [] "synthetic-lifecycle")
       :native-revision (fn [] {:revision 1 :exact-locator 1})
       :order-hint (fn [] 1)
       :exact-locator (fn [] 1)
       :select-current (fn [] nil)
       :select-authoritative (fn [_] nil)
       :select-at-least (fn [_ _] nil)
       :select-exact (fn [_ _] nil)
       :object-id->internal (fn [id] id)
       :internal-id->object (fn [id] id)
       :relation-defs
       (fn [resource-type relation-name]
         (filterv #(and (= resource-type (:resource-type %))
                        (= relation-name (:relation-name %)))
                  relations))
       :permission-defs
       (fn [resource-type permission-name]
         (filterv #(and (= resource-type (:resource-type %))
                        (= permission-name (:permission-name %)))
                  permissions))
       :subject->resources
       (fn [subject-type subject-id relation-id resource-type opts]
         (scan-slice
          (for [[st s rel rt r] tuples
                :when (and (= st subject-type) (= s subject-id)
                           (= rel relation-id) (= rt resource-type))]
            r)
          opts))
       :resource->subjects
       (fn [resource-type resource-id relation-id subject-type opts]
         (scan-slice
          (for [[st s rel rt r] tuples
                :when (and (= rt resource-type) (= r resource-id)
                           (= rel relation-id) (= st subject-type))]
            s)
          opts))
       :direct-match?
       (fn [subject-type subject-id relation-id resource-type resource-id]
         (contains? tuples
                    [subject-type subject-id relation-id
                     resource-type resource-id]))
       :all-permission-nodes
       (fn [] (set (map (juxt :resource-type :permission-name)
                        permissions)))}})))

;; ---------------------------------------------------------------------------
;; Fixtures
;; ---------------------------------------------------------------------------

(def ^:private rel-owner 101)   ;; doc.owner: user
(def ^:private rel-org 102)     ;; doc.org: org
(def ^:private rel-member 103)  ;; org.member: user
(def ^:private rel-admin 104)   ;; org.admin: user
(def ^:private rel-team 105)    ;; org.team: team
(def ^:private rel-tmember 106) ;; team.tmember: user

(def ^:private three-level-schema
  "doc.view = owner + org->view; org.view = member + admin + team->tview;
  team.tview = tmember — a closure under a closure, exercising the nested
  DFS where revision 2's recursive merge was unimplementable."
  {:relations
   [{:relation-id rel-owner :resource-type :doc :relation-name :owner
     :subject-type :user}
    {:relation-id rel-org :resource-type :doc :relation-name :org
     :subject-type :org}
    {:relation-id rel-member :resource-type :org :relation-name :member
     :subject-type :user}
    {:relation-id rel-admin :resource-type :org :relation-name :admin
     :subject-type :user}
    {:relation-id rel-team :resource-type :org :relation-name :team
     :subject-type :team}
    {:relation-id rel-tmember :resource-type :team :relation-name :tmember
     :subject-type :user}]
   :permissions
   [{:resource-type :doc :permission-name :view
     :source-relation-name :self :target-type :relation
     :target-name :owner}
    {:resource-type :doc :permission-name :view
     :source-relation-name :org :target-type :permission
     :target-name :view}
    {:resource-type :org :permission-name :view
     :source-relation-name :self :target-type :relation
     :target-name :member}
    {:resource-type :org :permission-name :view
     :source-relation-name :self :target-type :relation
     :target-name :admin}
    {:resource-type :org :permission-name :view
     :source-relation-name :team :target-type :permission
     :target-name :tview}
    {:resource-type :team :permission-name :tview
     :source-relation-name :self :target-type :relation
     :target-name :tmember}]})

(def ^:private users (vec (range 1001 1016)))
(def ^:private teams (vec (range 2001 2007)))
(def ^:private orgs (vec (range 3001 3009)))
(def ^:private docs (vec (range 4001 4041)))

(defn- random-tuples
  [^java.util.Random rnd]
  (set
   (concat
    (for [u users d docs :when (< (.nextDouble rnd) 0.06)]
      [:user u rel-owner :doc d])
    (for [o orgs d docs :when (< (.nextDouble rnd) 0.18)]
      [:org o rel-org :doc d])
    (for [u users o orgs :when (< (.nextDouble rnd) 0.10)]
      [:user u rel-member :org o])
    (for [u users o orgs :when (< (.nextDouble rnd) 0.05)]
      [:user u rel-admin :org o])
    (for [t teams o orgs :when (< (.nextDouble rnd) 0.20)]
      [:team t rel-team :org o])
    (for [u users t teams :when (< (.nextDouble rnd) 0.12)]
      [:user u rel-tmember :team t]))))

;; ---------------------------------------------------------------------------
;; Naive derivation oracle: materialize, keep least, sort
;; ---------------------------------------------------------------------------

(defn- rules-of [plan node]
  (get-in plan [:indexes :reverse-rules node]))

(defn- tuple-resources
  [tuples subject-type s rel resource-type]
  (sort (for [[st s' r rt v] tuples
              :when (and (= st subject-type) (= s' s) (= r rel)
                         (= rt resource-type))]
          v)))

(defn- tuple-subjects
  [tuples resource-type v rel subject-type]
  (sort (for [[st s r rt v'] tuples
              :when (and (= rt resource-type) (= v' v) (= r rel)
                         (= st subject-type))]
          s)))

(defn- naive-fwd-derivs
  "All (value, coords) derivations of `node` for the subject."
  [plan tuples subject-type s node]
  (mapcat
   (fn [rule]
     (case (:rule rule)
       :relation
       (when (= subject-type (:subject-type rule))
         (for [v (tuple-resources tuples subject-type s
                                  (:relation-eid rule)
                                  (:resource-type rule))]
           [v [(:ordinal rule) v]]))
       :self-permission
       (for [[v c] (naive-fwd-derivs plan tuples subject-type s
                                     (:target-node rule))]
         [v (into [(:ordinal rule)] c)])
       :arrow-relation
       (when (= subject-type (:target-subject-type rule))
         (for [i (tuple-resources tuples subject-type s
                                  (:target-relation-eid rule)
                                  (:intermediate-type rule))
               v (tuple-resources tuples (:intermediate-type rule) i
                                  (:via-relation-eid rule)
                                  (:resource-type rule))]
           [v [(:ordinal rule) i v]]))
       :arrow-permission
       (for [[i c] (naive-fwd-derivs plan tuples subject-type s
                                     (:target-node rule))
             v (tuple-resources tuples (:intermediate-type rule) i
                                (:via-relation-eid rule)
                                (:resource-type rule))]
         [v (-> [(:ordinal rule)] (into c) (conj v))])))
   (rules-of plan node)))

(defn- naive-rev-derivs
  [plan tuples subject-type entity node]
  (mapcat
   (fn [rule]
     (case (:rule rule)
       :relation
       (when (= subject-type (:subject-type rule))
         (for [su (tuple-subjects tuples (:resource-type rule) entity
                                  (:relation-eid rule) subject-type)]
           [su [(:ordinal rule) su]]))
       :self-permission
       (for [[su c] (naive-rev-derivs plan tuples subject-type entity
                                      (:target-node rule))]
         [su (into [(:ordinal rule)] c)])
       :arrow-relation
       (when (= subject-type (:target-subject-type rule))
         (for [i (tuple-subjects tuples (:resource-type rule) entity
                                 (:via-relation-eid rule)
                                 (:intermediate-type rule))
               su (tuple-subjects tuples (:intermediate-type rule) i
                                  (:target-relation-eid rule)
                                  subject-type)]
           [su [(:ordinal rule) i su]]))
       :arrow-permission
       (for [i (tuple-subjects tuples (:resource-type rule) entity
                               (:via-relation-eid rule)
                               (:intermediate-type rule))
             [su c] (naive-rev-derivs plan tuples subject-type i
                                      (:target-node rule))]
         [su (-> [(:ordinal rule) i] (into c))])))
   (rules-of plan node)))

(defn- least-emissions
  "[{:value v :coords c} ...] in ascending coordinate order — each value
  once, at its least derivation."
  [derivs]
  (->> derivs
       (group-by first)
       (map (fn [[v cs]]
              {:value v
               :coords (first (sort lp/compare-coords (map second cs)))}))
       (sort-by :coords lp/compare-coords)
       vec))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- full-walk
  [page-fn base page-size]
  (loop [after nil out []]
    (let [{:keys [emissions has-more?]}
          (page-fn (cond-> (assoc base :page-size page-size)
                     after (assoc :after-coords after)))]
      (if (empty? emissions)
        out
        (let [out (into out emissions)]
          (if has-more?
            (recur (:coords (peek out)) out)
            out))))))

(defn- seeded-case
  [seed]
  (let [rnd (java.util.Random. seed)
        tuples (random-tuples rnd)
        adapter (synthetic-adapter (assoc three-level-schema
                                          :tuples tuples))
        plan (sealed-plan/seal-plan adapter [:doc :view])]
    {:tuples tuples :adapter adapter :plan plan}))

(deftest forward-least-path-matches-oracles-test
  (doseq [seed [11 23 37 41 59]]
    (let [{:keys [tuples adapter plan]} (seeded-case seed)]
      (is (= :least-path (:order-mode plan)))
      (doseq [u (take 6 users)]
        (let [expected (least-emissions
                        (naive-fwd-derivs plan tuples :user u
                                          [:doc :view]))
              base {:plan plan :adapter adapter
                    :subject-type :user :subject-eid u}
              got (full-walk lp/forward-page base 4)
              reduced (reducer/run-forward
                       {:adapter adapter :plan plan :subject-type :user
                        :subject-eid u
                        :target reducer/exhaustion-target})]
          (testing (str "seed " seed " user " u)
            ;; order + coordinates against the naive oracle
            (is (= expected got))
            ;; result set against the certified reducer
            (is (= (set (:results reduced))
                   (set (map :value got))))))))))

(deftest reverse-least-path-matches-oracles-test
  (doseq [seed [13 29 43]]
    (let [{:keys [tuples adapter plan]} (seeded-case seed)]
      (doseq [d (take 8 docs)]
        (let [expected (least-emissions
                        (naive-rev-derivs plan tuples :user d
                                          [:doc :view]))
              base {:plan plan :adapter adapter
                    :subject-type :user :resource-eid d}
              got (full-walk lp/reverse-page base 3)
              reduced (reducer/run-reverse
                       {:adapter adapter :plan plan :subject-type :user
                        :resource-eid d
                        :target reducer/exhaustion-target})]
          (testing (str "seed " seed " doc " d)
            (is (= expected got))
            (is (= (set (:results reduced))
                   (set (map :value got))))))))))

(deftest resume-equals-suffix-test
  (let [{:keys [tuples adapter plan]} (seeded-case 23)
        u (nth users 2)
        base {:plan plan :adapter adapter
              :subject-type :user :subject-eid u}
        whole (full-walk lp/forward-page base 100)]
    (doseq [k (range (count whole))]
      (let [{:keys [emissions]}
            (lp/forward-page (assoc base
                                    :page-size 100
                                    :after-coords
                                    (:coords (nth whole k))))]
        (is (= (subvec whole (inc k)) emissions)
            (str "resume after boundary " k))))))

(deftest descending-agrees-with-ascending-test
  (doseq [seed [11 37]]
    (let [{:keys [adapter plan]} (seeded-case seed)]
      (doseq [u (take 4 users)]
        (let [base {:plan plan :adapter adapter
                    :subject-type :user :subject-eid u}
              asc (full-walk lp/forward-page base 5)
              {:keys [emissions]}
              (lp/forward-page (assoc base :page-size 1000 :last? true))]
          (is (= (vec (reverse asc)) emissions)
              (str "seed " seed " user " u))))
      (doseq [d (take 4 docs)]
        (let [base {:plan plan :adapter adapter
                    :subject-type :user :resource-eid d}
              asc (full-walk lp/reverse-page base 5)
              {:keys [emissions]}
              (lp/reverse-page (assoc base :page-size 1000 :last? true))]
          (is (= (vec (reverse asc)) emissions)))))))

(deftest before-cursor-pages-backward-test
  (let [{:keys [adapter plan]} (seeded-case 41)
        u (nth users 1)
        base {:plan plan :adapter adapter
              :subject-type :user :subject-eid u}
        asc (full-walk lp/forward-page base 100)]
    (when (>= (count asc) 3)
      (let [boundary (nth asc 2)
            {:keys [emissions]}
            (lp/forward-page (assoc base :page-size 100
                                    :before-coords (:coords boundary)))]
        (is (= (vec (reverse (subvec asc 0 2))) emissions)
            "descending resume yields the strictly-smaller prefix reversed")))))

(deftest stream-opens-do-not-scale-with-boundary-test
  ;; The demo pathology regression: page k's stream opens must be work-
  ;; bounded, never ordinal-bounded. Compare a deep resumed page against
  ;; the first page on a fixture with enough results to paginate.
  (let [{:keys [adapter plan]} (seeded-case 59)
        u (some (fn [u]
                  (let [base {:plan plan :adapter adapter
                              :subject-type :user :subject-eid u}]
                    (when (> (count (full-walk lp/forward-page base 50)) 8)
                      u)))
                users)]
    (when u
      (let [base {:plan plan :adapter adapter
                  :subject-type :user :subject-eid u}
            whole (full-walk lp/forward-page base 50)
            first-page (lp/forward-page (assoc base :page-size 2))
            deep-page (lp/forward-page
                       (assoc base :page-size 2
                              :after-coords
                              (:coords (nth whole (- (count whole) 3)))))]
        (is (<= (:stream-opens (:counters deep-page))
                (+ 8 (* 4 (:stream-opens (:counters first-page)))))
            "deep-page stream opens are bounded by per-page work")))))

(deftest budgets-fail-typed-test
  (let [{:keys [adapter plan]} (seeded-case 11)
        base {:plan plan :adapter adapter
              :subject-type :user :subject-eid (first users)
              :page-size 50 :max-commands 3}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"limit exceeded"
         (lp/forward-page base)))))
