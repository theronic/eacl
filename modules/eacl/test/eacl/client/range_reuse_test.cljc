(ns eacl.client.range-reuse-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.client.range-reuse :as range-reuse]))

(defn- edge [i] {:ordinal i :eid (* 10 i)})

(defn- page
  [n {:keys [next? previous?] :or {next? false previous? false}}]
  {:data (vec (range n))
   :edges (mapv edge (range n))
   :range-reusable? true
   :page-info {:start-cursor (when (pos? n) (edge 0))
               :end-cursor (when (pos? n) (edge (dec n)))
               :has-next-page? next?
               :has-previous-page? previous?}})

(deftest range-key-strips-page-size-and-keeps-everything-else-test
  (let [basis {:basis-identity {:revision 3}}
        key (fn [public] (range-reuse/range-key
                          basis {:operation :lookup-resources
                                 :query {:public public :internal (assoc public :internal 1)}
                                 :engine-version 1}))]
    (is (= (key {:subject :s :first 20}) (key {:subject :s :first 10})))
    (is (not= (key {:subject :s :first 20}) (key {:subject :s :last 20})))
    (is (not= (key {:subject :s :first 20}) (key {:subject :t :first 20})))
    (is (nil? (key {:subject :s})) "an unsized page has no range key")))

(deftest derive-prefix-pages-test
  (let [resident (page 20 {:next? true :previous? true})]
    (testing "a shorter forward page is a prefix with the tenth edge as its end"
      (let [derived (range-reuse/derive-page [:first 10] resident)]
        (is (= (vec (range 10)) (:data derived)))
        (is (= (edge 9) (get-in derived [:page-info :end-cursor])))
        (is (= (edge 0) (get-in derived [:page-info :start-cursor])))
        (is (true? (get-in derived [:page-info :has-next-page?])))
        (is (true? (get-in derived [:page-info :has-previous-page?])))
        (is (= 10 (count (:edges derived))))))
    (testing "the same size is the resident page itself"
      (is (identical? resident (range-reuse/derive-page [:first 20] resident))))
    (testing "a longer request cannot be derived while a next page exists"
      (is (nil? (range-reuse/derive-page [:first 30] resident))))
    (testing "a longer request is answered by a complete resident page"
      (let [complete (page 7 {:next? false})]
        (is (identical? complete (range-reuse/derive-page [:first 30] complete)))))
    (testing "next-page flag reflects the resident's own flag when sizes match"
      (let [complete (page 20 {:next? false})]
        (is (false? (get-in (range-reuse/derive-page [:first 20] complete)
                            [:page-info :has-next-page?])))
        (is (true? (get-in (range-reuse/derive-page [:first 5] complete)
                           [:page-info :has-next-page?])))))))

(deftest derive-suffix-pages-test
  (let [resident (page 20 {:next? true :previous? false})]
    (let [derived (range-reuse/derive-page [:last 5] resident)]
      (is (= (vec (range 15 20)) (:data derived)))
      (is (= (edge 15) (get-in derived [:page-info :start-cursor])))
      (is (= (edge 19) (get-in derived [:page-info :end-cursor])))
      (is (true? (get-in derived [:page-info :has-previous-page?])))
      (is (true? (get-in derived [:page-info :has-next-page?]))))
    (is (identical? resident (range-reuse/derive-page [:last 20] resident)))
    (is (identical? resident (range-reuse/derive-page [:last 25] resident))
        "no previous page: the resident page is the whole window")
    (is (nil? (range-reuse/derive-page
               [:last 25] (page 20 {:previous? true}))))))

(deftest unmarked-or-malformed-pages-never-derive-test
  (is (nil? (range-reuse/derive-page [:first 5] (dissoc (page 20 {}) :range-reusable?))))
  (is (nil? (range-reuse/derive-page [:first 5] (assoc (page 20 {}) :edges [(edge 0)]))))
  (is (nil? (range-reuse/derive-page [:first 0] (page 20 {}))))
  (is (nil? (range-reuse/derive-page nil (page 20 {})))))

(deftest tier-keeps-the-longest-page-test
  (let [tier (range-reuse/tier {:max-entries 8})
        key [:range :basis :first {:q 1}]]
    (is (nil? (range-reuse/lookup! tier key [:first 5])))
    (range-reuse/publish! tier key (page 10 {:next? true}))
    (is (= (vec (range 5)) (:data (range-reuse/lookup! tier key [:first 5]))))
    (is (nil? (range-reuse/lookup! tier key [:first 15])))
    (range-reuse/publish! tier key (page 20 {:next? true}))
    (is (= (vec (range 15)) (:data (range-reuse/lookup! tier key [:first 15]))))
    (range-reuse/publish! tier key (page 4 {}))
    (is (= 20 (count (:data (range-reuse/lookup! tier key [:first 20]))))
        "a shorter page never supersedes a longer one")
    (let [stats (range-reuse/stats tier)]
      (is (= 1 (:deposits stats)))
      (is (= 1 (:supersessions stats)))
      (is (= 1 (:entry-count stats))))))
