(ns eacl.relationships.safe-retraction
  "Portable contracts and pure planning helpers for safe entity retraction.

  Backend namespaces own snapshot reads and native transaction-function
  installation. The public transaction function deliberately takes only the
  native retractEntity target; it carries no EACL mutation envelope or journal
  state."
  (:require [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.storage :as storage]))

(def function-ident :eacl.fn/retractEntity)
(def function-version 2)
(def function-doc-prefix "EACL safe entity retraction function")
(def supported-modes #{:named :direct :unsupported})

(def relation-version-attribute :eacl/relation-version)
(def current-transaction-value :db/current-tx)

(defn- fail!
  [reason data]
  (throw
   (ex-info
    "Invalid EACL safe-retraction input."
    (merge {:type :eacl.safe-retraction/invalid
            :eacl/error :eacl.safe-retraction/invalid
            :reason reason}
           data))))

(defn support-descriptor
  [{:keys [backend mode] :as descriptor}]
  (when-not (keyword? backend)
    (fail! :invalid-backend {:backend backend}))
  (when-not (contains? supported-modes mode)
    (fail! :invalid-mode {:backend backend :mode mode}))
  (when-not (keyword? (:reason descriptor))
    (fail! :missing-reason {:backend backend :mode mode}))
  descriptor)

(defn valid-target?
  "True for the portable subset of native retractEntity targets.

  Numeric eids remain valid even after their entity datoms disappear. Lookup
  refs must be a two-element attribute/value vector; a missing lookup ref is a
  backend no-op because its lost eid cannot be reconstructed."
  [target]
  (or (nat-int? target)
      (and (vector? target)
           (= 2 (count target))
           (keyword? (first target))
           (some? (second target)))))

(defn validate-target!
  [target]
  (when-not (valid-target? target)
    (fail! :invalid-target {:target target}))
  target)

(defn protected-control-entity?
  "True when a pulled entity belongs to EACL's schema/control plane.

  Safe retraction is an object operation. Definitions and installed EACL
  functions must be changed through their dedicated writers so the schema
  generation remains authoritative."
  [{:keys [db-ident eacl-id relation-name permission-name schema-string]}]
  (or (= "schema-string" eacl-id)
      (some? relation-name)
      (some? permission-name)
      (some? schema-string)
      (and (keyword? db-ident)
           (contains? #{"eacl" "eacl.fn"} (namespace db-ident)))))

(defn component-closure
  "Returns a breadth-first, cycle-safe native component deletion closure.

  `children` is snapshot-bound and returns the component eids stored on one
  entity. Work is O(closure size plus component edges)."
  [root-eid children]
  (when-not (nat-int? root-eid)
    (fail! :invalid-target-eid {:target-eid root-eid}))
  (when-not (fn? children)
    (fail! :invalid-component-reader {}))
  (loop [queue (conj #?(:clj clojure.lang.PersistentQueue/EMPTY
                        :cljs (.-EMPTY cljs.core/PersistentQueue))
                      root-eid)
         seen #{}
         result []]
    (if (empty? queue)
      result
      (let [eid (peek queue)
            queue (pop queue)]
        (if (contains? seen eid)
          (recur queue seen result)
          (let [child-eids (vec (children eid))]
            (when-not (every? nat-int? child-eids)
              (fail! :invalid-component-eid
                     {:entity-eid eid :component-eids child-eids}))
            (recur (into queue child-eids)
                   (conj seen eid)
                   (conj result eid))))))))

(defn- decoded-half!
  [direction target-eid value]
  (or (case direction
        :forward (endpoint-pair/decode-forward target-eid value)
        :reverse (endpoint-pair/decode-reverse target-eid value))
      (fail! :malformed-endpoint-half
             {:direction direction
              :target-eid target-eid
              :value value})))

(defn plan-local-halves
  "Plans peer retractions from endpoint halves stored on `target-eid`.

  Local halves are intentionally omitted: the backend's native retractEntity
  operation owns them. Call once per entity in the native component closure
  and combine the distinct peer operations and relation ids."
  [target-eid forward-values reverse-values]
  (when-not (nat-int? target-eid)
    (fail! :invalid-target-eid {:target-eid target-eid}))
  (let [forward-plans
        (mapv
         (fn [value]
           (let [{:keys [subject-type relation-eid resource-type resource-eid]}
                 (decoded-half! :forward target-eid value)]
             {:relation-eid relation-eid
              :peer-eid resource-eid
              :self? (= target-eid resource-eid)
              :op [:db/retract
                   resource-eid
                   storage/reverse-attribute
                   (endpoint-pair/reverse-value
                    resource-type relation-eid subject-type target-eid)]}))
         forward-values)
        reverse-plans
        (mapv
         (fn [value]
           (let [{:keys [subject-type subject-eid relation-eid resource-type]}
                 (decoded-half! :reverse target-eid value)]
             {:relation-eid relation-eid
              :peer-eid subject-eid
              :self? (= target-eid subject-eid)
              :op [:db/retract
                   subject-eid
                   storage/forward-attribute
                   (endpoint-pair/forward-value
                    subject-type relation-eid resource-type target-eid)]}))
         reverse-values)
        plans (into forward-plans reverse-plans)]
    {:peer-retractions
     (into [] (comp (remove :self?) (map :op) (distinct)) plans)
     :relation-ids (into [] (comp (map :relation-eid) (distinct)) plans)
     :local-half-count (count plans)}))

(defn combine-plans
  "Combines closure-local or repair plans without duplicate tx operations."
  [plans]
  {:peer-retractions
   (into [] (comp (mapcat :peer-retractions) (distinct)) plans)
   :relation-ids
   (into [] (comp (mapcat :relation-ids) (distinct)) plans)
   :local-half-count (reduce + 0 (map :local-half-count plans))})

(defn relation-stamps
  "One idempotent current-transaction stamp per distinct affected relation."
  [relation-ids]
  (mapv (fn [relation-id]
          [:db/add relation-id relation-version-attribute
           current-transaction-value])
        (distinct relation-ids)))

(defn target-invocation
  "Builds the named target-only invocation accepted by all named backends."
  [target]
  (validate-target! target)
  [[function-ident target]])
