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
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.v8 :as engine]))

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

;; ---------------------------------------------------------------------------
;; Ordinal order vs (rank, ordinal) list order — the regression fixture
;; ---------------------------------------------------------------------------
;;
;; The plan's per-node rule lists are in (rank, ordinal) alternative
;; order (the reducer's scheduling order); the least-path contract is
;; lexicographic over SEALED ORDINALS. On this schema the two disagree
;; at doc.view (direct arms rank-sort around the arrows while their
;; canonical ordinals interleave), so a traversal that walks arms in
;; list order emits out of coordinate order and reports non-least
;; coordinates for entities derivable through both a low-ordinal arrow
;; and a high-ordinal direct arm.

(def ^:private divergent-schema
  {:relations
   [{:relation-id 401 :resource-type :doc :relation-name :owner
     :subject-type :user}
    {:relation-id 402 :resource-type :doc :relation-name :org
     :subject-type :org}
    {:relation-id 403 :resource-type :doc :relation-name :escrow
     :subject-type :org}
    {:relation-id 404 :resource-type :doc :relation-name :wallet
     :subject-type :wallet}
    {:relation-id 405 :resource-type :org :relation-name :member
     :subject-type :user}
    {:relation-id 406 :resource-type :wallet :relation-name :holder
     :subject-type :user}]
   :permissions
   [{:resource-type :doc :permission-name :view
     :source-relation-name :self :target-type :relation :target-name :owner}
    {:resource-type :doc :permission-name :view
     :source-relation-name :org :target-type :permission :target-name :oview}
    {:resource-type :doc :permission-name :view
     :source-relation-name :escrow :target-type :permission :target-name :oview}
    {:resource-type :doc :permission-name :view
     :source-relation-name :wallet :target-type :relation :target-name :holder}
    {:resource-type :org :permission-name :oview
     :source-relation-name :self :target-type :relation :target-name :member}]})

(deftest emission-order-follows-sealed-ordinals-test
  (let [tuples #{[:user 1 401 :doc 92]        ;; owner of d92
                 [:org 50 402 :doc 90] [:org 50 402 :doc 91]
                 [:user 1 405 :org 50]        ;; org member -> d90 d91
                 [:org 51 403 :doc 93]        ;; escrow -> d93
                 [:user 1 405 :org 51]
                 [:wallet 60 404 :doc 94] [:wallet 60 404 :doc 92]
                 [:user 1 406 :wallet 60]}    ;; wallet -> d94 and d92
        adapter (synthetic-adapter (assoc divergent-schema :tuples tuples))
        plan (sealed-plan/seal-plan adapter [:doc :view])
        rules (get-in plan [:indexes :reverse-rules [:doc :view]])
        base {:plan plan :adapter adapter :subject-type :user :subject-eid 1}
        expected (least-emissions
                  (naive-fwd-derivs plan tuples :user 1 [:doc :view]))
        got (full-walk lp/forward-page base 2)]
    (testing "the fixture really diverges: list order is not ordinal order"
      (is (not= (mapv :ordinal rules)
                (sort (mapv :ordinal rules)))
          "if this ever sorts equal, strengthen the schema — the
           regression needs a node whose (rank, ordinal) list order
           differs from ordinal order"))
    (testing "emissions equal the ordinal-lexicographic oracle"
      (is (= expected got)))
    (testing "the emitted sequence is ascending under compare-coords"
      (is (= (mapv :coords got)
             (vec (sort lp/compare-coords (mapv :coords got))))))
    (testing "descending walk agrees"
      (let [{:keys [emissions]} (lp/forward-page
                                 (assoc base :page-size 100 :last? true))]
        (is (= (vec (reverse got)) emissions))))
    (testing "resume from every boundary equals the suffix"
      (doseq [k (range (count got))]
        (is (= (subvec got (inc k))
               (:emissions (lp/forward-page
                            (assoc base :page-size 100
                                   :after-coords (:coords (nth got k)))))))))))

(deftest malformed-coordinates-fail-typed-test
  (let [{:keys [adapter plan]} (seeded-case 11)
        base {:plan plan :adapter adapter
              :subject-type :user :subject-eid (first users)}
        typed (fn [coords]
                (try (lp/forward-page (assoc base :page-size 5
                                             :after-coords coords))
                     :no-error
                     (catch Exception e
                       (:eacl/error (ex-data e)))))
        ordinals (mapv :ordinal
                       (get-in plan [:indexes :reverse-rules [:doc :view]]))]
    (doseq [coords (concat [[(first ordinals)]
                            [999999 1]
                            [(first ordinals) 1 2 3 4 5 6 7 8]]
                           (for [o (rest ordinals)] [o]))]
      (is (= :eacl.page/invalid-cursor (typed coords))
          (str "coords " coords " must fail typed, never as a raw
                index error")))))

(deftest witness-child-enumeration-is-shared-across-a-page-test
  ;; A page over an arrow-permission arm whose child has a large sparse
  ;; fan-in: every emission's smaller-witness check alternates against
  ;; the SAME child enumeration, so the page must pay for at most a
  ;; small constant number of full child walks (the main traversal's and
  ;; the shared witness prefix) — never one walk per emission.
  (let [n-groups 400
        groups (vec (range 1000 (+ 1000 n-groups)))
        schema {:relations
                [{:relation-id 501 :resource-type :doc :relation-name :org
                  :subject-type :org}
                 {:relation-id 502 :resource-type :org :relation-name :grp
                  :subject-type :group}
                 {:relation-id 503 :resource-type :group
                  :relation-name :gmember :subject-type :user}]
                :permissions
                [{:resource-type :doc :permission-name :view
                  :source-relation-name :org :target-type :permission
                  :target-name :oview}
                 {:resource-type :org :permission-name :oview
                  :source-relation-name :grp :target-type :relation
                  :target-name :gmember}]}
        tuples (set (concat
                     (for [g groups] [:user 1 503 :group g])
                     ;; both orgs' groups sit at the END of the holdings
                     [[:group (+ 1000 (- n-groups 2)) 502 :org 2001]
                      [:group (+ 1000 (- n-groups 1)) 502 :org 2002]]
                     (for [d (range 3001 3021) o [2001 2002]]
                       [:org o 501 :doc d])))
        adapter (synthetic-adapter (assoc schema :tuples tuples))
        plan (sealed-plan/seal-plan adapter [:doc :view])
        run (lp/forward-page {:plan plan :adapter adapter
                              :subject-type :user :subject-eid 1
                              :page-size 10})]
    (is (= 10 (count (:emissions run))))
    (is (<= (:commands (:counters run)) (* 3 n-groups))
        (str "a page must cost at most a few full child walks over the "
             n-groups "-group fan-in; unshared witness children cost "
             "page-size walks (" (:commands (:counters run))
             " commands seen)"))))

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
            "deep-page stream opens are bounded by per-page work")
        (is (= 3 (:emissions (:counters first-page)))
            "counted emissions are the derived results incl. the lookahead")))))

(deftest acyclic-lookup-never-builds-continuation-context-test
  ;; The keyset route consults no continuation state; the context (query
  ;; canonicalization, proof-frame resolution, backend reads on the
  ;; cached clients) must never be built for it. The thunk records
  ;; instead of throwing so a failure reads as a count, not a stack.
  (let [{:keys [adapter]} (seeded-case 11)
        forced (atom 0)
        page (engine/lookup-resources
              adapter
              {:subject {:type :user :id (first users)}
               :permission :view :resource/type :doc :first 3}
              {:continuation-cache-fn #(do (swap! forced inc) nil)})]
    (is (map? (:page-info page)))
    (is (zero? @forced)
        "least-path pages must not force the continuation context")))

(deftest budgets-fail-typed-test
  (let [{:keys [adapter plan]} (seeded-case 11)
        base {:plan plan :adapter adapter
              :subject-type :user :subject-eid (first users)
              :page-size 50 :max-commands 3}]
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo #"limit exceeded"
         (lp/forward-page base)))))
