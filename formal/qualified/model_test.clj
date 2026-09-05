(ns eacl.formal.qualified.model-test
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [eacl.formal.caveats.model :as lifecycle]
            [eacl.formal.qualified.model :as m]))

(def universe (m/worlds 2))
(def operators [:union :intersection :exclusion :arrow])
(def subsets (mapv (fn [mask] (set (filter #(bit-test mask %) universe))) (range 16)))
(def outcomes (into (mapv m/value subsets) [(m/fault :invalid) (m/fault :evaluator)]))

(defn boolean-compose [op a b]
  (case op :union (or a b) :intersection (and a b)
        :arrow (and a b) :exclusion (and a (not b))))

(deftest exhaustive-residual-and-authoritative-fault-algebra
  (doseq [op operators a outcomes b outcomes]
    (let [actual (m/compose op a b)
          expected (if (or (:fault a) (:fault b))
                     {:fault (set/union (:fault a #{}) (:fault b #{}))}
                     (m/value (set (filter #(boolean-compose op (contains? (:worlds a) %)
                                                             (contains? (:worlds b) %)) universe))))]
      (is (= expected actual))))
  (doseq [x outcomes]
    (is (= (= :has (m/kind universe x)) (= x (m/value universe)))))
  (is (= #{0} (m/missing-fields universe (m/atom-value universe 0) 2)))
  (is (= #{1} (m/missing-fields universe (m/atom-value universe 1) 2)))
  (let [residual (m/compose :exclusion (m/atom-value universe 0) (m/atom-value universe 1))]
    (is (= #{0 1} (m/missing-fields universe residual 2)))))

(def qualifier-cases
  (vec (for [v [(m/value universe) (m/value #{}) (m/atom-value universe 0) (m/fault :evaluator)]
             expiry [nil 1 3]]
         {:valid? true :caveat v :expiry expiry})))

(defn certificate-contract? [op a b start later incomplete]
  (let [at (fn [q time] (m/qualify universe {1 q} 1 time))
        x (cond-> (at a start) (contains? incomplete :left) (assoc :complete? false))
        y (cond-> (at b start) (contains? incomplete :right) (assoc :complete? false))
        result (m/combine universe op x y)
        future (m/compose op (:value (at a later)) (:value (at b later)))]
    (or (not (and (:complete? result) (<= start later) (m/before? later (:end result))))
        (= (:value result) future))))

(deftest temporal-witness-certificates-cover-all-outcome-roles
  (doseq [op operators a qualifier-cases b qualifier-cases
          start [0 1 3] later [0 1 2 3 4]
          incomplete [#{} #{:left} #{:right} #{:left :right}]]
    (is (certificate-contract? op a b start later incomplete)))
  (doseq [q qualifier-cases start [0 1 3] later [0 1 2 3 4]
          :when (<= start later)]
    (is (m/no-new-faults? (:value (m/qualify universe {1 q} 1 start))
                          (:value (m/qualify universe {1 q} 1 later)))))
  (let [permanent (m/evidence (m/value universe) nil)
        temporary (m/evidence (m/atom-value universe 0) 1)
        union (m/combine universe :union permanent temporary)]
    (is (nil? (:end union)))
    (is (m/publishable? union 1000)))
  (is (not (m/publishable? (assoc (m/evidence (m/value universe) nil) :complete? false) 0))))

(deftest publication-and-one-captured-time
  (let [identity [1 2 3 4 5]
        prepared (:state (m/stored-transition lifecycle/empty-state
                                              [:prepare nil 1 {:valid-until-ms 100} nil]))
        semantics {1 {:valid? true :expiry 100 :caveat (m/value universe)}}
        published (:state (m/stored-transition prepared [:publish identity 1 nil #{:app}]))
        state (m/lifecycle-state published semantics)
        grant [:edge identity]
        ban [:exclusion [:constant (m/value universe)] grant]
        check-at (fn [s tree time budget]
                   (m/kind universe (:value (m/evaluate universe s tree time budget))))]
    (doseq [time [99 100 101]]
      (let [before (m/lifecycle-state prepared semantics)]
        (is (= (m/value #{}) (:value (m/edge universe before identity time))))))
    (is (= (:forward published) (:reverse published)))
    (is (= #{:app} (:facts published)))
    (is (< (:generation prepared) (:generation published)))
    (doseq [[time expected] [[99 :has] [100 :no] [101 :no]]]
      (is (= expected (check-at state grant time 10))))
    (is (= :no (check-at state ban 99 10)))
    (is (= :has (check-at state ban 100 10)))
    (is (= :failure (check-at (update state :qualifiers dissoc 1) ban 99 10)))
    (is (= :failure (check-at (assoc state :reverse {}) grant 99 10)))
    (is (= :failure (check-at state ban 99 1)))
    (let [q {1 {:valid? true :expiry 100 :caveat (m/fault :evaluator)}}]
      (is (= :no (m/kind universe (:value (m/qualify universe q 1 100))))))
    (is (= [90 110 110 120] (rest (reductions m/capture-time 0 [90 110 95 120]))))))

(defn world-reachable [base edges world]
  (loop [reached (set (for [[node e] base :when (contains? (get-in e [:value :worlds]) world)] node))]
    (let [next (into reached (for [[head pairs] edges [edge target] pairs
                                   :when (and (contains? reached target)
                                              (contains? (get-in edge [:value :worlds]) world))] head))]
      (if (= next reached) reached (recur next)))))

(deftest finite-positive-recursion-agrees-with-independent-reachability
  (doseq [mask (range 512) time [0 1 3]]
    (let [base {0 (m/qualify universe {1 {:valid? true :expiry 3 :caveat (m/atom-value universe 0)}} 1 time)
                1 (m/evidence (m/value #{}) nil) 2 (m/evidence (m/value #{}) nil)}
          edges (into {} (for [head (range 3)]
                           [head (vec (for [target (range 3) :when (bit-test mask (+ (* head 3) target))]
                                        [(m/qualify universe {1 {:valid? true :expiry (when (= target 2) 1)
                                                                 :caveat (m/atom-value universe 1)}} 1 time)
                                         target]))]))
          result (m/fixed-point universe base edges 32)]
      (is (:complete? result))
      (doseq [node (range 3)]
        (is (= (set (filter #(contains? (world-reachable base edges %) node) universe))
               (get-in result [:values node :value :worlds]))))))
  (let [base {0 (m/evidence (m/value universe) nil)}]
    (is (= {:fault :work-limit :complete? false} (m/fixed-point universe base {} 0)))))

(def scope {:source 1 :schema 2 :relations [3 4] :qualifiers [5 6]
            :context [7 8] :evaluator [9 10] :policy :detailed :abi [9 3] :query [11]})
(def selected {:scope scope :basis 10 :ancestors #{9} :time 90
               :wall-time 90 :key-available? true})
(def entry {:authenticated? true :scope scope :basis 9 :start 90
            :evidence (m/evidence (m/value universe) 100) :kind :has})
(def cursor {:entry entry :mode :live :token-expiry 200 :retained-complete? true})

(deftest cache-and-cursor-scope-is-complete
  (is (m/accept-cache? universe entry selected))
  (doseq [field m/scope-fields]
    (is (not (m/accept-cache? universe entry (assoc-in selected [:scope field] [:changed]))))
    (is (= :scope-mismatch (m/cursor-decision universe cursor (assoc-in selected [:scope field] [:changed])))))
  (doseq [time [90 99 100 101 89]]
    (is (= (<= 90 time 99) (m/accept-cache? universe entry (assoc selected :time time))))
    (is (= (if (<= 90 time 99) :continue :restart-required)
           (m/cursor-decision universe cursor (assoc selected :time time)))))
  (doseq [x outcomes reported-kind [:has :no :conditional :failure]]
    (is (= (and (= reported-kind (m/kind universe x)) (not= :failure reported-kind))
           (m/accept-cache? universe (assoc entry :evidence (m/evidence x 100) :kind reported-kind) selected))))
  (doseq [time [90 91] basis [9 10]]
    (is (= (and (= time 90) (= basis 9))
           (m/accept-cache? universe (assoc-in entry [:evidence :complete?] false)
                            (assoc selected :time time :basis basis)))))
  (is (not (m/accept-cache? universe (assoc entry :authenticated? false) selected)))
  (is (not (m/accept-cache? universe entry (assoc selected :ancestors #{}))))
  (is (= :restart-required (m/cursor-decision universe (assoc cursor :retained-complete? false)
                                              (assoc selected :time 91))))
  (is (= :continue (m/cursor-decision universe (assoc cursor :mode :pinned)
                                      (assoc selected :basis 9 :time 90 :wall-time 110))))
  (is (= :restart-required (m/cursor-decision universe (assoc cursor :mode :pinned)
                                              (assoc selected :basis 9 :time 110 :wall-time 110))))
  (is (= :invalid-token (m/cursor-decision universe cursor (assoc selected :key-available? false))))
  (is (= :invalid-token (m/cursor-decision universe cursor (assoc selected :wall-time 200)))))

(deftest cursor-certification-includes-skipped-bans-and-lookahead
  (let [skipped-ban (m/evidence (m/value #{}) 100)
        emitted (m/evidence (m/value universe) nil)
        lookahead (m/evidence (m/atom-value universe 0) 120)
        certificate (m/cursor-certificate [skipped-ban emitted] [lookahead] true)]
    (is (= {:end 100 :complete? true} certificate))
    (is (not (:complete? (m/cursor-certificate [emitted] [] false))))
      (is (not (:complete? (m/cursor-certificate [emitted]
                                                 [(assoc lookahead :complete? false)] true))))))

(deftest recursive-certificate-follows-expiring-subtracting-base-evidence
  (doseq [mask (range 512)]
    (let [build (fn [time]
                  (let [truth (m/evidence (m/value universe) nil)
                        ban (m/qualify universe {1 {:valid? true :expiry 100 :caveat (m/value universe)}} 1 time)
                        base {0 (m/evidence (m/value #{}) nil)
                              1 (m/evidence (m/value #{}) nil)
                              2 (m/combine universe :exclusion truth ban)}
                        rules (into {} (for [head (range 3)]
                                         [head (vec (for [target (range 3)
                                                          :when (bit-test mask (+ (* head 3) target))]
                                                      [truth target]))]))]
                    (m/fixed-point universe base rules 32)))
          initial (build 90)]
      (doseq [time [99 100 101]]
        (let [later (build time)]
          (is (and (:complete? initial) (:complete? later)))
          (doseq [node (range 3)]
            (let [e (get-in initial [:values node])]
              (is (or (not (m/before? time (:end e)))
                      (= (:value e) (get-in later [:values node :value])))))))))))

(deftest decode-cache-lifecycle-and-writer-proof
  (let [prior {:source 1 :basis 10 :qid 100 :format 1 :version 11
               :relation 12 :writer-certified? true :content [1 2 3]}
        later (assoc prior :basis 20)
        opaque-prior (dissoc prior :content)
        opaque-later (dissoc later :content)]
    (is (m/accept-decode? prior later))
    (doseq [field [:source :qid :format]]
      (is (not (m/accept-decode? prior (update later field inc)))))
    (doseq [field [:version :relation]]
      (is (not (m/accept-decode? opaque-prior (update opaque-later field inc)))))
    (is (m/accept-decode? prior (assoc later :writer-certified? false)))
    (is (not (m/accept-decode? prior (assoc later :writer-certified? false :content [1 2 4]))))
    (is (not (m/accept-decode? (dissoc opaque-prior :version) (dissoc opaque-later :version))))))
