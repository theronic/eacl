(ns eacl.engine.stable-reducer-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-test :as completions]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as fixture]
            [eacl.engine.stable-reducer :as reducer]
            [eacl.engine.stable-route :as route]
            [eacl.backend.v8 :as backend]
            [eacl.relationships.edge :as edge]))

(def root [:folder :view])
(def direct {:rule :relation :ordinal 0 :node root :subject-type :user
             :resource-type :folder :relation-eid 10})
(def arrow {:rule :arrow-permission :ordinal 1 :node root :target-node root
            :resource-type :folder :intermediate-type :folder :via-relation-eid 20})
(def plan {:root root :indexes {:forward-seeds {:user [direct]}
                                :forward-consumers {root [arrow]}
                                :reverse-rules {root [direct arrow]}}})
(def rows [[1 10 100 101] [1 10 200 102]
           [100 20 300 nil] [200 20 300 nil] [300 20 400 103] [400 20 100 nil]])
(def leaves {101 completions/x 102 completions/y
             103 (evidence/combine :exclusion true completions/x)})

(defn environment [rows leaves extra]
  (let [commands (atom []) probes (atom []) reads (atom [])
        fetch (fn [{:keys [operation subject-eid resource-eid relation-eid bound-eid limit include-qualifier?]
                    :as command}]
                (swap! commands conj command)
                (->> rows
                     (keep (fn [[s r o q]]
                             (when (and (= r relation-eid)
                                        (= (if (= operation :subject->resources) s o)
                                           (if (= operation :subject->resources) subject-eid resource-eid)))
                               (let [eid (if (= operation :subject->resources) o s)]
                                 (when (or (nil? bound-eid) (> eid bound-eid))
                                   (if include-qualifier? (edge/pack eid q) eid))))))
                     (sort-by edge/endpoint) (take limit) vec))
        options (merge {:plan plan :fetch-fn fetch :subject-type :user :subject-eid 1
                        :resource-eid 400 :target reducer/exhaustion-target
                        :qualification (fixture/request) :physical-chunk-size 1
                        :result-policy :detailed} extra)
        complete (:candidate-evidence-fn (route/discovery-options options (:direction extra :forward)))
        options (assoc options :candidate-evidence-fn
                       (fn [eid item]
                         (swap! probes conj eid)
                         (complete eid item)))]
    {:options options :commands commands :probes probes :reads reads
     :qualify (fn [_ relation row]
                (swap! reads conj [relation row])
                (if-let [qid (edge/qualifier-id row)] (get leaves qid) (some? row)))}))

(defn run [env]
  (with-redefs [qualification/qualify (:qualify env)]
    ((if (= :reverse (:direction (:options env))) reducer/run-reverse reducer/run-forward)
     (:options env))))

(defn value-at [result eid]
  (get-in result [:result-evidence eid] true))

(deftest conditional-prefixes-revisit-cycles-without-duplicate-discoveries
  (doseq [chunk [1 2 64] cap [0 1 16]]
    (let [env (environment rows leaves {:physical-chunk-size chunk :sidecar-cap cap})
          full (run env)]
      (is (= [100 300 200 400] (:results full)))
      (doseq [eid [100 300]]
        (is (= #{1 2 3} (completions/completions (value-at full eid) [completions/x completions/y]))))
      (is (= #{2 3} (completions/completions (value-at full 200) [completions/x completions/y])))
      (is (= #{2} (completions/completions (value-at full 400) [completions/x completions/y])))
      (is (= [100 300 200 400] @(:probes env)))
      (is (every? :include-qualifier? @(:commands env)))
      (is (< (:transitions full) 100))
      (doseq [target [1 2 3]]
        (let [prefix (run (assoc-in env [:options :target] target))
              suffix (with-redefs [qualification/qualify (:qualify env)]
                       (reducer/resume (:options env) (reducer/history-free prefix)))]
          (is (= (:results full) (into (:results prefix) (:results suffix))))
          (is (= (:result-evidence full) (merge (:result-evidence prefix) (:result-evidence suffix)))))))))

(deftest reverse-prefixes-conjoin-and-complete-the-whole-root
  (doseq [chunk [1 2 64]]
    (let [env (environment rows leaves {:direction :reverse :physical-chunk-size chunk})
          result (run env)]
      (is (= [1] (:results result)))
      (is (= #{2} (completions/completions (value-at result 1) [completions/x completions/y])))
      (is (= [1] @(:probes env))))))

(deftest ordinary-qualified-traversal-retains-the-existing-fast-path
  (let [ordinary (mapv #(assoc % 3 nil) rows)
        env (environment ordinary {} {})
        qualified (run env)
        plain (reducer/run-forward (dissoc (:options env) :qualification :candidate-evidence-fn))]
    (is (= (:results plain) (:results qualified)))
    (is (= (:admissions plain) (:admissions qualified)))
    (is (= (:transitions plain) (:transitions qualified)))
    (is (empty? (:result-evidence qualified)))
    (is (empty? @(:probes env)))
    (is (empty? (get-in (reducer/history-free qualified) [:qualified :weights])))))

(deftest detailed-counts-and-windows-share-definite-filtering
  (doseq [policy [:definite :detailed] sink [:collect :count :window]]
    (let [env (environment rows leaves {:result-policy policy :result-sink sink :result-window-size 2})
          result (run env)]
      (is (= (if (= :detailed policy) 4 0) (:discovered result)))
      (is (zero? (:definite-count result)))
      (is (= (:discovered result) (:conditional-count result)))
      (is (= (if (and (= :detailed policy) (not= :count sink))
               (if (= :window sink) [200 400] [100 300 200 400]) [])
             (:results result)))
      (is (= (set (:results result)) (set (keys (:result-evidence result))))))
    (let [env (environment rows (assoc leaves 101 true) {:result-policy policy :result-sink sink :result-window-size 2})
          result (run env)]
      (is (= 2 (:definite-count result)))
      (is (= (if (= :detailed policy) 2 0) (:conditional-count result))))))

(defn error-data [f]
  (try (f) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error (ex-data error))))

(def bounded-options
  [[:max-admissions 2]
   [:max-transitions 2]
   [:max-commands 1]
   [:max-values 1]
   [:max-stack 1]
   [:max-evidence-size 1]])

(deftest weighted-admission-and-resume-keep-operational-bounds
  (doseq [[limit maximum] bounded-options]
    (let [env (environment rows leaves {limit maximum})]
      (is (= limit (:limit (error-data #(run env)))))))
  (let [env (environment rows leaves {})
        prefix (run (assoc-in env [:options :target] 1))
        checkpoint (reducer/history-free prefix)]
    (is (not-any? #(contains? (:qualified checkpoint) %)
                  [:request :candidate-evidence-fn :result-evidence]))
    (doseq [options [(dissoc (:options env) :qualification)
                     (assoc (:options env) :result-policy :definite)
                     (update (:options env) :qualification #(qualification/request (assoc % :time 100)))]]
      (reset! (:commands env) [])
      (is (= :eacl.reducer/checkpoint-scope-mismatch
             (:type (error-data #(reducer/resume options checkpoint)))))
      (is (empty? @(:commands env)))))
  (let [env (environment rows (assoc leaves 101 (evidence/fault :test/fault :invalid)) {})]
    (is (= :eacl.authorization/evaluation-failure (:type (error-data #(run env))))))
  (let [env (environment rows leaves {:result-policy nil})]
    (is (= :eacl.reducer/invalid-result-policy (:type (error-data #(run env)))))))

(deftest excluded-prefixes-consume-bounded-physical-work
  (let [env (environment (mapv (fn [eid] [1 10 eid 101]) (range 100 200)) {101 false}
                         {:max-values 7 :physical-chunk-size 1})]
    (is (= :max-values (:limit (error-data #(run env)))))
    (is (= 8 (count @(:commands env))))
    (is (= 7 (count @(:reads env))))
    (is (empty? @(:probes env)))))

(deftest direct-discovery-witnesses-avoid-repeating-the-known-tuple
  (doseq [direction [:forward :reverse]]
    (let [env (environment [[1 10 100 101]] {101 completions/x}
                           {:direction direction :resource-eid 100})
          result (run env)]
      (is (= (if (= :forward direction) [100] [1]) (:results result)))
      (is (= 1 (count (filter #(= 101 (edge/qualifier-id (second %))) @(:reads env))))))))

(deftest capped-count-categories-exclude-the-lookahead
  (doseq [limit [0 1 2 3 4 5] policy [:definite :detailed] ordinary? [false true]]
    (let [env (environment rows (cond-> leaves ordinary? (assoc 101 true))
                           {:result-policy policy :count-limit limit :subject-id 1})
          order (run env)
          prefix (take limit (:results order))
          definite (count (filter #(evidence/has? (value-at order %)) prefix))
          adapter (backend/make-adapter
                   {:id :discovery-count :capabilities {}
                    :operations (assoc (zipmap backend/required-snapshot-operations
                                               (repeat (fn [& _] (throw (ex-info "Unexpected count adapter read" {})))))
                                       :object-id->internal identity)})
          result (with-redefs [qualification/qualify (:qualify env)]
                   (route/count-resources (assoc (:options env) :adapter adapter)))]
      (is (= (count prefix) (:count result)))
      (is (= definite (:definite-count result)))
      (is (= (- (count prefix) definite) (:conditional-count result)))
      (is (= (> (:discovered order) limit) (:truncated? result))))))

(deftest temporal-revisions-replay-a-buffered-prefix
  (let [rows [[1 10 100 101] [100 20 200 nil] [100 20 300 nil] [200 20 100 102]]
        leaves {101 (evidence/with-certificate completions/x 200 true)
                102 (evidence/with-certificate true 100 true)}
        narrow (run (environment rows leaves {:physical-chunk-size 1}))
        wide (run (environment rows leaves {:physical-chunk-size 64}))]
    (is (= [100 200 300] (:results narrow) (:results wide)))
    (is (= (:result-evidence narrow) (:result-evidence wide)))
    (is (= (get-in narrow [:qualified :weights]) (get-in wide [:qualified :weights])))
    (doseq [target [1 2]]
      (let [env (environment rows leaves {:physical-chunk-size 64})
            prefix (run (assoc-in env [:options :target] target))
            suffix (with-redefs [qualification/qualify (:qualify env)]
                     (reducer/resume (:options env) (reducer/history-free prefix)))]
        (is (= (:result-evidence narrow) (merge (:result-evidence prefix) (:result-evidence suffix))))
        (is (= (get-in narrow [:qualified :weights]) (get-in suffix [:qualified :weights])))))))
