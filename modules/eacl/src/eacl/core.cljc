(ns eacl.core
  "Defines the IAuthorization protocol, records & helpers."
  (:require [eacl.execution :as execution]))

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

(defprotocol IAuthorization
  ;; For order-dependent calls, we try to maintain the order of [subject permission resource].

  ;; Check Permissions
  ;; We support various arities for convenience.
  (can?
    [this subject permission resource]
    [this subject permission resource consistency]
    [this {:as demand :keys [subject permission resource consistency]}])

  ;; `can?` Example:
  ;
  ;    (can? client (->user "andre") :view (->server 456))
  ;    => true | false
  ;
  ; Omitted or nil consistency defaults to :minimize-latency. The positional
  ; consistency arity and the map arity accept every mode advertised by the
  ; configured backend.
  ; Unknown definitions and unknown permissions throw structured ex-info with
  ; :type :eacl/unknown-definition or
  ; :eacl/unknown-relation-or-permission. Missing object ids under valid schema
  ; names remain ordinary denials.
  ;
  ; Records used liberally to avoid typos in subject/object types.
  ; Accepts any map-like with {:keys [type id]}.

  ;; Schema
  (read-schema [this])
  (write-schema! [this schema])

  ;; Relationships
  (read-relationships [this query])
  ; where query is a map with the following keys (defprotocol does not support multiple :namespaced/keys):
  ; {:as            query
  ;  :keys          [first last after before]
  ;  :subject/keys  [type id]
  ;  :resource/keys [type id relation]}
  ;
  ; at least one anchor filter is required: :resource/type, :subject/type,
  ; :resource/relation, :subject/id or :resource/id. :subject/id requires a
  ; non-nil :subject/type. Unknown filter keys are rejected (a silently dropped
  ; filter would broaden the result set).
  ;
  ; :subject/relation and :resource/id-prefix are not supported and throw
  ; :eacl.pagination/unsupported-filter.
  ; A supplied unknown definition or relation throws the same structured
  ; schema-name errors used by permission operations.

  (write-relationships! [this updates])
  ; updates is a seq of RelationshipUpdate maps with {:keys [operation relationship]}, where
  ; operation is one of #{:create :touch :delete} and Relationship has {:keys [subject relation resource]}.
  ; Note :touch is like :create but does not throw if a relationship already exists.
  ; Repeating one operation for the same resolved relationship has the same
  ; outcome as one occurrence; mixing different operations for it in one batch
  ; is rejected before submit.

  (write-relationship!
    [this operation subject relation resource]
    [this {:as demand :keys [operation subject relation resource]}])

  (create-relationships! [this relationships])
  ; create-relationships! takes a seq of Relationship. Construct via ->Relationship, or use vector.

  (create-relationship!
    [this subject relation resource]
    [this {:as relationship :keys [subject relation resource]}])

  ; delete-relationships! takes the result of read-relationships, or
  ; construct a seq using ->Relationship.
  (delete-relationships! [this relationships])

  (delete-object! [this object])
  ; delete-object! removes every relationship touching `object` in both
  ; directions, including the halves stored on the peer entities. Datomic and
  ; Datahike retractEntity operations do NOT do this — v7 relationships name
  ; their peer inside a tuple value, which retractEntity does not follow — so
  ; retracting a permissioned entity without calling this first leaves
  ; relationship halves that keep answering authorization queries.
  ; Call it before retracting the entity. It does not retract the entity itself.

  (delete-relationship!
    [this subject relation resource]
    [this {:as relationship :keys [subject relation resource]}])

  ;; Subject & Resource & Enumeration
  (lookup-resources [this {:as query :keys [consistency]}])
  ; lookup-resources (formerly 'what-can?') accepts:
  ; - :resource/type – keyword, required.
  ; - :permission - keyword, required.
  ; - :subject has {:keys [type id]}. Required.
  ; - :first with optional :after for forward pagination.
  ; - :last with optional :before for backward pagination.
  ; - :cancellation-token optionally requests cooperative cancellation.
  ; Returns {:data [...] :page-info {:start-cursor ... :end-cursor ...
  ;                                  :has-next-page? ... :has-previous-page? ...}}.

  (count-resources [this {:as query :keys [consistency]}])
  ; counting can be slow because it enumerates the full lookup-resources result
  ; set. Pass :count-limit to bound work and receive :truncated? in the result.

  (lookup-subjects [this {:as query :keys [consistency]}])
  ; lookup-subjects (formerly 'who-can?') accepts:
  ; - :resource has {:keys [type id]}. Required.
  ; - :permission (keyword) required.
  ; - :subject/type (keyword) required.
  ; - :subject/relation is NOT supported and throws :eacl.pagination/unsupported-filter.
  ; - :first/:after or :last/:before pagination, as above.

  (count-subjects [this {:as query :keys [consistency]}])
  ; Mirrors count-resources for lookup-subjects. Pass :count-limit to bound
  ; work and receive :truncated? in the result.

  (expand-permission-tree [this {:as query :keys [resource permission consistency]}])
  ; expand-permission-tree accepts exactly :resource, :permission, optional
  ; :consistency, :timeout-ms and :cancellation-token. It returns
  ; {:expanded-at causal-token :tree-root PermissionRelationshipTree-map}.
  ; Every tree node has :expanded-object, :expanded-relation and exactly one
  ; of {:intermediate {:operation :union :children [...]}} or
  ; {:leaf {:subjects [...]}}. Child and subject vector order is non-semantic.
  ; Expansion is shallow: direct subjects remain terminal leaves.
  )

(defprotocol IDetailedAuthorization
  "Optional authorization extension for callers that need cache provenance.
  Omitted or nil consistency defaults to :minimize-latency."
  (-check-permission [this demand]))

(defprotocol IBatchedAuthorization
  "Authorization extension for ordered point checks over one snapshot."
  (-check-permissions [this request]))

(defprotocol ISnapshotAuthorization
  "Optional synchronous composition boundary for multiple read operations that
  must observe one selected immutable backend snapshot."
  (-with-snapshot [this consistency request-options f]))

(defn with-snapshot
  "Runs `f` synchronously with a read-only authorization view fixed to one
  selected immutable snapshot.

  The view must not escape `f`, rejects writes and cross-thread use, and fails
  deterministically after scope exit. Omitted consistency uses
  :minimize-latency. Backends that do not implement the lifecycle boundary fail
  with a typed unsupported-capability error.

  The four-argument form accepts one request-options map (for example
  :timeout-ms and :cancellation-token) whose execution budget covers snapshot
  selection and every nested read."
  ([authorization f]
   (with-snapshot authorization nil {} f))
  ([authorization consistency f]
   (with-snapshot authorization consistency {} f))
  ([authorization consistency request-options f]
   (when-not (map? request-options)
     (throw
      (ex-info
       "with-snapshot request options must be a map."
       {:type :eacl/invalid-snapshot-request-options
        :eacl/error :eacl/invalid-snapshot-request-options
        :value request-options})))
   (when-not (fn? f)
     (throw
      (ex-info
       "with-snapshot requires a synchronous callback."
       {:type :eacl/invalid-snapshot-callback
        :eacl/error :eacl/invalid-snapshot-callback})))
   (if (satisfies? ISnapshotAuthorization authorization)
     (-with-snapshot authorization consistency request-options f)
     (throw
      (ex-info
       "This authorization implementation has no composable snapshot lifecycle."
       {:type :eacl/unsupported-capability
        :eacl/error :eacl/unsupported-capability
        :capability :snapshot-composition})))))

(defn check-permission
  "Returns an authorization decision with cache provenance.

  Existing IAuthorization implementations remain compatible: implementations
  that do not opt into IDetailedAuthorization are evaluated through can? and
  reported as an uncached decision. Omitted or nil consistency defaults to
  :minimize-latency; fully consistent behavior must be requested explicitly."
  ([authorization demand]
   (if (satisfies? IDetailedAuthorization authorization)
     (-check-permission authorization demand)
     {:allowed? (can? authorization demand)
      :cached? false
      :cache-basis nil}))
  ([authorization subject permission resource]
   (check-permission authorization
                     {:subject subject
                      :permission permission
                      :resource resource}))
  ([authorization subject permission resource consistency]
   (check-permission authorization
                     {:subject subject
                      :permission permission
                      :resource resource
                      :consistency consistency})))

(defn check-permissions
  "Returns detailed authorization decisions for an ordered batch.

  `request` is a closed envelope containing `:checks` and optional
  request-wide `:consistency`, `:timeout-ms`, `:cancellation-token`, `:cache?`,
  `:evaluation`, and `:aggregate-limits`. Implementations that cannot hold one
  immutable snapshot across the complete batch fail with a typed unsupported
  capability instead of looping over public scalar calls."
  [authorization request]
  (if (satisfies? IBatchedAuthorization authorization)
    (-check-permissions authorization request)
    (throw
     (ex-info
      "This authorization implementation has no batched point-check capability."
      {:type :eacl/unsupported-capability
       :eacl/error :eacl/unsupported-capability
       :capability :check-permissions}))))

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
