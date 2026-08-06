(ns eacl.formal.page-window-bridge-test
  (:require
   [clojure.test :refer [deftest is]]
   [eacl.formal.page-window-bridge :as formal]))

(defn- expected-page
  [values request]
  (let [n (count values)
        direction (if (contains? request :last) :desc :asc)
        size (or (:first request) (:last request) 1000)
        bound (if (= :asc direction)
                (:after request)
                (:before request))
        start
        (if (= :asc direction)
          (if (some? bound)
            (min n (inc bound))
            0)
          (let [end (if (some? bound)
                      (min n bound)
                      n)]
            (max 0 (- end size))))
        end
        (if (= :asc direction)
          (min n (+ start size))
          (if (some? bound)
            (min n bound)
            n))]
    {:items (subvec values start end)
     :start start
     :end end
     :has-next? (and (< start end) (< end n))
     :has-previous? (and (< start end) (pos? start))}))

(deftest generated-page-normalization-and-window-properties
  (doseq [request
          [{:first 1 :last 1}
           {:first 1 :after nil}
           {:last 1 :before nil}
           {:after 1}
           {:before 1}
           {:first 0}
           {:last -1}
           {:first 10001}]]
    (is (= :invalid (:status (formal/paginate [0 1 2] request)))
        (pr-str request)))
  (doseq [n (range 21)
          size (range 1 8)
          direction [:asc :desc]
          bound (range 0 (inc n))]
    (let [values (vec (range n))
          request
          (if (= :asc direction)
            {:first size :after bound}
            {:last size :before bound})
          expected (expected-page values request)
          actual (formal/paginate values request)]
      (is (= :valid (:status actual)))
      (is (= expected
             (select-keys
              actual
              [:items
               :start
               :end
               :has-next?
               :has-previous?]))
          (pr-str {:n n :request request}))))
  (doseq [n (range 30)
          size (range 1 10)]
    (let [values (vec (range n))]
      (is (= values (formal/forward-walk values size))))))

(deftest generated-cursor-continuation-decisions
  (let [base
        {:authenticated? true
         :scope-matches? true
         :expired? false
         :source "source"
         :cursor-source "source"
         :current-proof "proof"
         :cursor-proof "proof"
         :mode :exact-snapshot
         :cursor-graph 7}]
    (is (= :current
           (formal/continuation-decision base)))
    (is (= :exact
           (formal/continuation-decision
            (assoc
             base
             :current-proof "changed"
             :exact {:graph 7
                     :source "source"
                     :proof "proof"}))))
    (is (= :rebase-current
           (formal/continuation-decision
            (assoc base
                   :current-proof "changed"
                   :mode :recover-current))))
    (is (= :expired
           (formal/continuation-decision
            (assoc base :expired? true))))
    (is (= :snapshot-unavailable
           (formal/continuation-decision
            (assoc base :current-proof "changed"))))
    (is (= :divergence
           (formal/continuation-decision
            (assoc
             base
             :current-proof "changed"
             :exact {:graph 8
                     :source "source"
                     :proof "proof"}))))
    (is (= :invalid-authentication
           (formal/continuation-decision
            (assoc base :authenticated? false))))
    (is (= :scope-mismatch
           (formal/continuation-decision
            (assoc base :scope-matches? false))))))
