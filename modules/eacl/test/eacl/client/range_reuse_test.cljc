(ns eacl.client.range-reuse-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is testing]]
            [eacl.client.range-reuse :as range-reuse]))

(defn- edge [i] {:kind :stable-edge :ordinal i :result-eid (* 10 i)})

(defn- page
  "A computed page holding results `from` (inclusive) to `to` (exclusive)
  of a walk, with the walk's flags at its ends."
  [from to {:keys [next? previous?] :or {next? false previous? false}}]
  {:data (vec (range from to))
   :edges (mapv edge (range from to))
   :range-reusable? true
   :page-info {:start-cursor (when (< from to) (edge from))
               :end-cursor (when (< from to) (edge (dec to)))
               :has-next-page? next?
               :has-previous-page? previous?}})

(defn- first-window
  ([size] {:kind :first :size size :boundary nil})
  ([size after] {:kind :first :size size :boundary (edge after)}))

(defn- last-window
  ([size] {:kind :last :size size :boundary nil})
  ([size before] {:kind :last :size size :boundary (edge before)}))

(defn- semantic-key
  [public internal]
  {:operation :lookup-resources
   :query {:public public :internal internal}
   :demand {:kind :page :size (or (:first public) (:last public))}
   :engine-version 1})

(deftest walk-key-strips-page-size-and-boundary-test
  (let [basis {:basis-identity {:revision 3}}
        key (fn [public] (range-reuse/walk-key
                          basis (semantic-key public (assoc public :internal 1))))]
    (is (= (key {:subject :s :first 20}) (key {:subject :s :first 10})))
    (is (= (key {:subject :s :first 20}) (key {:subject :s :first 10 :after "c"}))
        "the boundary is not part of the walk")
    (is (not= (key {:subject :s :first 20}) (key {:subject :s :last 20})))
    (is (not= (key {:subject :s :first 20}) (key {:subject :t :first 20})))
    (is (not= (key {:subject :s :first 20})
              (range-reuse/walk-key {:basis-identity {:revision 4}}
                                    (semantic-key {:subject :s :first 20} {}))))
    (is (nil? (key {:subject :s})) "an unsized page has no walk key")))

(deftest window-reads-the-internal-boundary-test
  (is (= {:kind :first :size 5 :boundary (edge 9)}
         (range-reuse/window (semantic-key {:first 5 :after "token"}
                                           {:first 5 :after (edge 9)}))))
  (is (= {:kind :last :size 3 :boundary nil}
         (range-reuse/window (semantic-key {:last 3} {:last 3}))))
  (is (nil? (range-reuse/window (semantic-key {:subject :s} {:subject :s})))))

(defn- tier-with
  [& windows-and-pages]
  (let [tier (range-reuse/tier {})]
    (doseq [[window page] (partition 2 windows-and-pages)]
      (range-reuse/publish! tier :walk window page))
    tier))

(deftest windows-inside-a-segment-are-served-test
  (let [tier (tier-with (first-window 20) (page 0 20 {:next? true}))]
    (testing "a shorter page from the walk start"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 10))]
        (is (= (vec (range 10)) (:data page)))
        (is (= (edge 9) (get-in page [:page-info :end-cursor])))
        (is (= (edge 0) (get-in page [:page-info :start-cursor])))
        (is (true? (get-in page [:page-info :has-next-page?])))
        (is (false? (get-in page [:page-info :has-previous-page?])))))
    (testing "a continuation inside the segment"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 5 9))]
        (is (= [10 11 12 13 14] (:data page)))
        (is (= (edge 14) (get-in page [:page-info :end-cursor])))
        (is (true? (get-in page [:page-info :has-previous-page?])))
        (is (true? (get-in page [:page-info :has-next-page?])))))
    (testing "the window ending exactly at the segment's end keeps the walk's next flag"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 5 14))]
        (is (= [15 16 17 18 19] (:data page)))
        (is (true? (get-in page [:page-info :has-next-page?])))))
    (testing "the same size from the same start is the whole segment"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 20))]
        (is (= (vec (range 20)) (:data page)))))
    (testing "a window past the segment is a partial hit with the remainder request"
      (let [{:keys [page partial continuation]}
            (range-reuse/lookup! tier :walk (first-window 8 15))]
        (is (nil? page))
        (is (= [16 17 18 19] (:data partial)))
        (is (= {:kind :first :size 4 :boundary (edge 19) :checkpoint-size 20} continuation)
            "the remainder names the series whose checkpoint sits at the segment's end")))
    (testing "a window starting at the segment's end is a resume of the segment's series"
      (let [hit (range-reuse/lookup! tier :walk (first-window 5 19))]
        (is (nil? (:page hit)))
        (is (nil? (:partial hit)))
        (is (= {:kind :first :size 5 :boundary (edge 19) :checkpoint-size 20} (:continuation hit)))
        (is (= 1 (:resumes (range-reuse/stats tier))))))
    (testing "a boundary the segment does not hold misses"
      (is (nil? (range-reuse/lookup! tier :walk (first-window 5 40))))
      (is (= 1 (:misses (range-reuse/stats tier)))))
    (testing "the merged segment's series is the series of its last page"
      (let [tier (tier-with (first-window 5) (page 0 5 {:next? true})
                            (first-window 7 4) (page 5 12 {:next? true :previous? true}))]
        (is (= 7 (:checkpoint-size (:continuation (range-reuse/lookup! tier :walk (first-window 3 11))))))))
    (testing "a walk-start request needs a segment with no previous page"
      (let [tier (tier-with (first-window 5 9) (page 10 15 {:next? true :previous? true}))]
        (is (nil? (range-reuse/lookup! tier :walk (first-window 3))))
        (is (= [10 11 12] (:data (:page (range-reuse/lookup! tier :walk (first-window 3 9))))))))))

(deftest complete-segments-answer-larger-requests-test
  (let [tier (tier-with (first-window 20) (page 0 12 {:next? false}))
        {:keys [page]} (range-reuse/lookup! tier :walk (first-window 50))]
    (is (= (vec (range 12)) (:data page)))
    (is (false? (get-in page [:page-info :has-next-page?])))
    (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 50 7))]
      (is (= [8 9 10 11] (:data page)))
      (is (false? (get-in page [:page-info :has-next-page?]))))))

(deftest reverse-windows-are-served-symmetrically-test
  (let [tier (tier-with (last-window 20) (page 30 50 {:previous? true}))]
    (testing "a shorter reverse page from the walk end"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (last-window 10))]
        (is (= (vec (range 40 50)) (:data page)))
        (is (= (edge 40) (get-in page [:page-info :start-cursor])))
        (is (true? (get-in page [:page-info :has-previous-page?])))
        (is (false? (get-in page [:page-info :has-next-page?])))))
    (testing "a reverse continuation inside the segment"
      (let [{:keys [page]} (range-reuse/lookup! tier :walk (last-window 5 40))]
        (is (= [35 36 37 38 39] (:data page)))
        (is (true? (get-in page [:page-info :has-next-page?])))
        (is (true? (get-in page [:page-info :has-previous-page?])))))
    (testing "a reverse window past the segment's start is a partial hit"
      (let [{:keys [partial continuation]}
            (range-reuse/lookup! tier :walk (last-window 8 34))]
        (is (= [30 31 32 33] (:data partial)))
        (is (= {:kind :last :size 4 :boundary (edge 30)} continuation)
            "backward runs replay, so no series is named")))
    (testing "a reverse window into a walk with no previous page is complete"
      (let [tier (tier-with (last-window 20) (page 0 6 {}))
            {:keys [page]} (range-reuse/lookup! tier :walk (last-window 10 4))]
        (is (= [0 1 2 3] (:data page)))
        (is (false? (get-in page [:page-info :has-previous-page?])))))))

(deftest adjacent-pages-merge-into-one-segment-test
  (let [tier (tier-with (first-window 5) (page 0 5 {:next? true})
                        (first-window 5 4) (page 5 10 {:next? true :previous? true})
                        (first-window 5 9) (page 10 15 {:next? true :previous? true}))]
    (is (= 1 (:deposits (range-reuse/stats tier))))
    (is (= 2 (:extensions (range-reuse/stats tier))))
    (let [{:keys [page]} (range-reuse/lookup! tier :walk (first-window 15))]
      (is (= (vec (range 15)) (:data page)) "the three pages became one segment"))
    (testing "a page that ends at a segment's start is prepended"
      (let [tier (tier-with (first-window 5 9) (page 10 15 {:next? true :previous? true})
                            (first-window 10) (page 0 10 {:next? true}))]
        (is (= (vec (range 15))
               (:data (:page (range-reuse/lookup! tier :walk (first-window 15))))))))
    (testing "a republished derived page is covered and changes nothing"
      (let [before (range-reuse/stats tier)]
        (range-reuse/publish! tier :walk (first-window 3 4) (page 5 8 {:next? true :previous? true}))
        (is (= (select-keys before [:deposits :extensions :supersessions])
               (select-keys (range-reuse/stats tier) [:deposits :extensions :supersessions])))))))

(deftest retention-is-bounded-per-walk-test
  (let [tier (range-reuse/tier {:max-results-per-walk 12 :max-segments-per-walk 2})]
    (range-reuse/publish! tier :walk (first-window 5) (page 0 5 {:next? true}))
    (range-reuse/publish! tier :walk (first-window 5 19) (page 20 25 {:next? true :previous? true}))
    (range-reuse/publish! tier :walk (first-window 5 39) (page 40 45 {:next? true :previous? true}))
    (is (nil? (range-reuse/lookup! tier :walk (first-window 5)))
        "the oldest segment left when the segment cap was exceeded")
    (is (some? (:page (range-reuse/lookup! tier :walk (first-window 5 39)))))
    (range-reuse/publish! tier :walk (first-window 10 44) (page 45 55 {:next? true :previous? true}))
    (is (nil? (range-reuse/lookup! tier :walk (first-window 5 19)))
        "the result cap drops the oldest segment")
    (is (= (vec (range 40 55))
           (:data (:page (range-reuse/lookup! tier :walk (first-window 15 39))))))))

(deftest compose-joins-a-partial-page-with-its-continuation-test
  (let [partial (page 16 20 {:next? true :previous? true})
        remainder (page 20 24 {:next? true :previous? true})
        composed (range-reuse/compose partial {:kind :first :size 4 :boundary (edge 19) :checkpoint-size 20} remainder)]
    (is (= (vec (range 16 24)) (:data composed)))
    (is (= (edge 16) (get-in composed [:page-info :start-cursor])))
    (is (= (edge 23) (get-in composed [:page-info :end-cursor])))
    (is (true? (get-in composed [:page-info :has-next-page?])))
    (is (true? (get-in composed [:page-info :has-previous-page?]))))
  (testing "an exhausted remainder ends the walk"
    (let [composed (range-reuse/compose (page 16 20 {:next? true :previous? true})
                                        {:kind :first :size 4 :boundary (edge 19)}
                                        (page 20 22 {:previous? true}))]
      (is (= (vec (range 16 22)) (:data composed)))
      (is (false? (get-in composed [:page-info :has-next-page?])))))
  (testing "reverse composition puts the remainder first"
    (let [composed (range-reuse/compose (page 30 34 {:next? true :previous? true})
                                        {:kind :last :size 4 :boundary (edge 30)}
                                        (page 26 30 {:next? true :previous? true}))]
      (is (= (vec (range 26 34)) (:data composed)))
      (is (= (edge 26) (get-in composed [:page-info :start-cursor])))))
  (testing "a remainder without edges cannot compose"
    (is (nil? (range-reuse/compose (page 16 20 {:next? true})
                                   {:kind :first :size 4 :boundary (edge 19)}
                                   {:data [20] :page-info {}})))))

(deftest derive-page-keeps-the-first-cut-contract-test
  (let [resident (page 0 20 {:next? true :previous? true})]
    (is (= (vec (range 10)) (:data (range-reuse/derive-page [:first 10] resident))))
    (is (= (vec (range 10 20)) (:data (range-reuse/derive-page [:last 10] resident))))
    (is (nil? (range-reuse/derive-page [:first 30] resident)))
    (is (= (vec (range 20))
           (:data (range-reuse/derive-page [:first 30] (page 0 20 {:previous? true})))))
    (is (nil? (range-reuse/derive-page [:first 10] {:data [1] :page-info {}})))))
