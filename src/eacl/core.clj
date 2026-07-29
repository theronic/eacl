(ns eacl.core
  "Defines the IAuthorization protocol, records & helpers.")

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
  ; :resource/relation, :subject/id or :resource/id. Unknown filter keys are
  ; rejected (a silently dropped filter would broaden the result set).
  ;
  ; :subject/relation and :resource/id-prefix are not supported and throw
  ; :eacl.pagination/unsupported-filter.

  (write-relationships! [this updates])
  ; updates is a seq of RelationshipUpdate maps with {:keys [operation relationship]}, where
  ; operation is one of #{:create :touch :delete :unspecified} and Relationship has {:keys [subject relation resource]}.
  ; Note :touch is like :create but does not throw if a relationship already exists.

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
  ; directions, including the halves stored on the peer entities. Datomic's
  ; :db.fn/retractEntity does NOT do this — v7 relationships name their peer
  ; inside a tuple value, which retractEntity does not follow — so retracting a
  ; permissioned entity without calling this first leaves relationship halves
  ; that keep answering can?/lookup-resources/lookup-subjects.
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
	  ; Returns {:data [...] :page-info {:start-cursor ... :end-cursor ...
	  ;                                  :has-next-page? ... :has-previous-page? ...}}.

  (count-resources [this {:as query :keys [consistency]}])
  ; counting can be slow because it enumerates the full lookup-resources result set

  (lookup-subjects [this {:as query :keys [consistency]}])
	  ; lookup-subjects (formerly 'who-can?') accepts:
	  ; - :resource has {:keys [type id]}. Required.
	  ; - :permission (keyword) required.
	  ; - :subject/type (keyword) required.
	  ; - :subject/relation is NOT supported and throws :eacl.pagination/unsupported-filter.
	  ; - :first/:after or :last/:before pagination, as above.

  (expand-permission-tree [this {:as query :keys [resource permission consistency]}]))

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
