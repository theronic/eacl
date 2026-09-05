(ns eacl.formal.qualified.recursive-bridge
  "Independent finite graph refinement of the production component worklist.
   Native compact edges identify injected leaf evidence; the qualification
   bridge separately certifies decoding and evaluation of those leaves."
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as ds]
            [eacl.authorization.evidence :as evidence]
            [eacl.authorization.qualification :as qualification]
            [eacl.authorization.qualification-test :as q-fixtures]
            [eacl.datascript.backend :as backend]
            [eacl.datascript.impl :as impl]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.datascript.schema :as schema]
            [eacl.formal.qualified.evidence-bridge :as bridge]
            [eacl.formal.qualified.model :as model]
            [eacl.formal.qualified.model-test :as contract]
            [eacl.operator.plan :as plan]
            [eacl.operator.recursive :as recursive]
            [eacl.operator.recursive-test :as fixtures]
            [eacl.relationships.edge :as edge]
            [eacl.relationships.staged :as staged]))

(defn fixture []
  (let [conn (schema/create-conn {})]
    (schema/write-schema! conn fixtures/recursive-schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id (str "model/" %)) (range 4)))
    (let [writer (qualifiers/writer conn)
          ids (mapv #(ds/entid (ds/db conn) [:eacl/id (str "model/" %)]) (range 4))
          user (first ids) folders (subvec ids 1)
          relation (fn [name type]
                     (ds/entid (ds/db conn) [:eacl.relation/resource-type+relation-name+subject-type [:folder name type]]))
          direct (relation :direct :user) parent (relation :parent :folder) eligible (relation :eligible :user)]
      (doseq [folder folders]
        (staged/write! writer :create [:user user direct :folder folder] {:valid-until-ms 1000})
        (staged/write! writer :create [:user user eligible :folder folder] nil))
      (doseq [from folders to folders]
        (staged/write! writer :create [:folder from parent :folder to] {:valid-until-ms 1000}))
      (let [db (ds/db conn) adapter (backend/basis-adapter db {})
            qids (into {} (concat
                           (map-indexed (fn [i folder]
                                          [(edge/qualifier-id (impl/direct-edge db :user user direct :folder folder)) [:base i]]) folders)
                           (for [[i from] (map-indexed vector folders) [j to] (map-indexed vector folders)]
                             [(edge/qualifier-id (impl/direct-edge db :folder from parent :folder to)) [:via j i]])))]
        {:adapter adapter :plan (plan/seal-plan adapter [:folder :view]) :qids qids
         :candidates (mapv #(hash-map :direction :forward :subject-type :user :subject-eid user :resource-eid %) folders)}))))

(defn via-value [graph time target source]
  (if (bit-test graph (+ (* target 3) source))
    (let [end (nth [nil 100 110] (mod (+ target source) 3))]
      (if (model/before? time end) (evidence/with-certificate true end true) false))
    false))

(defn semantics [graph scenario time]
  (let [conditional (bridge/production-for-worlds #{1 3})
        base (if (= scenario :grant) conditional
                 (evidence/combine :exclusion conditional
                                   (if (< time 100) (evidence/with-certificate true 100 true) false)))]
    {:base {0 base 1 false 2 false}
     :via (into {} (for [target (range 3) source (range 3)]
                     [[target source] (via-value graph time target source)]))}))

(defn oracle [semantic]
  (:values
   (model/fixed-point contract/universe
                      (update-vals (:base semantic) bridge/model-evidence)
                      (into {} (for [target (range 3)]
                                 [target (mapv (fn [source]
                                                 [(bridge/model-evidence (get-in semantic [:via [target source]])) source])
                                               (range 3))])) 64)))

(deftest qualified-positive-scc-refinement-and-temporal-stability
  (let [{:keys [qids] :as options} (fixture)]
    (doseq [graph (range 512) scenario [:grant :expiring-ban]]
      (let [semantic (semantics graph scenario 99)
            expected (oracle semantic)
            request (q-fixtures/request)
            result (with-redefs [qualification/qualify
                                (fn [_ _ compact]
                                  (if-let [[kind a b] (get qids (edge/qualifier-id compact))]
                                    (if (= kind :base) (get-in semantic [:base a])
                                        (get-in semantic [:via [a b]]))
                                    (some? compact)))]
                     (recursive/evaluate-many (assoc options :qualification request :checkpoint? false)))
            actual (:decisions result)]
        (doseq [node (range 3)]
          (is (= (:value (get expected node)) (bridge/model-value (nth actual node)))))
        (doseq [time [100 109 110]
                :let [later (oracle (semantics graph scenario time))]
                node (range 3)]
          (is (or (not (evidence/reusable? (nth actual node) 99 time))
                  (= (bridge/model-value (nth actual node)) (:value (get later node))))))))))
