(ns eacl.schema.expression-persistence-test
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer [deftest is]]
            [eacl.cache.derived-schema :as derived-schema]
            [eacl.schema.expression-persistence :as persistence]
            [eacl.schema.expression-resolver :as resolver]))

(defn- derived-identity
  []
  {:abi {:engine 8 :derived :v1}
   :source {:backend :test
            :source-id :expression-test
            :branch nil
            :source-lifecycle "expression-test/initial"}
   :adapter {:backend :test
             :fingerprint :expression-test-v1
             :identity-contract :immutable-v1
             :operator-capability {:mode :scalar}}
   :schema-generation 1})

(def schema
  "definition user {}
   definition document {
     relation reader: user
     relation writer: user
     relation banned: user
     permission base = reader + writer
     permission view = base - banned
   }")

(defn- error-data [f]
  (try
    (f)
    nil
    (catch #?(:clj Exception :cljs :default) error
      (ex-data error))))

(deftest canonical-expression-entity-round-trip-test
  (let [{:keys [permissions] :as candidate}
        (persistence/candidate-schema (resolver/validate-schema schema))
        decoded (persistence/validate-entities permissions)]
    (is (= 3 (count (:relations candidate))))
    (is (= 2 (count permissions)))
    (is (= [:base :view]
           (mapv (comp :permission-name :expression) decoded)))
    (is (every? #(not-any? (fn [attribute] (contains? % attribute))
                           persistence/retired-derived-metric-attributes)
                permissions))
    (is (every? #(not-any? (fn [attribute] (contains? % attribute))
                           persistence/legacy-flat-attributes)
                permissions))))

(deftest expression-replacement-is-not-an-entity-deletion-test
  (let [old {:eacl/id "eacl.permission-expression::document::view"
             :value :old}
        replacement (assoc old :value :new)
        removed {:eacl/id "eacl.permission-expression::document::edit"}
        deltas {:additions #{replacement}
                :retractions #{old removed}}]
    (is (= [removed]
           (persistence/entity-deletions deltas)))))

(deftest corrupt-flat-mixed-and-metadata-storage-fails-closed-test
  (let [entity
        (first
          (:permissions
            (persistence/candidate-schema (resolver/validate-schema schema))))]
    (is (= :flat-only-representation
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(select-keys entity
                     [:eacl/id
                      :eacl.permission/resource-type
                      :eacl.permission/permission-name])])))))
    (is (= :mixed-flat-and-expression
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(assoc entity
                          :eacl.permission/target-type :relation)])))))
    (is (= :field-mismatch
           (:reason
             (error-data
               #(persistence/validate-entities
                  [(assoc entity :eacl.permission/resource-type
                          :folder)])))))
    (is (= (:expression (persistence/decode-entity-with-metadata entity))
           (:expression
            (persistence/decode-entity-with-metadata
             (assoc entity :eacl.permission/expression-digest
                    "stale-experimental-digest")))))
    (is (= (:expression (persistence/decode-entity-with-metadata entity))
           (:expression
            (persistence/decode-entity-with-metadata
             (assoc entity :eacl.permission/source-node-count 999999)))))
    (is (= :duplicate-expression
           (:reason
             (error-data
               #(persistence/validate-entities [entity entity])))))))

(deftest union-compatible-projection-retains-existing-plan-shape-test
  (let [candidate (persistence/candidate-schema
                    (resolver/validate-schema schema))
        base (first (:permissions candidate))
        expression (persistence/decode-entity base)
        definitions
        (persistence/union-compatible-definitions 42 expression)]
    (is (= 2 (count definitions)))
    (is (= #{:reader :writer} (set (map :target-name definitions))))
    (is (every? #(= 42 (:permission-id %)) definitions)))
  (let [candidate (persistence/candidate-schema
                    (resolver/validate-schema schema))
        view (second (:permissions candidate))
        data
        (error-data
          #(persistence/union-compatible-definitions
             43 (persistence/decode-entity view)))]
    (is (= :eacl.schema/operator-plan-required (:type data)))))

(deftest union-compatible-projection-preserves-flat-set-semantics-test
  (let [candidate
        (persistence/candidate-schema
         (resolver/validate-schema
          "definition user {}
           definition document {
             relation reader: user
             permission view = reader + reader
           }"))
        permission (first (:permissions candidate))
        definitions
        (persistence/union-compatible-definitions
         7 (persistence/decode-entity permission))]
    (is (= 1 (count definitions)))
    (is (= :reader (:target-name (first definitions))))))

(deftest cross-request-expression-decodes-use-flat-derived-lru-test
  (let [entity
        (first
         (:permissions
          (persistence/candidate-schema
           (resolver/validate-schema schema))))
        store (derived-schema/store 4)
        partition
        (derived-schema/artifact-partition
         store (derived-identity) :expression-decodes)
        first-value
        (binding [persistence/*structural-cache* partition]
          (persistence/decode-entity-with-metadata entity))
        second-value
        (binding [persistence/*structural-cache* partition]
          (persistence/decode-entity-with-metadata entity))]
    (is (= first-value second-value))
    (is (= {:entry-count 1 :max-entries 4}
           (derived-schema/stats store)))))

#?(:clj
   (deftest concurrent-structural-decode-misses-build-independently-test
     (let [request-count 8
           cache (atom {})
           entity {:eacl.permission/expression-payload "independent"}
           ready (java.util.concurrent.CountDownLatch. request-count)
           release (java.util.concurrent.CountDownLatch. 1)
           builds (java.util.concurrent.atomic.AtomicLong.)
           decode-var
           (ns-resolve 'eacl.schema.expression-persistence
                       'decode-entity-with-metadata-uncached)]
       (with-redefs-fn
         {decode-var
          (fn [_]
            (let [build (.getAndIncrement builds)]
              (.countDown ready)
              (.await release)
              {:build build}))}
         (fn []
           (let [workers
                 (mapv
                  (fn [_]
                    (future
                      (binding [persistence/*structural-cache* cache]
                        (persistence/decode-entity-with-metadata entity))))
                  (range request-count))]
             (is (.await ready 5 java.util.concurrent.TimeUnit/SECONDS))
             (is (= request-count (.get builds)))
             (.countDown release)
             (let [results (mapv #(deref % 5000 ::timed-out) workers)]
               (is (= (set (map #(hash-map :build %)
                                (range request-count)))
                      (set results)))
               (binding [persistence/*structural-cache* cache]
                 (is (contains?
                      (set results)
                      (persistence/decode-entity-with-metadata entity)))))))))))
