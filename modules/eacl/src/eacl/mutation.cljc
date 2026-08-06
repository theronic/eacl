(ns eacl.mutation
  "Portable mutation-journal identities and transaction-data construction."
  (:require [eacl.secure-format :as secure]))

(def mutation-id-attr :eacl.mutation/id)
(def mutation-fingerprint-attr :eacl.mutation/fingerprint)
(def mutation-kind-attr :eacl.mutation/kind)
(def mutation-issued-at-attr :eacl.mutation/issued-at)
(def mutation-expires-at-attr :eacl.mutation/expires-at)
(def graph-family-id-attr :eacl.graph/family-id)
(def graph-head-id-attr :eacl.graph/head-id)
(def graph-head-order-attr :eacl.graph/head-order)
(def schema-mutation-id-attr :eacl.schema/mutation-id)
(def relation-mutation-id-attr :eacl.relation/mutation-id)
(def dependency-mutation-id-attr :eacl.dependency/mutation-id)

(def graph-entity-id "eacl.v3/graph")
(def schema-entity-id "eacl.v3/schema")
(def mutation-entity-prefix "eacl.v3/mutation/")

(def default-token-ttl-seconds 3600)
(def default-retention-grace-seconds 300)
(def ^:private maximum-mutation-entries 10000000)
(def ^:private maximum-mutation-size (* 64 1024 1024))

(defn now-seconds
  []
  (quot (#?(:clj System/currentTimeMillis
            :cljs js/Date.now))
        1000))

(defn new-id
  "Returns a cryptographically random 256-bit URL-safe identifier."
  []
  (secure/b64url-encode (secure/random-bytes 32)))

(defn mutation-entity-id
  [mutation-id]
  (str mutation-entity-prefix mutation-id))

(defn mutation-fingerprint
  "Authenticates canonical logical mutation data under its random mutation id."
  [mutation-id canonical-data]
  (let [key
        (try
          (secure/b64url-decode mutation-id)
          (catch #?(:clj Exception :cljs :default) _
            (throw (ex-info "Invalid EACL mutation id."
                            {:type :eacl.mutation/invalid-id
                             :mutation-id mutation-id}))))]
    (when-not (= 32 (count key))
      (throw (ex-info "Invalid EACL mutation id."
                      {:type :eacl.mutation/invalid-id
                       :mutation-id mutation-id})))
    (secure/b64url-encode
     (secure/hmac-sha-256
      key
      (str "eacl/mutation/idempotency/v3\n"
           (secure/encode-canonical
            canonical-data
            {:maximum-entries maximum-mutation-entries
             :maximum-size maximum-mutation-size}))))))

(defn retention-expiry
  [{:keys [issued-at token-ttl-seconds retention-grace-seconds]
    :or {issued-at (now-seconds)
         token-ttl-seconds default-token-ttl-seconds
         retention-grace-seconds default-retention-grace-seconds}}]
  (when-not (and (integer? issued-at)
                 (integer? token-ttl-seconds)
                 (pos? token-ttl-seconds)
                 (integer? retention-grace-seconds)
                 (not (neg? retention-grace-seconds)))
    (throw (ex-info "Invalid EACL mutation retention configuration."
                    {:type :eacl/invalid-config
                     :issued-at issued-at
                     :token-ttl-seconds token-ttl-seconds
                     :retention-grace-seconds retention-grace-seconds})))
  (+ issued-at token-ttl-seconds retention-grace-seconds))

(defn mutation-record
  [{:keys [mutation-id kind canonical-data issued-at expires-at]}]
  (let [issued-at (or issued-at (now-seconds))]
    (cond-> {:eacl/id (mutation-entity-id mutation-id)
             mutation-id-attr mutation-id
             mutation-fingerprint-attr
             (mutation-fingerprint mutation-id canonical-data)
             mutation-kind-attr kind
             mutation-issued-at-attr issued-at}
      expires-at (assoc mutation-expires-at-attr expires-at))))

(defn transaction-data
  "Builds portable map transaction data for one atomic authorization mutation.

  `relation-ids` are backend-native entity ids or lookup refs. `order-value`
  may be a backend current-transaction value. The caller prepends actual
  schema/relationship tx-data and submits the combined collection once."
  [{:keys [mutation-id kind canonical-data relation-ids dependency-ids
           schema-change?
           order-value family-id issued-at previous-head-id token-ttl-seconds
           retention-grace-seconds family-cas?]
    :or {relation-ids []
         dependency-ids []}}]
  (when-not (and (string? mutation-id)
                 (not-empty mutation-id)
                 (keyword? kind))
    (throw (ex-info "Invalid EACL mutation transaction."
                    {:type :eacl.mutation/invalid
                     :mutation-id mutation-id
                     :kind kind})))
  (let [issued-at (or issued-at (now-seconds))
        previous-expires-at
        (retention-expiry
         {:issued-at issued-at
          :token-ttl-seconds
          (or token-ttl-seconds default-token-ttl-seconds)
          :retention-grace-seconds
          (or retention-grace-seconds default-retention-grace-seconds)})
        relation-ids (vec (distinct relation-ids))
        dependency-ids (vec (distinct dependency-ids))]
    (cond-> [(mutation-record
             {:mutation-id mutation-id
               :kind kind
               :canonical-data canonical-data
               :issued-at issued-at})
             (cond-> {:eacl/id graph-entity-id}
               (nil? previous-head-id)
               (assoc graph-head-id-attr mutation-id)
               (and family-id (not family-cas?))
               (assoc graph-family-id-attr family-id)
               (some? order-value) (assoc graph-head-order-attr order-value))]
      previous-head-id
      (conj [:db.fn/cas
             [:eacl/id graph-entity-id]
             graph-head-id-attr
             previous-head-id
             mutation-id])

      (and family-id family-cas?)
      (conj [:db.fn/cas
             [:eacl/id graph-entity-id]
             graph-family-id-attr
             nil
             family-id])

      previous-head-id
      (conj {:db/id [mutation-id-attr previous-head-id]
             mutation-expires-at-attr previous-expires-at})

      schema-change?
      (conj {:eacl/id schema-entity-id
             schema-mutation-id-attr mutation-id})

      (seq relation-ids)
      (into (map (fn [relation-id]
                   {:db/id relation-id
                    relation-mutation-id-attr mutation-id})
                 relation-ids))

      (seq dependency-ids)
      (into (map (fn [dependency-id]
                   {:db/id dependency-id
                    dependency-mutation-id-attr mutation-id})
                 dependency-ids)))))

(defn migration-data
  "Creates the initial graph/schema/relation identities in one transaction."
  [{:keys [relation-ids family-id order-value] :as options}]
  (let [mutation-id (or (:mutation-id options) (new-id))]
    {:mutation-id mutation-id
     :tx-data
     (transaction-data
      (merge options
             {:mutation-id mutation-id
              :kind :migration
              :canonical-data
              {:operation :migration
               :family-id family-id
               :relation-ids (vec (sort relation-ids))}
              :relation-ids relation-ids
              :schema-change? true
              :family-cas? true
              :order-value order-value}))}))

(defn mutation-data-matches?
  "Checks idempotent retry data against a stored mutation entity/map."
  [stored mutation-id canonical-data]
  (and (= mutation-id (get stored mutation-id-attr))
       (secure/secure-equal?
        (secure/utf8-bytes
         (or (get stored mutation-fingerprint-attr) ""))
        (secure/utf8-bytes
         (mutation-fingerprint mutation-id canonical-data)))))

(defn expired?
  ([mutation]
   (expired? mutation (now-seconds)))
  ([mutation now]
   (< (get mutation mutation-expires-at-attr 0) now)))
