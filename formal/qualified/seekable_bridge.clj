(ns eacl.formal.qualified.seekable-bridge
  "Direct native page generators versus independent completion-set algebra.
   Exhaustive walks also check the aggregate examined certificate; partial
   continuation frontiers are deliberately not certified by this bridge."
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.operator.seekable :as seekable]
            [eacl.operator.seekable-evidence-test :as fixtures]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(defn fixture []
  (let [{:keys [conn user docs] :as env} (fixtures/fixture)
        writer (qualifiers/writer conn)
        relations (into {} (for [name [:reader :writer :banned]]
                             [name (ds/entid (ds/db conn) [:eacl.relation/resource-type+relation-name+subject-type [:doc name :user]])]))]
    (doseq [doc docs relation (vals relations) [s r] [[user doc] [doc user]]]
      (staged/write! writer (if (impl/direct-edge (ds/db conn) :user s relation :doc r) :replace :create)
                     [:user s relation :doc r] {:valid-until-ms 1000}))
    (let [db (ds/db conn)
          qids (into {} (for [[i doc] (map-indexed vector docs) [name relation] relations
                              [s r] [[user doc] [doc user]]]
                          [(edge/qualifier-id (impl/direct-edge db :user s relation :doc r)) [name i]]))]
      (assoc env :db db :adapter (backend/basis-adapter db {}) :qids qids))))

(defn leaves [a b time]
  (into {} (for [[role values end] [[:reader [a b true a] nil]
                                    [:writer [b a a false] 110]
                                    [:banned [b a a false] 100]]
                  [i value] (map-indexed vector values)]
              [[role i] (if (model/before? time end) (evidence/with-certificate value end true) false)])))

(defn oracle [permission semantic i]
  (model/compose (if (= permission :both) :intersection :exclusion)
                 (bridge/model-value (get semantic [:reader i]))
                 (bridge/model-value (get semantic [(if (= permission :both) :writer :banned) i]))))

(defn drain [options]
  (loop [boundary nil rows [] certificate true]
    (let [page (seekable/page (assoc options :boundary boundary))
          emissions (:emissions page)
          rows (into rows emissions)
          certificate (evidence/combine :intersection certificate (:examined-certificate page))]
      (if (:exhausted? page)
        {:rows rows :certificate certificate}
        (recur (:coords (peek emissions)) rows certificate)))))

(defn check-case! [{:keys [docs qids] :as env} permission direction traversal width a b]
  (let [positions (zipmap docs (range))
        semantic (leaves a b 99)
        options (assoc (fixtures/options env permission 99 {} direction) :traversal traversal :width width)
        resolve-edge (fn [_ _ compact]
                       (if compact (get semantic (get qids (edge/qualifier-id compact))) false))
        result (with-redefs-fn {#'qualification/qualify resolve-edge} #(drain options))
        rows (:rows result)
        certificate (:certificate result)
        ordered (if (= direction :asc) docs (rseq docs))
        expected (vec (for [doc ordered
                            :let [value (oracle permission semantic (positions doc))]
                            :when (seq (:worlds value))]
                        [doc value]))]
    (is (= expected (mapv (fn [row] [(:value row) (bridge/model-value (:evidence row))]) rows)))
    (doseq [time [100 109 110]]
      (let [later (leaves a b time)
            unchanged-row? (fn [row]
                             (or (not (evidence/reusable? (:evidence row) 99 time))
                                 (= (bridge/model-value (:evidence row))
                                    (oracle permission later (positions (:value row))))))]
        (is (every? unchanged-row? rows))
        (is (or (not (evidence/reusable? certificate 99 time))
                (= (mapv #(oracle permission semantic %) (range 4))
                   (mapv #(oracle permission later %) (range 4)))))))))

(deftest direct-page-algebra-and-exhaustive-temporal-certificates
  (let [env (fixture)
        inputs (remove evidence/fault? (take-nth 3 (bridge/inputs)))]
    (doseq [permission [:both :allowed] direction [:asc :desc]
            traversal [:forward :reverse] width [1 2] a inputs b inputs]
      (check-case! env permission direction traversal width a b))))
