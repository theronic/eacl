(ns eacl.datomic.safe-retraction
  "Optional Datomic transaction function for atomically retracting an entity
  and the peer halves of EACL relationship tuples that are stored on it.

  Requiring this namespace does not change a database. Call `install!`
  explicitly, then transact the data returned by `retract-entity-tx-data`."
  (:require [clojure.string :as str]
            [datomic.api :as d]
            [eacl.datomic.mutation :as journal]
            [eacl.relationships.safe-retraction :as safe]))

(def function-digest
  "53ed4ed4f532cf085723d550ba8b367ffe648fc7667fd8312a578748a30ac564")

(def function-doc
  (str safe/function-doc-prefix " v" safe/function-version
       "; digest=" function-digest
       "; optional Datomic database function"))

(def function-definition
  "The installable Datomic database-function entity.

  Its body is deliberately self-contained: a Peer transactor evaluating it
  needs only Clojure and datomic.api, not EACL on the transactor classpath."
  {:db/ident safe/function-ident
   :db/doc function-doc
   :db/fn
   (d/function
    {:lang "clojure"
     :params '[db target envelope]
     :code
     '(let [invalid!
            (fn [reason data]
              (throw
               (ex-info
                "Invalid EACL safe-retraction transaction."
                (merge
                 {:type :eacl.safe-retraction/invalid
                  :eacl/error :eacl.safe-retraction/invalid
                  :reason reason}
                 data))))
            render-canonical
            (fn render-canonical [value]
              (cond
                (string? value)
                (str
                 "\""
                 (apply
                  str
                  (map
                   (fn [character]
                     (case (int character)
                       8 "\\b"
                       9 "\\t"
                       10 "\\n"
                       12 "\\f"
                       13 "\\r"
                       34 "\\\""
                       92 "\\\\"
                       (if (< (int character) 32)
                         (let [hex (Integer/toHexString (int character))]
                           (str "\\u"
                                (apply str (repeat (- 4 (count hex)) "0"))
                                hex))
                         (str character))))
                   value))
                 "\"")

                (keyword? value)
                (str ":"
                     (when-let [ns-part (namespace value)] (str ns-part "/"))
                     (name value))

                (integer? value) (str value)

                (sequential? value)
                (str "["
                     (apply str
                            (interpose " " (map render-canonical value)))
                     "]")

                :else nil))
            required-keys
            #{:version :mutation-id :fingerprint :issued-at
              :previous-expires-at :canonical-data}]
        (when-not (and (map? envelope)
                       (= required-keys (set (keys envelope))))
          (invalid! :invalid-envelope-shape
                    {:actual-keys
                     (when (map? envelope) (set (keys envelope)))}))
        (let [{:keys [version mutation-id fingerprint issued-at
                      previous-expires-at canonical-data]} envelope]
          (when-not (= 1 version)
            (invalid! :unsupported-version {:version version}))
          (when-not (= {:operation :safe-retract-entity :target target}
                       canonical-data)
            (invalid! :target-mismatch
                      {:expected
                       {:operation :safe-retract-entity :target target}
                       :actual canonical-data}))
          (when-not (and (string? mutation-id)
                         (= 43 (count mutation-id)))
            (invalid! :invalid-mutation-id {:mutation-id mutation-id}))
          (when-not (and (string? fingerprint)
                         (= 43 (count fingerprint)))
            (invalid! :invalid-fingerprint {}))
          (let [rendered-target (render-canonical target)
                expected-fingerprint
                (when rendered-target
                  (try
                    (let [decoder (java.util.Base64/getUrlDecoder)
                          encoder (.withoutPadding
                                   (java.util.Base64/getUrlEncoder))
                          key (.decode decoder mutation-id)
                          mac (javax.crypto.Mac/getInstance "HmacSHA256")
                          _ (.init
                             mac
                             (javax.crypto.spec.SecretKeySpec.
                              key "HmacSHA256"))
                          payload
                          (str
                           "eacl/mutation/idempotency/v3\n"
                           "{:operation :safe-retract-entity, :target "
                           rendered-target
                           "}")]
                      (.encodeToString
                       encoder
                       (.doFinal
                        mac
                        (.getBytes
                         payload java.nio.charset.StandardCharsets/UTF_8))))
                    (catch Throwable _ nil)))]
            (when-not
             (and expected-fingerprint
                  (java.security.MessageDigest/isEqual
                   (.getBytes
                    expected-fingerprint
                    java.nio.charset.StandardCharsets/UTF_8)
                   (.getBytes
                    fingerprint
                    java.nio.charset.StandardCharsets/UTF_8)))
              (invalid! :invalid-fingerprint {})))
          (when-not (and (integer? issued-at)
                         (integer? previous-expires-at)
                         (< issued-at previous-expires-at))
            (invalid! :invalid-retention
                      {:issued-at issued-at
                       :previous-expires-at previous-expires-at}))
          (let [target-eid (datomic.api/entid db target)]
            ;; entid returns a raw numeric eid even if that entity no longer
            ;; exists. The eavt check makes both missing lookup refs and stale
            ;; raw eids true no-ops and, crucially, avoids a global ghost scan.
            (if-not (and target-eid
                         (seq (datomic.api/datoms db :eavt target-eid)))
              []
              (let [forward-attr
                    :eacl.v7.relationship/subject-type+relation+resource-type+resource
                    reverse-attr
                    :eacl.v7.relationship/resource-type+relation+subject-type+subject
                    forward-values
                    (mapv :v
                          (datomic.api/datoms
                           db :eavt target-eid forward-attr))
                    reverse-values
                    (mapv :v
                          (datomic.api/datoms
                           db :eavt target-eid reverse-attr))
                    valid-value?
                    (fn [value]
                      (and (vector? value)
                           (= 4 (count value))
                           (keyword? (nth value 0))
                           (integer? (nth value 1))
                           (not (neg? (nth value 1)))
                           (keyword? (nth value 2))
                           (integer? (nth value 3))
                           (not (neg? (nth value 3)))))
                    _
                    (when-not (every? valid-value?
                                      (concat forward-values reverse-values))
                      (invalid! :malformed-endpoint-half
                                {:target-eid target-eid}))
                    peer-retractions
                    (distinct
                     (concat
                      (for [[subject-type relation-eid resource-type
                             resource-eid] forward-values
                            :when (not= target-eid resource-eid)]
                        [:db/retract
                         resource-eid
                         reverse-attr
                         [resource-type relation-eid subject-type target-eid]])
                      (for [[resource-type relation-eid subject-type
                             subject-eid] reverse-values
                            :when (not= target-eid subject-eid)]
                        [:db/retract
                         subject-eid
                         forward-attr
                         [subject-type relation-eid resource-type target-eid]])))
                    relation-eids
                    (distinct
                     (concat (map #(nth % 1) forward-values)
                             (map #(nth % 1) reverse-values)))
                    graph-eid
                    (datomic.api/entid db [:eacl/id "eacl.v3/graph"])
                    graph
                    (when graph-eid (datomic.api/entity db graph-eid))
                    head-id (:eacl.graph/head-id graph)]
                (when-not (and (string? head-id) (not-empty head-id))
                  (invalid! :missing-mutation-graph {}))
                (vec
                 (concat
                  [{:eacl/id (str "eacl.v3/mutation/" mutation-id)
                    :eacl.mutation/id mutation-id
                    :eacl.mutation/fingerprint fingerprint
                    :eacl.mutation/kind :object-deletion
                    :eacl.mutation/issued-at issued-at}
                   [:db.fn/cas
                    [:eacl/id "eacl.v3/graph"]
                    :eacl.graph/head-id
                    head-id
                    mutation-id]
                   [:db/add
                    [:eacl/id "eacl.v3/graph"]
                    :eacl.graph/head-order
                    "datomic.tx"]
                   {:db/id [:eacl.mutation/id head-id]
                    :eacl.mutation/expires-at previous-expires-at}]
                  (mapcat
                   (fn [relation-eid]
                     [[:db/add
                       relation-eid
                       :eacl/relation-version
                       "datomic.tx"]
                      {:db/id relation-eid
                       :eacl.relation/mutation-id mutation-id}])
                   relation-eids)
                  peer-retractions
                  [[:db.fn/retractEntity target-eid]])))))))})})

(def support
  (safe/support-descriptor
   {:backend :datomic
    :mode :named
    :reason :database-function
    :ident safe/function-ident
    :requires-installation? true}))

(defn support-descriptor
  "Describes Datomic's optional named transaction-function support."
  []
  support)

(defn installation-state
  "Returns :absent, :current, :upgradeable, or :conflict for `db`."
  [db]
  (if-let [eid (d/entid db safe/function-ident)]
    (let [entity (d/entity db eid)
          doc (:db/doc entity)
          installed-fn (:db/fn entity)]
      (cond
        (and (= function-doc doc) installed-fn) :current
        (and (string? doc)
             (str/starts-with? doc safe/function-doc-prefix)) :upgradeable
        :else :conflict))
    :absent))

(defn install!
  "Idempotently installs or upgrades `:eacl.fn/retractEntity` on `conn`.

  The mutation-journal prerequisites are prepared first. An unrelated entity
  already claiming the public ident is rejected instead of overwritten."
  [conn]
  (journal/ensure-migrated! conn)
  (let [state (installation-state (d/db conn))]
    (case state
      :current
      {:installed? false :state :current :support support}

      (:absent :upgradeable)
      (do
        @(d/transact conn [function-definition])
        {:installed? true :state state :support support})

      :conflict
      (throw
       (ex-info
        "The Datomic ident :eacl.fn/retractEntity is already owned by another function."
        {:type :eacl.safe-retraction/install-conflict
         :eacl/error :eacl.safe-retraction/install-conflict
         :backend :datomic
         :ident safe/function-ident})))))

(defn retract-entity-tx-data
  "Builds one invocation of the installed safe-retraction function.

  Retry-stable callers may supply :mutation-id and :issued-at. All envelope
  validation, authentication, and randomness happen outside the transactor."
  ([target]
   (retract-entity-tx-data target {}))
  ([target options]
   (let [envelope (safe/mutation-envelope target options)]
     (safe/validate-envelope target envelope)
     [[safe/function-ident target envelope]])))
