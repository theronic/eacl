(ns eacl.engine.relationships-test
  (:require [eacl.engine.relationships :as relationships]
            #?(:clj [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])))

(def scan-specs
  [{:idx 0 :scan-kind :forward-partial}
   {:idx 1 :scan-kind :forward-partial}
   {:idx 2 :scan-kind :forward-partial}])

(def rows-by-spec
  {0 [{:spec-idx 0
       :subject-id 11
       :resource-id 101
       :relationship :r-0-0}
      {:spec-idx 0
       :subject-id 12
       :resource-id 101
       :relationship :r-0-1}
      {:spec-idx 0
       :subject-id 13
       :resource-id 103
       :relationship :r-0-2}]
   1 []
   2 [{:spec-idx 2
       :subject-id 21
       :resource-id 201
       :relationship :r-2-0}
      {:spec-idx 2
       :subject-id 22
       :resource-id 202
       :relationship :r-2-1}
      {:spec-idx 2
       :subject-id 23
       :resource-id 202
       :relationship :r-2-2}
      {:spec-idx 2
       :subject-id 24
       :resource-id 204
       :relationship :r-2-3}]})

(def expected-relationships
  [:r-0-0 :r-0-1 :r-0-2 :r-2-0 :r-2-1 :r-2-2 :r-2-3])

(defn- counted-lazy-seq
  [realized values]
  (lazy-seq
   (when-let [remaining (seq values)]
     (swap! realized inc)
     (cons (first remaining)
           (counted-lazy-seq realized (rest remaining))))))

(defn- scanner
  [realized]
  (fn [{:keys [idx scan-kind]} edge direction]
    (let [ordered (cond-> (get rows-by-spec idx)
                    (= :desc direction) reverse)
          remaining
          (if edge
            (drop-while
             #(not
               (relationships/beyond-cursor?
                scan-kind direction edge %))
             ordered)
            ordered)]
      (counted-lazy-seq realized remaining))))

(defn- walk-forward
  [page-size scan-fn]
  (loop [query {:first page-size}
         pages []]
    (let [page (relationships/execute-page scan-specs query scan-fn)
          pages' (conj pages page)]
      (if (get-in page [:page-info :has-next-page?])
        (recur {:first page-size
                :after (get-in page [:page-info :end-cursor])}
               pages')
        pages'))))

(defn- walk-backward
  [page-size scan-fn]
  (loop [query {:last page-size}
         pages ()]
    (let [page (relationships/execute-page scan-specs query scan-fn)
          pages' (conj pages page)]
      (if (get-in page [:page-info :has-previous-page?])
        (recur {:last page-size
                :before (get-in page [:page-info :start-cursor])}
               pages')
        pages'))))

(deftest keyset-pages-form-one-stable-duplicate-free-sequence-test
  (doseq [page-size [1 2 3 7 10]]
    (testing (str "page size " page-size)
      (let [forward-pages (walk-forward page-size (scanner (atom 0)))
            backward-pages (walk-backward page-size (scanner (atom 0)))
            forward (mapcat :data forward-pages)
            backward (mapcat :data backward-pages)]
        (is (= expected-relationships (vec forward)))
        (is (= expected-relationships (vec backward)))
        (is (= (count expected-relationships)
               (count (distinct forward))))
        (is (= (count expected-relationships)
               (count (distinct backward))))
        (is (= forward-pages
               (walk-forward page-size (scanner (atom 0)))))))))

(deftest keyset-page-realizes-only-one-page-plus-lookahead-test
  (let [realized (atom 0)
        page (relationships/execute-page
              scan-specs {:first 2} (scanner realized))]
    (is (= [:r-0-0 :r-0-1] (:data page)))
    (is (= 3 @realized))
    (is (true? (get-in page [:page-info :has-next-page?])))))

(deftest keyset-page-propagates-the-remaining-physical-budget-test
  (let [budgets (atom [])
        scan-fn
        (fn [{:keys [idx physical-limit]} _edge _direction]
          (swap! budgets conj [idx physical-limit])
          (take physical-limit (get rows-by-spec idx)))
        page (relationships/execute-page scan-specs {:first 2} scan-fn)]
    (is (= [:r-0-0 :r-0-1] (:data page)))
    (is (= [[0 3]] @budgets))
    (is (true? (get-in page [:page-info :has-next-page?])))))

(deftest keyset-cursor-is-a-direction-neutral-exclusive-position-test
  (let [scan-fn (scanner (atom 0))
        forward-page
        (relationships/execute-page
         scan-specs {:first 3} scan-fn)
        backward-from-forward
        (relationships/execute-page
         scan-specs
         {:last 2
          :before (get-in forward-page [:page-info :end-cursor])}
         scan-fn)
        backward-page
        (relationships/execute-page
         scan-specs {:last 3} scan-fn)
        forward-from-backward
        (relationships/execute-page
         scan-specs
         {:first 2
          :after (get-in backward-page [:page-info :start-cursor])}
         scan-fn)]
    (is (= [:r-0-0 :r-0-1] (:data backward-from-forward)))
    (is (= [:r-2-2 :r-2-3] (:data forward-from-backward)))))

(deftest keyset-cursor-is-strictly-validated-test
  (let [scan-fn (scanner (atom 0))
        valid-edge {:kind :relationship-index
                    :v relationships/relationship-cursor-version
                    :anchor :progress
                    :scan-index 0
                    :subject-id 11
                    :resource-id 101}]
    (is (= [:r-0-1]
           (:data
            (relationships/execute-page
             scan-specs {:first 1 :after valid-edge} scan-fn))))
    (doseq [invalid-edge [(assoc valid-edge :scan-index 3)
                          (assoc valid-edge :subject-id -1)
                          (assoc valid-edge :unexpected true)
                          (dissoc valid-edge :resource-id)]]
      (is (= :eacl.pagination/invalid-cursor
             (try
               (relationships/execute-page
                scan-specs {:first 1 :after invalid-edge} scan-fn)
               nil
               (catch #?(:clj Exception :cljs :default) error
                 (:eacl/error (ex-data error)))))))))

(deftest keyset-progress-anchor-need-not-be-an-emitted-row-test
  (let [examined-but-not-emitted (second (get rows-by-spec 0))
        progress (relationships/progress-edge examined-but-not-emitted)
        page
        (relationships/execute-page
         scan-specs {:first 2 :after progress} (scanner (atom 0)))]
    (is (= :progress (:anchor progress)))
    (is (= [:r-0-2 :r-2-0] (:data page))
        "continuation starts strictly after the last examined candidate")))

(deftest keyset-pagination-rejects-present-nil-cursors-test
  (doseq [query [{:first 1 :after nil}
                 {:last 1 :before nil}]]
    (is (= :eacl.pagination/invalid-cursor
           (try
             (relationships/execute-page
              scan-specs query (scanner (atom 0)))
             nil
             (catch #?(:clj Exception :cljs :default) error
               (:eacl/error (ex-data error)))))
        (pr-str query))))

;; --- Partial scans over qualified rows (EACL-FORMAL-080) --------------------
;;
;; A partial scan reads AVET values `[p0 p1 p2 primary qualifier]` across
;; owners, so one primary endpoint's rows are ordered by qualifier (plain rows
;; first) before owner. The model index below sorts by that reference key,
;; independently of the engine comparator, and resumes like the backends: an
;; inclusive seek followed by `beyond-cursor?`.

(defn- reference-key
  [scan-kind {:keys [subject-id resource-id qualifier-id]}]
  (let [qualifier (if (some? qualifier-id) [1 qualifier-id] [0 0])]
    (case scan-kind
      :forward-partial [resource-id qualifier subject-id]
      :reverse-partial [subject-id qualifier resource-id])))

(defn- index-scanner
  "Models one backend index. `:exact` seeks to the cursor row's complete
  physical position; `:group` seeks to the start or end of the cursor's
  primary group, as the pre-fix seek bound did."
  [specs rows-by-spec seek]
  (fn [{:keys [idx scan-kind]} edge direction]
    (let [ordered (cond->> (sort-by #(reference-key scan-kind %)
                                    (get rows-by-spec idx))
                    (= :desc direction) reverse)
          primary (case scan-kind
                    :forward-partial :resource-id
                    :reverse-partial :subject-id)
          sought
          (if-not edge
            ordered
            (let [bound (reference-key scan-kind edge)
                  before? (fn [row]
                            (let [order (compare (reference-key scan-kind row) bound)]
                              (case direction :asc (neg? order) :desc (pos? order))))]
              (case seek
                :exact (drop-while before? ordered)
                :group (drop-while #(case direction
                                      :asc (< (primary %) (primary edge))
                                      :desc (> (primary %) (primary edge)))
                                   ordered))))]
      (drop-while #(not (relationships/beyond-cursor?
                         scan-kind direction edge %))
                  sought))))

(defn- qualified-fixture
  "Two specs whose primary groups mix plain rows with qualifiers that sort
  against owner order, including the EACL-RS-004 shape (spec 0, primary 7)."
  [scan-kind]
  (let [row (fn [idx primary owner qualifier]
              (cond-> (case scan-kind
                        :forward-partial {:spec-idx idx :resource-id primary :subject-id owner}
                        :reverse-partial {:spec-idx idx :subject-id primary :resource-id owner})
                (some? qualifier) (assoc :qualifier-id qualifier)
                true (as-> r (assoc r :relationship [idx primary owner qualifier]))))]
    {:specs [{:idx 0 :scan-kind scan-kind} {:idx 1 :scan-kind scan-kind}]
     :rows-by-spec
     {0 [(row 0 7 3 900) (row 0 7 7 nil) (row 0 7 5 120) (row 0 7 1 500)
         (row 0 8 2 nil) (row 0 8 9 40) (row 0 9 4 nil)]
      1 [(row 1 7 6 300) (row 1 7 2 nil) (row 1 8 8 41)]}}))

(defn- expected-order
  [{:keys [specs rows-by-spec]}]
  (into []
        (comp (mapcat (fn [{:keys [idx scan-kind]}]
                        (sort-by #(reference-key scan-kind %) (get rows-by-spec idx))))
              (map :relationship))
        specs))

(defn- walk-pages
  "Follows a forward or backward walk; fails the test instead of looping when
  a walk exceeds `max-pages`."
  [execute direction size max-pages]
  (loop [query (case direction :asc {:first size} :desc {:last size})
         pages []]
    (if (> (count pages) max-pages)
      (do (is false (str "walk did not terminate within " max-pages " pages"))
          pages)
      (let [page (execute query)
            pages (conj pages page)
            info (:page-info page)]
        (case direction
          :asc (if (:has-next-page? info)
                 (recur {:first size :after (:end-cursor info)} pages)
                 pages)
          :desc (if (:has-previous-page? info)
                  (recur {:last size :before (:start-cursor info)} pages)
                  (vec (reverse pages))))))))

(deftest qualified-partial-scans-page-in-physical-order-test
  (doseq [scan-kind [:forward-partial :reverse-partial]
          seek [:exact :group]
          :let [{:keys [specs rows-by-spec] :as fixture} (qualified-fixture scan-kind)
                scan-fn (index-scanner specs rows-by-spec seek)
                expected (expected-order fixture)]
          direction [:asc :desc]
          size (range 1 (+ 2 (count expected)))]
    (testing (pr-str [scan-kind seek direction size])
      (let [pages (walk-pages #(relationships/execute-page specs % scan-fn)
                              direction size (inc (count expected)))
            walked (into [] (mapcat :data) pages)]
        (is (= expected walked))
        (is (= (count expected) (count (distinct walked))))))))

(deftest qualified-partial-scan-edges-carry-the-qualifier-test
  (let [{:keys [specs rows-by-spec]} (qualified-fixture :reverse-partial)
        page (relationships/execute-page
              specs {:first 2} (index-scanner specs rows-by-spec :exact))
        end (get-in page [:page-info :end-cursor])]
    (is (= [[0 7 7 nil] [0 7 5 120]] (:data page)))
    (is (= 120 (:qualifier-id end)))
    (is (not (contains? (get-in page [:page-info :start-cursor]) :qualifier-id))
        "a plain row keeps the original edge shape")))

(deftest qualified-partial-filtered-windows-terminate-test
  (doseq [scan-kind [:forward-partial :reverse-partial]
          :let [{:keys [specs rows-by-spec] :as fixture} (qualified-fixture scan-kind)
                scan-fn (index-scanner specs rows-by-spec :exact)
                expected (expected-order fixture)]
          [label accept?] [[:all (constantly true)]
                           [:none (constantly false)]
                           [:qualified (fn [[_ _ _ qualifier]] (some? qualifier))]
                           [:odd-owner (fn [[_ _ owner _]] (odd? owner))]]
          candidate-window [1 2 3 (count expected) 10000]
          direction [:asc :desc]
          size [1 2 3]]
    (testing (pr-str [scan-kind label candidate-window direction size])
      (let [pages (walk-pages
                   #(relationships/execute-filtered-window
                     specs % nil scan-fn
                     {:candidate-window candidate-window :accept? accept?})
                   direction size (+ 2 (count expected)))
            walked (into [] (mapcat :data) pages)]
        (is (= (filterv accept? expected) walked))))))

(defn- next-random
  [state]
  (mod (* (max 1 state) 48271) 2147483647))

(defn- random-below
  [state n]
  (let [state (next-random state)]
    [state (mod state n)]))

(defn- random-qualified-fixture
  "Rows with unique (primary, owner) per spec and unique qualifier eids, as
  in storage, drawn from small domains so primary groups mix plain and
  qualified rows in every relative order."
  [seed scan-kind]
  (loop [state seed
         remaining 24
         rows-by-spec {0 {} 1 {} 2 {}}
         qualifier 100]
    (if (zero? remaining)
      [state
       {:specs (mapv (fn [idx] {:idx idx :scan-kind scan-kind}) (range 3))
        :rows-by-spec
        (into {}
              (map (fn [[idx rows]]
                     [idx (mapv (fn [[[primary owner] q]]
                                  (cond-> (case scan-kind
                                            :forward-partial {:spec-idx idx :resource-id primary :subject-id owner}
                                            :reverse-partial {:spec-idx idx :subject-id primary :resource-id owner})
                                    (some? q) (assoc :qualifier-id q)
                                    true (as-> r (assoc r :relationship [idx primary owner q]))))
                                rows)]))
              rows-by-spec)}]
      (let [[state idx] (random-below state 3)
            [state primary] (random-below state 4)
            [state owner] (random-below state 6)
            [state qualified] (random-below state 3)
            [state jump] (random-below state 50)
            q (when (pos? qualified) (+ qualifier jump))]
        (recur state (dec remaining)
               (assoc-in rows-by-spec [idx [primary owner]] q)
               (if q (inc q) qualifier))))))

(deftest random-qualified-partial-walks-are-total-and-duplicate-free-test
  (doseq [seed (range 1 61)
          scan-kind [:forward-partial :reverse-partial]]
    (let [[state {:keys [specs rows-by-spec] :as fixture}]
          (random-qualified-fixture seed scan-kind)
          expected (expected-order fixture)
          [state size] (random-below state 5)
          size (inc size)
          [state window] (random-below state (+ 3 (count expected)))
          window (inc window)
          [_ modulus] (random-below state 3)
          accept? (fn [[_ primary owner _]] (zero? (mod (+ primary owner) (inc modulus))))
          scan-fn (index-scanner specs rows-by-spec :exact)]
      (doseq [direction [:asc :desc]]
        (testing (pr-str {:seed seed :scan-kind scan-kind :direction direction
                          :size size :window window :modulus modulus})
          (is (= expected
                 (into [] (mapcat :data)
                       (walk-pages #(relationships/execute-page specs % scan-fn)
                                   direction size (inc (count expected))))))
          (is (= (filterv accept? expected)
                 (into [] (mapcat :data)
                       (walk-pages #(relationships/execute-filtered-window
                                     specs % nil scan-fn
                                     {:candidate-window window :accept? accept?})
                                   direction size (+ 2 (count expected)))))))))))

(deftest scans-outside-physical-order-fail-closed-test
  (let [{:keys [specs rows-by-spec]} (qualified-fixture :reverse-partial)
        ;; Owner order inside each primary group: the order the pre-fix
        ;; comparator assumed, which disagrees with the qualified index.
        scan-fn (fn [{:keys [idx]} _edge direction]
                  (cond->> (sort-by (juxt :subject-id :resource-id) (get rows-by-spec idx))
                    (= :desc direction) reverse))
        failure (fn [f]
                  (try (f) nil
                       (catch #?(:clj Exception :cljs :default) error
                         (select-keys (ex-data error) [:eacl/error :obligation]))))]
    (is (= {:eacl/error :eacl/backend-contract-violation :obligation :strict-order}
           (failure #(relationships/execute-page specs {:first 10} scan-fn))))
    (is (= {:eacl/error :eacl/backend-contract-violation :obligation :strict-order}
           (failure #(relationships/execute-filtered-window
                      specs {:first 1} nil scan-fn
                      {:candidate-window 10000 :accept? (constantly false)})))
        "a filtered window fails instead of re-examining rows until its budget ends")))

(deftest qualified-edge-coordinate-is-validated-test
  (let [{:keys [specs rows-by-spec]} (qualified-fixture :reverse-partial)
        scan-fn (index-scanner specs rows-by-spec :exact)
        edge {:kind :relationship-index
              :v relationships/relationship-cursor-version
              :anchor :progress
              :scan-index 0
              :subject-id 7
              :resource-id 5
              :qualifier-id 120}]
    (is (= [[0 7 1 500]]
           (:data (relationships/execute-page specs {:first 1 :after edge} scan-fn))))
    (doseq [invalid [(assoc edge :qualifier-id -1)
                     (assoc edge :qualifier-id nil)
                     (assoc edge :qualifier-id "120")]]
      (is (= :eacl.pagination/invalid-cursor
             (try
               (relationships/execute-page specs {:first 1 :after invalid} scan-fn)
               nil
               (catch #?(:clj Exception :cljs :default) error
                 (:eacl/error (ex-data error)))))
          (pr-str invalid)))))
