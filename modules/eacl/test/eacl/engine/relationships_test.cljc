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
                    :v 1
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
