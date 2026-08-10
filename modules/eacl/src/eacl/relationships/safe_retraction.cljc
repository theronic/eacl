(ns eacl.relationships.safe-retraction
  "Portable planning and mutation-envelope contracts for safe entity retraction.

  Backend namespaces own database access and transaction submission. This
  namespace deliberately depends only on the portable EACL artifact."
  (:require [eacl.mutation :as mutation]
            [eacl.relationships.endpoint-pair :as endpoint-pair]
            [eacl.relationships.storage :as storage]
            [eacl.secure-format :as secure]))

(def function-ident :eacl.fn/retractEntity)
(def function-version 1)
(def function-doc-prefix "EACL safe entity retraction function")
(def supported-modes #{:named :direct :unsupported})

(defn canonical-request
  [target]
  {:operation :safe-retract-entity
   :target target})

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

(defn mutation-envelope
  "Builds authenticated, retry-stable metadata for one safe-retraction call.

  Randomness and wall-clock access happen here, outside transaction-function
  evaluation. Pass :mutation-id and :issued-at in tests or deterministic retry
  orchestration."
  ([target]
   (mutation-envelope target {}))
  ([target {:keys [mutation-id issued-at token-ttl-seconds
                   retention-grace-seconds]
            :or {token-ttl-seconds mutation/default-token-ttl-seconds
                 retention-grace-seconds
                 mutation/default-retention-grace-seconds}}]
   (let [mutation-id (or mutation-id (mutation/new-id))
         issued-at (or issued-at (mutation/now-seconds))
         canonical-data (canonical-request target)]
     {:version function-version
      :mutation-id mutation-id
      :fingerprint
      (mutation/mutation-fingerprint mutation-id canonical-data)
      :issued-at issued-at
      :previous-expires-at
      (mutation/retention-expiry
       {:issued-at issued-at
        :token-ttl-seconds token-ttl-seconds
        :retention-grace-seconds retention-grace-seconds})
      :canonical-data canonical-data})))

(def envelope-keys
  #{:version :mutation-id :fingerprint :issued-at
    :previous-expires-at :canonical-data})

(defn validate-envelope
  "Returns `envelope` or throws structured data before any tx-data is emitted."
  [target envelope]
  (when-not (and (map? envelope)
                 (= envelope-keys (set (keys envelope))))
    (fail! :invalid-envelope-shape
           {:actual-keys (when (map? envelope) (set (keys envelope)))}))
  (let [{:keys [version mutation-id fingerprint issued-at
                previous-expires-at canonical-data]} envelope
        expected-data (canonical-request target)]
    (when-not (= function-version version)
      (fail! :unsupported-version {:version version}))
    (when-not (= expected-data canonical-data)
      (fail! :target-mismatch {:expected expected-data
                               :actual canonical-data}))
    (when-not (and (string? mutation-id) (= 43 (count mutation-id)))
      (fail! :invalid-mutation-id {:mutation-id mutation-id}))
    (when-not (and (integer? issued-at)
                   (integer? previous-expires-at)
                   (< issued-at previous-expires-at))
      (fail! :invalid-retention
             {:issued-at issued-at
              :previous-expires-at previous-expires-at}))
    (let [expected
          (mutation/mutation-fingerprint mutation-id expected-data)]
      (when-not
       (and (string? fingerprint)
            (secure/secure-equal? (secure/utf8-bytes expected)
                                  (secure/utf8-bytes fingerprint)))
        (fail! :invalid-fingerprint {})))
    envelope))

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
  "Plans peer retractions from the endpoint halves stored on `target-eid`.

  `forward-values` and `reverse-values` are the values of the two canonical
  endpoint attributes. Local halves are intentionally omitted: the backend's
  ordinary retractEntity operation owns them."
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

(defn mutation-tx-data
  "Portable v3 mutation proof data for one resolved safe retraction.

  The caller supplies the backend's current-transaction value. The graph state
  must already exist; backend installers/preparers ensure that prerequisite."
  [graph-state relation-ids order-value envelope]
  (let [{:keys [head-id]} graph-state
        {:keys [mutation-id fingerprint issued-at previous-expires-at]}
        envelope]
    (when-not (and (string? head-id) (not-empty head-id))
      (fail! :missing-mutation-graph {:graph-state graph-state}))
    (into
     [{:eacl/id (mutation/mutation-entity-id mutation-id)
       mutation/mutation-id-attr mutation-id
       mutation/mutation-fingerprint-attr fingerprint
       mutation/mutation-kind-attr :object-deletion
       mutation/mutation-issued-at-attr issued-at}
      [:db.fn/cas
       [:eacl/id mutation/graph-entity-id]
       mutation/graph-head-id-attr
       head-id
       mutation-id]
      [:db/add
       [:eacl/id mutation/graph-entity-id]
       mutation/graph-head-order-attr
       order-value]
      {:db/id [mutation/mutation-id-attr head-id]
       mutation/mutation-expires-at-attr previous-expires-at}]
     (map (fn [relation-id]
            {:db/id relation-id
             mutation/relation-mutation-id-attr mutation-id}))
     relation-ids)))
