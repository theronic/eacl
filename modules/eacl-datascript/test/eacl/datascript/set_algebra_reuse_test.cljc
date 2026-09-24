(ns eacl.datascript.set-algebra-reuse-test
  "Later requests on one client reuse the operand and operator decisions an
  earlier request cached, while the certificates of those decisions admit the
  later request's time, and so does a client restored from an exported cache
  snapshot. Every reused answer equals the cache-free answer at the same
  time."
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [datascript.core :as ds]
            [eacl.authorization.qualification-test :as qualification-fixtures]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.datascript.qualifiers :as qualifiers]
            [eacl.engine.stable-route :as stable-route]
            [eacl.relationships.staged :as staged]))

(def schema
  "definition user {}
   definition folder {
     relation parent: folder
     relation reader: user
     relation deleter: user
     relation eligible: user
     permission readable = reader + parent->readable
     permission granted = deleter + parent->granted
     permission removable = granted & readable
     permission removable_top = deleter + (granted & readable)
     permission inherited = reader + (parent->inherited & eligible)
   }")

(def ^:private folder-ids ["f0" "f1" "f2" "f3" "f4"])

(defn- fixture
  "f0 -> f1 -> f2 -> f3 -> f4, with alice's deleter grant on f0 expiring at 200,
  a plain reader grant on f1, and eligible grants on f2 (plain) and f3
  (expiring at 300)."
  []
  (let [conn (datascript/create-conn)
        now (atom 100)
        options {:clock #(deref now)}
        client (datascript/make-client conn options)
        alice (eacl/spice-object :user "alice")
        folders (mapv #(eacl/spice-object :folder %) folder-ids)
        expiring (fn [relationship end] (assoc relationship :valid-until-ms end))]
    (eacl/write-schema! client schema)
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (cons "alice" folder-ids)))
    (eacl/create-relationships!
     client
     (concat
      (for [[parent child] (partition 2 1 folders)]
        (eacl/->Relationship parent :parent child))
      [(expiring (eacl/->Relationship alice :deleter (folders 0)) 200)
       (eacl/->Relationship alice :reader (folders 1))
       (eacl/->Relationship alice :eligible (folders 2))
       (expiring (eacl/->Relationship alice :eligible (folders 3)) 300)]))
    {:conn conn :now now :options options :client client :alice alice :folders folders}))

(defn- walk [client alice permission]
  (set (map :id (:data (eacl/lookup-resources
                        client {:subject alice :permission permission
                                :resource/type :folder :first 100})))))

(defn- check [client alice permission folder & [cache?]]
  (:permissionship (eacl/check-permission
                    client (cond-> {:subject alice :permission permission :resource folder}
                             (false? cache?) (assoc :cache? false)))))

(defn- denotation-hits [client]
  (get-in (datascript/cache-stats client) [:subproblems :denotation-hits] 0))

(defn- observed
  "Runs `f`, returning its value, the membership searches and reuses of the
  union and guarded engines, and the client's denotation hits."
  [client f]
  (let [membership (atom {})
        hits (denotation-hits client)
        value (binding [stable-route/*membership-stats* membership] (f))]
    {:value value
     :searched (:searched @membership 0)
     :reused (:reused @membership 0)
     :hits (- (denotation-hits client) hits)}))

(deftest later-requests-reuse-certified-set-algebra-results-test
  (let [{:keys [client now alice folders]} (fixture)]
    (is (= #{"f1" "f2" "f3" "f4"} (walk client alice :removable)))
    (reset! now 150)
    (testing "an operator check reuses the walk's root decision"
      (doseq [folder folders]
        (let [{:keys [value searched hits]}
              (observed client #(check client alice :removable folder))]
          (is (= (check client alice :removable folder false) value) (:id folder))
          (is (zero? searched) (:id folder))
          (is (pos? hits) (:id folder)))))
    (testing "a check of a union-only operand reuses the walk's operand decision"
      (doseq [permission [:granted :readable] folder folders]
        (let [{:keys [value searched reused]}
              (observed client #(check client alice permission folder))]
          (is (= (check client alice permission folder false) value))
          (is (zero? searched))
          (is (= 1 reused)))))
    (testing "a sibling operator permission reuses the shared operands"
      (let [{:keys [value searched reused]}
            (observed client #(walk client alice :removable_top))]
        (is (= #{"f0" "f1" "f2" "f3" "f4"} value))
        (is (zero? searched))
        (is (pos? reused))))
    (testing "no decision outlives its certificate"
      (reset! now 250)
      (is (= #{} (walk client alice :removable)))
      (doseq [permission [:removable :removable_top :granted :readable] folder folders]
        (is (= (check client alice permission folder false)
               (check client alice permission folder))
            (str permission " " (:id folder)))))))

(deftest conditional-decisions-are-reused-only-under-their-own-context-test
  ;; alice deletes from f0 down; her reader grant on f1 is caveated and
  ;; expires at 200, so `removable` below f1 is conditional without a flag.
  (let [conn (datascript/create-conn)
        now (atom 100)
        client (datascript/make-client
                conn {:clock #(deref now)
                      :caveat-evaluator (qualification-fixtures/portable-evaluator (atom 0))})
        alice (eacl/spice-object :user "alice")
        folders (mapv #(eacl/spice-object :folder %) folder-ids)
        f2 (folders 2)
        check (fn [context & [cache?]]
                (:permissionship
                 (eacl/check-permission
                  client (cond-> {:subject alice :permission :removable :resource f2
                                  :caveat-context context}
                           (false? cache?) (assoc :cache? false)))))
        walk (fn [context]
               (eacl/lookup-resources client {:subject alice :permission :removable
                                              :resource/type :folder :first 100
                                              :caveat-context context
                                              :result-policy :detailed}))]
    (eacl/write-schema! client (str "caveat enabled(flag bool) { flag }\n" schema))
    (ds/transact! conn (mapv #(hash-map :eacl/id %) (cons "alice" folder-ids)))
    (eacl/create-relationships!
     client
     (cons (eacl/->Relationship alice :deleter (folders 0))
           (for [[parent child] (partition 2 1 folders)]
             (eacl/->Relationship parent :parent child))))
    (let [db (ds/db conn)
          reader (ds/entid db [:eacl.relation/resource-type+relation-name+subject-type
                               [:folder :reader :user]])
          caveat (ds/entid db [:eacl.caveat/name "enabled"])]
      (ds/transact! conn [{:db/id reader :eacl.relation/caveats [caveat]
                          :eacl.relation/allows-unqualified? true}])
      (staged/write! (qualifiers/writer conn) :create
                     [:user (ds/entid db [:eacl/id "alice"]) reader :folder (ds/entid db [:eacl/id "f1"])]
                     {:caveat caveat :valid-until-ms 200}))
    (walk {})
    (reset! now 150)
    (testing "the walk's conditional decision is reused under the same context"
      (let [{:keys [value searched hits]} (observed client #(check {}))]
        (is (= :conditional-permission value (check {} false)))
        (is (zero? searched))
        (is (pos? hits))))
    (testing "another context decides again"
      (doseq [[context expected] [[{"flag" false} :no-permission] [{"flag" true} :has-permission]]]
        (let [{:keys [value searched]} (observed client #(check context))]
          (is (= expected value (check context false)))
          (is (pos? searched)))))
    (testing "an expired decision is decided again and replaced"
      (reset! now 250)
      (walk {})
      (reset! now 260)
      (let [{:keys [value searched hits]} (observed client #(check {}))]
        (is (= :no-permission value (check {} false)))
        (is (zero? searched))
        (is (pos? hits))))))

(deftest guarded-members-are-reused-across-requests-test
  ;; The walk's candidates are alice's reader and eligible holdings, f1 to
  ;; f3; only their decisions exist to reuse.
  (let [{:keys [client now alice folders]} (fixture)]
    (is (= #{"f1" "f2" "f3"} (walk client alice :inherited)))
    (reset! now 150)
    (doseq [folder folders]
      (let [{:keys [value searched]}
            (observed client #(check client alice :inherited folder))]
        (is (= (check client alice :inherited folder false) value) (:id folder))
        (when (#{"f1" "f2" "f3"} (:id folder))
          (is (zero? searched) (:id folder)))))
    (reset! now 300)
    (is (= :no-permission (check client alice :inherited (folders 3)))
        "the expired eligible grant is not reused")))

(defn- point-entries
  "The snapshot's operator and membership decision entries, by kind."
  [snapshot]
  (group-by #(first (get-in % [:key 2 4]))
            (filter #(= :denotation (:tier %)) (:entries snapshot))))

(defn- error-type [f]
  (try (f) nil
       (catch #?(:clj Throwable :cljs :default) error (:type (ex-data error)))))

(deftest restored-snapshots-carry-certified-decisions-test
  (let [{:keys [conn now options client alice folders]} (fixture)
        bounds {:max-entries 4096}
        _ (walk client alice :removable)
        snapshot (datascript/export-cache-snapshot client bounds)
        entries (point-entries snapshot)
        membership (first (get entries :membership-point))
        index (fn [entry] (first (keep-indexed #(when (= entry %2) %1) (:entries snapshot))))]
    (testing "the snapshot carries operand and operator decisions with their certificates"
      (is (seq (get entries :membership-point)))
      (is (seq (get entries :operator-recursive-point)))
      (is (every? #(= :eacl.authorization/temporal-point-v1 (get-in % [:value :format]))
                  (mapcat val entries))))
    (testing "a restored client reuses them at a later time"
      (let [restored (datascript/make-client conn options)]
        (is (:restored? (datascript/restore-cache-snapshot! restored snapshot bounds)))
        (reset! now 150)
        (doseq [folder folders]
          (let [{:keys [value searched hits]}
                (observed restored #(check restored alice :removable folder))]
            (is (= (check restored alice :removable folder false) value) (:id folder))
            (is (zero? searched) (:id folder))
            (is (pos? hits) (:id folder))))
        (let [{:keys [searched reused]}
              (observed restored #(check restored alice :readable (folders 2)))]
          (is (zero? searched))
          (is (= 1 reused)))))
    (testing "a decision whose value disagrees with its key is refused"
      (doseq [[label tampered]
              [[:boolean-under-a-certified-key
                (assoc-in snapshot [:entries (index membership) :value] true)]
               [:interval-disagrees-with-evidence
                (update-in snapshot [:entries (index membership) :value :valid-until-ms]
                           (fnil inc 1000))]
               [:certified-value-under-a-plain-key
                (update-in snapshot [:entries (index membership) :key 2 4] pop)]]]
        (let [target (datascript/make-client conn options)]
          (is (= :eacl/incompatible-cache-snapshot
                 (error-type #(datascript/restore-cache-snapshot! target tampered bounds)))
              (name label)))))))
