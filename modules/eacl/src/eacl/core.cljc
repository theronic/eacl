(ns eacl.core
  "Public authorization capabilities, records, and normalization helpers."
  (:require [eacl.execution :as execution]
            [eacl.security.keyring :as keyring]))

(defn security-keyring
  "Creates a non-durable controller from {:keys {kid material} :active-kid kid}.
   Supply externally generated material (at least 32 random bytes). IDs must be
   unique across epochs. Optional :max-keys / :max-retired-kids lower hard caps.
   See docs/security-keyrings.md for secret ownership and the rollout runbook."
  [options]
  (keyring/keyring options))

(defn security-keyring? [value] (keyring/keyring? value))

(defn security-keyring-status
  "Returns generation and key identifiers, never secret material."
  [controller]
  (keyring/status controller))

(defn replace-security-keyring!
  "Atomically replaces {:keys {kid material} :active-kid kid} at
   :expected-generation. Returns safe status; stale updates raise
   :eacl.keyring/conflict. Removed IDs cannot be reintroduced."
  [controller desired]
  (keyring/replace! controller desired))

(defn add-security-key!
  "Installs an inactive key; identical accepted material is idempotent.
   Distribute and observe acceptance on every Peer before activation."
  [controller kid material]
  (keyring/add! controller kid material))

(defn activate-security-key!
  "Selects an installed key for issuance without removing accepted old keys.
   Does not change source lifecycle or authorization proof identity."
  [controller kid]
  (keyring/activate! controller kid))

(defn retire-security-key!
  "Removes an inactive key; subsequent operations reject artifacts under it.
   Default non-expiring cursors require indefinite retention for lossless resume.
   Imported caches miss; independently computed local answers remain reusable."
  [controller kid]
  (keyring/retire! controller kid))

(defn cancellation-token
  "Creates a caller-owned cooperative cancellation token for one request."
  []
  (execution/cancellation-token))

(defn cancellation-token?
  "True when `value` implements EACL cooperative cancellation."
  [value]
  (execution/cancellation-token? value))

(defn cancel!
  "Requests cooperative cancellation for `token`; idempotently returns true."
  [token]
  (execution/cancel! token))

(defn cancelled?
  "True when cancellation has been requested for `token`."
  [token]
  (execution/cancelled? token))

(declare ->Relationship ->RelationshipUpdate)

(defprotocol IAuthorizationReader
  "Canonical request-map authorization reads."
  (-check-permission [this request])
  (-read-schema [this request])
  (-read-relationships [this request])
  (-lookup-resources [this request])
  (-lookup-subjects [this request])
  (-count-resources [this request])
  (-count-subjects [this request])
  (-expand-permission-tree [this request]))

(defprotocol IAuthorizationWriter
  "Canonical authorization mutations."
  (-write-schema! [this request])
  (-write-relationships! [this request])
  (-delete-object! [this request]))

(defprotocol IRelationshipPreparation
  "Explicit writer-owned preparation for caller-composed qualified transactions."
  (-prepare-relationship! [this relationship])
  (-discard-prepared-relationship! [this prepared]))

(defprotocol IRelationshipPlanning
  "Atomic batch planning on one immutable snapshot."
  (-tx-relationships [this request]))

(defprotocol ISnapshotSource
  "Selects one immutable authorization snapshot."
  (-snapshot [this consistency options]))

(defprotocol IAuthorizationSnapshot
  "Basis metadata and explicit snapshot lifecycle."
  (-basis [this])
  (-basis-token [this])
  (-release! [this])
  (-released? [this]))

(defprotocol IBatchedAuthorization
  "Authorization extension for ordered point checks over one snapshot."
  (-check-permissions [this request]))

(defprotocol ISpeculativeAuthorization
  "Explicit, EACL-owned prospective transaction capabilities.

  Implementations establish provenance from this call path; callers cannot
  assert that an arbitrary native database value is ordinary or speculative."
  (-with [this tx-data])
  (-with-schema [this schema options])
  (-tx-relationship [this update])
  (-speculative-diagnostics [this]))

(defn snapshot?
  "True when `value` is an immutable EACL authorization snapshot."
  [value]
  (satisfies? IAuthorizationSnapshot value))

(defn acl?
  "True when `value` is a live EACL snapshot source."
  [value]
  (and (satisfies? ISnapshotSource value)
       (not (snapshot? value))))

(defn- target-kind
  [target]
  (cond
    (snapshot? target) :snapshot
    (acl? target) :acl
    (satisfies? IAuthorizationWriter target) :writer
    (satisfies? IAuthorizationReader target) :reader
    :else :non-eacl))

(defn- typed-error
  [type message data]
  (ex-info message (assoc data :type type :eacl/error type)))

(def ^:private endpoint-keys #{:type :id :relation})

(def ^:private single-relationship-write-keys
  #{:operation :subject :relation :resource
    :caveat :caveat-context :valid-until-ms})

(def ^:private execution-request-keys
  #{:consistency :cache? :populate-cache? :evaluation :timeout-ms
    :cancellation-token :caveat-context :aggregate-limits})

(def ^:private point-request-keys
  (into execution-request-keys #{:subject :permission :resource}))

(def ^:private read-schema-request-keys
  #{:consistency :timeout-ms :cancellation-token})

(def ^:private relationship-read-request-keys
  #{:subject/type :subject/id :resource/type :resource/id
    :resource/relation :resource/id-prefix :subject/relation
    :first :last :after :before :cursor :limit
    :page/basis :consistency :cache? :populate-cache? :evaluation
    :timeout-ms :caveat-context :relationship-state :cancellation-token
    :aggregate-limits :authorization})

(def ^:private page-request-keys
  #{:first :last :after :before :cursor :limit :page/basis
    :consistency :cache? :populate-cache? :evaluation :timeout-ms
    :cancellation-token :caveat-context :result-policy :aggregate-limits})

(def ^:private lookup-resources-request-keys
  (into page-request-keys
        #{:subject :permission :resource/type :resource/relationship}))

(def ^:private lookup-subjects-request-keys
  (into page-request-keys
        #{:resource :permission :subject/type :subject/relation
          :subject/relationship}))

(def ^:private count-resources-request-keys
  (into execution-request-keys
        #{:subject :permission :resource/type :count-limit :result-policy}))

(def ^:private count-subjects-request-keys
  (into execution-request-keys
        #{:resource :permission :subject/type :subject/relation
          :count-limit :result-policy}))

(def ^:private count-pagination-request-keys
  #{:first :last :after :before :cursor :limit :page/basis})

(def ^:private permission-tree-request-keys
  #{:resource :permission :consistency :timeout-ms :cancellation-token
    :cache? :populate-cache?})

(defn- validate-request-keys!
  [operation request known-keys]
  (when-not (map? request)
    (throw
     (typed-error
      :eacl/invalid-request
      (str (name operation) " requires a request map.")
      {:operation operation :reason :invalid-request-shape :value request})))
  (when-let [unknown-keys (seq (remove known-keys (keys request)))]
    (throw
     (typed-error
      :eacl/invalid-request
      (str (name operation) " received unknown request keys.")
      {:operation operation
       :reason :unknown-request-key
       :unknown-keys (vec unknown-keys)
       :known-keys known-keys})))
  request)

(defn- validate-endpoint-keys!
  [operation position endpoint]
  (when (map? endpoint)
    (when-let [unknown-keys (seq (remove endpoint-keys (keys endpoint)))]
      (throw
       (typed-error
        :eacl/invalid-request
        (str (name operation) " received unknown object keys.")
        {:operation operation
         :reason :unknown-object-key
         :position position
         :unknown-keys (vec unknown-keys)
         :known-keys endpoint-keys}))))
  endpoint)

(defn- validate-public-object!
  [operation position object]
  (validate-endpoint-keys! operation position object)
  (when-not (and (map? object)
                 (keyword? (:type object))
                 (contains? object :id)
                 (some? (:id object))
                 (or (nil? (:relation object))
                     (keyword? (:relation object))))
    (throw
     (typed-error
      :eacl/invalid-request
      (str (name operation)
           " requires a typed public object with a non-nil ID.")
      {:operation operation
       :reason :invalid-object-shape
       :position position
       :value object})))
  object)

(defn- validate-keyword-fields!
  [operation request positions]
  (doseq [position positions
          :when (contains? request position)]
    (when-not (keyword? (get request position))
      (throw
       (typed-error
        :eacl/invalid-request
        (str (name operation) " requires keyword authorization names and types.")
        {:operation operation
         :reason :invalid-request-value
         :position position
         :value (get request position)}))))
  request)

(defn- validate-stable-page-basis!
  [operation request]
  (when (and (contains? request :page/basis)
             (not= :stable (:page/basis request)))
    (throw
     (typed-error
      :eacl.pagination/invalid-page-request
      ":page/basis currently supports only :stable."
      {:operation operation
       :reason :unsupported-page-basis
       :key :page/basis
       :value (:page/basis request)})))
  request)

(defn- validate-read-request!
  [operation request known-keys endpoint-positions keyword-positions]
  (validate-request-keys! operation request known-keys)
  (doseq [position endpoint-positions
          :when (contains? request position)
          :let [endpoint (get request position)]]
    (validate-public-object! operation position endpoint))
  (validate-keyword-fields! operation request keyword-positions)
  request)

(defn- validate-count-request!
  [operation request known-keys endpoint-positions keyword-positions]
  (when (map? request)
    (when-let [pagination-key
               (some #(when (contains? request %) %)
                     count-pagination-request-keys)]
      (throw
       (typed-error
        :eacl.pagination/invalid-page-request
        (str (name operation) " does not accept pagination fields.")
        {:operation operation :key pagination-key}))))
  (validate-read-request!
   operation request known-keys endpoint-positions keyword-positions))

(defn- validate-relationship-read-request!
  [request]
  (when-not (map? request)
    (throw
     (ex-info
      "read-relationships requires a filter map."
      {:type :eacl.filters/invalid-filter
       :eacl/error :eacl.filters/invalid-filter
       :value request})))
  (doseq [unsupported-key [:resource/id-prefix :subject/relation]]
    (when (contains? request unsupported-key)
      (throw
       (ex-info
        (str (pr-str unsupported-key)
             " is not supported by read-relationships.")
        {:eacl/error :eacl.pagination/unsupported-filter
         :filter unsupported-key}))))
  (when-let [unknown-keys
             (seq (remove relationship-read-request-keys (keys request)))]
    (throw
     (ex-info
      "read-relationships was passed unknown filter keys."
      {:eacl/error :eacl.filters/unknown-filter
       :unknown-keys (vec unknown-keys)})))
  (validate-stable-page-basis! :read-relationships request)
  request)

(defn- validate-sequential!
  [operation position value]
  (when-not (sequential? value)
    (throw
     (typed-error
      :eacl/invalid-request
      (str (name operation) " requires a sequential " (name position) ".")
      {:operation operation
       :reason :invalid-request-shape
       :position position
       :value value})))
  value)

(defn ^:no-doc validate-reader-request!
  "Validates the closed public shape for a reader protocol operation.

  Shared clients call this again inside protocol methods so direct protocol
  invocation cannot bypass wrapper validation or select a basis first."
  [operation request]
  (case operation
    :check-permission
    (validate-read-request!
     operation request point-request-keys [:subject :resource] [:permission])

    :read-schema
    (validate-request-keys! operation request read-schema-request-keys)

    :read-relationships
    (validate-relationship-read-request! request)

    :lookup-resources
    (do
      (validate-read-request!
       operation request lookup-resources-request-keys [:subject]
       [:permission :resource/type])
      (validate-stable-page-basis! operation request))

    :lookup-subjects
    (do
      (validate-read-request!
       operation request lookup-subjects-request-keys [:resource]
       [:permission :subject/type])
      (validate-stable-page-basis! operation request)
      (when (contains? request :subject/relation)
        (throw
         (ex-info
          ":subject/relation is not supported by lookup-subjects."
          {:eacl/error :eacl.pagination/unsupported-filter
           :filter :subject/relation})))
      request)

    :count-resources
    (validate-count-request!
     operation request count-resources-request-keys [:subject]
     [:permission :resource/type])

    :count-subjects
    (validate-count-request!
     operation request count-subjects-request-keys [:resource]
     [:permission :subject/type])

    :expand-permission-tree
    (validate-read-request!
     operation request permission-tree-request-keys [:resource] [:permission])

    (throw
     (typed-error
      :eacl/invalid-request
      "Unknown authorization reader operation."
      {:operation operation :reason :unknown-operation}))))

(defn- reader!
  [target]
  (if (satisfies? IAuthorizationReader target)
    target
    (throw
     (typed-error
      :eacl/invalid-authorization-target
      "Value is not an EACL authorization reader."
      {:target (target-kind target)}))))

(defn- writer!
  [target]
  (if (satisfies? IAuthorizationWriter target)
    target
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Authorization target does not support mutation."
      {:capability :write
       :target (target-kind target)}))))

(defn check-permission
  "Returns the canonical detailed authorization decision."
  ([target request]
   (validate-reader-request! :check-permission request)
   (-check-permission (reader! target) request))
  ([target subject permission resource]
   (check-permission target
                     {:subject subject
                      :permission permission
                      :resource resource}))
  ([target subject permission resource consistency]
   (check-permission target
                     {:subject subject
                      :permission permission
                      :resource resource
                      :consistency consistency})))

(defn can?
  "Returns true only for a definite grant. Authoritative qualified evaluation
   failures become false here; check-permission preserves their typed error.
   Cancellation, resource limits, invalid requests, and backend errors propagate."
  ([target request]
   (try
     (let [decision (check-permission target request)]
       (and (true? (:allowed? decision))
            (or (not (contains? decision :permissionship))
                (= :has-permission (:permissionship decision)))))
     (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) error
       (if (= :eacl.authorization/evaluation-failure (:type (ex-data error)))
         false
         (throw error)))))
  ([target subject permission resource]
   (can? target {:subject subject :permission permission :resource resource}))
  ([target subject permission resource consistency]
   (can? target {:subject subject :permission permission :resource resource
                 :consistency consistency})))

(defn read-schema
  ([target]
   (read-schema target {}))
  ([target request]
   (validate-reader-request! :read-schema request)
   (-read-schema (reader! target) request)))

(defn read-relationships
  [target request]
  (validate-reader-request! :read-relationships request)
  (-read-relationships (reader! target) request))

(defn lookup-resources
  [target request]
  (validate-reader-request! :lookup-resources request)
  (-lookup-resources (reader! target) request))

(defn lookup-subjects
  [target request]
  (validate-reader-request! :lookup-subjects request)
  (-lookup-subjects (reader! target) request))

(defn count-resources
  [target request]
  (validate-reader-request! :count-resources request)
  (-count-resources (reader! target) request))

(defn count-subjects
  [target request]
  (validate-reader-request! :count-subjects request)
  (-count-subjects (reader! target) request))

(defn expand-permission-tree
  [target request]
  (validate-reader-request! :expand-permission-tree request)
  (-expand-permission-tree (reader! target) request))

(defn write-schema!
  [target schema]
  (let [request (if (and (map? schema) (contains? schema :schema))
                  schema
                  {:schema schema})]
    (validate-request-keys!
     :write-schema! request #{:schema :orphan-policy})
    (-write-schema! (writer! target) request)))

(defn write-relationships!
  [target updates]
  (let [request (if (and (map? updates) (contains? updates :updates))
                  updates
                  {:updates updates})]
    (validate-request-keys!
     :write-relationships! request #{:updates :tx-data})
    (validate-sequential! :write-relationships! :updates (:updates request))
    (-write-relationships! (writer! target) request)))

(defn prepare-relationship!
  "Creates an inert qualifier for a Relationship and returns an opaque handle
   (nil for an ordinary Relationship). Pass it as :prepared-qualifier on the
   update supplied to tx-relationship. Preparation never grants access."
  [target relationship]
  (if (satisfies? IRelationshipPreparation target)
    (-prepare-relationship! target relationship)
    (throw (typed-error :eacl/unsupported-capability
                        "Target cannot prepare a qualified Relationship."
                        {:capability :prepare-relationship :target (target-kind target)}))))

(defn discard-prepared-relationship!
  "Removes an unchanged, unattached preparation through its original writer.
   Attached or altered qualifiers are rejected."
  [target prepared]
  (if (satisfies? IRelationshipPreparation target)
    (-discard-prepared-relationship! target prepared)
    (throw (typed-error :eacl/unsupported-capability
                        "Target cannot discard a Relationship preparation."
                        {:capability :discard-prepared-relationship :target (target-kind target)}))))

(defn delete-object!
  "Removes every Relationship touching one externally identified object.

  Numeric values remain public IDs and are never interpreted as backend
  entity IDs. Use `delete-object-by-eid!` for explicit ghost repair after an
  entity's public identity has already been retracted."
  [target object]
  (let [request (if (and (map? object) (contains? object :object))
                  object
                  {:object object})]
    (validate-request-keys! :delete-object! request #{:object})
    (validate-public-object! :delete-object! :object (:object request))
    (-delete-object! (writer! target) request)))

(defn delete-object-by-eid!
  "Removes every Relationship touching one explicit native entity ID.

  This is the ghost-repair counterpart to `delete-object!`: it is intended for
  cleanup after native entity deletion has made the public object ID
  unresolvable. It does not retract the entity itself."
  [target native-eid]
  (when-not (and (integer? native-eid) (pos? native-eid))
    (throw
     (typed-error
      :eacl/invalid-object-id
      "A native entity ID must be a positive integer."
      {:native-eid native-eid})))
  (-delete-object! (writer! target) {:native-eid native-eid}))

(defn write-relationship!
  ([target operation subject relation resource]
   (write-relationship!
    target {:operation operation
            :subject subject
            :relation relation
            :resource resource}))
  ([target update]
   (validate-request-keys!
    :write-relationship! update single-relationship-write-keys)
   (let [{:keys [operation subject relation resource]} update]
     (write-relationships!
      target
      [(->RelationshipUpdate
        operation
        (merge (->Relationship subject relation resource)
               (select-keys update [:caveat :caveat-context :valid-until-ms])))]))))

(defn with
  "Applies native transaction data in memory and returns an immutable,
  cache-safe speculative snapshot. `target` must be an EACL client or
  EACL-created snapshot; native database values are never accepted."
  [target tx-data]
  (if (satisfies? ISpeculativeAuthorization target)
    (do
      (validate-sequential! :with :tx-data tx-data)
      (-with target tx-data))
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Authorization target cannot create a speculative snapshot."
      {:capability :with
       :target (target-kind target)}))))

(defn with-schema
  "Prospectively replaces the permission schema without committing it."
  ([target schema]
   (with-schema target schema {}))
  ([target schema options]
   (if (satisfies? ISpeculativeAuthorization target)
     (do
       (validate-request-keys!
        :with-schema options #{:orphan-policy})
       (-with-schema target schema options))
     (throw
      (typed-error
       :eacl/unsupported-capability
       "Authorization target cannot create a speculative schema snapshot."
       {:capability :with-schema
        :target (target-kind target)})))))

(defn tx-relationships
  "Plans an atomic batch on one snapshot. Accepts updates or
   {:updates [...] :tx-data [...]} for application composition. Prepared
   backends require :prepared-qualifier handles on qualified updates."
  [snapshot request]
  (if (and (snapshot? snapshot) (satisfies? IRelationshipPlanning snapshot))
    (let [request (if (map? request) request {:updates request})]
      (validate-request-keys!
       :tx-relationships request #{:updates :tx-data})
      (when-not (contains? request :updates)
        (throw
         (typed-error
          :eacl/invalid-request
          "tx-relationships requires :updates in its request envelope."
          {:operation :tx-relationships
           :reason :missing-request-key
           :missing-key :updates})))
      (validate-sequential! :tx-relationships :updates (:updates request))
      (-tx-relationships snapshot request))
    (throw (typed-error :eacl/unsupported-capability
                        "Target cannot plan a Relationship batch."
                        {:capability :tx-relationships :target (target-kind snapshot)}))))

(defn tx-relationship
  "Plans one relationship mutation against an immutable EACL snapshot.

  The returned native transaction data uses the same paired relationship
  representation, commit guards, and relation-version stamps as the committed
  writer. It can be composed with application tx-data and passed to `with`."
  ([snapshot update]
   (if (and (snapshot? snapshot)
            (satisfies? ISpeculativeAuthorization snapshot))
     (-tx-relationship snapshot update)
     (throw
      (typed-error
       :eacl/unsupported-capability
       "Relationship transaction planning requires an EACL snapshot."
       {:capability :tx-relationship
        :target (target-kind snapshot)}))))
  ([snapshot operation subject relation resource]
   (tx-relationship
    snapshot
    (->RelationshipUpdate
     operation
     (->Relationship subject relation resource)))))

(defn speculative-diagnostics
  "Returns immutable warnings accumulated by a speculative snapshot."
  [snapshot]
  (if (and (snapshot? snapshot)
           (satisfies? ISpeculativeAuthorization snapshot))
    (-speculative-diagnostics snapshot)
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Diagnostics require an EACL speculative snapshot."
      {:capability :speculative-diagnostics
       :target (target-kind snapshot)}))))

(defn create-relationships!
  [target relationships]
  (validate-sequential! :create-relationships! :relationships relationships)
  (write-relationships!
   target
   (mapv #(->RelationshipUpdate :create %) relationships)))

(defn create-relationship!
  ([target relationship]
   (create-relationships! target [relationship]))
  ([target subject relation resource]
   (create-relationship!
    target (->Relationship subject relation resource))))

(defn- relationship-seq
  [relationships]
  (if (map? relationships)
    (do
      (when-not (contains? relationships :data)
        (throw
         (typed-error
          :eacl/invalid-request
          "delete-relationships! requires a relationship collection or a page containing :data."
          {:operation :delete-relationships!
           :reason :invalid-request-shape
           :value relationships})))
      (validate-sequential!
       :delete-relationships! :data (:data relationships)))
    (validate-sequential!
     :delete-relationships! :relationships relationships)))

(defn delete-relationships!
  [target relationships]
  (write-relationships!
   target
   (mapv #(->RelationshipUpdate :delete %)
         (relationship-seq relationships))))

(defn delete-relationship!
  ([target relationship]
   (delete-relationships! target [relationship]))
  ([target subject relation resource]
   (delete-relationship!
    target (->Relationship subject relation resource))))

(defn snapshot
  "Captures or selects one retained immutable snapshot from `target`."
  ([target]
   (snapshot target nil))
  ([target consistency]
   (if (satisfies? ISnapshotSource target)
     (-snapshot target consistency {})
     (throw
      (typed-error
       :eacl/unsupported-capability
       "Authorization target cannot select a snapshot."
       {:capability :snapshot
        :target (target-kind target)})))))

(defn release!
  "Idempotently releases an authorization snapshot."
  [target]
  (if (snapshot? target)
    (-release! target)
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Authorization target has no snapshot lifecycle."
      {:capability :release
       :target (target-kind target)}))))

(defn released?
  [target]
  (if (snapshot? target)
    (-released? target)
    false))

(defn basis
  [target]
  (if (snapshot? target)
    (-basis target)
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Authorization target has no immutable basis."
      {:capability :basis
       :target (target-kind target)}))))

(defn basis-token
  [target]
  (if (snapshot? target)
    (-basis-token target)
    (throw
     (typed-error
      :eacl/unsupported-capability
      "Authorization target has no immutable basis token."
      {:capability :basis-token
       :target (target-kind target)}))))

#?(:clj
   (defmacro with-snapshot
     "Binds a retained snapshot and releases it in `finally`."
     [[binding expression] & body]
     (when-not (symbol? binding)
       (throw (IllegalArgumentException.
               "with-snapshot requires a symbol binding.")))
     `(let [~binding ~expression]
        (try
          ~@body
          (finally
            (release! ~binding))))))

(defn check-permissions
  "Returns detailed authorization decisions for an ordered batch.

  `request` is a closed envelope containing `:checks` and optional
  request-wide `:consistency`, `:timeout-ms`, `:cancellation-token`, `:cache?`,
  `:populate-cache?`, `:evaluation`, and `:aggregate-limits`. A false
  `:populate-cache?` preserves cache lookup while suppressing publication.
  Implementations that cannot hold one immutable snapshot across the complete
  batch fail with a typed unsupported capability instead of looping over public
  scalar calls."
  [target request]
  (validate-request-keys!
   :check-permissions request
   #{:checks :caveat-context :consistency :timeout-ms :cancellation-token
     :cache? :populate-cache? :evaluation :aggregate-limits})
  (reader! target)
  (if (satisfies? IBatchedAuthorization target)
    (-check-permissions target request)
    (throw
     (typed-error
      :eacl/unsupported-capability
      "This authorization implementation has no batched point-check capability."
      {:capability :check-permissions
       :target (target-kind target)}))))

; Spice affordances from previous impl.
(defrecord Relationship [subject relation resource])
(defrecord RelationshipUpdate [operation relationship])

; Todo: move SpiceObject out of core impl to Spice-specific namespace.

(defrecord SpiceObject [type id relation]) ; where relation means subject_relation, which is distinct from Relationship.relation

(defn spice-object
  "Multi-arity helper for SubjectReference.
  Need a better name for this. Only used internally here."
  ([type id] (->SpiceObject type id nil))
  ([type id relation] (->SpiceObject type id relation)))
