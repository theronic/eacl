(ns eacl.engine.fault-free-membership-test
  "Exhaustive finite graphs compare admitted batching with ordered points,
   including malformed metadata, cycles and both resource orders."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.engine.sealed-plan :as sealed]
            [eacl.engine.stable-route :as route]
            [eacl.test-support.tuple-adapter :as tuples]))

(def ^:private schema
  "definition user {} definition folder {
     relation reader: user
     relation parent: folder
     permission direct = reader + parent->direct
     permission arrow = parent->reader + parent->arrow
   }")

(def ^:private edges
  [[:user 7 :reader :folder 1] [:user 7 :reader :folder 2]
   [:folder 1 :parent :folder 1] [:folder 1 :parent :folder 2]
   [:folder 2 :parent :folder 1] [:folder 2 :parent :folder 2]])

(defn- relationships [code]
  (loop [code code index 0 result #{}]
    (if (= index (count edges))
      result
      (let [kind (mod code 4) edge (nth edges index)]
        (recur (quot code 4) (inc index)
               (case kind
                 0 result
                 1 (conj result edge)
                 2 (conj result (conj edge 1000))
                 3 (conj result (conj edge 1001))))))))

(defn- request []
  (qualification/request
   {:time 100 :context {} :basis {:source :finite-fault-graphs :revision 1}
    :version (constantly 1)
    :entity (fn [eid]
              (case eid
                1000 {:db/id eid :eacl.relationship-qualifier/format-version 1
                      :eacl.relationship-qualifier/valid-until-ms 200}
                1001 {:db/id eid :eacl.relationship-qualifier/format-version 1
                      :eacl.relationship-qualifier/valid-until-ms 200 :unexpected true}
                {:db/id eid}))}))

(defn- run-campaign []
  (let [adapter (tuples/from-schema schema #{})
        plans (mapv #(sealed/seal-plan adapter [:folder %]) [:direct :arrow])]
    (first
     (for [code (range 4096)
           :let [adapter (tuples/from-schema schema (relationships code))]
           plan plans
           order [[1 2 1] [2 1 2]]
           :let [options {:adapter adapter :plan plan :subject-type :user :subject-eid 7
                          :verify-fault-freedom? true :physical-chunk-size 1}
                 expected (mapv #(evidence/value
                                  (route/check-eids (assoc options :resource-eid % :qualification (request))))
                                order)
                 actual (mapv evidence/value
                              (route/check-many-eids
                               (assoc options :resource-eids order :qualification (request))))]
           :when (not= expected actual)]
       {:graph code :root (:root plan) :order order :expected expected :actual actual}))))

(deftest certified-batching-preserves-ordered-faults-on-every-two-node-graph
  ;; Six possible edges, each absent, plain, expiring or malformed: 4^6
  ;; graphs. Compare fault values as well as permissionship. Duplicate roots
  ;; and opposite query orders exercise request-local proof/answer reuse.
  (doseq [holding-limit [1 256]]
    (with-redefs [route/holdings-limit holding-limit]
      (is (nil? (run-campaign)) (str "holdings limit " holding-limit)))))
