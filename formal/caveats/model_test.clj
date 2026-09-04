(ns eacl.formal.caveats.model-test
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [eacl.formal.caveats.model :as m]))

(deftest independent-corpus
  (doseq [{:keys [id parameters plan context bound expected reject]}
          (:cases (edn/read-string (slurp "exploration/caveats/corpus.edn")))]
    (when plan
      (testing (name id)
        (if reject
          (is (= :invalid (m/plan-type parameters plan)))
          (is (= expected (m/evaluate parameters plan context (or bound {})))))))))

(def truth-table
  {:and {[:t :t] :t [:t :f] :f [:t :u] :u [:t :e] :e
         [:f :t] :f [:f :f] :f [:f :u] :f [:f :e] :f
         [:u :t] :u [:u :f] :f [:u :u] :u [:u :e] :e
         [:e :t] :e [:e :f] :f [:e :u] :e [:e :e] :e}
   :or {[:t :t] :t [:t :f] :t [:t :u] :t [:t :e] :t
        [:f :t] :t [:f :f] :f [:f :u] :u [:f :e] :e
        [:u :t] :t [:u :f] :u [:u :u] :u [:u :e] :e
        [:e :t] :t [:e :f] :e [:e :u] :e [:e :e] :e}})

(def finite-outcomes
  {:t (m/known :bool true) :f (m/known :bool false)
   :u (m/missing #{"a"} [:param "a"]) :e (m/fault :missing-map-key)})

(defn classification [r]
  (cond (:fault r) :e (:missing r) :u (:value r) :t :else :f))

(deftest exhaustive-four-valued-logic
  (doseq [[op table] truth-table [[a b] expected] table]
    (is (= expected (classification (m/logical op (finite-outcomes a) (finite-outcomes b)))))
    (is (= expected (classification (m/logical op (finite-outcomes b) (finite-outcomes a))))))
  (doseq [op [:and :or]
          [a af] [[#{} "x"] [#{"a"} "a"] [#{"a" "b"} "b"]]
          [b bf] [[#{} "y"] [#{"c"} "c"] [#{"a" "c"} "a"]]]
    (is (= (set/union a b)
           (:missing (m/logical op (m/missing a [:param af]) (m/missing b [:param bf])))))))

(deftest exhaustive-bound-context
  (doseq [request [nil false true] bound [nil false true]
          :let [request-map (if (nil? request) {} {"a" request})
                bound-map (if (nil? bound) {} {"a" bound})
                expected (if (some? bound) bound request)
                actual (m/evaluate {"a" :bool} [:param "a"] request-map bound-map)]]
    (is (= (if (nil? expected) {:outcome :conditional :missing-fields #{"a"} :residual [:param "a"]}
               {:outcome (if expected :true :false)}) actual)))
  (is (= {:outcome :error :reason :context-type}
         (m/evaluate {"a" :bool} [:literal :bool true] {"a" "bad"} {"a" true}))))

(deftest static-types-and-bounds
  (doseq [op [:eq :ne :lt :le :gt :ge]
          a [:bool :int :string :timestamp] b [:bool :int :string :timestamp]]
    (is (= (if (and (= a b) (or (#{:eq :ne} op) (#{:int :timestamp} a))) :bool :invalid)
           (m/plan-type {"a" a "b" b} [op [:param "a"] [:param "b"]]))))
  (doseq [v [-9007199254740991 -1 0 1 9007199254740991]] (is (m/value-valid? :int v)))
  (doseq [v [-9007199254740992 9007199254740992 1.0 nil "1"]] (is (not (m/value-valid? :int v))))
  (doseq [v [-62135596800000 0 253402300799999]] (is (m/value-valid? :timestamp [:timestamp v])))
  (doseq [v [-62135596800001 253402300800000 1.0]] (is (not (m/value-valid? :timestamp [:timestamp v]))))
  (is (m/value-valid? :string (apply str (repeat 4096 "a"))))
  (is (not (m/value-valid? :string (apply str (repeat 4097 "a")))))
  (is (not (m/value-valid? :string (str (char 55296)))))
  (is (m/value-valid? [:list :bool] (vec (repeat 128 true))))
  (is (not (m/value-valid? [:list :bool] (vec (repeat 129 true)))))
  (is (not (m/parameter-type? [:list [:list :bool]])))
  (is (= {:outcome :error :reason :resource-limit}
         (m/evaluate {"a" :string "b" :string} [:contains [:param "a"] [:param "b"]]
                     {"a" (apply str (repeat 4096 "a")) "b" (apply str (repeat 2048 "a"))} {}))))

(deftest qualifier-boundary-cases
  (doseq [[input expected]
          [[{} nil]
           [{:caveat 1} {:caveat 1}]
           [{:caveat 1 :caveat-context {}} {:caveat 1}]
           [{:valid-until-ms 0} {:valid-until-ms 0}]
           [{:caveat-context {}} {:fault :context-without-caveat}]
           [{:valid-until-ms 1.5} {:fault :qualifier-time}]
           [{:valid-until-ms 253402300800000} {:fault :qualifier-time}]
           [{:caveat ["lookup" 1]} {:fault :qualifier-ref}]
           [{:caveats [1 2]} {:fault :qualifier-unknown-field}]]]
    (is (= expected (m/normalized-qualifier input)))))

(def identities [[:alice :user :viewer :doc :one] [:bob :user :viewer :doc :one]])
(def actions
  (concat
   (for [q [1 2] value [{:caveat 1} {:valid-until-ms 0}]] [:prepare nil q value nil])
   (for [i identities q [nil 1 2] op [:publish :replace]] [op i q nil #{:application-fact}])
   (for [i identities] [:delete i nil nil nil])
   (for [q [1 2]] [:cleanup nil q nil nil])))

(defn lifecycle-check [before action]
  (let [{:keys [accepted state]} (m/transition before action)
        op (first action)]
    (and (m/healthy? state)
         (if accepted
           (and (> (:generation state) (:generation before))
                (if (#{:prepare :cleanup} op)
                  (= (select-keys before [:forward :reverse :facts])
                     (select-keys state [:forward :reverse :facts])) true)
                (every? (fn [q] (= (get-in state [:qualifiers q]) (get-in before [:qualifiers q])))
                        (set/intersection (set (keys (:qualifiers before))) (set (keys (:qualifiers state))))))
           (= before state)))))

(deftest exhaustive-small-lifecycle
  (loop [frontier #{m/empty-state} depth 0]
    (when (< depth 4)
      (doseq [s frontier a actions] (is (lifecycle-check s a)))
      (recur (into #{} (for [s frontier a actions] (:state (m/transition s a)))) (inc depth)))))

(deftest deterministic-generated-lifecycle
  (let [random (java.util.Random. 774031) choices (vec actions)]
    (loop [s m/empty-state remaining 2000]
      (when (pos? remaining)
        (let [a (nth choices (.nextInt random (count choices)))]
          (is (lifecycle-check s a))
          (recur (:state (m/transition s a)) (dec remaining)))))))

(deftest named-schema-lifecycle
  (let [selected {:generation 0 :definitions {} :allowances {}}
        definition {:name "region" :parameters [["a" :bool]]
                    :plan [:param "a"] :profile-version "eacl-cel/1"}
        replacement (m/schema-result selected 0 [definition] {:viewer #{nil "region"}} #{})
        installed (:selected replacement)]
    (is (:accepted replacement))
    (is (= 1 (:generation installed)))
    (is (= {} (:definitions selected)) "historical value remains unchanged")
    (is (false? (:accepted (m/schema-result installed 0 [] {} #{}))) "CAS detects concurrent replacement")
    (is (false? (:accepted (m/schema-result installed 1 [] {} #{"region"}))) "retained qualifier prevents removal")
    (is (false? (:accepted (m/schema-result selected 0 [definition definition] {} #{}))) "duplicate names rejected")
    (is (false? (:accepted (m/schema-result selected 0 [(update definition :parameters conj ["a" :int])] {} #{}))))
    (is (false? (:accepted (m/schema-result selected 0 [(assoc definition :plan [:literal :int 1])] {} #{}))))
    (is (false? (:accepted (m/schema-result selected 0 [definition] {:viewer #{"other"}} #{}))))
    (is (= 2 (count (:definitions (:selected (m/schema-result selected 0 [definition (assoc definition :name "second")] {} #{}))))))
    (doseq [relation [:viewer :editor] caveat [nil "region" "other"]]
      (is (= (and (= relation :viewer) (or (nil? caveat) (= caveat "region")))
             (m/allowed? installed relation caveat))))
    (doseq [allowed [#{nil} #{"region"} #{nil "region"}]
            caveat [nil "region" "other"]]
      (is (= (contains? allowed caveat)
             (m/allowed? {:allowances {:viewer allowed}} :viewer caveat))))))

(deftest container-substitution-preserves-residual-type
  (doseq [[type value] [[[:list :int] [1 2]] [[:map :string :bool] {"enabled" true}]]]
    (let [parameters {"x" :string "items" type}
          expression (if (= :list (first type))
                       [:in [:literal :int 1] [:param "items"]]
                       [:index [:param "items"] [:param "x"]])
          result (m/partial-value parameters expression {"items" value})]
      (is (= :bool (m/plan-type parameters (:residual result)))))))

(deftest mutation-controls
  (let [i (first identities) other (second identities)
        prepared (:state (m/transition m/empty-state [:prepare nil 1 {:caveat 1} nil]))
        published (:state (m/transition prepared [:publish i 1 nil #{:fact}]))]
    (is (not (m/healthy? (assoc-in prepared [:forward i] 1))) "one-half publication")
    (is (not (m/healthy? (-> published (assoc-in [:forward other] 1) (assoc-in [:reverse other] 1)))) "shared qualifier")
    (is (not (m/healthy? (update published :qualifiers dissoc 1))) "missing target cannot alias nil")
    (is (not= (get-in published [:qualifiers 1])
              (get-in (assoc-in published [:qualifiers 1 :value :caveat] 2) [:qualifiers 1])) "immutability requires history evidence")
    (is (false? (:accepted (m/transition published [:publish i nil nil #{}]))) "qualifier does not change identity")
    (is (not= (:generation prepared) (:generation published)) "publication advances proof generation")
    (is (not= {:outcome :false} (m/evaluate {"a" :bool} [:param "a"] {"a" false} {"a" true}))) "bound precedence"
    (is (not= :t (classification (m/fault :returned-error-object)))) "returned error is never truthy authorization"
    (is (not= :u (classification (m/logical :or (finite-outcomes :u) (finite-outcomes :t))))) "short circuit removes residual"
    (is (not= (:forward prepared) (:forward published)) "a prepared qualifier is not published")))
