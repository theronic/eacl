(ns eacl.engine.stable-page-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.evidence :as evidence]
            [eacl.backend.v8 :as backend]
            [eacl.engine.stable-page :as page]
            [eacl.engine.stable-reducer-evidence-test :as fixture]))

(defn page! [env options]
  (with-redefs [qualification/qualify (:qualify env)]
    (page/edge-page (merge (:options env)
                           {:direction :forward :anchor-eid 1 :page-size 1 :checkpoint-key ::pages}
                           options))))

(deftest qualified-page-evidence-survives-lookahead-replay-and-backward-windows
  (doseq [chunk [1 3] size [1 2 3] retained? [false true]]
    (let [env (fixture/environment fixture/rows fixture/leaves {:physical-chunk-size chunk})
          full (fixture/run env)
          options {:page-size size :checkpoints (when retained? (page/make-checkpoint-store))}
          pages (loop [after nil pages []]
                  (let [result (page! env (assoc options :after after))
                        pages (conj pages result)]
                    (if (:has-next? result)
                      (recur {:ordinal (+ (:start-ordinal result) (count (:eids result)))
                              :eid (peek (:eids result))} pages)
                      pages)))]
      (is (= (:results full) (into [] (mapcat :eids) pages)))
      (is (= (:result-evidence full) (apply merge (map :result-evidence pages))))
      (doseq [result pages]
        (is (= (set (:eids result)) (set (keys (:result-evidence result)))))
        (when (pos? (:start-ordinal result))
          (let [after {:ordinal (:start-ordinal result)
                       :eid (nth (:results full) (dec (:start-ordinal result)))}]
            (is (= result (page! env (assoc options :after after)))))))
      (doseq [ordinal [2 3 4]]
        (let [result (page! env (assoc options :before {:ordinal ordinal :eid (nth (:results full) (dec ordinal))}))
              expected (subvec (:results full) (max 0 (- ordinal 1 size)) (dec ordinal))]
          (is (= expected (:eids result)))
          (is (= (select-keys (:result-evidence full) expected) (:result-evidence result)))))
      (let [result (page! env (assoc options :last-window? true))
            expected (subvec (:results full) (- (count (:results full)) size))]
        (is (= expected (:eids result)))
        (is (= (select-keys (:result-evidence full) expected) (:result-evidence result)))))))

(deftest checkpoint-retention-partitions-qualified-request-scope
  (let [store (page/make-checkpoint-store)
        env (fixture/environment fixture/rows fixture/leaves {})
        _ (page! env {:checkpoints store})
        request (qualification/request (assoc (get-in env [:options :qualification]) :time 100))
        changed (fixture/environment fixture/rows (assoc fixture/leaves 101 true 102 true)
                                     {:qualification request})
        options {:after {:ordinal 1 :eid 100}}
        uncached (page! changed options)
        cached (page! changed (assoc options :checkpoints store))]
    (is (= [300] (:eids cached)))
    (is (= uncached cached))
    (is (empty? (:result-evidence cached)))
    (reset! (:commands env) [])
    (let [original (page! env {:checkpoints store :after {:ordinal 1 :eid 100} :raw-candidates? true})]
      (is (= #{300} (set (keys (:result-evidence original)))))
      (is (empty? @(:commands env))))))

(defn identity-adapter []
  (backend/make-adapter
   {:id :qualified-pages :capabilities {}
    :operations (merge (zipmap backend/required-snapshot-operations
                               (repeat (fn [& _] (throw (ex-info "Unexpected page adapter read" {})))))
                       {:native-revision (constantly 1)
                        :object-id->internal identity
                        :internal-id->object identity})}))

(deftest standalone-qualified-tokens-bind-time-context-and-policy
  (let [env (fixture/environment fixture/rows fixture/leaves {})
        options (merge (:options env)
                       {:adapter (identity-adapter) :direction :forward :anchor [:user 1]
                        :page-size 1 :security-key "qualified-page-key-0123456789abcdef"})
        first-page (with-redefs [qualification/qualify (:qualify env)] (page/page options))
        token (get-in first-page [:page-info :end-cursor])
        options (assoc options :after token)]
    (is (= [100] (:data first-page)))
    (is (= #{100} (set (keys (:result-evidence first-page)))))
    (doseq [changed [(dissoc options :qualification)
                     (assoc options :result-policy :definite)
                     (update options :qualification #(qualification/request (assoc % :time 100)))
                     (update options :qualification #(qualification/request (assoc % :context {"unused" true})))]]
      (reset! (:commands env) [])
      (is (= :eacl.page/invalid-cursor (:eacl/error (fixture/error-data #(page/page changed)))))
      (is (empty? @(:commands env))))
    (let [next-page (with-redefs [qualification/qualify (:qualify env)] (page/page options))]
      (is (= [300] (:data next-page)))
      (is (= #{300} (set (keys (:result-evidence next-page))))))))

(deftest pending-qualified-lookahead-needs-no-repeat-probe
  (let [env (fixture/environment fixture/rows fixture/leaves {})
        store (page/make-checkpoint-store)
        _ (page! env {:checkpoints store})
        _ (reset! (:commands env) [])
        result (page! env {:checkpoints store :after {:ordinal 1 :eid 100} :raw-candidates? true})]
    (is (= [300] (:eids result)))
    (is (= #{300} (set (keys (:result-evidence result)))))
    (is (empty? @(:commands env)))))

(deftest definite-lookahead-has-the-same-sparse-shape-as-replay
  (let [env (fixture/environment (mapv #(assoc % 3 nil) fixture/rows) {} {})
        store (page/make-checkpoint-store)
        _ (page! env {:checkpoints store})
        options {:after {:ordinal 1 :eid 100}}
        cached (page! env (assoc options :checkpoints store))
        replayed (page! env options)]
    (is (= cached replayed))
    (is (empty? (:result-evidence cached)))))

(def corruptions
  [#(dissoc % :qualification-certificate)
   #(assoc % :qualification-certificate {:start-ms 99 :valid-until-ms 99 :complete? true})
   #(assoc % :qualification-certificate {:start-ms 98 :valid-until-ms nil :complete? false})
   #(dissoc % :pending-evidence)
   #(assoc % :pending-evidence {})
   #(assoc % :pending-evidence {300 false})
   #(assoc % :pending-evidence {300 (evidence/fault :test/failure :corrupt)})
   #(update % :state dissoc :qualified)])

(deftest incomplete-or-faulty-qualified-checkpoints-fall-back-to-replay
  (doseq [corrupt corruptions]
    (let [env (fixture/environment fixture/rows fixture/leaves {})
          store (page/make-checkpoint-store)
          put page/checkpoint-put!
          _ (with-redefs [page/checkpoint-put! (fn [store key checkpoint] (put store key (corrupt checkpoint)))]
              (page! env {:checkpoints store}))
          _ (reset! (:commands env) [])
          options {:after {:ordinal 1 :eid 100}}
          cached (page! env (assoc options :checkpoints store))]
      (is (seq @(:commands env)))
      (is (= cached (page! env options)))
      (is (= #{300} (set (keys (:result-evidence cached))))))))
