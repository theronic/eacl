(ns eacl.datomic.adversarial-v8-review-test
  "Native regressions for qualified control protection and complete Relation stamps."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.walk :as walk]
            [datomic.api :as d]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.core :as eacl]
            [eacl.datomic.core :as core]
            [eacl.datomic.datomic-helpers :refer [with-mem-conn]]
            [eacl.datomic.safe-retraction :as safe-datomic]
            [eacl.datomic.schema :as schema]
            [eacl.relationships.safe-retraction :as safe]
            [eacl.relationships.storage :as storage]))

(def qualified-schema
  "caveat enabled(flag bool) { flag }
   caveat standby(flag bool) { flag }
   definition user {}
   definition doc {
     relation viewer: user | user with enabled | user with standby
     permission view = viewer
   }")

(def loop-schema
  "definition node {
     relation loop: node
     relation unrelated: node
     permission view = loop
   }")

(defn with-review-client [schema-source f]
  (with-mem-conn [conn schema/v8-schema]
    (let [client (core/make-client
                  conn
                  {:clock (constantly 99)
                   :caveat-evaluator
                   (qualification-fixtures/portable-evaluator (atom 0))})]
      ;; Use the public writer: unlike the low-level schema facade it selects
      ;; the qualified relation-allowance feature through orchestration.
      (eacl/write-schema! client {:schema schema-source})
      (safe-datomic/install! conn)
      (f conn client))))

(defn throwable-reasons [error]
  (loop [cause error result #{}]
    (if (nil? cause)
      result
      (recur (.getCause ^Throwable cause)
             (cond-> result
               (:reason (ex-data cause))
               (conj (:reason (ex-data cause))))))))

(defn retract-outcome! [conn target]
  (try
    {:report @(d/transact conn (safe-datomic/retract-entity-tx-data target))}
    (catch Exception error
      {:error error :reasons (throwable-reasons error)})))

(defn seed-qualified! [conn client]
  @(d/transact conn [{:eacl/id "review/u"} {:eacl/id "review/d"}])
  (let [subject (eacl/spice-object :user "review/u")
        resource (eacl/spice-object :doc "review/d")
        relationship (assoc (eacl/->Relationship subject :viewer resource)
                            :caveat "enabled"
                            :valid-until-ms 100)]
    ;; No bound Caveat context: its absence is essential to the minimal
    ;; false -> true witness when the ordinary Caveat reference disappears.
    (eacl/create-relationship! client relationship)
    (let [db (d/db conn)
          subject-eid (d/entid db [:eacl/id "review/u"])
          qid (nth (:v (first (d/datoms db :eavt subject-eid
                                        storage/forward-attribute))) 4)]
      {:caveat-eid (d/entid db [:eacl.caveat/name "enabled"])
       :qualifier-eid qid
       :request {:subject subject :resource resource :permission :view
                 :caveat-context {"flag" false} :cache? false}})))

(deftest safe-retraction-must-not-weaken-a-caveat-into-expiry-only
  (with-review-client
    qualified-schema
    (fn [conn client]
      (let [{:keys [caveat-eid qualifier-eid request]} (seed-qualified! conn client)
            before (d/db conn)
            before-result (eacl/check-permission client request)]
        (is (some? caveat-eid))
        (is (some? qualifier-eid))
        (is (= :no-permission (:permissionship before-result)))
        (let [outcome (retract-outcome! conn caveat-eid)
              after (d/db conn)
              after-result (eacl/check-permission client request)]
          (is (:error outcome) "Caveat definitions are not object-deletion targets")
          (is (contains? (:reasons outcome) :protected-control-entity))
          (is (= (d/basis-t before) (d/basis-t after))
              "a rejected deletion must not commit any ref cleanup")
          (is (= caveat-eid (d/entid after [:eacl.caveat/name "enabled"])))
          (is (= :no-permission (:permissionship after-result))
              (str "Native result after attempted deletion: " (pr-str after-result))))))))

(deftest safe-retraction-must-reject-qualifier-entity-targets
  (with-review-client
    qualified-schema
    (fn [conn client]
      (let [{:keys [qualifier-eid]} (seed-qualified! conn client)
            before (d/db conn)
            outcome (retract-outcome! conn qualifier-eid)]
        (is (:error outcome))
        (is (contains? (:reasons outcome) :protected-control-entity))
        (is (= (d/basis-t before) (d/basis-t (d/db conn))))
        (is (seq (d/datoms (d/db conn) :eavt qualifier-eid)))))))

(deftest component-closure-must-not-delete-a-caveat-definition
  (with-review-client
    qualified-schema
    (fn [conn client]
      (let [{:keys [caveat-eid request]} (seed-qualified! conn client)]
        @(d/transact conn [{:db/ident :review/component
                            :db/valueType :db.type/ref
                            :db/cardinality :db.cardinality/one
                            :db/isComponent true}])
        @(d/transact conn [{:eacl/id "review/parent"
                            :review/component caveat-eid}])
        (let [before (d/db conn)
              outcome (retract-outcome! conn [:eacl/id "review/parent"])]
          (is (:error outcome))
          (is (contains? (:reasons outcome) :protected-control-entity))
          (is (= (d/basis-t before) (d/basis-t (d/db conn))))
          (is (some? (d/entid (d/db conn) [:eacl/id "review/parent"])))
          (is (= :no-permission
                 (:permissionship (eacl/check-permission client request)))))))))

(defn relation-id [db name]
  (d/entid db [:eacl.relation/resource-type+relation-name+subject-type
               [:node name :node]]))

(defn generation [db relation]
  (:v (first (d/datoms db :eavt relation :eacl/relation-version))))

(deftest deleting-an-isolated-self-loop-must-advance-its-relation-generation
  (with-review-client
    loop-schema
    (fn [conn client]
      @(d/transact conn [{:eacl/id "review/loop"}
                         {:eacl/id "review/other-a"}
                         {:eacl/id "review/other-b"}])
      (let [node (eacl/spice-object :node "review/loop")
            other-a (eacl/spice-object :node "review/other-a")
            other-b (eacl/spice-object :node "review/other-b")]
        (eacl/create-relationship! client (eacl/->Relationship node :loop node))
        (eacl/create-relationship! client (eacl/->Relationship other-a :unrelated other-b))
        (let [before (d/db conn)
              eid (d/entid before [:eacl/id "review/loop"])
              loop-id (relation-id before :loop)
              unrelated-id (relation-id before :unrelated)
              expansion (d/invoke before safe/function-ident before eid)
              stamped (into #{}
                            (keep (fn [op]
                                    (when (and (= :db/add (first op))
                                               (= :eacl/relation-version (nth op 2 nil)))
                                      (second op))))
                            expansion)]
          (is (= #{loop-id} stamped)
              "zero peer retractions does not mean zero affected relations")
          @(d/transact conn (safe-datomic/retract-entity-tx-data eid))
          (let [after (d/db conn)]
            (is (empty? (d/datoms after :eavt eid)))
            (is (not= (generation before loop-id) (generation after loop-id)))
            (is (= (generation before unrelated-id)
                   (generation after unrelated-id)))))))))

(defn stamp-set [expansion]
  (into #{} (keep (fn [[op eid attribute]]
                    (when (and (= :db/add op) (= :eacl/relation-version attribute)) eid))) expansion))

(defn affected-relations
  "Independent projection of both physical indexes; self edges stay in scope."
  [db closure]
  (into #{}
        (for [attribute storage/attributes
              datom (d/datoms db :aevt attribute)
              :let [[_ relation _ peer] (:v datom)]
              :when (or (closure (:e datom)) (closure peer))]
          relation)))

(deftest native-stamps-refine-incident-edges-in-small-graphs
  (with-review-client
    loop-schema
    (fn [conn _client]
      @(d/transact conn [{:eacl/id "graph/a"} {:eacl/id "graph/b"}
                         {:eacl/id "graph/parent"}
                         {:db/ident :review/children :db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/many :db/isComponent true}])
      (let [base (d/db conn)
            a (d/entid base [:eacl/id "graph/a"])
            b (d/entid base [:eacl/id "graph/b"])
            parent (d/entid base [:eacl/id "graph/parent"])
            r (relation-id base :loop)
            u (relation-id base :unrelated)
            edges [[a r a] [b u b] [a u b] [b r a]]]
        (doseq [mask (range 16)
                direction [:forward :reverse :both]
                closure [#{a} #{a b}]]
          (let [edge-ops (for [index (range 4)
                               :when (bit-test mask index)
                               half [:forward :reverse]
                               :let [[s rel o] (nth edges index)]
                               :when (or (not= s o) (= :both direction) (= half direction))]
                           (if (= half :forward)
                             [:db/add s storage/forward-attribute [:node rel :node o nil]]
                             [:db/add o storage/reverse-attribute [:node rel :node s nil]]))
                ops (into (mapv #(vector :db/add parent :review/children %) closure) edge-ops)
                db (:db-after (d/with base ops))
                expected (affected-relations db (conj closure parent))
                expansion (d/invoke db safe/function-ident db parent)]
            (is (= expected (stamp-set expansion)))
            (is (= (count expected)
                   (count (filter #(= :eacl/relation-version (nth % 2 nil)) expansion))))))))))

(deftest component-self-loop-and-mixed-closure-advance-exactly
  (with-review-client
    loop-schema
    (fn [conn client]
      @(d/transact conn [{:db/ident :review/child
                          :db/valueType :db.type/ref
                          :db/cardinality :db.cardinality/one
                          :db/isComponent true}])
      @(d/transact conn [{:db/id -1 :eacl/id "child"}
                         {:eacl/id "parent" :review/child -1}
                         {:eacl/id "peer"}])
      (let [child (eacl/spice-object :node "child")
            parent (eacl/spice-object :node "parent")
            peer (eacl/spice-object :node "peer")]
        (eacl/create-relationship! client (eacl/->Relationship child :loop child))
        (eacl/create-relationship! client (eacl/->Relationship parent :unrelated peer))
        (let [before (d/db conn)
              p (d/entid before [:eacl/id "parent"])
              c (d/entid before [:eacl/id "child"])
              expected (affected-relations before #{p c})]
          (is (= expected (stamp-set (d/invoke before safe/function-ident before p))))
          @(d/transact conn [[safe/function-ident p] [safe/function-ident p]])
          (let [after (d/db conn)]
            (is (empty? (d/datoms after :eavt p)))
            (is (empty? (d/datoms after :eavt c)))
            (doseq [r expected] (is (< (generation before r) (generation after r))))
            (is (empty? (affected-relations after #{p c})))
            (is (empty? (stamp-set (d/invoke after safe/function-ident after p))))))))))

(defn mutated-definition [mutation]
  (let [definition safe-datomic/function-definition
        body (:db/fn definition)
        original (read-string (:code body))
        code (case mutation
               :control (walk/postwalk-replace {safe/qualified-control-attributes #{}} original)
               :self-loop (walk/postwalk-replace
                           {'(or local-halves repair-halves)
                            '(remove (fn [half] (nil? (:op half))) (or local-halves repair-halves))}
                           original))]
    (is (not= code original) "mutation must reach the production body")
    (assoc definition :db/fn (d/function {:lang (:lang body) :params (:params body) :code code}))))

(deftest native-witness-kills-missing-control-protection
  (with-review-client
    qualified-schema
    (fn [conn client]
      (let [{:keys [caveat-eid qualifier-eid request]} (seed-qualified! conn client)
            before (d/db conn)]
        (is (= :no-permission (:permissionship (eacl/check-permission client request))))
        @(d/transact conn [(mutated-definition :control)])
        @(d/transact conn (safe-datomic/retract-entity-tx-data caveat-eid))
        (is (some? (:eacl.relationship-qualifier/caveat (d/entity before qualifier-eid))))
        (is (nil? (:eacl.relationship-qualifier/caveat (d/entity (d/db conn) qualifier-eid))))
        (is (= :has-permission (:permissionship (eacl/check-permission client request))))))))

(deftest native-oracle-kills-premature-nil-op-filter
  (with-review-client
    loop-schema
    (fn [conn client]
      @(d/transact conn [{:eacl/id "self"}])
      (let [object (eacl/spice-object :node "self")]
        (eacl/create-relationship! client (eacl/->Relationship object :loop object))
        @(d/transact conn [(mutated-definition :self-loop)])
        (let [db (d/db conn) eid (d/entid db [:eacl/id "self"])]
          (is (not= (affected-relations db #{eid})
                    (stamp-set (d/invoke db safe/function-ident db eid)))))))))

(deftest recognized-v4-body-is-replaced-before-native-invocation
  (with-review-client
    qualified-schema
    (fn [conn client]
      (let [{:keys [caveat-eid request]} (seed-qualified! conn client)
            old (assoc (mutated-definition :control)
                       :db/doc (str safe/function-doc-prefix " v4; previous qualified guard"))]
        @(d/transact conn [old])
        (is (some #(= :db.fn/retractEntity (first %))
                  (d/invoke (d/db conn) safe/function-ident (d/db conn) caveat-eid)))
        (is (= :upgradeable (:state (safe-datomic/install! conn))))
        (is (= :current (:state (safe-datomic/install! conn))))
        (is (contains? (:reasons (retract-outcome! conn caveat-eid)) :protected-control-entity))
        (is (= :no-permission (:permissionship (eacl/check-permission client request))))))))
