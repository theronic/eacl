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
   (-read-schema (reader! target) request)))

(defn read-relationships
  [target request]
  (-read-relationships (reader! target) request))

(defn lookup-resources
  [target request]
  (-lookup-resources (reader! target) request))

(defn lookup-subjects
  [target request]
  (-lookup-subjects (reader! target) request))

(defn count-resources
  [target request]
  (-count-resources (reader! target) request))

(defn count-subjects
  [target request]
  (-count-subjects (reader! target) request))

(defn expand-permission-tree
  [target request]
  (-expand-permission-tree (reader! target) request))

(defn write-schema!
  [target schema]
  (-write-schema! (writer! target)
                  (if (and (map? schema) (contains? schema :schema))
                    schema
                    {:schema schema})))

(defn write-relationships!
  [target updates]
  (-write-relationships! (writer! target)
                         (if (and (map? updates) (contains? updates :updates))
                           updates
                           {:updates updates})))

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
  [target object]
  (-delete-object! (writer! target)
                   (if (and (map? object) (contains? object :object))
                     object
                     {:object object})))

(defn write-relationship!
  ([target operation subject relation resource]
   (write-relationship!
    target {:operation operation
            :subject subject
            :relation relation
            :resource resource}))
  ([target {:keys [operation subject relation resource] :as update}]
   (write-relationships!
    target
    [(->RelationshipUpdate
      operation
      (merge (->Relationship subject relation resource)
             (select-keys update [:caveat :caveat-context :valid-until-ms])))])))

(defn with
  "Applies native transaction data in memory and returns an immutable,
  cache-safe speculative snapshot. `target` must be an EACL client or
  EACL-created snapshot; native database values are never accepted."
  [target tx-data]
  (if (satisfies? ISpeculativeAuthorization target)
    (-with target tx-data)
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
     (-with-schema target schema options)
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
    (-tx-relationships snapshot (if (map? request) request {:updates request}))
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
    (:data relationships)
    relationships))

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
