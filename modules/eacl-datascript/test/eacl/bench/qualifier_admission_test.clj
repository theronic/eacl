(ns eacl.bench.qualifier-admission-test
  (:require [clojure.test :refer [deftest is]]
            [datascript.core :as d]
            [eacl.core :as eacl]
            [eacl.datascript.core :as api]
            [eacl.relationships.endpoint-pair :as pair]
            [eacl.relationships.storage :as storage]))

(defn run-admission! [relationship-count output]
  (let [seed (api/create-conn)
        _ (eacl/write-schema! (api/make-client seed {})
                             "definition user {}
definition document {
 relation viewer: user
 permission view = viewer
}")
        _ (d/transact! seed [{:eacl/id "alice"}])
        base @seed
        relation (d/entid base [:eacl.relation/resource-type+relation-name+subject-type [:document :viewer :user]])
        alice (d/entid base [:eacl/id "alice"])
        tx (inc (:max-tx base))
        started (System/nanoTime)
        ;; Trusted fixture construction avoids measuring transaction planning.
        database (d/init-db
                  (concat (d/datoms base :eavt)
                          [(d/datom relation :eacl/relation-version tx tx)]
                          (mapcat (fn [n]
                                    (let [eid (+ 1000 n)]
                                      [(d/datom eid :eacl/id (str "document-" n) tx)
                                       (d/datom alice storage/forward-attribute
                                                (pair/forward-value :user relation :document eid) tx)
                                       (d/datom eid storage/reverse-attribute
                                                (pair/reverse-value :document relation :user alice) tx)]))
                                  (range relationship-count)))
                  (:schema base))
        build-ns (- (System/nanoTime) started)
        conn (d/conn-from-db database)
        calls (atom [])
        native-datoms d/datoms
        native-seek d/seek-datoms
        started (System/nanoTime)
        client (with-redefs [d/datoms (fn [db index & components]
                                       (swap! calls conj [index components])
                                       (apply native-datoms db index components))
                             d/seek-datoms (fn [db index & components]
                                             (swap! calls conj [index components])
                                             (apply native-seek db index components))]
                 (api/make-client conn {}))
        admission-ns (- (System/nanoTime) started)
        target-reads (filterv (fn [[_ components]] (some storage/attributes components)) @calls)
        result {:relationship-count relationship-count
                :logical-relationship-datoms (* 2 relationship-count)
                :fixture-build-ns build-ns :admission-ns admission-ns
                :native-index-calls @calls :target-relationship-index-calls target-reads}]
    (assert (empty? target-reads))
    (assert (eacl/can? client (eacl/spice-object :user "alice") :view
                       (eacl/spice-object :document (str "document-" (dec relationship-count)))))
    (spit output (pr-str result))
    result))

(deftest ^:benchmark million-relationship-startup-is-bounded-test
  (is (empty? (:target-relationship-index-calls
               (run-admission! 1000000 "target/qualifier-reference/million-admission.edn")))))
