(ns eacl.datascript.uuid-lifecycle-test
  (:require [#?(:clj clojure.test :cljs cljs.test) :refer [deftest is testing]]
            [clojure.walk :as walk]
            [datascript.core :as ds]
            [eacl.uuid :as uuid]
            [eacl.causal-token :as token]
            [eacl.core :as eacl]
            [eacl.datascript.core :as datascript]
            [eacl.uuid-lifecycle-test :refer [a b error-data]]))

(deftest default-ownership-and-local-reset-test
  (let [conn (datascript/create-conn)
        c1 (datascript/make-client conn {}) c2 (datascript/make-client conn {})]
    (doseq [client [c1 c2]]
      (let [snapshot (eacl/snapshot client)]
        (try (is (= uuid/initial (:source-lifecycle (eacl/basis snapshot))))
             (finally (eacl/release! snapshot)))))
    (doseq [value [nil false "old"]]
      (let [error (error-data #(datascript/make-client conn {:source-lifecycle value}))]
        (is (= :eacl/invalid-config (:type error)))
        (is (not (contains? error :value)))))
    (let [state (get-in c1 [:runtime :runtime-lifecycle-state]) before @state]
      (datascript/expire-cache! c1 uuid/initial)
      (is (= uuid/initial (:source-lifecycle @state)))
      (is (not (identical? (:source-incarnation before) (:source-incarnation @state)))))
    (datascript/expire-cache! c1 a)
    (let [state (get-in c1 [:runtime :runtime-lifecycle-state]) before @state]
      (is (= :retired-initial-source-lifecycle
             (:reason (error-data #(datascript/expire-cache! c1 uuid/initial)))))
      (is (identical? before @state)))))

(defn- populated-client []
  (let [conn (datascript/create-conn)
        options {:source-lifecycle a :security-key "uuid-restore-fixture-key-00000000"}
        client (datascript/make-client conn options)
        user (eacl/spice-object :user "user")
        doc (eacl/spice-object :document "doc")
        query {:subject user :permission :view :resource doc}]
    (eacl/write-schema! client "definition user {}\ndefinition document {\n relation viewer: user\n permission view = viewer\n}")
    (ds/transact! conn [{:eacl/id "user"} {:eacl/id "doc"}])
    (eacl/create-relationship! client (eacl/->Relationship user :viewer doc))
    (is (true? (eacl/can? client query)))
    {:conn conn :client client :options options :query query}))

(deftest restore-is-bound-to-complete-current-lineage-test
  (let [{:keys [conn client options query]} (populated-client)
        bounds {:max-entries 64}
        snapshot (datascript/export-cache-snapshot client bounds)
        compatible (datascript/make-client conn options)]
    (is (pos? (:entry-count snapshot)))
    (is (:restored? (datascript/restore-cache-snapshot! compatible snapshot bounds)))
    (is (true? (eacl/can? compatible query)))
    (doseq [other [(datascript/make-client conn (assoc options :source-lifecycle b))
                   (datascript/make-client (datascript/create-conn) options)]]
      (let [state (get-in other [:runtime :runtime-lifecycle-state]) before @state]
        (is (= :source-lifecycle-mismatch
               (:reason (error-data #(datascript/restore-cache-snapshot! other snapshot bounds)))))
        (is (identical? before @state))))
    (doseq [legacy [:eacl.cache/basis-snapshot-v1 :eacl.cache/basis-snapshot-v2]]
      (let [state (get-in client [:runtime :runtime-lifecycle-state]) before @state]
        (is (= :eacl/cache-snapshot-upgrade-required
               (:type (error-data #(datascript/restore-cache-snapshot!
                                   client (assoc snapshot :format legacy) bounds)))))
        (is (identical? before @state))))))

#?(:cljs
   (deftest imported-uuid-keys-are-owned-before-scope-validation-test
     (let [{:keys [client query]} (populated-client)
           bounds {:max-entries 64}
           input-values (atom [])
           snapshot (walk/postwalk
                     (fn [value]
                       (if (uuid/value? value)
                         (let [copy (cljs.core/uuid (str value))]
                           (set! (.-__hash copy) 0)
                           (swap! input-values conj copy)
                           copy)
                         value))
                     (datascript/export-cache-snapshot client bounds))]
       (is (seq @input-values))
       (is (:restored? (datascript/restore-cache-snapshot! client snapshot bounds)))
       (doseq [value @input-values] (set! (.-uuid value) (str b)))
       (is (true? (eacl/can? client query)))
       (let [exported-values (atom [])]
         (walk/postwalk #(do (when (uuid/value? %) (swap! exported-values conj %)) %)
                        (datascript/export-cache-snapshot client bounds))
         (is (seq @exported-values))
         (is (every? uuid/owned? @exported-values))
         (is (every? #(= a %) @exported-values))))))

#?(:cljs
   (deftest hostile-wrapper-and-exposed-value-test
     (let [original (cljs.core/uuid (str a))
           _ (set! (.-__hash original) 0)
           owned (token/validate-source-lifecycle! original)
           expected-hash (hash (cljs.core/uuid (str a)))]
       (is (not (identical? original owned)))
       (is (= expected-hash (hash owned)))
       (is (js/Object.isFrozen owned))
       (is (identical? owned (token/validate-source-lifecycle! owned)))
       (set! (.-uuid original) (str b))
       (is (= a owned))
       (try (set! (.-uuid owned) (str b)) (catch :default _ nil))
       (try (set! (.-__hash owned) 0) (catch :default _ nil))
       (is (= a owned))
       (is (= expected-hash (hash owned)))
       (is (= :hit (get {owned :hit} (cljs.core/uuid (str a))))))
     (doseq [impostor [(cljs.core/uuid "invalid") (reify IUUID)]]
       (is (= :eacl/invalid-source-lifecycle
              (:type (error-data #(token/validate-source-lifecycle! impostor))))))
     (let [input (cljs.core/uuid (str a))
           client (datascript/make-client (datascript/create-conn) {:source-lifecycle input})
           snapshot (eacl/snapshot client)]
       (try
         (let [exposed (:source-lifecycle (eacl/basis snapshot))]
           (try (set! (.-uuid exposed) (str b)) (catch :default _ nil))
           (set! (.-uuid input) (str b))
           (is (= a (:source-lifecycle (eacl/basis snapshot))))
           (is (uuid/owned? exposed)))
         (finally (eacl/release! snapshot))))))
