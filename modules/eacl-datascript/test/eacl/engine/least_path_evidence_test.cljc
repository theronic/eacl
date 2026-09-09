(ns eacl.engine.least-path-evidence-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.evidence-test :as values]
            [eacl.authorization.qualification :as qualification]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.engine.least-path :as least-path]
            [eacl.engine.sealed-plan :as sealed]
            [eacl.operator.evaluator-test :as fixtures]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(def source
  "definition user {}
   definition group {
     relation member: user
     permission active = member
   }
   definition doc {
     relation reader: user
     relation writer: user
     relation parent: group
     permission direct = reader + writer
     permission delegated = direct
     permission via = reader + parent->member
     permission inherited = reader + parent->active
   }")

(def rows
  [[:user "u0" :reader :doc "d0" :a] [:user "u0" :reader :doc "d1" :b]
   [:user "u1" :reader :doc "d2" :a] [:user "u0" :writer :doc "d0" :b]
   [:user "u0" :writer :doc "d2" :a] [:user "u1" :writer :doc "d1" :b]
   [:group "g0" :parent :doc "d0" :a] [:group "g0" :parent :doc "d1" :b]
   [:group "g1" :parent :doc "d1" :a] [:group "g1" :parent :doc "d2" :b]
   [:user "u0" :member :group "g0" :a] [:user "u0" :member :group "g1" :b]
   [:user "u1" :member :group "g1" :a]])

(defn fixture
  ([] (fixture true))
  ([qualified?]
   (let [conn (schema/create-conn {})]
     (schema/write-schema! conn source)
     (ds/transact! conn (mapv #(hash-map :eacl/id %) ["u0" "u1" "d0" "d1" "d2" "g0" "g1"]))
     (let [db (ds/db conn) writer (qualifiers/writer conn)
           ids (into {} (map (fn [name] [name (ds/entid db [:eacl/id name])])
                            ["u0" "u1" "d0" "d1" "d2" "g0" "g1"]))
           identities (mapv (fn [[stype subject relation rtype resource role]]
                              [stype (ids subject)
                               (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type [rtype relation stype]])
                               rtype (ids resource) role]) rows)]
       (doseq [row identities]
         (staged/write! writer :create (subvec row 0 5) (when qualified? {:valid-until-ms 1000})))
       (let [db (ds/db conn)]
         {:db db :ids ids :adapter (backend/basis-adapter db {})
          :qid-roles (into {} (keep (fn [row]
                                     (when-let [qid (edge/qualifier-id (apply impl/direct-edge db (subvec row 0 5)))]
                                       [qid (nth row 5)]))) identities)})))))

(defn path-roles [permission subject resource]
  (let [direct-names (if (#{:direct :delegated} permission) #{:reader :writer} #{:reader})
        direct (for [[_ s r _ o role] rows :when (and (= subject s) (= resource o) (direct-names r))] [role])]
    (into (vec direct)
          (when (#{:via :inherited} permission)
            (for [[_ group r _ object via] rows :when (and (= :parent r) (= resource object))
                  [_ s r _ target holding] rows :when (and (= :member r) (= subject s) (= group target))]
              [via holding])))))

(defn membership [permission subject resource a b]
  (reduce #(evidence/combine :union %1 %2) false
          (map (fn [roles] (reduce #(evidence/combine :arrow %1 ({:a a :b b} %2)) true roles))
               (path-roles permission subject resource))))

(defn options [{:keys [db adapter ids]} permission traversal direction width]
  (merge {:adapter adapter :plan (sealed/seal-plan adapter [:doc permission])
          :subject-type :user :physical-chunk-size width :page-size width :raw-candidates? true
          :qualification (fixtures/qualified-request db 99 {})}
         (if (= traversal :forward) {:subject-eid (ids "u0")} {:resource-eid (ids "d1")})
         (when (= direction :desc) {:last? true})))

(defn drain [options traversal direction]
  (loop [boundary nil pages 0 result []]
    (when (> pages 32) (throw (ex-info "Legacy qualified page failed to progress" {})))
    (let [page ((if (= traversal :forward) least-path/forward-page least-path/reverse-page)
                (cond-> options boundary (assoc (if (= direction :asc) :after-coords :before-coords) boundary)))
          rows (:emissions page) result (into result rows)]
      (if (:exhausted? page) result
          (recur (:coords (peek rows)) (inc pages) result)))))

(defn evaluate [env permission traversal direction width a b]
  (with-redefs [qualification/qualify
                (fn [_ _ value]
                  (if-let [qid (edge/qualifier-id value)]
                    ({:a a :b b} (get (:qid-roles env) qid)) (some? value)))]
    (drain (options env permission traversal direction width) traversal direction)))

(deftest qualified-unions-keep-native-order-and-complete-node-evidence
  (let [env (fixture)]
    (doseq [permission [:direct :delegated :via :inherited]
            traversal [:forward :reverse] direction [:asc :desc] width [1 2]
            a [false true values/x values/y] b [false true values/x values/y]]
      (let [actual (evaluate env permission traversal direction width a b)
            names (if (= traversal :forward) ["d0" "d1" "d2"] ["u0" "u1"])
            expected (into {} (keep (fn [name]
                                     (let [value (if (= traversal :forward)
                                                   (membership permission "u0" name a b)
                                                   (membership permission name "d1" a b))]
                                       (when-not (evidence/no? value)
                                         [(get-in env [:ids name]) (evidence/value value)])))) names)]
        (is (= expected (into {} (map (juxt :value (comp evidence/value :evidence))) actual)))
        (is (= (count expected) (count actual)))
        (is (every? (if (= direction :asc) neg? pos?)
                    (map #(least-path/compare-coords (:coords %1) (:coords %2)) actual (rest actual))))))))

(deftest nil-qualifier-unions-retain-existing-coordinates-without-context-work
  (let [env (fixture false)]
    (doseq [permission [:direct :delegated :via :inherited]
            traversal [:forward :reverse] direction [:asc :desc] width [1 2]]
      (let [options (options env permission traversal direction width)
            ordinary (drain (dissoc options :qualification) traversal direction)
            qualified (with-redefs [qualification/exact-reuse-identity
                                    (fn [_] (throw (ex-info "Ordinary paths need no qualified scope" {})))]
                        (drain options traversal direction))]
        (is (= ordinary (mapv #(dissoc % :evidence) qualified)))
        (is (every? #(evidence/has? (:evidence %)) qualified))))))

(deftest union-boundaries-are-independent-of-walk-direction
  (let [env (fixture)]
    (doseq [permission [:direct :delegated :via :inherited] traversal [:forward :reverse] width [1 2]
            a [false values/x] b [true values/y]]
      (let [ascending (evaluate env permission traversal :asc width a b)
            descending (evaluate env permission traversal :desc width a b)]
        (is (= (mapv #(select-keys % [:value :coords]) (reverse ascending))
               (mapv #(select-keys % [:value :coords]) descending)))))))

(deftest legacy-node-evidence-is-bounded-and-demanded-faults-survive
  (let [env (fixture)]
    (with-redefs [least-path/maximum-qualified-node-evidence 1]
      (is (= :node-evidence-limit
             (try (evaluate env :direct :forward :asc 3 values/x values/y) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                    (:reason (ex-data error)))))))
    (is (= :eacl.authorization/evaluation-failure
           (try (evaluate env :direct :forward :asc 1 (evidence/fault :test/failure :invalid) true) nil
                (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
                  (:type (ex-data error))))))))

(deftest expired-union-paths-do-not-claim-an-earlier-coordinate
  (let [env (fixture)]
    (doseq [permission [:direct :delegated :via :inherited]
            traversal [:forward :reverse] direction [:asc :desc] time [999 1000]]
      (let [options (assoc (options env permission traversal direction 1)
                           :qualification (fixtures/qualified-request (:db env) time {}))
            actual (drain options traversal direction)]
        (is (= (if (= time 1000) 0 (if (= traversal :forward) 3 2))
               (count actual)))))))
