(ns eacl.caveats.integrity-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.relationships.qualifier-integrity :as integrity]))

(def owner [:user 1 2 :doc 3])
(def another [:user 1 2 :doc 4])
(def entity {:db/id 5 :eacl.relationship-qualifier/format-version 1
             :eacl.relationship-qualifier/valid-until-ms 1000})
(def frame {:source {:backend :test :id "one"} :cache-scope :exact-only
            :references {5 {:forward [owner] :reverse [owner]}}
            :qualifiers {5 {:entity entity :version 7 :facts []}}
            :relations {2 {:generation 7 :definition {:eacl.relation/resource-type :doc
                                                      :eacl.relation/subject-type :user
                                                      :eacl.relation/relation-name :viewer}}}
            :malformed-halves {:count 0 :sample []}})

(deftest corruption-classification
  (is (= :healthy (:status (integrity/report frame))))
  (doseq [[changed kind]
          [[(assoc-in frame [:qualifiers 5 :entity] nil) :missing-qualifier]
           [(assoc-in frame [:references 5] {:forward [owner another] :reverse [owner another]}) :shared-qualifier]
           [(assoc-in frame [:references 5 :reverse] []) :asymmetric-qualifier]
           [(assoc-in frame [:qualifiers 5 :entity :eacl/id] "forbidden") :malformed-qualifier]
           [(assoc-in frame [:qualifiers 5 :entity :eacl.relationship-qualifier/format-version] 99) :malformed-qualifier]
           [(assoc frame :duplicate-identities #{owner}) :duplicate-relationship-identity]
           [(assoc-in frame [:malformed-halves :count] 1) :malformed-relationship-half]]]
    (let [report (integrity/report changed)]
      (is (= :corrupt (:status report)))
      (is (= 1 (get-in report [:counts kind]))))))

(deftest orphan-and-mutation-evidence
  (let [orphan (assoc frame :references {})
        changed (assoc-in orphan [:qualifiers 5 :entity :eacl.relationship-qualifier/valid-until-ms] 2000)]
    (is (= :healthy (:status (integrity/report orphan))))
    (is (= [5] (:cleanup-candidates (integrity/report orphan))))
    (is (= :snapshot-only (:immutability-evidence (integrity/report changed))))
    (is (= 1 (get-in (integrity/report changed {:before orphan}) [:counts :mutable-qualifier])))
    (is (empty? (:cleanup-candidates (integrity/report changed {:before orphan}))))))

(deftest bounded-samples-and-source-fence
  (let [many (assoc frame :references {} :qualifiers
                    (into {} (for [qid (range 1 101)] [qid {:entity (assoc entity :db/id qid)}])))
        report (integrity/report many {:sample-size 3})]
    (is (= 100 (get-in report [:counts :unattached-qualifier])))
    (is (= 3 (count (:sample report))))
    (is (= 3 (count (:cleanup-candidates report)))))
  (is (= :eacl.integrity/source-mismatch
         (try (integrity/report frame {:before (assoc-in frame [:source :id] "other")}) nil
              (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e (:type (ex-data e)))))))
